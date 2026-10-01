package ru.visits11.teacher;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Телефон преподавателя «притворяется картой»: студенты прикладывают свои
 * телефоны-считыватели, мы кладём их данные в очередь — ПК сам забирает
 * их по /event и возвращает ответ в /event_result (все соединения исходящие
 * со стороны ПК, брандмауэр Windows ничего не блокирует).
 */
public final class TeacherCardService extends HostApduService {

    /** NFC-запросы, ждущие ПК: {"type":1|2,...}. */
    static final LinkedBlockingQueue<String> requests = new LinkedBlockingQueue<>();

    /** Ответ ПК на текущее касание. */
    static final SynchronousQueue<String> resultBox = new SynchronousQueue<>();

    /** Касания обрабатываются по одному. */
    static final Object TAP_LOCK = new Object();

    private static final byte[] NFC_AID = {(byte) 0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04};

    private static final int REPLY_LOGIN_TOKEN = 0;    // + токен сессии
    private static final int REPLY_MARKED = 1;         // + имя студента
    private static final int REPLY_BAD_CREDENTIALS = 2;
    private static final int REPLY_RELOGIN = 3;        // токен устарел
    private static final int REPLY_UNAVAILABLE = 4;    // ПК недоступен / перекличка не идёт
    private static final int REPLY_DEVICE_BLOCKED = 5; // аккаунт привязан к другому телефону

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

        if (instruction == 0x10) {
            // данные студента: логин\nпароль\nid-устройства (первый раз) или токен\nid-устройства
            int length = apdu.length >= 5 ? apdu[4] & 0xFF : 0;
            if (length < 1 || apdu.length < 5 + length) {
                return ok(new byte[0]);
            }
            int type = apdu[5] & 0xFF;
            String data = new String(apdu, 6, length - 1, StandardCharsets.UTF_8);

            String request;
            if (type == 1) {
                String[] parts = data.split("\n", 3);
                if (parts.length < 2) {
                    return ok(new byte[0]);
                }
                String device = parts.length > 2 ? parts[2] : "";
                request = "{\"type\":1,\"login\":\"" + escape(parts[0])
                        + "\",\"password\":\"" + escape(parts[1])
                        + "\",\"device\":\"" + escape(device) + "\"}";
            } else if (type == 2) {
                String[] parts = data.split("\n", 2);
                if (parts.length < 1 || parts[0].isEmpty()) {
                    return ok(new byte[0]);
                }
                String device = parts.length > 1 ? parts[1] : "";
                request = "{\"type\":2,\"token\":\"" + escape(parts[0])
                        + "\",\"device\":\"" + escape(device) + "\"}";
            } else {
                return ok(new byte[0]);
            }

            synchronized (TAP_LOCK) {
                // отдаём запрос ПК и ждём ответ прямо в момент касания
                requests.add(request);
                String result;
                try {
                    result = resultBox.poll(4, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result = null;
                }
                if (result == null) {
                    return ok(new byte[]{REPLY_UNAVAILABLE});
                }

                int code = codeOf(result);
                String value = extractString(result, "value");
                if (code == REPLY_LOGIN_TOKEN || code == REPLY_MARKED) {
                    vibrate();
                }
                byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                byte[] payload = new byte[1 + bytes.length];
                payload[0] = (byte) code;
                System.arraycopy(bytes, 0, payload, 1, bytes.length);
                return ok(payload);
            }
        }

        return new byte[]{(byte) 0x6D, (byte) 0x00};
    }

    @Override
    public void onDeactivated(int reason) {
    }

    /** Число после "code": в ответе ПК. */
    private static int codeOf(String json) {
        try {
            int at = json.indexOf("\"code\":");
            if (at < 0) {
                return REPLY_UNAVAILABLE;
            }
            at += 7;
            int end = at;
            while (end < json.length() && Character.isDigit(json.charAt(end))) {
                end++;
            }
            return Integer.parseInt(json.substring(at, end));
        } catch (Throwable ignored) {
            return REPLY_UNAVAILABLE;
        }
    }

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

    private void vibrate() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {
        }
    }
}
