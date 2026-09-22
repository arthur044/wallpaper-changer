package io.github.arthur044.wallpaperchanger.debug

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.arthur044.wallpaperchanger.AppContainer
import io.github.arthur044.wallpaperchanger.auth.AuthStatus
import io.github.arthur044.wallpaperchanger.auth.LoginResult
import io.github.arthur044.wallpaperchanger.auth.SpotifyAuth
import io.github.arthur044.wallpaperchanger.core.spotify.SpotifyApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * TEMPORARY (M3): exercises the auth layer by hand on a real device. Replaced by
 * the onboarding wizard (M12) and the main screen (M11). Never shows the token.
 */
@Composable
fun AuthDebugScreen(container: AppContainer, onOpenSpike: () -> Unit = {}, modifier: Modifier = Modifier) {
    val auth = container.spotifyAuth
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = null)
    var clientIdInput by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<AuthStatus?>(null) }
    var log by remember { mutableStateOf(listOf<String>()) }

    fun say(message: String) {
        log = (listOf("${LocalTime.now().withNano(0)}  $message") + log).take(30)
    }

    // Runs an action, reporting any failure instead of crashing the screen.
    fun act(label: String, block: suspend () -> String) {
        scope.launch {
            val outcome = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "${e::class.simpleName}: ${e.message}"
            }
            say("$label → $outcome")
            status = auth.status()
        }
    }

    LaunchedEffect(settings?.clientId) {
        if (clientIdInput.isEmpty()) clientIdInput = settings?.clientId.orEmpty()
    }
    LaunchedEffect(Unit) { status = auth.status() }

    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        act("Login") {
            val login = auth.completeAuthorization(result.data)
            // A new session picks syncing back up if the user had left it on.
            val resumed = login is LoginResult.Success && container.syncController.resumeIfEnabled()
            if (resumed) "$login · sync retomado" else login.toString()
        }
    }
    val syncStatus by container.syncEngine.status.collectAsState()
    // The service runs either way; without this the notification is just hidden.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        say("Notificações ${if (granted) "permitidas" else "negadas"}")
        act("Iniciar sync") { container.syncController.enable(); "ligado (volta após reiniciar)" }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Debug · Auth (M3)", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = clientIdInput,
            onValueChange = { clientIdInput = it },
            label = { Text("Spotify Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Redirect URI: ${SpotifyAuth.REDIRECT_URI}", style = MaterialTheme.typography.bodySmall)
        Text(describe(status), style = MaterialTheme.typography.bodyLarge)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                act("Salvar Client ID") {
                    container.settings.update { it.copy(clientId = clientIdInput) }.clientId.let { "salvo ($it)" }
                }
            }) { Text("Salvar Client ID") }
            val savedClientId = settings?.clientId?.takeIf { it.isNotBlank() }
            Button(
                enabled = savedClientId != null,
                onClick = { savedClientId?.let { loginLauncher.launch(auth.authorizationIntent(it)) } },
            ) { Text("Login") }
            Button(onClick = { act("Tocando agora") { describePlayback(container.spotifyApi) } }) {
                Text("Tocando agora")
            }
            OutlinedButton(onClick = {
                act("Forçar refresh") { "nova validade ${formatTime(auth.forceRefresh())}" }
            }) { Text("Forçar refresh") }
            OutlinedButton(onClick = { act("Sair") { auth.signOut(); "sessão apagada" } }) { Text("Sair") }
            Button(onClick = {
                act("Amostras de render") {
                    val files = renderSamples(context, container)
                    "${files.size} PNGs em ${files.firstOrNull()?.parent}"
                }
            }) { Text("Amostras de render") }
            Button(onClick = { act("Compor (cache)") { composeCurrent(context, container) } }) {
                Text("Compor (cache)")
            }
            Button(onClick = { act("Aplicar wallpaper") { applyCurrent(context, container) } }) {
                Text("Aplicar wallpaper")
            }
        }

        val syncLock = settings?.syncLockScreen ?: false
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = syncLock,
                enabled = settings != null,
                onCheckedChange = { on -> act("Lock screen") { container.settings.update { it.copy(syncLockScreen = on) }; "sync=$on" } },
            )
            Text("Sincronizar lock screen")
        }

        Text("Sync: $syncStatus", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    act("Iniciar sync") { container.syncController.enable(); "ligado (volta após reiniciar)" }
                }
            }) { Text("Iniciar sync") }
            OutlinedButton(onClick = { act("Parar sync") { container.syncController.disable(); "desligado" } }) {
                Text("Parar sync")
            }
            OutlinedButton(onClick = { container.syncEngine.syncNow() }) { Text("Sincronizar agora") }
            OutlinedButton(onClick = onOpenSpike) { Text("Medir MediaSession") }
        }

        log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun describe(status: AuthStatus?): String = when {
    status == null -> "Carregando…"
    !status.signedIn -> "Desconectado"
    else -> "Conectado · token válido até ${formatTime(status.accessTokenExpiresAtMillis)}"
}

private fun formatTime(epochMillis: Long?): String =
    epochMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().withNano(0).toString() } ?: "?"

// Checks the real Web API payloads against SpotifyApi's parsing (M4).
private suspend fun describePlayback(api: SpotifyApi): String {
    val playing = api.currentlyPlaying() ?: return "nada tocando (204 ou sem faixa)"
    val tracks = playing.albumId?.let { api.albumTracks(it) }.orEmpty()
    return "${playing.artistName} – ${playing.trackName} · tocando=${playing.isPlaying} · " +
        "album=${playing.albumId} · arte=${playing.artUrl != null} · faixas no álbum=${tracks.size}"
}
