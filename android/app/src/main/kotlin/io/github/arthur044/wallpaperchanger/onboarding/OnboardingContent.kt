package io.github.arthur044.wallpaperchanger.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.arthur044.wallpaperchanger.R

/** Why the last login attempt didn't produce a session. */
sealed interface LoginProblem {
    data object Cancelled : LoginProblem

    data class Failed(val detail: String) : LoginProblem
}

data class OnboardingUi(
    val step: OnboardingStep,
    val redirectUri: String,
    val loginProblem: LoginProblem? = null,
    val loginInProgress: Boolean = false,
    val isSamsung: Boolean = false,
)

class OnboardingCallbacks(
    val onOpenDashboard: () -> Unit = {},
    val onCopyRedirectUri: () -> Unit = {},
    val onSaveClientId: (String) -> Unit = {},
    val onLogin: () -> Unit = {},
    val onChangeClientId: () -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onAllowBackground: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onSkip: (OnboardingStep) -> Unit = {},
    val onStart: () -> Unit = {},
)

/** The first-run guide, stateless: [ui] says which step and how it went. */
@Composable
fun OnboardingContent(ui: OnboardingUi, callbacks: OnboardingCallbacks, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            if (ui.step != OnboardingStep.DONE) Progress(ui.step)
            when (ui.step) {
                OnboardingStep.CLIENT_ID -> ClientIdStep(ui, callbacks)
                OnboardingStep.LOGIN -> LoginStep(ui, callbacks)
                OnboardingStep.NOTIFICATIONS -> NotificationsStep(callbacks)
                OnboardingStep.BATTERY -> BatteryStep(ui, callbacks)
                OnboardingStep.DONE -> DoneStep(callbacks)
            }
        }
    }
}

@Composable
private fun Progress(step: OnboardingStep) {
    val number = step.ordinal + 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.onb_step_progress, number, ONBOARDING_STEP_COUNT),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag(TAG_PROGRESS),
        )
        LinearProgressIndicator(progress = { number / ONBOARDING_STEP_COUNT.toFloat() }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ClientIdStep(ui: OnboardingUi, callbacks: OnboardingCallbacks) {
    var draft by rememberSaveable { mutableStateOf("") }
    val valid = isValidClientId(draft)

    Title(stringResource(R.string.onb_client_title))
    Body(stringResource(R.string.onb_client_intro))

    Body(stringResource(R.string.onb_client_step1))
    OutlinedButton(onClick = callbacks.onOpenDashboard) { Text(stringResource(R.string.onb_open_dashboard)) }

    Body(stringResource(R.string.onb_client_step2))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Text(
                ui.redirectUri,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            TextButton(onClick = callbacks.onCopyRedirectUri) { Text(stringResource(R.string.onb_copy)) }
        }
    }

    Body(stringResource(R.string.onb_client_step3))
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(stringResource(R.string.onb_client_id_label)) },
        singleLine = true,
        isError = draft.isNotBlank() && !valid,
        supportingText = {
            if (draft.isNotBlank() && !valid) Text(stringResource(R.string.onb_client_id_invalid))
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth().testTag(TAG_CLIENT_ID),
    )
    Button(
        onClick = { callbacks.onSaveClientId(draft.trim()) },
        enabled = valid,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.onb_continue)) }
}

@Composable
private fun LoginStep(ui: OnboardingUi, callbacks: OnboardingCallbacks) {
    Title(stringResource(R.string.onb_login_title))
    Body(stringResource(R.string.onb_login_body))
    ui.loginProblem?.let { problem ->
        val text = when (problem) {
            LoginProblem.Cancelled -> stringResource(R.string.onb_login_cancelled, ui.redirectUri)
            is LoginProblem.Failed -> stringResource(R.string.onb_login_failed, problem.detail)
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp).testTag(TAG_LOGIN_PROBLEM),
            )
        }
    }
    Button(
        onClick = callbacks.onLogin,
        enabled = !ui.loginInProgress,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.onb_login_button)) }
    TextButton(onClick = callbacks.onChangeClientId) { Text(stringResource(R.string.onb_change_client_id)) }
}

@Composable
private fun NotificationsStep(callbacks: OnboardingCallbacks) {
    Title(stringResource(R.string.onb_notif_title))
    Body(stringResource(R.string.onb_notif_body))
    Button(onClick = callbacks.onAllowNotifications, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_allow))
    }
    TextButton(onClick = { callbacks.onSkip(OnboardingStep.NOTIFICATIONS) }) { Text(stringResource(R.string.onb_not_now)) }
}

@Composable
private fun BatteryStep(ui: OnboardingUi, callbacks: OnboardingCallbacks) {
    Title(stringResource(R.string.onb_battery_title))
    Body(stringResource(R.string.onb_battery_body))
    Button(onClick = callbacks.onAllowBackground, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_battery_button))
    }
    if (ui.isSamsung) {
        Body(stringResource(R.string.onb_battery_samsung), Modifier.testTag(TAG_SAMSUNG_HINT))
        OutlinedButton(onClick = callbacks.onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onb_open_app_settings))
        }
    }
    TextButton(onClick = { callbacks.onSkip(OnboardingStep.BATTERY) }) { Text(stringResource(R.string.onb_not_now)) }
}

@Composable
private fun DoneStep(callbacks: OnboardingCallbacks) {
    Title(stringResource(R.string.onb_done_title))
    Body(stringResource(R.string.onb_done_body))
    Button(onClick = callbacks.onStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_start)) }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}

internal const val TAG_PROGRESS = "onbProgress"
internal const val TAG_CLIENT_ID = "onbClientId"
internal const val TAG_LOGIN_PROBLEM = "onbLoginProblem"
internal const val TAG_SAMSUNG_HINT = "onbSamsungHint"
