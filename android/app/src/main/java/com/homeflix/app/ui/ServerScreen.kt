package com.homeflix.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homeflix.app.server.ServerFolders
import com.homeflix.app.server.ServerPin
import com.homeflix.app.server.ServerService
import com.homeflix.app.server.ServerState
import com.homeflix.app.ui.theme.HomeflixRed
import com.homeflix.app.ui.theme.Ink
import com.homeflix.app.ui.theme.Panel
import java.io.File

@Composable
fun ServerScreen(
    state: ServerState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var storageAllowed by remember { mutableStateOf(hasAllFilesAccess()) }
    var folders by remember { mutableStateOf(ServerFolders.list(context)) }
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
        folders = ServerFolders.add(context, path)
        folderError = null
        ServerService.configChanged(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
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
                        Text(
                            text = when {
                                state.running -> "Server running"
                                state.starting -> "Starting server…"
                                else -> "Server stopped"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
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
                            OutlinedButton(onClick = onStop, enabled = !state.starting) {
                                Text("Stop server")
                            }
                        } else {
                            Button(onClick = onStart) {
                                Text("Start server")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("PIN", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (currentPin != null) {
                            "Devices must enter this PIN once before they can watch. Current PIN: $currentPin"
                        } else {
                            "PIN is off. Anyone on this Wi-Fi can watch without entering a PIN."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            pinDraft = currentPin.orEmpty()
                            pinError = null
                            pinDialog = true
                        }) {
                            Text(if (currentPin != null) "Change PIN" else "Set PIN")
                        }
                        if (currentPin != null) {
                            OutlinedButton(onClick = {
                                ServerPin.set(context, null)
                                currentPin = null
                                ServerService.configChanged(context)
                            }) {
                                Text("Turn off PIN")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Movie folders", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Choose the folders this phone shares. Subfolders are included.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (folders.isEmpty()) {
                        Text(
                            "No folders selected. Add at least one folder so other devices have something to play.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    folders.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = folder,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                folders = ServerFolders.remove(context, folder)
                                folderError = null
                                ServerService.configChanged(context)
                            }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove $folder", tint = Color.White)
                            }
                        }
                    }
                    folderError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { folderPicker.launch(null) }) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Add folder")
                        }
                        OutlinedButton(onClick = {
                            folders = ServerFolders.reset(context)
                            folderError = null
                            ServerService.configChanged(context)
                        }) {
                            Text("Use defaults")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (storageAllowed) "All-files access is granted." else "All-files access is not granted, so only this app's own folder is readable.",
                        color = if (storageAllowed) Color(0xFF43D17A) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = {
                        storageAllowed = hasAllFilesAccess()
                        if (!storageAllowed && Build.VERSION.SDK_INT >= 30) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        }
                    }) {
                        Text(if (storageAllowed) "Check again" else "Allow access to files")
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
                TextButton(onClick = {
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
                TextButton(onClick = { pinDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

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
