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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

data class TasksUiState(
    val open: List<ExtractedItem> = emptyList(),
    val done: List<ExtractedItem> = emptyList(),
    val isLoading: Boolean = true
)

/** The action items Echo pulled out of memories — tasks and reminders (MU-5),
 *  with a completion workflow (done / undo / reschedule / dismiss). */
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val understandingDao: UnderstandingDao,
    authRepository: AuthRepository
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TasksUiState> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) flowOf(TasksUiState(isLoading = false))
        else combine(
            understandingDao.getOpenActionables(userId),
            understandingDao.getCompletedActionables(userId)
        ) { open, done -> TasksUiState(open = open, done = done, isLoading = false) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TasksUiState())

    fun markDone(itemId: String) = viewModelScope.launch {
        understandingDao.updateItemStatus(itemId, ItemStatus.DONE)
    }

    /** Move a completed commitment back to open. */
    fun undoDone(itemId: String) = viewModelScope.launch {
        understandingDao.updateItemStatus(itemId, ItemStatus.OPEN)
    }

    fun dismiss(itemId: String) = viewModelScope.launch {
        understandingDao.updateItemStatus(itemId, ItemStatus.DISMISSED)
    }

    /** Reschedule to the start of a day [daysFromNow] out (or clear with null). */
    fun reschedule(itemId: String, daysFromNow: Int?) = viewModelScope.launch {
        val millis = daysFromNow?.let {
            LocalDateTime.now().plusDays(it.toLong()).withHour(9).withMinute(0).withSecond(0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        understandingDao.updateItemDue(itemId, millis)
    }
}
