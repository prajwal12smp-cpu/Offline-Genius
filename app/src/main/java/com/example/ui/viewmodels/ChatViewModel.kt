package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.rag.RetrievedContextChunk
import com.example.data.local.entities.ChatMessageEntity
import com.example.data.local.entities.ConversationEntity
import com.example.data.local.entities.SubjectEntity
import com.example.data.repository.AuthRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.ChatStreamState
import com.example.data.repository.ClassroomRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val currentSubject: SubjectEntity? = null,
    val currentConversation: ConversationEntity? = null,
    val isThinking: Boolean = false,
    val thinkingStep: String = "",
    val isGenerating: Boolean = false,
    val streamedResponse: String = "",
    val isFallbackGenerating: Boolean = false,
    val currentGenerationPath: String = "LLM",
    val lastGenerationPath: String = "LLM",
    val activeRetrievedSources: List<RetrievedContextChunk> = emptyList(),
    val errorMessage: String? = null,
    val ragDebugMode: Boolean = true
)

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val classroomRepository: ClassroomRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    val conversations: StateFlow<List<ConversationEntity>> = _conversations.asStateFlow()

    val modelStatus: StateFlow<com.example.ai.llm.ModelStatus> = chatRepository.llmEngine.modelStatus

    init {
        viewModelScope.launch {
            chatRepository.llmEngine.modelStatus.collect { status ->
                val isReady = status is com.example.ai.llm.ModelStatus.Ready
                _uiState.value = _uiState.value.copy(
                    lastGenerationPath = if (isReady) "LLM" else "Fallback (${status.displayLabel})"
                )
            }
        }
    }

    fun toggleRagDebugMode() {
        _uiState.value = _uiState.value.copy(ragDebugMode = !_uiState.value.ragDebugMode)
    }

    private var activeStreamJob: Job? = null
    private var observeMessagesJob: Job? = null
    private var observeConversationsJob: Job? = null

    fun bindSubject(subject: SubjectEntity) {
        _uiState.value = _uiState.value.copy(currentSubject = subject)
        val studentId = authRepository.currentUser.value?.id ?: return
        observeConversations(subject.id, studentId)
    }

    private fun observeConversations(subjectId: Int, studentId: Int) {
        observeConversationsJob?.cancel()
        observeConversationsJob = viewModelScope.launch {
            chatRepository.getConversationsForSubject(subjectId, studentId).collect { list ->
                _conversations.value = list
                // If no conversation selected, open the latest if available or stay fresh
                if (_uiState.value.currentConversation == null && list.isNotEmpty()) {
                    loadConversation(list.first().id)
                }
            }
        }
    }

    fun loadConversation(conversationId: Int) {
        viewModelScope.launch {
            val conv = _conversations.value.find { it.id == conversationId }
            _uiState.value = _uiState.value.copy(
                currentConversation = conv,
                isThinking = false,
                isGenerating = false,
                streamedResponse = "",
                activeRetrievedSources = emptyList()
            )
            observeMessages(conversationId)
        }
    }

    private fun observeMessages(conversationId: Int) {
        observeMessagesJob?.cancel()
        observeMessagesJob = viewModelScope.launch {
            chatRepository.getMessagesForConversation(conversationId).collect { list ->
                _messages.value = list
            }
        }
    }

    fun startNewConversation() {
        activeStreamJob?.cancel()
        _uiState.value = _uiState.value.copy(
            currentConversation = null,
            isThinking = false,
            isGenerating = false,
            streamedResponse = "",
            activeRetrievedSources = emptyList()
        )
        _messages.value = emptyList()
    }

    fun deleteConversation(convId: Int) {
        viewModelScope.launch {
            chatRepository.deleteConversation(convId)
            if (_uiState.value.currentConversation?.id == convId) {
                startNewConversation()
            }
        }
    }

    fun sendQuestion(questionText: String) {
        val trimmed = questionText.trim()
        if (trimmed.isBlank() || _uiState.value.isThinking || _uiState.value.isGenerating) return

        val subject = _uiState.value.currentSubject ?: return
        val student = authRepository.currentUser.value ?: return

        activeStreamJob?.cancel()
        activeStreamJob = viewModelScope.launch {
            var conv = _uiState.value.currentConversation
            if (conv == null) {
                conv = chatRepository.getOrCreateConversation(subject.id, student.id, trimmed)
                _uiState.value = _uiState.value.copy(currentConversation = conv)
                observeMessages(conv.id)
            }

            val isReady = chatRepository.isModelReady()
            _uiState.value = _uiState.value.copy(
                isThinking = true,
                thinkingStep = if (isReady) "Grounding with Gemma 3 1B on ${subject.name} curriculum..."
                               else "Analyzing question against ${subject.name} curriculum...",
                isGenerating = false,
                streamedResponse = "",
                activeRetrievedSources = emptyList(),
                errorMessage = null,
                currentGenerationPath = if (isReady) "LLM" else "Fallback (${chatRepository.llmEngine.modelStatus.value.displayLabel})",
                lastGenerationPath = if (isReady) "LLM" else "Fallback (${chatRepository.llmEngine.modelStatus.value.displayLabel})"
            )

            chatRepository.askQuestionStream(
                conversationId = conv.id,
                subjectId = subject.id,
                subjectName = subject.name,
                question = trimmed
            ).collect { state ->
                when (state) {
                    is ChatStreamState.Thinking -> {
                        _uiState.value = _uiState.value.copy(
                            isThinking = true,
                            thinkingStep = state.step
                        )
                    }
                    is ChatStreamState.RetrievedSources -> {
                        _uiState.value = _uiState.value.copy(
                            activeRetrievedSources = state.sources
                        )
                    }
                    is ChatStreamState.Generating -> {
                        _uiState.value = _uiState.value.copy(
                            isThinking = false,
                            isGenerating = true,
                            streamedResponse = state.fullTextSoFar,
                            isFallbackGenerating = state.isFallback,
                            currentGenerationPath = state.generationPath,
                            lastGenerationPath = state.generationPath
                        )
                    }
                    is ChatStreamState.Completed -> {
                        _uiState.value = _uiState.value.copy(
                            isThinking = false,
                            isGenerating = false,
                            streamedResponse = "",
                            isFallbackGenerating = false,
                            lastGenerationPath = state.generationPath,
                            activeRetrievedSources = state.sources
                        )
                    }
                    is ChatStreamState.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isThinking = false,
                            isGenerating = false,
                            errorMessage = state.error
                        )
                    }
                }
            }
        }
    }
}
