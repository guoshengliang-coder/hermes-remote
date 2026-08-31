package com.hermes.client.di

import com.hermes.client.data.network.RelayLifecycleEventsSource
import com.hermes.client.data.repository.LifecycleEventCursorStore
import com.hermes.client.data.repository.LifecycleEventRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LifecycleEventModule {
    @Provides
    @Singleton
    fun provideLifecycleEventRepository(
        source: RelayLifecycleEventsSource,
        cursor: LifecycleEventCursorStore,
    ): LifecycleEventRepository = LifecycleEventRepository(source, cursor)
}
