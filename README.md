# openxpand-sdk-android

Collection of Android SDK modules for OpenXpand.

| Module | Description |
|--------|-------------|
| `openxpand-android-sdk-auth` | Mobile subscriber authentication via OAuth2 + PKCE (RFC 7636) |

---

## openxpand-android-sdk-auth

Android SDK for authenticating mobile subscribers via OAuth2 with PKCE (RFC 7636). It supports multiple subscriber identification methods and lets you choose whether the token exchange is performed from the app or from a backend.

## Requirements

- Android `minSdk 26` (Android 8.0+)
- Kotlin coroutines

## Installation

### 1. Add the JitPack repository

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Add the dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.openxpand:openxpand-sdk-android:VERSION")
}
```

Replace `VERSION` with the desired release tag (e.g. `v1.0.0`) or a commit hash.

## Configuration

Create an `OpenXpandConfig` instance with the OAuth2 client data:

```kotlin
import com.openxpand.sdk.OpenXpandConfig

val config = OpenXpandConfig(
    clientId = "my-client-id",
    tenant = "my-tenant",
    redirectUri = "https://my-app.com/callback",
    onSecurityProviderStatus = { status ->
        // Required: react to provider check result (UpToDate/UserActionRequired/NotAvailable/Error)
    }
)
```

> **Note:** The SDK does not handle `client_secret`. If the OAuth2 client is confidential,
> the secret must be handled exclusively by the backend that performs the token exchange.

### Optional parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `clientSecret` | `""` | Only if the client is confidential and the token exchange runs in code that can safely use the secret. |
| `baseGatewayUrl` | `https://opengw.openxpand.com` | Gateway base; used to document the **POST /token** path (`tokenEndpoint`). The **GET /auth** requests use fixed URLs defined in the SDK (`OpenXpandDefaults`). |

### Fixed SDK URLs (`OpenXpandDefaults`)

Not configurable from the app:

- **Auth HTTPS (IP + port):** `https://auth.openxpand.com`
- **Auth HTTP (cellular):** `http://opengw.openxpand.com`

### Derived endpoints

- **Auth endpoint (IP + port):** `{BASE_AUTH_HTTPS}/auth/realms/{tenant}/protocol/openid-connect/auth`
- **Cellular auth endpoint:** `{BASE_CELLULAR_HTTP}/auth/realms/{tenant}/protocol/openid-connect/auth`
- **Token endpoint (reference):** `{baseGatewayUrl}/auth/realms/{tenant}/protocol/openid-connect/token` — the code exchange is implemented by your app or backend.

## Initialization

```kotlin
import com.openxpand.sdk.OpenXpandAuth

val auth = OpenXpandAuth(
    context = applicationContext,
    config = config
)
```

**Important:** When `OpenXpandAuth` is instantiated, the SDK starts an **asynchronous background process** to check and update the Android security provider using Google Play services. This process is non-blocking and does not affect the constructor. However, **all subsequent network operations wait for this check to complete** before proceeding. See [Security Provider Updates](#security-provider-updates) for details on how to configure a callback to be notified if the check fails.

## Authentication methods

The SDK offers 2 methods to identify the subscriber:

### 1. IP + Port (HTTPS)

Sends an HTTPS request to the auth server. The server identifies the subscriber by the **source IP and port** assigned by the mobile network.

### 2. Cellular (HTTP)

**Forces the request through the cellular network** (even if WiFi is available). The subscriber is identified via the cellular network itself.

---

## Recommended usage: authorization in the SDK, token outside the SDK

The SDK **only** performs the **GET** to the authorization endpoint (IP + port, cellular, or both via `authorize()`). The **POST** to the `/token` endpoint must be implemented by your app or your backend: the SDK **does not** include code-for-token exchange for the standard OpenID flow.

### Step 1 — Obtain the authorization code (SDK)

The `authorize*` methods return the code and the PKCE `code_verifier`:

```kotlin
import com.openxpand.sdk.AuthorizationResult

// IP + Port
val authzResult = auth.authorizeViaIpPort()

// Cellular
val authzResult = auth.authorizeViaCellular()

// Try cellular first, fall back to IP + port on failure
val authzResult = auth.authorize()
```

```kotlin
when (authzResult) {
    is AuthorizationResult.Success -> {
        val code = authzResult.authorizationCode
        val codeVerifier = authzResult.codeVerifier
        // Send code + codeVerifier to the backend
    }
    is AuthorizationResult.Error -> {
        val message = authzResult.message
    }
}
```

### Step 2 — Exchange the code for tokens (your code)

From the app (OkHttp, Retrofit, etc.) or from a backend: POST to the token endpoint with `grant_type=authorization_code`, `code`, `client_id`, `redirect_uri`, `code_verifier`, and `client_secret` if applicable.

Example when the exchange is done by a **backend**:

The backend receives `code` and `code_verifier`, and issues the POST to the token endpoint:

```
POST {baseGatewayUrl}/auth/realms/{tenant}/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code={authorization_code}
&client_id={client_id}
&client_secret={client_secret}    ← only the backend knows it
&redirect_uri={redirect_uri}
&code_verifier={code_verifier}
```

### Alternative: full flow in the SDK (not recommended for production)

> **Warning:** The `authenticate*` methods perform the token exchange directly from the device.
> This is convenient for prototyping and testing, but **do not use in production** if your client is
> confidential: a `clientSecret` embedded in an APK can be extracted and abused. For production,
> keep Step 2 in a backend.

If you still choose to use the in-SDK exchange (e.g. public client with PKCE only):

```kotlin
import com.openxpand.sdk.AuthResult

// Try cellular first, fall back to IP + port
val result = auth.authenticate()

// Or pick a specific method:
// val result = auth.authenticateViaIpPort()
// val result = auth.authenticateViaCellular()

when (result) {
    is AuthResult.Success -> {
        val accessToken = result.accessToken
        // Use accessToken to call Number Verification or any protected API
    }
    is AuthResult.Error -> {
        val message = result.message
    }
}
```

---

### Step 3 — Verify the phone number (SIM Swap + Number Verification)

Once you have the `access_token`, call `verifyNumber()`. The SDK runs two CAMARA APIs in sequence:

1. **SIM Swap check** — detects whether the SIM was recently swapped (fraud signal).
   - `POST https://api.openxpand.com/api/camara/sim-swap/v0/check`
   - Response: `{ "swapped": true | false }`
2. **Number Verification** — confirms the phone number belongs to the authenticated subscriber. Only executed when `swapped = false`.
   - `POST https://api.openxpand.com/api/camara/number-verification/v0/verify`
   - Response: `{ "devicePhoneNumberVerified": true | false }`

```kotlin
import com.openxpand.sdk.NumberVerificationResult

when (val result = auth.verifyNumber(accessToken, phoneNumber = "+541198765432")) {
    is NumberVerificationResult.Success -> {
        if (result.verified) {
            // Phone number confirmed — authentication complete
        } else {
            // Number does not match — reject
        }
    }
    is NumberVerificationResult.SimSwapped -> {
        // SIM was recently swapped — treat as authentication failure
        // Do NOT proceed; possible SIM-swap fraud
    }
    is NumberVerificationResult.Error -> {
        val message = result.message
    }
}
```

> `verifyNumber()` accepts any `accessToken` — whether obtained via `authenticate()` (full SDK flow) or your own backend token exchange.

---

## API summary

### `OpenXpandAuth`

#### Authorization only (code + verifier — exchange done by caller)

| Method | Returns | Description |
|--------|---------|-------------|
| `authorize()` | `AuthorizationResult` | Tries cellular first; on failure falls back to IP + port. |
| `authorizeViaIpPort()` | `AuthorizationResult` | Identifies the subscriber by **source IP and port** (HTTPS). |
| `authorizeViaCellular()` | `AuthorizationResult` | Identifies the subscriber via the **cellular network**. Forces the request through cellular (HTTP), even if the device is on WiFi. |

#### Full flow (authorize + token exchange in the device)

> Not recommended for production with confidential clients. See [Alternative: full flow in the SDK](#alternative-full-flow-in-the-sdk-not-recommended-for-production).

| Method | Returns | Description |
|--------|---------|-------------|
| `authenticate()` | `AuthResult` | Tries cellular first; on failure falls back to IP + port. Returns access token. |
| `authenticateViaIpPort()` | `AuthResult` | Full flow via IP + port. |
| `authenticateViaCellular()` | `AuthResult` | Full flow via cellular network. |

### Result types

**`AuthorizationResult`** (authorization step only):
- `Success(authorizationCode: String, codeVerifier: String)`
- `Error(message: String, cause: Throwable?)`

**`AuthResult`** (full flow including token exchange):
- `Success(accessToken: String, tokenType: String, expiresIn: Long, refreshToken: String?)`
- `Error(message: String, cause: Throwable?)`

#### CAMARA verification

| Method | Returns | Description |
|--------|---------|-------------|
| `verifyNumber(accessToken, phoneNumber)` | `NumberVerificationResult` | Checks SIM swap first; if stable, verifies the phone number. |

**`NumberVerificationResult`**:
- `Success(verified: Boolean)` — `verified = true` means the number matches the subscriber
- `SimSwapped` — SIM was recently swapped; treat as authentication failure
- `Error(message: String, cause: Throwable?)`

---

## Permissions

The SDK declares the following permissions in its manifest (merged automatically):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```

- `INTERNET` — for HTTP/HTTPS requests.
- `ACCESS_NETWORK_STATE` — to detect the network type (WiFi/mobile).
- `CHANGE_NETWORK_STATE` — to force requests through the cellular network.

## Network security

The SDK includes a `network_security_config.xml` that allows cleartext (HTTP) traffic to `*.openxpand.com`, required by the cellular method. This configuration is merged automatically into the app manifest.

## Security Provider Updates

The Android security provider is patched periodically by Google to protect against known TLS/SSL vulnerabilities. The SDK automatically keeps the security provider up-to-date using Google Play services and ensures network operations do not proceed if the provider cannot be updated.

### Automatic behavior: Async initialization with gating

When an `OpenXpandAuth` instance is created, the SDK **immediately** initiates a non-blocking asynchronous check of the security provider status:

```kotlin
val auth = OpenXpandAuth(context = applicationContext, config = config)
// Security provider check started asynchronously in the background
// Network operations will wait for this check to complete before proceeding
```

**Important:** All network operations (`authorize()`, `authenticate()`, `verifyNumber()`, etc.) **wait for this check to complete** before making any network calls. This ensures the provider is updated before any secure communication.

### Gating policy: What happens when the check completes

| Provider Status | Behavior |
|-----------------|----------|
| ✅ **Up-to-date** | Callback invoked with `UpToDate`; network operations proceed normally |
| ⚠️ **UserActionRequired** | Callback invoked; network operations blocked with error result |
| ❌ **NotAvailable** | Callback invoked; network operations blocked with error result |
| ❌ **Error** | Callback invoked; network operations blocked with error result |

### Notification callback: Informing the host app about provider issues

Configure the required callback in `OpenXpandConfig` to be notified when the security provider check completes:

```kotlin
val config = OpenXpandConfig(
    clientId = "my-client-id",
    tenant = "my-tenant",
    redirectUri = "https://my-app.com/callback",
    // Required: notify the app when provider check finishes
    onSecurityProviderStatus = { status ->
        when (status) {
            is SecurityProviderStatus.UserActionRequired -> {
                // Google Play services needs user intervention
                // Use com.google.android.gms.common.GoogleApiAvailability.getErrorDialog()
                // to prompt the user to update/enable/install Google Play services
                Log.w("SecurityProvider", "User action required: status code ${status.statusCode}")
                // Show UI to user on main thread
            }
            is SecurityProviderStatus.NotAvailable -> {
                Log.e("SecurityProvider", "Google Play services not available on device")
                // Inform user that device may be vulnerable
            }
            is SecurityProviderStatus.Error -> {
                Log.e("SecurityProvider", "Provider update failed: ${status.message}")
                // Handle error gracefully
            }
            is SecurityProviderStatus.UpToDate -> {
                Log.i("SecurityProvider", "Provider is up-to-date")
            }
        }
    }
)

val auth = OpenXpandAuth(context = applicationContext, config = config)
```

**Note:** The callback is invoked from a **background thread**. Update UI from the main thread using `runOnUiThread()` or similar.

### Native Android resolution flow (recommended)

If you receive `SecurityProviderStatus.UserActionRequired`, use the standard Google Play services dialog so the user can update/enable/install what is needed.

```kotlin
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.common.GoogleApiAvailability
import com.openxpand.sdk.OpenXpandAuth
import com.openxpand.sdk.OpenXpandConfig
import com.openxpand.sdk.SecurityProviderStatus

class MainActivity : AppCompatActivity() {

    private var retryProviderInstall = false
    private lateinit var auth: OpenXpandAuth

    companion object {
        private const val ERROR_DIALOG_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = OpenXpandConfig(
            clientId = "my-client-id",
            tenant = "my-tenant",
            redirectUri = "https://my-app.com/callback",
            onSecurityProviderStatus = { status ->
                when (status) {
                    is SecurityProviderStatus.UserActionRequired -> {
                        runOnUiThread {
                            val availability = GoogleApiAvailability.getInstance()
                            if (availability.isUserResolvableError(status.statusCode)) {
                                availability.showErrorDialogFragment(
                                    this,
                                    status.statusCode,
                                    ERROR_DIALOG_REQUEST_CODE
                                ) {
                                    // User canceled dialog. Keep requests blocked.
                                }
                            }
                        }
                    }
                    is SecurityProviderStatus.NotAvailable -> {
                        // Non-recoverable on this device. Keep requests blocked and show your own UI.
                    }
                    is SecurityProviderStatus.Error -> {
                        // Unexpected provider-check error. Keep requests blocked and show your own UI.
                    }
                    is SecurityProviderStatus.UpToDate -> {
                        // Provider check succeeded.
                    }
                }
            }
        )

        auth = OpenXpandAuth(applicationContext, config)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ERROR_DIALOG_REQUEST_CODE) {
            // User returned from Play services resolution UI; retry provider installation path.
            retryProviderInstall = true
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (retryProviderInstall) {
            retryProviderInstall = false
            // Re-create auth to trigger provider check again, or expose your own re-init action.
            // auth = OpenXpandAuth(applicationContext, config)
        }
    }
}
```

This is the same native flow recommended by Android/Google for `ProviderInstaller`: show a resolvable error dialog when possible, then retry after the user returns.

### Network operation blocking behavior

```kotlin
// Example: Provider update fails
val auth = OpenXpandAuth(context, config)

// This call waits for the provider check, then blocks on non-success:
val result = auth.authorize()

when (result) {
    is AuthorizationResult.Success -> { /* ... */ }
    is AuthorizationResult.Error -> {
        // "Cannot proceed: Google Play services not available on device"
        // "Cannot proceed: security provider update failed (message)"
        // "Cannot proceed: user action required to update Google Play services (status: ...)"
    }
}
```

All public methods (`authorize()`, `authenticate()`, `authenticateViaIpPort()`, `authenticateViaCellular()`, `verifyNumber()`) follow this same gating pattern.

## Dependencies

| Library | Version | Usage |
|---------|---------|-------|
| OkHttp | 4.12.0 | HTTP client |
| Kotlin Coroutines Android | 1.7.3 | Async/suspend |
| Google Play services (base) | 18.3.0 | Security provider updates |
