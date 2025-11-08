package com.example.ragapp.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.ragapp.R

/**
 * Thanh nhập liệu + các nút điều khiển.
 * - onInitClicked(): user muốn tải/khởi tạo model.
 * - onSendClicked(text): gửi prompt hiện tại lên tầng trên.
 * - onStopClicked(): yêu cầu dừng suy luận đang chạy.
 * - isModelReady / isGenerating: điều khiển enable/disable nút cho UX đúng.
 */
@Composable
fun ChatInputBar(
    isModelReady: Boolean,
    isGenerating: Boolean,
    onInitClicked: () -> Unit,
    onSendClicked: (String) -> Unit,
    onStopClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val focus = LocalFocusManager.current

    Column(
        modifier = modifier
            .navigationBarsPadding() // tránh đè vào thanh điều hướng
            .imePadding()            // khi bàn phím mở, thanh nhập trồi lên
            .padding(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

            // Nút Init: bật khi chưa sẵn sàng model
            Button(
                onClick = onInitClicked,
                enabled = !isModelReady && !isGenerating
            ) { Text("Init") }

            // Ô nhập prompt chiếm phần còn lại
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                label = { Text("Prompt") },
                singleLine = true
            )

            // Nút Send: chỉ bật khi model ready & không trong trạng thái generating
            Button(
                onClick = {
                    val content = text.text.trim()
                    if (content.isNotEmpty()) {
                        onSendClicked(content)
                        text = TextFieldValue("")
                        focus.clearFocus()
                    }
                },
                enabled = isModelReady && !isGenerating
            ) { Text("Send") }

            // Nút Stop: chỉ bật khi đang generating
            Button(
                onClick = onStopClicked,
                enabled = isGenerating
            ) { Text("Stop") }
        }
    }
}
