package com.homeflix.app.data

import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class PinRequiredException : Exception("Enter the PIN set on the HOMEFLIX computer.")

class HomeflixRepository(
    private val api: ApiClient,
    private val store: HomeflixStore,
) {
    val connection: Flow<Connection?> = store.connection

    suspend fun connectionOnce(): Connection? = store.connectionOnce()

    suspend fun connect(rawAddress: String): Connection {
        val url = normalizeAddress(rawAddress)
        val previous = store.connectionOnce()
        val hello = api.hello(Connection(url))
        val keepKey = previous?.key?.takeIf { previous.url == url }
        store.saveServer(url, hello.name, keepKey)
        return Connection(url = url, name = hello.name, key = keepKey)
    }

    suspend fun unlock(pin: String): Connection {
        val connection = requireConnection()
        val result = api.unlock(connection, pin)
        val key = result.key?.takeIf { it.isNotBlank() }
            ?: throw ApiException(401, result.error ?: "The PIN was not accepted.")
        store.saveKey(key)
        return connection.copy(key = key)
    }

    suspend fun library(): List<LibraryItem> = withPinCheck { api.library(it) }

    suspend fun info(id: String): InfoResponse = withPinCheck { api.info(it, id) }

    suspend fun prepare(id: String, audio: Int): JobStatus = withPinCheck { api.prepare(it, id, audio) }

    suspend fun status(id: String, audio: Int): JobStatus = withPinCheck { api.status(it, id, audio) }

    suspend fun resume(id: String): Resume? = store.resume(id)

    suspend fun saveResume(id: String, resume: Resume) = store.saveResume(id, resume)

    suspend fun resumeMap(): Map<String, Resume> = store.resumeMap()

    suspend fun audioTrack(id: String): Int = store.audioTrack(id)

    suspend fun saveAudio(id: String, track: Int, lang: String?) = store.saveAudio(id, track, lang)

    suspend fun defaultAudioLang(): String? = store.defaultAudioLang()

    suspend fun hevcBlocked(tenBit: Boolean): Boolean = store.hevcBlocked(tenBit)

    suspend fun blockHevc(tenBit: Boolean) = store.blockHevc(tenBit)

    suspend fun boostEnabled(): Boolean = store.boostEnabled()

    suspend fun setBoost(on: Boolean) = store.setBoost(on)

    suspend fun disconnect() = store.clearServer()

    fun thumbUrl(connection: Connection, id: String, hero: Boolean = false): String =
        api.thumbUrl(connection, id, hero)

    fun subtitleUrl(connection: Connection, id: String, key: String): String =
        api.subtitleUrl(connection, id, key)

    fun videoUrl(connection: Connection, id: String, audio: Int): String =
        api.videoUrl(connection, id, audio)

    fun hlsUrl(connection: Connection, id: String, audio: Int): String =
        api.hlsUrl(connection, id, audio)

    private suspend fun requireConnection(): Connection =
        store.connectionOnce() ?: throw ApiException(0, "Choose a HOMEFLIX computer first.")

    private suspend fun <T> withPinCheck(block: suspend (Connection) -> T): T {
        val connection = requireConnection()
        return try {
            block(connection)
        } catch (err: ApiException) {
            if (err.pinRequired) throw PinRequiredException() else throw err
        }
    }

    private fun normalizeAddress(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw InvalidAddressException("Enter the address shown in HOMEFLIX.")
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val parsed = withScheme.toHttpUrlOrNull()
            ?: throw InvalidAddressException("That doesn't look like an address. Example: 192.168.1.20:8080")
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            throw InvalidAddressException("Use an http:// or https:// address.")
        }
        val normalized = parsed.newBuilder().apply {
            if (parsed.scheme == "http" && parsed.port == 80) port(8080)
        }.build()
        return normalized.toString().trimEnd('/')
    }
}
