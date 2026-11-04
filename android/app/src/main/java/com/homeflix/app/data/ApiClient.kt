package com.homeflix.app.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class ApiException(
    val statusCode: Int,
    override val message: String,
    val waitSeconds: Int = 0,
    val pinRequired: Boolean = false,
) : Exception(message)

class InvalidAddressException(message: String) : Exception(message)

/**
 * Talks to `server.js` exactly as the web player does. API calls carry the device key as a header;
 * media URLs (video, HLS, subtitles, thumbnails) carry it as `?k=`, because the video pipeline and
 * Coil load those directly.
 */
class ApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun hello(server: Connection): Hello = get(server, "/api/hello")

    suspend fun auth(server: Connection): AuthState = get(server, "/api/auth")

    suspend fun unlock(server: Connection, pin: String): UnlockResponse {
        val body = json.encodeToString(UnlockRequest.serializer(), UnlockRequest(pin))
            .toRequestBody(JSON)
        return request(server, "/api/unlock", body, "POST")
    }

    suspend fun library(server: Connection): List<LibraryItem> = get(server, "/api/library")

    suspend fun info(server: Connection, id: String): InfoResponse = get(server, "/api/info/$id")

    suspend fun prepare(server: Connection, id: String, audio: Int): JobStatus =
        request(server, "/api/prepare/$id", EMPTY, "POST", mapOf("audio" to audio.toString()))

    suspend fun status(server: Connection, id: String, audio: Int): JobStatus =
        get(server, "/api/status/$id", mapOf("audio" to audio.toString()))

    fun thumbUrl(server: Connection, id: String, hero: Boolean = false): String =
        mediaUrl(server, "/thumb/$id", if (hero) mapOf("size" to "hero") else emptyMap())

    fun subtitleUrl(server: Connection, id: String, key: String): String =
        mediaUrl(server, "/subs/$id/$key.vtt")

    fun videoUrl(server: Connection, id: String, audio: Int): String =
        mediaUrl(server, "/video/$id", if (audio > 0) mapOf("audio" to audio.toString()) else emptyMap())

    fun hlsUrl(server: Connection, id: String, audio: Int): String =
        mediaUrl(server, "/hls/$id/$audio/index.m3u8")

    fun mediaUrl(server: Connection, path: String, params: Map<String, String> = emptyMap()): String {
        val builder = baseBuilder(server, path)
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        server.key?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("k", it) }
        return builder.build().toString()
    }

    private suspend inline fun <reified T> get(
        server: Connection,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        val url = buildUrl(server, path, params)
        val request = requestBuilder(server, url, "GET", null)
        parse(client.newCall(request).execute())
    }

    private suspend inline fun <reified T> request(
        server: Connection,
        path: String,
        body: RequestBody,
        method: String,
        params: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        val url = buildUrl(server, path, params)
        val request = requestBuilder(server, url, method, body)
        parse(client.newCall(request).execute())
    }

    private fun buildUrl(server: Connection, path: String, params: Map<String, String>): HttpUrl {
        val builder = baseBuilder(server, path)
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private fun baseBuilder(server: Connection, path: String): HttpUrl.Builder {
        val base = server.url.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw InvalidAddressException("That doesn't look like an address. Example: 192.168.1.20:8080")
        val builder = base.newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        return builder
    }

    private fun requestBuilder(server: Connection, url: HttpUrl, method: String, body: RequestBody?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "HOMEFLIX-Android")
        server.key?.takeIf { it.isNotBlank() }?.let { builder.header("X-Homeflix-Key", it) }
        when (method) {
            "POST" -> builder.post(body ?: EMPTY)
            "PUT" -> builder.put(body ?: EMPTY)
            else -> builder.get()
        }
        return builder.build()
    }

    private inline fun <reified T> parse(response: Response): T {
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw errorFor(it, text)
            if (T::class == Unit::class) @Suppress("UNCHECKED_CAST") return Unit as T
            return try {
                json.decodeFromString(text)
            } catch (err: Exception) {
                throw ApiException(it.code, "The computer sent something unexpected. Try updating HOMEFLIX.", 0, it.code == 401)
            }
        }
    }

    private fun errorFor(response: Response, body: String): ApiException {
        val parsed = runCatching { json.decodeFromString<ErrorBody>(body) }.getOrNull()
        val message = parsed?.error?.takeIf { it.isNotBlank() }
            ?: body.takeIf { it.isNotBlank() }?.take(300)
            ?: "The computer answered HTTP ${response.code}."
        return ApiException(
            statusCode = response.code,
            message = message,
            waitSeconds = parsed?.wait ?: 0,
            pinRequired = parsed?.pin == true || response.code == 401,
        )
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val EMPTY: RequestBody = ByteArray(0).toRequestBody(JSON)
    }
}
