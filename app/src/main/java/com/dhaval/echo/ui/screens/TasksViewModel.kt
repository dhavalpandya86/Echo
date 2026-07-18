package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.db.ExtractedItem
import com.dhaval.echo.data.db.ItemStatus
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TasksUiState(
    val items: List<ExtractedItem> = emptyList(),
    val isLoading: Boolean = true
)

/** The action items Echo pulled out of memories — tasks and reminders (MU-5). */
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val understandingDao: UnderstandingDao,
    authRepository: AuthRepository
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TasksUiState> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) flowOf(TasksUiState(isLoading = false))
        else understandingDao.getOpenActionables(userId).map { TasksUiState(it, isLoading = false) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TasksUiState())

    fun markDone(itemId: String) = viewModelScope.launch {
        understandingDao.updateItemStatus(itemId, ItemStatus.DONE)
    }

    fun dismiss(itemId: String) = viewModelScope.launch {
        understandingDao.updateItemStatus(itemId, ItemStatus.DISMISSED)
    }
}
