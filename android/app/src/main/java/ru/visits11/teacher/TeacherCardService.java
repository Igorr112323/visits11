package ru.visits11.teacher;

import android.nfc.cardemulation.HostApduService;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Телефон преподавателя «притворяется картой»: студенты прикладывают свои
 * телефоны-считыватели. Касание кладём в очередь — ПК забирает его по USB
 * (см. MainActivity, /event и /event_result) и возвращает ответ.
 *
 * Протокол (AID F0391101020304, команда INS 0x10): данные студента —
 * «код-из-QR-кода \n id-устройства». Логина и пароля больше нет.
 */
public final class TeacherCardService extends HostApduService {

    /** NFC-запросы, ждущие ПК. */
    static final LinkedBlockingQueue<String> requests = new LinkedBlockingQueue<>();

    /** Ответ ПК на текущее касание. */
    static final SynchronousQueue<String> resultBox = new SynchronousQueue<>();

    /** Касания обрабатываются по одному. */
    static final Object TAP_LOCK = new Object();

    /** Номера запросов: ответ опоздавшего касания не достанется следующему. */
    private static final AtomicLong SEQ = new AtomicLong();

    private static final byte[] NFC_AID = {(byte) 0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04};

    /** Коды ответа — те же, что понимают приложения студентов. */
    private static final int REPLY_MARKED = 0;         // + имя студента
    private static final int REPLY_UNKNOWN_QR = 2;     // такого QR-кода нет в журнале
    private static final int REPLY_UNAVAILABLE = 4;    // ПК недоступен / перекличка не идёт
    private static final int REPLY_DEVICE_BLOCKED = 5; // телефон привязан к другому студенту

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) {
            return new byte[]{(byte) 0x6A, (byte) 0x82};
        }
        int instruction = apdu[1] & 0xFF;

        if (instruction == 0xA4) {
            // выбор нашего приложения — подтверждаем
            return ok(new byte[0]);
        }

        if (instruction != 0x10) {
            return new byte[]{(byte) 0x6D, (byte) 0x00};
        }

        // данные студента: код из QR-кода \n id-устройства
        int length = apdu.length >= 5 ? apdu[4] & 0xFF : 0;
        if (length < 1 || apdu.length < 5 + length) {
            return ok(new byte[0]);
        }
        String data = new String(apdu, 5, length, StandardCharsets.UTF_8);
        String[] parts = data.split("\n", 2);
        if (parts.length < 1 || parts[0].isEmpty()) {
            return ok(new byte[0]);
        }
        String device = parts.length > 1 ? parts[1] : "";
        String request = "{\"token\":\"" + escape(parts[0]) + "\",\"device\":\"" + escape(device) + "\"}";

        synchronized (TAP_LOCK) {
            // убираем невостребованные запросы прошлых касаний, чтобы очередь не забивалась
            requests.clear();
            resultBox.poll();

            // отдаём запрос ПК и ждём ответ прямо в момент касания;
            // чужие (опоздавшие) ответы пропускаем — ждём именно свой
            long id = SEQ.incrementAndGet();
            requests.add("{\"id\":" + id + "," + request.substring(1));
            String result = null;
            long deadline = System.nanoTime() + 4_000_000_000L;
            while (true) {
                long remain = deadline - System.nanoTime();
                if (remain <= 0) {
                    break;
                }
                String candidate;
                try {
                    candidate = resultBox.poll(remain, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (candidate == null) {
                    break;
                }
                if (idOf(candidate) == id) {
                    result = candidate;
                    break;
                }
            }
            if (result == null) {
                requests.clear();
                return ok(new byte[]{REPLY_UNAVAILABLE});
            }

            int code = codeOf(result);
            String value = extractString(result, "value");
            if (code == REPLY_MARKED) {
                vibrate();
            }
            byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[1 + bytes.length];
            payload[0] = (byte) code;
            System.arraycopy(bytes, 0, payload, 1, bytes.length);
            return ok(payload);
        }
    }

    @Override
    public void onDeactivated(int reason) {
    }

    /** Номер запроса в ответе ПК (0 — нет). */
    private static long idOf(String json) {
        try {
            int at = json.indexOf("\"id\":");
            if (at < 0) {
                return 0;
            }
            at += 5;
            while (at < json.length() && Character.isWhitespace(json.charAt(at))) {
                at++;
            }
            int end = at;
            while (end < json.length() && Character.isDigit(json.charAt(end))) {
                end++;
            }
            return Long.parseLong(json.substring(at, end));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Число после "code": в ответе ПК. */
    private static int codeOf(String json) {
        try {
            int at = json.indexOf("\"code\":");
            if (at < 0) {
                return REPLY_UNAVAILABLE;
            }
            at += 7;
            while (at < json.length() && Character.isWhitespace(json.charAt(at))) {
                at++;
            }
            int end = at;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
                end++;
            }
            int code = Integer.parseInt(json.substring(at, end));
            if (code == 1) {
                return REPLY_MARKED;
            }
            return code == REPLY_MARKED
                    || code == REPLY_UNKNOWN_QR
                    || code == REPLY_UNAVAILABLE
                    || code == REPLY_DEVICE_BLOCKED
                    ? code
                    : REPLY_UNAVAILABLE;
        } catch (Throwable ignored) {
            return REPLY_UNAVAILABLE;
        }
    }

    private static String extractString(String json, String key) {
        String marker = "\"" + key + "\":";
        int at = json.indexOf(marker);
        if (at < 0) {
            return null;
        }
        at += marker.length();
        while (at < json.length() && Character.isWhitespace(json.charAt(at))) {
            at++;
        }
        if (at >= json.length() || json.charAt(at) != '"') {
            return null;
        }
        int start = at + 1;
        int end = json.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return json.substring(start, end);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static byte[] ok(byte[] payload) {
        byte[] response = new byte[payload.length + 2];
        System.arraycopy(payload, 0, response, 0, payload.length);
        response[response.length - 2] = (byte) 0x90;
        response[response.length - 1] = 0x00;
        return response;
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
}
