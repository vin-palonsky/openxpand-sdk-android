package com.openxpand.sdk

/**
 * Fixed product URLs for GET /auth requests (IP+port HTTPS and cellular HTTP).
 * Not configurable by the integrating app; the gateway for POST /token is set via
 * [OpenXpandConfig.baseGatewayUrl].
 */
object OpenXpandDefaults {
    const val BASE_AUTH_HTTPS_URL = "https://auth.openxpand.com"
    const val BASE_CELLULAR_HTTP_URL = "http://opengw.openxpand.com"
    const val BASE_CAMARA_API_URL = "https://api.openxpand.com"
}

data class OpenXpandConfig(
    val clientId: String,
    val tenant: String,
    val redirectUri: String,
    /**
     * Optional. Required by Keycloak for confidential clients when exchanging the code at the token endpoint.
     * Leave empty for public clients (PKCE-only).
     */
    val clientSecret: String = "",
    /**
     * Gateway base URL (e.g. for token exchange). Authorization GET requests use
     * the fixed URLs in [OpenXpandDefaults] (auth HTTPS and cellular HTTP).
     */
    val baseGatewayUrl: String = "https://opengw.openxpand.com",
    /**
     * Base URL for CAMARA APIs (SIM swap, number verification).
     * Override for staging or testing environments.
     */
    val baseCamaraApiUrl: String = OpenXpandDefaults.BASE_CAMARA_API_URL,
    /**
     * Timeout in ms to wait for the cellular network to become available.
     * On some Android 10 devices (e.g. Samsung) the cellular radio can take
     * longer than 10 s to wake from idle when WiFi is active.
     * Increase to 20 000–30 000 on affected devices.
     */
    val cellularNetworkTimeoutMs: Int = 20_000,
    /**
     * Callback invoked when the security provider update check completes.
     *
     * Invoked with [SecurityProviderStatus.UpToDate], [SecurityProviderStatus.UserActionRequired],
     * [SecurityProviderStatus.NotAvailable], or [SecurityProviderStatus.Error].
     *
     * Use this to inform the user that the device's security provider is outdated or unavailable,
     * especially for [UserActionRequired] which requires user intervention.
     *
     * **Important:** This is called from a background thread. Update UI from the main thread.
     * Network operations are released only when provider update succeeds; on any non-success result,
     * SDK operations return typed errors and no network call is executed.
     */
    val onSecurityProviderStatus: (SecurityProviderStatus) -> Unit
) {
    val authEndpoint: String
        get() =
            "${OpenXpandDefaults.BASE_AUTH_HTTPS_URL}/auth/realms/$tenant/protocol/openid-connect/auth"

    val cellularAuthEndpoint: String
        get() =
            "${OpenXpandDefaults.BASE_CELLULAR_HTTP_URL}/auth/realms/$tenant/protocol/openid-connect/auth"

    val tokenEndpoint: String
        get() = "${baseGatewayUrl.trimEnd('/')}/auth/realms/$tenant/protocol/openid-connect/token"

    val simSwapEndpoint: String
        get() = "${baseCamaraApiUrl.trimEnd('/')}/api/camara/sim-swap/v0/check"

    val numberVerificationEndpoint: String
        get() = "${baseCamaraApiUrl.trimEnd('/')}/api/camara/number-verification/v0/verify"
}
