package ru.visits11.teacher;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Телефон преподавателя «притворяется картой»: студенты (Android и iPhone)
 * прикладывают свои телефоны-считыватели, мы пересылаем их данные на ПК
 * по USB-каналу камеры и возвращаем ответ прямо в момент касания.
 */
public final class TeacherCardService extends HostApduService {

    /** Адрес ПК — обновляется из запросов камеры (заголовок X-Host). */
    public static volatile String pcAddress = "";

    private static final byte[] NFC_AID = {(byte) 0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04};

    private static final int REPLY_LOGIN_TOKEN = 0;  // + токен сессии
    private static final int REPLY_MARKED = 1;       // + имя студента
    private static final int REPLY_BAD_CREDENTIALS = 2;
    private static final int REPLY_RELOGIN = 3;      // токен устарел
    private static final int REPLY_UNAVAILABLE = 4;  // ПК недоступен / перекличка не идёт

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
            // данные студента: логин+пароль (первый раз) или токен
            int length = apdu.length >= 5 ? apdu[4] & 0xFF : 0;
            if (length < 1 || apdu.length < 5 + length) {
                return ok(new byte[0]);
            }
            int type = apdu[5] & 0xFF;
            String data = new String(apdu, 6, length - 1, StandardCharsets.UTF_8);

            NfcReply reply;
            if (type == 1) {
                int split = data.indexOf('\n');
                if (split <= 0) {
                    return ok(new byte[0]);
                }
                reply = postLogin(data.substring(0, split), data.substring(split + 1));
                if (reply.code == REPLY_LOGIN_TOKEN) {
                    // первое касание отмечает сразу же
                    postMark(new String(reply.value, StandardCharsets.UTF_8));
                }
            } else if (type == 2) {
                reply = postMark(data);
            } else {
                return ok(new byte[0]);
            }

            if (reply.code == REPLY_LOGIN_TOKEN || reply.code == REPLY_MARKED) {
                vibrate();
            }

            byte[] payload = new byte[1 + (reply.value == null ? 0 : reply.value.length)];
            payload[0] = (byte) reply.code;
            if (reply.value != null) {
                System.arraycopy(reply.value, 0, payload, 1, reply.value.length);
            }
            return ok(payload);
        }

        return new byte[]{(byte) 0x6D, (byte) 0x00};
    }

    @Override
    public void onDeactivated(int reason) {
    }

    private static final class NfcReply {
        final int code;
        final byte[] value;

        NfcReply(int code, String value) {
            this.code = code;
            this.value = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static NfcReply postLogin(String login, String password) {
        String address = pcAddress;
        if (address == null || address.isEmpty()) {
            return new NfcReply(REPLY_UNAVAILABLE, null);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://" + address + "/api/login").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            byte[] body = ("{\"login\":\"" + escape(login)
                    + "\",\"password\":\"" + escape(password) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json");
            OutputStream output = connection.getOutputStream();
            output.write(body);
            output.flush();
            output.close();
            if (connection.getResponseCode() != 200) {
                return new NfcReply(REPLY_UNAVAILABLE, null);
            }
            String response = readAll(connection.getInputStream(), 8192);
            if (!response.contains("\"ok\":true")) {
                return new NfcReply(REPLY_BAD_CREDENTIALS, null);
            }
            String token = extractString(response, "token");
            if (token == null || token.isEmpty()) {
                return new NfcReply(REPLY_BAD_CREDENTIALS, null);
            }
            return new NfcReply(REPLY_LOGIN_TOKEN, token);
        } catch (IOException e) {
            return new NfcReply(REPLY_UNAVAILABLE, null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static NfcReply postMark(String token) {
        String address = pcAddress;
        if (address == null || address.isEmpty()) {
            return new NfcReply(REPLY_UNAVAILABLE, null);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://" + address + "/api/nfc_mark").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            byte[] body = ("{\"token\":\"" + escape(token) + "\"}").getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json");
            OutputStream output = connection.getOutputStream();
            output.write(body);
            output.flush();
            output.close();
            if (connection.getResponseCode() != 200) {
                return new NfcReply(REPLY_UNAVAILABLE, null);
            }
            String response = readAll(connection.getInputStream(), 8192);
            if (response.contains("\"ok\":true")) {
                String name = extractString(response, "name");
                return new NfcReply(REPLY_MARKED, name == null ? "" : name);
            }
            if (response.contains("\"relogin\":true")) {
                return new NfcReply(REPLY_RELOGIN, null);
            }
            return new NfcReply(REPLY_UNAVAILABLE, null);
        } catch (IOException e) {
            return new NfcReply(REPLY_UNAVAILABLE, null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream input, int cap) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while (output.size() < cap && (read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
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
