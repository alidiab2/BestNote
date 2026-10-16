package app.bestnote.engine.recognition.mlkit

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.bestnote.core.database.NotebookRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface IndexEntryPoint {
    fun indexer(): PageIndexer
}

/** Runs [PageIndexer] in the background (REC-02). */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val pages = EntryPointAccessors.fromApplication(applicationContext, IndexEntryPoint::class.java).indexer().run()
        Log.i(MlKitModels.TAG, "Search index caught up: $pages page(s) read")
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(MlKitModels.TAG, "Search indexing failed (attempt $runAttemptCount)", e)
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * When to index (REC-02, battery-aware):
 *  - after edits: [EDIT_DELAY_S] seconds after the last save, unless the battery is low;
 *    each new save pushes it back, so a writing session is indexed once, when the pen rests;
 *  - catch-up: about twice a day, but only while charging and idle, which covers a large first-time backlog;
 *  - right after the models finish downloading, so old handwriting becomes searchable.
 */
@Singleton
class IndexScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notebooks: NotebookRepository,
    private val models: MlKitModels,
) {
    private val work get() = WorkManager.getInstance(context)

    /** Call once from the application. [scope] outlives screens. */
    fun start(scope: CoroutineScope) {
        scope.launch { notebooks.saved.collect { afterEdit() } }
        scope.launch { models.state.map { it.isReady }.distinctUntilChanged().filter { it }.collect { afterEdit(delaySeconds = 0) } }
        work.enqueueUniquePeriodicWork(
            CATCH_UP,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<IndexWorker>(CATCH_UP_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build())
                .build(),
        )
        afterEdit()
    }

    /** (Re)starts the quiet-period timer for an incremental run. */
    fun afterEdit(delaySeconds: Long = EDIT_DELAY_S) {
        work.enqueueUniqueWork(
            INCREMENTAL,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MIN, TimeUnit.MINUTES)
                .build(),
        )
    }

    private companion object {
        const val INCREMENTAL = "search-index-incremental"
        const val CATCH_UP = "search-index-catch-up"
        const val EDIT_DELAY_S = 45L
        const val CATCH_UP_HOURS = 12L
        const val BACKOFF_MIN = 10L
    }
}
