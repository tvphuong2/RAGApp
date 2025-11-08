package com.example.ragapp.ui.chat

import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ragapp.model.Author
import com.example.ragapp.model.ChatUiState
import com.example.ragapp.model.MessageUi
import kotlin.system.measureTimeMillis

/**
 * Màn hình chat tổng thể:
 * - Giữ state tạm thời bằng remember (messages, input, flags).
 * - Vẽ danh sách tin nhắn bằng LazyColumn.
 * - Gắn ChatInputBar ở dưới cùng và nối các callback (Init/Send/Stop).
 * - Hiện tại: logic mô phỏng để bạn bấm thử; ngày sau nối JNI vào đúng chỗ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier
) {
    var uiState by remember { mutableStateOf(ChatUiState()) }

    /**
     * Callback: khởi tạo model.
     * Hiện tại chỉ set cờ isModelReady=true + log; ngày mai nối JNI.init(modelPath,...).
     */
    val onInitClicked = {
        Log.d("ChatScreen", "Init model clicked")
        uiState = uiState.copy(isModelReady = true)
    }

    /**
     * Callback: gửi prompt.
     * - Thêm tin USER vào list.
     * - Set isGenerating=true để disable nút Send.
     * - (Tạm) mô phỏng trả lời BOT sau một khoảnh khắc.
     * Ngày mai: gọi JNI.infer(...) hoặc inferStreaming(...) ở đây.
     */
    val onSendClicked: (String) -> Unit = { text ->
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

        // Mô phỏng thời gian suy luận + câu trả lời ngắn
        val elapsed = measureTimeMillis {
            // no-op; nếu muốn có độ trễ, bạn có thể dùng LaunchedEffect + delay
        }
        Log.d("ChatScreen", "Pretend prefill/decode took ${elapsed}ms")

        // Thêm tin BOT (giả lập). Trong streaming thực, bạn sẽ update *cùng một* messageId.
        val botMsg = MessageUi(
            id = now + 1, // id khác để LazyColumn render item mới
            author = Author.BOT,
            text = "🤖 (demo) Đây là nơi mô hình trả lời cho prompt: \"$text\"",
            timestampMs = System.currentTimeMillis()
        )
        uiState = uiState.copy(
            messages = uiState.messages + botMsg,
            isGenerating = false
        )
    }

    /**
     * Callback: dừng suy luận.
     * Ngày mai sẽ gọi JNI.cancel()/ đặt cờ dừng trong native.
     */
    val onStopClicked = {
        Log.d("ChatScreen", "Stop clicked")
        uiState = uiState.copy(isGenerating = false)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Offline Chat") }
            )
        },
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
        // Danh sách tin nhắn; đặt contentPadding để không đè top/bottom bars
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(
                items = uiState.messages,
                key = { it.id } // key ổn định giúp tránh nhảy layout khi cập nhật
            ) { msg ->
                MessageBubble(message = msg)
            }
        }
    }
}
