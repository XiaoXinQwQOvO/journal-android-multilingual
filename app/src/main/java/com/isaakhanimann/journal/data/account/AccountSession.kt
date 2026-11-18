/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.account

data class AccountSession(
    val token: String,
    val userId: Long,
    val username: String,
    val avatarSha: String? = null
)