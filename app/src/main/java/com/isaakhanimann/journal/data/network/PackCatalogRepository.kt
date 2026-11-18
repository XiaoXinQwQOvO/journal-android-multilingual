/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.network

import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PackCatalogRepository @Inject constructor(private val api: ApiClient) {

    suspend fun listPacks(): List<PackDto> {
        val response = api.getText("packs")
        return api.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(PackDto.serializer()),
            response
        )
    }

    suspend fun downloadPack(pack: PackDto, destination: File) {
        api.downloadToFile(pack.downloadUrl, destination, authenticated = true)
        if (pack.sizeBytes > 0 && destination.length() != pack.sizeBytes) {
            destination.delete()
            throw ApiException(0, "Download size mismatch")
        }
        val actual = sha256(destination)
        if (!actual.equals(pack.sha256, ignoreCase = true)) {
            destination.delete()
            throw ApiException(0, "Download checksum mismatch")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}