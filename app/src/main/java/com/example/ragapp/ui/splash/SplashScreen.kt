package com.example.ragapp.ui.splash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ragapp.modelprep.SplashViewModel

@Composable
fun SplashScreen(
    onReady: (modelPath: String) -> Unit,
    vm: SplashViewModel = viewModel()
) {
    val ui by vm.ui.collectAsState()

    LaunchedEffect(Unit) { vm.startPrepare() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(ui.message ?: "Chuẩn bị mô hình...")
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = ui.progress, modifier = Modifier.width(260.dp))
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.cancel() }, enabled = ui.stage == "copying") { Text("Huỷ") }
        }
    }

    if (ui.done && ui.modelPath != null) {
        LaunchedEffect(ui.modelPath) { onReady(ui.modelPath!!) }
    }
}
