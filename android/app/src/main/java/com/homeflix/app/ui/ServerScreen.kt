package com.homeflix.app.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homeflix.app.R
import com.homeflix.app.server.ServerClient
import com.homeflix.app.server.ServerFolders
import com.homeflix.app.server.ServerPin
import com.homeflix.app.server.ServerService
import com.homeflix.app.server.ServerState
import com.homeflix.app.server.ServerTileService
import com.homeflix.app.server.ServerUsage
import com.homeflix.app.ui.theme.HomeflixRed
import com.homeflix.app.ui.theme.Ink
import com.homeflix.app.ui.theme.Panel
import com.homeflix.app.ui.theme.PanelHigh
import java.io.File
import java.util.Locale

@Composable
fun ServerScreen(
    state: ServerState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var tileAdded by remember { mutableStateOf(ServerTileService.isAdded(context)) }
    var tileRemoveHelp by remember { mutableStateOf(false) }
    var storageAllowed by remember { mutableStateOf(hasAllFilesAccess()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageAllowed = hasAllFilesAccess()
                tileAdded = ServerTileService.isAdded(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var folders by remember { mutableStateOf(ServerFolders.list(context)) }
    var pickingType by remember { mutableStateOf(ServerFolders.MOVIES) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var currentPin by remember { mutableStateOf<String?>(ServerPin.get(context)) }
    var pinDialog by remember { mutableStateOf(false) }
    var pinDraft by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = treeUriToPath(uri)
        if (path == null) {
            folderError = "Choose a folder in this phone's internal storage or SD card."
            return@rememberLauncherForActivityResult
        }
        if (!File(path).canRead()) {
            folderError = "HOMEFLIX can't read that folder yet. Allow access to files, then add it again."
            return@rememberLauncherForActivityResult
        }
        folders = ServerFolders.add(context, path, pickingType)
        folderError = null
        ServerService.configChanged(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxHeight()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PremiumIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = "Share this phone",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "This phone can serve its own movies to other HOMEFLIX devices on the same Wi-Fi.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    if (state.running) Color(0xFF43D17A) else if (state.starting) Color(0xFFFFC857) else HomeflixRed,
                                    RoundedCornerShape(50),
                                ),
                        )
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(
                                text = "Server Mode",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                            Text(
                                text = when {
                                    state.running -> "Running (Active)"
                                    state.starting -> "Starting…"
                                    else -> "Disabled (Off)"
                                },
                                color = if (state.running) Color(0xFF43D17A) else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    if (state.starting) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(10.dp))
                            Text("Loading the embedded Node runtime and scanning your folders…", color = Color.White)
                        }
                    }

                    state.address?.let { address ->
                        Spacer(Modifier.height(16.dp))
                        Text("Address", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        Text(address, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("On this phone", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        Text(state.localAddress ?: "http://127.0.0.1:8080", color = Color.White)
                    }

                    state.message?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.running || state.starting) {
                            PremiumOutlinedButton(onClick = onStop, enabled = !state.starting) {
                                Text("Disable Server Mode")
                            }
                        } else {
                            PremiumButton(onClick = onStart) {
                                Text("Enable Server Mode")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Shared folders", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Choose the folders this phone shares. Movies and TV series are kept separate. Each TV series folder is one show; its videos become episodes. Tap a folder's type to switch it. Subfolders are included.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    FolderSection(
                        title = "Movie folders",
                        lead = "Every video appears as its own movie.",
                        folders = folders.filterNot { it.isSeries },
                        addLabel = "Add movie folder",
                        onAdd = {
                            pickingType = ServerFolders.MOVIES
                            folderPicker.launch(null)
                        },
                        onRemove = { folder ->
                            folders = ServerFolders.remove(context, folder.path)
                            folderError = null
                            ServerService.configChanged(context)
                        },
                        onSwitchType = { folder ->
                            folders = ServerFolders.setType(context, folder.path, ServerFolders.SERIES)
                            folderError = null
                            ServerService.configChanged(context)
                        },
                    )
                    Spacer(Modifier.height(18.dp))
                    FolderSection(
                        title = "TV series folders",
                        lead = "Each folder is one show. Season subfolders and episode numbers are optional.",
                        folders = folders.filter { it.isSeries },
                        addLabel = "Add TV series folder",
                        onAdd = {
                            pickingType = ServerFolders.SERIES
                            folderPicker.launch(null)
                        },
                        onRemove = { folder ->
                            folders = ServerFolders.remove(context, folder.path)
                            folderError = null
                            ServerService.configChanged(context)
                        },
                        onSwitchType = { folder ->
                            folders = ServerFolders.setType(context, folder.path, ServerFolders.MOVIES)
                            folderError = null
                            ServerService.configChanged(context)
                        },
                    )
                    if (folders.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No folders selected. Add at least one folder so other devices have something to play.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    folderError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (storageAllowed) "All-files access is granted." else "All-files access is not granted, so only this app's own folder is readable.",
                        color = if (storageAllowed) Color(0xFF43D17A) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    PremiumOutlinedButton(onClick = {
                        val intent = if (Build.VERSION.SDK_INT >= 30) {
                            try {
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            } catch (_: Exception) {
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            }
                        } else {
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }
                    }) {
                        Text(if (storageAllowed) "Revoke access" else "Allow access to files")
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "You control the shared folders and the PIN. The embedded Node runtime stays loaded while HOMEFLIX is running, so you can stop and start sharing again without restarting the app. Force stop HOMEFLIX to unload it completely.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (pinDialog) {
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text("Set a PIN") },
            text = {
                Column {
                    Text("Use 4 to 8 digits. Every device will need the new PIN the next time it connects.")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pinDraft,
                        onValueChange = { value ->
                            pinDraft = value.filter(Char::isDigit).take(8)
                            pinError = null
                        },
                        singleLine = true,
                        label = { Text("PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = pinError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    pinError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                PremiumTextButton(onClick = {
                    if (!ServerPin.isValid(pinDraft)) {
                        pinError = "The PIN must be 4 to 8 digits."
                    } else {
                        ServerPin.set(context, pinDraft)
                        currentPin = pinDraft
                        pinDialog = false
                        pinError = null
                        ServerService.configChanged(context)
                    }
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                PremiumTextButton(onClick = { pinDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

@Composable
private fun FolderSection(
    title: String,
    lead: String,
    folders: List<ServerFolders.SharedFolder>,
    addLabel: String,
    onAdd: () -> Unit,
    onRemove: (ServerFolders.SharedFolder) -> Unit,
    onSwitchType: (ServerFolders.SharedFolder) -> Unit,
) {
    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(lead, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    if (folders.isEmpty()) {
        Text("No folders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    folders.forEach { folder ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = File(folder.path).name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = folder.path,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PremiumTextButton(onClick = { onSwitchType(folder) }) {
                Text(
                    text = if (folder.isSeries) "TV series" else "Movie",
                    color = if (folder.isSeries) Color(0xFFF5B301) else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            PremiumIconButton(onClick = { onRemove(folder) }) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove ${folder.path}", tint = Color.White)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    PremiumOutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.Add, contentDescription = null)
        Spacer(Modifier.size(6.dp))
        Text(addLabel)
    }
}

@Composable
private fun UsageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) HomeflixRed else PanelHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun usagePeriodLabel(period: String) = when (period) {
    "today" -> "In use"
    "month" -> "This month"
    "year" -> "This year"
    "all" -> "Total"
    else -> "Range"
}

private fun timeAgo(ts: Long): String {
    val seconds = (System.currentTimeMillis() - ts) / 1000
    return when {
        seconds < 90 -> "just now"
        seconds < 3600 -> "${seconds / 60} m ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}

private fun activityLabel(client: ServerClient): String = when (client.activity) {
    "watching" -> client.title?.let { "Watching $it" } ?: "Watching"
    "opening" -> client.title?.let { "Opening $it…" } ?: "Opening something…"
    "browsing" -> "Browsing the library"
    else -> "Idle"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}

private fun formatSeconds(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remaining = seconds % 60
    return when {
        hours > 0 -> if (minutes > 0) "$hours h $minutes m" else "$hours h"
        minutes > 0 -> if (remaining > 0) "$minutes m $remaining s" else "$minutes m"
        remaining > 0 -> "$remaining s"
        else -> "—"
    }
}

private fun treeUriToPath(uri: Uri): String? {
    if (!DocumentsContract.isTreeUri(uri)) return null
    val docId = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (_: Exception) {
        return null
    }
    val parts = docId.split(':', limit = 2)
    val base = when (val volume = parts[0]) {
        "primary" -> Environment.getExternalStorageDirectory().absolutePath
        "home" -> return null
        else -> "/storage/$volume"
    }
    val relative = parts.getOrNull(1).orEmpty().trim('/')
    val path = if (relative.isEmpty()) base else "$base/$relative"
    return path.takeIf { File(it).isDirectory }
}
