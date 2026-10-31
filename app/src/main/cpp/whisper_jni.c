// Bridge between the app (Kotlin) and whisper.cpp.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "whisper.h"

// Segments Whisper itself rates as probably not speech are dropped.
#define NO_SPEECH_LIMIT 0.6f

static void quiet_log(enum ggml_log_level level, const char *text, void *user_data) {
    (void) level;
    (void) text;
    (void) user_data;
}

static struct whisper_context *as_context(jlong handle) {
    return (struct whisper_context *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_com_clipcaptions_app_WhisperLib_nativeInit(JNIEnv *env, jclass clazz, jstring model_path) {
    (void) clazz;
    whisper_log_set(quiet_log, NULL);

    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    if (path == NULL) return 0;

    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, params);

    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_com_clipcaptions_app_WhisperLib_nativeFree(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    struct whisper_context *ctx = as_context(handle);
    if (ctx != NULL) whisper_free(ctx);
}

// Transcribes (or translates into English) one chunk of 16 kHz mono audio.
// Returns the text as UTF-8 bytes (empty when no speech was found), or null on failure.
JNIEXPORT jbyteArray JNICALL
Java_com_clipcaptions_app_WhisperLib_nativeTranscribe(
        JNIEnv *env, jclass clazz, jlong handle, jfloatArray samples,
        jint threads, jboolean translate) {
    (void) clazz;
    struct whisper_context *ctx = as_context(handle);
    if (ctx == NULL || samples == NULL) return NULL;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 2;
    params.translate = translate == JNI_TRUE;
    params.language = "auto";
    params.detect_language = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f; // No slow retries; the app filters out bad output instead.
    params.no_speech_thold = NO_SPEECH_LIMIT;

    // Speech detection is done separately (nativeHasSpeech), on one thread.
    params.vad = false;

    jsize count = (*env)->GetArrayLength(env, samples);
    jfloat *audio = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (audio == NULL) return NULL;

    int result = whisper_full(ctx, params, audio, count);

    (*env)->ReleaseFloatArrayElements(env, samples, audio, JNI_ABORT);
    if (result != 0) return NULL;

    // Join the segments, skipping any that are probably not speech.
    size_t capacity = 256;
    size_t length = 0;
    char *text = malloc(capacity);
    if (text == NULL) return NULL;
    text[0] = '\0';

    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        if (whisper_full_get_segment_no_speech_prob(ctx, i) > NO_SPEECH_LIMIT) continue;
        const char *segment = whisper_full_get_segment_text(ctx, i);
        if (segment == NULL) continue;
        size_t segment_length = strlen(segment);
        if (length + segment_length + 2 > capacity) {
            while (length + segment_length + 2 > capacity) capacity *= 2;
            char *bigger = realloc(text, capacity);
            if (bigger == NULL) {
                free(text);
                return NULL;
            }
            text = bigger;
        }
        if (length > 0) text[length++] = ' ';
        memcpy(text + length, segment, segment_length);
        length += segment_length;
        text[length] = '\0';
    }

    // Raw bytes rather than a Java string: Whisper can split a character across segments,
    // which would be invalid for NewStringUTF. Kotlin decodes these safely.
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize) length);
    if (bytes != NULL && length > 0) {
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize) length, (const jbyte *) text);
    }
    free(text);
    return bytes;
}

// The language Whisper detected in the last chunk, as a short code such as "es", or "" if unknown.
JNIEXPORT jstring JNICALL
Java_com_clipcaptions_app_WhisperLib_nativeLanguage(JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    struct whisper_context *ctx = as_context(handle);
    if (ctx == NULL) return (*env)->NewStringUTF(env, "");
    int id = whisper_full_lang_id(ctx);
    const char *code = id >= 0 ? whisper_lang_str(id) : NULL;
    return (*env)->NewStringUTF(env, code != NULL ? code : "");
}

// ---- Speech detector (Silero VAD) ----
// Run on a single thread: the model is tiny, and extra threads only fight the video for the CPU.

// At least this much speech (in centiseconds) is needed before a chunk goes to Whisper.
#define MIN_SPEECH_CS 25.0f

JNIEXPORT jlong JNICALL
Java_com_clipcaptions_app_WhisperLib_nativeInitVad(JNIEnv *env, jclass clazz, jstring model_path) {
    (void) clazz;
    whisper_log_set(quiet_log, NULL);

    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    if (path == NULL) return 0;

    struct whisper_vad_context_params params = whisper_vad_default_context_params();
    params.n_threads = 1;
    params.use_gpu = false;
    struct whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(path, params);

    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong) (intptr_t) vctx;
}

JNIEXPORT void JNICALL
Java_com_clipcaptions_app_WhisperLib_nativeFreeVad(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    struct whisper_vad_context *vctx = (struct whisper_vad_context *) (intptr_t) handle;
    if (vctx != NULL) whisper_vad_free(vctx);
}

// True if the chunk contains enough speech to be worth sending to Whisper.
JNIEXPORT jboolean JNICALL
Java_com_clipcaptions_app_WhisperLib_nativeHasSpeech(JNIEnv *env, jclass clazz, jlong handle, jfloatArray samples) {
    (void) clazz;
    struct whisper_vad_context *vctx = (struct whisper_vad_context *) (intptr_t) handle;
    if (vctx == NULL || samples == NULL) return JNI_TRUE; // No detector: let Whisper decide.

    jsize count = (*env)->GetArrayLength(env, samples);
    jfloat *audio = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (audio == NULL) return JNI_TRUE;

    struct whisper_vad_segments *segments =
            whisper_vad_segments_from_samples(vctx, whisper_vad_default_params(), audio, count);
    (*env)->ReleaseFloatArrayElements(env, samples, audio, JNI_ABORT);
    if (segments == NULL) return JNI_TRUE;

    float speech = 0.0f;
    int n = whisper_vad_segments_n_segments(segments);
    for (int i = 0; i < n; i++) {
        speech += whisper_vad_segments_get_segment_t1(segments, i) - whisper_vad_segments_get_segment_t0(segments, i);
    }
    whisper_vad_free_segments(segments);
    return speech >= MIN_SPEECH_CS ? JNI_TRUE : JNI_FALSE;
}
