package app.bestnote.feature.settings.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bestnote.ai.vision.providers.account.AccountStatus
import app.bestnote.ai.vision.providers.account.SignInFailure
import app.bestnote.ai.vision.providers.account.SignInPhase
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.feature.settings.AccountStatusUi
import app.bestnote.feature.settings.AiAccountUiState
import app.bestnote.feature.settings.AiAccountViewModel
import app.bestnote.feature.settings.R
import java.text.NumberFormat
import java.util.Currency

/**
 * Settings → Smart Board AI → AI account (AI-11): "Sign in with OpenRouter" (OAuth PKCE in a
 * Custom Tab), the account's credits, sign out, and why chat subscriptions can't be used here.
 */
@Composable
internal fun AiAccountSection(viewModel: AiAccountViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.open.collect { openInTab(context, it) } }

    SettingsGroup(stringResource(R.string.settings_account_group)) {
        if (state.signedIn) {
            row { SignedInRow(state, viewModel) }
        } else {
            row { SignInRow(state, viewModel) }
        }
        row { SubscriptionsNote() }
    }
}

@Composable
private fun SignInRow(state: AiAccountUiState, viewModel: AiAccountViewModel) {
    Column(Modifier.fillMaxWidth().padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.AccountCircle, tint = MaterialTheme.colorScheme.primary, background = MaterialTheme.colorScheme.primaryContainer)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_account_openrouter_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_account_openrouter_sub), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
            }
        }
        when (val phase = state.phase) {
            is SignInPhase.Waiting, SignInPhase.Finishing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(if (phase == SignInPhase.Finishing) R.string.settings_account_finishing else R.string.settings_account_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.settings_account_cancel)) }
            }
            else -> Button(onClick = viewModel::signIn, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_account_sign_in))
            }
        }
        Notice(state.failure, ok = false)
    }
}

@Composable
private fun SignedInRow(state: AiAccountUiState, viewModel: AiAccountViewModel) {
    Column(Modifier.fillMaxWidth().padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.CheckCircle, tint = BestNoteTheme.colors.success, background = BestNoteTheme.colors.success.copy(alpha = 0.12f))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_account_signed_in), style = MaterialTheme.typography.bodyLarge)
                Text(statusText(state), style = MaterialTheme.typography.bodySmall, color = statusColor(state.status))
            }
        }
        Text(
            stringResource(if (state.inUse) R.string.settings_account_in_use else R.string.settings_account_not_in_use),
            style = MaterialTheme.typography.bodySmall,
            color = BestNoteTheme.colors.muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            if (!state.inUse) Button(onClick = viewModel::use) { Text(stringResource(R.string.settings_account_use)) }
            if (state.status == AccountStatusUi.Revoked) Button(onClick = viewModel::signIn) { Text(stringResource(R.string.settings_account_sign_in_again)) }
            OutlinedButton(onClick = viewModel::loadStatus) { Text(stringResource(R.string.settings_account_refresh)) }
            OutlinedButton(onClick = viewModel::signOut) { Text(stringResource(R.string.settings_account_sign_out)) }
        }
        Notice(if (state.phase == SignInPhase.Done) null else state.failure, ok = state.phase == SignInPhase.Done)
        Row(
            Modifier.fillMaxWidth().clip(BestNoteShapes.Control).clickable(role = Role.Button, onClick = viewModel::manage).padding(vertical = Spacing.S),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_account_manage), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_account_manage_sub), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
            }
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = BestNoteTheme.colors.muted)
        }
    }
}

/** Why ChatGPT Plus / Claude Pro can't be used, and what to do instead. Collapsed to one line. */
@Composable
private fun SubscriptionsNote() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = BestNoteTheme.colors.muted)
            Text(stringResource(R.string.settings_account_why_title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        val reduced = reducedMotion()
        AnimatedVisibility(open, enter = Motion.Enter.content(reduced), exit = Motion.Exit.content(reduced)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Text(stringResource(R.string.settings_account_why_body), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
                Text(stringResource(R.string.settings_account_why_instead), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
            }
        }
    }
}

@Composable
private fun Notice(failure: SignInFailure?, ok: Boolean) {
    val reduced = reducedMotion()
    AnimatedVisibility(visible = failure != null || ok, enter = Motion.Enter.content(reduced), exit = Motion.Exit.content(reduced)) {
        val color = if (ok) BestNoteTheme.colors.success else MaterialTheme.colorScheme.error
        val text = when (failure) {
            null -> stringResource(R.string.settings_account_done)
            SignInFailure.Cancelled -> stringResource(R.string.settings_account_err_cancelled)
            SignInFailure.TimedOut -> stringResource(R.string.settings_account_err_timeout)
            SignInFailure.Denied -> stringResource(R.string.settings_account_err_denied)
            SignInFailure.Rejected -> stringResource(R.string.settings_account_err_rejected)
            SignInFailure.Offline -> stringResource(R.string.settings_ai_err_offline)
            SignInFailure.Failed -> stringResource(R.string.settings_account_err_failed)
        }
        Row(
            Modifier.fillMaxWidth().clip(BestNoteShapes.Control).background(color.copy(alpha = 0.10f)).padding(Spacing.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = color)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun statusText(state: AiAccountUiState): String {
    val hint = state.keyHint?.let { stringResource(R.string.settings_ai_key_hint, it) }
    val status = when (val s = state.status) {
        null, AccountStatusUi.Loading -> stringResource(R.string.settings_account_status_loading)
        AccountStatusUi.Revoked -> stringResource(R.string.settings_account_status_revoked)
        AccountStatusUi.Unavailable -> stringResource(R.string.settings_account_status_unavailable)
        is AccountStatusUi.Ready -> creditsText(s.status)
    }
    return listOfNotNull(status, hint).joinToString("\n")
}

@Composable
private fun creditsText(s: AccountStatus): String {
    val money = NumberFormat.getCurrencyInstance(LocalConfiguration.current.locales[0]).apply { currency = Currency.getInstance("USD") }
    return when {
        s.limitRemaining != null -> stringResource(R.string.settings_account_status_left, money.format(s.limitRemaining))
        s.freeTier -> stringResource(R.string.settings_account_status_free)
        else -> stringResource(R.string.settings_account_status_used, money.format(s.usageMonthly ?: s.usage))
    }
}

@Composable
private fun statusColor(status: AccountStatusUi?) =
    if (status == AccountStatusUi.Revoked) MaterialTheme.colorScheme.error else BestNoteTheme.colors.muted

/** Custom Tab when a browser supports it, else any browser. */
private fun openInTab(context: Context, url: String) {
    val uri = url.toUri()
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
