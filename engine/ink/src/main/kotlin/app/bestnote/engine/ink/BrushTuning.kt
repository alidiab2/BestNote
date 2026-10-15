package app.bestnote.engine.ink

/**
 * Every brush tuning constant in one place, so designers can tweak pens without reading render
 * code. Sizes are multiples of the user's chosen size; pressure is androidx.ink's normalized 0..1.
 *
 * Changing a value changes how *new* strokes look only if the family key in [InkFamily] is bumped
 * (e.g. `fountain.v1` -> `fountain.v2`); otherwise stored strokes re-render with the new tuning.
 */
object BrushTuning {
    /** Fountain: stock androidx.ink pressure pen, slightly finer than the user size. */
    object Fountain {
        const val SIZE_SCALE = 1f
    }

    /** Ballpoint: near-constant width, a hint of pressure so it doesn't feel dead. */
    object Ballpoint {
        const val SIZE_SCALE = 0.7f
        const val MIN_PRESSURE_WIDTH = 0.85f
        const val RESPONSE_MS = 30L
    }

    /** Brush pen: wide pressure range plus tapered entry and exit. */
    object BrushPen {
        const val SIZE_SCALE = 2.4f
        const val MIN_PRESSURE_WIDTH = 0.12f
        const val RESPONSE_MS = 25L

        /** Taper length, in multiples of brush size, at the start and end of a stroke. */
        const val TAPER_LENGTH = 4f

        /** Width at the very tip of a taper, as a fraction of full width. */
        const val TAPER_TIP_WIDTH = 0.15f
    }

    /** Pencil: placeholder until INK-02 adds graphite texture and tilt. */
    object Pencil {
        const val SIZE_SCALE = 0.5f
        const val OPACITY = 0.8f
    }

    /** Highlighter: flat chisel tip, translucent, composited under ink with multiply. */
    object Highlighter {
        const val SIZE_SCALE = 4.5f

        /** Opacity of the whole highlight layer (0.45..0.55 per the Atelier spec). */
        const val OPACITY = 0.5f

        /** Chisel nib: narrow in x, full height in y; softened corners so where strokes overlap, their ends blend instead of looking like boxes. */
        const val CHISEL_SCALE_X = 0.3f
        const val CHISEL_SCALE_Y = 1f
        const val CHISEL_CORNER_ROUNDING = 0.5f
    }
}
