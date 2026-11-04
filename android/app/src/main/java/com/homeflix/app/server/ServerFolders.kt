package com.homeflix.app.server

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import java.io.File

/** The folders shared by the on-device server. Defaults can be replaced by the user. */
object ServerFolders {
    private const val PREFS = "homeflix_server"
    private const val KEY = "folders"

    fun defaults(context: Context): List<String> {
        val appMovies = context.getExternalFilesDir(null)?.let { File(it, "Movies") }
        return listOfNotNull(
            File(Environment.getExternalStorageDirectory(), "Movies"),
            File(Environment.getExternalStorageDirectory(), "Download"),
            appMovies,
        ).distinctBy { it.absolutePath.lowercase() }
            .map { it.absolutePath }
    }

    fun list(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return defaults(context)
        val raw = prefs.getString(KEY, null) ?: return defaults(context)
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val path = array.optString(i).trim()
                    if (path.isNotEmpty() && File(path).isAbsolute) add(File(path).absolutePath)
                }
            }.distinctBy { it.lowercase() }
        } catch (_: Exception) {
            defaults(context)
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
        return defaults(context)
    }

    private fun save(context: Context, folders: List<String>) {
        val array = JSONArray()
        folders.forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
