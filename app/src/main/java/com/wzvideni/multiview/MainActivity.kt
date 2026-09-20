package com.wzvideni.multiview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.wzvideni.multiview.ui.MultiStreamScreen
import com.wzvideni.multiview.ui.theme.MultiViewTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MultiViewTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0D111A)
                ) { innerPadding ->
                    MultiStreamScreen(
                        modifier = Modifier.padding(innerPadding),
                        onBackPressed = { finish() }
                    )
                }
            }
        }
    }
}