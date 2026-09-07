package io.github.fotlab.fotlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.fotlab.fotlab.ui.MainWindowFrame
import io.github.fotlab.fotlab.ui.theme.AppTheme

/**
 * The single activity of the application.
 *
 * It enables edge-to-edge and hands rendering to the shell; all structure lives
 * in [MainWindowFrame].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                MainWindowFrame()
            }
        }
    }
}
