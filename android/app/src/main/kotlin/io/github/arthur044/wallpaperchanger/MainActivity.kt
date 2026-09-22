package io.github.arthur044.wallpaperchanger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.github.arthur044.wallpaperchanger.debug.AuthDebugScreen
import io.github.arthur044.wallpaperchanger.onboarding.OnboardingScreen
import io.github.arthur044.wallpaperchanger.ui.AppTheme
import io.github.arthur044.wallpaperchanger.ui.MainScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Screen { ONBOARDING, MAIN, DEBUG }

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
                // Three screens don't need a navigation library.
                var screen by rememberSaveable { mutableStateOf<Screen?>(null) }
                LaunchedEffect(Unit) {
                    if (screen == null) {
                        val ready = container.settings.settings.first().onboardingDone &&
                            container.spotifyAuth.status().signedIn
                        screen = if (ready) Screen.MAIN else Screen.ONBOARDING
                    }
                }
                Scaffold { innerPadding ->
                    val modifier = Modifier.padding(innerPadding)
                    when (screen) {
                        null -> Unit // deciding; a blank frame for a few ms
                        Screen.ONBOARDING -> OnboardingScreen(container, onFinished = { screen = Screen.MAIN }, modifier)
                        Screen.MAIN -> MainScreen(
                            container,
                            onConnect = { screen = Screen.ONBOARDING },
                            onOpenDebug = { screen = Screen.DEBUG },
                            modifier = modifier,
                        )
                        Screen.DEBUG -> {
                            BackHandler { screen = Screen.MAIN }
                            AuthDebugScreen(container, modifier)
                        }
                    }
                }
            }
        }
    }
}
