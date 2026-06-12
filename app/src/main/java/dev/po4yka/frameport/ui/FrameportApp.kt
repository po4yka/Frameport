package dev.po4yka.frameport.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.navigation3.runtime.rememberNavBackStack
import dev.po4yka.frameport.navigation.FrameportDestination
import dev.po4yka.frameport.navigation.FrameportNavHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameportApp() {
    val backStack = rememberNavBackStack(FrameportDestination.Onboarding)
    val title by remember {
        derivedStateOf {
            (backStack.lastOrNull() as? FrameportDestination)?.title ?: FrameportDestination.Home.title
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(text = title) })
        },
    ) { innerPadding ->
        FrameportNavHost(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            backStack = backStack,
        )
    }
}
