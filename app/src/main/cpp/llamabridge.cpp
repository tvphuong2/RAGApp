// app/src/main/cpp/llamabridge.cpp
#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.h"  // third_party/llama/llama.h

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO , "llamabridge", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "llamabridge", __VA_ARGS__)

// --------- Globals ---------
static llama_model*       g_model = nullptr;
static llama_context*     g_ctx   = nullptr;
static const llama_vocab* g_vocab = nullptr;
static llama_sampler*     g_smpl  = nullptr;

// --------- Helpers ---------
// Detokenize theo chữ ký 6 tham số của llama_token_to_piece(vocab, token, buf, len, lstrip, special)
static std::string detok(const std::vector<llama_token>& toks) {
    if (!g_vocab || toks.empty()) return {};
    std::string out; out.reserve(toks.size() * 4);
    char buf[256];
    for (llama_token t : toks) {
        const int n = llama_token_to_piece(
                g_vocab,           // vocab theo header hiện tại
                t,
                buf, (int)sizeof(buf),
                /*lstrip*/ 0,
                /*special*/ true
        );
        if (n > 0) out.append(buf, n);
    }
    return out;
}

// Sampler chain: top-p -> temp -> dist
static llama_sampler* make_sampler(float topP, float temp) {
    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler* smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, /*min_keep*/1));
    if (temp > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    // sampler cuối để chọn token theo phân phối
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(/*seed*/0));
    return smpl;
}

// --------- JNI: init ---------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ragapp_LlamaBridge_init(
        JNIEnv* env, jclass /*clazz*/,
        jstring jModelPath, jint nCtx, jint nThreads) {

    // Dọn nếu đã init
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    if (g_ctx)  { llama_free(g_ctx);          g_ctx  = nullptr; }
    if (g_model){ llama_model_free(g_model);  g_model= nullptr; }
    g_vocab = nullptr;

    const char* cpath = env->GetStringUTFChars(jModelPath, nullptr);

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap  = true;
    mparams.use_mlock = false;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = (int)nCtx;
    cparams.n_threads = (int)nThreads;

    LOGI("Loading model: %s", cpath);
    g_model = llama_model_load_from_file(cpath, mparams);
    env->ReleaseStringUTFChars(jModelPath, cpath);

    if (!g_model) {
        LOGE("Failed to load model");
        llama_backend_free();
        return JNI_FALSE;
    }

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model); g_model = nullptr;
        llama_backend_free();
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);
    g_smpl  = make_sampler(/*topP*/0.95f, /*temp*/0.0f); // sẽ rebuild trong infer()

    LOGI("Model & context ready");
    return JNI_TRUE;
}

// --------- JNI: infer ---------
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ragapp_LlamaBridge_infer(
        JNIEnv* env, jclass /*clazz*/,
        jstring jPrompt, jint maxTokens, jfloat temp, jfloat topP) {
    if (!g_ctx || !g_model || !g_vocab) {
        return env->NewStringUTF("(init() not called)");
    }

    // 1) Tokenize
    const char* cprompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(cprompt ? cprompt : "");
    env->ReleaseStringUTFChars(jPrompt, cprompt);

    std::vector<llama_token> ptok(prompt.size() + 8);
    int n_inp = llama_tokenize(
            g_vocab,
            prompt.c_str(), (int)prompt.size(),
            ptok.data(), (int)ptok.size(),
            /*add_special*/ true,
            /*parse_special*/ true
    );
    if (n_inp < 0) n_inp = 0;
    ptok.resize(n_inp);
    if (ptok.empty()) return env->NewStringUTF("");

    // 2) Prefill (batch API)
    llama_batch batch = llama_batch_init((int)ptok.size(), /*embd*/0, /*n_seq_max*/1);
    for (int i = 0; i < (int)ptok.size(); ++i) {
        batch.token[i]     = ptok[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = (i == (int)ptok.size() - 1);
    }
    batch.n_tokens = (int)ptok.size();

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("decode prefill failed");
        llama_batch_free(batch);
        return env->NewStringUTF("(decode prefill failed)");
    }

    // 3) Sampler theo tham số runtime
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    g_smpl = make_sampler(topP, temp);

    // 4) Vòng sinh
    std::vector<llama_token> out; out.reserve((size_t)maxTokens);
    int n_past = (int)ptok.size();
    int i_last = batch.n_tokens - 1;

    for (int step = 0; step < (int)maxTokens; ++step) {
        const llama_token tok = llama_sampler_sample(g_smpl, g_ctx, i_last);
        if (llama_vocab_is_eog(g_vocab, tok)) break; // EOS/EOT/EOM
        out.push_back(tok);

        // decode token vừa sinh
        llama_batch stepb = llama_batch_init(1, 0, 1);
        stepb.token[0]     = tok;
        stepb.pos[0]       = n_past;
        stepb.n_seq_id[0]  = 1;
        stepb.seq_id[0][0] = 0;
        stepb.logits[0]    = true;
        stepb.n_tokens     = 1;

        if (llama_decode(g_ctx, stepb) != 0) {
            LOGE("decode step failed at %d", step);
            llama_batch_free(stepb);
            break;
        }
        llama_batch_free(stepb);

        n_past += 1;
        i_last  = 0; // batch 1 token => logits ở index 0
    }

    llama_batch_free(batch);

    const std::string text = detok(out);
    return env->NewStringUTF(text.c_str());
}

// --------- JNI: release ---------
extern "C" JNIEXPORT void JNICALL
Java_com_example_ragapp_LlamaBridge_release(
        JNIEnv*, jclass /*clazz*/) {
    if (g_smpl)  { llama_sampler_free(g_smpl);  g_smpl  = nullptr; }
    if (g_ctx)   { llama_free(g_ctx);           g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);   g_model = nullptr; }
    g_vocab = nullptr;
    llama_backend_free();
}
