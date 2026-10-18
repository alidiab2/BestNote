package app.bestnote.core.model

/**
 * INK-07: how a stroke's line is broken up. [Solid] is plain ink; [Dashed] and [Dotted] cut the stroke
 * into dashes or dots along its centerline (lengths scale with the pen's width). The stroke keeps every
 * sample, so it can be switched back to solid without loss.
 */
enum class LineDash { Solid, Dashed, Dotted }
