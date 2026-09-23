package io.github.arthur044.wallpaperchanger.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.arthur044.wallpaperchanger.AppContainer
import io.github.arthur044.wallpaperchanger.auth.LoginResult
import io.github.arthur044.wallpaperchanger.auth.SpotifyAuth
import kotlinx.coroutines.launch
import android.provider.Settings as SystemSettings

/**
 * Wires [OnboardingContent] to the phone: reads the facts (again whenever the
 * user comes back from a system screen) and performs each step's action.
 */
@Composable
fun OnboardingScreen(container: AppContainer, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = null)

    // Permissions and battery settings change outside the app: re-read on every resume.
    var refreshes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refreshes++
        onPauseOrDispose { }
    }
    var signedIn by remember { mutableStateOf<Boolean?>(null) }
    var notificationsAllowed by remember { mutableStateOf(context.notificationsAllowed()) }
    var batteryUnrestricted by remember { mutableStateOf(context.batteryUnrestricted()) }
    LaunchedEffect(refreshes) {
        signedIn = container.spotifyAuth.status().signedIn
        notificationsAllowed = context.notificationsAllowed()
        batteryUnrestricted = context.batteryUnrestricted()
    }

    var skippedNotifications by rememberSaveable { mutableStateOf(false) }
    var skippedBattery by rememberSaveable { mutableStateOf(false) }
    var loginProblem by remember { mutableStateOf<LoginProblem?>(null) }
    var loginInProgress by remember { mutableStateOf(false) }

    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch {
            loginProblem = when (val login = container.spotifyAuth.completeAuthorization(result.data)) {
                LoginResult.Success -> null
                LoginResult.Cancelled -> LoginProblem.Cancelled
                is LoginResult.Failed -> LoginProblem.Failed(login.reason)
            }
            loginInProgress = false
            refreshes++
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) skippedNotifications = true
        refreshes++
    }

    val current = settings ?: return
    val session = signedIn ?: return
    val facts = OnboardingFacts(
        hasClientId = current.clientId.isNotBlank(),
        signedIn = session,
        notificationsAllowed = notificationsAllowed,
        batteryUnrestricted = batteryUnrestricted,
        skipped = buildSet {
            if (skippedNotifications) add(OnboardingStep.NOTIFICATIONS)
            if (skippedBattery) add(OnboardingStep.BATTERY)
        },
    )
    val redirectUri = SpotifyAuth.REDIRECT_URI.toString()

    OnboardingContent(
        ui = OnboardingUi(
            step = currentStep(facts),
            redirectUri = redirectUri,
            loginProblem = loginProblem,
            loginInProgress = loginInProgress,
            isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
        ),
        callbacks = OnboardingCallbacks(
            onOpenDashboard = { context.open(Intent(Intent.ACTION_VIEW, DASHBOARD_URL.toUri())) },
            onCopyRedirectUri = { context.copyToClipboard(redirectUri) },
            onSaveClientId = { id -> scope.launch { container.settings.update { it.copy(clientId = id) } } },
            onLogin = {
                loginProblem = null
                loginInProgress = true
                loginLauncher.launch(container.spotifyAuth.authorizationIntent(current.clientId))
            },
            onChangeClientId = {
                loginProblem = null
                scope.launch { container.settings.update { it.copy(clientId = "") } }
            },
            onAllowNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onAllowBackground = { context.requestUnrestrictedBattery() },
            onOpenAppSettings = { context.open(appDetailsIntent(context)) },
            onSkip = { step ->
                when (step) {
                    OnboardingStep.NOTIFICATIONS -> skippedNotifications = true
                    OnboardingStep.BATTERY -> skippedBattery = true
                    else -> Unit
                }
            },
            onStart = {
                container.appScope.launch {
                    container.settings.update { it.copy(onboardingDone = true) }
                    container.syncController.enable()
                }
                onFinished()
            },
        ),
        modifier = modifier,
    )
}

private fun Context.notificationsAllowed(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun Context.batteryUnrestricted(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

// The direct "allow?" dialog; if a device lacks it, the app's settings page instead.
// Sideloaded app whose whole job is a background sync: the exemption is its core use.
@Suppress("BatteryLife")
private fun Context.requestUnrestrictedBattery() {
    val request = Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
    if (!open(request)) open(appDetailsIntent(this))
}

private fun appDetailsIntent(context: Context) =
    Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

private fun Context.open(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    Log.w(TAG, "Nothing can open ${intent.action}", e)
    false
}

private fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Redirect URI", text))
}

private const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"
private const val TAG = "Onboarding"
