/**
 * :core:datastore — small, non-secret user settings persisted with Jetpack DataStore as JSON.
 *
 * Exposes [UserPreferencesRepository]; nothing else in the app touches the DataStore directly.
 */
package app.bestnote.core.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { System, Light, Dark }

@Serializable
data class UserPreferences(
    /** When true only a stylus draws; fingers never ink (palm rejection's strongest mode). */
    val stylusOnly: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    /** Material You colors on Android 12+. */
    val dynamicColor: Boolean = true,
)

object UserPreferencesSerializer : Serializer<UserPreferences> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override val defaultValue = UserPreferences()

    override suspend fun readFrom(input: InputStream): UserPreferences = try {
        json.decodeFromString(UserPreferences.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable preferences", e)
    }

    override suspend fun writeTo(t: UserPreferences, output: OutputStream) =
        output.write(json.encodeToString(UserPreferences.serializer(), t).encodeToByteArray())
}

@Singleton
class UserPreferencesRepository @Inject constructor(private val store: DataStore<UserPreferences>) {
    val preferences: Flow<UserPreferences> = store.data

    suspend fun setStylusOnly(enabled: Boolean) {
        store.updateData { it.copy(stylusOnly = enabled) }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.updateData { it.copy(themeMode = mode) }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        store.updateData { it.copy(dynamicColor = enabled) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun userPreferencesStore(@ApplicationContext context: Context): DataStore<UserPreferences> =
        DataStoreFactory.create(UserPreferencesSerializer) { File(context.filesDir, "datastore/user_prefs.json") }
}
