// JNI bridge between LlamaNative.kt and llama.cpp (pinned at the submodule tag).
// One Session = one model + one context. Calls on a session are serialized by the Kotlin side.

#include <jni.h>
#include <android/api-level.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "ggml-backend.h"
#include "gguf.h"
#include "llama.h"

namespace {
// ponytail: all cores up to 8 for prefill; big.LITTLE-aware pinning would do better on some phones.
int prefill_threads(int generate_threads) {
    int cores = (int) std::thread::hardware_concurrency();
    return std::max(generate_threads, std::min(cores, 8));
}
}

#define TAG "Tune2AI-llama"
// Returned by generate when the NPU/GPU threw; Kotlin reloads the model on CPU.
#define ACCEL_FAILED "accelerator-failed"

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::atomic<bool> cancel{false};
    // Tokens currently in the KV cache (sequence 0, positions 0..n-1). A new prompt that starts with
    // the same tokens (chat history, agent steps) only processes what is new.
    std::vector<llama_token> cached;
    std::string backend = "CPU"; // what the model actually runs on, for the UI and the logs
    std::string note;            // why it is not on the requested hardware, empty when it is
    bool accel = false;          // running on an NPU/GPU
    bool embeddings = false;
    bool accel_failed = false;   // the accelerator threw during decode; the Kotlin side reloads on CPU
};

// ---- Hardware selection: NPU, then GPU, then CPU --------------------------------------------------
// Only devices that initialised on this phone are listed, so a missing or broken driver simply means
// "no accelerator" and the model runs on CPU.

struct Accel {
    ggml_backend_dev_t dev;
    std::string name; // shown to users, e.g. "GPU (Adreno 750, OpenCL)"
    bool npu;
    int rank;         // lower is tried first
};

std::vector<Accel> accelerators() {
    std::vector<Accel> out;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        auto type = ggml_backend_dev_type(dev);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
        const std::string api = ggml_backend_reg_name(ggml_backend_dev_backend_reg(dev));
        const std::string desc = ggml_backend_dev_description(dev);
        const bool npu = std::string(ggml_backend_dev_name(dev)).rfind("HTP", 0) == 0; // Qualcomm Hexagon
        // OpenCL has llama.cpp's Adreno-tuned kernels, so it goes before Vulkan for the same GPU.
        const int rank = npu ? 0 : api == "OpenCL" ? 1 : 2;
        out.push_back({dev, (npu ? "NPU (" : "GPU (") + desc + ", " + api + ")", npu, rank});
    }
    std::stable_sort(out.begin(), out.end(), [](const Accel & a, const Accel & b) { return a.rank < b.rank; });
    return out;
}


// Model formats the Adreno OpenCL kernels run correctly on the phones we have tested. Others (Q4_K_M seen on an
// Adreno 750) make the vendor driver end the process, which cannot be caught, so they never go to OpenCL.
// ponytail: allowlist from one device (Adreno 750); widen it as other formats are verified with GpuProbeTest.
bool opencl_safe_format(const std::string & path) {
    gguf_init_params params = {/*no_alloc =*/ true, /*ctx =*/ nullptr};
    gguf_context * g = gguf_init_from_file(path.c_str(), params);
    if (!g) return false;
    const int64_t key = gguf_find_key(g, "general.file_type");
    const int64_t ftype = key >= 0 ? (int64_t) gguf_get_val_u32(g, key) : -1;
    gguf_free(g);
    return ftype == LLAMA_FTYPE_ALL_F32 || ftype == LLAMA_FTYPE_MOSTLY_F16 ||
           ftype == LLAMA_FTYPE_MOSTLY_Q4_0 || ftype == LLAMA_FTYPE_MOSTLY_Q8_0;
}

bool is_opencl(const Accel & a) { return a.name.find(", OpenCL)") != std::string::npos; }

// A GPU driver can throw C++ exceptions (for example a shader that fails to compile). Left alone they abort the app.
int32_t safe_process(Session * s, llama_batch_ext * batch) {
    try {
        return llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
    } catch (const std::exception & e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "decode threw on %s: %s", s->backend.c_str(), e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "decode threw on %s", s->backend.c_str());
    }
    s->accel_failed = s->accel;
    return -100;
}

// Runs a short decode so driver problems show up at load time and not in the middle of a chat.
bool warm_up(Session * s) {
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    llama_token tok = llama_vocab_bos(vocab);
    if (tok < 0) tok = 0;
    llama_batch_ext * batch = llama_batch_ext_init(s->ctx);
    // 1 token takes the matrix-vector kernels, a long batch takes the matrix-matrix kernels.
    const int32_t sizes[2] = {1, (int32_t) std::min<uint32_t>(llama_n_batch(s->ctx), 256)};
    bool ok = true;
    for (int32_t n : sizes) {
        llama_batch_ext_clear(batch);
        for (int32_t i = 0; i < n; ++i) {
            const int32_t idx = llama_batch_ext_add_token(batch, 0, tok);
            const llama_pos pos = i;
            llama_batch_ext_set_pos(batch, idx, &pos);
        }
        if (s->embeddings) for (int32_t i = 0; i < n; ++i) llama_batch_ext_set_output_embd(batch, i, true);
        else llama_batch_ext_set_output_logits(batch, n - 1, true);
        if (safe_process(s, batch) != 0) { __android_log_print(ANDROID_LOG_ERROR, TAG, "warm-up failed at batch size %d", n); ok = false; break; }
        llama_memory_clear(llama_get_memory(s->ctx), true);
    }
    llama_batch_ext_free(batch);
    llama_memory_clear(llama_get_memory(s->ctx), true);
    return ok;
}

// Hardware choice from the app: 0 = auto (NPU, then GPU), 1 = CPU, 2 = GPU, 3 = NPU.
// A GPU or NPU is used only if it loads the model and passes a warm-up decode; otherwise the model runs on CPU
// and Session::note says why. `make_cparams` builds the context parameters.
template <typename MakeParams>
Session * open_session(const std::string & path, int mode, MakeParams make_cparams) {
    std::vector<const Accel *> candidates;
    const std::vector<Accel> accels = mode == 1 ? std::vector<Accel>{} : accelerators();
    std::string note;
    int opencl_skipped = 0;
    for (const Accel & a : accels) {
        if (!(mode == 0 || (mode == 2 && !a.npu) || (mode == 3 && a.npu))) continue;
        if (is_opencl(a) && !opencl_safe_format(path)) { ++opencl_skipped; continue; }
        candidates.push_back(&a);
    }
    if (opencl_skipped) note = "The GPU supports Q4_0 and Q8_0 models; this model's format runs on the CPU";
    if (mode == 2 && candidates.empty() && !opencl_skipped) note = "No GPU is available on this phone";
    if (mode == 3 && candidates.empty()) note = "No NPU is available on this phone";
    candidates.push_back(nullptr); // CPU, always last

    for (const Accel * pick : candidates) {
        const bool accel = pick != nullptr;
        auto mparams = llama_model_default_params();
        ggml_backend_dev_t devices[2] = {nullptr, nullptr};
        if (accel) {
            devices[0] = pick->dev;
            mparams.devices = devices;
            mparams.n_gpu_layers = -1; // everything the device can hold
            if (const char * ngl = getenv("TUNE2AI_GPU_LAYERS")) mparams.n_gpu_layers = atoi(ngl); // diagnostics
        } else {
            devices[0] = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
            if (devices[0]) mparams.devices = devices;
            mparams.n_gpu_layers = 0;
        }
        llama_model * model = nullptr;
        llama_context * ctx = nullptr;
        Session * s = nullptr;
        std::string failure;
        try {
            model = llama_model_load_from_file(path.c_str(), mparams);
            if (model) {
                const llama_context_params cparams = make_cparams(model);
                ctx = llama_init_from_model(model, cparams);
                if (!ctx) failure = "could not create a context";
                else {
                    s = new Session();
                    s->model = model;
                    s->ctx = ctx;
                    s->embeddings = cparams.embeddings;
                    s->accel = accel;
                    s->backend = accel ? pick->name : "CPU";
                    if (accel && !warm_up(s)) failure = "failed its warm-up run";
                }
            } else {
                failure = "could not load the model";
            }
        } catch (const std::exception & e) {
            failure = std::string("threw: ") + e.what();
        } catch (...) {
            failure = "threw an unknown error";
        }
        if (s && failure.empty()) {
            if (accel) note.clear(); // on the GPU/NPU: nothing to explain
            s->note = note;
            __android_log_print(ANDROID_LOG_INFO, TAG, "model running on %s%s%s", s->backend.c_str(), note.empty() ? "" : " - ", note.c_str());
            return s;
        }
        // Clean up this attempt and try the next candidate.
        if (s) { llama_free(s->ctx); llama_model_free(s->model); delete s; }
        else { if (ctx) llama_free(ctx); if (model) llama_model_free(model); }
        if (!accel) break;
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s %s, trying the next option", pick->name.c_str(), failure.c_str());
        if (note.empty()) note = pick->name + " failed to start";
    }
    return nullptr;
}


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
    // Warnings and errors, plus the one-line device summary ggml-vulkan prints (kept so a phone report shows the GPU).
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR || strstr(text, "ggml_vulkan") != nullptr) __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, "%s", text);
}

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *, void *) {
    llama_log_set(log_callback, nullptr);
    // Backends are separate libraries (GGML_BACKEND_DL). Loading by bare name finds them in the app's own
    // native library namespace. A backend whose vendor driver is missing just fails to load, here and only here.
    // TUNE2AI_SKIP_BACKENDS=vulkan,opencl (environment) leaves backends out, for diagnosing a phone.
    const char * skip_env = getenv("TUNE2AI_SKIP_BACKENDS");
    const std::string skip = skip_env ? skip_env : "";
    auto load = [&](const char * name, const char * lib) {
        if (skip.find(name) != std::string::npos) return;
        ggml_backend_load(lib);
    };
    if (!ggml_backend_load("libggml-cpu.so")) __android_log_print(ANDROID_LOG_ERROR, TAG, "CPU backend failed to load");
    load("opencl", "libggml-opencl.so");
    // The Vulkan backend needs Vulkan 1.1 entry points, which the loader has from Android 9 (API 28).
    if (android_get_device_api_level() >= 28) load("vulkan", "libggml-vulkan.so");
#ifdef TUNE2AI_HAS_HEXAGON
    load("hexagon", "libggml-hexagon.so");
#endif
    llama_backend_init();
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_load(JNIEnv * env, jclass, jstring path, jint n_ctx, jint n_threads, jint hardware) {
    Session * s = open_session(str(env, path), hardware, [&](llama_model *) {
        auto cparams = llama_context_default_params();
        cparams.n_ctx = (uint32_t) n_ctx;
        cparams.n_batch = std::min<uint32_t>(512, (uint32_t) n_ctx);
        cparams.n_threads = n_threads;
        // Reading the prompt (prefill) is compute-bound and scales with cores; generating is
        // memory-bound and does best on the big cores alone. So prefill gets more threads.
        cparams.n_threads_batch = prefill_threads(n_threads);
        return cparams;
    });
    if (!s) return 0;
    // Lets cancellation interrupt long prompt processing, not only the token loop.
    llama_set_abort_callback(s->ctx, [](void * data) { return session(reinterpret_cast<jlong>(data))->cancel.load(); }, s);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jstring JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_backendName(JNIEnv * env, jclass, jlong handle) {
    return env->NewStringUTF(session(handle)->backend.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_backendNote(JNIEnv * env, jclass, jlong handle) {
    return env->NewStringUTF(session(handle)->note.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_acceleratorFailed(JNIEnv *, jclass, jlong handle) {
    return session(handle)->accel_failed;
}

/** Accelerators this build and phone offer, best first, e.g. ["NPU (HTP0)", "GPU (Adreno 750)"]. Empty = CPU only. */
JNIEXPORT jobjectArray JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_accelerators(JNIEnv * env, jclass) {
    auto list = accelerators();
    jobjectArray arr = env->NewObjectArray((jsize) list.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < list.size(); ++i) env->SetObjectArrayElement(arr, (jsize) i, env->NewStringUTF(list[i].name.c_str()));
    return arr;
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

    // Reuse the cached prefix; always re-process at least the last prompt token for fresh logits.
    llama_memory_t mem = llama_get_memory(s->ctx);
    int32_t n_keep = 0;
    while (n_keep < (int32_t) s->cached.size() && n_keep < n_prompt && s->cached[n_keep] == tokens[n_keep]) ++n_keep;
    n_keep = std::min(n_keep, n_prompt - 1);
    if (!llama_memory_seq_rm(mem, 0, n_keep, -1)) { // some model types cannot trim; start over
        llama_memory_clear(mem, true);
        n_keep = 0;
    }
    s->cached.resize(n_keep);
    bool dirty = false; // KV contents no longer match `cached` (abort mid-batch)

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

    const int64_t t_start = ggml_time_us();
    // Prompt (new part only), in chunks of n_batch; logits only for the very last prompt token.
    for (int32_t i = n_keep; i < n_prompt && !error; i += n_batch) {
        const int32_t n = std::min(n_batch, n_prompt - i);
        batch_set_tokens(batch, tokens.data() + i, n, i, i + n == n_prompt);
        const int32_t rc = safe_process(s, batch);
        if (rc == 2) { dirty = true; break; }
        if (rc != 0) { error = s->accel_failed ? ACCEL_FAILED : "llama.cpp failed to process the prompt"; break; }
        s->cached.insert(s->cached.end(), tokens.begin() + i, tokens.begin() + i + n);
        if (s->cancel.load()) break;
    }

    std::string pending;
    llama_pos pos = n_prompt;
    int64_t t_first = 0;
    int32_t produced = 0;
    const char * stop = "budget";
    for (; !error && !s->cancel.load() && produced < budget; ++produced) {
        const llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (produced == 0) t_first = ggml_time_us();
        if (llama_vocab_is_eog(vocab, tok)) { stop = "end-of-turn"; break; }

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
        const int32_t rc = safe_process(s, batch);
        if (rc == 2) { dirty = true; break; }
        if (rc != 0) { error = s->accel_failed ? ACCEL_FAILED : "llama.cpp failed while generating"; break; }
        s->cached.push_back(tok);
    }
    if (dirty || error) { // unknown KV state: forget the cache rather than reuse a wrong one
        llama_memory_clear(mem, true);
        s->cached.clear();
    }

    if (s->cancel.load()) stop = "cancelled";
    if (error) stop = "error";
    const int64_t t_end = ggml_time_us();
    __android_log_print(ANDROID_LOG_INFO, TAG, "prompt %d tokens (%d cached, %.0f ms), generated %d tokens (%.1f tok/s), stop=%s",
                        n_prompt, n_keep, (t_first ? t_first - t_start : 0) / 1000.0, produced,
                        produced > 0 && t_end > t_first ? produced * 1e6 / (double) (t_end - t_first) : 0.0, stop);
    llama_batch_ext_free(batch);
    llama_sampler_free(smpl);
    env->DeleteLocalRef(sink_class);
    if (env->ExceptionCheck()) return nullptr; // let the Kotlin exception propagate
    return error ? env->NewStringUTF(error) : nullptr;
}

// Embedding models (T2AI-023): a context that outputs pooled embeddings instead of logits.
// n_batch = n_ubatch = n_ctx, because non-causal (BERT-style) models must see a text in one ubatch.
JNIEXPORT jlong JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_loadEmbedder(JNIEnv * env, jclass, jstring path, jint n_ctx, jint n_threads, jint hardware) {
    Session * s = open_session(str(env, path), hardware, [&](llama_model *) {
        auto cparams = llama_context_default_params();
        cparams.n_ctx = (uint32_t) n_ctx;
        cparams.n_batch = (uint32_t) n_ctx;
        cparams.n_ubatch = (uint32_t) n_ctx;
        cparams.n_threads = n_threads;
        cparams.n_threads_batch = n_threads;
        cparams.embeddings = true; // pooling type comes from the model's metadata
        return cparams;
    });
    if (!s) return 0;
    llama_set_abort_callback(s->ctx, [](void * data) { return session(reinterpret_cast<jlong>(data))->cancel.load(); }, s);
    return reinterpret_cast<jlong>(s);
}

// One L2-normalised embedding for text, truncated to the context length. Null on failure.
JNIEXPORT jfloatArray JNICALL
Java_com_tune2ai_sdk_llama_LlamaNative_embed(JNIEnv * env, jclass, jlong handle, jstring text_j) {
    Session * s = session(handle);
    s->cancel.store(false);
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const std::string text = str(env, text_j);

    const int32_t n_needed = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, false);
    std::vector<llama_token> tokens(std::max<int32_t>(n_needed, 0));
    if (n_needed > 0 && llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n_needed, true, false) < 0) {
        return nullptr;
    }
    const int32_t limit = (int32_t) std::min(llama_n_ctx(s->ctx), llama_n_batch(s->ctx));
    if ((int32_t) tokens.size() > limit) tokens.resize(limit); // ponytail: truncates; chunks are sized to fit
    if (tokens.empty()) return nullptr;

    llama_memory_clear(llama_get_memory(s->ctx), true); // each text is independent
    llama_batch_ext * batch = llama_batch_ext_init(s->ctx);
    for (int32_t i = 0; i < (int32_t) tokens.size(); ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = i;
        llama_batch_ext_set_pos(batch, idx, &pos);
        llama_batch_ext_set_output_embd(batch, idx, true);
    }
    const int32_t rc = safe_process(s, batch);
    llama_batch_ext_free(batch);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "embedding failed (rc=%d, %d tokens)", rc, (int) tokens.size());
        return nullptr;
    }

    const int32_t n_embd = llama_model_n_embd_out(s->model);
    std::vector<float> out(n_embd, 0.0f);
    if (llama_pooling_type(s->ctx) == LLAMA_POOLING_TYPE_NONE) {
        for (int32_t i = 0; i < (int32_t) tokens.size(); ++i) { // mean-pool token embeddings ourselves
            const float * e = llama_get_embeddings_ith(s->ctx, i);
            if (!e) return nullptr;
            for (int32_t j = 0; j < n_embd; ++j) out[j] += e[j] / (float) tokens.size();
        }
    } else {
        const float * e = llama_get_embeddings_seq(s->ctx, 0);
        if (!e) return nullptr;
        std::copy(e, e + n_embd, out.begin());
    }
    double norm = 0.0;
    for (float v : out) norm += (double) v * v;
    norm = std::sqrt(norm);
    if (norm > 0.0) for (float & v : out) v = (float) (v / norm);

    jfloatArray result = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(result, 0, n_embd, out.data());
    return result;
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
