/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.ui.tabs.settings

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isaakhanimann.journal.data.network.PackCatalogRepository
import com.isaakhanimann.journal.data.network.PackDto
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ExtensionStoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packCatalogRepository: PackCatalogRepository,
    userPreferences: UserPreferences
) : ViewModel() {

    val isLoggedIn = userPreferences.authTokenFlow
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val packs = MutableStateFlow<List<PackDto>>(emptyList())
    val installedVersionCodes = MutableStateFlow<Map<String, Int>>(emptyMap())
    val isLoading = MutableStateFlow(false)
    val downloadingRegisterName = MutableStateFlow<String?>(null)
    val snackbarHostState = SnackbarHostState()

    fun refresh() {
        if (!isLoggedIn.value) return
        if (isLoading.value) return
        viewModelScope.launch {
            isLoading.value = true
            try {
                packs.value = packCatalogRepository.listPacks()
                refreshInstalled()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    e.localizedMessage ?: "Failed to load extension packs"
                )
            } finally {
                isLoading.value = false
            }
        }
    }

    fun install(pack: PackDto) {
        if (downloadingRegisterName.value != null) return
        viewModelScope.launch {
            downloadingRegisterName.value = pack.registerName
            val zipFile = File(context.cacheDir, "store_${pack.registerName}.zip")
            try {
                packCatalogRepository.downloadPack(pack, zipFile)
                val message = withContext(Dispatchers.IO) {
                    ExtensionPackLoader.installDownloadedPack(
                        context,
                        zipFile,
                        pack.registerName
                    )
                }
                snackbarHostState.showSnackbar(message)
                refreshInstalled()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    e.localizedMessage ?: "Download failed"
                )
            } finally {
                zipFile.delete()
                downloadingRegisterName.value = null
            }
        }
    }

    private fun refreshInstalled() {
        installedVersionCodes.value = ExtensionPackLoader.getInstalledPacks(context)
            .associate { it.registerName to it.versionCode }
    }
}