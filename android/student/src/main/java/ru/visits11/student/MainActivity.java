package ru.visits11.student;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final String PREFS = "visits11student";
    private static final String KEY_STUDENT_KEY = "student_key";
    private static final String KEY_FULL_NAME = "full_name";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final byte[] NFC_AID = {(byte) 0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private View loginPanel;
    private TextView statusText;
    private boolean useNfc;
    private boolean scannerOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        loginPanel = findViewById(R.id.loginPanel);
        statusText = findViewById(R.id.statusText);
        Button scanButton = findViewById(R.id.loginButton);
        scanButton.setOnClickListener(view -> startQrScan());
        statusText.setOnLongClickListener(view -> {
            loginPanel.setVisibility(View.VISIBLE);
            startQrScan();
            return true;
        });
        statusText.setOnClickListener(view -> {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter != null && !adapter.isEnabled()) {
                try {
                    startActivity(new Intent("android.settings.NFC_SETTINGS"));
                } catch (Throwable ignored) {
                    toast("Включите NFC в настройках телефона");
                }
            }
        });

        useNfc = nfcSupported();
        updateStudentStatus();
        if (!hasStudent()) ui.postDelayed(this::startQrScan, 450);
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateStudentStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!useNfc || !hasStudent()) return;
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
        try {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter != null) adapter.disableReaderMode(this);
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    private boolean hasStudent() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STUDENT_KEY, null) != null;
    }

    private String deviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_DEVICE_ID, null);
        if (saved != null && !saved.isEmpty()) return saved;
        String actual;
        try {
            actual = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
            actual = null;
        }
        if (actual == null || actual.isEmpty()) actual = java.util.UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, actual).apply();
        return actual;
    }

    private void startQrScan() {
        if (scannerOpen) return;
        scannerOpen = true;
        IntentIntegrator scanner = new IntentIntegrator(this);
        scanner.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        scanner.setPrompt("Отсканируйте персональный QR-код КубГАУ");
        scanner.setBeepEnabled(false);
        scanner.setOrientationLocked(false);
        scanner.initiateScan();
    }

    private boolean saveQrPayload(String raw) {
        try {
            JSONObject payload = new JSONObject(raw);
            if (payload.optInt("version") != 1) return false;
            String studentKey = payload.optString("studentKey", "").trim();
            String fullName = payload.optString("fullName", "").trim();
            String serverUrl = payload.optString("serverUrl", "").trim();
            if (!studentKey.matches("[0-9a-fA-F]{32}") || fullName.isEmpty()) return false;
            Uri uri = Uri.parse(serverUrl);
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost() == null) return false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_STUDENT_KEY, studentKey)
                    .putString(KEY_FULL_NAME, fullName)
                    .putString(KEY_SERVER_URL, serverUrl)
                    .apply();
            deviceId();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            scannerOpen = false;
            if (result != null && result.getContents() != null && saveQrPayload(result.getContents())) {
                updateStudentStatus();
                toast("Вход выполнен");
            } else if (!hasStudent()) {
                loginPanel.setVisibility(View.VISIBLE);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private boolean nfcSupported() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC)
                && NfcAdapter.getDefaultAdapter(this) != null;
    }

    private void updateStudentStatus() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String name = prefs.getString(KEY_FULL_NAME, "");
        if (prefs.getString(KEY_STUDENT_KEY, null) == null) {
            loginPanel.setVisibility(View.VISIBLE);
            statusText.setVisibility(View.GONE);
            return;
        }
        loginPanel.setVisibility(View.GONE);
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(!useNfc
                ? name + "\nНа этом телефоне нет NFC"
                : NfcAdapter.getDefaultAdapter(this) != null && !NfcAdapter.getDefaultAdapter(this).isEnabled()
                ? name + "\nВключите NFC в настройках телефона"
                : name + "\nПриложите телефон к телефону преподавателя");
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) return;
        try {
            isoDep.connect();
            isoDep.setTimeout(5000);
            byte[] select = new byte[5 + NFC_AID.length];
            select[1] = (byte) 0xA4;
            select[2] = 0x04;
            select[4] = (byte) NFC_AID.length;
            System.arraycopy(NFC_AID, 0, select, 5, NFC_AID.length);
            if (!apduOk(isoDep.transceive(select))) {
                ui.post(() -> setStatus("Не получилось — приложите телефон ещё раз"));
                return;
            }
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String studentKey = prefs.getString(KEY_STUDENT_KEY, null);
            if (studentKey == null || studentKey.isEmpty()) {
                ui.post(() -> loginPanel.setVisibility(View.VISIBLE));
                return;
            }
            byte[] answer = isoDep.transceive(nfcCommand((byte) 3, studentKey + "\n" + deviceId()));
            if (!apduOk(answer) || answer.length < 3) {
                ui.post(() -> setStatus("Не получилось, попробуйте ещё раз"));
                return;
            }
            int code = answer[0] & 0xFF;
            if (code == 1 || code == 0) {
                vibrate();
                String name = prefs.getString(KEY_FULL_NAME, "");
                ui.post(() -> {
                    setStatus(name + "\nВы отмечены на паре");
                    ui.postDelayed(this::updateStudentStatus, 3500);
                });
            } else {
                ui.post(() -> setStatus("Не получилось, попробуйте ещё раз"));
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

    private void setStatus(String text) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
        loginPanel.setVisibility(View.GONE);
    }

    private void vibrate() {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 90, 70, 90}, -1));
            }
        } catch (Throwable ignored) {
        }
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
