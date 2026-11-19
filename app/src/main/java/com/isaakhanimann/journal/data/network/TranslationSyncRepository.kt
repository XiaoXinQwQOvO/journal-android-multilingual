/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.network

import android.content.Context
import com.isaakhanimann.journal.localization.I18n
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.json.JSONObject

/**
 * Pulls server-side translation overrides into `filesDir/server_lang`, where
 * I18n merges them over the bundled strings and extension packs.
 */
@Singleton
class TranslationSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiClient
) {
    private val mapSerializer = MapSerializer(
        String.serializer(),
        MapSerializer(String.serializer(), String.serializer())
    )

    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val text = api.getText("translations", authenticated = false)
            val translations = api.json.decodeFromString(mapSerializer, text)
            val directory = File(context.filesDir, DIRECTORY)
            directory.mkdirs()
            for ((lang, values) in translations) {
                if (lang !in SUPPORTED_LANGUAGES) continue
                val json = JSONObject()
                values.forEach { (key, value) -> json.put(key, value) }
                File(directory, "$lang.json").writeText(json.toString(), Charsets.UTF_8)
            }
            for (lang in SUPPORTED_LANGUAGES) {
                if (lang !in translations) {
                    File(directory, "$lang.json").delete()
                }
            }
            I18n.markDirty()
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val DIRECTORY = "server_lang"
        private val SUPPORTED_LANGUAGES = listOf("en_us", "zh_cn", "zh_tw")
    }
}