package com.homeflix.app.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeflix.app.HomeflixApp
import com.homeflix.app.data.ApiException
import com.homeflix.app.data.Connection
import com.homeflix.app.data.InfoResponse
import com.homeflix.app.data.Resume
import com.homeflix.app.ui.userMessage
import com.homeflix.app.util.PlaySource
import com.homeflix.app.util.ResumeRules
import com.homeflix.app.util.Series
import com.homeflix.app.util.SourceSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubtitleSource(val label: String, val lang: String, val url: String)

data class PlayRequest(
    val url: String,
    val isHls: Boolean,
    val subtitles: List<SubtitleSource>,
    val startAt: Double,
    val audio: Int,
    val title: String,
    val subtitle: String,
)

data class PlayerUiState(
    val loading: Boolean = true,
    val preparing: Boolean = false,
    val prepareProgress: Double = 0.0,
    val info: InfoResponse? = null,
    val request: PlayRequest? = null,
    val audio: Int = 0,
    val error: String? = null,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as HomeflixApp).container
    private val repository = container.repository

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private var loadJob: Job? = null
    private var fallingBack = false
    private var currentId: String? = null

    /** Point the player at a library item. Reusing one ViewModel avoids leaking player state. */
    fun open(itemId: String, startOver: Boolean = false) {
        currentId = itemId
        fallingBack = false
        _ui.value = PlayerUiState()
        load(startAtOverride = if (startOver) 0.0 else null)
    }

    fun load(
        audioOverride: Int? = null,
        startAtOverride: Double? = null,
        forceHls: Boolean = false,
    ) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, preparing = false, error = null) }
            try {
                val id = currentId ?: throw ApiException(0, "No movie selected.")
                val connection = repository.connectionOnce()
                    ?: throw ApiException(0, "Choose a HOMEFLIX computer first.")
                val info = repository.info(id)
                val audio = audioOverride ?: chooseAudio(info, repository.audioTrack(id))
                val resume = repository.resume(id)
                val startAt = startAtOverride ?: ResumeRules.resumePosition(resume) ?: 0.0
                val hevcBlocked = forceHls || repository.hevcBlocked(info.tenBit)
                val source = if (forceHls) {
                    PlaySource.HLS
                } else {
                    SourceSelector.choose(info, CodecSupport.canPlayHevc(), hevcBlocked)
                }
                val request = buildRequest(connection, info, audio, startAt, source)
                fallingBack = false
                _ui.update {
                    it.copy(
                        loading = false,
                        preparing = false,
                        info = info,
                        request = request,
                        audio = audio,
                        error = null,
                    )
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                _ui.update { it.copy(loading = false, preparing = false, error = err.userMessage()) }
            }
        }
    }

    fun retry() = load()

    suspend fun boostEnabled(): Boolean = repository.boostEnabled()

    fun setBoost(on: Boolean) {
        viewModelScope.launch { repository.setBoost(on) }
    }

    fun selectAudio(track: Int, positionSeconds: Double) {
        if (track == _ui.value.audio) return
        val id = currentId ?: return
        val info = _ui.value.info ?: return
        val trackInfo = info.audios.firstOrNull { it.n == track }
        viewModelScope.launch {
            repository.saveAudio(id, track, trackInfo?.lang)
            load(audioOverride = track, startAtOverride = positionSeconds)
        }
    }

    /** Called when ExoPlayer cannot show HEVC: remember it and ask the laptop to convert. */
    fun fallbackToHls(positionSeconds: Double) {
        if (fallingBack) return
        fallingBack = true
        val info = _ui.value.info
        viewModelScope.launch {
            if (info != null) repository.blockHevc(info.tenBit)
            load(
                audioOverride = _ui.value.audio,
                startAtOverride = positionSeconds,
                forceHls = true,
            )
        }
    }

    fun saveProgress(positionSeconds: Double, durationSeconds: Double) {
        if (durationSeconds <= 0) return
        val id = currentId ?: return
        viewModelScope.launch {
            repository.saveResume(
                id,
                Resume(
                    time = positionSeconds.coerceAtMost(durationSeconds),
                    duration = durationSeconds,
                    at = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun nextEpisode(library: List<com.homeflix.app.data.LibraryItem>): com.homeflix.app.data.LibraryItem? {
        val info = _ui.value.info ?: return null
        val asItem = com.homeflix.app.data.LibraryItem(
            id = info.id,
            title = info.title,
            show = info.show,
            season = info.season,
            ep = info.ep,
        )
        return Series.nextEpisode(library, asItem)
    }

    private suspend fun chooseAudio(info: InfoResponse, saved: Int): Int {
        if (info.audios.isEmpty()) return 0
        if (info.audios.any { it.n == saved }) return saved
        val preferred = repository.defaultAudioLang()
        val match = info.audios.firstOrNull { preferred != null && it.lang == preferred }
            ?: info.audios.firstOrNull { it.lang == "eng" }
            ?: info.audios.first()
        return match.n
    }

    private suspend fun buildRequest(
        connection: Connection,
        info: InfoResponse,
        audio: Int,
        startAt: Double,
        source: PlaySource,
    ): PlayRequest {
        val id = currentId ?: throw ApiException(0, "No movie selected.")
        if (source == PlaySource.HLS) return hlsRequest(connection, info, audio, startAt)
        if (source == PlaySource.DIRECT && audio == 0) {
            return fileRequest(connection, info, audio, startAt)
        }

        val existing = runCatching { repository.status(id, audio) }.getOrNull()
        if (existing == null || existing.state != "ready") {
            val prepared = try {
                repository.prepare(id, audio)
            } catch (err: ApiException) {
                if (err.statusCode == 507 || err.message.contains("nospace", true)) {
                    return hlsRequest(connection, info, audio, startAt)
                }
                throw err
            }
            if (prepared.code == "nospace") return hlsRequest(connection, info, audio, startAt)
            var status = prepared
            while (status.state == "working" || status.state == "queued" || status.state == "pending") {
                _ui.update { it.copy(preparing = true, prepareProgress = status.progress) }
                delay(1200)
                status = repository.status(id, audio)
            }
            if (status.state != "ready") {
                return hlsRequest(connection, info, audio, startAt)
            }
        }
        _ui.update { it.copy(preparing = false, prepareProgress = 0.0) }
        return fileRequest(connection, info, audio, startAt)
    }

    private fun fileRequest(
        connection: Connection,
        info: InfoResponse,
        audio: Int,
        startAt: Double,
    ): PlayRequest = PlayRequest(
        url = repository.videoUrl(connection, currentId.orEmpty(), audio),
        isHls = false,
        subtitles = subtitles(connection, info),
        startAt = startAt,
        audio = audio,
        title = info.title,
        subtitle = subtitleFor(info),
    )

    private fun hlsRequest(
        connection: Connection,
        info: InfoResponse,
        audio: Int,
        startAt: Double,
    ): PlayRequest = PlayRequest(
        url = repository.hlsUrl(connection, currentId.orEmpty(), audio),
        isHls = true,
        subtitles = subtitles(connection, info),
        startAt = startAt,
        audio = audio,
        title = info.title,
        subtitle = subtitleFor(info),
    )

    private fun subtitles(connection: Connection, info: InfoResponse): List<SubtitleSource> =
        info.subs.map { sub ->
            SubtitleSource(
                label = sub.label.ifBlank { sub.lang.uppercase() },
                lang = sub.lang,
                url = repository.subtitleUrl(connection, currentId.orEmpty(), sub.key),
            )
        }

    private fun subtitleFor(info: InfoResponse): String = buildList {
        info.year?.takeIf { it.isNotBlank() }?.let(::add)
        info.epLabel.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" • ")
}
