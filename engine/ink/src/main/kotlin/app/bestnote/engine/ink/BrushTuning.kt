package app.bestnote.engine.ink

/**
 * Every brush tuning constant in one place, so designers can tweak pens without reading render
 * code. Sizes are multiples of the user's chosen size; pressure is androidx.ink's normalized 0..1.
 * Widths ("…_WIDTH") are fractions of the pen's full width. Lengths in "brush sizes" are multiples
 * of the pen's width, so they scale with the size slider.
 *
 * Changing a value changes how *new* strokes look only if the family key in [InkFamily] is bumped
 * (e.g. `fountain.v1` -> `fountain.v2`); otherwise stored strokes re-render with the new tuning.
 */
object BrushTuning {
    /**
     * Input smoothing shared by every pen (androidx.ink's sliding-window input model). Each modeled
     * point is the average of the raw input in a window centred on it, with the raw input first
     * upsampled to [UPSAMPLING_HZ]. The window is centred, so it adds no lag: only the last half
     * window of the wet stroke is refined as new samples arrive.
     */
    object Smoothing {
        /** Wide enough to round off a 240 Hz digitizer's sample-to-sample corners and tremor. */
        const val WINDOW_MS = 28L

        /** Upsample sparse input (fast strokes, 120 Hz devices) so curves are made of short chords. */
        const val UPSAMPLING_HZ = 360

        /** Geometry simplification tolerance in stroke units (px at 1x zoom). */
        const val EPSILON = 0.05f
    }

    /** Pressure sensitivity (INK-04, [PenTuning.pressureSensitivity]). */
    object Pressure {
        /**
         * The constant pressure a pen gets at sensitivity 0: about normal writing pressure (the
         * real-writing corpus ramps 0.05 -> 0.55), so a pen at 0 looks like its everyday line.
         */
        const val NEUTRAL = 0.5f
    }

    /**
     * Stroke stabilization (INK-05, [StrokeStabilizer]): a speed-adaptive low-pass (1-euro filter)
     * on the pen position. Slow, wobbly movement is smoothed hard; at writing speed the cut-off
     * rises so the line never trails the pen by more than a frame.
     */
    object Stabilization {
        /** Cut-off (Hz) when the pen is still, at stabilization 1 and at 100. */
        const val MIN_CUTOFF_LOW_HZ = 12f
        const val MIN_CUTOFF_HIGH_HZ = 1f

        /**
         * Cut-off added per mm/s of pen speed. Writing (>= 20 mm/s) gets >= 11 Hz: at most one 60 Hz
         * frame of lag. Slower than that the line trails the pen by under 1 / (2 pi x 0.5) = 0.32 mm.
         */
        const val SPEED_COEFF = 0.5f

        /** Cut-off of the speed estimate itself. */
        const val SPEED_CUTOFF_HZ = 8f
    }

    /**
     * Taps and very short strokes (i/j dots, periods, Arabic nuqat) must leave a round dot of about
     * the pen's full width in every pen, whatever the pressure ramp or tapers say.
     */
    object Dot {
        /** A stroke shorter than this (traveled + remaining, in brush sizes) counts as a dot. */
        const val MAX_LENGTH = 2f

        /**
         * A dot's diameter as a multiple of the pen's full width. A pen that dwells for a tap pools
         * ink, so real dots read bolder than the line; at 1x they look like specks next to letters.
         */
        const val SCALE = 1.5f

        /**
         * Dashes: Ruqaa/Naskh two-dot dashes, short t-bars, a quick comma. Up to [DASH_FULL] brush
         * sizes long a stroke has no end tapers (it would be all taper: a hairline), and tapers come
         * back gradually until [DASH_LENGTH], from where a stroke tapers normally.
         */
        const val DASH_FULL = 6f
        const val DASH_LENGTH = 12f
    }

    /**
     * Fountain: a fine nib. Pressure swells the line, speed thins it a little, and the ends taper
     * gently; the nib is slightly oval at a fixed writing angle for a hint of thick/thin.
     */
    object Fountain {
        const val SIZE_SCALE = 1.15f

        /** Width at zero pressure; full width at full pressure. */
        const val MIN_PRESSURE_WIDTH = 0.4f
        const val RESPONSE_MS = 12L

        /** Speed in brush sizes per second: from [SLOW_SPEED] up to [FAST_SPEED] the line narrows to [FAST_WIDTH]. */
        const val SLOW_SPEED = 80f
        const val FAST_SPEED = 450f
        const val FAST_WIDTH = 0.8f

        /** Taper at each end, in brush sizes, and the width at the very tip. */
        const val TAPER_START = 1.5f
        const val TAPER_END = 2.5f
        const val TAPER_TIP_WIDTH = 0.45f

        /** Nib oval: height as a fraction of width, and the fixed nib angle in degrees. */
        const val NIB_ASPECT = 0.8f
        const val NIB_ANGLE_DEG = 40f
    }

    /** Ballpoint: near-constant width, a hint of pressure so it doesn't feel dead. */
    object Ballpoint {
        const val SIZE_SCALE = 0.75f
        const val MIN_PRESSURE_WIDTH = 0.85f
        const val RESPONSE_MS = 30L
    }

    /**
     * Brush pen: wide pressure range plus tapered entry and exit. At the default size normal
     * writing pressure stays well under full width, so counters of e/a/o stay open; pressing
     * harder gives the thick strokes.
     */
    object BrushPen {
        const val SIZE_SCALE = 1.7f
        const val MIN_PRESSURE_WIDTH = 0.22f
        const val RESPONSE_MS = 20L

        /** Taper length, in multiples of brush size, at the start and end of a stroke. */
        const val TAPER_LENGTH = 2f

        /** Width at the very tip of a taper, as a fraction of full width. */
        const val TAPER_TIP_WIDTH = 0.2f
    }

    /**
     * Pencil: graphite. Pressure darkens more than it widens, a paper-grain texture breaks up the
     * line, and tilting the pen widens and softens the stroke (side-of-lead shading).
     */
    object Pencil {
        const val SIZE_SCALE = 0.75f
        const val OPACITY = 0.9f

        /** Opacity at zero pressure (full at full pressure), and width at zero pressure. */
        const val MIN_PRESSURE_OPACITY = 0.45f
        const val MIN_PRESSURE_WIDTH = 0.8f
        const val RESPONSE_MS = 20L

        /** Tilt (radians from vertical) where shading starts and where it is full. */
        const val TILT_START_RAD = 1.0f
        const val TILT_FULL_RAD = 1.35f
        const val TILT_MAX_WIDTH = 2.6f
        const val TILT_MIN_OPACITY = 0.45f

        /** Grain: tile size in stroke units (fixed to the page, so it doesn't swim), and its strength 0..1. */
        const val GRAIN_TILE = 48f
        const val GRAIN_STRENGTH = 0.55f
        const val GRAIN_TEXTURE_PX = 256

        /** The first pencil's ([InkFamily.PencilV1]) grain bitmap size. */
        const val GRAIN_TEXTURE_PX_V1 = 128
    }

    /** Highlighter: flat chisel tip, translucent, composited under ink with multiply. */
    object Highlighter {
        /** 5 px default x 9 = 45 px chisel height: covers the x-height of normal (3–3.5 mm) writing. */
        const val SIZE_SCALE = 9f

        /** Opacity of the whole highlight layer (0.45..0.55 per the Atelier spec). */
        const val OPACITY = 0.5f

        /** Chisel nib: narrow in x, full height in y; slightly softened corners so overlapping ends blend. */
        const val CHISEL_SCALE_X = 0.25f
        const val CHISEL_SCALE_Y = 1f
        const val CHISEL_CORNER_ROUNDING = 0.2f
    }
}
