package com.homeflix.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.homeflix.app.ui.theme.Panel
import com.homeflix.app.ui.theme.HomeflixRed

@Composable
fun PinScreen(
    state: AppUiState,
    onUnlock: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = Panel,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.widthIn(max = 400.dp),
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(color = HomeflixRed, shape = RoundedCornerShape(50)) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    Text(
                        text = state.connection?.name ?: "HOMEFLIX",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Enter the PIN or password.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    OutlinedTextField(
                        value = pin,
                        onValueChange = { value ->
                            pin = value.filter { it.isLetterOrDigit() }.take(32)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                        label = { Text("PIN or password") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (pin.isNotBlank()) onUnlock(pin) },
                        ),
                        shape = RoundedCornerShape(14.dp),
                    )

                    state.pinError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    PremiumButton(
                        onClick = { onUnlock(pin) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = pin.isNotBlank() && !state.pinSubmitting,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (state.pinSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Unlock")
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    PremiumTextButton(onClick = onDisconnect) {
                        Text("Use a different computer")
                    }
                }
            }
        }
    }
}
