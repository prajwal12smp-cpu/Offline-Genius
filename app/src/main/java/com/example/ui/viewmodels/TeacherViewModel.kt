package com.example.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.llm.InferenceBackend
import com.example.ai.llm.MediaPipeLlmEngine
import com.example.ai.llm.ModelEngineState
import com.example.data.local.entities.*
import com.example.data.repository.AuthRepository
import com.example.data.repository.ClassroomRepository
import com.example.util.PdfStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class DraftQuizQuestion(
    val questionText: String = "",
    val optionA: String = "",
    val optionB: String = "",
    val optionC: String = "",
    val optionD: String = "",
    val correctOptionIndex: Int = 0,
    val explanation: String = ""
)

class TeacherViewModel(
    private val classroomRepository: ClassroomRepository,
    private val authRepository: AuthRepository,
    private val llmEngine: MediaPipeLlmEngine
) : ViewModel() {

    val allSubjects: StateFlow<List<SubjectEntity>> = classroomRepository.allSubjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMaterials: StateFlow<List<StudyMaterialEntity>> = classroomRepository.getAllMaterials()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allStudents: StateFlow<List<UserEntity>> = authRepository.getAllStudentsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allQuizAttempts: StateFlow<List<QuizAttemptEntity>> = classroomRepository.getAllAttempts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _engineState = MutableStateFlow(llmEngine.getEngineState())
    val engineState: StateFlow<ModelEngineState> = _engineState.asStateFlow()

    private val _isProcessingMaterial = MutableStateFlow(false)
    val isProcessingMaterial: StateFlow<Boolean> = _isProcessingMaterial.asStateFlow()

    private val _materialSuccessMsg = MutableStateFlow<String?>(null)
    val materialSuccessMsg: StateFlow<String?> = _materialSuccessMsg.asStateFlow()

    private val _materialErrorMsg = MutableStateFlow<String?>(null)
    val materialErrorMsg: StateFlow<String?> = _materialErrorMsg.asStateFlow()

    val modelStatus: StateFlow<com.example.ai.llm.ModelStatus> = llmEngine.modelStatus
    val importProgress: StateFlow<com.example.ai.llm.ModelImportProgress> = llmEngine.importProgress
    val modelLastError: StateFlow<String?> = llmEngine.lastErrorMessage

    private val _lastCrashLog = MutableStateFlow<String?>(null)
    val lastCrashLog: StateFlow<String?> = _lastCrashLog.asStateFlow()

    private var importJob: kotlinx.coroutines.Job? = null

    fun refreshEngineState() {
        _engineState.value = llmEngine.getEngineState()
    }

    fun refreshCrashLog(context: Context) {
        _lastCrashLog.value = com.example.util.CrashLogger.getCrashLog(context)
    }

    fun exportCrashLog(context: Context) {
        com.example.util.CrashLogger.exportCrashLog(context)
    }

    fun clearCrashLog(context: Context) {
        com.example.util.CrashLogger.clearCrashLog(context)
        _lastCrashLog.value = null
    }

    fun setModelBackend(backend: InferenceBackend) {
        llmEngine.setBackend(backend)
        refreshEngineState()
    }

    fun setMaxTokens(tokens: Int) {
        llmEngine.setMaxTokens(tokens)
        refreshEngineState()
    }

    fun loadModelFromCustomPath(path: String, backend: InferenceBackend) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            llmEngine.loadModelFromPath(path, backend)
            refreshEngineState()
        }
    }

    fun importModel(context: Context, uri: Uri) {
        importJob?.cancel()
        importJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            llmEngine.importModelFile(uri, context.contentResolver)
            refreshEngineState()
            refreshCrashLog(context)
        }
    }

    fun cancelModelImport() {
        importJob?.cancel()
        refreshEngineState()
    }

    fun reloadModel() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            llmEngine.checkAndInitializeModelOnStart()
            refreshEngineState()
        }
    }

    fun deleteModel() {
        llmEngine.deleteInstalledModel()
        refreshEngineState()
    }

    // Material Operations
    fun uploadStudyMaterial(
        context: Context,
        subjectId: Int,
        title: String,
        fileName: String,
        fileType: String,
        content: String
    ) {
        val appContext = context.applicationContext
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isProcessingMaterial.value = true
            _materialSuccessMsg.value = null
            _materialErrorMsg.value = null
            try {
                android.util.Log.d("OfflineGenius", "Starting manual study material creation: title='$title'")
                val cleanFileName = if (fileName.endsWith(".pdf", ignoreCase = true)) fileName else "$fileName.pdf"
                val materialsDir = PdfStorageManager.getMaterialsDirectory(appContext)
                val pdfFile = File(materialsDir, "${System.currentTimeMillis()}_$cleanFileName")

                PdfStorageManager.generatePdfDocument(
                    title = title,
                    rawText = content,
                    fileName = cleanFileName,
                    targetFile = pdfFile
                )

                val material = classroomRepository.addStudyMaterial(
                    subjectId = subjectId,
                    title = title,
                    fileName = cleanFileName,
                    fileType = fileType,
                    rawText = content,
                    fileSize = pdfFile.length(),
                    localFilePath = pdfFile.absolutePath
                )
                android.util.Log.d("OfflineGenius", "Study material saved: id=${material.id}, chunks=${material.chunkCount}")
                _materialSuccessMsg.value = "Saved & indexed '${material.title}' (${PdfStorageManager.formatFileSize(pdfFile.length())}, ${material.chunkCount} RAG chunks)"
            } catch (e: Throwable) {
                android.util.Log.e("OfflineGenius", "Error processing manual study material", e)
                _materialErrorMsg.value = "Couldn't process this material — please check the input."
            } finally {
                _isProcessingMaterial.value = false
            }
        }
    }

    fun uploadStudyMaterialFromUri(
        context: Context,
        subjectId: Int,
        title: String,
        uri: Uri
    ) {
        val appContext = context.applicationContext
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isProcessingMaterial.value = true
            _materialSuccessMsg.value = null
            _materialErrorMsg.value = null
            try {
                android.util.Log.d("OfflineGenius", "Starting background PDF processing for URI: $uri")

                // Step 1: Query display file name safely
                val originalFileName = try {
                    PdfStorageManager.getFileNameFromUri(appContext, uri)
                } catch (t: Throwable) {
                    android.util.Log.w("OfflineGenius", "Failed resolving file name from URI", t)
                    "study_material_${System.currentTimeMillis()}.pdf"
                }
                val finalTitle = title.ifBlank { originalFileName.substringBeforeLast(".") }

                // Step 2: Stream copy the original PDF file to local app storage with bounded streaming buffer
                val (savedFile, fileSize) = try {
                    PdfStorageManager.saveUploadedFile(appContext, uri, originalFileName)
                } catch (t: Throwable) {
                    android.util.Log.e("OfflineGenius", "Failed copying PDF to local app storage: ${t.message}", t)
                    throw IllegalStateException("Couldn't process this PDF — try another file")
                }
                android.util.Log.d("OfflineGenius", "File saved locally: ${savedFile.absolutePath} ($fileSize bytes)")

                // Step 3: Extract text safely from the local copy using PdfBox
                val extractedText = try {
                    PdfStorageManager.extractTextFromFile(savedFile, originalFileName, appContext)
                } catch (t: Throwable) {
                    android.util.Log.e("OfflineGenius", "Text extraction failed for $originalFileName", t)
                    "Study Material: $finalTitle\n\n(Offline curriculum document ready in reader.)"
                }

                // Step 3b: Validate extracted text before chunking/embedding
                val validation = PdfStorageManager.validateExtractedText(extractedText)
                if (!validation.isValid) {
                    android.util.Log.w("OfflineGenius", "PDF validation failed for $originalFileName: ${validation.warningMessage} (ratio=${validation.validWordRatio})")
                    try { savedFile.delete() } catch (_: Exception) {}
                    _materialErrorMsg.value = validation.warningMessage ?: "This PDF's text couldn't be extracted cleanly — try a text-based PDF instead of a scanned one"
                    return@launch
                }

                // Step 4: Chunk text, calculate term frequencies, and store metadata & chunks into Room
                val material = try {
                    classroomRepository.addStudyMaterial(
                        subjectId = subjectId,
                        title = finalTitle,
                        fileName = originalFileName,
                        fileType = "PDF",
                        rawText = extractedText,
                        fileSize = fileSize,
                        localFilePath = savedFile.absolutePath
                    )
                } catch (t: Throwable) {
                    android.util.Log.e("OfflineGenius", "Database insertion/indexing failed for $originalFileName", t)
                    throw IllegalStateException("Couldn't process this PDF — try another file")
                }

                android.util.Log.d("OfflineGenius", "PDF indexed successfully: id=${material.id}, chunks=${material.chunkCount}")
                _materialSuccessMsg.value = "Saved '${material.title}' (${PdfStorageManager.formatFileSize(fileSize)}) & indexed ${material.chunkCount} grounded chunks!"
            } catch (e: Throwable) {
                android.util.Log.e("OfflineGenius", "PDF upload/processing failed: ${e.message}", e)
                _materialErrorMsg.value = "Couldn't process this PDF — try another file"
            } finally {
                _isProcessingMaterial.value = false
            }
        }
    }

    fun setMaterialError(msg: String) {
        _materialErrorMsg.value = msg
    }

    fun clearMaterialError() {
        _materialErrorMsg.value = null
    }

    fun renameMaterial(materialId: Int, newTitle: String, newFileName: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                classroomRepository.renameStudyMaterial(materialId, newTitle, newFileName)
                _materialSuccessMsg.value = "Renamed study material to '$newTitle'"
            } catch (e: Throwable) {
                android.util.Log.e("OfflineGenius", "Failed to rename material", e)
                _materialErrorMsg.value = "Failed to rename: ${e.message}"
            }
        }
    }

    fun downloadMaterial(context: Context, material: StudyMaterialEntity) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = PdfStorageManager.ensureMaterialPdfFile(context, material)
                if (material.localFilePath.isNullOrBlank() || material.fileSize <= 0L) {
                    classroomRepository.updateMaterialFile(material.id, file.absolutePath, file.length())
                }
                val res = PdfStorageManager.saveFileToPublicDownloads(context, file, material.fileName)
                res.onSuccess {
                    _materialSuccessMsg.value = it
                }.onFailure {
                    _materialErrorMsg.value = "Download failed: ${it.message}"
                }
            } catch (e: Throwable) {
                android.util.Log.e("OfflineGenius", "Download material failed", e)
                _materialErrorMsg.value = "Download error: ${e.message}"
            }
        }
    }

    fun clearMaterialMsg() {
        _materialSuccessMsg.value = null
    }

    fun deleteMaterial(materialId: Int) {
        viewModelScope.launch {
            classroomRepository.deleteStudyMaterial(materialId)
            _materialSuccessMsg.value = "Material deleted from disk and database."
        }
    }

    // Quiz Creation
    fun createQuiz(
        subjectId: Int,
        title: String,
        description: String,
        timeLimitMinutes: Int,
        difficulty: String,
        questions: List<DraftQuizQuestion>,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            val quiz = QuizEntity(
                subjectId = subjectId,
                title = title.trim(),
                description = description.trim(),
                timeLimitMinutes = timeLimitMinutes,
                difficulty = difficulty,
                questionCount = questions.size
            )
            val questionEntities = questions.map {
                QuizQuestionEntity(
                    quizId = 0,
                    questionText = it.questionText.trim(),
                    optionA = it.optionA.trim(),
                    optionB = it.optionB.trim(),
                    optionC = it.optionC.trim(),
                    optionD = it.optionD.trim(),
                    correctOptionIndex = it.correctOptionIndex,
                    explanation = it.explanation.trim()
                )
            }
            classroomRepository.createQuiz(quiz, questionEntities)
            onSuccess()
        }
    }

    fun deleteQuiz(quizId: Int) {
        viewModelScope.launch {
            classroomRepository.deleteQuiz(quizId)
        }
    }
}
