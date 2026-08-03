package com.dhaval.echo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/**
 * Full-screen, swipeable, pinch-to-zoom viewer for a memory's photos.
 *
 * Opened by tapping any photo in the detail grid. Kept deliberately simple: a
 * black backdrop, a pager across the photos, double-tap and pinch to zoom, and
 * an optional caption strip along the bottom. The caption is read-only here —
 * editing lives on the detail screen where the keyboard has room.
 */
@Composable
fun PhotoViewerDialog(
    imagePaths: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    captionFor: (path: String) -> String = { "" },
    onSaveCaption: ((path: String, caption: String) -> Unit)? = null
) {
    if (imagePaths.isEmpty()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, imagePaths.lastIndex),
            pageCount = { imagePaths.size }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ZoomableImage(model = imagePaths[page])
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }

            val currentPath = imagePaths[pagerState.currentPage]
            CaptionStrip(
                // Key by page so switching photos resets the editor to that photo's text.
                path = currentPath,
                caption = captionFor(currentPath),
                onSave = onSaveCaption,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
            )
        }
    }
}

/** Read-only caption when there's no save handler; an editable field when there is. */
@Composable
private fun CaptionStrip(
    path: String,
    caption: String,
    onSave: ((path: String, caption: String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    if (onSave == null) {
        if (caption.isBlank()) return
        Text(
            text = caption,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        )
        return
    }

    var text by remember(path) { mutableStateOf(caption) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Add your words about this photo…", color = Color.White.copy(alpha = 0.6f)) },
            singleLine = false,
            maxLines = 3,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(path, text) }),
            modifier = Modifier.weight(1f)
        )
        if (text != caption) {
            TextButton(onClick = { onSave(path, text) }) {
                Text("Save", color = Color.White)
            }
        }
    }
}

/** One page: pinch and double-tap to zoom, pan while zoomed, snap back on double-tap out. */
@Composable
private fun ZoomableImage(model: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        // No panning until zoomed in — avoids fighting the pager's horizontal swipe.
        if (scale > 1f) {
            offsetX += panChange.x
            offsetY += panChange.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }

    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f; offsetX = 0f; offsetY = 0f
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .transformable(transformState)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
    )
}
