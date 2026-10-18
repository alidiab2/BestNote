/**
 * :core:datastore — small, non-secret user settings persisted with Jetpack DataStore as JSON.
 *
 * Exposes [UserPreferencesRepository]; nothing else in the app touches the DataStore directly.
 * Secrets (the Smart Board API key) never live here: they stay in the Keystore-backed provider
 * settings of `:ai:vision-*`.
 */
package app.bestnote.core.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PenStyle
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

enum class ThemeMode { System, Light, Dark }

/** What holding the S Pen side button while writing does (SPEN-04): a temporary tool for that stroke. */
enum class SideButtonAction { TemporaryEraser, Lasso, Off }

/** Where the editor's pen tray floats (DS-03). Physical sides: they do not mirror in RTL. */
enum class ToolbarDock { Top, Left, Right }

/** What clicking the S Pen side button while hovering does (quick palette). */
enum class HoverButtonAction { QuickPalette, Off }

/** When a finger may lay down ink. Pens always draw; palms are rejected in every mode. */
enum class FingerDrawing {
    /** Stylus-only: fingers scroll and zoom. */
    Never,

    /** Fingers draw until a pen is seen on this device, then only scroll and zoom (SPEN-02 auto mode, the default). */
    WhenNoPen,

    /** Fingers always draw (while no pen is touching or hovering). */
    Always,
}

/** Pressure → width response. [apply] maps raw 0..1 pressure to the brush's 0..1 input. */
enum class PressureCurve(val gamma: Float) {
    /** Light touches already give thick lines. */
    Soft(SOFT_GAMMA),
    Normal(1f),

    /** Needs a heavier hand for thick lines. */
    Firm(FIRM_GAMMA),
    ;

    fun apply(pressure: Float): Float = pressure.coerceIn(0f, 1f).pow(gamma)
}

private const val SOFT_GAMMA = 0.55f
private const val FIRM_GAMMA = 1.8f

/** Default size of new pages. Points (1/72 in). */
enum class PaperSize(val widthPt: Float, val heightPt: Float) {
    A4(595f, 842f),
    A5(420f, 595f),
    Letter(612f, 792f),
}

/** How warm the paper chrome (backgrounds, sidebar, desk) is. The page itself never changes. */
enum class ChromeWarmth(val amount: Float) { Warm(1f), Soft(0.5f), Neutral(0f) }

@Serializable
data class StylusPreferences(
    /** Side button held while the pen touches the page. */
    val sideButtonHold: SideButtonAction = SideButtonAction.TemporaryEraser,
    val fingerDrawing: FingerDrawing = FingerDrawing.WhenNoPen,
    val hoverCursor: Boolean = true,
    val pressureCurve: PressureCurve = PressureCurve.Normal,
    val tiltShading: Boolean = true,
    /** A pen (S Pen, USI…) has touched or hovered over the editor on this device (SPEN-02). */
    val penSeen: Boolean = false,
)

@Serializable
data class GesturePreferences(
    val twoFingerTapUndo: Boolean = true,
    val threeFingerTapRedo: Boolean = true,
    val penDoubleTapLastTool: Boolean = true,
    val pinchToZoom: Boolean = true,
    val fingerScroll: Boolean = true,
)

@Serializable
data class WritingPreferences(
    val penStyle: PenStyle = PenStyle.Fountain,
    /** ARGB; defaults to the Atelier ink black (#1E2433). */
    val colorArgb: Int = DEFAULT_INK,
    /** Brush size in the ink engine's units (BrushCatalog.DEFAULT_SIZE = 5). */
    val size: Float = DEFAULT_PEN_SIZE,
    val template: PaperTemplate = PaperTemplate.Ruled,
    val paperSize: PaperSize = PaperSize.A4,
) {
    companion object {
        const val DEFAULT_INK: Int = 0xFF1E2433.toInt()
        const val DEFAULT_PEN_SIZE: Float = 5f
        const val MIN_PEN_SIZE: Float = 1f
        const val MAX_PEN_SIZE: Float = 20f
    }
}

@Serializable
data class AppearancePreferences(
    val chromeWarmth: ChromeWarmth = ChromeWarmth.Warm,
    /** Forces reduced motion on; when off the app follows the system "Remove animations". */
    val reduceMotion: Boolean = false,
)

@Serializable
data class StoragePreferences(
    /** Days a deleted notebook stays in Trash. 0 = never purge automatically. */
    val trashRetentionDays: Int = DEFAULT_TRASH_DAYS,
    /** LIB-11: seconds in the background before unlocked notebooks lock again. 0 = immediately, -1 = until the app closes. */
    val relockAfterSeconds: Int = DEFAULT_RELOCK_SECONDS,
) {
    companion object {
        const val DEFAULT_TRASH_DAYS = 30
        val RETENTION_CHOICES = listOf(7, 30, 60, 90, 0)
        const val DEFAULT_RELOCK_SECONDS = 60
        const val RELOCK_UNTIL_CLOSED = -1
        val RELOCK_CHOICES = listOf(0, 60, 300, RELOCK_UNTIL_CLOSED)
    }
}

/**
 * One pen's saved state (INK-04/05/06). [pen] is the editor's pen kind name (e.g. "Fountain").
 *
 * @property sizePx brush size in the ink engine's units.
 * @property pressureSensitivity 0..1 (1 = the pen's own pressure curve, 0 = constant width).
 * @property stabilization 0..100.
 * @property dash the line style name (INK-07: "Solid", "Dashed" or "Dotted"; unknown names read as solid).
 */
@Serializable
data class PenState(
    val pen: String,
    val colorArgb: Int,
    val sizePx: Float,
    val opacity: Float = 1f,
    val pressureSensitivity: Float = 1f,
    val stabilization: Int = 0,
    val dash: String = "Solid",
)

/** A favorite pen in the tray (INK-06): pen type + color + size (+ feel), picked in one tap. */
@Serializable
data class FavoritePen(val id: String, val state: PenState)

/**
 * The pen tray that outlives the editor (INK-04..06): every pen's settings, the user's saved custom
 * colors and favorite pens. Sanitized on write by [UserPreferencesRepository.updatePens].
 */
@Serializable
data class PenPreferences(
    val pens: List<PenState> = emptyList(),
    val savedColors: List<Int> = emptyList(),
    val favorites: List<FavoritePen> = emptyList(),
) {
    /** Clamps every value into range, drops duplicates and caps the lists. */
    fun sanitized(): PenPreferences = PenPreferences(
        pens = pens.distinctBy { it.pen }.map { it.sanitized() },
        savedColors = savedColors.map { it or OPAQUE }.distinct().take(MAX_SAVED_COLORS),
        favorites = favorites.distinctBy { it.id }.take(MAX_FAVORITES).map { it.copy(state = it.state.sanitized()) },
    )

    companion object {
        const val MAX_SAVED_COLORS = 12
        const val MAX_FAVORITES = 6
        private const val OPAQUE = 0xFF000000.toInt()
        private const val MAX_STABILIZATION = 100

        private fun PenState.sanitized() = copy(
            colorArgb = colorArgb or OPAQUE,
            sizePx = if (sizePx.isFinite() && sizePx > 0f) sizePx else WritingPreferences.DEFAULT_PEN_SIZE,
            opacity = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 1f,
            pressureSensitivity = if (pressureSensitivity.isFinite()) pressureSensitivity.coerceIn(0f, 1f) else 1f,
            stabilization = stabilization.coerceIn(0, MAX_STABILIZATION),
        )
    }
}

/**
 * All settings. The top-level fields predate the sections and are read directly by the editor
 * and theme (DS-01, DS-03); newer settings live in sections (SET-01).
 */
@Serializable
data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.System,
    /** Material You colors on Android 12+ (chrome only). Opt-in: off by default (DS-01). */
    val dynamicColor: Boolean = false,
    /** Editor pen tray position (DS-03). */
    val toolbarDock: ToolbarDock = ToolbarDock.Top,
    /** Left-handed mode: side docks and popovers move to the other physical side (DS-03). */
    val leftHanded: Boolean = false,
    /** Settings: "Side button click while hovering: Quick palette / Off". */
    val hoverButtonAction: HoverButtonAction = HoverButtonAction.QuickPalette,
    /** Settings: double-click the side button while hovering to undo. Opt-in. */
    val hoverDoubleClickUndo: Boolean = false,
    val stylus: StylusPreferences = StylusPreferences(),
    val gestures: GesturePreferences = GesturePreferences(),
    val writing: WritingPreferences = WritingPreferences(),
    val appearance: AppearancePreferences = AppearancePreferences(),
    val storage: StoragePreferences = StoragePreferences(),
    /** ONB-01: the first-run welcome was finished or skipped. Settings → About → "Show welcome again" clears it. */
    val onboardingDone: Boolean = false,
    val pens: PenPreferences = PenPreferences(),
) {
    /**
     * True when fingers must never ink (palm rejection's strongest mode). Derived from
     * [StylusPreferences.fingerDrawing], which replaced the stored `stylusOnly` flag.
     */
    val stylusOnly: Boolean get() = stylus.fingerDrawing == FingerDrawing.Never

    /** Settings "Draw with finger": an opt-in override of the auto stylus-only mode. */
    val drawWithFinger: Boolean get() = stylus.fingerDrawing == FingerDrawing.Always
}

object UserPreferencesSerializer : Serializer<UserPreferences> {
    // coerceInputValues: an enum value from a newer build falls back to the default instead of failing.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    override val defaultValue = UserPreferences()

    override suspend fun readFrom(input: InputStream): UserPreferences = try {
        json.decodeFromString(UserPreferences.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable preferences", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("Unreadable preferences", e)
    }

    override suspend fun writeTo(t: UserPreferences, output: OutputStream) =
        output.write(json.encodeToString(UserPreferences.serializer(), t).encodeToByteArray())
}

/**
 * The one read/write API for settings. Read a whole snapshot with [preferences] or one section
 * with [stylus], [gestures], [writing], [appearance], [storage] (each emits only on change).
 */
@Singleton
class UserPreferencesRepository @Inject constructor(private val store: DataStore<UserPreferences>) {
    val preferences: Flow<UserPreferences> = store.data

    val stylus: Flow<StylusPreferences> = section { it.stylus }
    val gestures: Flow<GesturePreferences> = section { it.gestures }
    val writing: Flow<WritingPreferences> = section { it.writing }
    val appearance: Flow<AppearancePreferences> = section { it.appearance }
    val storage: Flow<StoragePreferences> = section { it.storage }
    val pens: Flow<PenPreferences> = section { it.pens }

    suspend fun updateStylus(transform: (StylusPreferences) -> StylusPreferences) {
        store.updateData { it.copy(stylus = transform(it.stylus)) }
    }

    suspend fun updateGestures(transform: (GesturePreferences) -> GesturePreferences) {
        store.updateData { it.copy(gestures = transform(it.gestures)) }
    }

    suspend fun updateWriting(transform: (WritingPreferences) -> WritingPreferences) {
        store.updateData {
            val next = transform(it.writing)
            it.copy(writing = next.copy(size = next.size.coerceIn(WritingPreferences.MIN_PEN_SIZE, WritingPreferences.MAX_PEN_SIZE)))
        }
    }

    suspend fun updateAppearance(transform: (AppearancePreferences) -> AppearancePreferences) {
        store.updateData { it.copy(appearance = transform(it.appearance)) }
    }

    suspend fun updateStorage(transform: (StoragePreferences) -> StoragePreferences) {
        store.updateData {
            val next = transform(it.storage)
            it.copy(storage = next.copy(trashRetentionDays = next.trashRetentionDays.coerceAtLeast(0)))
        }
    }

    /** Pens, saved colors and favorites (INK-04..06); values are clamped and lists capped. */
    suspend fun updatePens(transform: (PenPreferences) -> PenPreferences) {
        store.updateData { it.copy(pens = transform(it.pens).sanitized()) }
    }

    /** Any top-level field (theme, dock, hover button…). */
    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        store.updateData(transform)
    }

    /** ONB-01: [done] = false shows the welcome again. */
    suspend fun setOnboardingDone(done: Boolean) = update { it.copy(onboardingDone = done) }

    suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    suspend fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    suspend fun setToolbarDock(dock: ToolbarDock) = update { it.copy(toolbarDock = dock) }

    suspend fun setLeftHanded(enabled: Boolean) = update { it.copy(leftHanded = enabled) }

    suspend fun setHoverButtonAction(action: HoverButtonAction) = update { it.copy(hoverButtonAction = action) }

    suspend fun setHoverDoubleClickUndo(enabled: Boolean) = update { it.copy(hoverDoubleClickUndo = enabled) }

    suspend fun setStylusOnly(enabled: Boolean) = updateStylus {
        it.copy(fingerDrawing = if (enabled) FingerDrawing.Never else FingerDrawing.WhenNoPen)
    }

    /** "Draw with finger" on = fingers always ink; off = auto stylus-only once a pen is seen. */
    suspend fun setDrawWithFinger(enabled: Boolean) = updateStylus {
        it.copy(fingerDrawing = if (enabled) FingerDrawing.Always else FingerDrawing.WhenNoPen)
    }

    /** Remembers that a pen was used here: fingers stop inking from now on (auto mode). */
    suspend fun markPenSeen() = updateStylus { if (it.penSeen) it else it.copy(penSeen = true) }

    private fun <T> section(pick: (UserPreferences) -> T): Flow<T> = store.data.map(pick).distinctUntilChanged()
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun userPreferencesStore(@ApplicationContext context: Context): DataStore<UserPreferences> =
        DataStoreFactory.create(
            serializer = UserPreferencesSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { UserPreferences() },
        ) { File(context.filesDir, "datastore/user_prefs.json") }
}
