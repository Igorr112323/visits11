package ru.visits11.teacher;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

final class CameraStreamer {

    static final class Frame {
        final byte[] jpeg;
        final long seq;
        final int rotation;

        Frame(byte[] jpeg, long seq, int rotation) {
            this.jpeg = jpeg;
            this.seq = seq;
            this.rotation = rotation;
        }
    }

    private static final long MIN_INTERVAL_MS = 250;
    private static final int MAX_WIDTH = 1280;
    private static final int JPEG_QUALITY = 70;

    private final Context context;
    private final Object lock = new Object();

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean running;
    private int sensorRotation;
    private long lastFrameAt;

    private Frame latest;
    private long seq = System.currentTimeMillis();

    CameraStreamer(Context context) {
        this.context = context.getApplicationContext();
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
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorRotation = orientation == null ? 0 : orientation;
            Size size = pickSize(characteristics);

            thread = new HandlerThread("CameraStream");
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

    Frame next(long since, long waitMs) {
        long deadline = SystemClock.elapsedRealtime() + waitMs;
        synchronized (lock) {
            while (latest == null || latest.seq <= since) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) {
                    return null;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return latest;
        }
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
            synchronized (CameraStreamer.this) {
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
                            synchronized (CameraStreamer.this) {
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
            byte[] jpeg = toJpeg(image);
            synchronized (lock) {
                seq++;
                latest = new Frame(jpeg, seq, sensorRotation);
                lock.notifyAll();
            }
        } catch (Throwable ignored) {
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private static byte[] toJpeg(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        byte[] nv21 = new byte[width * height * 3 / 2];

        ByteBuffer luma = planes[0].getBuffer();
        int lumaStride = planes[0].getRowStride();
        for (int row = 0; row < height; row++) {
            luma.position(row * lumaStride);
            luma.get(nv21, row * width, width);
        }

        ByteBuffer cb = planes[1].getBuffer();
        ByteBuffer cr = planes[2].getBuffer();
        int rowStride = planes[1].getRowStride();
        int pixelStride = planes[1].getPixelStride();
        int offset = width * height;
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int index = row * rowStride + col * pixelStride;
                nv21[offset++] = cr.get(index);
                nv21[offset++] = cb.get(index);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        new YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, out);
        return out.toByteArray();
    }
}
