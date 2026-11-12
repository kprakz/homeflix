package io.github.kprakz.homeflix

import android.content.Context
import android.provider.MediaStore
import java.io.File

/** The phone's folders that hold videos, found through Android's media index. */
object Folders {
    data class Folder(val path: String, val name: String, val count: Int, val bytes: Long)

    /** Camera recordings are personal: not offered unless the person picks them. */
    fun isPersonal(path: String) = path.contains("/DCIM/", ignoreCase = true) || path.contains("/Screenshots", ignoreCase = true) || path.contains("/Screen record", ignoreCase = true)

    @Suppress("DEPRECATION") // DATA is still the file path, and Node reads files by path
    fun find(context: Context): List<Folder> {
        val counts = linkedMapOf<String, Pair<Int, Long>>()
        val projection = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.SIZE)
        context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { c ->
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (c.moveToNext()) {
                val path = c.getString(dataCol) ?: continue
                val dir = File(path).parent ?: continue
                val (n, b) = counts[dir] ?: (0 to 0L)
                counts[dir] = (n + 1) to (b + c.getLong(sizeCol))
            }
        }
        return counts.map { (dir, v) -> Folder(dir, File(dir).name, v.first, v.second) }.sortedByDescending { it.bytes }
    }
}
