package app.bestnote.engine.recognition.mlkit

import app.bestnote.core.database.PageSearchIndex
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PageRect
import app.bestnote.core.model.PageSearchHit
import app.bestnote.engine.recognition.HandwritingModels
import app.bestnote.engine.recognition.HandwritingRecognizer
import app.bestnote.engine.recognition.PageSearch
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** [PageSearch] over the Room index. */
class RoomPageSearch @Inject constructor(private val index: PageSearchIndex) : PageSearch {
    override suspend fun search(query: String, limit: Int): List<PageSearchHit> = index.search(query, limit)
    override suspend fun highlights(notebookId: NotebookId, pageId: String, query: String): List<PageRect> =
        index.highlights(notebookId, pageId, query)
}

@Module
@InstallIn(SingletonComponent::class)
interface RecognitionModule {
    @Binds fun models(impl: MlKitModels): HandwritingModels
    @Binds fun recognizer(impl: MlKitHandwritingRecognizer): HandwritingRecognizer
    @Binds fun search(impl: RoomPageSearch): PageSearch
}
