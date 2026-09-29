package com.example.data.repository

import com.example.ai.rag.RagEngine
import com.example.data.local.dao.*
import com.example.data.local.entities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class ClassroomRepository(
    private val subjectDao: SubjectDao,
    private val studyMaterialDao: StudyMaterialDao,
    private val materialChunkDao: MaterialChunkDao,
    private val quizDao: QuizDao,
    private val quizAttemptDao: QuizAttemptDao
) {
    // Subjects
    val allSubjects: Flow<List<SubjectEntity>> = subjectDao.getAllSubjectsFlow()
    val subjectCount: Flow<Int> = subjectDao.getSubjectCountFlow()

    suspend fun getSubjectById(id: Int): SubjectEntity? = subjectDao.getSubjectById(id)

    suspend fun createSubject(subject: SubjectEntity): Long = subjectDao.insertSubject(subject)

    suspend fun updateSubject(subject: SubjectEntity) = subjectDao.updateSubject(subject)

    suspend fun deleteSubject(id: Int) = subjectDao.deleteSubjectById(id)

    // Study Materials & On-Device RAG Processing
    fun getMaterialsBySubject(subjectId: Int): Flow<List<StudyMaterialEntity>> =
        studyMaterialDao.getMaterialsBySubjectFlow(subjectId)

    fun getAllMaterials(): Flow<List<StudyMaterialEntity>> =
        studyMaterialDao.getAllMaterialsFlow()

    val totalMaterialsCount: Flow<Int> = studyMaterialDao.getTotalMaterialsCount()

    suspend fun getMaterialById(id: Int): StudyMaterialEntity? =
        studyMaterialDao.getMaterialById(id)

    suspend fun addStudyMaterial(
        subjectId: Int,
        title: String,
        fileName: String,
        fileType: String,
        rawText: String,
        fileSize: Long = 0L,
        localFilePath: String? = null
    ): StudyMaterialEntity = withContext(Dispatchers.IO) {
        val safeRawText = com.example.ai.rag.RagEngine.cleanPdfText(rawText.ifBlank { "Study Material: $title" })
        val chunks = try {
            com.example.ai.rag.RagEngine.chunkText(safeRawText)
        } catch (t: Throwable) {
            android.util.Log.e("OfflineGenius", "Text chunking error", t)
            emptyList()
        }
        val wordCount = safeRawText.split(Regex("\\s+")).filter { it.isNotBlank() }.size

        val material = StudyMaterialEntity(
            subjectId = subjectId,
            title = title.trim(),
            fileName = fileName.trim(),
            fileType = fileType,
            rawText = safeRawText.trim(),
            chunkCount = chunks.size,
            totalWords = wordCount,
            fileSize = fileSize,
            localFilePath = localFilePath
        )

        val materialId = studyMaterialDao.insertMaterial(material).toInt()

        if (chunks.isNotEmpty()) {
            val chunkEntities = chunks.map { chunk ->
                val tfJson = JSONObject(chunk.termFrequencies).toString()
                MaterialChunkEntity(
                    materialId = materialId,
                    subjectId = subjectId,
                    chunkIndex = chunk.index,
                    content = chunk.text,
                    tokenCount = chunk.wordCount,
                    termsFrequencyJson = tfJson,
                    isIndexOrSyllabus = chunk.isIndexOrSyllabus
                )
            }
            // Insert in safe batches of 50 to avoid SQLite parameter limit on low-memory devices
            chunkEntities.chunked(50).forEach { batch ->
                materialChunkDao.insertChunks(batch)
            }
        }
        material.copy(id = materialId)
    }

    /**
     * Automatically re-processes all study materials stored in the database:
     * - Re-extracts text using the new PdfBox multi-column extractor if file exists on disk
     * - Cleans metadata, strips locale tags, fixes hyphenation/kerning
     * - Re-generates sentence-aware chunks and replaces old corrupted chunks in SQLite
     */
    suspend fun reprocessAllExistingMaterials(context: android.content.Context? = null) = withContext(Dispatchers.IO) {
        try {
            val materials = studyMaterialDao.getAllMaterialsList()
            android.util.Log.d("OfflineGenius", "Auto-reprocessing ${materials.size} existing study materials with upgraded PDF/RAG pipeline...")

            for (material in materials) {
                var textToProcess = material.rawText

                // Try re-extracting from local PDF file with PdfBox if file is present on disk
                if (!material.localFilePath.isNullOrBlank()) {
                    val file = java.io.File(material.localFilePath)
                    if (file.exists() && file.length() > 0) {
                        try {
                            val reExtracted = com.example.util.PdfStorageManager.extractTextFromFile(file, material.fileName, context)
                            if (reExtracted.isNotBlank()) {
                                textToProcess = reExtracted
                            }
                        } catch (e: Throwable) {
                            android.util.Log.w("OfflineGenius", "Failed re-extracting ${file.name}, using cleaned rawText", e)
                        }
                    }
                }

                // Clean with upgraded cleaner
                val cleanedText = com.example.ai.rag.RagEngine.cleanPdfText(textToProcess)
                val validation = com.example.util.PdfStorageManager.validateExtractedText(cleanedText)

                val newChunks = if (validation.isValid || cleanedText.length > 50) {
                    com.example.ai.rag.RagEngine.chunkText(cleanedText)
                } else {
                    emptyList()
                }

                // Delete old corrupted chunks and insert clean chunks
                materialChunkDao.deleteChunksByMaterialId(material.id)
                if (newChunks.isNotEmpty()) {
                    val chunkEntities = newChunks.map { chunk ->
                        val tfJson = JSONObject(chunk.termFrequencies).toString()
                        MaterialChunkEntity(
                            materialId = material.id,
                            subjectId = material.subjectId,
                            chunkIndex = chunk.index,
                            content = chunk.text,
                            tokenCount = chunk.wordCount,
                            termsFrequencyJson = tfJson,
                            isIndexOrSyllabus = chunk.isIndexOrSyllabus
                        )
                    }
                    chunkEntities.chunked(50).forEach { batch ->
                        materialChunkDao.insertChunks(batch)
                    }
                }

                val wordCount = cleanedText.split(Regex("\\s+")).count { it.isNotBlank() }
                studyMaterialDao.updateMaterial(
                    material.copy(
                        rawText = cleanedText,
                        chunkCount = newChunks.size,
                        totalWords = wordCount
                    )
                )
                android.util.Log.i("OfflineGenius", "Successfully reprocessed material ${material.id} '${material.title}': ${newChunks.size} chunks, $wordCount words")
            }
        } catch (e: Throwable) {
            android.util.Log.e("OfflineGenius", "Error re-processing study materials: ${e.message}", e)
        }
    }

    suspend fun renameStudyMaterial(materialId: Int, newTitle: String, newFileName: String) =
        withContext(Dispatchers.IO) {
            studyMaterialDao.renameMaterial(materialId, newTitle.trim(), newFileName.trim())
        }

    suspend fun updateMaterialFile(materialId: Int, filePath: String, fileSize: Long) =
        withContext(Dispatchers.IO) {
            studyMaterialDao.updateFilePathAndSize(materialId, filePath, fileSize)
        }

    suspend fun deleteStudyMaterial(materialId: Int) = withContext(Dispatchers.IO) {
        val existing = studyMaterialDao.getMaterialById(materialId)
        if (existing?.localFilePath != null) {
            try {
                val f = File(existing.localFilePath)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
        materialChunkDao.deleteChunksByMaterialId(materialId)
        studyMaterialDao.deleteMaterialById(materialId)
    }

    suspend fun getChunkCountForSubject(subjectId: Int): Int =
        materialChunkDao.getChunkCountForSubject(subjectId)

    // Quizzes
    fun getQuizzesBySubject(subjectId: Int): Flow<List<QuizEntity>> =
        quizDao.getQuizzesBySubjectFlow(subjectId)

    fun getAllQuizzes(): Flow<List<QuizEntity>> = quizDao.getAllQuizzesFlow()

    suspend fun getQuizById(quizId: Int): QuizEntity? = quizDao.getQuizById(quizId)

    suspend fun getQuestionsForQuiz(quizId: Int): List<QuizQuestionEntity> =
        quizDao.getQuestionsForQuiz(quizId)

    suspend fun createQuiz(quiz: QuizEntity, questions: List<QuizQuestionEntity>): Long =
        quizDao.insertQuizWithQuestions(quiz, questions)

    suspend fun deleteQuiz(quizId: Int) = withContext(Dispatchers.IO) {
        quizDao.deleteQuestionsByQuizId(quizId)
        quizDao.deleteQuizById(quizId)
    }

    // Quiz Attempts
    fun getAttemptsForStudent(studentId: Int): Flow<List<QuizAttemptEntity>> =
        quizAttemptDao.getAttemptsForStudentFlow(studentId)

    fun getAllAttempts(): Flow<List<QuizAttemptEntity>> =
        quizAttemptDao.getAllAttemptsFlow()

    val totalAttemptsCount: Flow<Int> = quizAttemptDao.getTotalAttemptsCountFlow()

    fun getAverageScoreForStudent(studentId: Int): Flow<Double?> =
        quizAttemptDao.getAverageScoreForStudentFlow(studentId)

    suspend fun submitQuizAttempt(
        quizId: Int,
        studentId: Int,
        studentName: String,
        quizTitle: String,
        subjectName: String,
        score: Int,
        totalQuestions: Int,
        timeSpentSeconds: Int,
        answersMap: Map<Int, Int>
    ): Long {
        val percentage = if (totalQuestions > 0) (score * 100) / totalQuestions else 0
        val answersJson = JSONObject(answersMap).toString()

        val attempt = QuizAttemptEntity(
            quizId = quizId,
            studentId = studentId,
            studentName = studentName,
            quizTitle = quizTitle,
            subjectName = subjectName,
            score = score,
            totalQuestions = totalQuestions,
            percentage = percentage,
            timeSpentSeconds = timeSpentSeconds,
            answersJson = answersJson
        )
        return quizAttemptDao.insertAttempt(attempt)
    }
}
