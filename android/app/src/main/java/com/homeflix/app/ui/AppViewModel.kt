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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    val discovering: Boolean = false,
    val discoveryError: String? = null,
    val scanPulse: Long = 0,
    val library: List<LibraryItem> = emptyList(),
    val resume: Map<String, Resume> = emptyMap(),
    val loadingLibrary: Boolean = false,
    val libraryError: String? = null,
    val libraryPulse: Long = 0,
    val needsPin: Boolean = false,
    val pinSubmitting: Boolean = false,
    val pinError: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as HomeflixApp).container
    private val repository = container.repository
    private val discovery = container.discovery
    private val lanScanner = container.lanScanner

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var discoveryJob: Job? = null
    private var discoveryGeneration = 0
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
        launchDiscovery(restart = false)
    }

    /** Stops the current mDNS browse and starts a fresh one. */
    fun refreshDiscovery() {
        launchDiscovery(restart = true)
    }

    private fun launchDiscovery(restart: Boolean) {
        if (restart) {
            discoveryJob?.cancel()
            discoveryJob = null
        } else if (discoveryJob?.isActive == true) {
            return
        }
        val generation = ++discoveryGeneration
        _state.update {
            it.copy(
                discovered = if (restart) emptyList() else it.discovered,
                discovering = true,
                discoveryError = null,
                scanPulse = it.scanPulse + 1,
            )
        }
        discoveryJob = viewModelScope.launch {
            val mdns = LinkedHashMap<String, DiscoveredServer>()
            val lan = LinkedHashMap<String, DiscoveredServer>()
            fun key(server: DiscoveredServer) = server.url.trimEnd('/').lowercase()
            fun publish() {
                if (generation != discoveryGeneration) return
                val merged = LinkedHashMap<String, DiscoveredServer>()
                mdns.values.forEach { merged[key(it)] = it }
                lan.values.forEach { merged.putIfAbsent(key(it), it) }
                _state.update { it.copy(discovered = merged.values.toList(), discoveryError = null) }
            }
            try {
                if (restart) delay(500)
                coroutineScope {
                    launch {
                        lanScanner.scan().collect { server ->
                            lan[key(server)] = server
                            publish()
                        }
                    }
                    discovery.discover().collect { servers ->
                        mdns.clear()
                        servers.forEach { mdns[key(it)] = it }
                        publish()
                    }
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                if (generation == discoveryGeneration) {
                    _state.update {
                        if (it.discovered.isEmpty()) it.copy(discoveryError = err.userMessage()) else it
                    }
                }
            } finally {
                if (generation == discoveryGeneration) {
                    _state.update { it.copy(discovering = false) }
                }
            }
        }
    }

    fun connect(address: String) {
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, connectError = null, pinError = null) }
            try {
                val connection = repository.connect(address)
                discoveryGeneration++
                discoveryJob?.cancel()
                discoveryJob = null
                _state.update {
                    it.copy(
                        connecting = false,
                        connection = connection,
                        discovered = emptyList(),
                        discovering = false,
                        discoveryError = null,
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
        if (refreshing) {
            _state.update { it.copy(libraryPulse = it.libraryPulse + 1) }
            return
        }
        val connection = _state.value.connection ?: return
        refreshing = true
        _state.update {
            it.copy(loadingLibrary = true, libraryError = null, libraryPulse = it.libraryPulse + 1)
        }
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
            repository.disconnect()
            _state.value = AppUiState(initializing = false)
            launchDiscovery(restart = true)
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
