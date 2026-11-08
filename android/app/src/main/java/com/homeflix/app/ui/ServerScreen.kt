package com.homeflix.app.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.QRCodeWriter
import com.homeflix.app.R
import com.homeflix.app.server.PinKind
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
    var pinKindDraft by remember { mutableStateOf(ServerPin.kindOf(ServerPin.get(context) ?: "")) }
    var pinVisible by remember { mutableStateOf(false) }
    var pinCopied by remember { mutableStateOf(false) }
    var addressCopied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    // Without POST_NOTIFICATIONS on Android 13+ the sharing notification (and its Disable
    // action) never appears in the panel, so ask the first time Server Mode is enabled.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(state.pin) {
        state.pin?.let {
            currentPin = it
            pinKindDraft = ServerPin.kindOf(it)
        }
    }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    if (state.running) Color(0xFF43D17A) else if (state.starting) Color(0xFFFFC857) else HomeflixRed,
                                    RoundedCornerShape(50),
                                ),
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
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
                        Text(
                            "Address",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Surface(shape = RoundedCornerShape(12.dp), color = PanelHigh, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = address,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "On this phone: ${state.localAddress ?: "http://127.0.0.1:8080"}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                PremiumIconButton(onClick = {
                                    clipboard.setText(AnnotatedString(address))
                                    addressCopied = true
                                }) {
                                    Icon(
                                        Icons.Rounded.ContentCopy,
                                        contentDescription = "Copy address",
                                        tint = Color.White,
                                    )
                                }
                            }
                        }
                        if (addressCopied) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Address copied.",
                                color = Color(0xFF43D17A),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            LaunchedEffect(Unit) { kotlinx.coroutines.delay(2000); addressCopied = false }
                        }
                        if (state.running) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Spacer(Modifier.height(16.dp))
                                Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
                                    QrCode(
                                        data = address,
                                        size = 200.dp,
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Scan to connect",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
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
                    if (state.running || state.starting) {
                        PremiumOutlinedButton(
                            onClick = onStop,
                            enabled = !state.starting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Disable Server Mode")
                        }
                    } else {
                        PremiumButton(
                            onClick = {
                                requestNotificationsIfNeeded()
                                onStart()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Enable Server Mode")
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "PIN and password",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        currentPin?.let {
                            Surface(
                                color = Color(0xFF43D17A).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    text = ServerPin.kindOf(it).label,
                                    color = Color(0xFF43D17A),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Devices must enter this to watch. It stays on this phone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    val pin = currentPin
                    if (pin == null) {
                        Text(
                            "No password yet. It is created the first time Server Mode starts.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Surface(shape = RoundedCornerShape(12.dp), color = PanelHigh, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
                                Text(
                                    text = if (pinVisible) pin else "•".repeat(minOf(pin.length, 12)),
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                PremiumIconButton(onClick = { pinVisible = !pinVisible }) {
                                    Icon(
                                        if (pinVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (pinVisible) "Hide password" else "Show password",
                                        tint = Color.White,
                                    )
                                }
                                PremiumIconButton(onClick = {
                                    clipboard.setText(AnnotatedString(pin))
                                    pinCopied = true
                                }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy password", tint = Color.White)
                                }
                            }
                        }
                        if (pinCopied) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Password copied.",
                                color = Color(0xFF43D17A),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            LaunchedEffect(Unit) { kotlinx.coroutines.delay(2000); pinCopied = false }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PremiumOutlinedButton(
                            onClick = {
                                pinKindDraft = currentPin?.let { ServerPin.kindOf(it) } ?: PinKind.PIN4
                                pinDraft = ""
                                pinError = null
                                pinDialog = true
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (currentPin == null) "Set a PIN" else "Change PIN")
                        }
                        PremiumOutlinedButton(
                            onClick = {
                                val generated = ServerPin.generate(pinKindDraft)
                                if (ServerPin.set(context, generated)) {
                                    currentPin = generated
                                    pinVisible = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Random")
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Shared folders", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Choose the folders this phone shares. Movies and series are kept separate. Each series folder is one show; its videos become episodes. Subfolders are included.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(14.dp))
                    FolderSection(
                        title = "Movie folders",
                        icon = Icons.Rounded.Movie,
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
                    )
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.12f),
                        thickness = 1.dp,
                    )
                    Spacer(Modifier.height(16.dp))
                    FolderSection(
                        title = "Series folders",
                        icon = Icons.Rounded.Tv,
                        lead = "Each folder is one show. Season subfolders and episode numbers are optional.",
                        folders = folders.filter { it.isSeries },
                        addLabel = "Add series folder",
                        onAdd = {
                            pickingType = ServerFolders.SERIES
                            folderPicker.launch(null)
                        },
                        onRemove = { folder ->
                            folders = ServerFolders.remove(context, folder.path)
                            folderError = null
                            ServerService.configChanged(context)
                        },
                    )
                    if (folders.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No folders selected. Add at least one folder so other devices have something to play.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    folderError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "File access control",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        Surface(
                            color = if (storageAllowed) Color(0xFF43D17A).copy(alpha = 0.15f) else HomeflixRed.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                text = if (storageAllowed) "Granted" else "Not Granted",
                                color = if (storageAllowed) Color(0xFF43D17A) else HomeflixRed,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (storageAllowed)
                            "All-files access is granted. HOMEFLIX can read movie and series folders across your phone and external storage."
                        else
                            "All-files access is not granted. HOMEFLIX can currently only read its own app storage folder.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(14.dp))
                    PremiumOutlinedButton(
                        onClick = {
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
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
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
            title = { Text(if (currentPin == null) "Set a PIN" else "Change PIN") },
            text = {
                Column {
                    Text("Every device will need the new PIN the next time it connects.")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PinKind.entries.forEach { kind ->
                            val selected = pinKindDraft == kind
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (selected) HomeflixRed else PanelHigh,
                                modifier = Modifier.clickable {
                                    pinKindDraft = kind
                                    pinDraft = ""
                                    pinError = null
                                },
                            ) {
                                Text(
                                    kind.label,
                                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinDraft,
                        onValueChange = { value ->
                            pinDraft = when (pinKindDraft) {
                                PinKind.PIN4, PinKind.PIN6 -> value.filter(Char::isDigit).take(pinKindDraft.maxLength)
                                PinKind.PASSWORD -> value.filter { it.isLetterOrDigit() }.take(PinKind.PASSWORD.maxLength)
                            }
                            pinError = null
                        },
                        singleLine = true,
                        label = { Text("PIN") },
                        placeholder = {
                            Text(
                                when (pinKindDraft) {
                                    PinKind.PIN4 -> "4 digits"
                                    PinKind.PIN6 -> "6 digits"
                                    PinKind.PASSWORD -> "6–32 letters and numbers"
                                },
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (pinKindDraft) {
                                PinKind.PIN4, PinKind.PIN6 -> KeyboardType.NumberPassword
                                PinKind.PASSWORD -> KeyboardType.Password
                            },
                        ),
                        visualTransformation = VisualTransformation.None,
                        isError = pinError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    PremiumTextButton(onClick = {
                        pinDraft = ServerPin.generate(pinKindDraft)
                        pinError = null
                    }) {
                        Text("Generate a random ${pinKindDraft.label.lowercase()}")
                    }
                    pinError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                PremiumTextButton(onClick = {
                    if (!ServerPin.isValid(pinDraft, pinKindDraft)) {
                        pinError = when (pinKindDraft) {
                            PinKind.PIN4 -> "The PIN must be exactly 4 digits."
                            PinKind.PIN6 -> "The PIN must be exactly 6 digits."
                            PinKind.PASSWORD -> "Use 6 to 32 letters and numbers with at least one of each."
                        }
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

@Composable
private fun QrCode(data: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val matrix: BitMatrix = remember(data) {
        val hints = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, hints)
    }
    Canvas(modifier.then(Modifier.size(size))) {
        drawRect(Color.White, Offset.Zero, this.size)
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(
                        Color.Black,
                        Offset(x * cell, y * cell),
                        Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

@Composable
private fun FolderSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    lead: String,
    folders: List<ServerFolders.SharedFolder>,
    addLabel: String,
    onAdd: () -> Unit,
    onRemove: (ServerFolders.SharedFolder) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = HomeflixRed,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
    Spacer(Modifier.height(4.dp))
    Text(lead, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    if (folders.isEmpty()) {
        Text("No folders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    folders.forEach { folder ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            color = PanelHigh,
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 0.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(36.dp)
                        .background(
                            color = HomeflixRed,
                            shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = HomeflixRed,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = File(folder.path).name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
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
                PremiumIconButton(onClick = { onRemove(folder) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove ${folder.path}", tint = Color.White)
                }
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
