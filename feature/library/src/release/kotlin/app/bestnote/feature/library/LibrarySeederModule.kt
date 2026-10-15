package app.bestnote.feature.library

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Release builds never seed demo content. */
@Module
@InstallIn(SingletonComponent::class)
object LibrarySeederModule {
    @Provides
    fun seeder(): LibrarySeeder = LibrarySeeder { }
}
