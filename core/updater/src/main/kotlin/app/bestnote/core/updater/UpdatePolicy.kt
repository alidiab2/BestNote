package app.bestnote.core.updater

import java.util.concurrent.TimeUnit

/** Persisted updater state. */
data class UpdateState(
    val autoCheck: Boolean = true,
    val lastCheckMillis: Long = 0,
    /** Highest versionCode the user chose "Skip this version" for; 0 = none. */
    val skippedCode: Long = 0,
    /** "Later": do not prompt before this time. */
    val laterUntilMillis: Long = 0,
)

/** Pure decisions, easy to test. */
object UpdatePolicy {
    val INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(24)

    fun isNewer(release: ReleaseInfo, current: Long): Boolean = release.versionCode > current

    /** Automatic checks run at most once per 24 h, and only if enabled. */
    fun shouldAutoCheck(state: UpdateState, now: Long): Boolean =
        state.autoCheck && (state.lastCheckMillis == 0L || now - state.lastCheckMillis >= INTERVAL_MILLIS || now < state.lastCheckMillis)

    /**
     * Whether to show the prompt. [manual] (the user tapped "Check for updates") ignores Later and
     * Skip, because they asked.
     */
    fun shouldPrompt(release: ReleaseInfo, current: Long, state: UpdateState, now: Long, manual: Boolean): Boolean {
        if (!isNewer(release, current)) return false
        if (manual) return true
        return release.versionCode > state.skippedCode && now >= state.laterUntilMillis
    }

    fun later(state: UpdateState, now: Long): UpdateState = state.copy(laterUntilMillis = now + INTERVAL_MILLIS)

    fun skip(state: UpdateState, release: ReleaseInfo): UpdateState =
        state.copy(skippedCode = maxOf(state.skippedCode, release.versionCode))

    fun checked(state: UpdateState, now: Long): UpdateState = state.copy(lastCheckMillis = now)
}
