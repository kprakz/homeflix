package com.homeflix.app.util

import java.util.Locale

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "${Math.round(bytes / 1_000_000.0)} MB"
    bytes > 0 -> "${Math.round(bytes / 1_000.0)} KB"
    else -> ""
}

fun formatClock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

fun formatRuntime(seconds: Double): String {
    val minutes = (seconds / 60).toInt()
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
}

fun isNew(mtime: Double): Boolean = mtime > 0 && System.currentTimeMillis() - mtime < 7.0 * 24 * 60 * 60 * 1000
