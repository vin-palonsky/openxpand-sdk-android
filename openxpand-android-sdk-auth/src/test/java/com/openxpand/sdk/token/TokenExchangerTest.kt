package com.openxpand.sdk.token

import com.openxpand.sdk.AuthResult
import com.openxpand.sdk.OpenXpandConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TokenExchangerTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var exchanger: TokenExchanger

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val config = OpenXpandConfig(
            clientId = "test-client",
            tenant = "acme",
            redirectUri = "https://app.example.com/callback",
            baseGatewayUrl = mockServer.url("/").toString().trimEnd('/'),
            onSecurityProviderStatus = {}
        )
        exchanger = TokenExchanger(config)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `exchange returns Success with access token`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"access_token":"tok123","token_type":"Bearer","expires_in":3600}"""))

        val result = exchanger.exchange("auth-code", "verifier")

        assertTrue(result is AuthResult.Success)
        with(result as AuthResult.Success) {
            assertEquals("tok123", accessToken)
            assertEquals("Bearer", tokenType)
            assertEquals(3600L, expiresIn)
            assertNull(refreshToken)
        }
    }

    @Test
    fun `exchange parses refresh_token when present`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"access_token":"tok","token_type":"Bearer","expires_in":3600,"refresh_token":"refresh123"}"""))

        val result = exchanger.exchange("code", "verifier") as AuthResult.Success
        assertEquals("refresh123", result.refreshToken)
    }

    @Test
    fun `exchange sends required POST params`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"access_token":"tok","token_type":"Bearer","expires_in":3600}"""))

        exchanger.exchange("my-code", "my-verifier")

        val body = mockServer.takeRequest().body.readUtf8()
        assertTrue(body.contains("grant_type=authorization_code"))
        assertTrue(body.contains("code=my-code"))
        assertTrue(body.contains("code_verifier=my-verifier"))
        assertTrue(body.contains("client_id=test-client"))
        assertTrue(body.contains("redirect_uri="))
    }

    @Test
    fun `exchange includes client_secret for confidential clients`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"access_token":"tok","token_type":"Bearer","expires_in":900}"""))

        val config = OpenXpandConfig(
            clientId = "test-client",
            tenant = "acme",
            redirectUri = "https://app.example.com/callback",
            clientSecret = "my-secret",
            baseGatewayUrl = mockServer.url("/").toString().trimEnd('/'),
            onSecurityProviderStatus = {}
        )
        TokenExchanger(config).exchange("code", "verifier")

        val body = mockServer.takeRequest().body.readUtf8()
        assertTrue(body.contains("client_secret=my-secret"))
    }

    @Test
    fun `exchange omits client_secret for public clients`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"access_token":"tok","token_type":"Bearer","expires_in":3600}"""))

        exchanger.exchange("code", "verifier")

        val body = mockServer.takeRequest().body.readUtf8()
        assertFalse(body.contains("client_secret"))
    }

    @Test
    fun `exchange returns Error on HTTP 400`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(400)
            .setBody("""{"error":"invalid_grant"}"""))

        val result = exchanger.exchange("bad-code", "verifier")

        assertTrue(result is AuthResult.Error)
        assertTrue((result as AuthResult.Error).message.contains("400"))
    }

    @Test
    fun `exchange returns Error when access_token missing in response`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"token_type":"Bearer"}"""))

        val result = exchanger.exchange("code", "verifier")

        assertTrue(result is AuthResult.Error)
    }
}
