package ru.visits11.teacher;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NFC-метка со ссылкой для iPhone (NDEF Type 4 Tag, отдаётся из TeacherCardService)
 * и одноразовые коды касаний.
 *
 * На каждое чтение метки выдаётся новый код. iPhone открывает ссылку
 * {адрес приложения студента}#n={код}, а потом присылает этот код звуком вместе
 * с отметкой: так ответ привязан именно к этому касанию, а записанный звук
 * потом не проиграть. Повторный запрос с тем же кодом получает тот же ответ
 * (если iPhone не расслышал первый).
 */
final class TapTags {

    private static final long TTL_MS = 120_000;
    private static final int MAX_CODES = 64;
    private static final String FALLBACK_URL = "https://visits11.local:8090/";

    /** Адрес приложения студента — присылает ПК вместе с опросом /event. */
    static volatile String baseUrl = "";

    private static final class Entry {
        final long issuedAt = System.currentTimeMillis();
        String reply;
    }

    private static final Map<String, Entry> CODES = new LinkedHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TapTags() {
    }

    private static void evict() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = CODES.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().issuedAt > TTL_MS) {
                it.remove();
            }
        }
        while (CODES.size() >= MAX_CODES) {
            Iterator<String> first = CODES.keySet().iterator();
            first.next();
            first.remove();
        }
    }

    /** Новый код касания (8 hex-символов). */
    static synchronized String issue() {
        evict();
        char[] code = new char[8];
        for (int i = 0; i < code.length; i++) {
            code[i] = HEX[RANDOM.nextInt(16)];
        }
        String value = new String(code);
        CODES.put(value, new Entry());
        return value;
    }

    static synchronized boolean valid(String code) {
        evict();
        return code != null && CODES.containsKey(code);
    }

    /** Уже отправленный окончательный ответ на это касание (или null). */
    static synchronized String cachedReply(String code) {
        evict();
        Entry entry = CODES.get(code);
        return entry == null ? null : entry.reply;
    }

    static synchronized void cacheReply(String code, String reply) {
        Entry entry = CODES.get(code);
        if (entry != null) {
            entry.reply = reply;
        }
    }

    /** Ссылка, которую увидит iPhone при касании с этим кодом. */
    static String urlFor(String code) {
        String base = baseUrl;
        if (base == null || base.isEmpty()) {
            base = FALLBACK_URL;
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base + "#n=" + code;
    }

    /** Содержимое NDEF-файла метки: [NLEN:2][запись URI]. */
    static byte[] ndefFile(String url) {
        String rest = url;
        int prefix = 0;
        if (url.startsWith("https://")) {
            prefix = 0x04;
            rest = url.substring(8);
        } else if (url.startsWith("http://")) {
            prefix = 0x03;
            rest = url.substring(7);
        }
        byte[] tail = rest.getBytes(StandardCharsets.UTF_8);
        int payloadLength = 1 + tail.length;
        byte[] record = new byte[4 + payloadLength];
        record[0] = (byte) 0xD1;              // MB | ME | SR | TNF = «известный тип»
        record[1] = 0x01;                     // длина типа
        record[2] = (byte) payloadLength;     // длина данных (короткая запись, < 256)
        record[3] = 0x55;                     // тип «U» — URI
        record[4] = (byte) prefix;
        System.arraycopy(tail, 0, record, 5, tail.length);

        byte[] file = new byte[2 + record.length];
        file[0] = (byte) (record.length >> 8);
        file[1] = (byte) record.length;
        System.arraycopy(record, 0, file, 2, record.length);
        return file;
    }
}
