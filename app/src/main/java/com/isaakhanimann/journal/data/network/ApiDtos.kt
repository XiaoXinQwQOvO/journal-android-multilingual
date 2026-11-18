/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class UserDto(
    val id: Long,
    val username: String,
    val avatarUrl: String? = null,
    val avatarSha256: String? = null
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserDto
)

@Serializable
data class CredentialsRequest(
    val username: String,
    val password: String
)

@Serializable
data class UpdateUsernameRequest(
    val username: String
)

@Serializable
data class AvatarResponse(
    val avatarUrl: String? = null,
    val avatarSha256: String? = null
)

@Serializable
data class SnapshotResponse(
    val sha256: String? = null,
    val bytes: Long? = null,
    val updatedAt: String? = null
)

@Serializable
data class PackDto(
    val registerName: String,
    val title: String,
    val description: String,
    val officialLink: String? = null,
    val versionName: String,
    val versionCode: Int,
    val sha256: String,
    val sizeBytes: Long = 0,
    val downloadUrl: String,
    val iconUrl: String? = null
)

@Serializable
data class PasskeyBeginResponse(
    val challengeId: String,
    val options: JsonObject
)

@Serializable
data class PasskeyLoginRequest(
    val username: String
)

@Serializable
data class PasskeyFinishRequest(
    val challengeId: String,
    val credential: JsonObject
)

data class ServerJournal(
    val payload: String,
    val updatedAt: String?
)