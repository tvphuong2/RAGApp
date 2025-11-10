package com.example.ragapp.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.ragapp.LlamaBridge
import com.example.ragapp.model.Author
import com.example.ragapp.model.ChatUiState
import com.example.ragapp.model.GenerationPreset
import com.example.ragapp.model.MessageUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.update

private const val DEFAULT_MAX_THREADS = 4

class ChatViewModel(
    private val modelPath: String,
    private val presets: List<GenerationPreset> = defaultPresets()
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            presets = presets,
            selectedPreset = presets.firstOrNull()
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamingJob: Job? = null
    private val nThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1).coerceAtMost(DEFAULT_MAX_THREADS)

    init {
        _uiState.value.selectedPreset?.let { preset ->
            initializeModel(preset)
        }
    }

    fun onInputChange(input: String) {
        _uiState.update { it.copy(input = input) }
    }

    fun onPresetSelected(preset: GenerationPreset) {
        if (preset == _uiState.value.selectedPreset) return
        stopGeneration()
        _uiState.update { it.copy(selectedPreset = preset) }
        initializeModel(preset)
    }

    fun sendMessage() {
        val state = _uiState.value
        val prompt = state.input.trim()
        val preset = state.selectedPreset ?: return
        if (prompt.isEmpty() || state.isGenerating || !state.isModelReady) return

        val timestamp = System.currentTimeMillis()
        val userMsg = MessageUi(
            id = timestamp,
            author = Author.USER,
            text = prompt,
            timestampMs = timestamp
        )
        val botId = timestamp + 1
        val botMsg = MessageUi(
            id = botId,
            author = Author.BOT,
            text = "",
            timestampMs = System.currentTimeMillis()
        )

        streamingJob?.cancel()
        _uiState.update {
            it.copy(
                messages = it.messages + userMsg + botMsg,
                input = "",
                isGenerating = true,
                statusMessage = "Đang tạo phản hồi..."
            )
        }

        streamingJob = viewModelScope.launch {
            var finished = false
            try {
                streamCompletion(prompt, preset).collect { event ->
                    when (event) {
                        is StreamEvent.Token -> {
                            _uiState.update { current ->
                                current.copy(
                                    messages = current.messages.map { message ->
                                        if (message.id == botId) {
                                            message.copy(text = message.text + event.text)
                                        } else {
                                            message
                                        }
                                    }
                                )
                            }
                        }

                        is StreamEvent.Error -> {
                            finished = true
                            _uiState.update { current ->
                                current.copy(
                                    isGenerating = false,
                                    statusMessage = event.message
                                )
                            }
                        }

                        StreamEvent.Completed -> {
                            finished = true
                            _uiState.update { current ->
                                current.copy(
                                    isGenerating = false,
                                    statusMessage = null
                                )
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                _uiState.update { current ->
                    current.copy(
                        isGenerating = false,
                        statusMessage = "Đã dừng."
                    )
                }
                throw ce
            } finally {
                if (!finished) {
                    _uiState.update { current ->
                        current.copy(isGenerating = false)
                    }
                }
                streamingJob = null
            }
        }
    }

    fun stopGeneration() {
        if (!_uiState.value.isGenerating) return
        streamingJob?.cancel()
        streamingJob = null
        viewModelScope.launch(Dispatchers.IO) { LlamaBridge.cancel() }
        _uiState.update { it.copy(isGenerating = false, statusMessage = "Đã dừng.") }
    }

    private fun initializeModel(preset: GenerationPreset) {
        streamingJob?.cancel()
        streamingJob = null
        _uiState.update { it.copy(isModelReady = false, statusMessage = "Đang tải mô hình...") }
        viewModelScope.launch(Dispatchers.IO) {
            LlamaBridge.cancel()
            val ok = try {
                LlamaBridge.init(modelPath, preset.contextLength, nThreads)
            } catch (t: Throwable) {
                false
            }
            _uiState.update {
                it.copy(
                    isModelReady = ok,
                    isGenerating = false,
                    statusMessage = if (ok) null else "Không thể tải mô hình"
                )
            }
        }
    }

    override fun onCleared() {
        streamingJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { LlamaBridge.cancel() }
        viewModelScope.launch(Dispatchers.IO) { LlamaBridge.release() }
        super.onCleared()
    }

    private fun streamCompletion(prompt: String, preset: GenerationPreset) = callbackFlow<StreamEvent> {
        var finished = false
        val scope = this
        val callback = object : LlamaBridge.TokenCallback {
            override fun onToken(token: String) {
                scope.trySendBlocking(StreamEvent.Token(token))
            }

            override fun onCompleted() {
                finished = true
                scope.trySendBlocking(StreamEvent.Completed)
                scope.close()
            }

            override fun onError(message: String) {
                finished = true
                scope.trySendBlocking(StreamEvent.Error(message))
                scope.close()
            }
        }

        val started = try {
            LlamaBridge.inferStreaming(
                prompt = prompt,
                maxTokens = preset.maxTokens,
                temp = preset.temperature,
                topP = preset.topP,
                callback = callback
            )
        } catch (t: Throwable) {
            finished = true
            scope.trySendBlocking(StreamEvent.Error(t.message ?: "inferStreaming() lỗi"))
            false
        }

        if (!started && !finished) {
            finished = true
            scope.trySendBlocking(StreamEvent.Error("Không thể bắt đầu suy luận"))
            scope.close()
        }

        awaitClose {
            if (!finished) {
                LlamaBridge.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    sealed interface StreamEvent {
        data class Token(val text: String) : StreamEvent
        data class Error(val message: String) : StreamEvent
        data object Completed : StreamEvent
    }

    companion object {
        fun factory(modelPath: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ChatViewModel(modelPath) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }

        private fun defaultPresets() = listOf(
            GenerationPreset(name = "Nhanh", temperature = 0.1f, topP = 0.9f, maxTokens = 128, contextLength = 1024),
            GenerationPreset(name = "Cân bằng", temperature = 0.7f, topP = 0.95f, maxTokens = 256, contextLength = 2048),
            GenerationPreset(name = "Sáng tạo", temperature = 0.95f, topP = 0.98f, maxTokens = 512, contextLength = 3072)
        )
    }
}
