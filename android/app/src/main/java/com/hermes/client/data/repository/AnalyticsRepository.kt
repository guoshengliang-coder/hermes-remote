package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.ModelUsageDto
import com.hermes.client.data.network.UsageDto

class AnalyticsRepository(private val rest: HermesRestApi) {
    suspend fun usage(profile: String? = null, days: Int = 30): UsageDto =
        rest.analyticsUsage(profile, days)
}
