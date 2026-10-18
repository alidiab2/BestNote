package app.bestnote.engine.canvas.select

import app.bestnote.core.model.EquationElement
import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.TextElement

/**
 * What the lasso picks up (CANVAS-20), like Goodnotes' lasso options: everything, or only handwriting, typed text
 * (text boxes and equations), pictures, or shapes (recognized shapes and connectors). Pure Kotlin.
 */
enum class LassoFilter {
    All,
    Handwriting,
    Text,
    Images,
    Shapes,
    ;

    /** Does this filter take a stroke? [isShape]: the stroke is a recognized shape (it has a `ShapeInfo`). */
    fun takesStroke(isShape: Boolean): Boolean = when (this) {
        All -> true
        Handwriting -> !isShape
        Shapes -> isShape
        Text, Images -> false
    }

    /** Does this filter take [e]? Smart Board notes are text boxes, equations and pictures, so they follow their kind. */
    fun takesElement(e: PageElement): Boolean = when (this) {
        All -> true
        Text -> e is TextElement || e is EquationElement
        Images -> e is ImageElement
        Handwriting, Shapes -> false
    }
}
