package com.example.ragapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ragapp.ui.chat.ChatScreen
import com.example.ragapp.ui.theme.RAGAppTheme

/**
 * Activity chính của app.
 * - enableEdgeToEdge(): cho phép UI tràn viền hệ thống (status/nav bar trong suốt).
 * - setContent { ... }: root của Compose; mọi UI bắt đầu từ đây.
 * - ChatScreen(): màn hình chat tối giản đã tạo ở trên.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RAGAppTheme {
                ChatScreen()
            }
        }
    }
}
