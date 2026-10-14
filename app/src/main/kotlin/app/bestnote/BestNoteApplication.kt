/**
 * :app — BestNote's entry point: the Hilt application graph, the single activity and the
 * navigation graph that stitches the feature modules together. Holds no feature logic.
 */
package app.bestnote

import android.app.Application
import app.bestnote.core.common.DefaultDispatcherProvider
import app.bestnote.core.common.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@HiltAndroidApp
class BestNoteApplication : Application()

@Module
@InstallIn(SingletonComponent::class)
interface AppModule {
    @Binds
    fun dispatchers(impl: DefaultDispatcherProvider): DispatcherProvider
}
