package ru.visits11.teacher;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
/** USB event broker. Historical class name retained; not an Android service. */
public final class TeacherCardService {
    static final LinkedBlockingQueue<String> requests = new LinkedBlockingQueue<>();
    static final SynchronousQueue<String> resultBox = new SynchronousQueue<>();
    static final Object TAP_LOCK = new Object();
    private static final AtomicLong SEQ = new AtomicLong();
    static final int REPLY_MARKED=0, REPLY_UNKNOWN_QR=2, REPLY_UNAVAILABLE=4, REPLY_DEVICE_BLOCKED=5;
    /**
     * Передаёт данные студента (код из QR + id телефона) на ПК по USB и ждёт ответ.
     * Используется и для Android (BLE), и для iPhone (BLE при поднесении вплотную).
     */
    static byte[] exchangeWithPc(String token, String device, Runnable onMarked) {
        String request = "{\"token\":\"" + escape(token) + "\",\"device\":\"" + escape(device) + "\"}";

        synchronized (TAP_LOCK) {
            requests.clear();
            resultBox.poll();

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
                return new byte[]{REPLY_UNAVAILABLE};
            }

            int code = codeOf(result);
            String value = extractString(result, "value");
            if (code == REPLY_MARKED && onMarked != null) {
                onMarked.run();
            }
            byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[1 + bytes.length];
            payload[0] = (byte) code;
            System.arraycopy(bytes, 0, payload, 1, bytes.length);
            return payload;
        }
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

}
