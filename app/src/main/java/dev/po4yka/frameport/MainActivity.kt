package dev.po4yka.frameport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.ui.FrameportApp

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FrameportTheme {
                FrameportApp()
            }
        }
    }
}
