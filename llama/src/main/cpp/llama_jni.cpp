#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>
#include <numeric>
#include <cstring>

#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlamaHandle {
    llama_model   * model;
    llama_context * ctx;
    const llama_vocab * vocab;
};

static LlamaHandle * handleFromJLong(jlong h) {
    return reinterpret_cast<LlamaHandle *>(static_cast<uintptr_t>(h));
}

extern "C" {

// ── loadModel(path: String): Long ──────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_helium314_keyboard_llama_LlamaInference_loadModel(
        JNIEnv * env, jobject /* thiz */, jstring jpath) {

    llama_log_set([](enum ggml_log_level level, const char * text, void *) {
        if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
        else LOGI("%s", text);
    }, nullptr);

    const char * path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("Loading model from %s", path);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU-only on Android

    llama_model * model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0L;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx   = 512;
    cp.n_batch = 512;

    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0L;
    }

    auto * handle    = new LlamaHandle{model, ctx, llama_model_get_vocab(model)};
    LOGI("Model loaded. Vocab size: %d", llama_vocab_n_tokens(handle->vocab));
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(handle));
}

// ── predict(handle: Long, prompt: String, topK: Int): Array<String> ────────
JNIEXPORT jobjectArray JNICALL
Java_helium314_keyboard_llama_LlamaInference_predict(
        JNIEnv * env, jobject /* thiz */,
        jlong jhandle, jstring jprompt, jint topK) {

    auto * h = handleFromJLong(jhandle);
    if (!h) return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);

    const char * prompt_c = env->GetStringUTFChars(jprompt, nullptr);

    // Tokenize
    std::vector<llama_token> tokens(512);
    int n_tok = llama_tokenize(
        h->vocab, prompt_c, static_cast<int32_t>(strlen(prompt_c)),
        tokens.data(), static_cast<int32_t>(tokens.size()),
        /* add_special= */ true, /* parse_special= */ true);
    env->ReleaseStringUTFChars(jprompt, prompt_c);

    if (n_tok <= 0) {
        LOGE("Tokenization failed: %d", n_tok);
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    tokens.resize(n_tok);

    // Clear KV memory and decode the full prompt from scratch each call
    llama_memory_clear(llama_get_memory(h->ctx), false);
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(n_tok));
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("llama_decode failed");
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    // Read logits at last token position
    const int n_vocab  = llama_vocab_n_tokens(h->vocab);
    const float * logits = llama_get_logits_ith(h->ctx, n_tok - 1);
    if (!logits) {
        LOGE("No logits returned");
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    // Argsort top-200 by logit value (descending)
    const int scan_k = std::min(200, n_vocab);
    std::vector<int> indices(n_vocab);
    std::iota(indices.begin(), indices.end(), 0);
    std::partial_sort(indices.begin(), indices.begin() + scan_k, indices.end(),
        [&logits](int a, int b) { return logits[a] > logits[b]; });

    // Decode each candidate, keep purely alphabetic single words
    char piece_buf[64];
    std::vector<std::string> results;
    results.reserve(topK);

    for (int i = 0; i < scan_k && static_cast<int>(results.size()) < topK; ++i) {
        llama_token tok = static_cast<llama_token>(indices[i]);
        int len = llama_token_to_piece(h->vocab, tok, piece_buf, sizeof(piece_buf) - 1,
                                       /* lstrip= */ 0, /* special= */ false);
        if (len <= 0) continue;
        piece_buf[len] = '\0';

        // Strip leading space (sentencepiece convention: "▁word" → " word")
        const char * word = piece_buf;
        while (*word == ' ') ++word;

        if (*word == '\0') continue;

        // Keep only purely alphabetic tokens (a-z, A-Z)
        bool alpha = true;
        for (const char * p = word; *p; ++p) {
            if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z'))) {
                alpha = false;
                break;
            }
        }
        if (!alpha) continue;

        // Lowercase and deduplicate
        std::string w(word);
        for (char & c : w) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));

        bool dup = false;
        for (const auto & existing : results) {
            if (existing == w) { dup = true; break; }
        }
        if (!dup) results.push_back(std::move(w));
    }

    // Build Java String array
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(results.size()), string_class, nullptr);
    for (int i = 0; i < static_cast<int>(results.size()); ++i) {
        env->SetObjectArrayElement(arr, i, env->NewStringUTF(results[i].c_str()));
    }
    return arr;
}

// ── freeModel(handle: Long) ─────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_helium314_keyboard_llama_LlamaInference_freeModel(
        JNIEnv * /* env */, jobject /* thiz */, jlong jhandle) {

    auto * h = handleFromJLong(jhandle);
    if (!h) return;
    llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
    LOGI("Model freed");
}

} // extern "C"
