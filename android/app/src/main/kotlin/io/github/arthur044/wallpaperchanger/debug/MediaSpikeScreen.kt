package io.github.arthur044.wallpaperchanger.debug

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.arthur044.wallpaperchanger.AppContainer
import io.github.arthur044.wallpaperchanger.core.spike.SpikeLog
import io.github.arthur044.wallpaperchanger.core.spike.SpikeSummary
import io.github.arthur044.wallpaperchanger.core.spike.TrackSighting
import io.github.arthur044.wallpaperchanger.media.MediaSessionProbe
import io.github.arthur044.wallpaperchanger.media.notificationAccessGranted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * TEMPORARY (M13): runs the local MediaSession and the Web API side by side and
 * measures the difference, so decision D4 rests on numbers from this phone.
 * Turn syncing off first, so its polls don't mix into the counts.
 */
@Composable
fun MediaSpikeScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = remember { SpikeLog() }
    var running by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(log.summary()) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    var access by remember { mutableStateOf(context.notificationAccessGranted()) }

    fun say(line: String) {
        lines = (listOf("${clock.format(Instant.now())}  $line") + lines).take(20)
    }

    DisposableEffect(Unit) {
        onDispose { running = false }
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        access = context.notificationAccessGranted()
        if (!access) {
            say("Sem acesso a notificações: o MediaSession não é legível")
            running = false
            return@LaunchedEffect
        }
        launch {
            MediaSessionProbe(context).snapshots().collect { snapshot ->
                val before = log.summary().trackChanges
                log.onMediaSession(TrackSighting(snapshot.title, snapshot.artist, snapshot.atMillis))
                summary = log.summary()
                if (summary.trackChanges > before) {
                    context.appendEvent("S", snapshot.atMillis, snapshot.artist, snapshot.title)
                    say("SESSÃO  ${snapshot.artist} – ${snapshot.title} (álbum ${snapshot.album})")
                }
            }
        }
        launch {
            val interval = container.settings.settings.first().webApiPollInterval
            while (true) {
                val playing = try {
                    container.spotifyApi.currentlyPlaying()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    say("API falhou: ${e::class.simpleName}")
                    null
                }
                if (playing?.trackName == null) {
                    log.onWebApiCallOnly()
                    context.appendEvent("A", System.currentTimeMillis(), null, null)
                } else {
                    val before = log.summary().pairedChanges
                    val at = System.currentTimeMillis()
                    context.appendEvent("A", at, playing.artistName, playing.trackName)
                    log.onWebApi(TrackSighting(playing.trackName, playing.artistName, at))
                    if (log.summary().pairedChanges > before) {
                        val change = log.changes.last()
                        say(
                            "API     ${playing.artistName} – ${playing.trackName} · atraso " +
                                "${change.headStart?.inWholeSeconds}s · títulos batem=${change.keysMatch}",
                        )
                    }
                }
                summary = log.summary()
                delay(interval)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Debug · MediaSession (M13)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Desligue a sincronização antes de medir, para as consultas do serviço não " +
                "entrarem na conta. Deixe tocando uma playlist de álbuns variados.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Acesso a notificações: ${if (access) "concedido" else "faltando"}")
        Text(summary.describe(), style = MaterialTheme.typography.bodyLarge)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { Text("Acesso a notificações") }
            Button(onClick = { running = !running }) { Text(if (running) "Parar medição" else "Iniciar medição") }
            OutlinedButton(onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { writeReport(context, container, log, summary) }
                    say("Relatório em ${file.path}")
                }
            }) { Text("Salvar relatório") }
        }

        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Every event, on disk the moment it happens. The process died twice during
 * the first attempt (removed from recents, force-stopped) and took the whole
 * in-memory measurement with it; this file survives that.
 */
private fun Context.appendEvent(kind: String, atMillis: Long, artist: String?, title: String?) {
    val dir = File(cacheDir, "spike").apply { mkdirs() }
    File(dir, "events.log").appendText("$kind|$atMillis|${artist.orEmpty()}|${title.orEmpty()}\n")
}

private fun SpikeSummary.describe(): String = buildString {
    append("Trocas vistas pela sessão: $trackChanges (pareadas com a API: $pairedChanges)\n")
    append("Eventos da sessão: $mediaSessionEvents · chamadas à API: $webApiCalls\n")
    append("Atraso da API: médio ${averageHeadStart?.inWholeSeconds ?: "-"}s · pior ${worstHeadStart?.inWholeSeconds ?: "-"}s\n")
    append("Títulos divergentes: $titleMismatches")
}

private suspend fun writeReport(context: Context, container: AppContainer, log: SpikeLog, summary: SpikeSummary): File {
    val interval = container.settings.settings.first().webApiPollInterval
    val dir = File(context.cacheDir, "spike").apply { mkdirs() }
    val file = File(dir, "mediasession_report.txt")
    file.writeText(
        buildString {
            appendLine("M13 MediaSession spike · ${Instant.now()}")
            appendLine("Web API poll interval: $interval")
            appendLine()
            appendLine(summary.describe())
            appendLine()
            appendLine("changes (session -> api):")
            log.changes.forEach { change ->
                appendLine(
                    "- ${change.fromMediaSession.artist} – ${change.fromMediaSession.title} " +
                        "| api: ${change.fromWebApi?.artist} – ${change.fromWebApi?.title} " +
                        "| head start: ${change.headStart?.inWholeMilliseconds ?: "-"} ms " +
                        "| keys match: ${change.keysMatch}",
                )
            }
        },
    )
    return file
}

private val clock: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
