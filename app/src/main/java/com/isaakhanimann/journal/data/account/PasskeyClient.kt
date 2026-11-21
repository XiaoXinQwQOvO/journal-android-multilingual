/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Outcome of a Credential Manager passkey call, mapped to something the UI can
 * explain. The old code collapsed every failure into "passkey is not available
 * on this device", which hid the real cause (usually a Digital Asset Links
 * mismatch on the server) and made the feature look unsupported.
 */
sealed interface PasskeyResult<out T> {
    data class Success<T>(val value: T) : PasskeyResult<T>

    /** The user dismissed the system credential picker. Not an error. */
    data object Cancelled : PasskeyResult<Nothing>

    /** No passkey exists in the credential provider for this RP. */
    data object NoCredential : PasskeyResult<Nothing>

    /**
     * No provider on the device can handle *creating* the credential. Google
     * Password Manager reports this when the device has no screen lock, no
     * signed-in Google account, or an outdated Play services build — states
     * where login (with its hybrid fallback) can still work.
     */
    data class NoProvider(val detail: String?) : PasskeyResult<Nothing>

    /**
     * The credential provider rejected the request itself, almost always because
     * `/.well-known/assetlinks.json` on the RP domain does not list this APK's
     * package name and signing-certificate SHA-256.
     */
    data class UnverifiedAppRequest(val detail: String?) : PasskeyResult<Nothing>

    /** Credential Manager (or its Play Services provider) is missing/too old. */
    data object NotSupported : PasskeyResult<Nothing>

    /** Anything else, including transport failures while talking to the server. */
    data class Failure(val detail: String?) : PasskeyResult<Nothing>
}

/**
 * Thin wrapper over `androidx.credentials.CredentialManager`.
 *
 * Two things here are load-bearing and were wrong before:
 *
 * 1. Every call needs an [Activity] context, not the application context. The
 *    bottom sheet is an Activity-scoped dialog; with an application context the
 *    call throws and the picker never appears.
 *
 * 2. `startRegistration`/`startAuthentication` take the *inner* options object
 *    (`PublicKeyCredentialCreationOptionsJSON` / `...RequestOptionsJSON`). The
 *    server already unwraps webauthn-rs' `{"publicKey": {...}}` envelope, but the
 *    unwrap is repeated here so an older server build still works.
 */
@Singleton
class PasskeyClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * True when the platform can host a Credential Manager UI at all.
     *
     * Passkeys need Android 9 (API 28). On API 26/27 the library still works for
     * passwords and federated credentials, but there is no passkey provider, so
     * the account screen hides the passkey actions instead of offering a button
     * that can only fail.
     */
    fun isPasskeySupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** Create (register) a passkey for the signed-in account. */
    suspend fun createPasskey(
        activity: Activity,
        optionsJson: String
    ): PasskeyResult<CreatePublicKeyCredentialResponse> {
        if (!isPasskeySupported()) return PasskeyResult.NotSupported
        val requestJson = unwrapPublicKey(optionsJson)
        return try {
            val request = CreatePublicKeyCredentialRequest(requestJson)
            val response = CredentialManager.create(activity)
                .createCredential(activity, request)
            val publicKey = response as? CreatePublicKeyCredentialResponse
                ?: return PasskeyResult.Failure("unexpected credential type")
            PasskeyResult.Success(publicKey)
        } catch (e: CreateCredentialException) {
            mapCreateFailure(e)
        }
    }

    /**
     * Sign in with an existing passkey.
     *
     * `preferImmediatelyAvailableCredentials` stays false: it makes Credential
     * Manager fail silently instead of showing the picker whenever no credential
     * is cached locally yet, which is exactly the state a user is in the first
     * time they tap the button on a new device.
     */
    suspend fun getPasskey(
        activity: Activity,
        optionsJson: String
    ): PasskeyResult<PublicKeyCredential> {
        if (!isPasskeySupported()) return PasskeyResult.NotSupported
        val requestJson = unwrapPublicKey(optionsJson)
        return try {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetPublicKeyCredentialOption(requestJson))
                .build()
            val response = CredentialManager.create(activity).getCredential(activity, request)
            val publicKey = response.credential as? PublicKeyCredential
                ?: return PasskeyResult.Failure("unexpected credential type")
            PasskeyResult.Success(publicKey)
        } catch (e: GetCredentialException) {
            mapGetFailure(e)
        }
    }

    /**
     * webauthn-rs serialises `{"publicKey": {...}}`; Credential Manager wants the
     * inner object. Tolerating both shapes keeps the client compatible with an
     * un-upgraded server.
     */
    private fun unwrapPublicKey(optionsJson: String): String = runCatching {
        val element = json.parseToJsonElement(optionsJson)
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return@runCatching optionsJson
        val inner = obj["publicKey"] as? kotlinx.serialization.json.JsonObject ?: return@runCatching optionsJson
        inner.toString()
    }.getOrDefault(optionsJson)

    private fun mapCreateFailure(e: CreateCredentialException): PasskeyResult<Nothing> = when (e) {
        is CreatePublicKeyCredentialDomException ->
            mapDomError(e.domError.type, e.errorMessage, forCreate = true)
        is CreateCredentialCancellationException -> PasskeyResult.Cancelled
        is CreateCredentialProviderConfigurationException -> PasskeyResult.NotSupported
        is CreateCredentialUnsupportedException -> PasskeyResult.NotSupported
        is CreateCredentialInterruptedException ->
            PasskeyResult.Failure(e.errorMessage?.toString())
        // Nothing on the device is willing to *create* the credential. Login can
        // still work here because the provider handles that with a different
        // module, so this must not be reported as "no passkey saved".
        is CreateCredentialNoCreateOptionException -> PasskeyResult.NoProvider(e.errorMessage?.toString())
        else -> PasskeyResult.Failure(e.errorMessage?.toString())
    }

    private fun mapGetFailure(e: GetCredentialException): PasskeyResult<Nothing> = when (e) {
        is GetPublicKeyCredentialDomException ->
            mapDomError(e.domError.type, e.errorMessage, forCreate = false)
        is GetCredentialCancellationException -> PasskeyResult.Cancelled
        is GetCredentialProviderConfigurationException -> PasskeyResult.NotSupported
        is GetCredentialUnsupportedException -> PasskeyResult.NotSupported
        is GetCredentialInterruptedException ->
            PasskeyResult.Failure(e.errorMessage?.toString())
        is NoCredentialException -> PasskeyResult.NoCredential
        else -> PasskeyResult.Failure(e.errorMessage?.toString())
    }

    /**
     * `domError.type` mirrors the WebAuthn DOMException names. `SecurityError` is
     * what the credential provider raises when it cannot tie the calling package
     * to the RP domain — i.e. the assetlinks.json check failed — so it is surfaced
     * separately from a generic failure.
     */
    private fun mapDomError(type: String?, message: CharSequence?, forCreate: Boolean): PasskeyResult<Nothing> =
        when (type) {
            "androidx.credentials.TYPE_SECURITY_ERROR",
            "androidx.credentials.TYPE_NOT_ALLOWED_ERROR" -> PasskeyResult.UnverifiedAppRequest(message?.toString())
            // On the create path "not found" means no provider could handle the
            // request at all; on the login path it is the ordinary "no passkey
            // saved for this RP yet" outcome.
            "androidx.credentials.TYPE_NOT_FOUND_ERROR" ->
                if (forCreate) PasskeyResult.NoProvider(message?.toString())
                else PasskeyResult.NoCredential
            "androidx.credentials.TYPE_ABORT_ERROR" -> PasskeyResult.Cancelled
            else -> PasskeyResult.Failure(message?.toString() ?: type)
        }
}

/**
 * Unwraps the Activity out of a Compose `LocalContext`. `LocalContext.current`
 * inside an Activity's content is the Activity itself, but inside a themed
 * wrapper or a dialog it can be a ContextWrapper, and Credential Manager must be
 * handed the actual Activity.
 */
fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return context as? Activity
}
