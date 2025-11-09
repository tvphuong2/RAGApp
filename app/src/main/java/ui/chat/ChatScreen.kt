package com.example.ragapp.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ragapp.LlamaBridge
import com.example.ragapp.model.Author
import com.example.ragapp.model.ChatUiState
import com.example.ragapp.model.MessageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.measureTimeMillis

private const val TAG = "ChatScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    modelPath: String,
    nCtx: Int = 1024,
    nThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
    maxTokens: Int = 128,
    temp: Float = 0.7f,
    topP: Float = 0.9f
) {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf(ChatUiState()) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    // Metrics/debug counters
    var initAttempts by remember { mutableIntStateOf(0) }
    var inferCount by remember { mutableIntStateOf(0) }
    var lastInitMs by remember { mutableLongStateOf(0L) }
    var lastInferMs by remember { mutableLongStateOf(0L) }
    var lastError by remember { mutableStateOf<String?>(null) }

    // Log khi modelPath đổi
    LaunchedEffect(modelPath) {
        Log.i(TAG, "modelPath changed -> $modelPath")
    }

    // Giải phóng native khi rời màn
    DisposableEffect(Unit) {
        onDispose {
            try {
                Log.i(TAG, "Releasing native resources…")
                LlamaBridge.release()
            } catch (t: Throwable) {
                Log.w(TAG, "release() threw: ${t.message}", t)
            }
        }
    }

    // ---------- Helpers ----------
    fun showSnack(msg: String) {
        Log.i(TAG, "SNACK: $msg")
        scope.launch { snack.showSnackbar(msg) }
    }

    fun shortPath(p: String): String = runCatching { File(p).name }.getOrElse { p }

    fun validateModelPath(p: String): Pair<Boolean, String?> {
        val f = File(p)
        if (!f.exists()) return false to "Model file not found: $p"
        if (!f.isFile)   return false to "Not a regular file: $p"
        if (f.length() <= 0L) return false to "Model file is empty: $p"
        return true to null
    }

    // ---------- Actions ----------
    /** Khởi tạo model */
    val onInitClicked: () -> Unit = init@{
        if (uiState.isModelReady || uiState.isGenerating) return@init
        uiState = uiState.copy(isGenerating = true)
        initAttempts += 1
        lastError = null

        scope.launch(Dispatchers.Default) {
            val (okPath, reason) = validateModelPath(modelPath)
            if (!okPath) {
                Log.e(TAG, "validateModelPath: $reason")
                lastError = reason
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(isGenerating = false, isModelReady = false)
                    showSnack(reason ?: "Invalid model path")
                }
                return@launch
            }

            Log.i(
                TAG,
                "init(): path='${shortPath(modelPath)}', size=${File(modelPath).length()}B, nCtx=$nCtx, nThreads=$nThreads, arch=${System.getProperty("os.arch")}"
            )

            var ok = false
            val elapsed = measureTimeMillis {
                ok = try {
                    LlamaBridge.init(modelPath, nCtx, nThreads)
                } catch (e: UnsatisfiedLinkError) {
                    lastError = "Native libs missing: ${e.message}"
                    Log.e(TAG, lastError!!, e)
                    false
                } catch (e: SecurityException) {
                    lastError = "No permission to read model: ${e.message}"
                    Log.e(TAG, lastError!!, e)
                    false
                } catch (e: Throwable) {
                    lastError = "init() failed: ${e.message}"
                    Log.e(TAG, lastError!!, e)
                    false
                }
            }
            lastInitMs = elapsed

            withContext(Dispatchers.Main) {
                uiState = uiState.copy(isModelReady = ok, isGenerating = false)
                if (ok) {
                    showSnack("Model loaded in ${elapsed}ms · ctx=$nCtx · threads=$nThreads")
                } else {
                    showSnack(lastError ?: "Init failed")
                }
            }
        }
    }

    /** Gửi prompt */
    val onSendClicked: (String) -> Unit = { text ->
        if (uiState.isModelReady && !uiState.isGenerating && text.isNotBlank()) {
            val now = System.currentTimeMillis()
            val userMsg = MessageUi(
                id = now,
                author = Author.USER,
                text = text,
                timestampMs = now
            )
            uiState = uiState.copy(
                messages = uiState.messages + userMsg,
                isGenerating = true
            )

            scope.launch(Dispatchers.Default) {
                Log.i(
                    TAG,
                    "infer(): tokens<=${maxTokens}, temp=$temp, topP=$topP, promptChars=${text.length}"
                )

                var reply = "(unknown error)"
                val elapsed = measureTimeMillis {
                    reply = try {
                        LlamaBridge.infer(
                            prompt = text,
                            maxTokens = maxTokens,
                            temp = temp,
                            topP = topP
                        )
                    } catch (e: UnsatisfiedLinkError) {
                        lastError = "Native libs missing during infer: ${e.message}"
                        Log.e(TAG, lastError!!, e)
                        "(native error: ${e.message})"
                    } catch (e: Throwable) {
                        lastError = "infer() failed: ${e.message}"
                        Log.e(TAG, lastError!!, e)
                        "(lỗi suy luận: ${e.message})"
                    }
                }
                lastInferMs = elapsed
                inferCount += 1

                withContext(Dispatchers.Main) {
                    val botMsg = MessageUi(
                        id = now + 1,
                        author = Author.BOT,
                        text = reply,
                        timestampMs = System.currentTimeMillis()
                    )
                    uiState = uiState.copy(
                        messages = uiState.messages + botMsg,
                        isGenerating = false
                    )
                    showSnack("Infer done in ${elapsed}ms")
                }
            }
        } else {
            if (!uiState.isModelReady) showSnack("Model not initialized")
        }
        Unit
    }

    /** Dừng (UI). Khi có native stop(), gọi ở đây. */
    val onStopClicked: () -> Unit = {
        // com.example.ragapp.LlamaBridge.stop()
        uiState = uiState.copy(isGenerating = false)
        Log.i(TAG, "Stop requested from UI")
        Unit
    }

    // ---------- UI ----------
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("Offline Chat") })
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            ChatInputBar(
                isModelReady = uiState.isModelReady,
                isGenerating = uiState.isGenerating,
                onInitClicked = onInitClicked,
                onSendClicked = onSendClicked,
                onStopClicked = onStopClicked
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Debug strip
            DebugStrip(
                modelFileName = shortPath(modelPath),
                fileSize = File(modelPath).takeIf { it.exists() }?.length() ?: -1L,
                nCtx = nCtx,
                nThreads = nThreads,
                initAttempts = initAttempts,
                inferCount = inferCount,
                lastInitMs = lastInitMs,
                lastInferMs = lastInferMs,
                lastError = lastError
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { msg ->
                    MessageBubble(message = msg)
                }
            }
        }
    }
}

/** Thanh trạng thái debug nho nhỏ ở đầu màn hình. */
@Composable
private fun DebugStrip(
    modelFileName: String,
    fileSize: Long,
    nCtx: Int,
    nThreads: Int,
    initAttempts: Int,
    inferCount: Int,
    lastInitMs: Long,
    lastInferMs: Long,
    lastError: String?
) {
    val sizeText = when {
        fileSize < 0L -> "?"
        fileSize < 1024 -> "${fileSize}B"
        fileSize < (1024 * 1024) -> "${fileSize / 1024}KB"
        else -> String.format("%.2fMB", fileSize.toDouble() / (1024 * 1024))
    }

    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(
                text = "Model: $modelFileName ($sizeText) · ctx=$nCtx · th=$nThreads",
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = "Init#=$initAttempts (last ${lastInitMs}ms) · Infer#=$inferCount (last ${lastInferMs}ms)",
                style = MaterialTheme.typography.labelSmall
            )
            if (!lastError.isNullOrBlank()) {
                Text(
                    text = "Last error: $lastError",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
