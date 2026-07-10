package com.dhaval.echo.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Echo Breathe animation: scales from 1.0 to 1.055 over 4 seconds.
 */
@Composable
fun Modifier.echoBreathe(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "breathe")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.055f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    return this.graphicsLayer(scaleX = scale, scaleY = scale)
}

/**
 * Echo Ripple animation: scales from 0.5 to 1.5 and fades in/out over 2.4 seconds.
 */
@Composable
fun Modifier.echoRipple(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val infiniteTransition = rememberInfiniteTransition(label = "ripple")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )
    
    val opacity by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2400
                0.0f at 0
                0.55f at 528 // 22% of 2400
                0.0f at 2400
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "opacity"
    )
    
    return this.graphicsLayer(scaleX = scale, scaleY = scale, alpha = opacity)
}
