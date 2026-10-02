package ru.visits11.teacher;

import android.nfc.cardemulation.HostApduService;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
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

    /** Номера запросов: ответ опоздавшего касания не достанется следующему. */
    private static final AtomicLong SEQ = new AtomicLong();

    private static final byte[] NFC_AID = {(byte) 0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04};

    static final int REPLY_LOGIN_TOKEN = 0;    // + токен сессии
    static final int REPLY_MARKED = 1;         // + имя студента
    static final int REPLY_BAD_CREDENTIALS = 2;
    static final int REPLY_RELOGIN = 3;        // токен устарел
    static final int REPLY_UNAVAILABLE = 4;    // ПК недоступен / перекличка не идёт
    static final int REPLY_DEVICE_BLOCKED = 5; // аккаунт привязан к другому телефону

    // --- метка со ссылкой для iPhone: NFC Forum Type 4 Tag (NDEF), только чтение
    private static final byte[] NDEF_AID = {(byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01};
    private static final byte[] NDEF_CAPABILITY = {
            0x00, 0x0F,             // длина CC
            0x20,                   // версия 2.0
            0x00, 0x3B,             // макс. чтение за раз
            0x00, 0x34,             // макс. запись за раз
            0x04, 0x06,             // TLV «NDEF-файл»
            (byte) 0xE1, 0x04,      // его идентификатор
            0x02, 0x00,             // макс. размер
            0x00,                   // чтение — без ограничений
            (byte) 0xFF             // запись — запрещена
    };
    private static final int FILE_CAPABILITY = 1;
    private static final int FILE_NDEF = 2;

    private boolean ndefSelected;
    private int selectedFile;
    private byte[] ndefFile = new byte[0];

    /** Команды метки; null — это не NDEF-команда, обрабатываем как раньше. */
    private byte[] processNdef(byte[] apdu, int instruction) {
        int p1 = apdu[2] & 0xFF;

        if (instruction == 0xA4 && p1 == 0x04) {
            int length = apdu.length >= 5 ? apdu[4] & 0xFF : 0;
            if (length == NDEF_AID.length && apdu.length >= 5 + length) {
                boolean same = true;
                for (int i = 0; i < length; i++) {
                    if (apdu[5 + i] != NDEF_AID[i]) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    // iPhone выбрал метку: на каждое касание — новый код в ссылке
                    ndefSelected = true;
                    selectedFile = 0;
                    ndefFile = TapTags.ndefFile(TapTags.urlFor(TapTags.issue()));
                    return ok(new byte[0]);
                }
            }
            ndefSelected = false;   // выбрано наше приложение (студент-Android)
            return null;
        }
        if (!ndefSelected) {
            return null;
        }

        if (instruction == 0xA4 && p1 == 0x00) {
            int length = apdu.length >= 5 ? apdu[4] & 0xFF : 0;
            if (length == 2 && apdu.length >= 7) {
                int fileId = ((apdu[5] & 0xFF) << 8) | (apdu[6] & 0xFF);
                if (fileId == 0xE103) {
                    selectedFile = FILE_CAPABILITY;
                    return ok(new byte[0]);
                }
                if (fileId == 0xE104) {
                    selectedFile = FILE_NDEF;
                    return ok(new byte[0]);
                }
            }
            return new byte[]{(byte) 0x6A, (byte) 0x82};
        }
        if (instruction == 0xB0) {
            byte[] file = selectedFile == FILE_CAPABILITY ? NDEF_CAPABILITY
                    : selectedFile == FILE_NDEF ? ndefFile : null;
            if (file == null) {
                return new byte[]{(byte) 0x69, (byte) 0x86};
            }
            int offset = (p1 << 8) | (apdu[3] & 0xFF);
            int wanted = apdu.length >= 5 ? apdu[4] & 0xFF : 256;
            if (wanted == 0) {
                wanted = 256;
            }
            if (offset > file.length) {
                return new byte[]{(byte) 0x6B, 0x00};
            }
            int count = Math.min(wanted, file.length - offset);
            byte[] part = new byte[count];
            System.arraycopy(file, offset, part, 0, count);
            return ok(part);
        }
        return new byte[]{(byte) 0x6D, (byte) 0x00};
    }

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) {
            return new byte[]{(byte) 0x6A, (byte) 0x82};
        }
        int instruction = apdu[1] & 0xFF;

        byte[] tag = processNdef(apdu, instruction);
        if (tag != null) {
            return tag;
        }

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
        ndefSelected = false;
        selectedFile = 0;
    }

    /**
     * Запрос от звукового канала (AcousticLink): то же, что NFC-касание студента, —
     * отдаём запрос ПК и ждём ответ. type 1: логин\nпароль\nустройство, type 2: токен\nустройство.
     * Вернёт json-ответ ПК или null, если ПК не ответил.
     */
    static String relayAcoustic(int type, String data) {
        String request;
        if (type == 1) {
            String[] parts = data.split("\n", 3);
            if (parts.length < 2) {
                return null;
            }
            String device = parts.length > 2 ? parts[2] : "";
            request = "{\"type\":1,\"login\":\"" + escape(parts[0])
                    + "\",\"password\":\"" + escape(parts[1])
                    + "\",\"device\":\"" + escape(device) + "\"}";
        } else if (type == 2) {
            String[] parts = data.split("\n", 2);
            if (parts.length < 1 || parts[0].isEmpty()) {
                return null;
            }
            String device = parts.length > 1 ? parts[1] : "";
            request = "{\"type\":2,\"token\":\"" + escape(parts[0])
                    + "\",\"device\":\"" + escape(device) + "\"}";
        } else {
            return null;
        }

        synchronized (TAP_LOCK) {
            long id = SEQ.incrementAndGet();
            requests.add("{\"id\":" + id + "," + request.substring(1));
            long deadline = System.nanoTime() + 4_000_000_000L;
            while (true) {
                long remain = deadline - System.nanoTime();
                if (remain <= 0) {
                    return null;
                }
                String candidate;
                try {
                    candidate = resultBox.poll(remain, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (candidate == null) {
                    return null;
                }
                if (idOf(candidate) == id) {
                    return candidate;
                }
            }
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
    static int codeOf(String json) {
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

    static String extractString(String json, String key) {
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
