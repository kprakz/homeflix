package com.homeflix.app.server

import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.homeflix.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: one tap starts Server Mode, another tap stops it, without opening the app.
 * The tile follows [ServerService.state] so it stays accurate when the server is changed elsewhere.
 */
class ServerTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var stateJob: Job? = null

    override fun onTileAdded() {
        super.onTileAdded()
        setAdded(this, true)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        setAdded(this, false)
    }

    override fun onStartListening() {
        super.onStartListening()
        val listeningScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = listeningScope
        stateJob = listeningScope.launch {
            ServerService.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        scope?.cancel()
        stateJob = null
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = ServerService.state.value
        if (state.running || state.starting) {
            ServerService.stop(this)
            render(state.copy(running = false, starting = false))
        } else {
            ServerService.start(this)
            render(state.copy(running = false, starting = true))
        }
    }

    private fun render(state: ServerState) {
        val tile = qsTile ?: return
        val active = state.running || state.starting
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.server_tile_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                state.running -> state.address?.removePrefix("http://") ?: getString(R.string.server_tile_serving)
                state.starting -> getString(R.string.server_tile_starting)
                else -> getString(R.string.server_tile_off)
            }
        }
        tile.updateTile()
    }

    companion object {
        private const val PREFS = "homeflix_server"
        private const val KEY_TILE_ADDED = "tile_added"

        fun isAdded(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TILE_ADDED, false)

        fun setAdded(context: Context, added: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_TILE_ADDED, added)
                .apply()
        }
    }
}
