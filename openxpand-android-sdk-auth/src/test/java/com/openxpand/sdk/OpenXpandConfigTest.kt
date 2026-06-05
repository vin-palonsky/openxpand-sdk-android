package com.openxpand.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenXpandConfigTest {

    private val config = OpenXpandConfig(
        clientId = "test-client",
        tenant = "acme",
        redirectUri = "https://app.example.com/callback",
        onSecurityProviderStatus = {}
    )

    @Test
    fun `authEndpoint uses fixed HTTPS base URL and tenant`() {
        assertEquals(
            "https://auth.openxpand.com/auth/realms/acme/protocol/openid-connect/auth",
            config.authEndpoint
        )
    }

    @Test
    fun `cellularAuthEndpoint uses fixed HTTP base URL and tenant`() {
        assertEquals(
            "http://opengw.openxpand.com/auth/realms/acme/protocol/openid-connect/auth",
            config.cellularAuthEndpoint
        )
    }

    @Test
    fun `tokenEndpoint uses default gateway URL and tenant`() {
        assertEquals(
            "https://opengw.openxpand.com/auth/realms/acme/protocol/openid-connect/token",
            config.tokenEndpoint
        )
    }

    @Test
    fun `tokenEndpoint strips trailing slash from baseGatewayUrl`() {
        val configWithSlash = config.copy(baseGatewayUrl = "https://opengw.openxpand.com/")
        assertEquals(
            "https://opengw.openxpand.com/auth/realms/acme/protocol/openid-connect/token",
            configWithSlash.tokenEndpoint
        )
    }

    @Test
    fun `tokenEndpoint uses custom gateway URL`() {
        val configCustomGw = config.copy(baseGatewayUrl = "https://my-gateway.example.com")
        assertEquals(
            "https://my-gateway.example.com/auth/realms/acme/protocol/openid-connect/token",
            configCustomGw.tokenEndpoint
        )
    }

    @Test
    fun `clientSecret defaults to empty string`() {
        assertEquals("", config.clientSecret)
    }

    @Test
    fun `simSwapEndpoint uses default CAMARA base URL`() {
        assertEquals(
            "https://api.openxpand.com/api/camara/sim-swap/v0/check",
            config.simSwapEndpoint
        )
    }

    @Test
    fun `numberVerificationEndpoint uses default CAMARA base URL`() {
        assertEquals(
            "https://api.openxpand.com/api/camara/number-verification/v0/verify",
            config.numberVerificationEndpoint
        )
    }

    @Test
    fun `simSwapEndpoint uses custom baseCamaraApiUrl`() {
        val custom = config.copy(baseCamaraApiUrl = "https://staging.api.openxpand.com")
        assertEquals(
            "https://staging.api.openxpand.com/api/camara/sim-swap/v0/check",
            custom.simSwapEndpoint
        )
    }

    @Test
    fun `numberVerificationEndpoint strips trailing slash from baseCamaraApiUrl`() {
        val custom = config.copy(baseCamaraApiUrl = "https://api.openxpand.com/")
        assertEquals(
            "https://api.openxpand.com/api/camara/number-verification/v0/verify",
            custom.numberVerificationEndpoint
        )
    }

    @Test
    fun `endpoints reflect tenant change`() {
        val other = config.copy(tenant = "telecom")
        assertEquals(
            "https://auth.openxpand.com/auth/realms/telecom/protocol/openid-connect/auth",
            other.authEndpoint
        )
        assertEquals(
            "https://opengw.openxpand.com/auth/realms/telecom/protocol/openid-connect/token",
            other.tokenEndpoint
        )
    }
}
