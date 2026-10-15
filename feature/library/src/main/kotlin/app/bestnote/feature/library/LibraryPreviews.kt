package app.bestnote.feature.library

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import java.util.concurrent.TimeUnit

private const val PREVIEW_NOW = 1_760_550_000_000L // a fixed evening, so previews are stable

private fun previewState(layout: LibraryLayout = LibraryLayout.Shelf): LibraryUiState {
    val metadata = InMemoryLibraryMetadata().apply {
        setFolders(listOf(Folder("uni", "University", 0), Folder("work", "Work", 1), Folder("personal", "Personal", 2)))
    }
    val titles = listOf("Organic Chemistry", "Linear Algebra", "ملاحظات المحاضرة", "Team Meetings", "Q4 Roadmap", "Sketchbook", "Travel Journal", "Reading Log")
    val folders = listOf("uni", "uni", "uni", "work", "work", null, "personal", "personal")
    val notebooks = titles.mapIndexed { i, title ->
        val id = NotebookId("preview-$i")
        metadata.put(id, NotebookMeta(coverIndex = i, folderId = folders[i], favorite = i % 3 == 0, lastPageIndex = 3 + i))
        val updated = PREVIEW_NOW - TimeUnit.HOURS.toMillis(i * 7L + 1)
        Notebook(id, title, updated, updated, pageCount = 12 + i * 4)
    }
    return buildLibraryState(notebooks, emptyList(), LibraryControls(layout = layout), metadata, 6, PREVIEW_NOW)
}

@Preview(name = "Library · shelf", widthDp = 1280, heightDp = 800)
@Composable
private fun LibraryShelfPreview() {
    BestNoteTheme(darkTheme = false) { LibraryContent(previewState(), PREVIEW_NOW, LibraryCallbacks()) }
}

@Preview(name = "Library · shelf · dark", widthDp = 1280, heightDp = 800)
@Composable
private fun LibraryShelfDarkPreview() {
    BestNoteTheme(darkTheme = true) { LibraryContent(previewState(), PREVIEW_NOW, LibraryCallbacks()) }
}

@Preview(name = "Library · grid", widthDp = 1280, heightDp = 800)
@Composable
private fun LibraryGridPreview() {
    BestNoteTheme(darkTheme = false) { LibraryContent(previewState(LibraryLayout.Grid), PREVIEW_NOW, LibraryCallbacks()) }
}

@Preview(name = "Library · list · RTL", widthDp = 1280, heightDp = 800, locale = "ar")
@Composable
private fun LibraryListRtlPreview() {
    BestNoteTheme(darkTheme = false) { LibraryContent(previewState(LibraryLayout.List), PREVIEW_NOW, LibraryCallbacks()) }
}

@Preview(name = "Cover", widthDp = 160, heightDp = 210)
@Composable
private fun CoverPreview() {
    BestNoteTheme { NotebookCover("Organic Chemistry", 1, Modifier.size(CoverWidth, CoverHeight)) }
}
