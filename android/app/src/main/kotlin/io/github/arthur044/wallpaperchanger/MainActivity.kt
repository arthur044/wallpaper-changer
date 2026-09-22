package io.github.arthur044.wallpaperchanger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.github.arthur044.wallpaperchanger.debug.AuthDebugScreen
import io.github.arthur044.wallpaperchanger.ui.AppTheme
import io.github.arthur044.wallpaperchanger.ui.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WallpaperApp).container
        // Opening the app is the user's own way back if the system refused a
        // background restart: bring syncing back if it was left on.
        if (savedInstanceState == null) {
            lifecycleScope.launch { container.syncController.resumeIfEnabled() }
        }
        setContent {
            AppTheme {
                // Two screens don't need a navigation library.
                var showDebug by rememberSaveable { mutableStateOf(false) }
                Scaffold { innerPadding ->
                    val modifier = Modifier.padding(innerPadding)
                    if (showDebug) {
                        BackHandler { showDebug = false }
                        AuthDebugScreen(container, modifier)
                    } else {
                        MainScreen(container, onOpenDebug = { showDebug = true }, modifier = modifier)
                    }
                }
            }
        }
    }
}
