package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.DemoVisionProvider
import app.bestnote.ai.vision.NotesRequest
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import app.bestnote.engine.layout.NoteDocument

/** Debug builds: a recorded AI answer replayed for the photo (see SmartBoardViewModel.recordedAnswer). */
internal class RecordedVisionProvider(private val doc: NoteDocument) : VisionProvider {
    private val demo = DemoVisionProvider(latencyMs = 0)

    override suspend fun reconstructBoard(image: VisionImage, request: BoardRequest): AiDocument = demo.reconstructBoard(image, request)

    override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float, language: String?) = demo.beautify(selection, widthPt, heightPt, language)

    override suspend fun writeNotes(image: VisionImage, request: NotesRequest): NoteDocument = doc
}
