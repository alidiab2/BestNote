package app.bestnote.engine.ink

/** Default ink colors (approved "Atelier" direction), opaque ARGB. Brushes apply their own opacity. */
object InkPalette {
    const val BLACK: Int = 0xFF1E2433.toInt()
    const val BLUE: Int = 0xFF2A4BD7.toInt()
    const val RED: Int = 0xFFD9462B.toInt()
    const val GREEN: Int = 0xFF2F7D57.toInt()
    const val GRAPHITE: Int = 0xFF4A4D57.toInt()
    const val HIGHLIGHTER_YELLOW: Int = 0xFFF7D84A.toInt()

    val inks: List<Int> = listOf(BLACK, BLUE, RED, GREEN)
}
