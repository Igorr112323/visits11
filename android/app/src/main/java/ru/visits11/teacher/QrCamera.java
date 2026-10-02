package ru.visits11.teacher;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

final class QrCamera {

    private static final long MIN_INTERVAL_MS = 150;
    private static final long REPEAT_MS = 10_000;
    private static final int MAX_WIDTH = 1280;
    private static final int MAX_QUEUE = 20;
    private static final Pattern PAYLOAD = Pattern.compile("^V11B[A-Za-z0-9_-]{27}$");

    private final Context context;
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean running;
    private long lastFrameAt;
    private String lastText = "";
    private long lastTextAt;

    QrCamera(Context context) {
        this.context = context.getApplicationContext();
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
    }

    synchronized void start() {
        if (running) {
            return;
        }
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String id = pickBackCamera(manager);
            if (id == null) {
                return;
            }
            Size size = pickSize(manager.getCameraCharacteristics(id));
            thread = new HandlerThread("QrCamera");
            thread.start();
            handler = new Handler(thread.getLooper());
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, handler);
            running = true;
            manager.openCamera(id, stateCallback, handler);
        } catch (Throwable ignored) {
            stop();
        }
    }

    synchronized void stop() {
        running = false;
        try {
            if (session != null) {
                session.close();
            }
        } catch (Throwable ignored) {
        }
        session = null;
        try {
            if (device != null) {
                device.close();
            }
        } catch (Throwable ignored) {
        }
        device = null;
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Throwable ignored) {
        }
        reader = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        handler = null;
    }

    private static String pickBackCamera(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return ids.length > 0 ? ids[0] : null;
    }

    private static Size pickSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size best = null;
        if (map != null) {
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes != null) {
                for (Size size : sizes) {
                    if (size.getWidth() > MAX_WIDTH) {
                        continue;
                    }
                    if (best == null || size.getWidth() * size.getHeight() > best.getWidth() * best.getHeight()) {
                        best = size;
                    }
                }
            }
        }
        return best != null ? best : new Size(640, 480);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            synchronized (QrCamera.this) {
                if (!running) {
                    camera.close();
                    return;
                }
                device = camera;
            }
            createSession(camera);
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
        }
    };

    private void createSession(CameraDevice camera) {
        try {
            final ImageReader target;
            final Handler callbackHandler;
            synchronized (this) {
                target = reader;
                callbackHandler = handler;
            }
            if (target == null || callbackHandler == null) {
                return;
            }
            final CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(target.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Collections.singletonList(target.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession configured) {
                            synchronized (QrCamera.this) {
                                if (!running) {
                                    configured.close();
                                    return;
                                }
                                session = configured;
                            }
                            try {
                                configured.setRepeatingRequest(builder.build(), null, callbackHandler);
                            } catch (Throwable ignored) {
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession failed) {
                            failed.close();
                        }
                    }, callbackHandler);
        } catch (Throwable ignored) {
        }
    }

    private void onImage(ImageReader source) {
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastFrameAt < MIN_INTERVAL_MS) {
                return;
            }
            lastFrameAt = now;
            String text = decode(image);
            if (text != null) {
                submit(text, now);
            }
        } catch (Throwable ignored) {
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private String decode(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int stride = plane.getRowStride();
        byte[] luma = new byte[width * height];
        for (int row = 0; row < height; row++) {
            buffer.position(row * stride);
            buffer.get(luma, row * width, width);
        }
        try {
            PlanarYUVLuminanceSource luminance =
                    new PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false);
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(luminance)), hints).getText();
        } catch (Throwable notFound) {
            return null;
        }
    }

    private void submit(String text, long now) {
        if (!PAYLOAD.matcher(text).matches()) {
            return;
        }
        if (text.equals(lastText) && now - lastTextAt < REPEAT_MS) {
            return;
        }
        lastText = text;
        lastTextAt = now;
        if (TeacherCardService.requests.size() >= MAX_QUEUE) {
            return;
        }
        TeacherCardService.requests.add("{\"type\":3,\"qr\":\"" + text + "\"}");
    }
}
