package com.dhaval.echo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/**
 * @param onSearch when non-null, the bar submits on the keyboard's Search action
 *   (used by Reflect, where the query is asked rather than filtered live). When
 *   null the bar filters reactively as the user types (Story/Remember).
 */
@Composable
fun EchoSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null
) {
    // The bar owns the cursor, not the caller.
    //
    // A `String` carries no selection, so a TextField driven by one puts the
    // cursor wherever it likes whenever the value it is handed changes. On the
    // reactive screens the value round-trips through a StateFlow *and a Room
    // query* before coming back, so the field recomposes at least once with the
    // previous text — and that reset the caret to 0. Typing "prabir" produced
    // "rabirP": the first letter landed, the caret jumped home, and every letter
    // after it was inserted in front.
    //
    // Holding a TextFieldValue here keeps text and selection together through
    // that round trip. The caller still only ever sees a String.
    var field by remember { mutableStateOf(TextFieldValue(value)) }

    // Adopt changes that came from somewhere other than this keyboard — a clear
    // button, a restored query, a prompt tapped in Reflect. Guarded on the text
    // actually differing, so the caller echoing back what was just typed is not
    // mistaken for an external edit and does not disturb the caret.
    LaunchedEffect(value) {
        if (value != field.text) {
            field = TextFieldValue(value, TextRange(value.length))
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "border_color"
    )
    
    val elevation by animateDpAsState(
        targetValue = if (isFocused) 8.dp else 0.dp,
        label = "elevation"
    )

    TextField(
        value = field,
        onValueChange = {
            field = it
            onValueChange(it.text)
        },
        placeholder = { 
            Text(
                placeholder, 
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            ) 
        },
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(elevation, RoundedCornerShape(28.dp))
            .border(1.dp, borderColor, RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(28.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        leadingIcon = {
            Icon(
                Icons.Default.Search, 
                contentDescription = null,
                tint = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        },
        shape = RoundedCornerShape(28.dp),
        interactionSource = interactionSource,
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = onSearch != null,
        keyboardOptions = if (onSearch != null) {
            KeyboardOptions(imeAction = ImeAction.Search)
        } else KeyboardOptions.Default,
        keyboardActions = if (onSearch != null) {
            KeyboardActions(onSearch = { onSearch() })
        } else KeyboardActions.Default
    )
}
