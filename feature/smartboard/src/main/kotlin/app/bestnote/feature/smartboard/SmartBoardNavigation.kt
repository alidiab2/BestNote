package app.bestnote.feature.smartboard

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import kotlinx.serialization.Serializable

/**
 * Smart Board for [notebookId], writing on paper the size of page [pageIndex] (the one on screen), inserting after it.
 * [continuePageId]: "Continue / add photo" on that Smart Board page (storage id); [reserved]: the lines the
 * user wrote on in its pages ([app.bestnote.engine.layout.ReservedLines] encoding), kept free.
 */
@Serializable
data class SmartBoardRoute(
    val notebookId: String,
    val widthPt: Float,
    val heightPt: Float,
    val template: String,
    val pageIndex: Int = -1,
    val continuePageId: String? = null,
    val reserved: String? = null,
)

fun NavController.navigateToSmartBoard(notebook: NotebookId, page: Page, continuePageId: String? = null, reserved: String? = null) =
    navigate(SmartBoardRoute(notebook.value, page.widthPt, page.heightPt, page.template.name, page.index, continuePageId, reserved))

/** [onInsert] receives the handwritten pages; the caller hands them to the editor and goes back. */
fun NavGraphBuilder.smartBoardScreen(onClose: () -> Unit, onInsert: (SmartBoardResult) -> Unit) {
    composable<SmartBoardRoute> { SmartBoardScreen(onClose, onInsert) }
}
