package app.bestnote

import android.content.Context
import app.bestnote.ai.vision.gemini.GeminiKeyChecker
import app.bestnote.ai.vision.gemini.GeminiSettings
import app.bestnote.ai.vision.gemini.KeystoreGeminiSettings
import app.bestnote.ai.vision.providers.AiProviders
import app.bestnote.ai.vision.providers.KeystoreAiSettings
import app.bestnote.ai.vision.providers.SmartBoardStyles
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.providers.account.AiAccounts
import app.bestnote.ai.vision.providers.account.KeystoreAccountStore
import app.bestnote.ai.vision.providers.account.OkAuthHttp
import app.bestnote.ai.vision.providers.account.OpenRouterAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.bestnote.feature.settings.data.DebugInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * Smart Board provider wiring. Keys live only in [KeystoreAiSettings] / [KeystoreGeminiSettings]
 * (Android Keystore); Settings edits them and Smart Board reads the selected provider through
 * [AiProviders].
 */
@Module
@InstallIn(SingletonComponent::class)
object SmartBoardModule {
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient()

    @Provides
    @Singleton
    fun keystoreGeminiSettings(@ApplicationContext context: Context): KeystoreGeminiSettings = KeystoreGeminiSettings(context)

    @Provides
    fun geminiSettings(settings: KeystoreGeminiSettings): GeminiSettings = settings

    @Provides
    fun geminiKeyChecker(http: OkHttpClient): GeminiKeyChecker = GeminiKeyChecker(http)

    @Provides
    @Singleton
    fun aiSettings(@ApplicationContext context: Context, gemini: KeystoreGeminiSettings): KeystoreAiSettings = KeystoreAiSettings(context, gemini)

    @Provides
    @Singleton
    fun aiProviders(http: OkHttpClient, settings: KeystoreAiSettings): AiProviders = AiProviders(http, settings)

    /** AI-11 "Sign in with OpenRouter": app-wide so a sign-in survives Settings going behind the browser. */
    @Provides
    @Singleton
    fun aiAccounts(@ApplicationContext context: Context, http: OkHttpClient, settings: KeystoreAiSettings, providers: AiProviders): AiAccounts = AiAccounts(
        auth = OpenRouterAuth(OkAuthHttp(http)),
        store = KeystoreAccountStore(settings),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        returnUri = "${context.packageName}://ai-account/done",
        onSignedIn = { providers.refreshModels(AiProviderKind.OpenRouter) },
    )

    @Provides
    @Singleton
    fun smartBoardStyles(@ApplicationContext context: Context): SmartBoardStyles = SmartBoardStyles(context)

    @Provides
    @Singleton
    fun debugInfo(@ApplicationContext context: Context): DebugInfo = object : DebugInfo {
        override val versionName = BuildConfig.VERSION_NAME
        override val versionCode = BuildConfig.VERSION_CODE.toLong()
        override val commit = BuildConfig.GIT_COMMIT
        override val buildType = BuildConfig.BUILD_TYPE
        override val crashDir = File(context.filesDir, BestNoteApplication.CRASH_DIR)
    }
}
