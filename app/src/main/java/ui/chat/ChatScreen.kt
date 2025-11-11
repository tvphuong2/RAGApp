package com.example.ragapp.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ragapp.model.GenerationPreset
import com.example.ragapp.model.MessageUi

@Composable
fun ChatScreen(
    modelPath: String,
    modifier: Modifier = Modifier,
) {
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(modelPath))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PresetSelector(
            presets = uiState.presets,
            selected = uiState.selectedPreset,
            onSelect = viewModel::onPresetSelected
        )

        StatusHeader(
            isModelReady = uiState.isModelReady,
            statusMessage = uiState.statusMessage
        )

        ChatHistory(
            messages = uiState.messages,
            listState = listState,
            modifier = Modifier.weight(1f)
        )

        InputBar(
            input = uiState.input,
            onInputChange = viewModel::onInputChange,
            onSend = viewModel::sendMessage,
            onStop = viewModel::stopGeneration,
            canSend = uiState.input.isNotBlank() && uiState.isModelReady && !uiState.isGenerating,
            isGenerating = uiState.isGenerating,
            isModelReady = uiState.isModelReady,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        )
    }
}

@Composable
private fun PresetSelector(
    presets: List<GenerationPreset>,
    selected: GenerationPreset?,
    onSelect: (GenerationPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (presets.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(presets, key = { it.name }) { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onSelect(preset) },
                label = { Text(preset.name) }
            )
        }
    }
}

@Composable
private fun StatusHeader(
    isModelReady: Boolean,
    statusMessage: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!isModelReady) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = statusMessage ?: "Đang tải mô hình...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else if (!statusMessage.isNullOrBlank()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ChatHistory(
    messages: List<MessageUi>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
    ) {
        if (messages.isEmpty()) {
            Text(
                text = "Đặt câu hỏi để bắt đầu cuộc trò chuyện.",
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    canSend: Boolean,
    isGenerating: Boolean,
    isModelReady: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Nhập câu hỏi...") },
                enabled = isModelReady,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Spacer(Modifier.width(12.dp))
            val buttonLabel = if (isGenerating) "Dừng" else "Gửi"
            val buttonEnabled = isModelReady && (isGenerating || canSend)
            val onClick = if (isGenerating) onStop else onSend
            val buttonColors = if (isGenerating) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                )
            }

            Button(onClick = onClick, enabled = buttonEnabled, colors = buttonColors) {
                Text(buttonLabel)
            }
        }
    }
}
