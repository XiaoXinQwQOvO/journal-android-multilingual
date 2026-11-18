/*
 * Copyright (c) 2022-2023. Isaak Hanimann.
 * This file is part of PsychonautWiki Journal.
 *
 * PsychonautWiki Journal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * PsychonautWiki Journal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PsychonautWiki Journal.  If not, see https://www.gnu.org/licenses/gpl-3.0.en.html.
 */

package com.isaakhanimann.journal.ui.tabs.settings

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isaakhanimann.journal.data.account.AvatarSyncService
import com.isaakhanimann.journal.data.journal.JournalExportService
import com.isaakhanimann.journal.data.network.JournalSyncRepository
import com.isaakhanimann.journal.data.room.experiences.ExperienceRepository
import com.isaakhanimann.journal.data.sync.AutoUploadScheduler
import com.isaakhanimann.journal.localization.I18n
import com.isaakhanimann.journal.ui.notifications.Notifications
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import com.isaakhanimann.journal.ui.utils.DateLocaleOption
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(

    @ApplicationContext private val context: Context,
    private val experienceRepository: ExperienceRepository,
    private val fileSystemConnection: FileSystemConnection,
    private val userPreferences: UserPreferences,
    private val journalExportService: JournalExportService,
    private val journalSyncRepository: JournalSyncRepository,
    private val autoUploadScheduler: AutoUploadScheduler,
    private val avatarSyncService: AvatarSyncService
) : ViewModel() {

    fun saveDosageDotsAreHidden(value: Boolean) = viewModelScope.launch {
        userPreferences.saveDosageDotsAreHidden(value)
    }

    fun saveAreSubstanceHeightsIndependent(value: Boolean) = viewModelScope.launch {
        userPreferences.saveAreSubstanceHeightsIndependent(value)
    }

    fun saveIsTimelineHidden(value: Boolean) = viewModelScope.launch {
        userPreferences.saveIsTimelineHidden(value)
    }

    val isTimelineHiddenFlow = userPreferences.isTimelineHiddenFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    fun saveIsBottomBarPinned(value: Boolean) = viewModelScope.launch {
        userPreferences.saveIsBottomBarPinned(value)
    }

    val isBottomBarPinnedFlow = userPreferences.isBottomBarPinnedFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val areSubstanceHeightsIndependentFlow = userPreferences.areSubstanceHeightsIndependentFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val areDosageDotsHiddenFlow = userPreferences.areDosageDotsHiddenFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    fun saveUse24HourClock(value: Boolean) = viewModelScope.launch {
        userPreferences.saveUse24HourClock(value)
    }

    val use24HourClockFlow = userPreferences.use24HourClockFlow.stateIn(
        initialValue = null,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    fun saveOpenLinkInBrowser(value: Boolean) {
        viewModelScope.launch {
            userPreferences.saveOpenLinkInBrowser(value)
        }
    }

    val isAppLockEnabledFlow = userPreferences.isAppLockEnabledFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    fun saveAppLockEnabled(value: Boolean) {
        viewModelScope.launch {
            userPreferences.saveAppLockEnabled(value)
        }
    }

    val isEffectNotificationEnabledFlow = userPreferences.isEffectNotificationEnabledFlow.stateIn(
        initialValue = true,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    fun saveEffectNotificationEnabled(value: Boolean) {
        viewModelScope.launch {
            userPreferences.saveEffectNotificationEnabled(value)
            if (!value) {
                // Disabling the toggle also dismisses any effect notification
                // that is still showing.
                Notifications.cancelAllEffectNotifications(context)
            }
        }
    }

    val isOpenLinkInBrowserFlow = userPreferences.isOpenLinkInBrowserFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val isMidnightCutoffEnabledFlow = userPreferences.isMidnightCutoffEnabledFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    fun saveMidnightCutoffEnabled(value: Boolean) {
        viewModelScope.launch {
            userPreferences.saveMidnightCutoffEnabled(value)
        }
    }

    val isStatsByIngestionTimeFlow = userPreferences.isStatsByIngestionTimeFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    fun saveStatsByIngestionTime(value: Boolean) {
        viewModelScope.launch {
            userPreferences.saveStatsByIngestionTime(value)
        }
    }

    val selectedLanguageFlow = userPreferences.selectedLanguageFlow.stateIn(
        initialValue = null,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val dateLocaleOptionFlow = userPreferences.dateLocaleOptionFlow.stateIn(
        initialValue = DateLocaleOption.FOLLOW_LANGUAGE,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    fun saveDateLocaleOption(value: DateLocaleOption) {
        viewModelScope.launch {
            userPreferences.saveDateLocaleOption(value)
        }
    }

    val achievementsFlow = userPreferences.achievementsFlow.stateIn(
        initialValue = emptyList(),
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val ownerUserNameFlow = userPreferences.ownerUserNameFlow.stateIn(
        initialValue = "You",
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val snackbarHostState = SnackbarHostState()

    fun saveSelectedLanguage(languageKey: String?) {
        viewModelScope.launch {
            userPreferences.saveSelectedLanguage(languageKey)
        }
    }

    fun addAchievement(achievement: String) {
        viewModelScope.launch {
            userPreferences.addAchievement(achievement)
        }
    }

    fun saveOwnerUserName(userName: String?) {
        viewModelScope.launch {
            userPreferences.saveOwnerUserName(userName)
        }
    }

    /** Returns true when the picked file starts with the encrypted-export magic (reads only the prefix). */
    fun isImportEncrypted(uri: Uri): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            val prefix = ByteArray(5)
            var read = 0
            while (read < prefix.size) {
                val n = input.read(prefix, read, prefix.size - read)
                if (n == -1) break
                read += n
            }
            input.close()
            ExportEncryption.isEncryptedExport(prefix)
        } catch (e: Exception) {
            false
        }
    }

    fun importFile(uri: Uri, password: String? = null) {
        viewModelScope.launch {
            val bytes = fileSystemConnection.getBytesFromUri(uri)
            if (bytes == null) {
                snackbarHostState.showSnackbar(
                    message = "File not found",
                    duration = SnackbarDuration.Short
                )
            } else {
                try {
                    val text = if (ExportEncryption.isEncryptedExport(bytes)) {
                        if (password == null) {
                            snackbarHostState.showSnackbar(
                                message = "This backup is encrypted",
                                duration = SnackbarDuration.Short
                            )
                            return@launch
                        }
                        try {
                            ExportEncryption.decryptExport(bytes, password)
                        } catch (_: javax.crypto.AEADBadTagException) {
                            snackbarHostState.showSnackbar(
                                message = "Wrong password",
                                duration = SnackbarDuration.Short
                            )
                            return@launch
                        }
                    } else {
                        bytes.toString(Charsets.UTF_8)
                    }
                    journalExportService.applyText(text)
                    snackbarHostState.showSnackbar(
                        message = "Import successful",
                        duration = SnackbarDuration.Short
                    )
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(
                        message = "Decoding file failed",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    fun exportFile(uri: Uri, password: String? = null) {
        viewModelScope.launch {
            val journalExport = journalExportService.build()
            try {
                val jsonList = journalExportService.encode(journalExport)
                if (password == null) {
                    fileSystemConnection.saveTextInUri(uri, text = jsonList)
                } else {
                    val encrypted = ExportEncryption.encryptExport(jsonList, password)
                    fileSystemConnection.saveBytesInUri(uri, encrypted)
                }
                snackbarHostState.showSnackbar(
                    message = "Export successful",
                    duration = SnackbarDuration.Short
                )
            } catch (_: Exception) {
                snackbarHostState.showSnackbar(
                    message = "Export failed",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    val accountSessionFlow = userPreferences.accountSessionFlow.stateIn(
        initialValue = null,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val isAutoUploadEnabledFlow = userPreferences.isAutoUploadEnabledFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    fun setAutoUploadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && userPreferences.authTokenFlow.firstOrNull() == null) {
                snackbarHostState.showSnackbar(
                    message = I18n.translate(context, "sync_not_logged_in"),
                    duration = SnackbarDuration.Short
                )
                return@launch
            }
            userPreferences.saveAutoUploadEnabled(enabled)
            if (enabled) {
                autoUploadScheduler.scheduleIfEnabled()
            } else {
                autoUploadScheduler.cancel()
            }
        }
    }

    fun uploadOwnerAvatar() {
        viewModelScope.launch {
            if (userPreferences.authTokenFlow.firstOrNull() == null) return@launch
            val username = userPreferences.ownerUserNameFlow.firstOrNull() ?: "You"
            runCatching { avatarSyncService.uploadLocalAvatar(username) }
        }
    }

    fun uploadJournal() {
        viewModelScope.launch {
            if (userPreferences.authTokenFlow.firstOrNull() == null) {
                snackbarHostState.showSnackbar(
                    message = I18n.translate(context, "sync_not_logged_in"),
                    duration = SnackbarDuration.Short
                )
                return@launch
            }
            try {
                val journalExport = journalExportService.build()
                journalSyncRepository.upload(journalExportService.encode(journalExport))
                snackbarHostState.showSnackbar(
                    message = I18n.translate(context, "sync_upload_success"),
                    duration = SnackbarDuration.Short
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    message = e.localizedMessage
                        ?: I18n.translate(context, "sync_failed"),
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    fun downloadJournal() {
        viewModelScope.launch {
            if (userPreferences.authTokenFlow.firstOrNull() == null) {
                snackbarHostState.showSnackbar(
                    message = I18n.translate(context, "sync_not_logged_in"),
                    duration = SnackbarDuration.Short
                )
                return@launch
            }
            try {
                val snapshot = journalSyncRepository.download()
                if (snapshot == null) {
                    snackbarHostState.showSnackbar(
                        message = I18n.translate(context, "sync_no_server_data"),
                        duration = SnackbarDuration.Short
                    )
                    return@launch
                }
                journalExportService.applyText(snapshot.payload)
                snackbarHostState.showSnackbar(
                    message = I18n.translate(context, "sync_download_success"),
                    duration = SnackbarDuration.Short
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    message = e.localizedMessage
                        ?: I18n.translate(context, "sync_failed"),
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    fun deleteEverything() {
        viewModelScope.launch {
            experienceRepository.deleteEverything()
        }
    }
}
