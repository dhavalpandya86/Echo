package com.dhaval.echo.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dhaval.echo.R
import com.dhaval.echo.ui.theme.EchoIndigo
import com.dhaval.echo.ui.theme.EchoLavender
import com.dhaval.echo.ui.theme.echoBreathe

@Composable
fun WelcomeScreen(
    onCreateAccount: () -> Unit,
    onLogin: () -> Unit,
    onPhoneLogin: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onFacebookSignIn: () -> Unit,
    error: String? = null
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) { error?.let { snackbarHostState.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(48.dp))

            // ── Brand lockup ──────────────────────────────────────────
            EchoMark()

            Spacer(Modifier.height(28.dp))

            Text(
                text = "echo",
                fontFamily = MaterialTheme.typography.displayLarge.fontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 56.sp,
                letterSpacing = (-0.03).em,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Where your memories live.",
                style = MaterialTheme.typography.titleMedium,
                color = EchoIndigo,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.weight(1.3f))
            Spacer(Modifier.height(48.dp))

            // ── Primary actions (one indigo action) ───────────────────
            Button(
                onClick = onCreateAccount,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = EchoIndigo)
            ) {
                Text("Create account", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f))
            ) {
                Text(
                    "Log in",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Divider ───────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
                Text(
                    text = "  or continue with  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Social ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GoogleSignInButton(onClick = onGoogleSignIn, modifier = Modifier.weight(1f))
                FacebookSignInButton(onClick = onFacebookSignIn, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(14.dp))

            TextButton(onClick = onPhoneLogin) {
                Text(
                    "Continue with phone",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun EchoMark() {
    Box(contentAlignment = Alignment.Center) {
        // Soft lavender halo
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(EchoLavender.copy(alpha = 0.35f), Color.Transparent)
                    )
                )
        )
        Icon(
            painter = painterResource(R.drawable.ic_echo_mark),
            contentDescription = "Echo",
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .size(84.dp)
                .echoBreathe()
        )
    }
}

@Composable
private fun GoogleSignInButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        GoogleLogo(modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Google",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun FacebookSignInButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val facebookBlue = Color(0xFF1877F2)
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = facebookBlue)
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            drawRect(color = Color.White, size = Size(w * 0.30f, h), topLeft = Offset(w * 0.30f, 0f))
            drawRect(
                color = Color.White,
                topLeft = Offset(w * 0.18f, h * 0.40f),
                size = Size(w * 0.52f, h * 0.16f)
            )
            drawRect(
                color = Color.White,
                topLeft = Offset(w * 0.30f, 0f),
                size = Size(w * 0.42f, h * 0.20f)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("Facebook", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun GoogleLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val cx = w / 2f
        val cy = size.height / 2f
        val r = minOf(w, size.height) / 2f

        val googleBlue = Color(0xFF4285F4)
        val googleRed = Color(0xFFEA4335)
        val googleYellow = Color(0xFFFBBC05)
        val googleGreen = Color(0xFF34A853)

        drawArc(color = googleBlue, startAngle = -30f, sweepAngle = 120f, useCenter = true, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2))
        drawArc(color = googleRed, startAngle = 90f, sweepAngle = 120f, useCenter = true, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2))
        drawArc(color = googleYellow, startAngle = 210f, sweepAngle = 60f, useCenter = true, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2))
        drawArc(color = googleGreen, startAngle = 270f, sweepAngle = 60f, useCenter = true, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2))
        drawCircle(color = Color.White, radius = r * 0.55f, center = Offset(cx, cy))
        drawRect(color = googleBlue, topLeft = Offset(cx, cy - r * 0.2f), size = Size(r * 0.85f, r * 0.4f))
        drawCircle(color = Color.White, radius = r * 0.38f, center = Offset(cx, cy))
    }
}
