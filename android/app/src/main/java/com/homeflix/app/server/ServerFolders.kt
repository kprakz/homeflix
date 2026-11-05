package com.homeflix.app.server

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The folders shared by the on-device server. Nothing is shared until the owner explicitly picks
 * folders: pre-selecting Movies/Download would expose files they never agreed to serve.
 */
object ServerFolders {
    const val MOVIES = "movies"
    const val SERIES = "series"

    data class SharedFolder(val path: String, val type: String = MOVIES) {
        val isSeries: Boolean get() = type == SERIES
    }

    private const val PREFS = "homeflix_server"
    private const val KEY = "folders"

    fun defaults(): List<SharedFolder> = emptyList()

    fun list(context: Context): List<SharedFolder> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return defaults()
        val raw = prefs.getString(KEY, null) ?: return defaults()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.opt(i)
                    val path: String?
                    val type: String
                    when (item) {
                        is JSONObject -> {
                            path = item.optString("path").trim()
                            type = normalizeType(item.optString("type"))
                        }
                        is String -> {
                            path = item.trim()
                            type = MOVIES
                        }
                        else -> {
                            path = null
                            type = MOVIES
                        }
                    }
                    if (!path.isNullOrEmpty() && File(path).isAbsolute) add(SharedFolder(File(path).absolutePath, type))
                }
            }.distinctBy { it.path.lowercase() }
        } catch (_: Exception) {
            defaults()
        }
    }

    fun add(context: Context, path: String, type: String = MOVIES): List<SharedFolder> {
        val clean = File(path).absolutePath
        val next = list(context).toMutableList()
        val index = next.indexOfFirst { it.path.equals(clean, ignoreCase = true) }
        val entry = SharedFolder(clean, normalizeType(type))
        if (index >= 0) next[index] = entry else next.add(entry)
        save(context, next)
        return next
    }

    fun setType(context: Context, path: String, type: String): List<SharedFolder> {
        val next = list(context).map {
            if (it.path.equals(path, ignoreCase = true)) it.copy(type = normalizeType(type)) else it
        }
        save(context, next)
        return next
    }

    fun remove(context: Context, path: String): List<SharedFolder> {
        val next = list(context).filterNot { it.path.equals(path, ignoreCase = true) }
        save(context, next)
        return next
    }

    fun reset(context: Context): List<SharedFolder> {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        return defaults()
    }

    private fun normalizeType(type: String?): String = if (type == SERIES) SERIES else MOVIES

    private fun save(context: Context, folders: List<SharedFolder>) {
        val array = JSONArray()
        folders.forEach {
            array.put(JSONObject().put("path", it.path).put("type", normalizeType(it.type)))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
