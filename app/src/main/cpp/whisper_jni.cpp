// JNI bridge between WhisperNative.kt and whisper.cpp.
//
// Deliberately thin: everything that can be decided in Kotlin (padding, audio context,
// language, cleanup) is decided there and tested in :core. This file only moves arrays across
// the boundary and turns whisper.cpp's return codes into exceptions.

#include <jni.h>
#include <android/log.h>
#include <asm/hwcap.h>
#include <dirent.h>
#include <sys/auxv.h>

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <set>
#include <sstream>
#include <string>
#include <vector>

#include "ggml-cpu.h"
#include "whisper.h"

// Defined by patch-whisper.cmake inside whisper.cpp: the CPU thread pool every newly loaded
// model computes on.
extern "C" ggml_threadpool_t whisper_amber_cpu_threadpool;

#define TAG "Amber"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// One dictation runs at a time, so a single flag is enough. Reset at the start of every run.
std::atomic<bool> g_abort{false};

bool abort_requested(void * /* user_data */) {
    return g_abort.load(std::memory_order_relaxed);
}

void throw_runtime(JNIEnv *env, const std::string &message) {
    jclass cls = env->FindClass("app/amethyst/core/engine/whisper/WhisperRuntimeException");
    if (cls == nullptr) {
        env->ExceptionClear();
        cls = env->FindClass("java/lang/IllegalStateException");
    }
    env->ThrowNew(cls, message.c_str());
}

// The library is built for armv8.2-a with dot-product and fp16 instructions (CMakeLists.txt);
// an ARMv8.0 core (Cortex-A53 and the like) would die on the first of them with SIGILL - and
// take the accessibility service with it. Asked of the kernel before any model is touched.
bool cpu_supported() {
    const unsigned long caps = getauxval(AT_HWCAP);
    return (caps & HWCAP_ASIMDDP) != 0 && (caps & HWCAP_ASIMDHP) != 0;
}

// A Java string as std::string; false (with an exception pending) when the VM is out of memory.
bool read_string(JNIEnv *env, jstring value, std::string &out) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return false;
    out.assign(chars);
    env->ReleaseStringUTFChars(value, chars);
    return true;
}

// whisper.cpp logs every layer it loads; keep warnings and errors only.
void quiet_log(ggml_log_level level, const char *text, void * /* user_data */) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

// One pool for the life of the process. Its threads sleep on a condition variable between
// dictations (see pause_pool), so keeping it costs a few stacks of memory, not battery.
ggml_threadpool_t g_pool = nullptr;

// The language the last transcription ran in (detected, when several were allowed). Read by
// Kotlin right after transcribe, on the same thread.
std::string g_last_language;
std::vector<int> g_pool_tids;

std::set<int> list_threads() {
    std::set<int> tids;
    if (DIR *dir = opendir("/proc/self/task")) {
        while (dirent *entry = readdir(dir)) {
            const int tid = atoi(entry->d_name);
            if (tid > 0) tids.insert(tid);
        }
        closedir(dir);
    }
    return tids;
}

// Created from the thread that loads the model, so the workers inherit its priority. Their
// kernel ids are found by comparing the thread list before and after: ggml does not expose
// them, and the performance hint session needs them.
void ensure_pool(int threads) {
    if (g_pool != nullptr || threads < 1) return;
    const std::set<int> before = list_threads();
    ggml_threadpool_params params = ggml_threadpool_params_default(threads);
    params.paused = true;
    g_pool = ggml_threadpool_new(&params);
    if (g_pool == nullptr) {
        LOGW("could not create the CPU thread pool, falling back to per-graph threads");
        return;
    }
    for (int tid : list_threads()) {
        if (before.count(tid) == 0) g_pool_tids.push_back(tid);
    }
    whisper_amber_cpu_threadpool = g_pool;
    LOGI("CPU thread pool: %d threads, %zu workers", threads, g_pool_tids.size());
}

void pause_pool() {
    if (g_pool != nullptr) ggml_threadpool_pause(g_pool);
}

// Stops whisper_full right before its first encoder pass. Used to prime the state; see
// detect_among().
bool stop_before_encoding(whisper_context * /* ctx */, whisper_state * /* state */, void * /* user_data */) {
    return false;
}

std::vector<std::string> split_languages(const std::string &list) {
    std::vector<std::string> out;
    std::stringstream stream(list);
    std::string code;
    while (std::getline(stream, code, ',')) {
        if (!code.empty() && whisper_lang_id(code.c_str()) >= 0) out.push_back(code);
    }
    return out;
}

// Picks the most likely of [allowed] for this audio. Whisper's own "auto" chooses among all
// ~100 languages and on a short dictation happily lands on Dutch or Norwegian; fixing one
// language instead makes it translate whatever else is spoken into that one. Restricting the
// choice to the languages the user actually speaks avoids both.
//
// whisper_lang_auto_detect encodes with the audio context left in the state by the previous
// whisper_full, so a first whisper_full that stops before encoding sets this dictation's
// context (and computes the mel) without paying for a transcription. whisper.cpp logs that
// stop as "encoder_begin_callback returned false - aborting"; here it is expected.
//
// Detection looks at the first ~10 s only, with an encoder context to match: the language is
// clear by then, and a full 30 s encoder pass here would nearly double the cost of the
// dictation - the encoder is most of large-v3-turbo's work.
constexpr int kDetectSamples = 16000 * 10;
constexpr int kDetectAudioCtx = 512;  // 1500 positions are 30 s; 512 cover ~10 s

std::string detect_among(whisper_context *ctx, const std::vector<std::string> &allowed,
                         const float *samples, int count, int audio_ctx, int threads) {
    whisper_full_params prime = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    prime.n_threads = threads;
    prime.audio_ctx = audio_ctx > 0 ? std::min(audio_ctx, kDetectAudioCtx) : kDetectAudioCtx;
    count = std::min(count, kDetectSamples);
    prime.language = allowed.front().c_str();
    prime.detect_language = false;
    prime.print_progress = false;
    prime.encoder_begin_callback = stop_before_encoding;
    whisper_full(ctx, prime, samples, count);

    std::vector<float> probs(whisper_lang_max_id() + 1, 0.0f);
    if (whisper_lang_auto_detect(ctx, 0, threads, probs.data()) < 0) {
        LOGW("language detection failed, using %s", allowed.front().c_str());
        return allowed.front();
    }
    std::string best = allowed.front();
    float best_p = -1.0f;
    for (const auto &code : allowed) {
        const float p = probs[whisper_lang_id(code.c_str())];
        if (p > best_p) {
            best_p = p;
            best = code;
        }
    }
    LOGI("detected %s (p=%.2f among the allowed languages)", best.c_str(), best_p);
    return best;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_amethyst_engine_WhisperNative_load(JNIEnv *env, jobject /* this */, jstring jpath, jint threads) {
    whisper_log_set(quiet_log, nullptr);
    if (!cpu_supported()) {
        throw_runtime(env, "cpu unsupported: local Whisper needs the ARMv8.2 dot-product instructions");
        return 0;
    }
    // Before the context: whisper.cpp hands the pool to its CPU backend while loading.
    ensure_pool(threads);

    std::string path;
    if (!read_string(env, jpath, path)) return 0;
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = nullptr;
    // A C++ exception must not cross into the VM: it would abort the whole process.
    try {
        ctx = whisper_init_from_file_with_params(path.c_str(), params);
    } catch (const std::exception &e) {
        throw_runtime(env, std::string("model load failed: ") + e.what());
        return 0;
    } catch (...) {
        throw_runtime(env, "model load failed");
        return 0;
    }

    if (ctx == nullptr) {
        throw_runtime(env, "whisper_init_from_file_with_params returned null");
        return 0;
    }
    LOGI("whisper model loaded");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_app_amethyst_engine_WhisperNative_free(JNIEnv * /* env */, jobject /* this */, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_amethyst_engine_WhisperNative_lastLanguage(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(g_last_language.c_str());
}

// Kernel thread ids of the pool's workers, for the performance hint session. Empty when there
// is no pool yet or it could not be created.
extern "C" JNIEXPORT jintArray JNICALL
Java_app_amethyst_engine_WhisperNative_workerTids(JNIEnv *env, jobject /* this */) {
    jintArray out = env->NewIntArray(static_cast<jsize>(g_pool_tids.size()));
    if (out == nullptr) return nullptr;
    if (!g_pool_tids.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(g_pool_tids.size()), g_pool_tids.data());
    }
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_app_amethyst_engine_WhisperNative_abort(JNIEnv * /* env */, jobject /* this */) {
    g_abort.store(true, std::memory_order_relaxed);
}

// Returns UTF-8 bytes rather than a jstring: whisper.cpp can cut a multi-byte character at a
// token boundary, and NewStringUTF aborts the process on invalid input under CheckJNI.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_amethyst_engine_WhisperNative_transcribe(
        JNIEnv *env,
        jobject /* this */,
        jlong handle,
        jfloatArray jsamples,
        jstring jlanguage,
        jint audio_ctx,
        jint threads,
        jstring jprompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) {
        throw_runtime(env, "model not loaded");
        return nullptr;
    }

    g_abort.store(false, std::memory_order_relaxed);

    // "auto", a single code ("de"), or a list ("de,en") to detect between.
    std::string requested;
    // Text recognized just before this audio, when it is one chunk of a longer dictation.
    std::string prompt;
    if (!read_string(env, jlanguage, requested) || !read_string(env, jprompt, prompt)) return nullptr;

    jsize count = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    if (samples == nullptr) return nullptr;  // out of memory, exception pending

    std::string lang = requested;
    try {
        if (requested != "auto") {
            const std::vector<std::string> allowed = split_languages(requested);
            if (allowed.empty()) {
                lang = "auto";
            } else if (allowed.size() == 1) {
                lang = allowed.front();
            } else {
                lang = detect_among(ctx, allowed, samples, count, audio_ctx, threads);
            }
        }
    } catch (...) {
        env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
        pause_pool();
        throw_runtime(env, "language detection failed");
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.audio_ctx = audio_ctx;
    params.language = lang.c_str();
    params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    params.detect_language = false;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.abort_callback = abort_requested;
    params.abort_callback_user_data = nullptr;

    int rc;
    try {
        rc = whisper_full(ctx, params, samples, count);
    } catch (const std::exception &e) {
        env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
        pause_pool();
        throw_runtime(env, std::string("whisper_full threw: ") + e.what());
        return nullptr;
    } catch (...) {
        env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
        pause_pool();
        throw_runtime(env, "whisper_full threw");
        return nullptr;
    }
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
    // Between dictations the workers wait on a condition variable instead of polling.
    pause_pool();

    if (g_abort.load(std::memory_order_relaxed)) {
        throw_runtime(env, "aborted");
        return nullptr;
    }
    if (rc != 0) {
        LOGW("whisper_full returned %d", rc);
        throw_runtime(env, "whisper_full returned " + std::to_string(rc));
        return nullptr;
    }

    const int lang_id = whisper_full_lang_id(ctx);
    g_last_language = lang_id >= 0 ? whisper_lang_str(lang_id) : lang;

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        text += whisper_full_get_segment_text(ctx, i);
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out == nullptr) return nullptr;  // out of memory, exception pending
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()),
                            reinterpret_cast<const jbyte *>(text.data()));
    return out;
}
