package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.ModelOptionDto
import com.hermes.client.data.network.ModelProviderDto

class ModelRepository(private val rest: HermesRestApi) {
    suspend fun options(profile: String? = null): List<ModelOptionDto> = rest.modelOptions(profile)
    suspend fun providers(profile: String? = null): List<ModelProviderDto> = rest.modelProviders(profile)
    suspend fun set(provider: String, model: String, profile: String? = null) = rest.setModel(provider, model, profile)
}
