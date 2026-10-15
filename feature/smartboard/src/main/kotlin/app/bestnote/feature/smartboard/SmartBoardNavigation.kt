package app.bestnote.feature.smartboard

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import kotlinx.serialization.Serializable

/** Smart Board for [notebookId], writing on paper the size of the page being read. */
@Serializable
data class SmartBoardRoute(val notebookId: String, val widthPt: Float, val heightPt: Float, val template: String)

fun NavController.navigateToSmartBoard(notebook: NotebookId, page: Page) =
    navigate(SmartBoardRoute(notebook.value, page.widthPt, page.heightPt, page.template.name))

/** [onInsert] receives the handwritten pages; the caller hands them to the editor and goes back. */
fun NavGraphBuilder.smartBoardScreen(onClose: () -> Unit, onInsert: (SmartBoardResult) -> Unit) {
    composable<SmartBoardRoute> { SmartBoardScreen(onClose, onInsert) }
}
