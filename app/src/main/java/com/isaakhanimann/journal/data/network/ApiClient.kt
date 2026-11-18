/*
 * This file is part of Journal Android Multilingual, licensed under the
 * GNU General Public License v3.0 or later. See COPYING for details.
 */

package com.isaakhanimann.journal.data.network

import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val code: Int, override val message: String) : Exception(message)

data class ApiRawResponse(val body: String, val headers: Headers, val code: Int)

@OptIn(ExperimentalSerializationApi::class)
@Singleton
class ApiClient @Inject constructor(private val userPreferences: UserPreferences) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    suspend fun getText(path: String, authenticated: Boolean = true): String =
        executeRequest(buildRequest("GET", path, authenticated))

    suspend fun getRaw(path: String, authenticated: Boolean = true): ApiRawResponse =
        withContext(Dispatchers.IO) {
            val request = buildRequest("GET", path, authenticated)
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        throw apiError(response.code, body, authenticated)
                    }
                    ApiRawResponse(body, response.headers, response.code)
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw ApiException(0, e.localizedMessage ?: "Network error")
            }
        }

    suspend fun getBytes(path: String, authenticated: Boolean = false): ByteArray =
        withContext(Dispatchers.IO) {
            val request = buildRequest("GET", path, authenticated)
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw apiError(response.code, response.body.string(), authenticated)
                    }
                    response.body.bytes()
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw ApiException(0, e.localizedMessage ?: "Network error")
            }
        }

    suspend fun sendJson(
        path: String,
        method: String,
        body: String,
        authenticated: Boolean = true
    ): String = executeRequest(
        buildRequest(method, path, authenticated, body.toRequestBody(jsonMediaType), jsonMediaType)
    )

    suspend fun uploadFile(
        path: String,
        method: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        authenticated: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                fieldName,
                fileName,
                bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            )
            .build()
        val request = buildRequest(method, path, authenticated, multipart, null)
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw apiError(response.code, body, authenticated)
                body
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(0, e.localizedMessage ?: "Network error")
        }
    }

    suspend fun downloadToFile(
        path: String,
        destination: File,
        authenticated: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val request = buildRequest("GET", path, authenticated)
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw apiError(response.code, response.body.string(), authenticated)
                }
                destination.parentFile?.mkdirs()
                response.body.byteStream().use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(0, e.localizedMessage ?: "Network error")
        }
    }

    private fun resolveUrl(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> ApiConfig.ORIGIN + path
        else -> ApiConfig.BASE_URL + path
    }

    private suspend fun buildRequest(
        method: String,
        path: String,
        authenticated: Boolean,
        body: okhttp3.RequestBody? = null,
        contentType: okhttp3.MediaType? = jsonMediaType
    ): Request {
        val builder = Request.Builder().url(resolveUrl(path))
        if (authenticated) {
            val token = userPreferences.authTokenFlow.firstOrNull()
                ?: throw ApiException(401, "Not logged in")
            builder.header("Authorization", "Bearer $token")
        }
        if (contentType != null && body != null) {
            builder.header("Content-Type", contentType.toString())
        }
        builder.method(method, body ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(null))
        return builder.build()
    }

    private suspend fun executeRequest(request: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw apiError(response.code, body, request.header("Authorization") != null)
                }
                body
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(0, e.localizedMessage ?: "Network error")
        }
    }

    private suspend fun apiError(code: Int, body: String, authenticated: Boolean): ApiException {
        if (code == 401 && authenticated) {
            userPreferences.clearSession()
        }
        val message = runCatching {
            val element = json.parseToJsonElement(body)
            ((element as? JsonObject)?.get("message") as? JsonPrimitive)?.content
        }.getOrNull()
        return ApiException(code, message ?: "HTTP $code")
    }
}