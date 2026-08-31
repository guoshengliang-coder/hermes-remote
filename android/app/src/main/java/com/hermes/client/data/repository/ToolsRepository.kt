package com.hermes.client.data.repository

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.network.CronRunDto
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.MessagingPlatformDto
import com.hermes.client.data.network.SkillDto
import com.hermes.client.data.network.ToolsetDto

class ToolsRepository(private val rest: HermesRestApi) {
    suspend fun skills(profile: String? = null): List<SkillDto> = rest.skills(profile)
    suspend fun toggleSkill(name: String, enabled: Boolean, profile: String? = null) = rest.toggleSkill(name, enabled, profile)
    suspend fun toolsets(profile: String? = null): List<ToolsetDto> = rest.toolsets(profile)
    suspend fun cronJobs(profile: String? = null): List<CronJobDto> = rest.cronJobs(profile)
    suspend fun cronJob(jobId: String, profile: String? = null): CronJobDto = rest.cronJob(jobId, profile)
    suspend fun cronRuns(jobId: String, profile: String? = null): List<CronRunDto> =
        rest.cronRuns(jobId, profile)
    suspend fun createCron(prompt: String, schedule: String, name: String, profile: String? = null) =
        rest.createCron(prompt, schedule, name, profile)
    suspend fun updateCron(jobId: String, prompt: String, schedule: String, name: String, profile: String? = null) =
        rest.updateCron(jobId, prompt, schedule, name, profile)
    suspend fun pauseCron(jobId: String, profile: String? = null) = rest.pauseCron(jobId, profile)
    suspend fun resumeCron(jobId: String, profile: String? = null) = rest.resumeCron(jobId, profile)
    suspend fun triggerCron(jobId: String, profile: String? = null) = rest.triggerCron(jobId, profile)
    suspend fun deleteCron(jobId: String, profile: String? = null) = rest.deleteCron(jobId, profile)
    suspend fun messagingPlatforms(profile: String? = null): List<MessagingPlatformDto> = rest.messagingPlatforms(profile)
    suspend fun setMessagingEnabled(platformId: String, enabled: Boolean, profile: String? = null) =
        rest.setMessagingEnabled(platformId, enabled, profile)
    suspend fun configureMessaging(platformId: String, env: Map<String, String>, enabled: Boolean?, profile: String? = null) =
        rest.configureMessaging(platformId, env, enabled, profile)
}
