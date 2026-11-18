/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.network

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JournalSyncRepository @Inject constructor(private val api: ApiClient) {

    suspend fun upload(jsonText: String): SnapshotResponse {
        val response = api.sendJson("journal", "PUT", jsonText)
        return api.json.decodeFromString(SnapshotResponse.serializer(), response)
    }

    suspend fun download(): ServerJournal? {
        return try {
            val response = api.getRaw("journal")
            ServerJournal(
                payload = response.body,
                updatedAt = response.headers["X-Updated-At"]
            )
        } catch (e: ApiException) {
            if (e.code == 404) null else throw e
        }
    }
}