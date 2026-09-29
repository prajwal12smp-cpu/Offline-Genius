package com.example.data.repository

import com.example.ai.llm.InferenceProgress
import com.example.ai.llm.MediaPipeLlmEngine
import com.example.ai.rag.CandidateChunk
import com.example.ai.rag.RagEngine
import com.example.ai.rag.RetrievedContextChunk
import com.example.data.local.dao.ChatDao
import com.example.data.local.dao.MaterialChunkDao
import com.example.data.local.dao.StudyMaterialDao
import com.example.data.local.entities.ChatMessageEntity
import com.example.data.local.entities.ConversationEntity
import com.example.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed class ChatStreamState {
    data class Thinking(val step: String) : ChatStreamState()
    data class RetrievedSources(val sources: List<RetrievedContextChunk>) : ChatStreamState()
    data class Generating(val partialText: String, val fullTextSoFar: String, val isFallback: Boolean = false, val generationPath: String = "LLM") : ChatStreamState()
    data class Completed(val messageId: Int, val fullResponse: String, val sources: List<RetrievedContextChunk>, val isFallback: Boolean = false, val generationPath: String = "LLM") : ChatStreamState()
    data class Error(val error: String) : ChatStreamState()
}

class ChatRepository(
    private val chatDao: ChatDao,
    private val materialChunkDao: MaterialChunkDao,
    private val studyMaterialDao: StudyMaterialDao,
    val llmEngine: MediaPipeLlmEngine
) {

    fun isModelReady(): Boolean = llmEngine.isModelReady()

    fun getConversationsForSubject(subjectId: Int, studentId: Int): Flow<List<ConversationEntity>> =
        chatDao.getConversationsBySubjectAndStudentFlow(subjectId, studentId)

    fun getAllConversationsForStudent(studentId: Int): Flow<List<ConversationEntity>> =
        chatDao.getAllConversationsForStudentFlow(studentId)

    fun getMessagesForConversation(conversationId: Int): Flow<List<ChatMessageEntity>> =
        chatDao.getMessagesForConversationFlow(conversationId)

    suspend fun getOrCreateConversation(subjectId: Int, studentId: Int, initialQuestion: String): ConversationEntity {
        val title = if (initialQuestion.length > 40) initialQuestion.take(37) + "..." else initialQuestion
        val entity = ConversationEntity(
            subjectId = subjectId,
            studentId = studentId,
            title = title,
            lastMessage = initialQuestion,
            updatedAt = System.currentTimeMillis()
        )
        val id = chatDao.insertConversation(entity).toInt()
        return entity.copy(id = id)
    }

    suspend fun deleteConversation(conversationId: Int) = withContext(Dispatchers.IO) {
        chatDao.deleteMessagesForConversation(conversationId)
        chatDao.deleteConversationById(conversationId)
    }

    /**
     * Executes the complete on-device RAG + MediaPipe Gemma 3 1B inference pipeline
     */
    fun askQuestionStream(
        conversationId: Int,
        subjectId: Int,
        subjectName: String,
        question: String
    ): Flow<ChatStreamState> = flow {
        // 1. Save student question to Room
        withContext(Dispatchers.IO) {
            chatDao.insertMessage(
                ChatMessageEntity(
                    conversationId = conversationId,
                    role = "user",
                    content = question,
                    retrievedSourcesJson = "[]"
                )
            )
            chatDao.updateConversationLastMessage(
                conversationId = conversationId,
                lastMessage = question,
                updatedAt = System.currentTimeMillis()
            )
        }

        // Checkpoint 1: Synchronous flush before RAG retrieval begins
        CrashLogger.logInferenceStep(
            llmEngine.context,
            "Question received: '$question' for subject '$subjectName' - starting RAG retrieval."
        )

        emit(ChatStreamState.Thinking("Searching local curriculum materials for $subjectName..."))

        // 2. Retrieve candidate chunks strictly for the chosen subject
        val candidateChunks = withContext(Dispatchers.IO) {
            val chunks = materialChunkDao.getChunksForSubject(subjectId)
            android.util.Log.d("OfflineGenius_RAG", "Subject filtering confirmed: Found ${chunks.size} chunks in DB for subjectId=$subjectId ('$subjectName')")
            // Map each chunk with its study material title
            val materialTitles = mutableMapOf<Int, String>()
            chunks.map { chunk ->
                val title = materialTitles.getOrPut(chunk.materialId) {
                    studyMaterialDao.getMaterialById(chunk.materialId)?.title ?: "Course Document"
                }
                val tfMap = mutableMapOf<String, Int>()
                try {
                    val jsonObj = JSONObject(chunk.termsFrequencyJson)
                    val keys = jsonObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        tfMap[k] = jsonObj.optInt(k, 1)
                    }
                } catch (_: Exception) {
                    // fallback tokenization
                    tfMap.putAll(RagEngine.computeTermFrequencies(RagEngine.tokenize(chunk.content)))
                }

                CandidateChunk(
                    chunkId = chunk.id,
                    materialId = chunk.materialId,
                    materialTitle = title,
                    chunkIndex = chunk.chunkIndex,
                    text = chunk.content,
                    wordCount = chunk.tokenCount,
                    termFrequencies = tfMap,
                    isIndexOrSyllabus = chunk.isIndexOrSyllabus
                )
            }
        }

        // 3. Rank via on-device dense semantic embeddings + discriminative BM25 + explanatory pattern re-ranking
        val rankedChunks = RagEngine.rankChunksHybrid(
            query = question,
            candidateChunks = candidateChunks,
            topK = 3
        )

        android.util.Log.d("OfflineGenius_RAG", "=== HYBRID RE-RANKING RESULTS FOR '$question' ===")
        rankedChunks.forEachIndexed { idx, rc ->
            android.util.Log.d("OfflineGenius_RAG", "[$idx] Score=${rc.score} (Semantic=${rc.semanticScore}, Boost=${rc.explanatoryBoost}) Title='${rc.materialTitle}' Section=${rc.chunkIndex + 1} Preview='${rc.text.take(90)}...'")
        }

        // Checkpoint 2: Synchronous flush after retrieval completes, right before building prompt
        val totalContextChars = rankedChunks.sumOf { it.text.length }
        CrashLogger.logInferenceStep(
            llmEngine.context,
            "RAG retrieval complete: ${rankedChunks.size} chunks retrieved, total context chars=$totalContextChars - building prompt."
        )

        emit(ChatStreamState.RetrievedSources(rankedChunks))

        // 4. Run on-device inference via MediaPipe / Gemma 3 1B
        var accumulatedText = ""
        var isFallbackAnswer = false
        var currentPath = "LLM"

        llmEngine.generateRAGResponse(
            subjectName = subjectName,
            question = question,
            retrievedChunks = rankedChunks
        ).collect { progress ->
            when (progress) {
                is InferenceProgress.Thinking -> {
                    emit(ChatStreamState.Thinking(progress.step))
                }
                is InferenceProgress.Generating -> {
                    accumulatedText = progress.fullTextSoFar
                    isFallbackAnswer = progress.isFallback
                    currentPath = progress.generationPath
                    emit(ChatStreamState.Generating(progress.partialText, progress.fullTextSoFar, isFallbackAnswer, currentPath))
                }
                is InferenceProgress.Completed -> {
                    accumulatedText = progress.fullText
                    isFallbackAnswer = progress.isFallback
                    currentPath = progress.generationPath
                }
                is InferenceProgress.Error -> {
                    emit(ChatStreamState.Error("${progress.message}. ${progress.suggestion}"))
                }
            }
        }

        // 5. Store completed response in Room with rich citations (including text & score for debug mode)
        val sourcesArray = JSONArray()
        for (source in rankedChunks) {
            val obj = JSONObject()
            obj.put("chunkId", source.chunkId)
            obj.put("materialId", source.materialId)
            obj.put("title", source.materialTitle)
            obj.put("section", source.chunkIndex + 1)
            obj.put("score", source.score)
            obj.put("text", source.text)
            val matchedTermsArray = JSONArray()
            source.matchedTerms.forEach { matchedTermsArray.put(it) }
            obj.put("matchedTerms", matchedTermsArray)
            sourcesArray.put(obj)
        }

        val messageId = withContext(Dispatchers.IO) {
            val msgId = chatDao.insertMessage(
                ChatMessageEntity(
                    conversationId = conversationId,
                    role = "assistant",
                    content = accumulatedText,
                    retrievedSourcesJson = sourcesArray.toString(),
                    isFallback = isFallbackAnswer,
                    generationPath = currentPath
                )
            ).toInt()

            chatDao.updateConversationLastMessage(
                conversationId = conversationId,
                lastMessage = if (accumulatedText.length > 60) accumulatedText.take(57) + "..." else accumulatedText,
                updatedAt = System.currentTimeMillis()
            )
            msgId
        }

        emit(ChatStreamState.Completed(messageId, accumulatedText, rankedChunks, isFallbackAnswer, currentPath))
    }
}
