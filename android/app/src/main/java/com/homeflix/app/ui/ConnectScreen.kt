package com.homeflix.app.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homeflix.app.discovery.QrScannerDialog
import com.homeflix.app.ui.theme.HomeflixRed
import com.homeflix.app.ui.theme.Ink
import com.homeflix.app.ui.theme.Panel

@Composable
fun ConnectScreen(
    state: AppUiState,
    isServerRunning: Boolean = false,
    onConnect: (String) -> Unit,
    onDiscover: () -> Unit,
    onServerMode: () -> Unit,
) {
    val context = LocalContext.current
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var address by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Ink, Color(0xFF1A0507), Ink),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "HOMEFLIX",
                color = HomeflixRed,
                fontWeight = FontWeight.Black,
                fontSize = 42.sp,
                letterSpacing = 3.sp,
            )
            Text(
                text = "Your library. On every screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(40.dp))

            Column(
                modifier = Modifier.widthIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Computers on this Wi-Fi",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.discovered.isEmpty() && state.discovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    RefreshIconButton(
                        pulse = state.scanPulse,
                        contentDescription = "Search again",
                        onClick = onDiscover,
                    )
                }

                if (state.discovered.isEmpty()) {
                    Text(
                        text = state.discoveryError
                            ?: "Looking for HOMEFLIX. Make sure the computer and this device are on the same network.",
                        color = if (state.discoveryError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    state.discovered.forEach { server ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .bounceClickable(enabled = !state.connecting) { onConnect(server.url) },
                            color = Panel,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Surface(
                                    color = HomeflixRed,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Tv,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(server.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = server.url.removePrefix("http://"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "OR CONNECT BY HAND",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 2.sp,
                )

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Computer address") },
                    placeholder = { Text("192.168.1.20:8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { if (address.isNotBlank()) onConnect(address) },
                    ),
                    shape = RoundedCornerShape(14.dp),
                )

                PremiumButton(
                    onClick = { onConnect(address) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = address.isNotBlank() && !state.connecting,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (state.connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Connecting…")
                    } else {
                        Text("Connect")
                    }
                }

                if (hasCamera) {
                    PremiumOutlinedButton(
                        onClick = { scanning = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = !state.connecting,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Scan QR code")
                    }
                }

                Spacer(Modifier.height(10.dp))
                PremiumOutlinedButton(
                    onClick = onServerMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    enabled = !state.connecting,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        Icons.Rounded.WifiTethering,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Phone Server Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isServerRunning) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = Color(0xFF43D17A).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(50),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Color(0xFF43D17A), RoundedCornerShape(50)),
                                )
                                Text(
                                    text = "ON",
                                    color = Color(0xFF43D17A),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }

                state.connectError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Open HOMEFLIX on the computer that has your movies. Its address is shown in Settings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (scanning) {
        QrScannerDialog(
            onResult = { text ->
                scanning = false
                onConnect(text)
            },
            onDismiss = { scanning = false },
        )
    }
}

