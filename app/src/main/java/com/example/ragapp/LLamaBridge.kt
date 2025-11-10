package com.example.ragapp

object LlamaBridge {
    init {
        try { System.loadLibrary("c++_shared") } catch (_: Throwable) {}
        try { System.loadLibrary("omp") } catch (_: Throwable) {}          // <-- thêm dòng này
        try { System.loadLibrary("ggml-base") } catch (_: Throwable) {}   // nếu gói theo module
        try { System.loadLibrary("ggml-cpu") } catch (_: Throwable) {}    // nếu gói theo module
        try { System.loadLibrary("ggml") } catch (_: Throwable) {}
        try { System.loadLibrary("llama") } catch (_: Throwable) {}
        System.loadLibrary("llamabridge")
    }
    @JvmStatic external fun init(modelPath: String, nCtx: Int, nThreads: Int): Boolean
    @JvmStatic external fun infer(prompt: String, maxTokens: Int, temp: Float, topP: Float): String
    @JvmStatic external fun release()
}

