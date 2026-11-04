package com.homeflix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeflix.app.HomeflixApp
import com.homeflix.app.data.ApiException
import com.homeflix.app.data.Connection
import com.homeflix.app.data.DiscoveredServer
import com.homeflix.app.data.InvalidAddressException
import com.homeflix.app.data.LibraryItem
import com.homeflix.app.data.PinRequiredException
import com.homeflix.app.data.Resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val initializing: Boolean = true,
    val connection: Connection? = null,
    val connecting: Boolean = false,
    val connectError: String? = null,
    val discovered: List<DiscoveredServer> = emptyList(),
    val library: List<LibraryItem> = emptyList(),
    val resume: Map<String, Resume> = emptyMap(),
    val loadingLibrary: Boolean = false,
    val libraryError: String? = null,
    val needsPin: Boolean = false,
    val pinSubmitting: Boolean = false,
    val pinError: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as HomeflixApp).container
    private val repository = container.repository
    private val discovery = container.discovery

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var discoveryJob: Job? = null
    private var refreshing = false

    init {
        viewModelScope.launch {
            repository.resume.collect { resume ->
                _state.update { it.copy(resume = resume) }
            }
        }
        viewModelScope.launch {
            val saved = repository.connectionOnce()
            _state.update { it.copy(initializing = false, connection = saved) }
            if (saved != null) {
                refreshLibrary()
            } else {
                startDiscovery()
            }
        }
    }

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            runCatching {
                discovery.discover().collect { servers ->
                    _state.update { it.copy(discovered = servers) }
                }
            }
        }
    }

    fun connect(address: String) {
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, connectError = null, pinError = null) }
            try {
                val connection = repository.connect(address)
                discoveryJob?.cancel()
                discoveryJob = null
                _state.update {
                    it.copy(
                        connecting = false,
                        connection = connection,
                        discovered = emptyList(),
                        needsPin = false,
                        connectError = null,
                    )
                }
                refreshLibrary()
            } catch (err: Exception) {
                _state.update { it.copy(connecting = false, connectError = err.userMessage()) }
            }
        }
    }

    fun refreshLibrary() {
        if (refreshing) return
        val connection = _state.value.connection ?: return
        refreshing = true
        viewModelScope.launch {
            try {
                val items = repository.library()
                val resume = repository.resumeMap()
                _state.update {
                    it.copy(
                        connection = connection,
                        library = items,
                        resume = resume,
                        loadingLibrary = false,
                        libraryError = null,
                        needsPin = false,
                    )
                }
            } catch (err: PinRequiredException) {
                _state.update { it.copy(loadingLibrary = false, needsPin = true) }
            } catch (err: Exception) {
                _state.update {
                    it.copy(
                        loadingLibrary = false,
                        libraryError = err.userMessage(),
                        needsPin = err is ApiException && err.pinRequired,
                    )
                }
            } finally {
                refreshing = false
            }
        }
        _state.update { it.copy(loadingLibrary = true, libraryError = null) }
    }

    fun unlock(pin: String) {
        viewModelScope.launch {
            _state.update { it.copy(pinSubmitting = true, pinError = null) }
            try {
                val connection = repository.unlock(pin)
                _state.update {
                    it.copy(
                        connection = connection,
                        pinSubmitting = false,
                        needsPin = false,
                        pinError = null,
                    )
                }
                refreshLibrary()
            } catch (err: Exception) {
                _state.update {
                    it.copy(
                        pinSubmitting = false,
                        pinError = err.userMessage(),
                        needsPin = true,
                    )
                }
            }
        }
    }

    fun clearPinError() {
        _state.update { it.copy(pinError = null) }
    }

    fun disconnect() {
        viewModelScope.launch {
            discoveryJob?.cancel()
            discoveryJob = null
            repository.disconnect()
            _state.value = AppUiState(initializing = false)
            startDiscovery()
        }
    }

    fun refreshResume() {
        viewModelScope.launch {
            val resume = repository.resumeMap()
            _state.update { it.copy(resume = resume) }
        }
    }

    fun thumbUrl(id: String, hero: Boolean = false): String? =
        _state.value.connection?.let { repository.thumbUrl(it, id, hero) }
}

internal fun Throwable.userMessage(): String = when (this) {
    is InvalidAddressException -> message ?: "That address doesn't look right."
    is ApiException -> message
    is PinRequiredException -> message ?: "Enter the PIN."
    else -> message ?: "Couldn't reach the HOMEFLIX computer."
}
