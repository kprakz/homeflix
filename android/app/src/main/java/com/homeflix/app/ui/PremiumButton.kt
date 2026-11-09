package com.homeflix.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Applies a smooth spring scale bounce animation when pressed.
 */
@Composable
fun Modifier.bouncePress(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.95f,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "bouncePressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Modifier for clickable elements (cards, rows, posters) that combines a ripple indication
 * and a spring press scale animation.
 */
@Composable
fun Modifier.bounceClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .bouncePress(interactionSource, pressedScale = pressedScale)
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(),
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * Premium filled Button with interactive spring press animation.
 */
@Composable
fun PremiumButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    pressedScale: Float = 0.95f,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.bouncePress(interactionSource, pressedScale = pressedScale),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/**
 * Premium OutlinedButton with interactive spring press animation.
 */
@Composable
fun PremiumOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    pressedScale: Float = 0.95f,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.bouncePress(interactionSource, pressedScale = pressedScale),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/**
 * Premium TextButton with interactive spring press animation.
 */
@Composable
fun PremiumTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    pressedScale: Float = 0.95f,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.bouncePress(interactionSource, pressedScale = pressedScale),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/**
 * Premium IconButton with interactive spring press animation.
 */
@Composable
fun PremiumIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    pressedScale: Float = 0.88f,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.bouncePress(interactionSource, pressedScale = pressedScale),
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content,
    )
}
