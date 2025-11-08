package com.example.ragapp.model

// Người gửi tin nhắn: USER hay BOT (để style bong bóng)
enum class Author { USER, BOT }

// Một item tin nhắn hiển thị trong danh sách
data class MessageUi(
    val id: Long,          // key ổn định cho LazyColumn (tránh nhảy layout)
    val author: Author,    // USER hay BOT
    val text: String,      // nội dung hiển thị
    val timestampMs: Long  // thời gian tạo (có thể dùng để sort/format)
)

// Toàn bộ trạng thái cần cho màn hình chat
data class ChatUiState(
    val messages: List<MessageUi> = emptyList(), // danh sách tin nhắn
    val input: String = "",                      // nội dung đang gõ
    val isModelReady: Boolean = false,           // đã init model chưa (để enable/disable nút)
    val isGenerating: Boolean = false            // đang sinh token? (để disable Send/enable Stop)
)
