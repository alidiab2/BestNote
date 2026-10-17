package app.bestnote.engine.mathtype

import app.bestnote.engine.mathtype.AtomKind.Bin
import app.bestnote.engine.mathtype.AtomKind.Close
import app.bestnote.engine.mathtype.AtomKind.Inner
import app.bestnote.engine.mathtype.AtomKind.Open
import app.bestnote.engine.mathtype.AtomKind.Ord
import app.bestnote.engine.mathtype.AtomKind.Punct
import app.bestnote.engine.mathtype.AtomKind.Rel

/** LaTeX command names → symbols, operators, delimiters and accents the typesetter knows. */
internal object MathSymbols {
    class Symbol(val text: String, val kind: AtomKind)

    private fun MutableMap<String, Symbol>.add(kind: AtomKind, vararg pairs: Pair<String, String>) {
        pairs.forEach { (k, v) -> put(k, Symbol(v, kind)) }
    }

    val symbols: Map<String, Symbol> = HashMap<String, Symbol>().apply {
        // Greek: lowercase (italic by default), uppercase (upright by default).
        add(
            Ord,
            "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ϵ", "varepsilon" to "ε", "zeta" to "ζ",
            "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ", "varkappa" to "ϰ", "lambda" to "λ", "mu" to "μ",
            "nu" to "ν", "xi" to "ξ", "omicron" to "ο", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ",
            "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "ϕ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
            "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
            "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        )
        add(
            Ord,
            "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃", "nexists" to "∄", "neg" to "¬", "lnot" to "¬",
            "emptyset" to "∅", "varnothing" to "∅", "angle" to "∠", "measuredangle" to "∡", "triangle" to "△", "square" to "□", "Box" to "□",
            "hbar" to "ℏ", "hslash" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ", "wp" to "℘", "prime" to "′",
            "degree" to "°", "top" to "⊤", "bot" to "⊥", "imath" to "ı", "jmath" to "ȷ", "S" to "§", "P" to "¶", "dagger" to "†",
            "ddagger" to "‡", "checkmark" to "✓", "therefore" to "∴", "because" to "∵", "clubsuit" to "♣", "diamondsuit" to "♢",
            "heartsuit" to "♡", "spadesuit" to "♠", "flat" to "♭", "natural" to "♮", "sharp" to "♯", "backslash" to "\\",
            "vert" to "|", "|" to "‖", "Vert" to "‖", "mathellipsis" to "…", "ldots" to "…", "dots" to "…", "dotsc" to "…", "dotso" to "…",
            "vdots" to "⋮", "ddots" to "⋱", "iddots" to "⋰", "%" to "%", "$" to "$", "#" to "#", "&" to "&", "_" to "_",
            "textbackslash" to "\\", "star" to "⋆", "infin" to "∞",
        )
        add(Inner, "cdots" to "⋯", "dotsb" to "⋯", "dotsm" to "⋯", "dotsi" to "⋯")
        add(
            Bin,
            "pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷", "cdot" to "⋅", "ast" to "∗", "circ" to "∘", "bullet" to "∙",
            "cup" to "∪", "cap" to "∩", "wedge" to "∧", "land" to "∧", "vee" to "∨", "lor" to "∨", "oplus" to "⊕", "ominus" to "⊖",
            "otimes" to "⊗", "oslash" to "⊘", "odot" to "⊙", "setminus" to "∖", "smallsetminus" to "∖", "sqcup" to "⊔", "sqcap" to "⊓",
            "uplus" to "⊎", "amalg" to "⨿", "diamond" to "⋄", "bigtriangleup" to "△", "bigtriangledown" to "▽", "triangleleft" to "◁",
            "triangleright" to "▷", "wr" to "≀", "dotplus" to "∔", "ltimes" to "⋉", "rtimes" to "⋊", "centerdot" to "⋅", "bigcirc" to "◯",
        )
        add(
            Rel,
            "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠", "leqslant" to "⩽", "geqslant" to "⩾",
            "ll" to "≪", "gg" to "≫", "lll" to "⋘", "ggg" to "⋙", "approx" to "≈", "approxeq" to "≊", "equiv" to "≡", "sim" to "∼",
            "simeq" to "≃", "cong" to "≅", "propto" to "∝", "doteq" to "≐", "triangleq" to "≜", "coloneqq" to "≔", "eqqcolon" to "≕",
            "in" to "∈", "notin" to "∉", "ni" to "∋", "owns" to "∋", "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆",
            "supseteq" to "⊇", "subsetneq" to "⊊", "supsetneq" to "⊋", "nsubseteq" to "⊈", "sqsubseteq" to "⊑", "sqsupseteq" to "⊒",
            "perp" to "⟂", "parallel" to "∥", "nparallel" to "∦", "mid" to "∣", "nmid" to "∤", "vdash" to "⊢", "dashv" to "⊣",
            "models" to "⊨", "prec" to "≺", "succ" to "≻", "preceq" to "⪯", "succeq" to "⪰", "asymp" to "≍", "bowtie" to "⋈",
            "smile" to "⌣", "frown" to "⌢", "nless" to "≮", "ngtr" to "≯", "nleq" to "≰", "ngeq" to "≱", "lesssim" to "≲",
            "gtrsim" to "≳", "neqsim" to "≂", "ncong" to "≇", "colon" to ":", "sqsubset" to "⊏", "sqsupset" to "⊐",
            // arrows
            "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←", "leftrightarrow" to "↔", "Rightarrow" to "⇒",
            "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "implies" to "⟹", "impliedby" to "⟸", "iff" to "⟺", "mapsto" to "↦",
            "longmapsto" to "⟼", "longrightarrow" to "⟶", "longleftarrow" to "⟵", "longleftrightarrow" to "⟷",
            "Longrightarrow" to "⟹", "Longleftarrow" to "⟸", "Longleftrightarrow" to "⟺", "uparrow" to "↑", "downarrow" to "↓",
            "updownarrow" to "↕", "Uparrow" to "⇑", "Downarrow" to "⇓", "Updownarrow" to "⇕", "nearrow" to "↗", "searrow" to "↘",
            "swarrow" to "↙", "nwarrow" to "↖", "hookrightarrow" to "↪", "hookleftarrow" to "↩", "rightharpoonup" to "⇀",
            "rightharpoondown" to "⇁", "leftharpoonup" to "↼", "leftharpoondown" to "↽", "rightleftharpoons" to "⇌",
            "leftrightharpoons" to "⇋", "rightrightarrows" to "⇉", "leftleftarrows" to "⇇", "rightleftarrows" to "⇄",
            "leftrightarrows" to "⇆", "nrightarrow" to "↛", "nleftarrow" to "↚", "nRightarrow" to "⇏", "nLeftrightarrow" to "⇎",
            "twoheadrightarrow" to "↠", "rightsquigarrow" to "⇝", "leadsto" to "⇝", "circlearrowright" to "↻", "circlearrowleft" to "↺",
            "longrightleftharpoons" to "⇌",
        )
        add(Punct, "ldotp" to ".", "cdotp" to "⋅")
        add(Open, "lbrace" to "{", "{" to "{", "langle" to "⟨", "lfloor" to "⌊", "lceil" to "⌈", "lbrack" to "[", "lvert" to "|", "lVert" to "‖", "lgroup" to "⟮")
        add(Close, "rbrace" to "}", "}" to "}", "rangle" to "⟩", "rfloor" to "⌋", "rceil" to "⌉", "rbrack" to "]", "rvert" to "|", "rVert" to "‖", "rgroup" to "⟯")
    }

    /** Large operators: symbol and whether limits go above/below in display style. */
    val bigOps: Map<String, Pair<String, Boolean>> = mapOf(
        "sum" to ("∑" to true), "prod" to ("∏" to true), "coprod" to ("∐" to true),
        "bigcup" to ("⋃" to true), "bigcap" to ("⋂" to true), "bigvee" to ("⋁" to true), "bigwedge" to ("⋀" to true),
        "bigoplus" to ("⨁" to true), "bigotimes" to ("⨂" to true), "bigodot" to ("⨀" to true), "biguplus" to ("⨄" to true),
        "bigsqcup" to ("⨆" to true),
        "int" to ("∫" to false), "iint" to ("∬" to false), "iiint" to ("∭" to false), "oint" to ("∮" to false),
        "oiint" to ("∯" to false), "oiiint" to ("∰" to false), "intop" to ("∫" to true), "smallint" to ("∫" to false),
    )

    /** Large-operator characters typed directly ("∑") → limits in display style. */
    val bigOpSymbols: Map<String, Boolean> by lazy { bigOps.values.associate { it.first to it.second }.filterKeys { it != "∫" } + ("∫" to false) }

    /** Operator names written upright; true = limits below in display style (lim, max…). */
    val opNames: Map<String, Boolean> = mapOf(
        "lim" to true, "liminf" to true, "limsup" to true, "max" to true, "min" to true, "sup" to true, "inf" to true,
        "det" to true, "gcd" to true, "Pr" to true, "argmax" to true, "argmin" to true,
        "sin" to false, "cos" to false, "tan" to false, "cot" to false, "sec" to false, "csc" to false, "arcsin" to false,
        "arccos" to false, "arctan" to false, "sinh" to false, "cosh" to false, "tanh" to false, "coth" to false, "log" to false,
        "ln" to false, "lg" to false, "exp" to false, "dim" to false, "ker" to false, "deg" to false, "arg" to false, "hom" to false,
        "sgn" to false, "tr" to false, "rank" to false, "span" to false, "mod" to false,
    )

    /** Display text of operator names that are not just their command name. */
    val opText: Map<String, String> = mapOf("liminf" to "lim inf", "limsup" to "lim sup", "argmax" to "arg max", "argmin" to "arg min")

    /** Accent command → (combining code point, stretchy). */
    val accents: Map<String, Pair<Int, Boolean>> = mapOf(
        "hat" to (0x302 to false), "widehat" to (0x302 to true), "check" to (0x30C to false), "widecheck" to (0x30C to true),
        "tilde" to (0x303 to false), "widetilde" to (0x303 to true), "acute" to (0x301 to false), "grave" to (0x300 to false),
        "dot" to (0x307 to false), "ddot" to (0x308 to false), "dddot" to (0x20DB to false), "breve" to (0x306 to false),
        "bar" to (0x304 to false), "vec" to (0x20D7 to false), "mathring" to (0x30A to false),
        "overrightarrow" to (0x20D7 to true), "overleftarrow" to (0x20D6 to true), "overleftrightarrow" to (0x20E1 to true),
    )

    /** Delimiter names after `\left`, `\big`… (single characters are read as themselves). */
    val delimiters: Map<String, String> = mapOf(
        "{" to "{", "}" to "}", "lbrace" to "{", "rbrace" to "}", "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋",
        "lceil" to "⌈", "rceil" to "⌉", "vert" to "|", "|" to "‖", "Vert" to "‖", "lvert" to "|", "rvert" to "|", "lVert" to "‖",
        "rVert" to "‖", "uparrow" to "↑", "downarrow" to "↓", "updownarrow" to "↕", "Uparrow" to "⇑", "Downarrow" to "⇓",
        "backslash" to "∖", "lbrack" to "[", "rbrack" to "]", "lgroup" to "⟮", "rgroup" to "⟯", "lmoustache" to "⎰", "rmoustache" to "⎱",
    )

    /** `\not` + relation → its negated relation. */
    val negations: Map<String, String> = mapOf(
        "=" to "≠", "<" to "≮", ">" to "≯", "≤" to "≰", "≥" to "≱", "∈" to "∉", "≡" to "≢", "∼" to "≁", "≈" to "≉", "⊂" to "⊄",
        "⊃" to "⊅", "⊆" to "⊈", "⊇" to "⊉", "∣" to "∤", "∥" to "∦", "∃" to "∄", "≃" to "≄", "≅" to "≇", "→" to "↛", "←" to "↚",
    )

    /** Spaces in mu (18 mu = 1 em). */
    val spaces: Map<String, Float> = mapOf(
        "," to 3f, "thinspace" to 3f, ":" to 4f, ">" to 4f, "medspace" to 4f, ";" to 5f, "thickspace" to 5f, "!" to -3f,
        "negthinspace" to -3f, "negmedspace" to -4f, "negthickspace" to -5f, "quad" to 18f, "qquad" to 36f, " " to 6f, "enspace" to 9f,
    )

    /** Spacing class of a character typed directly (or a Unicode symbol pasted in). */
    fun kindOf(c: String): AtomKind = when (c) {
        "+", "-", "*", "−", "±", "∓", "×", "÷", "⋅", "·", "∗", "∘", "∪", "∩", "∧", "∨", "⊕", "⊗", "∖" -> Bin
        "=", "<", ">", ":", "≤", "≥", "≠", "≈", "≡", "∼", "≃", "≅", "∝", "∈", "∉", "∋", "⊂", "⊃", "⊆", "⊇", "→", "←", "↔",
        "⇒", "⇐", "⇔", "⟹", "⟺", "↦", "∣", "∥", "⟂", "≪", "≫", "⩽", "⩾" -> Rel
        "(", "[", "{", "⟨", "⌊", "⌈" -> Open
        ")", "]", "}", "⟩", "⌋", "⌉", "!", "?" -> Close
        ",", ";" -> Punct
        else -> byText[c] ?: Ord
    }

    private val byText: Map<String, AtomKind> by lazy {
        HashMap<String, AtomKind>().apply { symbols.values.forEach { if (it.text.length <= 2) putIfAbsent(it.text, it.kind) } }
    }

    /** The code point that draws [cp] in [alphabet] (math italic, bold, double-struck…). */
    @Suppress("CyclomaticComplexMethod", "MagicNumber")
    fun styled(cp: Int, alphabet: Alphabet): Int {
        val upper = cp in 'A'.code..'Z'.code
        val lower = cp in 'a'.code..'z'.code
        val digit = cp in '0'.code..'9'.code
        val greekLower = cp in 0x3B1..0x3C9
        val greekUpper = cp in 0x391..0x3A9
        val greekVariant = greekVariants[cp]
        fun latin(upperBase: Int, lowerBase: Int, holes: Map<Char, Int> = emptyMap()): Int =
            holes[cp.toChar()] ?: if (upper) upperBase + cp - 'A'.code else lowerBase + cp - 'a'.code
        return when (alphabet) {
            Alphabet.Default, Alphabet.Italic -> when {
                cp == 'h'.code -> 0x210E
                upper || lower -> latin(0x1D434, 0x1D44E)
                greekLower -> 0x1D6FC + cp - 0x3B1
                greekVariant != null -> greekVariant
                alphabet == Alphabet.Italic && greekUpper -> 0x1D6E2 + cp - 0x391
                cp == 0x131 -> 0x1D6A4
                cp == 0x237 -> 0x1D6A5
                else -> cp
            }
            Alphabet.Upright -> cp
            Alphabet.Bold -> when {
                upper || lower -> latin(0x1D400, 0x1D41A)
                digit -> 0x1D7CE + cp - '0'.code
                greekUpper -> 0x1D6A8 + cp - 0x391
                greekLower -> 0x1D6C2 + cp - 0x3B1
                else -> cp
            }
            Alphabet.BoldItalic -> when {
                upper || lower -> latin(0x1D468, 0x1D482)
                digit -> 0x1D7CE + cp - '0'.code
                greekUpper -> 0x1D71C + cp - 0x391
                greekLower -> 0x1D736 + cp - 0x3B1
                else -> cp
            }
            Alphabet.Script -> if (upper || lower) latin(0x1D49C, 0x1D4B6, scriptHoles) else cp
            Alphabet.Fraktur -> if (upper || lower) latin(0x1D504, 0x1D51E, frakturHoles) else cp
            Alphabet.DoubleStruck -> when {
                upper || lower -> latin(0x1D538, 0x1D552, doubleStruckHoles)
                digit -> 0x1D7D8 + cp - '0'.code
                else -> cp
            }
        }
    }

    private val greekVariants = mapOf(0x3F5 to 0x1D716, 0x3D1 to 0x1D717, 0x3F0 to 0x1D718, 0x3D5 to 0x1D719, 0x3F1 to 0x1D71A, 0x3D6 to 0x1D71B)
    private val scriptHoles = mapOf(
        'B' to 0x212C, 'E' to 0x2130, 'F' to 0x2131, 'H' to 0x210B, 'I' to 0x2110, 'L' to 0x2112, 'M' to 0x2133, 'R' to 0x211B,
        'e' to 0x212F, 'g' to 0x210A, 'o' to 0x2134,
    )
    private val frakturHoles = mapOf('C' to 0x212D, 'H' to 0x210C, 'I' to 0x2111, 'R' to 0x211C, 'Z' to 0x2128)
    private val doubleStruckHoles = mapOf('C' to 0x2102, 'H' to 0x210D, 'N' to 0x2115, 'P' to 0x2119, 'Q' to 0x211A, 'R' to 0x211D, 'Z' to 0x2124)
}
