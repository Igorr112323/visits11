package ru.visits11.student;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Приложение студента: первый запуск — логин и пароль, дальше открываешь
 * приложение, прикладываешь телефон к телефону преподавателя — отметка ставится.
 * NFC не нужен ни Wi-Fi, ни интернет. Телефонам без NFC — фолбэк Wi-Fi+QR:
 * код генерирует ПК каждые 5 секунд, снимок экрана бесполезен (~10 секунд жизни).
 */
public final class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final String PREFS = "visits11student";
    private static final String KEY_LOGIN = "login";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_HOST = "host";
    private static final String KEY_TOKEN = "token";
    private static final byte[] NFC_AID = {(byte) 0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04};
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

    /** true — NFC есть: отметка «телефон к телефону», сеть не нужна вовсе. */
    private boolean useNfc;

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
                    .remove(KEY_TOKEN)
                    .apply();
            token = null;
            if (useNfc) {
                updateNfcStatus();
            } else {
                wake();
            }
        });

        // смена студента — только долгим нажатием (чтобы не открыть случайно)
        statusText.setOnLongClickListener(v -> {
            loginPanel.setVisibility(View.VISIBLE);
            return true;
        });

        useNfc = nfcSupported();
        if (useNfc) {
            dot.setVisibility(View.INVISIBLE);
            updateNfcStatus();
        }
    }

    /** Уникальный ID устройства — аккаунт привязывается к первому телефону.</summary> */
    private String deviceId() {
        try {
            return Settings.Secure.ANDROID_ID;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** NFC в этом телефоне? */
    private boolean nfcSupported() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC)
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
                && NfcAdapter.getDefaultAdapter(this) != null;
    }

    /** Экран NFC-режима: вход выполнен — ждём прикладывания телефона. */
    private void updateNfcStatus() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean hasLogin = prefs.getString(KEY_LOGIN, null) != null;
        if (hasLogin) {
            statusText.setText("Приложите телефон к телефону преподавателя");
            statusText.setVisibility(View.VISIBLE);
            qrView.setVisibility(View.GONE);
            loginPanel.setVisibility(View.GONE);
        } else {
            loginPanel.setVisibility(View.VISIBLE);
            statusText.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (useNfc) {
            updateNfcStatus();
            return; // NFC-режим: сеть не нужна, работает даже с закрытым приложением
        }
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

    @Override
    protected void onResume() {
        super.onResume();
        if (!useNfc) {
            return;
        }
        try {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter != null) {
                adapter.enableReaderMode(this, this,
                        NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!useNfc) {
            return;
        }
        try {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter != null) {
                adapter.disableReaderMode(this);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Приложили телефон к телефону преподавателя: обмен и отметка. */
    @Override
    public void onTagDiscovered(Tag tag) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            return;
        }
        try {
            isoDep.connect();
            isoDep.setTimeout(5000);

            // выбор приложения телефона преподавателя
            byte[] select = new byte[5 + NFC_AID.length];
            select[1] = (byte) 0xA4;
            select[2] = 0x04;
            select[4] = (byte) NFC_AID.length;
            System.arraycopy(NFC_AID, 0, select, 5, NFC_AID.length);
            if (!apduOk(isoDep.transceive(select))) {
                return;
            }

            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String login = prefs.getString(KEY_LOGIN, null);
            String password = prefs.getString(KEY_PASSWORD, null);
            if (login == null || password == null) {
                return;
            }

            String device = deviceId();
            for (int attempt = 0; attempt < 2; attempt++) {
                String token = prefs.getString(KEY_TOKEN, null);
                byte[] identity = token != null
                        ? nfcCommand((byte) 2, token + "\n" + device)
                        : nfcCommand((byte) 1, login + "\n" + password + "\n" + device);
                byte[] answer = isoDep.transceive(identity);
                if (!apduOk(answer) || answer.length < 3) {
                    ui.post(() -> setStatus("Не получилось, попробуйте ещё раз"));
                    return;
                }
                int result = answer[0] & 0xFF;
                String value = new String(answer, 1, answer.length - 3, StandardCharsets.UTF_8);

                if (result == 0 && !value.isEmpty()) {
                    // вход выполнен — сохраняем токен, отметка уже ушла на ПК
                    prefs.edit().putString(KEY_TOKEN, value).apply();
                    vibrate();
                    ui.post(() -> setStatus("Готово! Вы отмечены"));
                    return;
                }
                if (result == 1) {
                    vibrate();
                    ui.post(() -> setStatus(value.isEmpty() ? "Вы отмечены" : "Вы отмечены — " + value));
                    return;
                }
                if (result == 3) {
                    // сессия устарела — этим же касанием перелогинимся
                    prefs.edit().remove(KEY_TOKEN).apply();
                    continue;
                }
                if (result == 5) {
                    ui.post(() -> setStatus("Аккаунт привязан к другому телефону"));
                    return;
                }
                if (result == 2) {
                    ui.post(() -> {
                        setStatus("Неверный логин или пароль");
                        loginPanel.setVisibility(View.VISIBLE);
                    });
                    return;
                }
                // 4: перекличка не идёт или ПК недоступен
                ui.post(() -> setStatus("Не получилось, попробуйте ещё раз"));
                return;
            }
        } catch (Throwable ignored) {
            ui.post(() -> setStatus("Не получилось, попробуйте ещё раз"));
        } finally {
            try {
                isoDep.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** APDU «вот кто я»: [type][данные]. */
    private static byte[] nfcCommand(byte type, String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        byte[] apdu = new byte[6 + bytes.length];
        apdu[1] = 0x10;
        apdu[4] = (byte) (1 + bytes.length);
        apdu[5] = type;
        System.arraycopy(bytes, 0, apdu, 6, bytes.length);
        return apdu;
    }

    private static boolean apduOk(byte[] response) {
        return response != null && response.length >= 2
                && (response[response.length - 2] & 0xFF) == 0x90
                && (response[response.length - 1] & 0xFF) == 0x00;
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
                    + "\",\"password\":\"" + escape(password)
                    + "\",\"device\":\"" + escape(deviceId()) + "\"}")
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
                byte[] png = readAllBytes(connection.getInputStream(), 1024 * 1024);
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

    /** Адреса своей Wi-Fi подсети /24 (кроме собственного) — строго локальная сеть. */
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
                // только Wi-Fi: мобильный интернет не используется вовсе
                String name = nic.getName().toLowerCase(Locale.ROOT);
                if (!name.startsWith("wlan") && !name.startsWith("wifi")
                        && !name.startsWith("ap") && !name.startsWith("swlan")) {
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
        return new String(readAllBytes(input, cap), StandardCharsets.UTF_8);
    }

    private static byte[] readAllBytes(InputStream input, int cap) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while (output.size() < cap && (read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
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
