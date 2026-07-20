package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.ai.Reflection
import com.dhaval.echo.data.ai.ReflectionReviewService
import com.dhaval.echo.data.ai.ReviewPeriod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewUiState(
    val period: ReviewPeriod = ReviewPeriod.WEEK,
    val reflection: Reflection? = null,
    val isLoading: Boolean = true
)

/** Generates a reflection over the user's memories for a chosen period. */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val reviewService: ReflectionReviewService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    init { load(ReviewPeriod.WEEK) }

    fun load(period: ReviewPeriod) {
        _uiState.value = ReviewUiState(period = period, isLoading = true)
        viewModelScope.launch {
            val reflection = reviewService.generate(period)
            _uiState.value = ReviewUiState(period = period, reflection = reflection, isLoading = false)
        }
    }
}
