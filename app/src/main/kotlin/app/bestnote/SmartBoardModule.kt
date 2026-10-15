package app.bestnote

import android.content.Context
import app.bestnote.ai.vision.gemini.GeminiKeyChecker
import app.bestnote.ai.vision.gemini.GeminiSettings
import app.bestnote.ai.vision.gemini.KeystoreGeminiSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Smart Board provider wiring. The key lives only in [KeystoreGeminiSettings] (Android Keystore);
 * Settings (SET-01) edits it and the Smart Board pipeline (AI-04) reads it through [GeminiSettings].
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
}
