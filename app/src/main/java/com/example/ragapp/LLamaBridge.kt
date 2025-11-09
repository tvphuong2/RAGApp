package com.example.ragapp

object LlamaBridge {
    init {
        // thứ tự: STL -> ggml -> llama -> shim
        try { System.loadLibrary("c++_shared") } catch (_: Throwable) {}
        try { System.loadLibrary("ggml") } catch (_: Throwable) {}
        try { System.loadLibrary("llama") } catch (_: Throwable) {}
        System.loadLibrary("llamabridge")
    }

    @JvmStatic external fun init(modelPath: String, nCtx: Int, nThreads: Int): Boolean
    @JvmStatic external fun infer(prompt: String, maxTokens: Int, temp: Float, topP: Float): String
    @JvmStatic external fun release()
}
