package ru.visits11.student;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * Приложение студента. Логина и пароля нет: вход один раз по QR-коду —
 * навести камеру («Сканировать QR-код») или выбрать картинку из галереи
 * («Загрузить из галереи»). Дальше отметка — прикладывание телефона
 * к телефону преподавателя (NFC). Интернет и Wi-Fi приложению не нужны:
 * разрешения INTERNET у него нет вовсе.
 *
 * Камера работает внутри этого же окна (системная камера + ZXing) — никаких
 * сторонних экранов и библиотек-сканеров: меньше деталей, которым можно сломаться.
 */
public final class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final String PREFS = "visits11student";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_NAME = "name";

    private static final byte[] NFC_AID = {(byte) 0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04};

    /** Версия формата QR-кода студента — та же строка, что на ПК (Services/StudentQr.cs). */
    private static final String QR_PREFIX = "V11S2";

    /** Коды ответа телефона преподавателя. */
    private static final int REPLY_MARKED = 0;         // + имя студента
    private static final int REPLY_UNKNOWN_QR = 2;     // такого QR-кода нет в журнале
    private static final int REPLY_UNAVAILABLE = 4;    // перекличка не идёт / нет связи с ПК
    private static final int REPLY_DEVICE_BLOCKED = 5; // телефон привязан к другому студенту

    private View entryPanel;
    private TextView statusText;
    private FrameLayout scannerPanel;
    private FrameLayout previewFrame;
    private SurfaceView cameraPreview;

    /** Камера-сканер внутри приложения: своя система координат вместо чужого экрана. */
    private Camera camera;
    private Thread decodeThread;
    private volatile boolean cameraRunning;
    private volatile int frameWidth;
    private volatile int frameHeight;

    /** Последний кадр камеры: в очереди всегда не больше одного — старые не нужны. */
    private final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(1);

    private static final int REQ_PICK_QR = 41;
    private static final int REQ_CAMERA = 42;

    /** true — NFC есть: отметка «телефон к телефону», сеть не нужна вовсе. */
    private boolean useNfc;

    /** Момент последней успешной отметки: не даёт отрыву руки затереть «Вы отмечены». */
    private volatile long lastMarkedAt;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        entryPanel = findViewById(R.id.entryPanel);
        statusText = findViewById(R.id.statusText);
        scannerPanel = findViewById(R.id.scannerPanel);
        previewFrame = findViewById(R.id.previewFrame);
        cameraPreview = findViewById(R.id.cameraPreview);
        Button scanButton = findViewById(R.id.scanButton);
        Button galleryButton = findViewById(R.id.galleryButton);

        // камера включается, когда её окно уже на экране, и гаснет вместе с ним
        cameraPreview.getHolder().addCallback(surfaceCallback);
        findViewById(R.id.cancelScanButton).setOnClickListener(v -> closeScanner(null));

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
        // ушли с экрана — камера больше не нужна
        stopCamera();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
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

    /** Вход сканированием: сначала разрешение на камеру, потом своя камера на экране. */
    private void scanWithCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        openScanner();
    }

    /**
     * Показывает экран сканирования. Камера живёт внутри этого же окна:
     * системная камера плюс ZXing — ни сторонних экранов, ни чужих оформлений,
     * которым можно сломаться на новом Android.
     */
    private void openScanner() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            toast("На этом телефоне нет камеры — загрузите картинку из галереи");
            return;
        }
        scannerPanel.setVisibility(View.VISIBLE);
    }

    private void closeScanner(String message) {
        scannerPanel.setVisibility(View.GONE);
        stopCamera();
        if (message != null) {
            toast(message);
        }
    }

    /** Камера включается, когда её окно уже на экране, и гаснет вместе с ним. */
    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            if (scannerPanel.getVisibility() == View.VISIBLE) {
                startCamera(holder);
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            sizePreview();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            stopCamera();
        }
    };

    private void startCamera(SurfaceHolder holder) {
        if (camera != null) {
            return;
        }
        try {
            Camera opened = Camera.open();
            Camera.Parameters parameters = opened.getParameters();
            parameters.setPreviewFormat(ImageFormat.NV21);

            // кадр поменьше — распознавание быстрее: берём самый крупный до 1280 px
            // (16:9 предпочтительнее: в портрете такое превью занимает экран целиком)
            Camera.Size chosen = null;
            Camera.Size widest = null;
            List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
            if (sizes != null) {
                for (Camera.Size size : sizes) {
                    if (size.width > 1280 || size.height > 1024) {
                        continue;
                    }
                    boolean is16to9 = Math.abs((double) size.width / size.height - 16.0 / 9.0) < 0.03;
                    if (is16to9 && (chosen == null || size.width * size.height > chosen.width * chosen.height)) {
                        chosen = size;
                    }
                    if (widest == null || size.width * size.height > widest.width * widest.height) {
                        widest = size;
                    }
                }
            }
            if (chosen == null) {
                chosen = widest;
            }
            if (chosen != null) {
                parameters.setPreviewSize(chosen.width, chosen.height);
            }

            List<String> focusModes = parameters.getSupportedFocusModes();
            if (focusModes != null) {
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }

            opened.setParameters(parameters);
            opened.setDisplayOrientation(90); // телефон держат портретом
            opened.setPreviewDisplay(holder);

            frameWidth = parameters.getPreviewSize().width;
            frameHeight = parameters.getPreviewSize().height;

            camera = opened;
            cameraRunning = true;
            camera.setPreviewCallback((data, source) -> {
                if (cameraRunning) {
                    frames.offer(data); // в очереди только свежий кадр, старые пропускаем
                }
            });
            camera.startPreview();

            decodeThread = new Thread(this::decodeLoop, "QrCamera");
            decodeThread.start();
            sizePreview();
        } catch (Throwable error) {
            stopCamera();
            closeScanner("Камера не открылась — загрузите картинку из галереи");
        }
    }

    private void stopCamera() {
        cameraRunning = false;
        frames.clear();

        Camera current = camera;
        camera = null;
        if (current != null) {
            try {
                current.setPreviewCallback(null);
            } catch (Throwable ignored) {
            }
            try {
                current.stopPreview();
            } catch (Throwable ignored) {
            }
            try {
                current.release();
            } catch (Throwable ignored) {
            }
        }

        Thread worker = decodeThread;
        decodeThread = null;
        if (worker != null) {
            worker.interrupt();
        }
        frameWidth = 0;
        frameHeight = 0;
    }

    /**
     * Подгоняет окно камеры под экран с сохранением пропорций: картинка не
     * растягивается (иначе квадратный QR-код перестаёт распознаваться),
     * а по краям остаются чёрные поля.
     */
    private void sizePreview() {
        final int width = frameWidth;
        final int height = frameHeight;
        if (width <= 0 || height <= 0) {
            return;
        }
        previewFrame.post(() -> {
            int targetWidth = previewFrame.getWidth();
            int targetHeight = previewFrame.getHeight();
            if (targetWidth == 0 || targetHeight == 0) {
                return;
            }
            // кадр приходит «лёжа», а на экране повёрнут — стороны меняем местами
            float scale = Math.min((float) targetWidth / height, (float) targetHeight / width);
            ViewGroup.LayoutParams params = cameraPreview.getLayoutParams();
            params.width = Math.round(height * scale);
            params.height = Math.round(width * scale);
            cameraPreview.setLayoutParams(params);
        });
    }

    /** Кадры камеры разбираем в отдельном потоке, чтобы экран не подвисал. */
    private void decodeLoop() {
        Map<DecodeHintType, Object> hints = qrHints();
        while (cameraRunning) {
            byte[] frame;
            try {
                frame = frames.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (frame == null) {
                continue;
            }
            int width = frameWidth;
            int height = frameHeight;
            if (width <= 0 || height <= 0) {
                continue;
            }
            String text = decodeNv21(frame, width, height, hints);
            if (text != null) {
                final String payload = text;
                ui.post(() -> {
                    closeScanner(null);
                    applyQrPayload(payload);
                });
                return;
            }
        }
    }

    /** QR на кадре камеры (формат NV21): яркость берётся из Y-плоскости кадра. */
    private static String decodeNv21(byte[] frame, int width, int height, Map<DecodeHintType, Object> hints) {
        try {
            LuminanceSource source =
                    new PlanarYUVLuminanceSource(frame, width, height, 0, 0, width, height, false);
            return tryDecode(source, hints);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_CAMERA) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            openScanner();
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

        Map<DecodeHintType, Object> hints = qrHints();

        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        String text = tryDecode(source, hints);
        return text != null ? text : tryDecode(source.invert(), hints);
    }

    /** Что и как ищем в любом изображении: только QR-код, но старательно. */
    private static Map<DecodeHintType, Object> qrHints() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        return hints;
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
        // после успешной отметки 5 секунд игнорируем повторные касания,
        // чтобы при отведении руки обрыв NFC-поля не затёр «Вы отмечены» ошибкой
        if (lastMarkedAt > 0 && SystemClock.elapsedRealtime() - lastMarkedAt < 5000) {
            return;
        }
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
            if (lastMarkedAt == 0 || SystemClock.elapsedRealtime() - lastMarkedAt >= 5000) {
                ui.post(() -> setStatus("Не получилось, попробуйте ещё раз"));
            }
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
            lastMarkedAt = SystemClock.elapsedRealtime();
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
