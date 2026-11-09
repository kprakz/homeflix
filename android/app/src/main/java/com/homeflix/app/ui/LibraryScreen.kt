package com.homeflix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.homeflix.app.data.LibraryItem
import com.homeflix.app.data.Resume
import com.homeflix.app.ui.theme.Gold
import com.homeflix.app.ui.theme.HomeflixRed
import com.homeflix.app.ui.theme.PanelHigh
import com.homeflix.app.util.ResumeRules
import com.homeflix.app.util.Series
import com.homeflix.app.util.formatClock
import com.homeflix.app.util.formatSize
import com.homeflix.app.util.isNew
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: AppUiState,
    onPlay: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    thumbUrl: (String, Boolean) -> String?,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<LibraryItem?>(null) }
    var selectedShow by remember { mutableStateOf<String?>(null) }

    val filtered = remember(state.library, query) {
        state.library
            .filter { item ->
                query.isBlank() ||
                    item.title.contains(query, ignoreCase = true) ||
                    item.show?.contains(query, ignoreCase = true) == true
            }
            .sortedWith(
                compareBy(
                    { it.show ?: it.title.lowercase() },
                    { it.season ?: 0 },
                    { it.ep ?: 0 },
                    { it.title.lowercase() },
                ),
            )
    }
    val showGroups = remember(filtered) {
        Series.group(filtered).toSortedMap()
    }
    val movies = remember(filtered) {
        filtered.filter { Series.of(it) == null }
    }
    val continueWatching = remember(state.library, state.resume) {
        state.resume.entries
            .filter { ResumeRules.inProgress(it.value) }
            .sortedByDescending { it.value.at }
            .mapNotNull { entry -> state.library.firstOrNull { it.id == entry.key } }
            .distinctBy { it.id }
            .take(12)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
                title = {
                    Column {
                        HomeflixLogo(fontSize = 32.sp)
                        state.connection?.name?.let { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    RefreshIconButton(
                        pulse = state.libraryPulse,
                        contentDescription = "Refresh library",
                        onClick = onRefresh,
                    )
                    PremiumIconButton(onClick = onDisconnect) {
                        Icon(Icons.Rounded.Tv, contentDescription = "Change computer")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search movies and shows") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        PremiumIconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(50),
            )

            when {
                state.loadingLibrary && state.library.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = HomeflixRed)
                            Spacer(Modifier.height(12.dp))
                            Text("Reading the library…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                state.libraryError != null && state.library.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(32.dp),
                        ) {
                            Text(state.libraryError, color = MaterialTheme.colorScheme.error)
                            PremiumButton(onClick = onRefresh) { Text("Try again") }
                        }
                    }
                }

                else -> {
                    if (continueWatching.isNotEmpty() && query.isBlank()) {
                        Text(
                            text = "CONTINUE WATCHING",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowItems(continueWatching, key = { it.id }) { item ->
                                ContinueCard(
                                    item = item,
                                    resume = state.resume[item.id],
                                    imageUrl = thumbUrl(item.id, false),
                                    onClick = { onPlay(item.id, false) },
                                )
                            }
                        }
                    }

                    if (showGroups.isNotEmpty()) {
                        Text(
                            text = if (query.isBlank()) "TV SHOWS" else "SHOWS",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowItems(showGroups.entries.toList(), key = { it.key }) { (name, episodes) ->
                                ShowCard(
                                    name = name,
                                    count = episodes.size,
                                    imageUrl = episodes.firstOrNull()?.let { thumbUrl(it.id, false) },
                                    onClick = { selectedShow = name },
                                )
                            }
                        }
                    }

                    if (movies.isNotEmpty() || showGroups.isEmpty()) {
                        Text(
                            text = when {
                                showGroups.isNotEmpty() -> "MOVIES (${movies.size})"
                                query.isBlank() -> "ALL TITLES (${movies.size})"
                                else -> "RESULTS (${movies.size})"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                        )

                        if (movies.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(32.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Movie,
                                        contentDescription = null,
                                        modifier = Modifier.size(44.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(if (query.isBlank()) "No titles yet" else "No matches")
                                    if (query.isBlank()) {
                                        Text(
                                            "Add a movie or series folder on your computer, then refresh.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 140.dp),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                gridItems(movies, key = { it.id }) { item ->
                                    LibraryCard(
                                        item = item,
                                        resume = state.resume[item.id],
                                        imageUrl = thumbUrl(item.id, false),
                                        onClick = { selected = item },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { item ->
        ItemDetailSheet(
            item = item,
            resume = state.resume[item.id],
            imageUrl = thumbUrl(item.id, true),
            onPlay = { startOver ->
                selected = null
                onPlay(item.id, startOver)
            },
            onDismiss = { selected = null },
        )
    }

    selectedShow?.let { name ->
        ShowDetailSheet(
            name = name,
            episodes = showGroups[name].orEmpty(),
            resume = state.resume,
            thumbUrl = thumbUrl,
            onEpisode = { episode ->
                selectedShow = null
                selected = episode
            },
            onDismiss = { selectedShow = null },
        )
    }
}

@Composable
private fun LibraryCard(
    item: LibraryItem,
    resume: Resume?,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) HomeflixRed else Color.Transparent,
                shape = shape,
            )
            .bounceClickable(onClick = onClick)
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(PanelHigh),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.65f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.65f),
                        ),
                    ),
            )
            if (isNew(item.mtime)) {
                Surface(
                    color = Gold,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                ) {
                    Text(
                        "NEW",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            val busy = item.state == "working" || item.state == "queued" || item.downloading
            if (busy) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                ) {
                    Text(
                        text = if (item.progress > 0) {
                            "Preparing ${(item.progress * 100).roundToInt()}%"
                        } else {
                            "Preparing…"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (resume != null && ResumeRules.percent(resume) > 0f) {
                LinearProgressIndicator(
                    progress = { ResumeRules.percent(resume) },
                    color = HomeflixRed,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
        Text(
            text = item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = listOfNotNull(
                item.epLabel.takeIf { it.isNotBlank() },
                item.year,
                formatSize(item.size).takeIf { it.isNotBlank() },
            ).joinToString(" • ") + if (ResumeRules.finished(resume)) " • Watched" else "",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContinueCard(
    item: LibraryItem,
    resume: Resume?,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(240.dp)
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) HomeflixRed else Color.Transparent,
                shape = shape,
            )
            .bounceClickable(onClick = onClick)
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(PanelHigh),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f)),
            )
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(26.dp),
                )
            }
            if (resume != null) {
                LinearProgressIndicator(
                    progress = { ResumeRules.percent(resume) },
                    color = HomeflixRed,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
        Text(
            text = item.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (resume != null && ResumeRules.resumePosition(resume) != null) {
            Text(
                text = "${formatClock(ResumeRules.resumePosition(resume) ?: 0.0)} of ${formatClock(resume.duration)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShowCard(
    name: String,
    count: Int,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(200.dp)
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) HomeflixRed else Color.Transparent,
                shape = shape,
            )
            .bounceClickable(onClick = onClick)
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(PanelHigh),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f)),
            )
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(
                    Icons.Rounded.Tv,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(26.dp),
                )
            }
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            ) {
                Text(
                    text = "$count episode${if (count == 1) "" else "s"}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowDetailSheet(
    name: String,
    episodes: List<LibraryItem>,
    resume: Map<String, Resume>,
    thumbUrl: (String, Boolean) -> String?,
    onEpisode: (LibraryItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${episodes.size} episode${if (episodes.size == 1) "" else "s"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
            episodes.forEach { episode ->
                val episodeResume = resume[episode.id]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bounceClickable { onEpisode(episode) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(128.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PanelHigh),
                    ) {
                        val image = thumbUrl(episode.id, false)
                        if (image != null) {
                            AsyncImage(
                                model = image,
                                contentDescription = episode.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (ResumeRules.percent(episodeResume) > 0f) {
                            LinearProgressIndicator(
                                progress = { ResumeRules.percent(episodeResume) },
                                color = HomeflixRed,
                                trackColor = Color.White.copy(alpha = 0.25f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(3.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = listOf(episode.epLabel, episode.title)
                                .filter { it.isNotBlank() }
                                .joinToString(" • "),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when {
                                ResumeRules.finished(episodeResume) -> "Watched"
                                ResumeRules.resumePosition(episodeResume) != null ->
                                    "Resume ${formatClock(ResumeRules.resumePosition(episodeResume) ?: 0.0)}"
                                else -> formatSize(episode.size)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            PremiumTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDetailSheet(
    item: LibraryItem,
    resume: Resume?,
    imageUrl: String?,
    onPlay: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(PanelHigh),
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.surface,
                            ),
                        ),
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = listOfNotNull(
                        item.epLabel.takeIf { it.isNotBlank() },
                        item.year,
                        formatSize(item.size).takeIf { it.isNotBlank() },
                        item.folder,
                    ).joinToString(" • "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                val resumeAt = ResumeRules.resumePosition(resume)
                if (resumeAt != null) {
                    Text(
                        text = "Resume from ${formatClock(resumeAt)}",
                        color = Gold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (ResumeRules.finished(resume)) {
                    Text("Watched", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PremiumButton(
                    onClick = { onPlay(false) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (resumeAt != null) "Resume" else "Play", fontWeight = FontWeight.SemiBold)
                }
                if (resumeAt != null) {
                    PremiumOutlinedButton(
                        onClick = { onPlay(true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Start over")
                    }
                }
                PremiumTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
        }
    }
}
