package ru.visits11.student;

import android.content.SharedPreferences;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.nio.charset.StandardCharsets;

/**
 * Отвечает, когда студент прикладывает телефон к телефону преподавателя:
 * первый раз отправляет логин и пароль (и получает токен сессии),
 * дальше — только токен; отметка подтверждается вибрацией.
 * Работает даже когда приложение закрыто — достаточно включённого экрана.
 */
public final class StudentCardService extends HostApduService {

    private static final String PREFS = "visits11student";
    private static final String KEY_LOGIN = "login";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_TOKEN = "token";

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
            // «кто ты»: токен, если уже входили, иначе логин и пароль
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String token = prefs.getString(KEY_TOKEN, null);
            if (token != null) {
                return ok(wrap((byte) 2, token.getBytes(StandardCharsets.UTF_8)));
            }
            String login = prefs.getString(KEY_LOGIN, null);
            String password = prefs.getString(KEY_PASSWORD, null);
            if (login == null || password == null) {
                return ok(wrap((byte) 0, new byte[0]));
            }
            return ok(wrap((byte) 1, (login + "\n" + password).getBytes(StandardCharsets.UTF_8)));
        }

        if (instruction == 0x20) {
            // результат от преподавателя
            int length = apdu[4] & 0xFF;
            if (length < 1 || apdu.length < 5 + length) {
                return ok(new byte[0]);
            }
            int result = apdu[5] & 0xFF;
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (result == 0 && length > 1) {
                // вход выполнен — сохраняем токен
                String token = new String(apdu, 6, length - 1, StandardCharsets.UTF_8);
                prefs.edit().putString(KEY_TOKEN, token).apply();
                vibrate();
            } else if (result == 1) {
                // отмечен
                vibrate();
            } else if (result == 3) {
                // сессия устарела — при следующем прикладывании перевойдём
                prefs.edit().remove(KEY_TOKEN).apply();
            }
            return ok(new byte[0]);
        }

        return new byte[]{(byte) 0x6D, (byte) 0x00};
    }

    @Override
    public void onDeactivated(int reason) {
    }

    private static byte[] wrap(byte type, byte[] data) {
        byte[] packet = new byte[1 + data.length];
        packet[0] = type;
        System.arraycopy(data, 0, packet, 1, data.length);
        return packet;
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
