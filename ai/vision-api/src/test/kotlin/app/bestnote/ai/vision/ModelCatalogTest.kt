package app.bestnote.ai.vision

import app.bestnote.ai.vision.ModelCatalog.Preference
import app.bestnote.ai.vision.ModelCatalog.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCatalogTest {
    private fun models(vararg ids: String) = ids.map { AiModel(it) }

    @Test
    fun `versions ignore dates, snapshots and sizes`() {
        assertEquals(listOf(3, 8), ModelCatalog.version("gemini-3.8-flash"))
        assertEquals(listOf(5, 5), ModelCatalog.version("claude-opus-5-5"))
        assertEquals(listOf(4, 5), ModelCatalog.version("claude-haiku-4-5-20251001"))
        assertEquals(listOf(6, 1), ModelCatalog.version("gpt-6.1-sol"))
        assertEquals(listOf(1, 5), ModelCatalog.version("models/gemini-1.5-pro-002"))
        assertEquals(listOf(5), ModelCatalog.version("gpt-5-2025-08-07"))
    }

    @Test
    fun `tiers from family words`() {
        assertEquals(Tier.Quality, ModelCatalog.tierOf("gemini-4-argon"))
        assertEquals(Tier.Quality, ModelCatalog.tierOf("gemini-3.1-pro-preview"))
        assertEquals(Tier.Fast, ModelCatalog.tierOf("gemini-3.8-flash"))
        assertEquals(Tier.Lite, ModelCatalog.tierOf("gemini-3.5-flash-lite"))
        assertEquals(Tier.Quality, ModelCatalog.tierOf("claude-opus-5-5"))
        assertEquals(Tier.Balanced, ModelCatalog.tierOf("claude-sonnet-5-5"))
        assertEquals(Tier.Fast, ModelCatalog.tierOf("claude-haiku-5-5"))
        assertEquals(Tier.Frontier, ModelCatalog.tierOf("claude-fable-5-1"))
        assertEquals(Tier.Fast, ModelCatalog.tierOf("gpt-6-luna"))
        assertEquals(Tier.Balanced, ModelCatalog.tierOf("gpt-6.1-sol"))
        assertEquals(Tier.Quality, ModelCatalog.tierOf("gpt-6-astra"))
        assertEquals(Tier.Fast, ModelCatalog.tierOf("gpt-5-mini"))
    }

    @Test
    fun `gemini default is the newest pro-class model, fast is the newest flash`() {
        val list = models("gemini-2.5-flash", "gemini-2.5-pro", "gemini-3.1-pro-preview", "gemini-3-flash-preview",
            "gemini-3.6-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4-argon")
        assertEquals("gemini-4-argon", ModelCatalog.pick(list, Preference.Quality)!!.id)
        assertEquals("gemini-3.8-flash", ModelCatalog.pick(list, Preference.Fast)!!.id)
    }

    @Test
    fun `claude default skips the frontier tier and prefers stable aliases`() {
        val list = models("claude-fable-5-1", "claude-opus-5", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-haiku-4-5-20251001")
        assertEquals("claude-opus-5-5", ModelCatalog.pick(list, Preference.Quality)!!.id)
        assertEquals("claude-haiku-5-5", ModelCatalog.pick(list, Preference.Fast)!!.id)
    }

    @Test
    fun `stable beats preview at the same version`() {
        val list = models("gemini-3.1-pro-preview", "gemini-3.1-pro")
        assertEquals("gemini-3.1-pro", ModelCatalog.pick(list, Preference.Quality)!!.id)
    }

    @Test
    fun `models without vision are skipped and tiers fall back`() {
        val list = listOf(AiModel("gpt-6-astra", vision = false), AiModel("gpt-6.1-sol"), AiModel("gpt-6-luna"))
        assertEquals("gpt-6.1-sol", ModelCatalog.pick(list, Preference.Quality)!!.id)
        assertNull(ModelCatalog.pick(listOf(AiModel("x", vision = false)), Preference.Quality))
    }

    @Test
    fun `resolve keeps an explicit choice, else picks from the list, else the newest-known fallback`() {
        val list = models("claude-opus-5-5", "claude-haiku-5-5")
        assertEquals("claude-sonnet-5-5", ModelCatalog.resolve(AiProviderKind.Claude, "claude-sonnet-5-5", list))
        assertEquals("claude-opus-5-5", ModelCatalog.resolve(AiProviderKind.Claude, "", list))
        assertEquals("claude-haiku-5-5", ModelCatalog.resolve(AiProviderKind.Claude, ModelCatalog.AUTO_FAST, list))
        assertEquals("gemini-3.8-flash", ModelCatalog.resolve(AiProviderKind.Gemini, null, emptyList()))
        assertEquals("gemini-3.8-flash", ModelCatalog.resolve(AiProviderKind.Gemini, ModelCatalog.AUTO_FAST, emptyList()))
    }

    @Test
    fun `picker order groups by tier, newest first`() {
        val sorted = ModelCatalog.sorted(models("gemini-2.5-flash", "gemini-4-argon", "gemini-3.8-flash", "gemini-2.5-pro"))
        assertEquals(listOf("gemini-4-argon", "gemini-2.5-pro", "gemini-3.8-flash", "gemini-2.5-flash"), sorted.map { it.id })
    }

    @Test fun specialistModelsAreNeverPickedAutomatically() {
        val list = models("deep-research-pro-preview-12-2025", "gemini-3.8-flash", "gemini-3.6-flash-image", "text-embedding-005", "gemini-3.5-flash-live")
        assertEquals("gemini-3.8-flash", ModelCatalog.pick(list, Preference.Quality)!!.id)
        assertEquals(listOf("gemini-3.8-flash"), ModelCatalog.candidates(list, Preference.Quality).map { it.id })
    }

    @Test fun stableBeatsANewerPreviewInTheSameTier() {
        val list = models("gemini-3.1-pro-preview", "gemini-2.5-pro", "gemini-3.8-flash")
        assertEquals(listOf("gemini-2.5-pro", "gemini-3.1-pro-preview", "gemini-3.8-flash"), ModelCatalog.candidates(list, Preference.Quality).map { it.id })
    }

    @Test fun specialistModelsSortLastInThePicker() {
        val sorted = ModelCatalog.sorted(models("deep-research-pro-preview-12-2025", "gemini-3.8-flash"))
        assertEquals("deep-research-pro-preview-12-2025", sorted.last().id)
    }

    @Test
    fun `models marked text-only are skipped by every automatic choice`() {
        val list = models("deepseek-v4-pro", "deepseek-flash", "other-large")
        val marked = ModelCatalog.markTextOnly(list, setOf("deepseek-v4-pro", "custom-text"))
        assertEquals(false, marked.first { it.id == "deepseek-v4-pro" }.vision)
        assertEquals(false, marked.first { it.id == "custom-text" }.vision) // unknown ids are added, flagged
        assertEquals(null, marked.first { it.id == "deepseek-flash" }.vision)
        assertEquals(listOf("other-large", "deepseek-flash"), ModelCatalog.candidates(marked, Preference.Quality).map { it.id })
        assertEquals("other-large", ModelCatalog.resolve(AiProviderKind.OpenAiCompatible, "", marked))
        assertEquals(list, ModelCatalog.markTextOnly(list, emptySet()))
        // An explicit choice is still honoured (the app warns instead).
        assertEquals("deepseek-v4-pro", ModelCatalog.resolve(AiProviderKind.OpenAiCompatible, "deepseek-v4-pro", marked))
    }
}
