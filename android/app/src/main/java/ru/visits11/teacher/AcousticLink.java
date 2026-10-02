package ru.visits11.teacher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Звуковой канал с iPhone студента (ультразвук, запасной — слышимый сигнал).
 *
 * После касания NFC-метки iPhone открывает страницу и присылает звуком:
 *   <тип 1|2><режим u|a><код касания, 8 hex>\n<данные>
 * Данные те же, что в NFC-обмене Android (type 1: логин\nпароль\nустройство,
 * type 2: токен\nустройство) — мы передаём их на ПК тем же путём и отвечаем звуком:
 *   <код касания><результат><значение>
 * Коды результата — как REPLY_* в TeacherCardService, плюс 6 — «код касания устарел».
 */
final class AcousticLink {

    private static final int FRAME_FALLBACK = 1024;
    private static final int REPLY_STALE_TAP = 6;
    private static final int NAME_LIMIT_BYTES = 24;

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile boolean running;
    private volatile boolean transmitting;
    private Thread listener;

    AcousticLink(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void start() {
        if (running || !Ggwave.load()) {
            return;
        }
        running = true;
        raiseVolume();
        listener = new Thread(this::listenLoop, "AcousticRx");
        listener.start();
    }

    synchronized void stop() {
        running = false;
        if (listener != null) {
            listener.interrupt();
            listener = null;
        }
    }

    // ------------------------------------------------------------ приём

    private void listenLoop() {
        int frame = Ggwave.samplesPerFrame();
        if (frame <= 0) {
            frame = FRAME_FALLBACK;
        }
        int minBuffer = AudioRecord.getMinBufferSize(Ggwave.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = null;
        int rx = -1;
        try {
            record = new AudioRecord(inputSource(), Ggwave.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer, frame * 2 * 8));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                return;
            }
            rx = Ggwave.create();
            record.startRecording();

            short[] buffer = new short[frame];
            while (running) {
                int filled = 0;
                while (filled < frame && running) {
                    int read = record.read(buffer, filled, frame - filled);
                    if (read <= 0) {
                        return;
                    }
                    filled += read;
                }
                if (!running || filled < frame) {
                    break;
                }
                if (transmitting) {
                    continue;   // свой звук не слушаем
                }
                byte[] message = Ggwave.decode(rx, buffer, frame);
                if (message != null) {
                    worker.execute(() -> handle(message));
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (record != null) {
                try {
                    record.stop();
                } catch (Throwable ignored) {
                }
                record.release();
            }
            if (rx >= 0) {
                Ggwave.destroy(rx);
            }
            running = false;
        }
    }

    /** Без обработки звука (шумодав вырезал бы ультразвук), если телефон это умеет. */
    private int inputSource() {
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null && "true".equals(
                    manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))) {
                return MediaRecorder.AudioSource.UNPROCESSED;
            }
        } catch (Throwable ignored) {
        }
        return MediaRecorder.AudioSource.MIC;
    }

    // ------------------------------------------------------------ разбор и ответ

    private void handle(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() < 11 || text.charAt(10) != '\n') {
            return;
        }
        char type = text.charAt(0);
        char mode = text.charAt(1);
        if ((type != '1' && type != '2') || (mode != 'u' && mode != 'a')) {
            return;
        }
        String code = text.substring(2, 10);
        String data = text.substring(11);

        String reply = TapTags.cachedReply(code);
        if (reply == null) {
            if (!TapTags.valid(code)) {
                reply = code + REPLY_STALE_TAP;
            } else {
                String result = TeacherCardService.relayAcoustic(type - '0', data);
                int status = result == null
                        ? TeacherCardService.REPLY_UNAVAILABLE : TeacherCardService.codeOf(result);
                String value = result == null ? null : TeacherCardService.extractString(result, "value");
                if (value == null) {
                    value = "";
                }
                if (status == TeacherCardService.REPLY_MARKED) {
                    value = limitBytes(value, NAME_LIMIT_BYTES);
                }
                if (status == TeacherCardService.REPLY_LOGIN_TOKEN
                        || status == TeacherCardService.REPLY_MARKED) {
                    vibrate();
                }
                reply = code + status + value;
                // окончательные ответы запоминаем: если iPhone не расслышал — повторим без ПК
                if (status == TeacherCardService.REPLY_LOGIN_TOKEN
                        || status == TeacherCardService.REPLY_MARKED
                        || status == TeacherCardService.REPLY_BAD_CREDENTIALS
                        || status == TeacherCardService.REPLY_DEVICE_BLOCKED) {
                    TapTags.cacheReply(code, reply);
                }
            }
        }
        transmit(reply, mode);
    }

    private static String limitBytes(String value, int limit) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= limit) {
            return value;
        }
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < value.length(); i++) {
            String ch = value.substring(i, i + 1);
            int size = ch.getBytes(StandardCharsets.UTF_8).length;
            if (used + size > limit) {
                break;
            }
            out.append(ch);
            used += size;
        }
        return out.toString();
    }

    // ------------------------------------------------------------ передача

    private void transmit(String text, char mode) {
        int tx = -1;
        AudioTrack track = null;
        try {
            tx = Ggwave.create();
            boolean ultrasound = mode == 'u';
            byte[] pcm = Ggwave.encode(tx, text.getBytes(StandardCharsets.UTF_8),
                    ultrasound ? Ggwave.PROTOCOL_ULTRASOUND_FAST : Ggwave.PROTOCOL_AUDIBLE_FAST,
                    ultrasound ? 70 : 45);
            if (pcm == null || pcm.length == 0) {
                return;
            }
            transmitting = true;
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(Ggwave.SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(pcm, 0, pcm.length);
            track.play();
            long durationMs = pcm.length / 2L * 1000L / Ggwave.SAMPLE_RATE;
            Thread.sleep(durationMs + 300);
        } catch (Throwable ignored) {
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignored) {
                }
                track.release();
            }
            if (tx >= 0) {
                Ggwave.destroy(tx);
            }
            try {
                Thread.sleep(200);   // «хвост» своего звука не должен попасть в приём
            } catch (InterruptedException ignored) {
            }
            transmitting = false;
        }
    }

    /** Громкость мультимедиа — на максимум: ультразвук тихим динамиком не слышен. */
    private void raiseVolume() {
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null) {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC,
                        manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Заметная двойная вибрация — отметка прошла. */
    private void vibrate() {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager =
                        (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = manager != null ? manager.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 90, 70, 90}, -1));
            }
        } catch (Throwable ignored) {
        }
    }
}
