package ru.visits11.student;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Приложение студента: первый запуск — логин и пароль, дальше только QR-код.
 * QR генерирует ПК каждые 5 секунд — время телефона не используется,
 * поэтому спешащие/отстающие часы не ломают отметку. Снимок экрана бесполезен:
 * через ~10 секунд код перестаёт действовать.
 */
public final class MainActivity extends Activity {

    private static final String PREFS = "visits11student";
    private static final String KEY_LOGIN = "login";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_HOST = "host";
    private static final int PORT = 8090;
    private static final long POLL_MS = 5000;

    private static final int LOGIN_OK = 0;
    private static final int LOGIN_REJECTED = 1;
    private static final int LOGIN_NETWORK = 2;

    private static final int QR_OK = 0;
    private static final int QR_INACTIVE = 1; // перекличка не идёт
    private static final int QR_STALE = 2;    // сессия устарела — перевойдём
    private static final int QR_ERROR = 3;    // сети нет

    private View loginPanel;
    private EditText loginInput;
    private EditText passwordInput;
    private ImageView qrView;
    private TextView statusText;
    private View dot;

    private volatile boolean running;
    private Thread loopThread;
    private volatile String token;
    private volatile String host;
    private volatile byte[] lastPng;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // яркость на максимум — камере преподавателя проще считать код
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = 1f;
        getWindow().setAttributes(attributes);

        loginPanel = findViewById(R.id.loginPanel);
        loginInput = findViewById(R.id.loginInput);
        passwordInput = findViewById(R.id.passwordInput);
        qrView = findViewById(R.id.qrView);
        statusText = findViewById(R.id.statusText);
        dot = findViewById(R.id.dot);
        Button loginButton = findViewById(R.id.loginButton);

        loginButton.setOnClickListener(v -> {
            String login = loginInput.getText().toString().trim();
            String password = passwordInput.getText().toString();
            if (login.isEmpty() || password.isEmpty()) {
                toast("Введите логин и пароль");
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_LOGIN, login)
                    .putString(KEY_PASSWORD, password)
                    .apply();
            token = null;
            wake();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        running = true;
        if (loopThread == null) {
            loopThread = new Thread(this::loop, "Poll");
            loopThread.start();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        running = false;
        wake();
        loopThread = null;
    }

    private void wake() {
        Thread thread = loopThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    // ------------------------------------------------------------ главный цикл

    private void loop() {
        while (running) {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                String login = prefs.getString(KEY_LOGIN, null);
                String password = prefs.getString(KEY_PASSWORD, null);

                if (login == null || password == null) {
                    setStatus("Войдите по логину и паролю");
                    showLogin();
                    dotRed();
                    sleepQuietly(1000);
                    continue;
                }

                if (host == null) {
                    setStatus("Поиск ПК…");
                    host = findHost(prefs);
                }
                if (host == null) {
                    setStatus("Нет связи с ПК");
                    dotRed();
                    sleepQuietly(2000);
                    continue;
                }

                if (token == null) {
                    int code = login(host, login, password);
                    if (code == LOGIN_REJECTED) {
                        token = null;
                        setStatus("Неверный логин или пароль");
                        showLogin();
                        dotRed();
                        sleepQuietly(1500);
                        continue;
                    }
                    if (code != LOGIN_OK) {
                        host = null;
                        setStatus("Нет связи с ПК");
                        dotRed();
                        sleepQuietly(2000);
                        continue;
                    }
                }

                int qr = fetchQr(host, token);
                if (qr == QR_OK) {
                    showQr(lastPng);
                    dotGreen();
                } else if (qr == QR_INACTIVE) {
                    setStatus("Перекличка не начата");
                    dotGreen();
                } else if (qr == QR_STALE) {
                    token = null;
                    continue; // сразу перевойдём тем же логином
                } else {
                    host = null;
                    setStatus("Нет связи с ПК");
                    dotRed();
                }
                sleepQuietly(POLL_MS);
            } catch (Throwable ignored) {
                sleepQuietly(2000);
            }
        }
    }

    // ------------------------------------------------------------ сеть

    private int login(String server, String login, String password) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "http://" + server + ":" + PORT + "/api/login").openConnection();
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
                return LOGIN_NETWORK;
            }
            String response = readAll(connection.getInputStream(), 8192);
            if (!response.contains("\"ok\":true")) {
                return LOGIN_REJECTED;
            }
            String parsed = extractString(response, "token");
            if (parsed == null || parsed.isEmpty()) {
                return LOGIN_REJECTED;
            }
            token = parsed;
            return LOGIN_OK;
        } catch (IOException e) {
            return LOGIN_NETWORK;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private int fetchQr(String server, String session) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "http://" + server + ":" + PORT + "/api/qr?token=" + session).openConnection();
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            int code = connection.getResponseCode();
            if (code == 200) {
                byte[] png = readAll(connection.getInputStream(), 1024 * 1024);
                if (png.length == 0) {
                    return QR_ERROR;
                }
                lastPng = png;
                return QR_OK;
            }
            if (code == 204) {
                return QR_INACTIVE;
            }
            if (code == 401) {
                return QR_STALE;
            }
            return QR_ERROR;
        } catch (IOException e) {
            return QR_ERROR;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Поиск ПК: сначала сохранённый адрес, затем перебор своей подсети. */
    private String findHost(SharedPreferences prefs) {
        String cached = prefs.getString(KEY_HOST, null);
        if (cached != null && ping(cached)) {
            return cached;
        }
        List<String> targets = buildTargets();
        if (targets.isEmpty()) {
            return null;
        }
        ExecutorService pool = Executors.newFixedThreadPool(24);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (String target : targets) {
                futures.add(pool.submit(() -> ping(target) ? target : null));
            }
            for (Future<String> future : futures) {
                try {
                    String found = future.get(4, TimeUnit.SECONDS);
                    if (found != null) {
                        prefs.edit().putString(KEY_HOST, found).apply();
                        return found;
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return null;
    }

    private static boolean ping(String server) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "http://" + server + ":" + PORT + "/api/ping").openConnection();
            connection.setConnectTimeout(350);
            connection.setReadTimeout(350);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            return readAll(connection.getInputStream(), 512).contains("visits11-server");
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Адреса своей подсети /24 (кроме собственного). */
    private static List<String> buildTargets() {
        List<String> targets = new ArrayList<>();
        List<String> own = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)) {
                        continue;
                    }
                    String ip = address.getHostAddress();
                    int lastDot = ip.lastIndexOf('.');
                    if (lastDot < 0 || own.contains(ip)) {
                        continue;
                    }
                    own.add(ip);
                    String base = ip.substring(0, lastDot + 1);
                    for (int i = 1; i <= 254; i++) {
                        String candidate = base + i;
                        if (!own.contains(candidate)) {
                            targets.add(candidate);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return targets;
    }

    // ------------------------------------------------------------ утилиты

    private static String readAll(InputStream input, int cap) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while (output.size() < cap && (read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Значение строкового поля из простого JSON ответа. */
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

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ интерфейс

    private void showQr(byte[] png) {
        final Bitmap bitmap = png == null ? null : BitmapFactory.decodeByteArray(png, 0, png.length);
        ui.post(() -> {
            if (bitmap != null) {
                qrView.setImageBitmap(bitmap);
                qrView.setVisibility(View.VISIBLE);
                statusText.setVisibility(View.GONE);
                loginPanel.setVisibility(View.GONE);
            }
        });
    }

    private void setStatus(String text) {
        ui.post(() -> {
            statusText.setText(text);
            statusText.setVisibility(View.VISIBLE);
            qrView.setVisibility(View.GONE);
        });
    }

    private void showLogin() {
        ui.post(() -> loginPanel.setVisibility(View.VISIBLE));
    }

    private void dotGreen() {
        ui.post(() -> dot.setBackgroundResource(R.drawable.dot_green));
    }

    private void dotRed() {
        ui.post(() -> dot.setBackgroundResource(R.drawable.dot_red));
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
