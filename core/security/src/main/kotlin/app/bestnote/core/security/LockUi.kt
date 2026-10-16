package app.bestnote.core.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.model.NotebookId
import app.bestnote.core.storage.AppPin
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LockEntryPoint {
    fun locks(): LockManager
}

/** The app's [LockManager], for composables outside a ViewModel. */
@Composable
fun rememberLockManager(): LockManager {
    val context = LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context, LockEntryPoint::class.java).locks() }
}

/** What the recovery / PIN step of a lock dialog asks for. */
internal enum class PinStep { None, Create, Enter }

/** Answers from the lock setup dialog. */
internal class SetupAnswer(val recoveryPassword: String?, val pin: String?)

/** The dialog [LockDialogs] shows right now. */
internal sealed interface LockPrompt {
    class EnterPin(val wrong: Boolean, val answer: CompletableDeferred<String?>) : LockPrompt
    class Recovery(val wrong: Boolean, val answer: CompletableDeferred<String?>) : LockPrompt
    class Setup(val count: Int, val folder: String?, val pin: PinStep, val wrongPin: Boolean, val answer: CompletableDeferred<SetupAnswer?>) : LockPrompt
    class RemoveLock(val count: Int, val answer: CompletableDeferred<Boolean>) : LockPrompt
    class PlainExport(val answer: CompletableDeferred<Boolean>) : LockPrompt
    class Lost(val answer: CompletableDeferred<Unit>) : LockPrompt
    class Failed(val answer: CompletableDeferred<Unit>) : LockPrompt
    data object Working : LockPrompt
}

/**
 * Runs lock, unlock and "remove lock" with their prompts (biometric / screen lock, app PIN, recovery password).
 * Each screen that offers them keeps one ([rememberLockController]) and draws [LockDialogs].
 */
@Stable
class LockController internal constructor(val manager: LockManager, private val activity: FragmentActivity?) {
    internal var prompt by mutableStateOf<LockPrompt?>(null)
        private set

    private suspend fun <T> ask(make: (CompletableDeferred<T>) -> LockPrompt): T {
        val answer = CompletableDeferred<T>()
        prompt = make(answer)
        return try {
            answer.await()
        } finally {
            prompt = null
        }
    }

    private suspend fun <T> working(block: suspend () -> T): T {
        prompt = LockPrompt.Working
        return try {
            block()
        } finally {
            if (prompt == LockPrompt.Working) prompt = null
        }
    }

    /** Runs [block]; a storage or Keystore failure is reported in a dialog (never a crash) and gives false. */
    private suspend fun guarded(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(TAG, "Lock operation failed", e)
        prompt = null
        ask<Unit> { LockPrompt.Failed(it) }
        false
    }

    /** Unlocks [ids] for this session, asking whatever is needed. @return true when all are unlocked. */
    suspend fun unlock(ids: Collection<NotebookId>): Boolean = guarded { unlockOrCancel(ids) }

    private suspend fun unlockOrCancel(ids: Collection<NotebookId>): Boolean {
        var wrongPin = false
        var wrongPassword = false
        var prompted = false
        while (true) {
            when (manager.tryUnlock(ids)) {
                LockManager.Need.Nothing -> return true
                LockManager.Need.Authentication -> {
                    if (prompted || !authenticate()) return false
                    prompted = true
                }
                LockManager.Need.Pin -> {
                    val pin = ask<String?> { LockPrompt.EnterPin(wrongPin, it) } ?: return false
                    wrongPin = !working { manager.openPin(pin) }
                }
                LockManager.Need.RecoveryPassword -> {
                    val password = ask<String?> { LockPrompt.Recovery(wrongPassword, it) } ?: return false
                    wrongPassword = !working { manager.unlockWithRecovery(ids, password) }
                }
                LockManager.Need.Lost -> {
                    ask<Unit> { LockPrompt.Lost(it) }
                    return false
                }
            }
        }
    }

    /**
     * Locks [ids] after the setup dialog (explanation, warning, optional recovery password, app PIN when the device has
     * no screen lock). [folder] names the folder being locked. @return false if cancelled.
     */
    suspend fun lock(ids: Collection<NotebookId>, folder: String? = null, keepUnlocked: Boolean = false): Boolean = guarded { lockOrCancel(ids, folder, keepUnlocked) }

    private suspend fun lockOrCancel(ids: Collection<NotebookId>, folder: String?, keepUnlocked: Boolean): Boolean {
        val step = when {
            !manager.usesPin -> PinStep.None
            !manager.hasPin -> PinStep.Create
            !manager.pinOpen -> PinStep.Enter
            else -> PinStep.None
        }
        var wrong = false
        while (true) {
            val answer = ask<SetupAnswer?> { LockPrompt.Setup(ids.size, folder, step, wrong, it) } ?: return false
            when (step) {
                PinStep.Create -> working { manager.createPin(answer.pin.orEmpty()) }
                PinStep.Enter -> if (!working { manager.openPin(answer.pin.orEmpty()) }) {
                    wrong = true
                    continue
                }
                PinStep.None -> Unit
            }
            working { manager.lock(ids, answer.recoveryPassword, keepUnlocked) }
            return true
        }
    }

    /** "Remove lock": confirms, unlocks and writes [ids] back unencrypted. */
    suspend fun removeLock(ids: Collection<NotebookId>): Boolean = guarded {
        if (!ask<Boolean> { LockPrompt.RemoveLock(ids.size, it) }) return@guarded false
        if (!unlockOrCancel(ids)) return@guarded false
        working { manager.removeLock(ids) }
        true
    }

    private companion object {
        const val TAG = "BestNoteLock"
    }

    /** Before exporting or sharing a locked notebook: says the file will not be encrypted. */
    suspend fun confirmPlainExport(): Boolean = ask { LockPrompt.PlainExport(it) }

    private suspend fun authenticate(): Boolean {
        val host = activity ?: return false
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(host.getString(R.string.lock_prompt_title))
            .setSubtitle(host.getString(R.string.lock_prompt_subtitle))
            .setAllowedAuthenticators(
                // BIOMETRIC_STRONG | DEVICE_CREDENTIAL is only accepted from Android 11.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
                else Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        manager.beginAuthentication()
        try {
            return suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(
                    host,
                    ContextCompat.getMainExecutor(host),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                )
                prompt.authenticate(info)
                cont.invokeOnCancellation { prompt.cancelAuthentication() }
            }
        } finally {
            manager.endAuthentication()
        }
    }
}

@Composable
fun rememberLockController(): LockController {
    val manager = rememberLockManager()
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    return remember(manager, activity) { LockController(manager, activity) }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Keeps the window out of screenshots and the recents preview while [enabled] (a locked notebook is open).
 * Only the screen that calls it: the flag is cleared when it leaves.
 */
@Composable
fun SecureWindow(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, enabled) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (enabled) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * Shows [content] for notebook [id] only while it may be shown: a locked notebook gets a lock screen with an Unlock
 * button (the prompt opens by itself), and once it is unlocked the window is kept out of screenshots and recents.
 * If the session relocks while it is open, the content is hidden again until the next unlock.
 */
@Composable
fun LockGate(id: NotebookId, onBack: () -> Unit, content: @Composable () -> Unit) {
    val controller = rememberLockController()
    val manager = controller.manager
    val ready by manager.ready.collectAsStateWithLifecycle()
    val sealed by manager.sealed.collectAsStateWithLifecycle()
    val unlocked by manager.unlocked.collectAsStateWithLifecycle()
    LaunchedEffect(manager) { if (!manager.ready.value) manager.refresh() }
    if (!ready) return
    val isSealed = id in sealed
    SecureWindow(isSealed)
    if (!isSealed || id in unlocked) {
        content()
        return
    }
    val scope = rememberCoroutineScope()
    var asked by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (!asked) {
            asked = true
            controller.unlock(listOf(id))
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).padding(Spacing.XL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(30.dp))
            }
            Text(stringResource(R.string.lock_gate_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(stringResource(R.string.lock_gate_body), style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.lock_back)) }
                Button(onClick = { scope.launch { controller.unlock(listOf(id)) } }) { Text(stringResource(R.string.lock_unlock)) }
            }
        }
    }
    LockDialogs(controller)
}

/** The dialogs of [controller]; draw once per screen that uses it. */
@Composable
fun LockDialogs(controller: LockController) {
    when (val p = controller.prompt) {
        null -> Unit
        LockPrompt.Working -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.L)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text(stringResource(R.string.lock_working))
                }
            },
        )
        is LockPrompt.EnterPin -> SecretDialog(
            title = stringResource(R.string.lock_pin_enter_title),
            body = stringResource(R.string.lock_pin_enter_body),
            label = stringResource(R.string.lock_pin_label),
            error = if (p.wrong) stringResource(R.string.lock_pin_wrong) else null,
            numeric = true,
            valid = AppPin::isValid,
            onDone = { p.answer.complete(it) },
        )
        is LockPrompt.Recovery -> SecretDialog(
            title = stringResource(R.string.lock_recovery_title),
            body = stringResource(R.string.lock_recovery_body),
            label = stringResource(R.string.lock_recovery_label),
            error = if (p.wrong) stringResource(R.string.lock_recovery_wrong) else null,
            numeric = false,
            valid = { it.isNotEmpty() },
            onDone = { p.answer.complete(it) },
        )
        is LockPrompt.Setup -> SetupDialog(p)
        is LockPrompt.RemoveLock -> ConfirmDialog(
            title = pluralStringResource(R.plurals.lock_remove_title, p.count, p.count),
            body = stringResource(R.string.lock_remove_body),
            confirm = stringResource(R.string.lock_remove_confirm),
            onAnswer = { p.answer.complete(it) },
        )
        is LockPrompt.PlainExport -> ConfirmDialog(
            title = stringResource(R.string.lock_export_title),
            body = stringResource(R.string.lock_export_body),
            confirm = stringResource(R.string.lock_export_confirm),
            onAnswer = { p.answer.complete(it) },
        )
        is LockPrompt.Failed -> AlertDialog(
            onDismissRequest = { p.answer.complete(Unit) },
            title = { Text(stringResource(R.string.lock_failed_title)) },
            text = { Text(stringResource(R.string.lock_failed_body)) },
            confirmButton = { TextButton(onClick = { p.answer.complete(Unit) }) { Text(stringResource(R.string.lock_ok)) } },
        )
        is LockPrompt.Lost -> AlertDialog(
            onDismissRequest = { p.answer.complete(Unit) },
            title = { Text(stringResource(R.string.lock_lost_title)) },
            text = { Text(stringResource(R.string.lock_lost_body)) },
            confirmButton = { TextButton(onClick = { p.answer.complete(Unit) }) { Text(stringResource(R.string.lock_ok)) } },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.lock_cancel)) } },
    )
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, numeric: Boolean, error: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(if (numeric) v.filter { it in '0'..'9' }.take(AppPin.MAX_LENGTH) else v) },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecretDialog(title: String, body: String, label: String, error: String?, numeric: Boolean, valid: (String) -> Boolean, onDone: (String?) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                SecretField(value, { value = it }, label, numeric, error)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(value) }, enabled = valid(value)) { Text(stringResource(R.string.lock_unlock)) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.lock_cancel)) } },
    )
}

/** Minimum recovery password length: long enough that PBKDF2 is not the only defence. */
internal const val MIN_RECOVERY_LENGTH = 8

@Composable
private fun SetupDialog(p: LockPrompt.Setup) {
    var pin by remember { mutableStateOf("") }
    var pinAgain by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var recoveryAgain by remember { mutableStateOf("") }
    val pinOk = when (p.pin) {
        PinStep.None -> true
        PinStep.Create -> AppPin.isValid(pin) && pin == pinAgain
        PinStep.Enter -> AppPin.isValid(pin)
    }
    val recoveryOk = recovery.isEmpty() || (recovery.length >= MIN_RECOVERY_LENGTH && recovery == recoveryAgain)
    AlertDialog(
        onDismissRequest = { p.answer.complete(null) },
        title = {
            Text(
                when {
                    p.folder != null -> stringResource(R.string.lock_setup_title_folder, p.folder)
                    else -> pluralStringResource(R.plurals.lock_setup_title, p.count, p.count)
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                Text(
                    stringResource(if (p.pin == PinStep.None) R.string.lock_setup_body_device else R.string.lock_setup_body_pin),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.lock_setup_hidden), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
                when (p.pin) {
                    PinStep.Create -> {
                        SecretField(pin, { pin = it }, stringResource(R.string.lock_pin_new_label), numeric = true)
                        SecretField(
                            pinAgain,
                            { pinAgain = it },
                            stringResource(R.string.lock_pin_again_label),
                            numeric = true,
                            error = if (pinAgain.isNotEmpty() && pinAgain != pin) stringResource(R.string.lock_mismatch) else null,
                        )
                    }
                    PinStep.Enter -> SecretField(pin, { pin = it }, stringResource(R.string.lock_pin_label), numeric = true, error = if (p.wrongPin) stringResource(R.string.lock_pin_wrong) else null)
                    PinStep.None -> Unit
                }
                Text(stringResource(R.string.lock_setup_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                SecretField(
                    recovery,
                    { recovery = it },
                    stringResource(R.string.lock_recovery_optional_label),
                    numeric = false,
                    error = if (recovery.isNotEmpty() && recovery.length < MIN_RECOVERY_LENGTH) pluralStringResource(R.plurals.lock_recovery_short, MIN_RECOVERY_LENGTH, MIN_RECOVERY_LENGTH) else null,
                )
                if (recovery.isNotEmpty()) {
                    SecretField(
                        recoveryAgain,
                        { recoveryAgain = it },
                        stringResource(R.string.lock_recovery_again_label),
                        numeric = false,
                        error = if (recoveryAgain.isNotEmpty() && recoveryAgain != recovery) stringResource(R.string.lock_mismatch) else null,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { p.answer.complete(SetupAnswer(recovery.ifEmpty { null }, pin.ifEmpty { null })) },
                enabled = pinOk && recoveryOk,
            ) { Text(stringResource(R.string.lock_lock)) }
        },
        dismissButton = { TextButton(onClick = { p.answer.complete(null) }) { Text(stringResource(R.string.lock_cancel)) } },
    )
}
