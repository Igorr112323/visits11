package ru.visits11.teacher;

/** Мост к ggwave (cpp/ggwave_jni.cpp): звуковые сообщения, 16-бит PCM, 48 кГц, моно. */
final class Ggwave {

    /** Слышимый «Fast» (запасной режим). */
    static final int PROTOCOL_AUDIBLE_FAST = 1;
    /** Ультразвуковой «[U] Fast». */
    static final int PROTOCOL_ULTRASOUND_FAST = 4;

    static final int SAMPLE_RATE = 48000;

    private static boolean loaded;

    static synchronized boolean load() {
        if (!loaded) {
            try {
                System.loadLibrary("ggwavejni");
                loaded = true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    /** Новый экземпляр (id может быть 0 — это настоящий id). */
    static native int create();

    static native void destroy(int instance);

    static native int samplesPerFrame();

    /** PCM звукового сообщения или null. */
    static native byte[] encode(int instance, byte[] payload, int protocol, int volume);

    /** Подать кадр микрофона; вернёт сообщение, когда оно дослушано до конца, иначе null. */
    static native byte[] decode(int instance, short[] frame, int samples);

    private Ggwave() {
    }
}
