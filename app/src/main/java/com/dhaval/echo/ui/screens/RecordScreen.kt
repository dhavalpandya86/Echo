package com.dhaval.echo.ui.screens

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhaval.echo.ui.theme.EchoDeepIndigo
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun RecordScreen(
    onNavigateBack: () -> Unit,
    viewModel: RecordViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // UI state for finishing animation
    var isFinishing by remember { mutableStateOf(false) }
    var showCheckmark by remember { mutableStateOf(false) }

    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0f
    }

    // Auto-start recording
    LaunchedEffect(Unit) {
        if (!state.isRecording && !state.isSaving) {
            viewModel.startRecording()
        }
    }

    // Handle the transition from Saving to Idle with animation
    LaunchedEffect(state.isSaving) {
        if (state.isSaving) {
            isFinishing = true
            delay(1000) // Animation time for wave collapse
            showCheckmark = true
            delay(1200) // Time to see the "Memory Saved" message
            onNavigateBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Ambient background motion
        AmbientBackground(isRecording = state.isRecording && !state.isPaused && !isFinishing, reduceMotion = reduceMotion)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: Status
            RecordingStatus(
                isPaused = state.isPaused,
                isFinishing = isFinishing,
                showCheckmark = showCheckmark
            )

            // Middle: Timer and Waveform
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                val timerAlpha by animateFloatAsState(
                    targetValue = if (isFinishing) 0f else 1f,
                    animationSpec = tween(500),
                    label = "timer_alpha"
                )

                Text(
                    text = formatDuration(state.durationMillis),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 80.sp,
                        fontWeight = FontWeight.ExtraLight,
                        letterSpacing = (-2).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.alpha(timerAlpha)
                )
                
                Spacer(Modifier.height(64.dp))
                
                RealWaveform(
                    amplitude = state.amplitude,
                    isRecording = state.isRecording && !state.isPaused && !isFinishing,
                    isFinishing = isFinishing,
                    reduceMotion = reduceMotion
                )
            }

            // Bottom: Controls
            RecordingControls(
                isPaused = state.isPaused,
                isFinishing = isFinishing,
                onCancel = {
                    viewModel.cancelRecording()
                    onNavigateBack()
                },
                onTogglePause = {
                    if (state.isPaused) viewModel.resumeRecording() else viewModel.pauseRecording()
                },
                onFinish = { viewModel.stopRecording() }
            )
        }

        // Overlay for Memory Saved
        AnimatedVisibility(
            visible = showCheckmark,
            enter = fadeIn(tween(600)) + scaleIn(initialScale = 0.9f, animationSpec = tween(600)),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(120.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(
                        "Memory Saved",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingStatus(
    isPaused: Boolean,
    isFinishing: Boolean,
    showCheckmark: Boolean
) {
    AnimatedContent(
        targetState = when {
            showCheckmark -> "Saved"
            isFinishing -> "Finishing..."
            isPaused -> "Paused"
            else -> "Recording"
        },
        transitionSpec = {
            fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
        },
        label = "status"
    ) { status ->
        Text(
            text = status.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 48.dp)
        )
    }
}

@Composable
private fun AmbientBackground(isRecording: Boolean, reduceMotion: Boolean) {
    if (reduceMotion) return

    val infiniteTransition = rememberInfiniteTransition(label = "ambient")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = if (isRecording) alpha else 0.05f
                this.scaleX = scale
                this.scaleY = scale
            }
            .background(
                Brush.radialGradient(
                    colors = listOf(EchoDeepIndigo.copy(alpha = 0.3f), Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = 1200f
                )
            )
            .blur(100.dp)
    )
}

@Composable
private fun RealWaveform(
    amplitude: Float,
    isRecording: Boolean,
    isFinishing: Boolean,
    reduceMotion: Boolean
) {
    val barCount = 48
    val amplitudes = remember { mutableStateListOf<Float>().apply { repeat(barCount) { add(0f) } } }
    
    // Normalize amplitude from MediaRecorder (0-32767) to 0.0 - 1.0
    val normalizedAmplitude = (amplitude / 32767f).coerceIn(0f, 1f)

    // Smooth current amplitude to avoid jitter
    val animatedAmplitude by animateFloatAsState(
        targetValue = normalizedAmplitude,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "amp"
    )

    LaunchedEffect(animatedAmplitude) {
        if (isRecording) {
            amplitudes.removeAt(0)
            amplitudes.add(animatedAmplitude)
        }
    }

    // Collapse effect
    LaunchedEffect(isFinishing) {
        if (isFinishing) {
            while (amplitudes.any { it > 0.01f }) {
                repeat(amplitudes.size) { i ->
                    amplitudes[i] = amplitudes[i] * 0.7f
                }
                delay(16)
            }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(horizontal = 24.dp)
    ) {
        val width = size.width
        val height = size.height
        val barWidth = width / (barCount * 1.6f)
        val gap = barWidth * 0.6f

        amplitudes.forEachIndexed { index, amp ->
            val jitter = if (isRecording && !reduceMotion) (Math.random().toFloat() * 0.03f) else 0f
            val barHeight = (height * (amp + 0.04f + jitter)).coerceIn(6.dp.toPx(), height)
            
            val x = index * (barWidth + gap)
            val y = (height - barHeight) / 2

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        EchoDeepIndigo.copy(alpha = 0.3f),
                        EchoDeepIndigo,
                        EchoDeepIndigo.copy(alpha = 0.3f)
                    )
                ),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}

@Composable
private fun RecordingControls(
    isPaused: Boolean,
    isFinishing: Boolean,
    onCancel: () -> Unit,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit
) {
    val controlsAlpha by animateFloatAsState(
        targetValue = if (isFinishing) 0f else 1f,
        animationSpec = tween(500),
        label = "controls_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 64.dp)
            .alpha(controlsAlpha),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cancel
        IconButton(
            onClick = onCancel,
            enabled = !isFinishing,
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Cancel")
        }

        // Breathing Record/Pause Button
        BreathingRecordButton(
            isRecording = !isPaused,
            isFinishing = isFinishing,
            onClick = onTogglePause
        )

        // Finish
        Surface(
            onClick = onFinish,
            enabled = !isFinishing,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Check, 
                    contentDescription = "Finish",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun BreathingRecordButton(
    isRecording: Boolean,
    isFinishing: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val scale = if (isRecording && !isFinishing) 1.15f else breathingScale
    val glowAlpha by animateFloatAsState(
        targetValue = if (isRecording && !isFinishing) 0.5f else 0.1f,
        animationSpec = tween(1000),
        label = "glow"
    )

    Box(contentAlignment = Alignment.Center) {
        // Outer Glow
        Box(
            modifier = Modifier
                .size(110.dp)
                .scale(scale + 0.1f)
                .alpha(glowAlpha)
                .background(EchoDeepIndigo, CircleShape)
                .blur(30.dp)
        )

        // Duration Ring
        Canvas(modifier = Modifier.size(96.dp)) {
            drawArc(
                color = EchoDeepIndigo.copy(alpha = 0.1f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx())
            )
            
            if (isRecording) {
                drawArc(
                    color = EchoDeepIndigo,
                    startAngle = rotation,
                    sweepAngle = 120f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }
        }

        // Main Button
        Surface(
            onClick = onClick,
            enabled = !isFinishing,
            shape = CircleShape,
            color = if (isRecording) EchoDeepIndigo else MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .size(84.dp)
                .scale(scale)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isRecording) "Pause" else "Resume",
                    tint = if (isRecording) Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
