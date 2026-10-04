package ru.visits11.teacher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Телефон преподавателя: логотип КубГАУ на весь экран, экран не гаснет.
 * Невидимо держит связь с ПК по USB (ПК сам находит телефон) — через неё
 * NFC-сервис (TeacherCardService) пересылает отметки студентов на ПК.
 * Нет связи с ПК — нажмите на экран, откроется тумблер «USB-модем».
 */
public final class MainActivity extends Activity {

    private static final int SERVER_PORT = 8090;
    private static final long FRAME_WAIT_MS = 800;
    private static final long PC_TIMEOUT_MS = 3000;
    private static final UUID TAP_SERVICE_ID = UUID.fromString("F0391101-0203-4000-8000-00805F9B34FB");
    private static final UUID IDENTITY_CHARACTERISTIC_ID = UUID.fromString("F0391102-0203-4000-8000-00805F9B34FB");
    private static final UUID MARK_CHARACTERISTIC_ID = UUID.fromString("F0391103-0203-4000-8000-00805F9B34FB");
    private static final int BLE_PERMISSION_REQUEST = 41;

    private TextView statusText;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private volatile boolean bleScanning;
    private volatile String tapFeedback = "";
    private volatile long tapFeedbackAt;
    private final Set<String> connectingDevices = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> lastTapByDevice = Collections.synchronizedMap(new HashMap<>());
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            connectToStudent(result.getDevice());
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) connectToStudent(result.getDevice());
        }

        @Override
        public void onScanFailed(int errorCode) {
            bleScanning = false;
            toast("BLE-сканирование не запустилось: " + errorCode);
        }
    };

    private ServerSocket serverSocket;
    private volatile boolean serverRunning;

    private volatile long lastClientAt;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
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
        // нажатие: выключенный NFC -> настройки NFC, нет ПК -> тумблер USB-модема
        findViewById(R.id.root).setOnClickListener(v -> {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter != null && !adapter.isEnabled()) {
                try {
                    startActivity(new Intent("android.settings.NFC_SETTINGS"));
                } catch (Throwable ignored) {
                    toast("Включите NFC в настройках");
                }
                return;
            }
            if (!pcConnected()) {
                openTetherSettings();
            }
        });

        startServer();
        ensureBlePermissions();
        ui.post(statusTicker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(statusTicker);
        stopBleScan();
        serverRunning = false;
        closeQuietly(serverSocket);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBleScan();
    }

    @Override
    protected void onPause() {
        stopBleScan();
        super.onPause();
    }

    private void ensureBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            List<String> missing = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), BLE_PERMISSION_REQUEST);
        } else if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, BLE_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BLE_PERMISSION_REQUEST) startBleScan();
    }

    private boolean blePermissionsGranted() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startBleScan() {
        if (bleScanning || !blePermissionsGranted()) return;
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = manager == null ? null : manager.getAdapter();
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                tapFeedback = "Включите Bluetooth для касания iPhone";
                tapFeedbackAt = SystemClock.elapsedRealtime();
                return;
            }
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bleScanner == null) return;
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(TAP_SERVICE_ID)).build();
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            bleScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            bleScanning = true;
        } catch (SecurityException | IllegalStateException ignored) {
            bleScanning = false;
        }
    }

    private void stopBleScan() {
        if (!bleScanning || bleScanner == null) return;
        try {
            bleScanner.stopScan(scanCallback);
        } catch (SecurityException | IllegalStateException ignored) {
        }
        bleScanning = false;
    }

    private void connectToStudent(BluetoothDevice device) {
        if (device == null || !blePermissionsGranted()) return;
        String address;
        try {
            address = device.getAddress();
        } catch (SecurityException ignored) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Long last = lastTapByDevice.get(address);
        if (connectingDevices.contains(address) || last != null && now - last < 5000) return;
        connectingDevices.add(address);
        lastTapByDevice.put(address, now);
        final String[] studentName = {"Студент"};
        try {
            device.connectGatt(this, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        if (!gatt.requestMtu(247)) gatt.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                        closeGatt(gatt, address);
                    }
                }

                @Override
                public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                    gatt.discoverServices();
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        closeGatt(gatt, address);
                        return;
                    }
                    BluetoothGattService service = gatt.getService(TAP_SERVICE_ID);
                    BluetoothGattCharacteristic identity = service == null ? null : service.getCharacteristic(IDENTITY_CHARACTERISTIC_ID);
                    if (identity == null || !gatt.readCharacteristic(identity)) closeGatt(gatt, address);
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                    if (characteristic.getUuid().equals(IDENTITY_CHARACTERISTIC_ID) && status == BluetoothGatt.GATT_SUCCESS) {
                        try {
                            JSONObject identity = new JSONObject(new String(characteristic.getValue(), StandardCharsets.UTF_8));
                            String studentKey = identity.optString("studentKey", "");
                            String deviceId = identity.optString("deviceId", "");
                            studentName[0] = identity.optString("fullName", "Студент");
                            if (!studentKey.matches("[0-9a-fA-F]{32}") || deviceId.isEmpty()) {
                                closeGatt(gatt, address);
                                return;
                            }
                            BluetoothGattService service = gatt.getService(TAP_SERVICE_ID);
                            BluetoothGattCharacteristic mark = service == null ? null : service.getCharacteristic(MARK_CHARACTERISTIC_ID);
                            if (mark == null || !writeMark(gatt, mark)) closeGatt(gatt, address);
                        } catch (Exception ignored) {
                            closeGatt(gatt, address);
                        }
                    }
                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                    if (characteristic.getUuid().equals(MARK_CHARACTERISTIC_ID) && status == BluetoothGatt.GATT_SUCCESS) {
                        completeBleTap(studentName[0]);
                        closeGatt(gatt, address);
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException | IllegalArgumentException ignored) {
            connectingDevices.remove(address);
        }
    }

    private boolean writeMark(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        byte[] value = "MARK".getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
            }
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            characteristic.setValue(value);
            return gatt.writeCharacteristic(characteristic);
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private void closeGatt(BluetoothGatt gatt, String address) {
        try {
            gatt.disconnect();
            gatt.close();
        } catch (SecurityException ignored) {
        }
        connectingDevices.remove(address);
    }

    private void completeBleTap(String name) {
        tapFeedback = "BLE-касание: " + name + " — отметка принята";
        tapFeedbackAt = SystemClock.elapsedRealtime();
        vibrate();
        toast("Отметка принята: " + name);
        ui.post(this::updateStatus);
    }

    private void vibrate() {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator())
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 70, 50, 70}, -1));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ статус связи

    private boolean pcConnected() {
        return serverRunning && SystemClock.elapsedRealtime() - lastClientAt < PC_TIMEOUT_MS;
    }

    private void updateStatus() {
        // 1. NFC — без него касания не работают вовсе
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        if (adapter == null) {
            statusText.setText("NFC не поддерживается этим телефоном");
            statusText.setTextColor(0xFFB34A4A);
            appendTapFeedback();
            return;
        }
        if (!adapter.isEnabled()) {
            statusText.setText("NFC ВЫКЛЮЧЕН — нажмите, чтобы включить");
            statusText.setTextColor(0xFFB34A4A);
            appendTapFeedback();
            return;
        }

        // 2. связь с ПК (ПК сам забирает отметки с телефона — брандмауэр не мешает)
        if (pcConnected()) {
            statusText.setText("ПК подключён, NFC готов");
            statusText.setTextColor(0xFF2E7D32);
        } else if (isUsbTethered()) {
            statusText.setText("Кабель подключён — запустите Visits11 на ПК");
            statusText.setTextColor(0xFF8A8A8A);
        } else {
            statusText.setText("Нет связи с ПК — нажмите, чтобы включить USB-модем");
            statusText.setTextColor(0xFFB34A4A);
        }
        appendTapFeedback();
    }

    private void appendTapFeedback() {
        if (!tapFeedback.isEmpty() && SystemClock.elapsedRealtime() - tapFeedbackAt < 5000)
            statusText.setText(statusText.getText() + "\n" + tapFeedback);
    }

    private static final String[][] TETHER_SCREENS = {
            {"com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"},
            {"com.android.settings", "com.android.settings.Settings$TetherSettings"},
            {"com.android.settings", "com.android.settings.Settings$WirelessSettingsActivity"},
    };

    /**
     * Открывает сразу экран с тумблером «USB-модем».
     * Включить его за пользователя Android запрещает даже системным приложениям,
     * поэтому единственный переключатель делает сам пользователь.
     */
    private void openTetherSettings() {
        for (String[] screen : TETHER_SCREENS) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new android.content.ComponentName(screen[0], screen[1]));
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

    /** Появилась ли сеть USB-модема (usb0/rndis0). */
    private boolean isUsbTethered() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                String name = nic.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("usb") && !name.contains("rndis")) continue;
                if (!nic.isUp()) continue;
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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
        try {
            serverSocket = new ServerSocket(SERVER_PORT);
        } catch (IOException e) {
            serverRunning = false;
            toast("Не удалось занять порт 8090");
            return;
        }
        while (serverRunning) {
            try {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client), "Http").start();
            } catch (IOException e) {
                break;
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

            // "GET /frame?since=0 HTTP/1.1"
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

            if ("/info".equals(path)) {
                respond(client, "200 OK", "application/json",
                        "{\"app\":\"visits11-camera\"}".getBytes(StandardCharsets.US_ASCII));
            } else if ("/frame".equals(path) && "GET".equals(method)) {
                // кадров больше нет: немного ждём, чтобы ПК не крутил запросы вхолостую
                Thread.sleep(FRAME_WAIT_MS);
                respond(client, "204 No Content", "text/plain", new byte[0]);
            } else if ("/event".equals(path) && "GET".equals(method)) {
                // ПК забирает NFC-запросы (long-poll 3 сек)
                serveEvent(client);
            } else if ("/event_result".equals(path) && "POST".equals(method)) {
                // ПК возвращает ответ на касание
                acceptEventResult(client, headers);
            } else {
                respond(client, "404 Not Found", "text/plain", new byte[0]);
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(client);
        }
    }

    /** Отдаёт ПК ожидающий NFC-запрос (или 204, если касаний не было). */
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
                TeacherCardService.resultBox.offer(new String(body, 0, filled, StandardCharsets.UTF_8));
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

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
