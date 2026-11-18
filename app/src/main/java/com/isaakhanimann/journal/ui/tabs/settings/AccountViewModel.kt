/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.ui.tabs.settings

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isaakhanimann.journal.data.account.AvatarSyncService
import com.isaakhanimann.journal.data.network.AccountRepository
import com.isaakhanimann.journal.data.network.ApiException
import com.isaakhanimann.journal.data.network.LoginResult
import com.isaakhanimann.journal.data.network.TotpSetupDto
import com.isaakhanimann.journal.data.network.UserDto
import com.isaakhanimann.journal.localization.I18n
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

@HiltViewModel
class AccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val avatarSyncService: AvatarSyncService,
    private val userPreferences: UserPreferences
) : ViewModel() {

    val accountSessionFlow = userPreferences.accountSessionFlow.stateIn(
        initialValue = null,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val snackbarHostState = SnackbarHostState()

    private val _isBusy = MutableStateFlow(false)
    val isBusyFlow = _isBusy

    private val _pendingTotpToken = MutableStateFlow<String?>(null)
    val pendingTotpTokenFlow = _pendingTotpToken

    private val _totpEnabled = MutableStateFlow(false)
    val totpEnabledFlow = _totpEnabled

    private val _totpSetup = MutableStateFlow<TotpSetupDto?>(null)
    val totpSetupFlow = _totpSetup

    fun register(username: String, password: String, confirmPassword: String) {
        val name = username.trim()
        when {
            name.isEmpty() || password.length < 8 -> {
                showMessage(I18n.translate(context, "account_error_password_short"))
                return
            }
            password != confirmPassword -> {
                showMessage(I18n.translate(context, "account_password_mismatch"))
                return
            }
        }
        busy {
            val user = accountRepository.register(name, password)
            onAuthenticated(user)
        }
    }

    fun login(username: String, password: String) {
        val name = username.trim()
        if (name.isEmpty() || password.isEmpty()) return
        busy {
            when (val result = accountRepository.login(name, password)) {
                is LoginResult.TotpRequired -> {
                    _pendingTotpToken.value = result.totpToken
                }
                is LoginResult.Success -> {
                    onAuthenticated(result.user)
                }
            }
        }
    }

    fun verifyTotp(code: String) {
        val pending = _pendingTotpToken.value ?: return
        busy {
            try {
                val user = accountRepository.verifyTotp(pending, code.trim())
                _pendingTotpToken.value = null
                onAuthenticated(user)
            } catch (e: ApiException) {
                if (e.code == 400 || e.code == 401) {
                    showMessage(I18n.translate(context, "account_2fa_invalid_code"))
                } else {
                    throw e
                }
            }
        }
    }

    fun cancelTotp() {
        _pendingTotpToken.value = null
    }

    fun refreshProfile() {
        viewModelScope.launch {
            runCatching { accountRepository.me() }
                .onSuccess { _totpEnabled.value = it.totpEnabled }
        }
    }

    fun startTotpSetup() {
        busy {
            _totpSetup.value = accountRepository.totpSetup()
        }
    }

    fun cancelTotpSetup() {
        _totpSetup.value = null
    }

    fun confirmTotpEnable(code: String) {
        busy {
            try {
                _totpEnabled.value = accountRepository.totpEnable(code.trim())
                _totpSetup.value = null
                showMessage(I18n.translate(context, "account_2fa_enabled"))
            } catch (e: ApiException) {
                if (e.code == 400 || e.code == 401) {
                    showMessage(I18n.translate(context, "account_2fa_invalid_code"))
                } else {
                    throw e
                }
            }
        }
    }

    fun disableTotp(code: String) {
        busy {
            try {
                _totpEnabled.value = accountRepository.totpDisable(code.trim())
                showMessage(I18n.translate(context, "account_2fa_disabled"))
            } catch (e: ApiException) {
                if (e.code == 400 || e.code == 401) {
                    showMessage(I18n.translate(context, "account_2fa_invalid_code"))
                } else {
                    throw e
                }
            }
        }
    }

    fun loginWithPasskey(activityContext: Context, username: String) {
        val name = username.trim()
        if (name.isEmpty()) return
        busy {
            val begin = accountRepository.passkeyLoginBegin(name)
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(
                    GetPublicKeyCredentialOption(publicKeyJson(begin.options))
                )
                .build()
            val credential = CredentialManager.create(activityContext)
                .getCredential(activityContext, request)
            val publicKey = credential.credential as? PublicKeyCredential ?: run {
                showMessage(I18n.translate(context, "account_passkey_unavailable"))
                return@busy
            }
            val user = accountRepository.passkeyLoginFinish(
                begin.challengeId,
                publicKey.authenticationResponseJson
            )
            onAuthenticated(user)
        }
    }

    fun addPasskey(activityContext: Context) {
        busy {
            val begin = accountRepository.passkeyRegisterBegin()
            val request = CreatePublicKeyCredentialRequest(publicKeyJson(begin.options))
            val credential = CredentialManager.create(activityContext)
                .createCredential(activityContext, request)
            val publicKey = credential as? CreatePublicKeyCredentialResponse ?: run {
                showMessage(I18n.translate(context, "account_passkey_unavailable"))
                return@busy
            }
            accountRepository.passkeyRegisterFinish(
                begin.challengeId,
                publicKey.registrationResponseJson
            )
            showMessage(I18n.translate(context, "account_passkey_created"))
        }
    }

    fun updateUsername(newUsername: String, oldUsername: String) {
        val name = newUsername.trim()
        if (name.isEmpty() || name == oldUsername) return
        busy {
            val user = accountRepository.updateUsername(name)
            val oldFile = AvatarUtil.getAvatarFile(context, oldUsername)
            if (oldFile.exists()) {
                oldFile.renameTo(AvatarUtil.getAvatarFile(context, user.username))
            }
            userPreferences.saveOwnerUserName(user.username)
            showMessage(I18n.translate(context, "account_username_changed"))
        }
    }

    fun uploadAvatar(activityContext: Context, uri: Uri) {
        busy {
            val session = userPreferences.accountSessionFlow.firstOrNull() ?: return@busy
            val bytes = withContext(Dispatchers.IO) {
                activityContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: return@busy
            withContext(Dispatchers.IO) {
                val target = AvatarUtil.getAvatarFile(context, session.username)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            avatarSyncService.uploadLocalAvatar(session.username)
            showMessage(I18n.translate(context, "account_avatar_updated"))
        }
    }

    fun logout() {
        viewModelScope.launch {
            accountRepository.logout()
            _totpEnabled.value = false
            _totpSetup.value = null
            _pendingTotpToken.value = null
        }
    }

    private fun busy(block: suspend () -> Unit) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                block()
            } catch (e: GetCredentialException) {
                showMessage(I18n.translate(context, "account_passkey_unavailable"))
            } catch (e: CreateCredentialException) {
                showMessage(I18n.translate(context, "account_passkey_unavailable"))
            } catch (e: Exception) {
                showMessage(messageFor(e))
            } finally {
                _isBusy.value = false
            }
        }
    }

    private suspend fun onAuthenticated(user: UserDto) {
        _totpEnabled.value = user.totpEnabled
        mirrorOwner(user)
        showMessage(
            I18n.translate(
                context,
                "account_logged_in_as",
                mapOf("username" to user.username)
            )
        )
    }

    private suspend fun mirrorOwner(user: UserDto) {
        userPreferences.saveOwnerUserName(user.username)
        val url = user.avatarUrl
        if (url == null) {
            // First login with a local avatar: publish it instead of dropping it.
            runCatching { avatarSyncService.uploadLocalAvatar(user.username) }
            return
        }
        val bytes = accountRepository.downloadAvatar(url) ?: return
        withContext(Dispatchers.IO) {
            val target = AvatarUtil.getAvatarFile(context, user.username)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
    }

    private fun showMessage(message: String) {
        viewModelScope.launch {
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }

    /**
 * webauthn-rs returns `{"publicKey": {...}}`, while Credential Manager expects
 * the inner PublicKeyCredentialCreationOptions / RequestOptions object.
 */
    private fun publicKeyJson(options: JsonObject): String =
        ((options["publicKey"] as? JsonObject) ?: options).toString()

    private fun messageFor(e: Exception): String {
        if (e is ApiException) {
            return when (e.code) {
                401 -> I18n.translate(context, "account_error_invalid_credentials")
                409 -> I18n.translate(context, "account_error_username_taken")
                0 -> I18n.translate(context, "account_error_network")
                else -> e.message
            }
        }
        return e.localizedMessage ?: I18n.translate(context, "account_error_generic")
    }
}