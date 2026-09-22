package io.github.arthur044.wallpaperchanger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.arthur044.wallpaperchanger.debug.AuthDebugScreen

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
            MaterialTheme {
                Scaffold { innerPadding ->
                    AuthDebugScreen(container, Modifier.padding(innerPadding))
                }
            }
        }
    }
}
