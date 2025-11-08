package com.example.ragapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
    }
    Spacer(Modifier.height(4.dp))
}
