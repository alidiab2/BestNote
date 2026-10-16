package app.bestnote.core.updater

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Updater state in its own small SharedPreferences file. */
interface UpdateStore {
    fun load(): UpdateState
    fun save(state: UpdateState)
}

@Singleton
class PrefsUpdateStore @Inject constructor(@ApplicationContext context: Context) : UpdateStore {
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    override fun load() = UpdateState(
        autoCheck = prefs.getBoolean("auto", true),
        lastCheckMillis = prefs.getLong("last", 0),
        skippedCode = prefs.getLong("skipped", 0),
        laterUntilMillis = prefs.getLong("later", 0),
    )

    override fun save(state: UpdateState) {
        prefs.edit()
            .putBoolean("auto", state.autoCheck)
            .putLong("last", state.lastCheckMillis)
            .putLong("skipped", state.skippedCode)
            .putLong("later", state.laterUntilMillis)
            .apply()
    }
}
