package app.bestnote.feature.settings.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface SettingsDataModule {
    @Binds fun keyStore(impl: ProvidersKeyStore): SmartBoardKeyStore

    @Binds fun aiAccount(impl: AccountsGateway): AiAccountGateway

    @Binds fun locale(impl: SystemAppLocaleController): AppLocaleController

    @Binds fun storage(impl: FileStorageInspector): StorageInspector

    @Binds fun debugLog(impl: AndroidDebugLogCollector): DebugLogCollector
}
