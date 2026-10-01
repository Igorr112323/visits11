package ru.visits11.teacher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Камера преподавателя. Сам ничего не настраивает и не вводит:
 * слушает порт 8090 и отдаёт кадры ПК (Visits11 находит телефон сам).
 * Подсказывает включить «USB-модем», когда телефон подключён кабелем.
 */
public final class MainActivity extends Activity
        implements TextureView.SurfaceTextureListener, NfcAdapter.ReaderCallback {

    private static final int REQUEST_CAMERA = 1;
    private static final int SERVER_PORT = 8090;
    private static final long FRAME_WAIT_MS = 800;   // long-poll на /frame
    private static final long PC_TIMEOUT_MS = 3000;  // точка гаснет, если ПК давно не спрашивал
    private static final long ENCODE_INTERVAL_MS = 100; // не чаще ~10 кадров/с

    private TextureView preview;
    private View dot;
    private View promptPanel;

    private volatile SurfaceTexture surface;
    private volatile int frameRotation = 90;
    private int previewWidth;
    private int previewHeight;

    private Camera camera;
    private int cameraId;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private boolean cameraStarted;

    private ServerSocket serverSocket;
    private volatile boolean serverRunning;
    private Thread serverThread;

    /** последний готовый JPEG + счётчик кадров */
    private final Object frameLock = new Object();
    private byte[] latestJpeg;
    private long frameSeq;
    private long lastEncodeAt;
    private volatile long lastClientAt;

    /** Адрес ПК (приходит в заголовке X-Host запросов кадров) — для NFC-отметок. */
    private volatile String pcAddress = "";

    private static final byte[] NFC_AID = {(byte) 0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04};
    private static final Object NFC_LOCK = new Object();

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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preview = findViewById(R.id.preview);
        dot = findViewById(R.id.dot);
        promptPanel = findViewById(R.id.promptPanel);
        Button tetherButton = findViewById(R.id.tetherButton);

        preview.setSurfaceTextureListener(this);
        dot.setOnClickListener(v -> openTetherSettings());
        tetherButton.setOnClickListener(v -> openTetherSettings());

        startServer();
        ui.post(statusTicker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableNfcReader();
        if (hasCameraPermission()) {
            if (preview.isAvailable()) {
                startCamera();
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        disableNfcReader();
        stopCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(statusTicker);
        serverRunning = false;
        closeQuietly(serverSocket);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (preview.isAvailable()) {
                startCamera();
            }
        } else {
            toast("Нужен доступ к камере");
            finish();
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------ индикатор и подсказка

    private void updateStatus() {
        boolean pcConnected = serverRunning
                && SystemClock.elapsedRealtime() - lastClientAt < PC_TIMEOUT_MS;
        dot.setBackgroundResource(pcConnected ? R.drawable.dot_green : R.drawable.dot_red);
        promptPanel.setVisibility(pcConnected || isUsbTethered() ? View.GONE : View.VISIBLE);
    }

    /** Экраны с тумблером USB-модема на разных прошивках (пробуем по порядку). */
    private static final String[][] TETHER_SCREENS = {
            {"com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"},
            {"com.android.settings", "com.android.settings.Settings$TetherSettings"},
            {"com.android.settings", "com.android.settings.Settings$WirelessSettingsActivity"},
    };

    /**
     * Открывает сразу экран с тумблером «USB-модем» — искать ничего не нужно.
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

    // ------------------------------------------------------------ HTTP-сервер кадров

    private void startServer() {
        if (serverRunning) {
            return;
        }
        serverRunning = true;
        serverThread = new Thread(this::serverLoop, "FrameServer");
        serverThread.start();
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

            // ПК сообщает свой адрес — по нему пересылаем NFC-отметки
            String hostHeader = extractHeader(headers, "X-Host");
            if (hostHeader != null && !hostHeader.isEmpty()) {
                pcAddress = hostHeader;
            }
            lastClientAt = SystemClock.elapsedRealtime();

            // "GET /frame?since=0 HTTP/1.1"
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0];
            String target = parts[1];
            String path = target;
            String query = "";
            int queryAt = target.indexOf('?');
            if (queryAt >= 0) {
                path = target.substring(0, queryAt);
                query = target.substring(queryAt + 1);
            }

            if ("/info".equals(path)) {
                respond(client, "200 OK", "application/json",
                        "{\"app\":\"visits11-camera\"}".getBytes(StandardCharsets.US_ASCII), 0, 0);
            } else if ("/frame".equals(path) && "GET".equals(method)) {
                serveFrame(client, query);
            } else {
                respond(client, "404 Not Found", "text/plain",
                        new byte[0], 0, 0);
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(client);
        }
    }

    /** Отдаёт новый кадр (или ждёт его до FRAME_WAIT_MS); нет кадра — 204. */
    private void serveFrame(Socket client, String query) {
        long since = parseSince(query);

        byte[] jpeg = null;
        long seq = 0;
        long deadline = SystemClock.elapsedRealtime() + FRAME_WAIT_MS;
        synchronized (frameLock) {
            while (frameSeq == since) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) {
                    break;
                }
                try {
                    frameLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (frameSeq != since) {
                jpeg = latestJpeg;
                seq = frameSeq;
            }
        }

        if (jpeg == null) {
            respond(client, "204 No Content", "text/plain", new byte[0], 0, 0);
        } else {
            respond(client, "200 OK", "image/jpeg", jpeg, seq, frameRotation);
        }
    }

    private static long parseSince(String query) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "since".equals(pair.substring(0, eq))) {
                try {
                    return Long.parseLong(pair.substring(eq + 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

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

    private static void respond(Socket client, String status, String contentType,
                                byte[] body, long seq, int rotation) {
        try {
            byte[] head = ("HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + (seq > 0 ? "X-Seq: " + seq + "\r\n" : "")
                    + (rotation > 0 ? "X-Rot: " + rotation + "\r\n" : "")
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

    // ------------------------------------------------------------ камера

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        surface = surfaceTexture;
        if (hasCameraPermission()) {
            startCamera();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        surface = surfaceTexture;
        fitPreviewAspect();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        surface = null;
        stopCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
    }

    private void startCamera() {
        if (cameraStarted) {
            return;
        }
        cameraStarted = true;
        cameraThread = new HandlerThread("Camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraHandler.post(this::openCameraInternal);
    }

    private void openCameraInternal() {
        cameraId = findBackCamera();
        try {
            camera = Camera.open(cameraId);
        } catch (Exception e) {
            camera = null;
            toast("Не удалось открыть камеру");
            return;
        }
        try {
            Camera.Parameters params = camera.getParameters();

            // максимальное разрешение не больше 1280x720
            Camera.Size best = null;
            for (Camera.Size size : params.getSupportedPreviewSizes()) {
                if (size.width <= 1280 && size.height <= 720) {
                    if (best == null || size.width * size.height > best.width * best.height) {
                        best = size;
                    }
                }
            }
            if (best == null) {
                best = params.getSupportedPreviewSizes().get(0);
            }
            params.setPreviewSize(best.width, best.height);
            params.setPreviewFormat(ImageFormat.NV21);
            camera.setParameters(params);

            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);
            frameRotation = info.orientation;
            previewWidth = best.width;
            previewHeight = best.height;

            camera.setDisplayOrientation(90);
            runOnUiThread(this::fitPreviewAspect);

            if (surface != null) {
                camera.setPreviewTexture(surface);
            }
            int bufferBits = ImageFormat.getBitsPerPixel(ImageFormat.NV21);
            int bufferSize = best.width * best.height * bufferBits / 8;
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.setPreviewCallbackWithBuffer(this::onPreviewFrame);
            camera.startPreview();
        } catch (Exception e) {
            toast("Ошибка камеры");
            releaseCameraQuietly(camera);
            camera = null;
        }
    }

    /** Камера отдает NV21 → сжимаем в JPEG (не чаще 10 раз/с), ПК заберёт свежий. */
    private void onPreviewFrame(byte[] data, Camera c) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastEncodeAt >= ENCODE_INTERVAL_MS && previewWidth > 0) {
            lastEncodeAt = now;
            try {
                YuvImage yuv = new YuvImage(data, ImageFormat.NV21, previewWidth, previewHeight, null);
                ByteArrayOutputStream out = new ByteArrayOutputStream(48 * 1024);
                yuv.compressToJpeg(new Rect(0, 0, previewWidth, previewHeight), 70, out);
                byte[] jpeg = out.toByteArray();
                synchronized (frameLock) {
                    latestJpeg = jpeg;
                    frameSeq++;
                    frameLock.notifyAll();
                }
            } catch (Throwable ignored) {
            }
        }
        if (c != null) {
            c.addCallbackBuffer(data);
        }
    }

    private void stopCamera() {
        if (!cameraStarted) {
            return;
        }
        cameraStarted = false;
        final Camera current = camera;
        camera = null;
        if (cameraHandler != null) {
            cameraHandler.post(() -> releaseCameraQuietly(current));
        } else {
            releaseCameraQuietly(current);
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private static void releaseCameraQuietly(Camera camera) {
        if (camera == null) {
            return;
        }
        try {
            camera.setPreviewCallbackWithBuffer(null);
        } catch (Throwable ignored) {
        }
        try {
            camera.stopPreview();
        } catch (Throwable ignored) {
        }
        try {
            camera.release();
        } catch (Throwable ignored) {
        }
    }

    private int findBackCamera() {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return i;
            }
        }
        return 0;
    }

    /** Превью с сохранением пропорций (камера повёрнута на 90°). */
    private void fitPreviewAspect() {
        if (previewWidth <= 0 || previewHeight <= 0) {
            return;
        }
        View root = (View) preview.getParent();
        int containerWidth = root.getWidth();
        int containerHeight = root.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) {
            return;
        }
        float targetAspect = previewHeight / (float) previewWidth;
        int width;
        int height;
        if (containerWidth / (float) containerHeight > targetAspect) {
            height = containerHeight;
            width = Math.round(height * targetAspect);
        } else {
            width = containerWidth;
            height = Math.round(width / targetAspect);
        }
        android.widget.FrameLayout.LayoutParams params =
                (android.widget.FrameLayout.LayoutParams) preview.getLayoutParams();
        params.width = width;
        params.height = height;
        preview.setLayoutParams(params);
    }

    // ------------------------------------------------------------ NFC-отметки

    private void enableNfcReader() {
        try {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter != null) {
                adapter.enableReaderMode(this, this,
                        NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
            }
        } catch (Throwable ignored) {
        }
    }

    private void disableNfcReader() {
        try {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter != null) {
                adapter.disableReaderMode(this);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Студент приложил телефон: обмен по NFC, отметка через ПК, ответ студенту. */
    @Override
    public void onTagDiscovered(Tag tag) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            return;
        }
        try {
            synchronized (NFC_LOCK) {
                isoDep.connect();
                isoDep.setTimeout(5000);

                // выбор нашего приложения в телефоне студента
                byte[] select = new byte[5 + NFC_AID.length];
                select[1] = (byte) 0xA4;
                select[2] = 0x04;
                select[4] = (byte) NFC_AID.length;
                System.arraycopy(NFC_AID, 0, select, 5, NFC_AID.length);
                if (!apduOk(isoDep.transceive(select))) {
                    return;
                }

                // кто ты: логин+пароль (первый раз) или токен сессии
                byte[] answer = isoDep.transceive(new byte[]{0x00, 0x10, 0x00, 0x00, 0x00});
                if (!apduOk(answer)) {
                    return;
                }
                byte[] packet = payloadOf(answer);
                if (packet == null || packet.length < 1) {
                    return;
                }
                int type = packet[0] & 0xFF;
                String data = new String(packet, 1, packet.length - 1, StandardCharsets.UTF_8);

                NfcReply reply;
                if (type == 1) {
                    int split = data.indexOf('\n');
                    if (split <= 0) {
                        return;
                    }
                    reply = postLogin(data.substring(0, split), data.substring(split + 1));
                    if (reply.code == REPLY_LOGIN_TOKEN) {
                        // первое прикладывание отмечает сразу же
                        postMark(new String(reply.value, StandardCharsets.UTF_8));
                    }
                } else if (type == 2) {
                    reply = postMark(data);
                } else {
                    return;
                }

                byte[] result = new byte[1 + (reply.value == null ? 0 : reply.value.length)];
                result[0] = (byte) reply.code;
                if (reply.value != null) {
                    System.arraycopy(reply.value, 0, result, 1, reply.value.length);
                }
                byte[] command = new byte[5 + result.length];
                command[1] = 0x20;
                command[4] = (byte) result.length;
                System.arraycopy(result, 0, command, 5, result.length);

                if (apduOk(isoDep.transceive(command))
                        && (reply.code == REPLY_LOGIN_TOKEN || reply.code == REPLY_MARKED)) {
                    vibrate();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                isoDep.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final int REPLY_LOGIN_TOKEN = 0;  // + токен сессии
    private static final int REPLY_MARKED = 1;       // + имя студента
    private static final int REPLY_BAD_CREDENTIALS = 2;
    private static final int REPLY_RELOGIN = 3;      // токен устарел
    private static final int REPLY_UNAVAILABLE = 4;  // ПК недоступен / перекличка не идёт

    private static final class NfcReply {
        final int code;
        final byte[] value;

        NfcReply(int code, String value) {
            this.code = code;
            this.value = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private NfcReply postLogin(String login, String password) {
        String address = pcAddress;
        if (address == null || address.isEmpty()) {
            return new NfcReply(REPLY_UNAVAILABLE, null);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://" + address + "/api/login").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            byte[] body = ("{\"login\":\"" + escape(login)
                    + "\",\"password\":\"" + escape(password) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json");
            OutputStream output = connection.getOutputStream();
            output.write(body);
            output.flush();
            output.close();
            if (connection.getResponseCode() != 200) {
                return new NfcReply(REPLY_UNAVAILABLE, null);
            }
            String response = readAll(connection.getInputStream(), 8192);
            if (!response.contains("\"ok\":true")) {
                return new NfcReply(REPLY_BAD_CREDENTIALS, null);
            }
            String token = extractString(response, "token");
            if (token == null || token.isEmpty()) {
                return new NfcReply(REPLY_BAD_CREDENTIALS, null);
            }
            return new NfcReply(REPLY_LOGIN_TOKEN, token);
        } catch (IOException e) {
            return new NfcReply(REPLY_UNAVAILABLE, null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private NfcReply postMark(String token) {
        String address = pcAddress;
        if (address == null || address.isEmpty()) {
            return new NfcReply(REPLY_UNAVAILABLE, null);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://" + address + "/api/nfc_mark").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            byte[] body = ("{\"token\":\"" + escape(token) + "\"}").getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json");
            OutputStream output = connection.getOutputStream();
            output.write(body);
            output.flush();
            output.close();
            if (connection.getResponseCode() != 200) {
                return new NfcReply(REPLY_UNAVAILABLE, null);
            }
            String response = readAll(connection.getInputStream(), 8192);
            if (response.contains("\"ok\":true")) {
                String name = extractString(response, "name");
                return new NfcReply(REPLY_MARKED, name == null ? "" : name);
            }
            if (response.contains("\"relogin\":true")) {
                return new NfcReply(REPLY_RELOGIN, null);
            }
            return new NfcReply(REPLY_UNAVAILABLE, null);
        } catch (IOException e) {
            return new NfcReply(REPLY_UNAVAILABLE, null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean apduOk(byte[] response) {
        return response != null && response.length >= 2
                && (response[response.length - 2] & 0xFF) == 0x90
                && (response[response.length - 1] & 0xFF) == 0x00;
    }

    private static byte[] payloadOf(byte[] response) {
        if (response == null || response.length < 2) {
            return null;
        }
        return Arrays.copyOfRange(response, 0, response.length - 2);
    }

    private static String readAll(InputStream input, int cap) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while (output.size() < cap && (read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String extractString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return json.substring(start, end);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void vibrate() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ утилиты

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
