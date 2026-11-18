/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.account

import android.content.Context
import com.isaakhanimann.journal.data.network.AccountRepository
import com.isaakhanimann.journal.ui.tabs.settings.AvatarUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Single upload path for both the owner card and the account screen. */
@Singleton
class AvatarSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository
) {
    suspend fun uploadLocalAvatar(username: String): Boolean {
        val file: File = AvatarUtil.getUserAvatar(context, username) ?: return false
        val compressed = withContext(Dispatchers.IO) {
            val bytes = file.readBytes()
            AvatarUtil.compressAvatar(bytes)
        }
        accountRepository.uploadAvatar(compressed, "image/webp")
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeBytes(compressed)
        }
        return true
    }
}