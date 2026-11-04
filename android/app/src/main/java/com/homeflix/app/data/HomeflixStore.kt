package com.homeflix.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.homeflixDataStore by preferencesDataStore(name = "homeflix")

/** Everything this device remembers: the chosen computer, its key, and per-movie playback state. */
class HomeflixStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val SERVER_KEY = stringPreferencesKey("server_key")
        val AUDIO_LANG = stringPreferencesKey("audio_lang")
        val TV_MODE = booleanPreferencesKey("tv_mode")
        val BOOST = booleanPreferencesKey("volume_boost")
    }

    private fun positionKey(id: String) = stringPreferencesKey("pos_$id")
    private fun audioKey(id: String) = intPreferencesKey("aud_$id")
    private fun hevcKey(tenBit: Boolean) = booleanPreferencesKey(if (tenBit) "hevc10" else "hevc8")

    val connection: Flow<Connection?> = context.homeflixDataStore.data
        .map { prefs -> prefs.toConnection() }
        .distinctUntilChanged()

    val resume: Flow<Map<String, Resume>> = context.homeflixDataStore.data
        .map { prefs -> prefs.toResumeMap() }
        .distinctUntilChanged()

    suspend fun connectionOnce(): Connection? = connection.first()

    suspend fun saveServer(url: String, name: String?, key: String?) {
        context.homeflixDataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = url
            if (name.isNullOrBlank()) prefs.remove(Keys.SERVER_NAME) else prefs[Keys.SERVER_NAME] = name
            if (key.isNullOrBlank()) prefs.remove(Keys.SERVER_KEY) else prefs[Keys.SERVER_KEY] = key
        }
    }

    suspend fun saveKey(key: String) {
        context.homeflixDataStore.edit { it[Keys.SERVER_KEY] = key }
    }

    /** Forget the computer, but keep watch positions and language choices for when it comes back. */
    suspend fun clearServer() {
        context.homeflixDataStore.edit { prefs ->
            prefs.remove(Keys.SERVER_URL)
            prefs.remove(Keys.SERVER_NAME)
            prefs.remove(Keys.SERVER_KEY)
        }
    }

    suspend fun resumeMap(): Map<String, Resume> = resume.first()

    suspend fun resume(id: String): Resume? {
        val prefs = context.homeflixDataStore.data.first()
        val value = prefs[positionKey(id)] ?: return null
        return runCatching { json.decodeFromString<Resume>(value) }.getOrNull()
    }

    suspend fun saveResume(id: String, resume: Resume) {
        context.homeflixDataStore.edit { prefs ->
            prefs[positionKey(id)] = json.encodeToString(Resume.serializer(), resume)
        }
    }

    suspend fun audioTrack(id: String): Int {
        val prefs = context.homeflixDataStore.data.first()
        return prefs[audioKey(id)] ?: 0
    }

    suspend fun saveAudio(id: String, track: Int, lang: String?) {
        context.homeflixDataStore.edit { prefs ->
            prefs[audioKey(id)] = track
            if (!lang.isNullOrBlank() && lang != "und") prefs[Keys.AUDIO_LANG] = lang
        }
    }

    suspend fun defaultAudioLang(): String? {
        val prefs = context.homeflixDataStore.data.first()
        return prefs[Keys.AUDIO_LANG]
    }

    suspend fun hevcBlocked(tenBit: Boolean): Boolean {
        val prefs = context.homeflixDataStore.data.first()
        return prefs[hevcKey(tenBit)] == true
    }

    suspend fun blockHevc(tenBit: Boolean) {
        context.homeflixDataStore.edit { it[hevcKey(tenBit)] = true }
    }

    suspend fun boostEnabled(): Boolean {
        val prefs = context.homeflixDataStore.data.first()
        return prefs[Keys.BOOST] == true
    }

    suspend fun setBoost(on: Boolean) {
        context.homeflixDataStore.edit { it[Keys.BOOST] = on }
    }

    suspend fun tvMode(): Boolean {
        val prefs = context.homeflixDataStore.data.first()
        return prefs[Keys.TV_MODE] == true
    }

    suspend fun setTvMode(tv: Boolean) {
        context.homeflixDataStore.edit { it[Keys.TV_MODE] = tv }
    }

    private fun Preferences.toResumeMap(): Map<String, Resume> = buildMap {
        for ((key, value) in asMap()) {
            val name = key.name
            if (!name.startsWith("pos_") || value !is String) continue
            val resume = runCatching { json.decodeFromString<Resume>(value) }.getOrNull() ?: continue
            put(name.removePrefix("pos_"), resume)
        }
    }

    private fun Preferences.toConnection(): Connection? {
        val url = this[Keys.SERVER_URL]?.takeIf { it.isNotBlank() } ?: return null
        return Connection(url = url, name = this[Keys.SERVER_NAME], key = this[Keys.SERVER_KEY])
    }
}
