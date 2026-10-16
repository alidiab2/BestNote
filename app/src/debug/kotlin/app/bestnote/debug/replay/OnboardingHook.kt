/*
 * Debug-only: shows the ONB-01 first-run flow again without clearing app data.
 *
 *   adb shell am broadcast -a app.bestnote.debug.ONBOARDING -p app.bestnote.debug
 *
 * Same effect as Settings → About → Show welcome again (clears the "onboarding done" flag).
 */
package app.bestnote.debug.replay

import android.content.Context
import app.bestnote.core.datastore.UserPreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface OnboardingHookEntry {
    fun preferences(): UserPreferencesRepository
}

internal fun showOnboardingAgain(context: Context) {
    val prefs = EntryPointAccessors.fromApplication(context.applicationContext, OnboardingHookEntry::class.java).preferences()
    CoroutineScope(Dispatchers.Default).launch { prefs.setOnboardingDone(false) }
}
