package com.example.ragapp.modelprep

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class PrepUiState(
    val stage: String = "idle",
    val progress: Float = 0f,
    val message: String? = null,
    val modelPath: String? = null,
    val nCtxHint: Int? = null,
    val done: Boolean = false
)

class SplashViewModel(app: Application) : AndroidViewModel(app) {

    private val preparer = ModelPreparer(app)
    private val _ui = MutableStateFlow(PrepUiState())
    val ui: StateFlow<PrepUiState> = _ui

    private var job: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    fun startPrepare() {
        if (job?.isActive == true) return
        cancelFlag.set(false)
        _ui.value = PrepUiState(stage = "preparing", progress = 0f, message = "Đang kiểm tra mô hình...")
        job = viewModelScope.launch {
            val res = preparer.ensureModelReady(
                progressCb = { copied, total ->
                    val p = if (total <= 0) 0f else copied.toFloat() / total.toFloat()
                    _ui.value = _ui.value.copy(
                        stage = "copying",
                        progress = p.coerceIn(0f, 1f),
                        message = "Đã sao chép ${human(copied)} / ${human(total)}"
                    )
                },
                cancelFlag = cancelFlag
            )
            _ui.value = if (res.ready) {
                _ui.value.copy(
                    stage = "done",
                    progress = 1f,
                    message = res.message,
                    modelPath = res.modelAbsolutePath,
                    nCtxHint = res.nCtxHint,
                    done = true
                )
            } else {
                _ui.value.copy(
                    stage = "error",
                    message = res.message ?: "Lỗi không rõ",
                    done = false
                )
            }
        }
    }

    fun cancel() {
        cancelFlag.set(true)
    }

    private fun human(b: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            b >= gb -> String.format("%.2f GB", b / gb)
            b >= mb -> String.format("%.2f MB", b / mb)
            b >= kb -> String.format("%.2f KB", b / kb)
            else -> "$b B"
        }
    }
}
