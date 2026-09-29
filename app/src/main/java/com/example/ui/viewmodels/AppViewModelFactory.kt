package com.example.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.ai.llm.MediaPipeLlmEngine
import com.example.data.local.AppDatabase
import com.example.data.repository.AuthRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.ClassroomRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    private val database by lazy { AppDatabase.getDatabase(context) }
    val llmEngine: MediaPipeLlmEngine
        get() = MediaPipeLlmEngine.getInstance(context)

    val authRepository by lazy { AuthRepository(database.userDao()) }
    val classroomRepository by lazy {
        ClassroomRepository(
            database.subjectDao(),
            database.studyMaterialDao(),
            database.materialChunkDao(),
            database.quizDao(),
            database.quizAttemptDao()
        )
    }
    val chatRepository by lazy {
        ChatRepository(
            database.chatDao(),
            database.materialChunkDao(),
            database.studyMaterialDao(),
            llmEngine
        )
    }

    init {
        com.example.util.PdfStorageManager.initPdfBox(context)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            classroomRepository.reprocessAllExistingMaterials(context)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(AuthViewModel::class.java) -> {
                AuthViewModel(authRepository) as T
            }
            modelClass.isAssignableFrom(SubjectViewModel::class.java) -> {
                SubjectViewModel(classroomRepository) as T
            }
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> {
                ChatViewModel(chatRepository, classroomRepository, authRepository) as T
            }
            modelClass.isAssignableFrom(QuizViewModel::class.java) -> {
                QuizViewModel(classroomRepository, authRepository) as T
            }
            modelClass.isAssignableFrom(StudentMaterialViewModel::class.java) -> {
                StudentMaterialViewModel(classroomRepository) as T
            }
            modelClass.isAssignableFrom(TeacherViewModel::class.java) -> {
                TeacherViewModel(classroomRepository, authRepository, llmEngine) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
