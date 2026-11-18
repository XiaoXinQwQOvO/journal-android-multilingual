/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.ui.tabs.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.isaakhanimann.journal.data.network.AccountRepository
import com.isaakhanimann.journal.data.network.ApiException
import com.isaakhanimann.journal.data.network.UserDto
import com.isaakhanimann.journal.localization.I18n
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class AccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
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
            mirrorOwner(user)
            showMessage(I18n.translate(context, "account_logged_in_as", mapOf("username" to user.username)))
        }
    }

    fun login(username: String, password: String) {
        val name = username.trim()
        if (name.isEmpty() || password.isEmpty()) return
        busy {
            val user = accountRepository.login(name, password)
            mirrorOwner(user)
            showMessage(I18n.translate(context, "account_logged_in_as", mapOf("username" to user.username)))
        }
    }

    fun loginWithPasskey(activityContext: Context, username: String) {
        val name = username.trim()
        if (name.isEmpty()) return
        busy {
            val begin = accountRepository.passkeyLoginBegin(name)
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetPublicKeyCredentialOption(begin.options.toString()))
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
            mirrorOwner(user)
            showMessage(I18n.translate(context, "account_logged_in_as", mapOf("username" to user.username)))
        }
    }

    fun addPasskey(activityContext: Context) {
        busy {
            val begin = accountRepository.passkeyRegisterBegin()
            val request = CreatePublicKeyCredentialRequest(begin.options.toString())
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
            // Phone photos routinely exceed the 2 MiB avatar limit, so downscale
            // and re-encode before uploading; the same bytes are kept locally.
            val compressed = withContext(Dispatchers.IO) { compressAvatar(bytes) }
            accountRepository.uploadAvatar(compressed, "image/webp")
            withContext(Dispatchers.IO) {
                val target = AvatarUtil.getAvatarFile(context, session.username)
                target.parentFile?.mkdirs()
                target.writeBytes(compressed)
            }
            showMessage(I18n.translate(context, "account_avatar_updated"))
        }
    }

    fun logout() {
        viewModelScope.launch {
            accountRepository.logout()
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

    private fun compressAvatar(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return bytes
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 1024 || bounds.outHeight / sampleSize > 1024) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return bytes
        val scale = minOf(1f, 512f / maxOf(decoded.width, decoded.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            decoded
        }
        val output = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= 30) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        scaled.compress(format, 80, output)
        return output.toByteArray()
    }

    private suspend fun mirrorOwner(user: UserDto) {
        userPreferences.saveOwnerUserName(user.username)
        val url = user.avatarUrl ?: return
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