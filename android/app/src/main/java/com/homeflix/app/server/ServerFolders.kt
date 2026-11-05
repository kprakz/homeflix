package com.homeflix.app.server

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * The folders shared by the on-device server. Nothing is shared until the owner explicitly picks
 * folders: pre-selecting Movies/Download would expose files they never agreed to serve.
 */
object ServerFolders {
    private const val PREFS = "homeflix_server"
    private const val KEY = "folders"

    fun defaults(): List<String> = emptyList()

    fun list(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return defaults()
        val raw = prefs.getString(KEY, null) ?: return defaults()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val path = array.optString(i).trim()
                    if (path.isNotEmpty() && File(path).isAbsolute) add(File(path).absolutePath)
                }
            }.distinctBy { it.lowercase() }
        } catch (_: Exception) {
            defaults()
        }
    }

    fun add(context: Context, path: String): List<String> {
        val clean = File(path).absolutePath
        val next = (list(context) + clean).distinctBy { it.lowercase() }
        save(context, next)
        return next
    }

    fun remove(context: Context, path: String): List<String> {
        val next = list(context).filterNot { it.equals(path, ignoreCase = true) }
        save(context, next)
        return next
    }

    fun reset(context: Context): List<String> {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        return defaults()
    }

    private fun save(context: Context, folders: List<String>) {
        val array = JSONArray()
        folders.forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
