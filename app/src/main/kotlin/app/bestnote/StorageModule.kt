package app.bestnote

import android.content.Context
import app.bestnote.core.storage.FileNotebookStore
import app.bestnote.core.storage.NotebookStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The app's single [NotebookStore]. Temporary home: DOC-03 provides the same binding from
 * `:core:database` (`StorageModule`), and whichever lands second deletes this file.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppStorageModule {
    @Provides
    @Singleton
    fun notebookStore(@ApplicationContext context: Context): NotebookStore = FileNotebookStore(File(context.filesDir, "notebooks"))
}
