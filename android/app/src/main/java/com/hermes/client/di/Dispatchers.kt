package com.hermes.client.di

import javax.inject.Qualifier

/** Dispatcher used for CPU-bound transcript normalization away from the UI thread. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
