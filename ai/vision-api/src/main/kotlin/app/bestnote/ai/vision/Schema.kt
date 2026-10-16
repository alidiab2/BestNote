package app.bestnote.ai.vision

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Provider-neutral wire schema the model must return (JSON mode). Deliberately flat and
 * forgiving: every field optional except `type`, so a partially-wrong answer still yields the
 * valid blocks. DocumentMapper validates and drops what it cannot use.
 */

@Serializable
data class AiDocument(
    val title: String? = null,
    val blocks: List<AiBlock> = emptyList(),
)

@Serializable
data class AiBlock(
    /** heading | definition | paragraph | bullets | callout | math | proof | margin_note | diagram */
    val type: String,
    /** Optional id so margin notes can point at the block they comment on. */
    val id: String? = null,
    /** Position in the *logical lecture flow* (0, 1, 2…), not the physical board position. */
    val order: Int? = null,
    /** title | cues | main | summary. Where the template should put it. */
    val role: String? = null,
    val level: Int? = null,
    /** Callout / definition / proof title. */
    val title: String? = null,
    val text: String? = null,
    val items: List<String>? = null,
    /** info | definition | theorem | key_result | warning | example | summary */
    val tone: String? = null,
    val latex: String? = null,
    /** True for a key formula the student would box. */
    val boxed: Boolean? = null,
    val steps: List<AiProofStep>? = null,
    /** For margin_note: the `id` of the block this remark belongs next to. */
    val annotates: String? = null,
    /** The lecturer's intent: regular | essential | annotation | emphasis. */
    val semantic: String? = null,
    /** The ink colour actually seen on the board ("red", "#d32f2f"). Fallback for [semantic]. */
    @SerialName("ink_color") val inkColor: String? = null,
    val diagram: AiDiagram? = null,
    /** Normalised 0..1 source bbox [x0,y0,x1,y1] in the photo, used when [order] is missing. */
    @SerialName("source_box") val sourceBox: List<Float>? = null,
    /** Language of the block's words (BCP 47, e.g. "he", "ar", "en"); direction comes from the letters themselves. */
    val lang: String? = null,
)

@Serializable
data class AiProofStep(
    val latex: String? = null,
    val text: String? = null,
    /** Why this step follows ("by the chain rule"). */
    val because: String? = null,
)

@Serializable
data class AiDiagram(
    /** Coordinate box [width, height] the shapes are expressed in. */
    val size: List<Float>,
    val shapes: List<AiShape> = emptyList(),
)

@Serializable
data class AiShape(
    /** line | arrow | rect | ellipse | polyline | polygon | path | label */
    val kind: String,
    /** Flat point list [x0,y0,x1,y1,...]; rect/ellipse use [x0,y0,x1,y1]. */
    val points: List<Float> = emptyList(),
    val rotation: Float? = null,
    val d: String? = null,
    val text: String? = null,
    val latex: Boolean? = null,
    val dashed: Boolean? = null,
    /** Board ink colour of this shape; mapped through the notebook palette. */
    val color: String? = null,
)
