package com.openxpand.sdk

/**
 * Represents the result of an asynchronous security provider update check.
 *
 * This is reported to the host app via the optional callback in [OpenXpandConfig].
 * It indicates whether the device's security provider is up-to-date or needs user intervention.
 */
sealed class SecurityProviderStatus {
    /**
     * Security provider is up-to-date; no action required.
     */
    data object UpToDate : SecurityProviderStatus()

    /**
     * Google Play services needs user intervention (e.g., update, enable, or install).
     *
     * The host app should handle this by prompting the user. Typically, use
     * `com.google.android.gms.common.GoogleApiAvailability.getErrorDialog()` to show
     * a system dialog, or implement custom UI.
     *
     * **Important:** The SDK blocks network operations while this status is active.
     * Prompt the user to resolve Play services and retry the operation afterwards.
     *
     * @param statusCode Error code from Google Play services indicating the type of user action needed.
     */
    data class UserActionRequired(val statusCode: Int) : SecurityProviderStatus()

    /**
     * Google Play services is not available on this device.
     *
     * The device does not have a suitable version of Google Play services,
     * so the security provider cannot be updated.
     * The SDK blocks network operations and returns typed errors.
     */
    data object NotAvailable : SecurityProviderStatus()

    /**
     * An unexpected error occurred during the security provider update check.
     *
     * @param message Error message describing the issue.
     */
    data class Error(val message: String) : SecurityProviderStatus()
}
