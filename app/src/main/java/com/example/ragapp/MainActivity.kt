package com.example.ragapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.example.ragapp.ui.chat.ChatScreen
import com.example.ragapp.ui.splash.SplashScreen
import com.example.ragapp.ui.theme.RAGAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RAGAppTheme {
                var modelPath by remember { mutableStateOf<String?>(null) }

                if (modelPath == null) {
                    SplashScreen(
                        onReady = { path -> modelPath = path }
                    )
                } else {
                    // Sau này truyền modelPath cho init(...) của JNI ở ChatScreen
                    val path = requireNotNull(modelPath)
                    ChatScreen(modelPath = path)
                }
            }
        }
    }
}
