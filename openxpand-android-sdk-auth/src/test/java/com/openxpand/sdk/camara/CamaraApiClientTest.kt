package com.openxpand.sdk.camara

import com.openxpand.sdk.OpenXpandConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CamaraApiClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: CamaraApiClient

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val config = OpenXpandConfig(
            clientId = "test-client",
            tenant = "acme",
            redirectUri = "https://app.example.com/callback",
            baseCamaraApiUrl = mockServer.url("/").toString().trimEnd('/'),
            onSecurityProviderStatus = {}
        )
        client = CamaraApiClient(config)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    // ── checkSimSwap ──────────────────────────────────────────────

    @Test
    fun `checkSimSwap returns true when swapped`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"swapped":true}"""))

        assertTrue(client.checkSimSwap("token", "+541198765432"))
    }

    @Test
    fun `checkSimSwap returns false when not swapped`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"swapped":false}"""))

        assertFalse(client.checkSimSwap("token", "+541198765432"))
    }

    @Test
    fun `checkSimSwap sends Bearer token in Authorization header`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"swapped":false}"""))

        client.checkSimSwap("my-token", "+541198765432")

        assertEquals("Bearer my-token", mockServer.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `checkSimSwap sends phoneNumber in request body`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"swapped":false}"""))

        client.checkSimSwap("token", "+541198765432")

        assertTrue(mockServer.takeRequest().body.readUtf8().contains("+541198765432"))
    }

    @Test
    fun `checkSimSwap throws CamaraApiException on HTTP error`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        try {
            client.checkSimSwap("bad-token", "+541198765432")
            fail("Expected CamaraApiException")
        } catch (e: CamaraApiException) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    // ── verifyNumber ──────────────────────────────────────────────

    @Test
    fun `verifyNumber returns true when number matches`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"devicePhoneNumberVerified":true}"""))

        assertTrue(client.verifyNumber("token", "+541198765432"))
    }

    @Test
    fun `verifyNumber returns false when number does not match`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"devicePhoneNumberVerified":false}"""))

        assertFalse(client.verifyNumber("token", "+541198765432"))
    }

    @Test
    fun `verifyNumber sends Bearer token in Authorization header`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"devicePhoneNumberVerified":true}"""))

        client.verifyNumber("my-token", "+541198765432")

        assertEquals("Bearer my-token", mockServer.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `verifyNumber sends phoneNumber in request body`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"devicePhoneNumberVerified":true}"""))

        client.verifyNumber("token", "+541198765432")

        assertTrue(mockServer.takeRequest().body.readUtf8().contains("+541198765432"))
    }

    @Test
    fun `verifyNumber throws CamaraApiException on HTTP error`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        try {
            client.verifyNumber("token", "+541198765432")
            fail("Expected CamaraApiException")
        } catch (e: CamaraApiException) {
            assertTrue(e.message!!.contains("403"))
        }
    }
}
