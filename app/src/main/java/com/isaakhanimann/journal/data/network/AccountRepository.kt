/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.network

import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonObject

sealed interface LoginResult {
    data class Success(val user: UserDto) : LoginResult
    data class TotpRequired(val totpToken: String) : LoginResult
}

@Singleton
class AccountRepository @Inject constructor(
    private val api: ApiClient,
    private val userPreferences: UserPreferences
) {
    suspend fun register(username: String, password: String): UserDto {
        val response = api.sendJson(
            path = "auth/register",
            method = "POST",
            body = api.json.encodeToString(
                CredentialsRequest.serializer(),
                CredentialsRequest(username, password)
            ),
            authenticated = false
        )
        return saveAuth(decodeAuth(response))
    }

    suspend fun login(username: String, password: String): LoginResult {
        val response = api.sendJson(
            path = "auth/login",
            method = "POST",
            body = api.json.encodeToString(
                CredentialsRequest.serializer(),
                CredentialsRequest(username, password)
            ),
            authenticated = false
        )
        val login = api.json.decodeFromString(LoginResponse.serializer(), response)
        val token = login.token
        val user = login.user
        if (login.requiresTotp) {
            val pending = login.totpToken
                ?: throw ApiException(0, "Server requested a two-factor code without a token")
            return LoginResult.TotpRequired(pending)
        }
        if (token == null || user == null) {
            throw ApiException(0, "Server returned an incomplete login response")
        }
        saveAuth(AuthResponse(token, user))
        return LoginResult.Success(user)
    }

    suspend fun verifyTotp(totpToken: String, code: String): UserDto {
        val response = api.sendJson(
            path = "auth/totp/verify",
            method = "POST",
            body = api.json.encodeToString(
                TotpVerifyRequest.serializer(),
                TotpVerifyRequest(totpToken, code)
            ),
            authenticated = false
        )
        return saveAuth(decodeAuth(response))
    }

    suspend fun totpSetup(): TotpSetupDto {
        val response = api.sendJson("auth/totp/setup", "POST", "{}")
        return api.json.decodeFromString(TotpSetupDto.serializer(), response)
    }

    suspend fun totpEnable(code: String): Boolean {
        val response = api.sendJson(
            path = "auth/totp/enable",
            method = "POST",
            body = api.json.encodeToString(TotpCodeRequest.serializer(), TotpCodeRequest(code))
        )
        return api.json.decodeFromString(TotpStateDto.serializer(), response).totpEnabled
    }

    suspend fun totpDisable(code: String): Boolean {
        val response = api.sendJson(
            path = "auth/totp/disable",
            method = "POST",
            body = api.json.encodeToString(TotpCodeRequest.serializer(), TotpCodeRequest(code))
        )
        return api.json.decodeFromString(TotpStateDto.serializer(), response).totpEnabled
    }

    suspend fun logout() = userPreferences.clearSession()

    suspend fun me(): UserDto {
        val user = decodeUser(api.getText("me"))
        updateSession(user)
        return user
    }

    suspend fun updateUsername(username: String): UserDto {
        val response = api.sendJson(
            path = "me",
            method = "PATCH",
            body = api.json.encodeToString(
                UpdateUsernameRequest.serializer(),
                UpdateUsernameRequest(username)
            )
        )
        val user = decodeUser(response)
        updateSession(user)
        return user
    }

    suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): AvatarResponse {
        val response = api.uploadFile(
            path = "me/avatar",
            method = "PUT",
            fieldName = "file",
            fileName = "avatar",
            mimeType = mimeType,
            bytes = bytes
        )
        val avatar = api.json.decodeFromString(AvatarResponse.serializer(), response)
        userPreferences.updateAccountAvatarSha(avatar.avatarSha256)
        return avatar
    }

    suspend fun downloadAvatar(url: String): ByteArray? = runCatching {
        api.getBytes(url, authenticated = false)
    }.getOrNull()

    suspend fun passkeyRegisterBegin(): PasskeyBeginResponse {
        val response = api.sendJson("auth/passkey/register/begin", "POST", "{}")
        return api.json.decodeFromString(PasskeyBeginResponse.serializer(), response)
    }

    suspend fun passkeyRegisterFinish(challengeId: String, credentialJson: String) {
        api.sendJson(
            path = "auth/passkey/register/finish",
            method = "POST",
            body = api.json.encodeToString(
                PasskeyFinishRequest.serializer(),
                PasskeyFinishRequest(challengeId, parseCredential(credentialJson))
            )
        )
    }

    suspend fun passkeyLoginBegin(username: String): PasskeyBeginResponse {
        val response = api.sendJson(
            path = "auth/passkey/login/begin",
            method = "POST",
            body = api.json.encodeToString(
                PasskeyLoginRequest.serializer(),
                PasskeyLoginRequest(username)
            ),
            authenticated = false
        )
        return api.json.decodeFromString(PasskeyBeginResponse.serializer(), response)
    }

    suspend fun passkeyLoginFinish(challengeId: String, credentialJson: String): UserDto {
        val response = api.sendJson(
            path = "auth/passkey/login/finish",
            method = "POST",
            body = api.json.encodeToString(
                PasskeyFinishRequest.serializer(),
                PasskeyFinishRequest(challengeId, parseCredential(credentialJson))
            ),
            authenticated = false
        )
        return saveAuth(decodeAuth(response))
    }

    private fun parseCredential(json: String): JsonObject =
        api.json.parseToJsonElement(json) as JsonObject

    private fun decodeAuth(json: String): AuthResponse =
        api.json.decodeFromString(AuthResponse.serializer(), json)

    private fun decodeUser(json: String): UserDto =
        api.json.decodeFromString(UserDto.serializer(), json)

    private suspend fun saveAuth(auth: AuthResponse): UserDto {
        userPreferences.saveSession(
            token = auth.token,
            userId = auth.user.id,
            username = auth.user.username,
            avatarSha = auth.user.avatarSha256
        )
        return auth.user
    }

    private suspend fun updateSession(user: UserDto) {
        val session = userPreferences.accountSessionFlow.firstOrNull() ?: return
        userPreferences.saveSession(
            token = session.token,
            userId = user.id,
            username = user.username,
            avatarSha = user.avatarSha256
        )
    }
}