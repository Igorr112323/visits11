package ru.visits11.teacher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Телефон преподавателя: логотип КубГАУ на весь экран, экран не гаснет.
 * Держит связь с ПК только по USB-кабелю («USB-модем») — Wi-Fi не используется
 * вовсе: сервер слушает единственный адрес USB-интерфейса, а не всю сеть.
 * Через эту связь NFC-сервис (TeacherCardService) пересылает отметки студентов на ПК.
 * Нет связи с ПК — нажмите на экран, откроется тумблер «USB-модем».
 */
public final class MainActivity extends Activity {

    private static final int SERVER_PORT = 8090;
    private static final long PC_TIMEOUT_MS = 3000;  // связь считается потерянной
    private static final String SIGNATURE = "visits11-teacher";

    private TextView statusText;

    private volatile ServerSocket serverSocket;
    private volatile InetAddress boundAddress;
    private volatile boolean serverRunning;

    private volatile long lastClientAt;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            ensureUsbBinding();
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
        ui.post(statusTicker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(statusTicker);
        serverRunning = false;
        closeQuietly(serverSocket);
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
            return;
        }
        if (!adapter.isEnabled()) {
            statusText.setText("NFC ВЫКЛЮЧЕН — нажмите, чтобы включить");
            statusText.setTextColor(0xFFB34A4A);
            return;
        }

        // 2. связь с ПК (ПК сам забирает отметки с телефона по кабелю)
        if (pcConnected()) {
            statusText.setText("ПК подключён, NFC готов");
            statusText.setTextColor(0xFF2E7D32);
        } else if (usbAddress() != null) {
            statusText.setText("Кабель подключён — запустите Журнал на ПК");
            statusText.setTextColor(0xFF8A8A8A);
        } else {
            statusText.setText("Нет связи с ПК — нажмите, чтобы включить USB-модем");
            statusText.setTextColor(0xFFB34A4A);
        }
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
     * Адрес USB-модема (usb0/rndis0). Именно его слушает сервер: из Wi-Fi телефон
     * недоступен вообще, отметки ходят только по кабелю.
     */
    private InetAddress usbAddress() {
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
                        return address;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
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
                serverSocket = new ServerSocket(SERVER_PORT, 8, address);
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

            if ("/info".equals(path)) {
                respond(client, "200 OK", "application/json",
                        ("{\"app\":\"" + SIGNATURE + "\"}").getBytes(StandardCharsets.US_ASCII));
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
