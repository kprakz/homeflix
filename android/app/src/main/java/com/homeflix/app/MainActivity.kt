package com.homeflix.app

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeflix.app.player.PlayerScreen
import com.homeflix.app.server.ServerService
import com.homeflix.app.ui.ServerScreen
import com.homeflix.app.player.PlayerViewModel
import com.homeflix.app.ui.AppViewModel
import com.homeflix.app.ui.ConnectScreen
import com.homeflix.app.ui.HomeflixLogo
import com.homeflix.app.ui.LibraryScreen
import com.homeflix.app.ui.PinScreen
import com.homeflix.app.ui.theme.HomeflixRed
import com.homeflix.app.ui.theme.HomeflixTheme
import com.homeflix.app.ui.theme.Ink

class MainActivity : ComponentActivity() {
    private val openServerUi = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        openServerUi.value = intent?.getBooleanExtra(ServerService.EXTRA_OPEN_SERVER, false) == true
        setContent {
            HomeflixTheme {
                HomeflixRoot(openServerUi)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(ServerService.EXTRA_OPEN_SERVER, false)) openServerUi.value = true
    }
}

@Composable
private fun HomeflixRoot(openServerUi: androidx.compose.runtime.State<Boolean>) {
    val appVm: AppViewModel = viewModel()
    val playerVm: PlayerViewModel = viewModel()
    val state by appVm.state.collectAsStateWithLifecycle()
    var playingId by rememberSaveable { mutableStateOf<String?>(null) }
    var startOverFor by rememberSaveable { mutableStateOf<String?>(null) }
    var serverMode by rememberSaveable { mutableStateOf(false) }
    val serverState by ServerService.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The server notification's tap intent opens the Share-this-phone screen (consumed once).
    val serverUiRequested by openServerUi
    LaunchedEffect(serverUiRequested) {
        if (serverUiRequested) {
            serverMode = true
            (openServerUi as? androidx.compose.runtime.MutableState<Boolean>)?.value = false
        }
    }

    val playingItem = remember(state.library, playingId) {
        playingId?.let { id -> state.library.firstOrNull { it.id == id } }
    }

    LaunchedEffect(playingId, startOverFor) {
        playingId?.let { playerVm.open(it, startOver = startOverFor == it) }
    }

    BackHandler(enabled = playingId != null) {
        playingId = null
        startOverFor = null
        appVm.refreshResume()
    }

    BackHandler(enabled = playingId == null && serverMode) {
        serverMode = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when {
            serverMode -> ServerScreen(
                state = serverState,
                onStart = { ServerService.start(context) },
                onStop = { ServerService.stop(context) },
                onBack = { serverMode = false },
            )

            state.initializing -> Splash()

            playingItem != null -> PlayerScreen(
                vm = playerVm,
                item = playingItem,
                library = state.library,
                onClose = {
                    playingId = null
                    startOverFor = null
                    appVm.refreshResume()
                },
                onNext = { nextId ->
                    startOverFor = null
                    playingId = nextId
                },
            )

            state.connection == null -> ConnectScreen(
                state = state,
                isServerRunning = serverState.running,
                onConnect = appVm::connect,
                onDiscover = appVm::refreshDiscovery,
                onServerMode = {
                    serverMode = true
                },
            )

            state.needsPin -> PinScreen(
                state = state,
                onUnlock = appVm::unlock,
                onDisconnect = appVm::disconnect,
            )

            else -> LibraryScreen(
                state = state,
                onPlay = { id, startOver ->
                    startOverFor = if (startOver) id else null
                    playingId = id
                },
                onRefresh = appVm::refreshLibrary,
                onDisconnect = appVm::disconnect,
                thumbUrl = { id, hero -> appVm.thumbUrl(id, hero) },
            )
        }
    }
}

@Composable
private fun Splash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HomeflixLogo(fontSize = 48.sp)
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        }
    }
}
