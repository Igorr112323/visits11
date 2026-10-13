package ru.visits11.student;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Отметка по Bluetooth: то же самое, что делает приложение студента на iPhone.
 *
 * Телефон ищет считыватель (телефон преподавателя или коробочку ESP32), подключается
 * и отправляет пакет 0x01 + <код студента> + "\n" + <id телефона> + 0x00.
 * Ответ читается уведомлением и опросом: [код][имя]. Код 0xFF — «считыватель ещё думает».
 *
 * Срабатывает только у считывателя вплотную: при слабее TOUCH_RSSI_THRESHOLD пакет не отправляется.
 */
final class BleMark {

    /** Считыватель должен быть не дальше, чем этот уровень сигнала (дБм). Меньше — строже (например -46). */
    private static final int TOUCH_RSSI_THRESHOLD = -50;

    private static final UUID SERVICE_UUID = UUID.fromString("F0391101-0203-0405-0607-08090A0B0C0D");
    private static final UUID CHAR_UUID = UUID.fromString("F0391102-0203-0405-0607-08090A0B0C0D");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Сколько ждём ответ считывателя, мс. */
    private static final long EXCHANGE_TIMEOUT_MS = 5000;

    /** Как часто дополнительно опрашиваем характеристику, мс (уведомление может не дойти). */
    private static final long READ_POLL_MS = 300;

    /** Пауза после отметки, мс: чтобы не отмечаться дважды подряд. */
    private static final long COOLDOWN_MS = 5000;

    interface Listener {
        void onReply(int code, String value);
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;

    private volatile boolean started;
    private volatile boolean busy;
    private volatile boolean finished;
    private long busyUntil;
    private byte[] payload;
    private int writeOffset;
    private int chunkSize = 20;

    private final Runnable readPoll = new Runnable() {
        @Override
        public void run() {
            if (gatt == null || characteristic == null || finished) {
                return;
            }
            try {
                gatt.readCharacteristic(characteristic);
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, READ_POLL_MS);
        }
    };

    private final Runnable giveUp = new Runnable() {
        @Override
        public void run() {
            if (!busy || finished) {
                return;
            }
            finish(4, "");
        }
    };

    BleMark(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Ищем считыватель рядом. */
    void start() {
        if (started) {
            return;
        }
        started = true;
        beginScan();
    }

    /** Bluetooth включили уже после запуска — начинаем поиск заново. */
    void nudge() {
        if (started) {
            beginScan();
        }
    }

    /** Экран ушёл — поиск прекращаем. */
    void stop() {
        started = false;
        stopScan();
        closeGatt();
        handler.removeCallbacks(readPoll);
        handler.removeCallbacks(giveUp);
        busy = false;
    }

    // ------------------------------------------------------------ поиск

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private void beginScan() {
        if (!started || busy || SystemClock.elapsedRealtime() < busyUntil) {
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            return;
        }

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(filter);
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            scanner.startScan(filters, settings, scanCallback);
        } catch (Throwable ignored) {
        }
    }

    private void stopScan() {
        BluetoothLeScanner current = scanner;
        scanner = null;
        if (current == null) {
            return;
        }
        try {
            current.stopScan(scanCallback);
        } catch (Throwable ignored) {
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!started || busy || result == null) {
                return;
            }
            int rssi = result.getRssi();
            // срабатываем только вплотную: считыватель передаёт на минимальной мощности
            if (rssi == 0 || rssi < TOUCH_RSSI_THRESHOLD) {
                return;
            }
            BluetoothDevice device = result.getDevice();
            if (device == null) {
                return;
            }
            busy = true;
            stopScan();
            connect(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanner = null;
            if (started && !busy) {
                handler.postDelayed(BleMark.this::beginScan, 2000);
            }
        }
    };

    // ------------------------------------------------------------ обмен

    private void connect(BluetoothDevice device) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(context, false, gattCallback);
            }
        } catch (Throwable ignored) {
            gatt = null;
        }
        if (gatt == null) {
            finish(4, "");
            return;
        }
        try {
            gatt.requestMtu(247);
        } catch (Throwable ignored) {
        }
        handler.postDelayed(giveUp, EXCHANGE_TIMEOUT_MS);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    connection.discoverServices();
                } catch (Throwable ignored) {
                }
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED && !finished) {
                finish(4, "");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt connection, int mtu, int status) {
            if (mtu > 23) {
                chunkSize = Math.min(180, mtu - 3);
            }
            try {
                connection.discoverServices();
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt connection, int status) {
            BluetoothGattService service = connection.getService(SERVICE_UUID);
            BluetoothGattCharacteristic found =
                    service == null ? null : service.getCharacteristic(CHAR_UUID);
            if (found == null) {
                finish(4, "");
                return;
            }
            characteristic = found;
            try {
                connection.setCharacteristicNotification(found, true);
                BluetoothGattDescriptor cccd = found.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    connection.writeDescriptor(cccd);
                }
            } catch (Throwable ignored) {
            }

            String token = token();
            if (token == null) {
                finish(4, "");
                return;
            }
            sendPayload(connection, token + "\n" + deviceId());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt connection,
                                          BluetoothGattCharacteristic value, int status) {
            if (finished || payload == null) {
                return;
            }
            if (writeOffset >= payload.length) {
                handler.removeCallbacks(giveUp);
                handler.postDelayed(giveUp, EXCHANGE_TIMEOUT_MS);
                handler.postDelayed(readPoll, READ_POLL_MS);
                return;
            }
            writeChunk(connection);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt connection,
                                         BluetoothGattCharacteristic value, int status) {
            if (value != null) {
                parseReply(value.getValue());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt connection,
                                            BluetoothGattCharacteristic value) {
            if (value != null) {
                parseReply(value.getValue());
            }
        }
    };

    private void sendPayload(BluetoothGatt connection, String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        payload = new byte[body.length + 2];
        payload[0] = 0x01;
        System.arraycopy(body, 0, payload, 1, body.length);
        payload[payload.length - 1] = 0x00;
        writeOffset = 0;
        writeChunk(connection);
    }

    private void writeChunk(BluetoothGatt connection) {
        int end = Math.min(writeOffset + chunkSize, payload.length);
        byte[] chunk = new byte[end - writeOffset];
        System.arraycopy(payload, writeOffset, chunk, 0, chunk.length);
        writeOffset = end;
        try {
            characteristic.setValue(chunk);
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (!connection.writeCharacteristic(characteristic)) {
                finish(4, "");
            }
        } catch (Throwable ignored) {
            finish(4, "");
        }
    }

    private void parseReply(byte[] data) {
        if (data == null || data.length == 0 || finished) {
            return;
        }
        int code = data[0] & 0xFF;
        if (code == 0xFF) {
            return;
        }
        String value = new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
        finish(code, value);
    }

    /** Ответ получен: закрываем соединение и показываем результат. */
    private void finish(int code, String value) {
        if (finished) {
            return;
        }
        finished = true;
        busy = false;
        busyUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS;
        handler.removeCallbacks(readPoll);
        handler.removeCallbacks(giveUp);
        closeGatt();
        listener.onReply(code, value);
        if (started) {
            handler.postDelayed(this::beginScan, COOLDOWN_MS);
        }
    }

    private void closeGatt() {
        BluetoothGatt current = gatt;
        gatt = null;
        characteristic = null;
        payload = null;
        writeOffset = 0;
        if (current == null) {
            return;
        }
        try {
            current.disconnect();
        } catch (Throwable ignored) {
        }
        try {
            current.close();
        } catch (Throwable ignored) {
        }
    }

    private String token() {
        try {
            return context.getSharedPreferences("visits11student", Context.MODE_PRIVATE)
                    .getString("token", null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String deviceId() {
        try {
            return android.provider.Settings.Secure.getString(
                    context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
