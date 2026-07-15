package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.ai.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val conversationService: ConversationService,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private var currentConversationId: String? = null

    init {
        loadSuggestedQuestions()
    }

    private fun loadSuggestedQuestions() {
        viewModelScope.launch {
            conversationService.getSuggestedQuestions().collect { questions ->
                _uiState.update { it.copy(suggestedQuestions = questions) }
            }
        }
    }

    fun onQuestionChange(question: String) {
        _uiState.update { it.copy(currentQuestion = question) }
    }

    fun askQuestion() {
        val question = _uiState.value.currentQuestion
        if (question.isBlank()) return

        _uiState.update { it.copy(isThinking = true, currentQuestion = "") }

        viewModelScope.launch {
            conversationService.ask(question, currentConversationId)
                .onStart { 
                    // Optimization: if it's the first question, we might want to refresh messages
                    // but RealConversationService already adds to repo
                }
                .collect { assistantMessage ->
                    if (currentConversationId == null) {
                        currentConversationId = assistantMessage.conversationId
                        observeMessages(assistantMessage.conversationId)
                    }
                    _uiState.update { it.copy(isThinking = false) }
                }
        }
    }

    private fun observeMessages(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.getMessages(conversationId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }
    
    fun startNewConversation() {
        currentConversationId = null
        _uiState.update { ConversationUiState(suggestedQuestions = it.suggestedQuestions) }
    }
}

data class ConversationUiState(
    val messages: List<Message> = emptyList(),
    val currentQuestion: String = "",
    val isThinking: Boolean = false,
    val suggestedQuestions: List<String> = emptyList()
)
