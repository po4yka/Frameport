package dev.po4yka.frameport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.ui.HomeScreen
import org.junit.Rule
import org.junit.Test

class HomeScreenSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeScreenShowsPrimaryActions() {
        composeRule.setContent {
            FrameportTheme {
                HomeScreen(
                    onConnectCamera = {},
                    onBrowseMedia = {},
                    onImportQueue = {},
                    onRemote = {},
                    onLiveView = {},
                    onDiagnostics = {},
                    onSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Frameport").assertIsDisplayed()
        composeRule.onAllNodesWithText("Connect camera").assertCountEquals(2)
        composeRule.onAllNodesWithText("Diagnostics").assertCountEquals(2)
    }
}
