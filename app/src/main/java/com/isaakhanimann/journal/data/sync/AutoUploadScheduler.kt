/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.isaakhanimann.journal.data.room.experiences.JournalDataEvents
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

@Singleton
class AutoUploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences
) {
    @OptIn(FlowPreview::class)
    fun observe(scope: CoroutineScope) {
        scope.launch {
            // Coalesce bursts of edits (adding an ingestion touches several rows).
            JournalDataEvents.journalChangeSignal
                .debounce(15_000)
                .collect { scheduleIfEnabled() }
        }
    }

    suspend fun scheduleIfEnabled() {
        if (!userPreferences.isAutoUploadEnabledFlow.first()) return
        if (userPreferences.authTokenFlow.firstOrNull() == null) return
        enqueue()
    }

    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<AutoUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        private const val WORK_NAME = "auto_upload_journal"
    }
}