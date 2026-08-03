package com.dhaval.echo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.preferences.AppearanceMode
import com.dhaval.echo.data.preferences.AppearancePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the appearance choice for the whole app.
 *
 * Scoped at the root rather than to the settings screen, because the theme has
 * to be resolved before anything is drawn — a preference read further down the
 * tree would repaint the app a moment after launch.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val preferences: AppearancePreferences
) : ViewModel() {

    val mode: StateFlow<AppearanceMode> = preferences.mode
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppearanceMode.SYSTEM)

    fun setMode(mode: AppearanceMode) {
        viewModelScope.launch { preferences.setMode(mode) }
    }
}
