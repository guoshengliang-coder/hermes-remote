package com.hermes.client.data.network

import kotlinx.serialization.Serializable

@Serializable
data class AccountCapabilitiesDto(
    val version: Int = 0,
    val accountAuth: AccountAuthCapabilityDto = AccountAuthCapabilityDto(),
    val binding: AccountBindingCapabilityDto = AccountBindingCapabilityDto(),
    val legacy: AccountLegacyCapabilityDto = AccountLegacyCapabilityDto(),
)

@Serializable
data class AccountAuthCapabilityDto(
    val enabled: Boolean = false,
    val providers: List<String> = emptyList(),
    val android: Boolean = false,
    val accountDeletion: Boolean = false,
)

@Serializable
data class AccountBindingCapabilityDto(
    val enabled: Boolean = false,
    val maxActiveConnectorsPerAccount: Int = 1,
    val supportsDeviceSelection: Boolean = false,
    val supportsDeviceSharing: Boolean = false,
)

@Serializable
data class AccountLegacyCapabilityDto(
    val appTokenAccepted: Boolean = true,
)

@Serializable
data class EmailChallengeDto(
    val challengeId: String,
    val expiresAt: String,
    val resendAfter: String,
)

@Serializable
data class EmailChallengeResponseDto(val challenge: EmailChallengeDto)

@Serializable
data class AccountReauthenticationGrantDto(
    val grant: String,
    val scope: String,
    val expiresAt: String,
)

@Serializable
data class AccountDto(
    val id: String,
    val displayName: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class AccountInstallationDto(
    val id: String,
    val kind: String,
    val platform: String,
    val displayName: String,
)

@Serializable
data class AccountTokensDto(
    val accessToken: String,
    val accessExpiresAt: String,
    val refreshToken: String,
    val refreshExpiresAt: String,
)

@Serializable
data class AccountExchangeResponseDto(
    val account: AccountDto,
    val installation: AccountInstallationDto,
    val session: AccountTokensDto,
)

@Serializable
data class AccountRefreshResponseDto(val session: AccountTokensDto)

@Serializable
data class AccountConnectorHealthDto(
    val online: Boolean = false,
    val lastSeenAt: String? = null,
)

@Serializable
data class AccountHermesHealthDto(
    val reachable: Boolean? = null,
    val version: String? = null,
)

@Serializable
data class AccountGatewayHealthDto(val latencyMs: Long? = null)

@Serializable
data class AccountEndToEndHealthDto(
    val healthy: Boolean? = null,
    val checkedAt: String? = null,
)

@Serializable
data class AccountDeviceDto(
    val id: String,
    val generation: Int,
    val deviceId: String,
    val desktopDisplayName: String,
    val connector: AccountConnectorHealthDto = AccountConnectorHealthDto(),
    val hermes: AccountHermesHealthDto = AccountHermesHealthDto(),
    val gateway: AccountGatewayHealthDto = AccountGatewayHealthDto(),
    val endToEnd: AccountEndToEndHealthDto = AccountEndToEndHealthDto(),
    val access: String,
    val isDefault: Boolean = false,
)

@Serializable
data class AccountActiveBindingDto(
    val id: String,
    val generation: Int,
    val deviceId: String,
    val desktopDisplayName: String,
    val connector: AccountConnectorHealthDto = AccountConnectorHealthDto(),
    val hermes: AccountHermesHealthDto = AccountHermesHealthDto(),
    val gateway: AccountGatewayHealthDto = AccountGatewayHealthDto(),
    val endToEnd: AccountEndToEndHealthDto = AccountEndToEndHealthDto(),
)

@Serializable
data class AccountBindingSnapshotDto(
    val state: String,
    val binding: AccountActiveBindingDto? = null,
)

fun AccountActiveBindingDto.asOwnedDevice(): AccountDeviceDto = AccountDeviceDto(
    id = id,
    generation = generation,
    deviceId = deviceId,
    desktopDisplayName = desktopDisplayName,
    connector = connector,
    hermes = hermes,
    gateway = gateway,
    endToEnd = endToEnd,
    access = "owner",
    isDefault = true,
)

@Serializable
data class AccountDevicesResponseDto(
    val items: List<AccountDeviceDto> = emptyList(),
    val maxOwnedDevices: Int = 3,
)

@Serializable
data class AccountDeviceResponseDto(val device: AccountDeviceDto)

@Serializable
internal data class AccountErrorEnvelopeDto(val error: AccountErrorDto)

@Serializable
internal data class AccountErrorDto(
    val code: String = "HR-ACCOUNT-001",
    val message: String = "Account request failed.",
    val retryable: Boolean = false,
    val recoveryAction: String = "none",
    val correlationId: String? = null,
)
