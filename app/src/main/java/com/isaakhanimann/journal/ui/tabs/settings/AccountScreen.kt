/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.ui.tabs.settings

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.isaakhanimann.journal.localization.i18n
import kotlinx.coroutines.launch

private enum class AuthMode { LOGIN, REGISTER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    navigateBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val session = viewModel.accountSessionFlow.collectAsState(initial = null).value
    val isBusy = viewModel.isBusyFlow.collectAsState().value
    val pendingTotp = viewModel.pendingTotpTokenFlow.collectAsState().value
    val totpEnabled = viewModel.totpEnabledFlow.collectAsState().value
    val totpSetup = viewModel.totpSetupFlow.collectAsState().value
    val activityContext = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copiedMessage = i18n("copied_to_clipboard")
    var showDisableTotp by remember { mutableStateOf(false) }

    LaunchedEffect(session?.username) {
        if (session != null) viewModel.refreshProfile()
    }

    if (pendingTotp != null) {
        var code by remember(pendingTotp) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::cancelTotp,
            title = { Text(i18n("account_2fa_required")) },
            text = {
                Column {
                    Text(i18n("account_2fa_enter_code"))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text(i18n("account_2fa_code")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = code.length == 6 && !isBusy,
                    onClick = { viewModel.verifyTotp(code) }
                ) {
                    Text(i18n("account_2fa_verify_action"))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelTotp) {
                    Text(i18n("common_cancel"))
                }
            }
        )
    }

    if (totpSetup != null) {
        var code by remember(totpSetup) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::cancelTotpSetup,
            title = { Text(i18n("account_2fa_setup_title")) },
            text = {
                Column {
                    Text(i18n("account_2fa_setup_hint"))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = totpSetup.secret,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("", totpSetup.secret))
                            )
                            Toast.makeText(activityContext, copiedMessage, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }) {
                        Text(i18n("account_2fa_copy_secret"))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text(i18n("account_2fa_code")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = code.length == 6 && !isBusy,
                    onClick = { viewModel.confirmTotpEnable(code) }
                ) {
                    Text(i18n("account_2fa_enable_action"))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelTotpSetup) {
                    Text(i18n("common_cancel"))
                }
            }
        )
    }

    if (showDisableTotp) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDisableTotp = false },
            title = { Text(i18n("account_2fa_disable_title")) },
            text = {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text(i18n("account_2fa_code")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = code.length == 6 && !isBusy,
                    onClick = {
                        showDisableTotp = false
                        viewModel.disableTotp(code)
                    }
                ) {
                    Text(i18n("account_2fa_disable_action"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableTotp = false }) {
                    Text(i18n("common_cancel"))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(viewModel.snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(i18n("account_title")) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        if (session == null) {
            AuthContent(
                modifier = Modifier.fillMaxSize().padding(padding),
                isBusy = isBusy,
                onRegister = viewModel::register,
                onLogin = viewModel::login,
                onPasskeyLogin = { ctx, username -> viewModel.loginWithPasskey(ctx, username) }
            )
        } else {
            ProfileContent(
                modifier = Modifier.fillMaxSize().padding(padding),
                username = session.username,
                avatarSha = session.avatarSha,
                isBusy = isBusy,
                totpEnabled = totpEnabled,
                onChangeUsername = { viewModel.updateUsername(it, session.username) },
                onUploadAvatar = { uri -> viewModel.uploadAvatar(activityContext, uri) },
                onAddPasskey = { viewModel.addPasskey(it) },
                onStartTotpSetup = viewModel::startTotpSetup,
                onDisableTotp = { showDisableTotp = true },
                onLogout = viewModel::logout
            )
        }
    }
}

@Composable
private fun AuthContent(
    modifier: Modifier,
    isBusy: Boolean,
    onRegister: (String, String, String) -> Unit,
    onLogin: (String, String) -> Unit,
    onPasskeyLogin: (android.content.Context, String) -> Unit
) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(AuthMode.LOGIN) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == AuthMode.LOGIN,
                onClick = { mode = AuthMode.LOGIN },
                label = { Text(i18n("account_login")) }
            )
            FilterChip(
                selected = mode == AuthMode.REGISTER,
                onClick = { mode = AuthMode.REGISTER },
                label = { Text(i18n("account_register")) }
            )
        }
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(i18n("account_username")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(i18n("account_password")) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        if (mode == AuthMode.REGISTER) {
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text(i18n("account_password_confirm")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (isBusy) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        } else {
            Button(
                onClick = {
                    if (mode == AuthMode.REGISTER) {
                        onRegister(username, password, confirmPassword)
                    } else {
                        onLogin(username, password)
                    }
                },
                enabled = username.isNotBlank() && password.isNotBlank() &&
                    (mode == AuthMode.LOGIN || confirmPassword.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (mode == AuthMode.REGISTER) {
                        i18n("account_register_action")
                    } else {
                        i18n("account_login_action")
                    }
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            TextButton(
                onClick = { onPasskeyLogin(context, username) },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(i18n("account_passkey_sign_in"))
            }
        }
    }
}

@Composable
private fun ProfileContent(
    modifier: Modifier,
    username: String,
    avatarSha: String?,
    isBusy: Boolean,
    totpEnabled: Boolean,
    onChangeUsername: (String) -> Unit,
    onUploadAvatar: (android.net.Uri) -> Unit,
    onAddPasskey: (android.content.Context) -> Unit,
    onStartTotpSetup: () -> Unit,
    onDisableTotp: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var showEditDialog by remember { mutableStateOf(false) }
    var newName by remember(username) { mutableStateOf(username) }

    val pickAvatar = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onUploadAvatar(uri)
            refresh++
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(i18n("account_change_username")) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(i18n("account_username")) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.trim().isNotEmpty() && !isBusy,
                    onClick = {
                        showEditDialog = false
                        onChangeUsername(newName)
                    }
                ) {
                    Text(i18n("common_done"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text(i18n("common_cancel"))
                }
            }
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val file = remember(refresh, username, avatarSha) {
                        AvatarUtil.getUserAvatar(context, username)
                    }
                    if (file != null) {
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(username, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        newName = username
                        showEditDialog = true
                    }) {
                        Icon(Icons.Outlined.Edit, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(i18n("account_change_username"))
                    }
                    TextButton(onClick = {
                        pickAvatar.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(Icons.Outlined.Edit, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(i18n("account_avatar_change"))
                    }
                }
                if (isBusy) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(i18n("account_2fa"), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (totpEnabled) i18n("account_2fa_on") else i18n("account_2fa_off"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = if (totpEnabled) onDisableTotp else onStartTotpSetup,
                    enabled = !isBusy
                ) {
                    Text(
                        if (totpEnabled) i18n("account_2fa_disable_action")
                        else i18n("account_2fa_enable_action")
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { onAddPasskey(context) },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(i18n("account_passkey_create"))
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = onLogout,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            i18n("account_logout"),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        } else {
            ElevatedCard(Modifier.fillMaxWidth()) {
                TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(i18n("account_logout"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Text(
                i18n("account_passkey_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}