// JNI bridge between LlamaNative.kt and llama.cpp (pinned at the submodule tag).
// One Session = one model + one context. Calls on a session are serialized by the Kotlin side.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "Tune2AI-llama"

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::atomic<bool> cancel{false};
};

Session * session(jlong handle) { return reinterpret_cast<Session *>(handle); }

std::string str(JNIEnv * env, jstring s) {
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Java strings cross JNI as modified UTF-8, which mangles 4-byte characters (emoji), so text
// leaves native code as raw UTF-8 bytes and Kotlin decodes it.
jstring utf8(JNIEnv * env, const std::string & s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring result = (jstring) env->NewObject(string_class, ctor, bytes, env->NewStringUTF("UTF-8"));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(string_class);
    return result;
}

// Length of the longest prefix of s made only of complete UTF-8 sequences.
size_t complete_utf8_prefix(const std::string & s) {
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        auto c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) { // lead byte or ASCII
            size_t need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return (back + 1 >= need) ? s.size() : i - 1;
        }
        --i;
        ++back;
    }
    return s.size();
}

void batch_set_tokens(llama_batch_ext * batch, const llama_token * tokens, int32_t n, llama_pos pos0, bool logits_last) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = pos0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    if (logits_last) llama_batch_ext_set_output_logits(batch, n - 1, true);
}

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, "%s", text);
}

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *, void *) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_load(JNIEnv * env, jclass, jstring path, jint n_ctx, jint n_threads) {
    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(str(env, path).c_str(), mparams);
    if (!model) return 0;

    auto cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) n_ctx;
    cparams.n_batch = std::min<uint32_t>(512, (uint32_t) n_ctx);
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    auto * s = new Session();
    s->model = model;
    s->ctx = ctx;
    // Lets cancellation interrupt long prompt processing, not only the token loop.
    llama_set_abort_callback(ctx, [](void * data) { return session(reinterpret_cast<jlong>(data))->cancel.load(); }, s);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jstring JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_applyTemplate(JNIEnv * env, jclass, jlong handle, jobjectArray roles, jobjectArray contents) {
    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> role_strs, content_strs;
    for (jsize i = 0; i < n; ++i) {
        role_strs.push_back(str(env, (jstring) env->GetObjectArrayElement(roles, i)));
        content_strs.push_back(str(env, (jstring) env->GetObjectArrayElement(contents, i)));
    }
    std::vector<llama_chat_message> chat;
    for (jsize i = 0; i < n; ++i) chat.push_back({role_strs[i].c_str(), content_strs[i].c_str()});

    const char * tmpl = llama_model_chat_template(session(handle)->model, nullptr);
    if (!tmpl) tmpl = "chatml";
    size_t total = 0;
    for (auto & c : content_strs) total += c.size();
    std::vector<char> buf(2 * total + 1024);
    int32_t len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int32_t) buf.size());
    if (len > (int32_t) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) return nullptr; // template not supported by llama_chat_apply_template
    return utf8(env, std::string(buf.data(), len));
}

// Streams generated text to sink.onPiece(byte[]) -> boolean (false = stop).
// Returns null on success or cancellation, or an error message.
JNIEXPORT jstring JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_generate(JNIEnv * env, jclass, jlong handle, jstring prompt_j,
                                                jint max_tokens, jfloat temperature, jint seed, jobject sink) {
    Session * s = session(handle);
    s->cancel.store(false);
    llama_memory_clear(llama_get_memory(s->ctx), true);

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const std::string prompt = str(env, prompt_j);
    const int32_t n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n_prompt, true, true) < 0) {
        return env->NewStringUTF("Failed to tokenize the prompt");
    }
    const int32_t n_ctx = (int32_t) llama_n_ctx(s->ctx);
    if (n_prompt >= n_ctx) {
        return env->NewStringUTF(("Prompt is " + std::to_string(n_prompt) + " tokens; the context holds " + std::to_string(n_ctx)).c_str());
    }
    const int32_t budget = std::min<int32_t>(max_tokens, n_ctx - n_prompt);

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_piece = env->GetMethodID(sink_class, "onPiece", "([B)Z");

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));
    }
    llama_batch_ext * batch = llama_batch_ext_init(s->ctx);
    const int32_t n_batch = (int32_t) llama_n_batch(s->ctx);
    const char * error = nullptr;

    // Prompt, in chunks of n_batch; logits only for the very last prompt token.
    for (int32_t i = 0; i < n_prompt && !error; i += n_batch) {
        const int32_t n = std::min(n_batch, n_prompt - i);
        batch_set_tokens(batch, tokens.data() + i, n, i, i + n == n_prompt);
        const int32_t rc = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (rc == 2 || s->cancel.load()) break;
        if (rc != 0) error = "llama.cpp failed to process the prompt";
    }

    std::string pending;
    llama_pos pos = n_prompt;
    for (int32_t produced = 0; !error && !s->cancel.load() && produced < budget; ++produced) {
        const llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char buf[256];
        const int32_t n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
        if (n < 0) { error = "Failed to decode a token"; break; }
        pending.append(buf, n);
        const size_t ready = complete_utf8_prefix(pending);
        if (ready > 0) {
            jbyteArray bytes = env->NewByteArray((jsize) ready);
            env->SetByteArrayRegion(bytes, 0, (jsize) ready, reinterpret_cast<const jbyte *>(pending.data()));
            const jboolean keep_going = env->CallBooleanMethod(sink, on_piece, bytes);
            env->DeleteLocalRef(bytes);
            pending.erase(0, ready);
            if (env->ExceptionCheck() || !keep_going) break;
        }

        batch_set_tokens(batch, &tok, 1, pos++, true);
        const int32_t rc = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (rc == 2) break;
        if (rc != 0) error = "llama.cpp failed while generating";
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(smpl);
    env->DeleteLocalRef(sink_class);
    if (env->ExceptionCheck()) return nullptr; // let the Kotlin exception propagate
    return error ? env->NewStringUTF(error) : nullptr;
}

JNIEXPORT void JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_cancel(JNIEnv *, jclass, jlong handle) {
    session(handle)->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    Session * s = session(handle);
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

} // extern "C"
