package com.homeflix.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate

@Composable
fun RefreshIconButton(pulse: Long, contentDescription: String, onClick: () -> Unit) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            angle.snapTo(0f)
            angle.animateTo(360f, animationSpec = tween(durationMillis = 900, easing = LinearEasing))
        }
    }
    PremiumIconButton(onClick = onClick) {
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = contentDescription,
            modifier = Modifier.rotate(angle.value),
        )
    }
}
