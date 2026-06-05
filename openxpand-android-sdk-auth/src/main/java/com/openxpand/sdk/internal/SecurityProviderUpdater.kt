package com.openxpand.sdk.internal

import android.content.Context
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.security.ProviderInstaller
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages updates to Android's security provider using Google Play services.
 *
 * The Android security provider can have vulnerabilities over time. Google Play services
 * provides a mechanism ([ProviderInstaller]) to patch the security provider to protect
 * against known exploits.
 *
 * This utility offers both synchronous and asynchronous methods to update the provider,
 * guards against redundant concurrent installation attempts, and caches the result
 * for all subsequent calls.
 */
internal object SecurityProviderUpdater {

    /** Tracks whether an async install is currently in progress. */
    @Volatile
    private var isAsyncInstallInProgress = false

    /** Cached result from the most recent install attempt (if any). */
    @Volatile
    private var cachedResult: ProviderUpdateResult? = null

    /** Queue of callbacks waiting for an in-progress async install to complete. */
    private val pendingCallbacks = CopyOnWriteArrayList<(ProviderUpdateResult) -> Unit>()

    /**
     * Synchronously updates the security provider if needed.
     * This is appropriate if the caller can tolerate blocking (e.g., in a background sync adapter).
     *
     * **Note:** This blocks the calling thread. Call from a background thread or coroutine.
     *
     * @param context Android application context (used only to check Google Play services availability).
     * @return [ProviderUpdateResult] indicating success, user action required, or unavailability.
     */
    fun installIfNeeded(context: Context): ProviderUpdateResult {
        return try {
            ProviderInstaller.installIfNeeded(context)
            ProviderUpdateResult.Success
        } catch (e: com.google.android.gms.common.GooglePlayServicesRepairableException) {
            // Google Play services is out of date, disabled, or missing.
            val statusCode = e.connectionStatusCode
            HttpLog.println("SecurityProviderUpdater: Repairable error. Status code: $statusCode")
            ProviderUpdateResult.UserActionRequired(statusCode)
        } catch (e: com.google.android.gms.common.GooglePlayServicesNotAvailableException) {
            // Non-recoverable; the device does not have a suitable version of Google Play services.
            HttpLog.println("SecurityProviderUpdater: Google Play services not available")
            ProviderUpdateResult.NotAvailable
        } catch (e: Exception) {
            // Unexpected error.
            HttpLog.println("SecurityProviderUpdater: Unexpected error: ${e.message}")
            ProviderUpdateResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Asynchronously updates the security provider if needed.
     * Non-blocking; the callback is invoked when the operation completes.
     *
     * If a result is already cached from a previous update, the callback is invoked immediately.
     * If an async install is currently in progress, the callback is queued and invoked
     * when the update completes.
     *
     * @param context Android application context.
     * @param callback Invoked with the result when available (cached or newly completed).
     */
    fun installIfNeededAsync(
        context: Context,
        callback: (ProviderUpdateResult) -> Unit
    ) {
        // If we have a cached result, invoke the callback immediately
        cachedResult?.let {
            callback(it)
            return
        }

        // If an async install is already in progress, queue the callback
        if (isAsyncInstallInProgress) {
            pendingCallbacks.add(callback)
            return
        }

        // Start a new async install
        isAsyncInstallInProgress = true
        pendingCallbacks.add(callback)

        try {
            ProviderInstaller.installIfNeededAsync(
                context,
                object : ProviderInstaller.ProviderInstallListener {
                    override fun onProviderInstalled() {
                        val result = ProviderUpdateResult.Success
                        completeAsyncInstall(result)
                    }

                    override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: android.content.Intent?) {
                        val message = "Provider installation failed. Error code: $errorCode"
                        HttpLog.println("SecurityProviderUpdater: $message")
                        val result = ProviderUpdateResult.UserActionRequired(errorCode)
                        completeAsyncInstall(result)
                    }
                }
            )
        } catch (e: Exception) {
            val message = e.message ?: "Unknown error"
            HttpLog.println("SecurityProviderUpdater: Async install error: $message")
            val result = ProviderUpdateResult.Error(message)
            completeAsyncInstall(result)
        }
    }

    /**
     * Called when an async install attempt completes (success or failure).
     * Caches the result and invokes all pending callbacks.
     */
    private fun completeAsyncInstall(result: ProviderUpdateResult) {
        isAsyncInstallInProgress = false
        cachedResult = result

        when (result) {
            is ProviderUpdateResult.Success ->
                HttpLog.println("SecurityProviderUpdater: Provider installed/updated successfully")
            is ProviderUpdateResult.UserActionRequired ->
                HttpLog.println("SecurityProviderUpdater: User action required (status: ${result.statusCode})")
            is ProviderUpdateResult.NotAvailable ->
                HttpLog.println("SecurityProviderUpdater: Google Play services not available")
            is ProviderUpdateResult.Error ->
                HttpLog.println("SecurityProviderUpdater: Error: ${result.message}")
        }

        // Invoke all pending callbacks
        val callbacks = pendingCallbacks.toList()
        pendingCallbacks.clear()
        callbacks.forEach { it(result) }
    }
}

/**
 * Result of a security provider update attempt.
 */
sealed class ProviderUpdateResult {
    /** Provider is up-to-date or was successfully updated. */
    data object Success : ProviderUpdateResult()

    /**
     * Google Play services needs user intervention (e.g., update, enable, or install).
     * The host app should use [GoogleApiAvailability.getErrorDialog] or similar
     * to prompt the user to resolve the issue.
     *
     * @param statusCode Error code indicating the type of user action needed.
     */
    data class UserActionRequired(val statusCode: Int) : ProviderUpdateResult()

    /**
     * Google Play services is not available on this device.
     * No recovery is possible; the provider cannot be updated.
     */
    data object NotAvailable : ProviderUpdateResult()

    /**
     * An unexpected error occurred during the update attempt.
     * @param message Error message describing the issue.
     */
    data class Error(val message: String) : ProviderUpdateResult()
}

