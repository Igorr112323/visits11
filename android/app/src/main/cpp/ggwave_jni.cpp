// JNI-мост к ggwave: кодирование и разбор звуковых сообщений (16-бит PCM, 48 кГц, моно).
#include <jni.h>
#include <vector>
#include "ggwave/ggwave.h"

extern "C" {

JNIEXPORT jint JNICALL
Java_ru_visits11_teacher_Ggwave_create(JNIEnv *, jclass) {
    ggwave_setLogFile(nullptr);
    ggwave_Parameters parameters = ggwave_getDefaultParameters();
    parameters.sampleRateInp = 48000;
    parameters.sampleRateOut = 48000;
    parameters.sampleRate = 48000;
    parameters.sampleFormatInp = GGWAVE_SAMPLE_FORMAT_I16;
    parameters.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_I16;
    parameters.operatingMode = GGWAVE_OPERATING_MODE_RX_AND_TX;
    return (jint) ggwave_init(parameters);
}

JNIEXPORT void JNICALL
Java_ru_visits11_teacher_Ggwave_destroy(JNIEnv *, jclass, jint instance) {
    ggwave_free((ggwave_Instance) instance);
}

JNIEXPORT jint JNICALL
Java_ru_visits11_teacher_Ggwave_samplesPerFrame(JNIEnv *, jclass) {
    return (jint) ggwave_getDefaultParameters().samplesPerFrame;
}

// PCM (16 бит, little-endian) звукового сообщения либо null
JNIEXPORT jbyteArray JNICALL
Java_ru_visits11_teacher_Ggwave_encode(JNIEnv *env, jclass, jint instance, jbyteArray payload,
                                       jint protocol, jint volume) {
    jsize length = env->GetArrayLength(payload);
    std::vector<char> text((size_t) length);
    env->GetByteArrayRegion(payload, 0, length, reinterpret_cast<jbyte *>(text.data()));

    int size = ggwave_encode((ggwave_Instance) instance, text.data(), (int) length,
                             (ggwave_ProtocolId) protocol, (int) volume, nullptr, 1);
    if (size <= 0) {
        return nullptr;
    }
    std::vector<char> waveform((size_t) size);
    int written = ggwave_encode((ggwave_Instance) instance, text.data(), (int) length,
                                (ggwave_ProtocolId) protocol, (int) volume, waveform.data(), 0);
    if (written <= 0) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(written);
    env->SetByteArrayRegion(result, 0, written, reinterpret_cast<jbyte *>(waveform.data()));
    return result;
}

// один кадр (samplesPerFrame отсчётов) → байты сообщения, если оно только что дослушано, иначе null
JNIEXPORT jbyteArray JNICALL
Java_ru_visits11_teacher_Ggwave_decode(JNIEnv *env, jclass, jint instance, jshortArray frame,
                                       jint samples) {
    std::vector<jshort> pcm((size_t) samples);
    env->GetShortArrayRegion(frame, 0, samples, pcm.data());

    char output[256];
    int n = ggwave_decode((ggwave_Instance) instance, pcm.data(), samples * (int) sizeof(jshort),
                          output);
    if (n <= 0) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(n);
    env->SetByteArrayRegion(result, 0, n, reinterpret_cast<jbyte *>(output));
    return result;
}

}  // extern "C"
