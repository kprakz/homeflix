package com.homeflix.app.data

import kotlinx.serialization.Serializable

/** A computer running HOMEFLIX that this device is talking to. */
data class Connection(
    val url: String,
    val name: String? = null,
    val key: String? = null,
)

/** A computer found by mDNS, before the user chooses it. */
data class DiscoveredServer(
    val name: String,
    val url: String,
    val version: String? = null,
)

@Serializable
data class LibraryItem(
    val id: String,
    val title: String = "",
    val year: String? = null,
    val size: Long = 0,
    val mtime: Long = 0,
    val folder: String? = null,
    val downloading: Boolean = false,
    val show: String? = null,
    val season: Int? = null,
    val ep: Int? = null,
    val state: String = "idle",
    val progress: Double = 0.0,
    val error: String? = null,
) {
    val isSeries: Boolean get() = !show.isNullOrBlank()
    val epLabel: String get() = if (isSeries && season != null && ep != null) "S$season:E$ep" else ""
}

@Serializable
data class AudioTrack(
    val n: Int = 0,
    val lang: String = "und",
    val label: String = "Audio",
)

@Serializable
data class SubtitleTrack(
    val key: String,
    val lang: String = "und",
    val label: String = "Subtitles",
)

@Serializable
data class InfoResponse(
    val id: String,
    val title: String = "",
    val year: String? = null,
    val size: Long = 0,
    val mtime: Long = 0,
    val folder: String? = null,
    val downloading: Boolean = false,
    val show: String? = null,
    val season: Int? = null,
    val ep: Int? = null,
    val duration: Double = 0.0,
    val mode: String = "direct",
    val videoCodec: String? = null,
    val tenBit: Boolean = false,
    val height: Int = 0,
    val audios: List<AudioTrack> = emptyList(),
    val subs: List<SubtitleTrack> = emptyList(),
    val state: String = "idle",
    val progress: Double = 0.0,
    val error: String? = null,
) {
    val isSeries: Boolean get() = !show.isNullOrBlank()
    val epLabel: String get() = if (isSeries && season != null && ep != null) "S$season:E$ep" else ""
}

@Serializable
data class JobStatus(
    val state: String = "idle",
    val progress: Double = 0.0,
    val error: String? = null,
    val code: String? = null,
)

@Serializable
data class Hello(
    val app: String = "homeflix",
    val name: String = "HOMEFLIX",
    val version: String = "",
    val api: Int = 1,
    val pin: Boolean = false,
    val port: Int = 0,
)

@Serializable
data class AuthState(
    val pin: Boolean = false,
    val ok: Boolean = false,
    val version: String = "",
    val api: Int = 1,
)

@Serializable
data class UnlockResponse(
    val ok: Boolean = false,
    val key: String? = null,
    val error: String? = null,
    val wait: Int = 0,
)

@Serializable
internal data class UnlockRequest(val pin: String)

@Serializable
internal data class ErrorBody(
    val error: String? = null,
    val wait: Int = 0,
    val pin: Boolean = false,
)

@Serializable
data class Resume(
    val time: Double = 0.0,
    val duration: Double = 0.0,
    val at: Long = 0,
)
