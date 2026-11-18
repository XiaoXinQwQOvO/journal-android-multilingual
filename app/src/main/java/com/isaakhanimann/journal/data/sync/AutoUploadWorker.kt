/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.isaakhanimann.journal.data.journal.JournalExportService
import com.isaakhanimann.journal.data.network.JournalSyncRepository
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

@HiltWorker
class AutoUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val userPreferences: UserPreferences,
    private val journalExportService: JournalExportService,
    private val journalSyncRepository: JournalSyncRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!userPreferences.isAutoUploadEnabledFlow.first()) return Result.success()
        if (userPreferences.authTokenFlow.firstOrNull() == null) return Result.success()
        return try {
            val journalExport = journalExportService.build()
            journalSyncRepository.upload(journalExportService.encode(journalExport))
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}