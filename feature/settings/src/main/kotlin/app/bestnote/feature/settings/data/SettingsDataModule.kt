package app.bestnote.feature.settings.data

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface SettingsDataModule {
    @Binds fun keyStore(impl: ProvidersKeyStore): SmartBoardKeyStore

    @Binds fun locale(impl: SystemAppLocaleController): AppLocaleController

    @Binds fun storage(impl: FileStorageInspector): StorageInspector

    @Binds fun debugLog(impl: AndroidDebugLogCollector): DebugLogCollector

    @Binds fun featureRequests(impl: AndroidFeatureRequestStore): FeatureRequestStore

    @Binds fun featureRequestSharer(impl: AndroidFeatureRequestStore): FeatureRequestSharer

    @Binds fun githubSettings(impl: KeystoreGitHubRequestSettings): GitHubRequestSettings

    @Binds fun attachmentReader(impl: AndroidAttachmentReader): AttachmentReader

    companion object {
        @Provides
        @Singleton
        fun requestSink(http: OkHttpClient, settings: GitHubRequestSettings): RequestSink = GitHubRequestSink(http, settings)
    }
}
