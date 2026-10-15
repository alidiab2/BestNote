/**
 * :app — BestNote's entry point: the Hilt application graph, the single activity and the
 * navigation graph that stitches the feature modules together. Holds no feature logic.
 */
package app.bestnote

import android.app.Application
import android.util.Log
import app.bestnote.core.common.DefaultDispatcherProvider
import app.bestnote.core.common.DispatcherProvider
import app.bestnote.core.database.NotebookRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BestNoteApplication : Application() {
    @Inject lateinit var notebooks: NotebookRepository

    override fun onCreate() {
        super.onCreate()
        // The Room index is a cache of the notebook packages: refresh it in the background (ADR-0004).
        notebooks.backgroundScope.launch {
            runCatching { notebooks.reconcileIndex() }.onFailure { Log.w("BestNote", "Index reconcile failed", it) }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface AppModule {
    @Binds
    fun dispatchers(impl: DefaultDispatcherProvider): DispatcherProvider
}
