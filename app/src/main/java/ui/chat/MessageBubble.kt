package com.example.ragapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ragapp.model.Author
import com.example.ragapp.model.MessageUi
import java.util.Locale

/**
 * Hiển thị một bong bóng tin nhắn.
 * - Căn trái cho USER, căn phải cho BOT (hoặc ngược tùy sở thích).
 * - Giới hạn maxWidth để dòng chữ không quá dài.
 */
@Composable
fun MessageBubble(
    message: MessageUi,
    modifier: Modifier = Modifier
) {
    val isUser = message.author == Author.USER
    val bubbleColor = if (isUser)
        MaterialTheme.colorScheme.surfaceVariant
    else
        MaterialTheme.colorScheme.primaryContainer

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.Start else Arrangement.End
    ) {
        val horizontalAlignment = if (isUser) Alignment.Start else Alignment.End
        Column(horizontalAlignment = horizontalAlignment) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)            // giới hạn chiều rộng bong bóng
                    .clip(RoundedCornerShape(16.dp))
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start
                )
            }

            if (!isUser) {
                val info = buildList {
                    message.firstTokenLatencyMs?.let { latency ->
                        add(formatLatency(latency))
                    }
                    message.tokensPerSecond?.let { rate ->
                        add(String.format(Locale.getDefault(), "Tốc độ: %.1f tokens/s", rate))
                    }
                }
                if (info.isNotEmpty()) {
                    Text(
                        text = info.joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

private fun formatLatency(latencyMs: Long): String {
    return if (latencyMs >= 1000) {
        String.format(Locale.getDefault(), "Latency token đầu: %.1f s", latencyMs / 1000.0)
    } else {
        "Latency token đầu: ${latencyMs} ms"
    }
}
