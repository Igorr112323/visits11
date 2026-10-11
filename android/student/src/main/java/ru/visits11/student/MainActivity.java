package ru.visits11.student;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Приложение студента. Логина и пароля нет: вход один раз по QR-коду —
 * навести камеру («Сканировать QR-код») или выбрать картинку из галереи
 * («Загрузить из галереи»). Дальше отметка — прикладывание телефона
 * к телефону преподавателя (NFC). Интернет и Wi-Fi приложению не нужны:
 * разрешения INTERNET у него нет вовсе.
 */
public final class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final String PREFS = "visits11student";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_NAME = "name";

    private static final byte[] NFC_AID = {(byte) 0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04};

    private static final int REQ_PICK_QR = 41;
    private static final int REQ_CAMERA = 42;

    /** Версия формата QR-кода студента — та же строка, что на ПК (Services/StudentQr.cs). */
    private static final String QR_PREFIX = "V11S2";

    /** Коды ответа телефона преподавателя. */
    private static final int REPLY_MARKED = 0;         // + имя студента
    private static final int REPLY_UNKNOWN_QR = 2;     // такого QR-кода нет в журнале
    private static final int REPLY_UNAVAILABLE = 4;    // перекличка не идёт / нет связи с ПК
    private static final int REPLY_DEVICE_BLOCKED = 5; // телефон привязан к другому студенту

    private View entryPanel;
    private TextView statusText;

    /** true — NFC есть: отметка «телефон к телефону», сеть не нужна вовсе. */
    private boolean useNfc;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        entryPanel = findViewById(R.id.entryPanel);
        statusText = findViewById(R.id.statusText);
        Button scanButton = findViewById(R.id.scanButton);
        Button galleryButton = findViewById(R.id.galleryButton);

        // два и только два способа входа: камера и картинка из галереи
        scanButton.setOnClickListener(v -> scanWithCamera());
        galleryButton.setOnClickListener(v -> pickQrImage());

        // смена студента — долгим нажатием (чтобы не открыть случайно)
        statusText.setOnLongClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(KEY_TOKEN)
                    .remove(KEY_NAME)
                    .apply();
            showEntry();
            toast("Отсканируйте QR-код студента");
            return true;
        });
        // выключенный NFC — по нажатию открываем настройки
        statusText.setOnClickListener(v -> {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter != null && !adapter.isEnabled()) {
                try {
                    startActivity(new Intent("android.settings.NFC_SETTINGS"));
                } catch (Throwable ignored) {
                }
            }
        });

        useNfc = nfcSupported();
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateScreen();
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

    // ------------------------------------------------------------ экран

    /** Уникальный ID устройства — аккаунт привязывается к первому телефону. */
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
                && NfcAdapter.getDefaultAdapter(this) != null;
    }

    private String storedToken() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TOKEN, null);
    }

    private String storedName() {
        String name = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NAME, null);
        return name == null ? "" : name;
    }

    /** QR-код ещё не считан — показываем вход, иначе — «приложите телефон». */
    private void updateScreen() {
        if (storedToken() == null) {
            showEntry();
            return;
        }

        entryPanel.setVisibility(View.GONE);
        statusText.setVisibility(View.VISIBLE);

        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        if (!useNfc) {
            statusText.setText("Этот телефон без NFC — отметиться не получится");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            statusText.setText("Включите NFC в настройках телефона");
            return;
        }
        String name = storedName();
        statusText.setText(name.isEmpty()
                ? "Приложите телефон к телефону преподавателя"
                : name + "\nПриложите телефон к телефону преподавателя");
    }

    private void showEntry() {
        entryPanel.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.GONE);
    }

    private void showReady() {
        entryPanel.setVisibility(View.GONE);
        statusText.setVisibility(View.VISIBLE);
        updateScreen();
    }

    // ------------------------------------------------------------ камера

    /** Вход сканированием: сначала разрешение на камеру, потом сам сканер. */
    private void scanWithCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        launchScanner();
    }

    private void launchScanner() {
        try {
            IntentIntegrator integrator = new IntentIntegrator(this);
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            integrator.setPrompt("Наведите камеру на QR-код студента");
            integrator.setBeepEnabled(false);
            integrator.setBarcodeImageEnabled(false);
            integrator.setOrientationLocked(true);
            integrator.initiateScan();
        } catch (Throwable ignored) {
            toast("Не удалось открыть камеру — загрузите картинку из галереи");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_CAMERA) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            launchScanner();
        } else {
            toast("Без камеры можно загрузить картинку из галереи");
        }
    }

    // ------------------------------------------------------------ галерея

    /**
     * Открывает системный выбор картинки. Разрешения не нужны: картинку
     * отдаёт сам системный пикер (Android 13+ — «выбор фото», ниже — «Документы»/галерея).
     */
    private void pickQrImage() {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            } else {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
            }
            intent.setType("image/*");
            startActivityForResult(intent, REQ_PICK_QR);
        } catch (Throwable first) {
            try {
                startActivityForResult(
                        new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK_QR);
            } catch (Throwable second) {
                toast("Не нашлось приложения с галереей");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // результат сканирования камерой
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            applyQrPayload(scan.getContents());
            return;
        }

        // результат выбора картинки из галереи
        if (requestCode != REQ_PICK_QR || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        // фото может быть большим: распознаём в фоне, интерфейс не подвисает
        new Thread(() -> {
            String text = null;
            try {
                text = readQrText(uri);
            } catch (Throwable ignored) {
            }
            final String payload = text;
            ui.post(() -> applyQrPayload(payload));
        }, "QrLogin").start();
    }

    // ------------------------------------------------------------ вход по QR

    /** Применяет распознанный QR: сохраняет код студента — это и есть вход. */
    private void applyQrPayload(String text) {
        Credentials credentials = parsePayload(text);
        if (credentials == null) {
            toast("В этой картинке нет кода для входа");
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_TOKEN, credentials.token)
                .putString(KEY_NAME, credentials.name)
                .apply();

        showReady();
        vibrate();
        toast(credentials.name.isEmpty()
                ? "Вход выполнен"
                : "Вход выполнен: " + credentials.name);
    }

    /** Код студента и ФИО из QR-кода. */
    private static final class Credentials {
        final String token;
        final String name;

        Credentials(String token, String name) {
            this.token = token;
            this.name = name;
        }
    }

    /**
     * Разбор содержимого QR (формат ПК, Services/StudentQr.cs):
     * <pre>
     *   V11S2.&lt;код&gt;.&lt;ФИО&gt;.&lt;CRC32&gt;   — поля в base64url
     *   Student: Ivanov Ivan Ivanovich
     *   Code: QVJISVBPVl9JSQ
     * </pre>
     * Читается только первая строка; строки ниже — для человека.
     */
    private static Credentials parsePayload(String text) {
        if (text == null) {
            return null;
        }
        int lineEnd = text.indexOf('\n');
        String machine = (lineEnd >= 0 ? text.substring(0, lineEnd) : text).trim();

        String[] parts = machine.split("\\.", -1);
        if (parts.length != 4 || !QR_PREFIX.equals(parts[0])) {
            return null;
        }
        String body = parts[0] + "." + parts[1] + "." + parts[2];
        if (!crc32Hex(body).equalsIgnoreCase(parts[3])) {
            return null;
        }

        String token = fromBase64Url(parts[1]);
        String name = fromBase64Url(parts[2]);
        if (token == null || token.isEmpty()) {
            return null;
        }
        return new Credentials(token, name == null ? "" : name);
    }

    private static String fromBase64Url(String value) {
        try {
            byte[] bytes = Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** CRC32 (IEEE 802.3), 8 hex-символов — тот же алгоритм на ПК и на iPhone. */
    private static String crc32Hex(String value) {
        CRC32 crc = new CRC32();
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        return String.format(java.util.Locale.ROOT, "%08x", crc.getValue());
    }

    // ------------------------------------------------------------ чтение картинки

    /** Картинка из галереи → содержимое QR-кода (или null, если кода нет). */
    private String readQrText(Uri uri) throws IOException {
        Bitmap bitmap = loadBitmap(uri);
        if (bitmap == null) {
            return null;
        }
        try {
            return decodeQr(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    /** Картинка, ужатая до разумного размера: большие фото декодеру не нужны, а память едят. */
    private Bitmap loadBitmap(Uri uri) throws IOException {
        ContentResolver resolver = getContentResolver();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                return null;
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 720 && bounds.outHeight / (sample * 2) >= 720) {
            sample *= 2; // длинная сторона остаётся около 720–1400 px
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try (InputStream input = resolver.openInputStream(uri)) {
            return input == null ? null : BitmapFactory.decodeStream(input, null, options);
        }
    }

    /** QR-код с картинки: обычный и «негатив» (скриншот тёмной темы — белое на чёрном). */
    private static String decodeQr(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 8 || height < 8) {
            return null;
        }
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));

        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        String text = tryDecode(source, hints);
        return text != null ? text : tryDecode(source.invert(), hints);
    }

    private static String tryDecode(LuminanceSource source, Map<DecodeHintType, Object> hints) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            return new MultiFormatReader().decode(bitmap, hints).getText();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------ NFC-отметка

    /** Приложили телефон к телефону преподавателя: отправляем код студента. */
    @Override
    public void onTagDiscovered(Tag tag) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            return;
        }
        try {
            String token = storedToken();
            if (token == null) {
                return;
            }

            isoDep.connect();
            isoDep.setTimeout(5000);

            // выбор приложения телефона преподавателя
            byte[] select = new byte[5 + NFC_AID.length];
            select[1] = (byte) 0xA4;
            select[2] = 0x04;
            select[4] = (byte) NFC_AID.length;
            System.arraycopy(NFC_AID, 0, select, 5, NFC_AID.length);
            if (!apduOk(isoDep.transceive(select))) {
                ui.post(() -> setStatus("Не получилось — приложите телефон еще раз"));
                return;
            }

            byte[] answer = isoDep.transceive(nfcCommand(token + "\n" + deviceId()));
            if (!apduOk(answer) || answer.length < 3) {
                ui.post(() -> setStatus("Не получилось, попробуйте ещё раз"));
                return;
            }

            int result = answer[0] & 0xFF;
            String value = new String(answer, 1, answer.length - 3, StandardCharsets.UTF_8);
            handleReply(result, value);
        } catch (Throwable ignored) {
            ui.post(() -> setStatus("Не получилось, попробуйте ещё раз"));
        } finally {
            try {
                isoDep.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Ответ телефона преподавателя: показываем результат и вибрируем при успехе. */
    private void handleReply(int result, String value) {
        if (result == REPLY_MARKED) {
            vibrate();
            ui.post(() -> setStatus(value.isEmpty() ? "Вы отмечены" : "Вы отмечены — " + value));
            return;
        }
        if (result == REPLY_UNKNOWN_QR) {
            // код не из журнала: стираем его и просим войти заново
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_TOKEN).apply();
            ui.post(() -> {
                showEntry();
                toast("Такого QR-кода нет в журнале — войдите заново");
            });
            return;
        }
        if (result == REPLY_DEVICE_BLOCKED) {
            ui.post(() -> setStatus("Этот телефон привязан к другому студенту"));
            return;
        }
        ui.post(() -> setStatus("Не получилось: перекличка не идёт"));
    }

    /** APDU-команда 0x10 с кодом студента. */
    private static byte[] nfcCommand(String data) {
        byte[] payload = data.getBytes(StandardCharsets.UTF_8);
        byte[] command = new byte[5 + payload.length];
        command[1] = (byte) 0x10;
        command[4] = (byte) payload.length;
        System.arraycopy(payload, 0, command, 5, payload.length);
        return command;
    }

    private static boolean apduOk(byte[] response) {
        return response != null && response.length >= 2
                && (response[response.length - 2] & 0xFF) == 0x90
                && (response[response.length - 1] & 0xFF) == 0x00;
    }

    /** Заметная двойная вибрация — отметка прошла. */
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

    // ------------------------------------------------------------ интерфейс

    private void setStatus(String text) {
        ui.post(() -> {
            statusText.setText(text);
            statusText.setVisibility(View.VISIBLE);
            entryPanel.setVisibility(View.GONE);
        });
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
