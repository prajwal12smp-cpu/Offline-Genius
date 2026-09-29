package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entities.QuizAttemptEntity
import com.example.data.local.entities.QuizEntity
import com.example.data.local.entities.QuizQuestionEntity
import com.example.data.repository.AuthRepository
import com.example.data.repository.ClassroomRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class QuizTakingState(
    val quiz: QuizEntity? = null,
    val subjectName: String = "",
    val questions: List<QuizQuestionEntity> = emptyList(),
    val currentQuestionIndex: Int = 0,
    val selectedAnswers: Map<Int, Int> = emptyMap(), // questionIndex -> selectedOptionIndex (0..3)
    val remainingSeconds: Int = 0,
    val isSubmitted: Boolean = false,
    val score: Int = 0,
    val totalQuestions: Int = 0,
    val percentage: Int = 0
)

class QuizViewModel(
    private val classroomRepository: ClassroomRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _takingState = MutableStateFlow(QuizTakingState())
    val takingState: StateFlow<QuizTakingState> = _takingState.asStateFlow()

    private val _quizzesForSubject = MutableStateFlow<List<QuizEntity>>(emptyList())
    val quizzesForSubject: StateFlow<List<QuizEntity>> = _quizzesForSubject.asStateFlow()

    private val _studentAttempts = MutableStateFlow<List<QuizAttemptEntity>>(emptyList())
    val studentAttempts: StateFlow<List<QuizAttemptEntity>> = _studentAttempts.asStateFlow()

    private var timerJob: Job? = null

    init {
        loadStudentAttempts()
    }

    fun loadQuizzesForSubject(subjectId: Int) {
        viewModelScope.launch {
            classroomRepository.getQuizzesBySubject(subjectId).collect { list ->
                _quizzesForSubject.value = list
            }
        }
    }

    fun loadStudentAttempts() {
        val student = authRepository.currentUser.value ?: return
        viewModelScope.launch {
            classroomRepository.getAttemptsForStudent(student.id).collect { list ->
                _studentAttempts.value = list
            }
        }
    }

    fun startQuiz(quiz: QuizEntity, subjectName: String) {
        viewModelScope.launch {
            val questions = classroomRepository.getQuestionsForQuiz(quiz.id)
            val durationSeconds = quiz.timeLimitMinutes * 60

            _takingState.value = QuizTakingState(
                quiz = quiz,
                subjectName = subjectName,
                questions = questions,
                currentQuestionIndex = 0,
                selectedAnswers = emptyMap(),
                remainingSeconds = durationSeconds,
                isSubmitted = false,
                score = 0,
                totalQuestions = questions.size,
                percentage = 0
            )

            startTimer(durationSeconds)
        }
    }

    private fun startTimer(totalSeconds: Int) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var remaining = totalSeconds
            while (remaining > 0 && !_takingState.value.isSubmitted) {
                delay(1000)
                remaining--
                _takingState.value = _takingState.value.copy(remainingSeconds = remaining)
            }
            if (!_takingState.value.isSubmitted && _takingState.value.questions.isNotEmpty()) {
                submitQuiz()
            }
        }
    }

    fun selectOption(questionIndex: Int, optionIndex: Int) {
        if (_takingState.value.isSubmitted) return
        val currentAnswers = _takingState.value.selectedAnswers.toMutableMap()
        currentAnswers[questionIndex] = optionIndex
        _takingState.value = _takingState.value.copy(selectedAnswers = currentAnswers)
    }

    fun nextQuestion() {
        val curr = _takingState.value.currentQuestionIndex
        if (curr < _takingState.value.questions.size - 1) {
            _takingState.value = _takingState.value.copy(currentQuestionIndex = curr + 1)
        }
    }

    fun prevQuestion() {
        val curr = _takingState.value.currentQuestionIndex
        if (curr > 0) {
            _takingState.value = _takingState.value.copy(currentQuestionIndex = curr - 1)
        }
    }

    fun jumpToQuestion(index: Int) {
        if (index in 0 until _takingState.value.questions.size) {
            _takingState.value = _takingState.value.copy(currentQuestionIndex = index)
        }
    }

    fun submitQuiz() {
        timerJob?.cancel()
        val state = _takingState.value
        val questions = state.questions
        val selected = state.selectedAnswers
        val quiz = state.quiz ?: return
        val student = authRepository.currentUser.value ?: return

        var score = 0
        questions.forEachIndexed { idx, q ->
            val userChoice = selected[idx]
            if (userChoice != null && userChoice == q.correctOptionIndex) {
                score++
            }
        }

        val percentage = if (questions.isNotEmpty()) (score * 100) / questions.size else 0
        val timeSpent = (quiz.timeLimitMinutes * 60) - state.remainingSeconds

        _takingState.value = state.copy(
            isSubmitted = true,
            score = score,
            totalQuestions = questions.size,
            percentage = percentage
        )

        // Save locally to Room
        viewModelScope.launch {
            classroomRepository.submitQuizAttempt(
                quizId = quiz.id,
                studentId = student.id,
                studentName = student.fullName,
                quizTitle = quiz.title,
                subjectName = state.subjectName,
                score = score,
                totalQuestions = questions.size,
                timeSpentSeconds = timeSpent.coerceAtLeast(1),
                answersMap = selected
            )
            loadStudentAttempts()
        }
    }

    fun exitQuiz() {
        timerJob?.cancel()
        _takingState.value = QuizTakingState()
    }
}
