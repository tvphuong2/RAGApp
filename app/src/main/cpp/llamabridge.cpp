// app/src/main/cpp/llamabridge.cpp
#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <android/log.h>
#include <atomic>
#include <cassert>

#include "llama.h"   // third_party/llama/llama.h

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO , "llamabridge", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "llamabridge", __VA_ARGS__)

// --------- Globals ---------
static llama_model*         g_model   = nullptr;
static llama_context*       g_ctx     = nullptr;
static const llama_vocab*   g_vocab   = nullptr;
static llama_sampler*       g_smpl    = nullptr;
static llama_context_params g_cparams;
static bool                 g_inited  = false;
static std::atomic<bool>    g_cancel_requested(false);

// --------- Helpers ---------

// Build a chat-formatted prompt using the model's chat template (GGUF metadata)
static std::string apply_chat_template(llama_model* model, const std::string& user_msg, const char* sys_msg_opt) {
    std::vector<llama_chat_message> msgs;
    if (sys_msg_opt && sys_msg_opt[0]) {
        llama_chat_message msys{ "system", sys_msg_opt };
        msgs.push_back(msys);
    }
    llama_chat_message muser{ "user", user_msg.c_str() };
    msgs.push_back(muser);

    // Lấy template mặc định từ model; có thể null nếu GGUF không có
    const char* tmpl = llama_model_chat_template(model, /*name*/ nullptr);

    // buffer tăng dần
    size_t cap = std::max<size_t>(user_msg.size() * 2 + 256, 64 * 1024);
    for (int tries = 0; tries < 6; ++tries) {
        std::vector<char> buf(cap);
        int n = llama_chat_apply_template(
                /*tmpl*/ tmpl,                 // <- đúng chữ ký 6 tham số
                /*chat*/ msgs.data(),
                /*n_msg*/ (size_t)msgs.size(),
                /*add_ass*/ true,
                /*buf*/ buf.data(),
                /*length*/ (int32_t)buf.size()
        );
        if (n >= 0 && (size_t)n <= buf.size()) {
            return std::string(buf.data(), (size_t)n);
        }
        cap *= 2;
    }

    // Fallback khi không có template: cực tối giản
    std::string fb;
    if (sys_msg_opt && sys_msg_opt[0]) {
        fb += "System: ";
        fb += sys_msg_opt;
        fb += "\n";
    }
    fb += "User: ";
    fb += user_msg;
    fb += "\nAssistant:";
    return fb;
}

// Detokenize: không render special tokens
static std::string detok(const std::vector<llama_token>& toks) {
    if (!g_vocab || toks.empty()) return {};
    std::string out; out.reserve(toks.size() * 4);
    char buf[256];
    for (llama_token t : toks) {
        const int n = llama_token_to_piece(
                g_vocab, t, buf, (int)sizeof(buf),
                /*lstrip*/ 0,
                /*special*/ false
        );
        if (n > 0) out.append(buf, n);
    }
    return out;
}

static std::string token_to_piece(llama_token tok) {
    if (!g_vocab) return {};
    std::vector<char> buf(32);
    for (int attempt = 0; attempt < 5; ++attempt) {
        const int n = llama_token_to_piece(
                g_vocab,
                tok,
                buf.data(),
                (int)buf.size(),
                /*lstrip*/ 0,
                /*special*/ false
        );
        if (n >= 0 && n < (int)buf.size()) {
            return std::string(buf.data(), (size_t)n);
        }
        if (n < 0) break;
        buf.resize((size_t)n + 1);
    }
    return {};
}

// Sampler chain
static llama_sampler* make_sampler(float topP, float temp) {
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;

    llama_sampler* smpl = llama_sampler_chain_init(sp);

    // (tuỳ chọn) giảm lặp: bật nếu cần, theo chữ ký hiện tại (last_n, repeat, freq, present)
    // llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.10f, 0.0f, 0.0f));

    llama_sampler_chain_add(smpl, llama_sampler_init_top_p((topP > 0.f && topP <= 1.f) ? topP : 0.95f, /*min_keep*/ 1));

    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());     // tất định
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(/*seed*/ 0));
    }
    return smpl;
}

// Clamp: giữ prefix (system/header) + phần đuôi câu hỏi hiện tại
static void clamp_with_keep(std::vector<llama_token>& ptok, int n_ctx_total, int reserve, int n_keep_prefix) {
    const int n_avail = std::max(0, n_ctx_total - reserve);
    if ((int)ptok.size() <= n_avail) return;

    n_keep_prefix = std::max(0, std::min(n_keep_prefix, (int)ptok.size()));
    if (n_keep_prefix >= n_avail) { ptok.resize(n_avail); return; }

    const int n_tail = n_avail - n_keep_prefix;
    std::vector<llama_token> kept;
    kept.reserve(n_avail);
    kept.insert(kept.end(), ptok.begin(), ptok.begin() + n_keep_prefix);
    kept.insert(kept.end(), ptok.end() - n_tail, ptok.end());
    ptok.swap(kept);
}

// --------- JNI: init ---------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ragapp_LlamaBridge_init(
        JNIEnv* env, jclass /*clazz*/,
        jstring jModelPath, jint nCtx, jint nThreads) {

    if (g_smpl)  { llama_sampler_free(g_smpl);  g_smpl  = nullptr; }
    if (g_ctx)   { llama_free(g_ctx);           g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);   g_model = nullptr; }
    g_vocab  = nullptr;

    const char* cpath = env->GetStringUTFChars(jModelPath, nullptr);

    llama_backend_init();
    g_inited = true;

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap  = true;
    mparams.use_mlock = false;

    g_cparams = llama_context_default_params();
    g_cparams.n_ctx             = (uint32_t)nCtx;
    g_cparams.n_threads         = (int32_t)nThreads;
    g_cparams.n_threads_batch   = (int32_t)nThreads;

    g_cancel_requested.store(false);

    LOGI("Loading model: %s", cpath);
    g_model = llama_model_load_from_file(cpath, mparams);
    env->ReleaseStringUTFChars(jModelPath, cpath);

    if (!g_model) {
        LOGE("Failed to load model");
        llama_backend_free();
        g_inited = false;
        return JNI_FALSE;
    }

    g_ctx = llama_init_from_model(g_model, g_cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model); g_model = nullptr;
        llama_backend_free();
        g_inited = false;
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);

    if (g_smpl) llama_sampler_free(g_smpl);
    g_smpl = make_sampler(/*topP*/0.95f, /*temp*/0.0f);

    LOGI("Model & context ready (n_ctx=%u, n_threads=%d)",
         (unsigned)llama_n_ctx(g_ctx), (int)nThreads);
    return JNI_TRUE;
}

// --------- JNI: infer ---------
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ragapp_LlamaBridge_infer(
        JNIEnv* env, jclass /*clazz*/,
        jstring jPrompt, jint maxTokens, jfloat temp, jfloat topP) {
    if (!g_model || !g_vocab || !g_inited) {
        return env->NewStringUTF("(init() not called)");
    }

    g_cancel_requested.store(false);

    // Reset context per call (đơn giản & an toàn)
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    g_ctx = llama_init_from_model(g_model, g_cparams);
    if (!g_ctx) {
        LOGE("Failed to recreate context before infer");
        return env->NewStringUTF("(failed to recreate context)");
    }

    // 0) Build chat-formatted prompt
    const char* cprompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string user_prompt(cprompt ? cprompt : "");
    env->ReleaseStringUTFChars(jPrompt, cprompt);

    const char* kSystem = "You are a helpful AI assistant.";
    const std::string prompt_templ = apply_chat_template(g_model, user_prompt, kSystem);

    // 1) Tokenize (add_special=false vì template đã có special; parse_special=true để nhận diện token đặc biệt)
    std::vector<llama_token> ptok(prompt_templ.size() + 8);
    int n_inp = llama_tokenize(
            g_vocab,
            prompt_templ.c_str(), (int)prompt_templ.size(),
            ptok.data(), (int)ptok.size(),
            /*add_special*/ false,
            /*parse_special*/ true
    );
    if (n_inp < 0) n_inp = 0;
    ptok.resize(n_inp);
    if (ptok.empty()) return env->NewStringUTF("");

    // 1b) Clamp to ctx with keep-prefix
    const int n_ctx_total = (int)llama_n_ctx(g_ctx);
    const int reserve     = std::max<int>(std::max(32, (int)maxTokens), 64);
    const int n_keep_prefix = std::min<int>(256, (int)ptok.size()); // giữ phần system/header
    clamp_with_keep(ptok, n_ctx_total, reserve, n_keep_prefix);

    // 2) Prefill
    llama_batch batch = llama_batch_init((int32_t)ptok.size(), /*embd*/0, /*n_seq_max*/1);
    for (int i = 0; i < (int)ptok.size(); ++i) {
        batch.token[i]     = ptok[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = (i == (int)ptok.size() - 1);
    }
    batch.n_tokens = (int32_t)ptok.size();

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("decode prefill failed (n_inp=%d, n_ctx=%d, reserve=%d)",
             (int)ptok.size(), n_ctx_total, reserve);
        llama_batch_free(batch);
        return env->NewStringUTF("(decode prefill failed)");
    }

    // 3) (Re)build sampler theo tham số runtime
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    g_smpl = make_sampler(topP, temp);

    // 4) Generate
    std::vector<llama_token> out; out.reserve((size_t)std::max(0, (int)maxTokens));
    int n_past = (int)ptok.size();
    int i_last = batch.n_tokens - 1;

    for (int step = 0; step < (int)maxTokens; ++step) {
        const llama_token tok = llama_sampler_sample(g_smpl, g_ctx, i_last);

        if (llama_vocab_is_eog(g_vocab, tok)) break; // EOS/EOT/…

        out.push_back(tok);
        llama_sampler_accept(g_smpl, tok); // cập nhật penalties/grammar nếu có

        llama_batch stepb = llama_batch_init(1, 0, 1);
        stepb.token[0]     = tok;
        stepb.pos[0]       = n_past;
        stepb.n_seq_id[0]  = 1;
        stepb.seq_id[0][0] = 0;
        stepb.logits[0]    = true;
        stepb.n_tokens     = 1;

        if (llama_decode(g_ctx, stepb) != 0) {
            LOGE("decode step failed at %d (n_past=%d)", step, n_past);
            llama_batch_free(stepb);
            break;
        }
        llama_batch_free(stepb);

        n_past += 1;
        i_last  = 0;
    }

    llama_batch_free(batch);

    const std::string text = detok(out);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ragapp_LlamaBridge_infer_1streaming(
        JNIEnv* env, jclass /*clazz*/,
        jstring jPrompt, jint maxTokens, jfloat temp, jfloat topP, jobject jCallback) {

    if (!g_model || !g_vocab || !g_inited || jCallback == nullptr) {
        return JNI_FALSE;
    }

    g_cancel_requested.store(false);

    jobject callback = env->NewGlobalRef(jCallback);
    if (!callback) {
        return JNI_FALSE;
    }

    jclass cbClass = env->GetObjectClass(callback);
    if (!cbClass) {
        env->DeleteGlobalRef(callback);
        return JNI_FALSE;
    }

    jmethodID onToken     = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleted = env->GetMethodID(cbClass, "onCompleted", "()V");
    jmethodID onError     = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");

    auto sendError = [&](const char* msg) {
        if (!onError) return;
        jstring jMsg = env->NewStringUTF(msg ? msg : "");
        env->CallVoidMethod(callback, onError, jMsg);
        env->DeleteLocalRef(jMsg);
    };

    auto cleanup = [&]() {
        if (cbClass) {
            env->DeleteLocalRef(cbClass);
            cbClass = nullptr;
        }
        env->DeleteGlobalRef(callback);
    };

    if (!onToken || !onCompleted || !onError) {
        sendError("Callback methods missing");
        cleanup();
        return JNI_FALSE;
    }

    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    g_ctx = llama_init_from_model(g_model, g_cparams);
    if (!g_ctx) {
        sendError("Không thể tạo context");
        cleanup();
        return JNI_FALSE;
    }

    const char* cprompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string user_prompt(cprompt ? cprompt : "");
    env->ReleaseStringUTFChars(jPrompt, cprompt);

    const char* kSystem = "You are a helpful AI assistant.";
    const std::string prompt_templ = apply_chat_template(g_model, user_prompt, kSystem);

    std::vector<llama_token> ptok(prompt_templ.size() + 8);
    int n_inp = llama_tokenize(
            g_vocab,
            prompt_templ.c_str(), (int)prompt_templ.size(),
            ptok.data(), (int)ptok.size(),
            /*add_special*/ false,
            /*parse_special*/ true
    );
    if (n_inp < 0) n_inp = 0;
    ptok.resize(n_inp);
    if (ptok.empty()) {
        sendError("Prompt trống");
        cleanup();
        return JNI_FALSE;
    }

    const int n_ctx_total = (int)llama_n_ctx(g_ctx);
    const int reserve     = std::max<int>(std::max(32, (int)maxTokens), 64);
    const int n_keep_prefix = std::min<int>(256, (int)ptok.size());
    clamp_with_keep(ptok, n_ctx_total, reserve, n_keep_prefix);

    llama_batch batch = llama_batch_init((int32_t)ptok.size(), /*embd*/0, /*n_seq_max*/1);
    for (int i = 0; i < (int)ptok.size(); ++i) {
        batch.token[i]     = ptok[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = (i == (int)ptok.size() - 1);
    }
    batch.n_tokens = (int32_t)ptok.size();

    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        sendError("decode prefill thất bại");
        cleanup();
        return JNI_FALSE;
    }

    if (g_smpl) {
        llama_sampler_free(g_smpl);
        g_smpl = nullptr;
    }
    g_smpl = make_sampler(topP, temp);

    int n_past = (int)ptok.size();
    int i_last = batch.n_tokens - 1;
    bool had_error = false;

    for (int step = 0; step < (int)maxTokens && !g_cancel_requested.load(); ++step) {
        const llama_token tok = llama_sampler_sample(g_smpl, g_ctx, i_last);

        if (llama_vocab_is_eog(g_vocab, tok)) break;

        llama_sampler_accept(g_smpl, tok);

        const std::string piece = token_to_piece(tok);
        jstring jPiece = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(callback, onToken, jPiece);
        env->DeleteLocalRef(jPiece);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            had_error = true;
            break;
        }

        llama_batch stepb = llama_batch_init(1, 0, 1);
        stepb.token[0]     = tok;
        stepb.pos[0]       = n_past;
        stepb.n_seq_id[0]  = 1;
        stepb.seq_id[0][0] = 0;
        stepb.logits[0]    = true;
        stepb.n_tokens     = 1;

        if (llama_decode(g_ctx, stepb) != 0) {
            llama_batch_free(stepb);
            had_error = true;
            break;
        }
        llama_batch_free(stepb);

        n_past += 1;
        i_last  = 0;
    }

    llama_batch_free(batch);

    if (had_error) {
        sendError("Suy luận bị gián đoạn");
        cleanup();
        return JNI_FALSE;
    }

    if (!g_cancel_requested.load()) {
        env->CallVoidMethod(callback, onCompleted);
    }

    cleanup();
    return JNI_TRUE;
}

// --------- JNI: release ---------
extern "C" JNIEXPORT void JNICALL
Java_com_example_ragapp_LlamaBridge_release(
        JNIEnv*, jclass /*clazz*/) {
    if (g_smpl)  { llama_sampler_free(g_smpl);  g_smpl  = nullptr; }
    if (g_ctx)   { llama_free(g_ctx);           g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);   g_model = nullptr; }
    g_vocab = nullptr;

    if (g_inited) {
        llama_backend_free();
        g_inited = false;
    }
    g_cancel_requested.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ragapp_LlamaBridge_cancel(
        JNIEnv*, jclass /*clazz*/) {
    g_cancel_requested.store(true);
    if (g_ctx) {
        llama_reset_logits(g_ctx);
    }
}
