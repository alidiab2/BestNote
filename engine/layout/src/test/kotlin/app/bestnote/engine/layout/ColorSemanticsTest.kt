package app.bestnote.engine.layout

import app.bestnote.core.model.InkSemantic
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSemanticsTest {
    @Test fun `board colour names map to intent`() {
        assertEquals(InkSemantic.Regular, ColorSemantics.fromBoardColor("black"))
        assertEquals(InkSemantic.Regular, ColorSemantics.fromBoardColor("white chalk"))
        assertEquals(InkSemantic.Essential, ColorSemantics.fromBoardColor("dark red"))
        assertEquals(InkSemantic.Annotation, ColorSemantics.fromBoardColor("green"))
        assertEquals(InkSemantic.Emphasis, ColorSemantics.fromBoardColor("yellow chalk"))
    }

    @Test fun `hex colours are classified by hue`() {
        assertEquals(InkSemantic.Essential, ColorSemantics.fromBoardColor("#d32f2f"))
        assertEquals(InkSemantic.Annotation, ColorSemantics.fromBoardColor("#2e7d32"))
        assertEquals(InkSemantic.Regular, ColorSemantics.fromBoardColor("#1565c0"))
        assertEquals(InkSemantic.Regular, ColorSemantics.fromBoardColor("#333333"))
        assertEquals(InkSemantic.Emphasis, ColorSemantics.fromBoardColor("#8e24aa"))
    }

    @Test fun `model intent wins over the literal colour`() {
        assertEquals(InkSemantic.Essential, ColorSemantics.resolve("essential", "blue"))
        assertEquals(InkSemantic.Annotation, ColorSemantics.resolve(null, "green"))
        assertEquals(InkSemantic.Regular, ColorSemantics.resolve("nonsense", null))
    }
}
