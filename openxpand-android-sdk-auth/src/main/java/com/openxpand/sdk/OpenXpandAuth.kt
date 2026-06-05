package com.openxpand.sdk

import android.content.Context
import com.openxpand.sdk.cellular.CellularRequestManager
import com.openxpand.sdk.pkce.PkceGenerator
import com.openxpand.sdk.internal.SdkHttpClient
import com.openxpand.sdk.internal.SecurityProviderUpdater
import com.openxpand.sdk.internal.ProviderUpdateResult
import com.openxpand.sdk.silent.SilentLoginManager
import com.openxpand.sdk.camara.CamaraApiClient
import com.openxpand.sdk.token.TokenExchanger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture

class OpenXpandAuth(
    private val context: Context,
    private val config: OpenXpandConfig
) {

    private val silentLoginManager = SilentLoginManager(config)
    private val cellularRequestManager = CellularRequestManager(context, config)
    private val tokenExchanger = TokenExchanger(config)
    private val camaraApiClient = CamaraApiClient(config)

    /**
     * Future that completes when the security provider update check finishes.
     * All network operations wait for this before proceeding.
     */
    private val providerCheckFuture = CompletableFuture<ProviderUpdateResult>()

    private suspend fun awaitProviderUpdateResult(): ProviderUpdateResult = withContext(Dispatchers.IO) {
        providerCheckFuture.get()
    }

    private sealed class ProviderGateDecision {
        data object Proceed : ProviderGateDecision()
        data class Blocked(val message: String) : ProviderGateDecision()
    }

    init {
        // Trigger an asynchronous update of the security provider.
        // This protects against known TLS/SSL vulnerabilities without blocking the caller.
        // All network operations wait for this check to complete before running.
        SecurityProviderUpdater.installIfNeededAsync(context.applicationContext) { result ->
            // Complete the future so network operations can proceed
            providerCheckFuture.complete(result)

            // Notify the host app with provider check result
            when (result) {
                is ProviderUpdateResult.Success -> {
                    config.onSecurityProviderStatus.invoke(SecurityProviderStatus.UpToDate)
                }
                is ProviderUpdateResult.UserActionRequired -> {
                    config.onSecurityProviderStatus.invoke(
                        SecurityProviderStatus.UserActionRequired(result.statusCode)
                    )
                }
                is ProviderUpdateResult.NotAvailable -> {
                    config.onSecurityProviderStatus.invoke(SecurityProviderStatus.NotAvailable)
                }
                is ProviderUpdateResult.Error -> {
                    config.onSecurityProviderStatus.invoke(SecurityProviderStatus.Error(result.message))
                }
            }
        }
    }

    private suspend fun evaluateProviderGate(): ProviderGateDecision {
        return try {
            val result = awaitProviderUpdateResult()
            when (result) {
                is ProviderUpdateResult.Success -> ProviderGateDecision.Proceed
                is ProviderUpdateResult.UserActionRequired ->
                    ProviderGateDecision.Blocked(
                        "Cannot proceed: user action required to update Google Play services (status: ${result.statusCode})"
                    )
                is ProviderUpdateResult.NotAvailable ->
                    ProviderGateDecision.Blocked("Cannot proceed: Google Play services not available on device")
                is ProviderUpdateResult.Error ->
                    ProviderGateDecision.Blocked("Cannot proceed: security provider update failed (${result.message})")
            }
        } catch (e: Exception) {
            ProviderGateDecision.Blocked("Security provider check failed: ${e.message}")
        }
    }

    private suspend fun <T> gateOrNull(errorFactory: (String) -> T): T? {
        return when (val decision = evaluateProviderGate()) {
            is ProviderGateDecision.Proceed -> null
            is ProviderGateDecision.Blocked -> errorFactory(decision.message)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Authorization-only methods (return code + verifier)
    //  The caller decides where to exchange: locally or via backend.
    // ──────────────────────────────────────────────────────────────

    /**
     * Obtains an authorization code via IP + source port identification (HTTPS).
     *
     * Returns [AuthorizationResult.Success] with the `authorizationCode` and
     * `codeVerifier` that must be sent to a token endpoint to get tokens.
     */
    suspend fun authorizeViaIpPort(): AuthorizationResult {
        // Gate: wait for security provider check
        gateOrNull { message -> AuthorizationResult.Error(message) }?.let { return it }

        return try {
            val pkce = PkceGenerator.generate()
            val code = silentLoginManager.authorize(pkce.codeChallenge)
            AuthorizationResult.Success(code, pkce.codeVerifier)
        } catch (e: Exception) {
            AuthorizationResult.Error("IP+Port authorization failed: ${e.message}", e)
        }
    }

    /**
     * Obtains an authorization code by identifying the subscriber via the cellular network.
     *
     * Forces the request through the cellular network (HTTP), even if the device is on WiFi.
     *
     * Returns [AuthorizationResult.Success] with the `authorizationCode` and
     * `codeVerifier` that must be sent to a token endpoint to get tokens.
     */
    suspend fun authorizeViaCellular(): AuthorizationResult {
        // Gate: wait for security provider check
        gateOrNull { message -> AuthorizationResult.Error(message) }?.let { return it }

        return try {
            val pkce = PkceGenerator.generate()
            val code = cellularRequestManager.authorize(pkce.codeChallenge)
            AuthorizationResult.Success(code, pkce.codeVerifier)
        } catch (e: Exception) {
            AuthorizationResult.Error("Cellular authorization failed: ${e.message}", e)
        }
    }

    /**
     * Tries cellular identification first; if it fails, falls back
     * to IP+port identification. Returns the first successful result
     * or the last error if both fail.
     */
    suspend fun authorize(): AuthorizationResult {
        // Gate: wait for security provider check (applies to all attempts)
        gateOrNull { message -> AuthorizationResult.Error(message) }?.let { return it }

        val cellularResult = authorizeViaCellular()
        if (cellularResult is AuthorizationResult.Success) return cellularResult

        val ipPortResult = authorizeViaIpPort()
        if (ipPortResult is AuthorizationResult.Success) return ipPortResult

        return AuthorizationResult.Error(
            "All authorization methods failed. " +
                "Cellular: ${(cellularResult as AuthorizationResult.Error).message}. " +
                "IP+Port: ${(ipPortResult as AuthorizationResult.Error).message}."
        )
    }

    // ──────────────────────────────────────────────────────────────
    //  Full-flow methods (authorize + token exchange in the device)
    //  Convenient for testing. In production, prefer doing the token
    //  exchange from a backend so client_secret never leaves the server.
    // ──────────────────────────────────────────────────────────────

    suspend fun authenticateViaIpPort(): AuthResult {
        // Gate: wait for security provider check
        gateOrNull { message -> AuthResult.Error(message) }?.let { return it }

        return when (val result = authorizeViaIpPort()) {
            is AuthorizationResult.Success ->
                tokenExchanger.exchange(result.authorizationCode, result.codeVerifier)
            is AuthorizationResult.Error ->
                AuthResult.Error(result.message, result.cause)
        }
    }

    suspend fun authenticateViaCellular(): AuthResult {
        // Gate: wait for security provider check
        gateOrNull { message -> AuthResult.Error(message) }?.let { return it }

        return when (val result = authorizeViaCellular()) {
            is AuthorizationResult.Success ->
                tokenExchanger.exchange(result.authorizationCode, result.codeVerifier)
            is AuthorizationResult.Error ->
                AuthResult.Error(result.message, result.cause)
        }
    }

    suspend fun authenticate(): AuthResult {
        // Gate: wait for security provider check
        gateOrNull { message -> AuthResult.Error(message) }?.let { return it }

        val cellularResult = authenticateViaCellular()
        if (cellularResult is AuthResult.Success) return cellularResult

        return authenticateViaIpPort()
    }

    // ──────────────────────────────────────────────────────────────
    //  CAMARA API — SIM Swap + Number Verification
    // ──────────────────────────────────────────────────────────────

    /**
     * Verifies that [phoneNumber] belongs to the authenticated subscriber.
     *
     * First checks for a recent SIM swap. If the SIM was swapped ([NumberVerificationResult.SimSwapped]),
     * the call is aborted to prevent SIM-swap fraud. Only when the SIM is stable does it proceed
     * to confirm the phone number via the Number Verification API.
     *
     * @param accessToken Bearer token obtained from [authenticate] or your own token exchange.
     * @param phoneNumber Phone number to verify, E.164 format recommended **without the
     *                    mobile `9` prefix for Argentine numbers** (e.g. `"+541198765432"`,
     *                    not `"+5491198765432"`). The CAMARA backend rejects the mobile-prefixed form.
     */
    suspend fun verifyNumber(accessToken: String, phoneNumber: String): NumberVerificationResult {
        // Gate: wait for security provider check
        gateOrNull { message -> NumberVerificationResult.Error(message) }?.let { return it }

        return try {
            val swapped = camaraApiClient.checkSimSwap(accessToken, phoneNumber)
            if (swapped) return NumberVerificationResult.SimSwapped

            val verified = camaraApiClient.verifyNumber(accessToken, phoneNumber)
            NumberVerificationResult.Success(verified)
        } catch (e: Exception) {
            NumberVerificationResult.Error("Number verification failed: ${e.message}", e)
        }
    }

    companion object {
        /**
         * Enables HTTP logging to Logcat (tag `OpenXpandHttp`) even when the AAR is not a debug build.
         * Must be called before creating the first [OpenXpandAuth] instance.
         */
        @JvmStatic
        fun setHttpLoggingEnabled(enabled: Boolean) {
            SdkHttpClient.forceHttpLogging = enabled
        }
    }
}
