package ru.visits11.teacher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Камера преподавателя: показывает превью и шлёт кадры (JPEG) на ПК
 * в приложение Visits11: POST http://адрес/api/frame?rot=N
 */
public final class MainActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final String PREFS = "visits11";
    private static final String KEY_ADDRESS = "address";
    private static final int REQUEST_CAMERA = 1;

    private TextureView preview;
    private View dot;
    private View setupPanel;
    private EditText addressInput;

    private volatile SurfaceTexture surface;
    private volatile String serverBase = "";
    private volatile int frameRotation = 90;
    private volatile boolean connected;

    private Camera camera;
    private int cameraId;
    private int previewWidth;
    private int previewHeight;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private boolean cameraStarted;

    private volatile boolean senderRunning;
    private Thread senderThread;

    /** последний сжатый кадр, ждёт отправки (отправляется только свежий) */
    private final AtomicReference<byte[]> pendingFrame = new AtomicReference<>();

    // ------------------------------------------------------------ жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preview = findViewById(R.id.preview);
        dot = findViewById(R.id.dot);
        setupPanel = findViewById(R.id.setupPanel);
        addressInput = findViewById(R.id.addressInput);
        Button connectButton = findViewById(R.id.connectButton);

        preview.setSurfaceTextureListener(this);
        dot.setOnClickListener(v -> setupPanel.setVisibility(View.VISIBLE));
        connectButton.setOnClickListener(v -> connect());

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ADDRESS, null);
        if (saved != null && !saved.isEmpty()) {
            serverBase = saved;
        } else {
            setupPanel.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission()) {
            if (preview.isAvailable()) {
                startCamera();
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
        startSender();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSender();
        stopCamera();
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

    // ------------------------------------------------------------ подключение

    private void connect() {
        String addr = addressInput.getText().toString().trim();
        if (addr.isEmpty()) {
            toast("Введите адрес ПК");
            return;
        }
        if (!addr.startsWith("http://") && !addr.startsWith("https://")) {
            addr = "http://" + addr;
        }
        while (addr.endsWith("/")) {
            addr = addr.substring(0, addr.length() - 1);
        }
        serverBase = addr;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ADDRESS, addr).apply();

        setupPanel.setVisibility(View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(addressInput.getWindowToken(), 0);
        }
        startSender();
    }

    private void startSender() {
        if (serverBase.isEmpty() || senderRunning) {
            return;
        }
        senderRunning = true;
        senderThread = new Thread(this::senderLoop, "Sender");
        senderThread.start();
    }

    private void stopSender() {
        senderRunning = false;
        Thread thread = senderThread;
        senderThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Отправляет свежие кадры; при сбое сети — пауза и повтор. */
    private void senderLoop() {
        while (senderRunning && !Thread.currentThread().isInterrupted()) {
            byte[] frame = pendingFrame.getAndSet(null);
            if (frame == null) {
                sleepMs(50);
                continue;
            }
            boolean ok = sendFrame(frame);
            if (ok != connected) {
                connected = ok;
                runOnUiThread(() -> dot.setBackgroundResource(
                        ok ? R.drawable.dot_green : R.drawable.dot_red));
            }
            if (!ok) {
                sleepMs(1500);
            }
        }
    }

    private boolean sendFrame(byte[] jpeg) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    serverBase + "/api/frame?rot=" + frameRotation).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setFixedLengthStreamingMode(jpeg.length);
            connection.setRequestProperty("Content-Type", "image/jpeg");
            OutputStream output = connection.getOutputStream();
            output.write(jpeg);
            output.flush();
            output.close();
            int code = connection.getResponseCode();
            return code >= 200 && code < 300;
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    /** Сжатие кадра в JPEG; если предыдущий ещё не отправлен — кадр пропускаем. */
    private void onPreviewFrame(byte[] data, Camera c) {
        if (pendingFrame.get() == null && previewWidth > 0) {
            try {
                YuvImage yuv = new YuvImage(data, ImageFormat.NV21, previewWidth, previewHeight, null);
                ByteArrayOutputStream out = new ByteArrayOutputStream(48 * 1024);
                yuv.compressToJpeg(new Rect(0, 0, previewWidth, previewHeight), 70, out);
                pendingFrame.set(out.toByteArray());
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
        pendingFrame.set(null);
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

    // ------------------------------------------------------------ утилиты

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
