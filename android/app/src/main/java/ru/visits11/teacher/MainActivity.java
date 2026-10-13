package ru.visits11.teacher;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Телефон преподавателя: логотип КубГАУ на весь экран, экран не гаснет.
 * Держит связь с ПК только по USB-кабелю («USB-модем») — Wi-Fi не используется
 * вовсе: сервер слушает единственный адрес USB-интерфейса, а не всю сеть.
 * Через эту связь BLE-сервис (TeacherCardService) пересылает отметки Android-студентов,
 * а встроенный BLE-сервер — отметки iPhone при поднесении вплотную.
 */
public final class MainActivity extends Activity {

    private static final int SERVER_PORT = 8090;
    private static final long PC_TIMEOUT_MS = 6000;  // long-poll /event длится 3 с — запас в 2 раза
    private static final String SIGNATURE = "visits11-teacher";
    private static final int REQ_BLE = 77;

    /** UUID BLE-сервиса и характеристики для отметки iPhone при поднесении вплотную. */
    private static final UUID BLE_SERVICE_UUID =
            UUID.fromString("F0391101-0203-0405-0607-08090A0B0C0D");
    private static final UUID BLE_CHAR_UUID =
            UUID.fromString("F0391102-0203-0405-0607-08090A0B0C0D");
    private static final UUID BLE_CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private TextView statusText;

    private volatile ServerSocket serverSocket;
    private volatile InetAddress boundAddress;
    private volatile boolean serverRunning;

    private volatile long lastClientAt;

    // BLE-сервер для iPhone
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic markCharacteristic;
    private volatile boolean bleAdvertising;
    private volatile int txPower = 0;
    private final ConcurrentHashMap<String, ByteArrayOutputStream> bleBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> bleReplies = new ConcurrentHashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            ensureUsbBinding();
            ensureBleRunning();
            updateStatus();
            ui.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------------------ жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // экран не должен гаснуть во время пары
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        statusText = findViewById(R.id.statusText);
        // нажатие: выключенный BLE -> настройки BLE, выключенный Bluetooth -> включить Bluetooth,
        // мешает VPN -> настройки VPN, нет ПК -> USB-модем
        findViewById(R.id.root).setOnClickListener(v -> {
            if (!hasBlePermissions()) {
                requestBlePermissionsIfNeeded();
                return;
            }
            if (!isBluetoothEnabled()) {
                try {
                    startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                } catch (Throwable ignored) {
                    try {
                        startActivity(new Intent("android.settings.BLUETOOTH_SETTINGS"));
                    } catch (Throwable ignored2) {
                        toast("Включите Bluetooth в настройках");
                    }
                }
                return;
            }
            if (!pcConnected()) {
                if (usbAddress() != null && vpnActive()) {
                    openVpnSettings();
                } else {
                    openTetherSettings();
                }
            }
        });

        requestBlePermissionsIfNeeded();
        startServer();
        ui.post(statusTicker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(statusTicker);
        serverRunning = false;
        closeQuietly(serverSocket);
        stopBleServer();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE) {
            ensureBleRunning();
            updateStatus();
        }
    }

    // ------------------------------------------------------------ BLE для iPhone

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission("android.permission.BLUETOOTH_ADVERTISE") == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean isBluetoothEnabled() {
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
            return adapter != null && adapter.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void requestBlePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 && !hasBlePermissions()) {
            try {
                requestPermissions(new String[]{
                        "android.permission.BLUETOOTH_ADVERTISE",
                        "android.permission.BLUETOOTH_CONNECT"
                }, REQ_BLE);
            } catch (Throwable ignored) {
            }
        }
    }

    private void ensureBleRunning() {
        if (!isBluetoothEnabled() || !hasBlePermissions()) {
            if (bleAdvertising) {
                stopBleServer();
            }
            return;
        }
        if (bleAdvertising) {
            return;
        }
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            if (manager == null) return;
            BluetoothAdapter adapter = manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            BluetoothLeAdvertiser leAdvertiser = adapter.getBluetoothLeAdvertiser();
            if (leAdvertiser == null) return;

            BluetoothGattServer server = manager.openGattServer(this, gattCallback);
            if (server == null) return;

            BluetoothGattService service = new BluetoothGattService(
                    BLE_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                    BLE_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                            | BluetoothGattCharacteristic.PROPERTY_READ
                            | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                            | BluetoothGattCharacteristic.PERMISSION_READ);
            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                    BLE_CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            characteristic.addDescriptor(cccd);
            service.addCharacteristic(characteristic);
            server.addService(service);

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(txPower)
                    .setConnectable(true)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(BLE_SERVICE_UUID))
                    .build();

            gattServer = server;
            markCharacteristic = characteristic;
            advertiser = leAdvertiser;
            bleAdvertising = true;
            leAdvertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (Throwable ignored) {
            bleAdvertising = false;
        }
    }

    private void stopBleServer() {
        bleAdvertising = false;
        bleBuffers.clear();
        bleReplies.clear();
        try {
            if (advertiser != null) {
                advertiser.stopAdvertising(advertiseCallback);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (gattServer != null) {
                gattServer.close();
            }
        } catch (Throwable ignored) {
        }
        advertiser = null;
        gattServer = null;
        markCharacteristic = null;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            bleAdvertising = false;
        }
    };

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (device != null && newState == BluetoothGatt.STATE_DISCONNECTED) {
                String key = device.getAddress();
                bleBuffers.remove(key);
                bleReplies.remove(key);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (responseNeeded && gattServer != null) {
                try {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (responseNeeded && gattServer != null) {
                try {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                } catch (Throwable ignored) {
                }
            }
            if (device == null || value == null || !BLE_CHAR_UUID.equals(characteristic.getUuid())) {
                return;
            }
            String key = device.getAddress();
            ByteArrayOutputStream buffer = bleBuffers.computeIfAbsent(key, k -> new ByteArrayOutputStream());
            int startFrom = 0;
            if (value.length > 0 && value[0] == 1) {
                // 0x01 — начало нового пакета от iPhone
                buffer.reset();
                bleReplies.put(key, new byte[]{(byte) 0xFF});
                startFrom = 1;
            }
            int endAt = -1;
            for (int i = startFrom; i < value.length; i++) {
                if (value[i] == 0) {
                    endAt = i;
                    break;
                }
            }
            if (endAt < 0) {
                buffer.write(value, startFrom, value.length - startFrom);
                if (buffer.size() > 2048) {
                    buffer.reset();
                }
                return;
            }
            buffer.write(value, startFrom, endAt - startFrom);
            final String full = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            buffer.reset();

            String[] parts = full.split("\n", 2);
            if (parts.length < 1 || parts[0].isEmpty()) {
                return;
            }
            final String token = parts[0];
            final String studentDevice = parts.length > 1 ? parts[1] : "";
            bleReplies.put(key, new byte[]{(byte) 0xFF}); // 0xFF — запрос обрабатывается на ПК

            new Thread(() -> {
                byte[] reply = TeacherCardService.exchangeWithPc(token, studentDevice, MainActivity.this::vibrate);
                if (bleBuffers.get(key) != buffer) return;
                bleReplies.put(key, reply);
                // Both clients poll their per-device result. A shared characteristic notification
                // could race with another student's reply and incorrectly deliver code 2.
            }, "BleMark").start();
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (gattServer == null || device == null) return;
            try {
                byte[] reply = bleReplies.get(device.getAddress());
                if (reply == null) {
                    reply = new byte[]{(byte) 0xFF};
                }
                byte[] slice = offset >= reply.length
                        ? new byte[0]
                        : Arrays.copyOfRange(reply, offset, reply.length);
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice);
            } catch (Throwable ignored) {
            }
        }
    };

    /** Заметная двойная вибрация при успешной отметке iPhone (такая же, как при BLE). */
    private void vibrate() {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                vibrator = manager != null ? manager.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 90, 70, 90}, -1));
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ статус связи

    private boolean pcConnected() {
        return serverRunning && lastClientAt > 0
                && SystemClock.elapsedRealtime() - lastClientAt < PC_TIMEOUT_MS;
    }

    private void updateStatus() {
        if (!hasBlePermissions() || !isBluetoothEnabled()) {
            statusText.setText("Включите Bluetooth и разрешите доступ — нажмите сюда");
            statusText.setTextColor(0xFFB34A4A);
            return;
        }
        // 2. связь с ПК (ПК сам забирает отметки с телефона по кабелю)
        InetAddress usb = usbAddress();
        if (pcConnected()) {
            if (bleAdvertising) {
                statusText.setText("ПК подключён, Bluetooth готов (" + versionName() + ")");
            } else {
                statusText.setText("ПК подключён, Bluetooth недоступен ( " + versionName() + ")");
            }
            statusText.setTextColor(0xFF2E7D32);
        } else if (usb != null && vpnActive()) {
            statusText.setText("USB-модем включён (" + usb.getHostAddress() + "), v" + versionName()
                    + ".\nВыключите VPN на телефоне (нажмите сюда) и запустите «Журнал КубГАУ» на ПК");
            statusText.setTextColor(0xFFB34A4A);
        } else if (usb != null) {
            // адрес на экране: сразу видно, что USB-модем действительно работает
            statusText.setText("USB-модем включён (" + usb.getHostAddress() + "), v" + versionName()
                    + ".\nЗапустите «Журнал КубГАУ» на ПК");
            statusText.setTextColor(0xFF8A8A8A);
        } else {
            statusText.setText("Нет связи с ПК — нажмите, чтобы включить USB-модем");
            statusText.setTextColor(0xFFB34A4A);
        }
    }

    /** Версия приложения — видна на экране, чтобы понимать, какая сборка установлена. */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static final String[][] TETHER_SCREENS = {
            {"com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"},
            {"com.android.settings", "com.android.settings.Settings$TetherSettings"},
            {"com.android.settings", "com.android.settings.Settings$WirelessSettingsActivity"},
    };

    private void openVpnSettings() {
        try {
            startActivity(new Intent("android.net.vpn.SETTINGS"));
            return;
        } catch (Throwable ignored) {
        }
        openTetherSettings();
    }

    /**
     * Открывает сразу экран с тумблером «USB-модем».
     * Включить его за пользователя Android запрещает даже системным приложениям,
     * поэтому единственный переключатель делает сам пользователь.
     */
    private void openTetherSettings() {
        for (String[] screen : TETHER_SCREENS) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(screen[0], screen[1]));
                startActivity(intent);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            startActivity(new Intent("android.settings.TETHER_SETTINGS"));
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent("android.settings.WIRELESS_SETTINGS"));
        } catch (Throwable ignored) {
            toast("Откройте «USB-модем» в настройках");
        }
    }

    /**
     * Адрес USB-модема. Именно его слушает сервер: из Wi-Fi телефон недоступен вообще,
     * отметки ходят только по кабелю. Имя интерфейса у разных телефонов своё —
     * usb0, rndis0, ncm0, изредка eth0; Wi-Fi и мобильную сеть не слушаем никогда.
     */
    private InetAddress usbAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!isUsbInterface(nic.getName())) continue;
                if (!nic.isUp()) continue;
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Включён ли VPN на телефоне (туннель tun/ppp/wg может уводить пакеты от USB-кабеля). */
    private boolean vpnActive() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp()) continue;
                String name = nic.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("wg") || name.startsWith("tap")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** USB-модем это или посторонний интерфейс (Wi-Fi, мобильная сеть, точка доступа). */
    private static boolean isUsbInterface(String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        if (name.contains("usb") || name.contains("rndis") || name.contains("ncm")) {
            return true;
        }
        // на части телефонов USB-модем называется eth0/eth1 — но не мобильная сеть
        return name.startsWith("eth") && !name.contains("rmnet");
    }

    /** Адрес USB-модема изменился — пересоздаём сервер на новом адресе. */
    private void ensureUsbBinding() {
        InetAddress current = boundAddress;
        if (current != null && !current.equals(usbAddress())) {
            closeQuietly(serverSocket); // accept бросит исключение, цикл поднимет сокет заново
        }
    }

    // ------------------------------------------------------------ связь с ПК

    private void startServer() {
        if (serverRunning) {
            return;
        }
        serverRunning = true;
        new Thread(this::serverLoop, "PcLink").start();
    }

    private void serverLoop() {
        while (serverRunning) {
            InetAddress address = usbAddress();
            if (address == null) {
                // кабеля нет — Wi-Fi не слушаем вовсе
                sleepQuietly(400);
                continue;
            }
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(address, SERVER_PORT), 16);
                serverSocket = socket;
                boundAddress = address;
            } catch (IOException e) {
                boundAddress = null;
                sleepQuietly(400);
                continue;
            }

            try {
                while (serverRunning) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client), "Http").start();
                }
            } catch (IOException e) {
                // сокет закрыли (сменился адрес) или сервер останавливают
            } finally {
                closeQuietly(serverSocket);
                serverSocket = null;
                boundAddress = null;
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(4000);
            String headers = readHeaders(client.getInputStream());
            if (headers == null) {
                return;
            }
            int lineEnd = headers.indexOf("\r\n");
            String requestLine = lineEnd < 0 ? headers : headers.substring(0, lineEnd);

            lastClientAt = SystemClock.elapsedRealtime();

            // "GET /event?wait=3000 HTTP/1.1"
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0];
            String path = parts[1];
            int queryAt = path.indexOf('?');
            if (queryAt >= 0) {
                path = path.substring(0, queryAt);
            }

            if ("/power".equals(path) && "GET".equals(method)) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("level=([0-3])(?:&| |$)").matcher(requestLine);
                if (!m.find()) { respond(client, "400 Bad Request", "text/plain", new byte[0]); return; }
                int level = Integer.parseInt(m.group(1));
                ui.post(() -> { if (txPower != level) { txPower = level; stopBleServer(); ensureBleRunning(); } });
                respond(client, "200 OK", "text/plain", new byte[0]);
            } else if ("/info".equals(path)) {
                respond(client, "200 OK", "application/json",
                        ("{\"app\":\"" + SIGNATURE + "\",\"legacy\":\"visits11-camera\"}").getBytes(StandardCharsets.US_ASCII));
            } else if ("/event".equals(path) && "GET".equals(method)) {
                // ПК забирает BLE-запросы (long-poll 3 сек)
                serveEvent(client);
                lastClientAt = SystemClock.elapsedRealtime();
            } else if ("/event_result".equals(path) && "POST".equals(method)) {
                // ПК возвращает ответ на касание
                acceptEventResult(client, headers);
            } else if ("/frame".equals(path) && "GET".equals(method)) {
                // совместимость со старыми версиями ПК
                sleepQuietly(800);
                lastClientAt = SystemClock.elapsedRealtime();
                respond(client, "204 No Content", "text/plain", new byte[0]);
            } else {
                respond(client, "404 Not Found", "text/plain", new byte[0]);
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(client);
        }
    }

    /** Отдаёт ПК ожидающий BLE-запрос (или 204, если касаний не было). */
    private static void serveEvent(Socket client) {
        String request = null;
        try {
            request = TeacherCardService.requests.poll(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (request == null) {
            respond(client, "204 No Content", "text/plain", new byte[0]);
        } else {
            respond(client, "200 OK", "application/json", request.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Принимает ответ ПК на касание и передаёт его ожидающему студенту. */
    private static void acceptEventResult(Socket client, String headers) {
        try {
            int length = 0;
            String declared = extractHeader(headers, "Content-Length");
            if (declared != null) {
                length = Integer.parseInt(declared.trim());
            }
            if (length <= 0 || length > 8192) {
                respond(client, "400 Bad Request", "text/plain", new byte[0]);
                return;
            }
            byte[] body = new byte[length];
            InputStream input = client.getInputStream();
            int filled = 0;
            while (filled < length) {
                int read = input.read(body, filled, length - filled);
                if (read < 0) {
                    break;
                }
                filled += read;
            }
            if (filled > 0) {
                TeacherCardService.resultBox.offer(
                        new String(body, 0, filled, StandardCharsets.UTF_8),
                        1, TimeUnit.SECONDS);
            }
            respond(client, "200 OK", "text/plain", new byte[0]);
        } catch (Throwable ignored) {
            try {
                respond(client, "500 Server Error", "text/plain", new byte[0]);
            } catch (Throwable ignored2) {
            }
        }
    }

    // ------------------------------------------------------------ утилиты

    /** Читает заголовки до \r\n\r\n целиком. */
    private static String readHeaders(InputStream input) throws IOException {
        byte[] head = new byte[4096];
        int len = 0;
        while (true) {
            if (len >= head.length) {
                return null;
            }
            int b = input.read();
            if (b < 0) {
                return null;
            }
            head[len++] = (byte) b;
            if (len >= 4
                    && head[len - 1] == '\n' && head[len - 2] == '\r'
                    && head[len - 3] == '\n' && head[len - 4] == '\r') {
                break;
            }
        }
        return new String(head, 0, len, StandardCharsets.US_ASCII);
    }

    private static String extractHeader(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static void respond(Socket client, String status, String contentType, byte[] body) {
        try {
            byte[] head = ("HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            // заголовки и тело — ОДНИМ пакетом, чтобы клиент не потерял половину
            byte[] packet = new byte[head.length + body.length];
            System.arraycopy(head, 0, packet, 0, head.length);
            System.arraycopy(body, 0, packet, head.length, body.length);
            OutputStream output = client.getOutputStream();
            output.write(packet);
            output.flush();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
