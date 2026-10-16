package app.bestnote.feature.templates

import android.content.Context
import app.bestnote.core.fonts.AppFont
import app.bestnote.core.fonts.FontRegistry
import app.bestnote.core.fonts.FontScript
import app.bestnote.engine.templates.FontOptions
import app.bestnote.engine.templates.ScriptKey
import app.bestnote.engine.templates.TemplateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** The font ids a template may name, from the registry as it is right now. */
class RegistryFontOptions(private val registry: FontRegistry) : FontOptions {
    override fun ids(script: ScriptKey): List<String> {
        val s = when (script) {
            ScriptKey.Latin -> FontScript.Latin
            ScriptKey.Arabic -> FontScript.Arabic
            ScriptKey.Hebrew -> FontScript.Hebrew
        }
        val own = registry.forScript(s).map(AppFont::id)
        // The script's default first so a missing font falls back to it.
        val first = app.bestnote.core.fonts.BundledFonts.defaultFor(s)
        return (listOf(first) + own + "system").distinct()
    }

    override fun nameOf(id: String) = registry.nameOf(id)
}

@Module
@InstallIn(SingletonComponent::class)
object TemplatesModule {
    /** The user's templates (JSON files in files/templates), shared by the editor and Smart Board. */
    @Provides
    @Singleton
    fun templateStore(@ApplicationContext context: Context): TemplateStore {
        val registry = FontRegistry.get(context).also { it.start() }
        return TemplateStore(File(context.filesDir, "templates")) { RegistryFontOptions(registry) }
    }
}
