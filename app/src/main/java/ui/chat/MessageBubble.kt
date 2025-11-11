package com.example.ragapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val telemetry = if (!isUser) {
        buildList {
            message.firstTokenLatencyMs?.let { latency ->
                add(formatLatency(latency))
            }
            message.tokensPerSecond?.let { rate ->
                add(String.format(Locale.getDefault(), "Tốc độ: %.1f tokens/s", rate))
            }
        }
    } else {
        emptyList()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.Start else Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)            // giới hạn chiều rộng bong bóng
                .clip(RoundedCornerShape(18.dp))
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                textAlign = TextAlign.Start
            )
        }

        if (telemetry.isNotEmpty()) {
            Text(
                text = telemetry.joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

private fun formatLatency(latencyMs: Long): String {
    return if (latencyMs >= 1000) {
        String.format(Locale.getDefault(), "Latency token đầu: %.1f s", latencyMs / 1000.0)
    } else {
        "Latency token đầu: ${latencyMs} ms"
    }
}
