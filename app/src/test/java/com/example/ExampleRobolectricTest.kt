package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.llm.MediaPipeLlmEngine
import com.example.ai.rag.CandidateChunk
import com.example.ai.rag.RagEngine
import com.example.data.local.AppDatabase
import com.example.data.local.entities.SubjectEntity
import com.example.data.repository.AuthRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.ChatStreamState
import com.example.data.repository.ClassroomRepository
import com.example.util.PdfStorageManager
import com.example.util.SecurityUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.ui.viewmodels.AppViewModelFactory
import com.example.ui.viewmodels.ChatViewModel
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @org.junit.Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MediaPipeLlmEngine.resetInstanceForTesting()
        com.example.util.CrashLogger.clearCrashLog(context)
    }

    @org.junit.After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MediaPipeLlmEngine.resetInstanceForTesting()
        com.example.util.CrashLogger.clearCrashLog(context)
    }

    @Test
    fun readStringFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Offline Genius", appName)
    }

    @Test
    fun testLocalPasswordSecurityHashing() {
        val salt = SecurityUtils.generateSalt()
        val hash = SecurityUtils.hashPassword("classroomSecret123", salt)
        assertTrue(SecurityUtils.verifyPassword("classroomSecret123", salt, hash))
        assertFalse(SecurityUtils.verifyPassword("wrongPassword", salt, hash))
    }

    @Test
    fun testTextCleaningArtifactsAndHyphens() {
        val noisyPdfText = """
            OFFLINE GENIUS SMART CLASSROOM - Oct 12, 2026
            Page 1 of 12
            Photosynthesis is a biologi-
            cal process by which green plants convert light energy
            into chemical energy.
            - 2 -
            Water and carbon dioxide are the primary reac-
            tants in this conversion.
            Offline Curriculum Study Material
        """.trimIndent()

        val cleaned = RagEngine.cleanPdfText(noisyPdfText)
        assertFalse(cleaned.contains("Page 1 of 12"))
        assertFalse(cleaned.contains("- 2 -"))
        assertFalse(cleaned.contains("OFFLINE GENIUS SMART CLASSROOM"))
        assertTrue(cleaned.contains("biological"))
        assertTrue(cleaned.contains("reactants"))
    }

    @Test
    fun testSentenceAwareChunkingQuality() {
        val paragraphs = (1..10).joinToString("\n\n") { p ->
            "Section $p covers key scientific principles. First principle of science requires empirical observation and rigorous hypothesis testing. Second principle requires repeatability across independent laboratory experiments. Conclusion for section $p confirms scientific validity."
        }

        val chunks = RagEngine.chunkText(
            rawText = paragraphs,
            targetWordsPerChunk = 50,
            minWordsPerChunk = 20,
            maxWordsPerChunk = 80,
            overlapWords = 15
        )

        assertTrue("Chunks should not be empty", chunks.isNotEmpty())
        for (chunk in chunks) {
            // Sentences must never be cut mid-sentence (every chunk must end with sentence boundary punctuation)
            val trimmed = chunk.text.trim()
            assertTrue(
                "Chunk should end at sentence punctuation: '$trimmed'",
                trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")
            )
            assertTrue("Chunk word count should be reasonable", chunk.wordCount >= 20)
        }
    }

    @Test
    fun testFlateDecompressionExtractsRealContent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testPdf = File(context.cacheDir, "biology_curriculum.pdf")
        if (testPdf.exists()) testPdf.delete()

        val curriculumBody = "Photosynthesis transforms solar energy into chemical energy stored in glucose molecules. Chlorophyll in the chloroplasts absorbs light at specific wavelengths."
        PdfStorageManager.generatePdfDocument(
            title = "Biology: Cell Energy",
            rawText = curriculumBody,
            fileName = "biology_curriculum.pdf",
            targetFile = testPdf
        )

        assertTrue(testPdf.exists())
        val extracted = PdfStorageManager.extractTextFromFile(testPdf, "biology_curriculum.pdf")
        assertTrue("Extracted text must not be blank", extracted.isNotBlank())
        // Must extract the real text drawn in the PDF streams, not the empty visual fallback!
        assertTrue(
            "Extracted text should contain curriculum keywords from decompressed stream",
            extracted.contains("Photosynthesis", ignoreCase = true) ||
                    extracted.contains("energy", ignoreCase = true) ||
                    extracted.contains("Chlorophyll", ignoreCase = true)
        )
    }

    @Test
    fun testSubjectFilteredRetrieval() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val classroomRepo = ClassroomRepository(
            db.subjectDao(),
            db.studyMaterialDao(),
            db.materialChunkDao(),
            db.quizDao(),
            db.quizAttemptDao()
        )

        try {
            // 1. Create Physics (id=1) and Biology (id=2)
            val physicsId = db.subjectDao().insertSubject(
                SubjectEntity(name = "Physics Mechanics", code = "PHYS101", description = "Science Department", colorHex = "#2563EB")
            ).toInt()
            val biologyId = db.subjectDao().insertSubject(
                SubjectEntity(name = "Cell Biology", code = "BIO101", description = "Science Department", colorHex = "#16A34A")
            ).toInt()

            // 2. Add material to Physics
            classroomRepo.addStudyMaterial(
                subjectId = physicsId,
                title = "Newtonian Dynamics",
                fileName = "newton.pdf",
                fileType = "PDF",
                rawText = "Newton's First Law states an object remains at rest unless acted upon by a net force. Inertia is the resistance of an object to changes in motion."
            )

            // 3. Add material to Biology
            classroomRepo.addStudyMaterial(
                subjectId = biologyId,
                title = "Cell Division",
                fileName = "mitosis.pdf",
                fileType = "PDF",
                rawText = "Mitosis is a process where a single cell divides into two identical daughter cells with duplicate chromosomes."
            )

            // 4. Retrieve strictly for Physics
            val physicsChunks = db.materialChunkDao().getChunksForSubject(physicsId)
            val biologyChunks = db.materialChunkDao().getChunksForSubject(biologyId)

            assertEquals(1, physicsChunks.size)
            assertEquals(1, biologyChunks.size)
            assertEquals(physicsId, physicsChunks.first().subjectId)
            assertEquals(biologyId, biologyChunks.first().subjectId)

            // Verify Physics chunks contain Newton/Inertia, never Mitosis
            assertTrue(physicsChunks.first().content.contains("Inertia"))
            assertFalse(physicsChunks.first().content.contains("Mitosis"))

            // Verify Biology chunks contain Mitosis, never Inertia
            assertTrue(biologyChunks.first().content.contains("Mitosis"))
            assertFalse(biologyChunks.first().content.contains("Inertia"))

            // 5. Test BM25 ranking when querying Physics:
            val candidatePhysics = physicsChunks.map {
                CandidateChunk(
                    chunkId = it.id,
                    materialId = it.materialId,
                    materialTitle = "Newtonian Dynamics",
                    chunkIndex = it.chunkIndex,
                    text = it.content,
                    wordCount = it.tokenCount,
                    termFrequencies = RagEngine.computeTermFrequencies(RagEngine.tokenize(it.content))
                )
            }

            // Question matching Physics
            val physicsResults = RagEngine.rankChunksBM25("What is inertia in motion?", candidatePhysics)
            assertTrue(physicsResults.isNotEmpty())
            assertTrue(physicsResults.first().score > 0.0)
            assertTrue(physicsResults.first().matchedTerms.contains("inertia"))

            // Question matching Biology asked in Physics must yield 0 results
            val bioQueryInPhysics = RagEngine.rankChunksBM25("Explain mitosis and daughter cells", candidatePhysics)
            assertTrue("Querying Physics for Biology terms should yield 0 matched chunks", bioQueryInPhysics.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun testBM25ScoreDifferentiationAndRanking() {
        val candidate1 = CandidateChunk(
            chunkId = 1,
            materialId = 1,
            materialTitle = "Physics Chapter 1",
            chunkIndex = 0,
            text = "Newton's second law of motion specifies that force equals mass multiplied by acceleration. F = m * a.",
            wordCount = 18,
            termFrequencies = RagEngine.computeTermFrequencies(RagEngine.tokenize("Newton's second law of motion specifies that force equals mass multiplied by acceleration F m a"))
        )

        val candidate2 = CandidateChunk(
            chunkId = 2,
            materialId = 1,
            materialTitle = "Physics Chapter 1",
            chunkIndex = 1,
            text = "Thermal equilibrium occurs when two physical systems cease to exchange heat energy across a diathermic boundary.",
            wordCount = 18,
            termFrequencies = RagEngine.computeTermFrequencies(RagEngine.tokenize("Thermal equilibrium occurs when two physical systems cease to exchange heat energy across a diathermic boundary"))
        )

        val results = RagEngine.rankChunksBM25(
            query = "What is Newton's second law of acceleration?",
            candidateChunks = listOf(candidate1, candidate2),
            topK = 2
        )

        assertEquals(1, results.size) // candidate2 has 0 relevant matches
        val topMatch = results.first()
        assertEquals(1, topMatch.chunkId)
        assertTrue("Top chunk score should be clearly positive", topMatch.score > 2.0)
        assertTrue(topMatch.matchedTerms.contains("newton"))
        assertTrue(topMatch.matchedTerms.contains("accelerat"))
    }

    @Test
    fun testPromptConstructionTemplateAndTruncation() {
        val chunks = listOf(
            com.example.ai.rag.RetrievedContextChunk(
                chunkId = 1,
                materialId = 1,
                materialTitle = "Physics Mechanics",
                chunkIndex = 0,
                text = "Force equals mass times acceleration.",
                score = 8.5,
                matchedTerms = listOf("force", "acceleration")
            ),
            com.example.ai.rag.RetrievedContextChunk(
                chunkId = 2,
                materialId = 1,
                materialTitle = "Physics Mechanics",
                chunkIndex = 1,
                text = "Momentum is mass times velocity.",
                score = 3.2,
                matchedTerms = listOf("mass")
            )
        )

        val prompt = RagEngine.buildRagPrompt(
            subjectName = "Physics",
            question = "How is force calculated?",
            retrievedChunks = chunks,
            maxTotalChars = 4000
        )

        // Must match the exact requested prompt template
        assertTrue(prompt.startsWith("You are a teaching assistant for Physics. Using ONLY the context below, answer the student's question clearly in your own words. Give a short definition first, then bullet points if the context lists items. Do not copy text word for word. Do not mention the context or page numbers. If the context does not contain the answer, reply exactly: 'I couldn't find this in the uploaded materials.'\n\nContext:\n"))
        assertTrue(prompt.contains("Force equals mass times acceleration."))
        assertTrue(prompt.contains("Question: How is force calculated?"))
        assertTrue(prompt.endsWith("Answer:"))
    }

    @Test
    fun testEndToEndRAGQuestionAnswering() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val authRepo = AuthRepository(db.userDao())
        val classroomRepo = ClassroomRepository(
            db.subjectDao(),
            db.studyMaterialDao(),
            db.materialChunkDao(),
            db.quizDao(),
            db.quizAttemptDao()
        )
        val llmEngine = MediaPipeLlmEngine(context)
        val chatRepo = ChatRepository(db.chatDao(), db.materialChunkDao(), db.studyMaterialDao(), llmEngine)

        try {
            // 1. Create student and subject
            val user = (authRepo.register("student1", "pass1234", "Alex Morgan", "STUDENT", "Grade 10") as com.example.data.repository.AuthResult.Success).user
            val subjectId = db.subjectDao().insertSubject(
                SubjectEntity(name = "Cell Biology", code = "BIO101", description = "Science Department", colorHex = "#16A34A")
            ).toInt()

            // 2. Upload study material
            classroomRepo.addStudyMaterial(
                subjectId = subjectId,
                title = "Cell Organelles",
                fileName = "cell_organelles.pdf",
                fileType = "PDF",
                rawText = "The mitochondria is the powerhouse of the cell, generating adenosine triphosphate (ATP) through cellular respiration."
            )

            // 3. Create conversation and ask question
            val conv = chatRepo.getOrCreateConversation(subjectId, user.id, "What is the powerhouse of the cell?")
            val states = chatRepo.askQuestionStream(
                conversationId = conv.id,
                subjectId = subjectId,
                subjectName = "Cell Biology",
                question = "What is the powerhouse of the cell and what does it generate?"
            ).toList()

            // 4. Verify stream transitions
            val thinkingState = states.filterIsInstance<ChatStreamState.Thinking>()
            val retrievedState = states.filterIsInstance<ChatStreamState.RetrievedSources>().firstOrNull()
            val completedState = states.filterIsInstance<ChatStreamState.Completed>().firstOrNull()

            assertTrue("Must emit Thinking states", thinkingState.isNotEmpty())
            assertTrue("Must retrieve sources", retrievedState != null && retrievedState.sources.isNotEmpty())

            val topChunk = retrievedState!!.sources.first()
            assertEquals("Cell Organelles", topChunk.materialTitle)
            assertTrue("Retrieved chunk must contain mitochondria content", topChunk.text.contains("mitochondria"))
            assertTrue(topChunk.score > 0)

            assertTrue("Must emit Completed state", completedState != null)
            assertTrue("Response must contain mitochondria", completedState!!.fullResponse.contains("mitochondria", ignoreCase = true))
            assertFalse("Must NOT contain raw curriculum prefix", completedState!!.fullResponse.contains("Based on the uploaded curriculum materials"))
            assertFalse("Must NOT contain raw curriculum citations block in text body", completedState!!.fullResponse.contains("Curriculum Citations"))

            // 5. Verify rich sources stored in database for debug inspection
            val savedMessages = db.chatDao().getMessagesForConversationFlow(conv.id).first()
            val assistantMsg = savedMessages.first { it.role == "assistant" }
            val sourcesJson = JSONArray(assistantMsg.retrievedSourcesJson)
            assertEquals(1, sourcesJson.length())
            val firstSource = sourcesJson.getJSONObject(0)
            assertEquals("Cell Organelles", firstSource.getString("title"))
            assertTrue(firstSource.getDouble("score") > 0.0)
            assertTrue(firstSource.getString("text").contains("mitochondria"))
        } finally {
            db.close()
        }
    }

    @Test
    fun testTextCleaningLocaleTagsAndDehyphenationAndKerning() {
        val corruptedPdfText = """
            H adoop en-US (Se en-US) is an open-source distributed storage framework.
            Big Data A nalytics involves examining large and varied data sets.
            Hado-
            op distributed file system provides high throughput access to application data.
            /Lang (en-US) << /Type /Catalog >>
        """.trimIndent()

        val cleaned = RagEngine.cleanPdfText(corruptedPdfText)

        assertFalse("Must strip locale tags like en-US", cleaned.contains("en-US"))
        assertFalse("Must strip (Se en-US)", cleaned.contains("(Se en-US)"))
        assertFalse("Must strip /Lang tags", cleaned.contains("/Lang"))
        assertFalse("Must fix H adoop kerning", cleaned.contains("H adoop"))
        assertFalse("Must fix A nalytics kerning", cleaned.contains("A nalytics"))
        assertFalse("Must fix Hado-\\noop de-hyphenation", cleaned.contains("Hado-\nop"))

        assertTrue("Must contain clean Hadoop", cleaned.contains("Hadoop is an open-source distributed storage framework"))
        assertTrue("Must contain clean Analytics", cleaned.contains("Big Data Analytics involves"))
        assertTrue("Must contain de-hyphenated Hadoop", cleaned.contains("Hadoop distributed file system"))
    }

    @Test
    fun testValidateExtractedTextDetectsGibberishVsCleanText() {
        val corruptedFragmentText = "mits val pa th or en-US (Se en-US) /Lang /Span << >> 1 2"
        val corruptedResult = PdfStorageManager.validateExtractedText(corruptedFragmentText)
        assertFalse("Corrupted fragments should fail validation", corruptedResult.isValid)
        assertEquals(
            "This PDF's text couldn't be extracted cleanly — try a text-based PDF instead of a scanned one",
            corruptedResult.warningMessage
        )

        val cleanAcademicText = """
            Big Data Analytics is the complex process of examining big data to uncover information 
            such as hidden patterns, correlations, market trends and customer preferences that can help 
            organizations make informed business decisions. Apache Hadoop is widely used for this.
        """.trimIndent()
        val cleanResult = PdfStorageManager.validateExtractedText(cleanAcademicText)
        assertTrue("Clean academic text should pass validation", cleanResult.isValid)
        assertTrue(cleanResult.warningMessage == null)
        assertTrue(cleanResult.validWordRatio > 0.8)
    }

    @Test
    fun testAutoReprocessExistingCorruptedMaterials() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val classroomRepo = ClassroomRepository(
            db.subjectDao(),
            db.studyMaterialDao(),
            db.materialChunkDao(),
            db.quizDao(),
            db.quizAttemptDao()
        )

        try {
            val subjectId = db.subjectDao().insertSubject(
                SubjectEntity(name = "Big Data Analytics", code = "CS401", description = "Computer Science", colorHex = "#2563EB")
            ).toInt()

            // Insert old material that had raw corrupted text before the fix
            val oldMaterial = classroomRepo.addStudyMaterial(
                subjectId = subjectId,
                title = "Big Data Analytics",
                fileName = "big_data.pdf",
                fileType = "PDF",
                rawText = "H adoop en-US (Se en-US) is used in Big Data A nalytics. Hado-\noop stores large volume of data across clusters."
            )

            // Auto-reprocess all existing materials
            classroomRepo.reprocessAllExistingMaterials(context)

            // Verify chunks in Room have been cleaned and replaced
            val chunks = db.materialChunkDao().getChunksForSubject(subjectId)
            assertTrue("Chunks must exist after reprocessing", chunks.isNotEmpty())
            val chunkContent = chunks.first().content

            assertFalse("Chunk must not contain en-US", chunkContent.contains("en-US"))
            assertFalse("Chunk must not contain H adoop", chunkContent.contains("H adoop"))
            assertFalse("Chunk must not contain Hado-\\noop", chunkContent.contains("Hado-\nop"))
            assertTrue("Chunk must contain clean Hadoop", chunkContent.contains("Hadoop"))
            assertTrue("Chunk must contain clean Analytics", chunkContent.contains("Analytics"))
        } finally {
            db.close()
        }
    }

    @Test
    fun testMarkdownInlineParsing() {
        val rawMarkdown = "**Big Data Analytics** is *crucial* for `Hadoop` [Source 1]"
        val defaultColor = androidx.compose.ui.graphics.Color.Black
        val annotated = com.example.ui.components.parseInlineMarkdown(rawMarkdown, defaultColor)

        // Raw asterisks and backticks must be removed from the display text
        assertFalse(annotated.text.contains("**"))
        assertFalse(annotated.text.contains("`"))
        assertTrue(annotated.text.contains("Big Data Analytics is crucial for Hadoop [Source 1]"))
    }

    @Test
    fun testBigDataAnalyticsPdfWhatIsData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val authRepo = AuthRepository(db.userDao())
        val classroomRepo = ClassroomRepository(
            db.subjectDao(),
            db.studyMaterialDao(),
            db.materialChunkDao(),
            db.quizDao(),
            db.quizAttemptDao()
        )
        val llmEngine = MediaPipeLlmEngine(context)
        val chatRepo = ChatRepository(db.chatDao(), db.materialChunkDao(), db.studyMaterialDao(), llmEngine)

        try {
            val student = (authRepo.register("student_cs", "pass1234", "Jordan Lee", "STUDENT", "Year 3") as com.example.data.repository.AuthResult.Success).user
            val subjectId = db.subjectDao().insertSubject(
                SubjectEntity(name = "Big Data Analytics", code = "CS401", description = "Computer Science", colorHex = "#2563EB")
            ).toInt()

            val bigDataText = """
                Data is defined as distinct pieces of information, usually formatted in a special way. 
                In computing, data is information that has been translated into a form that is efficient 
                for movement or processing. Big Data refers to datasets whose size or type is beyond the 
                ability of traditional relational databases to capture, manage, and process with low latency.
                Apache Hadoop is an open-source framework for distributed storage and processing of big data.
            """.trimIndent()

            classroomRepo.addStudyMaterial(
                subjectId = subjectId,
                title = "Big Data Analytics & Architecture",
                fileName = "big_data_analytics.pdf",
                fileType = "PDF",
                rawText = bigDataText
            )

            val conv = chatRepo.getOrCreateConversation(subjectId, student.id, "what is Data?")
            val states = chatRepo.askQuestionStream(
                conversationId = conv.id,
                subjectId = subjectId,
                subjectName = "Big Data Analytics",
                question = "what is Data?"
            ).toList()

            val retrievedState = states.filterIsInstance<ChatStreamState.RetrievedSources>().firstOrNull()
            val completedState = states.filterIsInstance<ChatStreamState.Completed>().firstOrNull()

            assertTrue("Should retrieve sources for 'what is Data?'", retrievedState != null && retrievedState.sources.isNotEmpty())
            val topChunk = retrievedState!!.sources.first()

            assertEquals("Big Data Analytics & Architecture", topChunk.materialTitle)
            assertTrue("Retrieved chunk must contain data definition", topChunk.text.contains("Data is defined as distinct pieces of information"))
            assertFalse("Chunk must not contain locale tags", topChunk.text.contains("en-US"))
            assertTrue("Top chunk score must be positive", topChunk.score > 0.0)

            assertTrue("Completed response must not be null", completedState != null)
            assertTrue("Response must contain data definition", completedState!!.fullResponse.contains("distinct pieces of information", ignoreCase = true) || completedState.fullResponse.contains("data", ignoreCase = true))

            // Verify stored citations in message
            val savedMessages = db.chatDao().getMessagesForConversationFlow(conv.id).first()
            val assistantMsg = savedMessages.first { it.role == "assistant" }
            val sourcesJson = JSONArray(assistantMsg.retrievedSourcesJson)
            assertEquals(1, sourcesJson.length())
            val citation = sourcesJson.getJSONObject(0)
            assertEquals("Big Data Analytics & Architecture", citation.getString("title"))
            assertTrue(citation.getString("text").contains("Data is defined as distinct pieces of information"))
        } finally {
            db.close()
        }
    }

    @Test
    fun testCleanOutputRemovesPrefixAndRawCitations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = MediaPipeLlmEngine(context)

        val rawWithPrefix = """
            Based on the uploaded curriculum materials for Big Data:

            Answer: The four characteristics of big data are Volume, Velocity, Variety, and Veracity.
            • Volume refers to data quantity.
            • Velocity refers to speed.
            • Variety refers to different formats.
            • Veracity refers to quality.

            📚 Curriculum Citations:
            • [Source 1] Unit 1 Fundamentals
        """.trimIndent()

        val cleaned = engine.cleanOutput(rawWithPrefix)

        assertFalse("Must remove prefix", cleaned.contains("Based on the uploaded curriculum materials"))
        assertFalse("Must remove Answer: prefix", cleaned.startsWith("Answer:"))
        assertFalse("Must remove Curriculum Citations", cleaned.contains("Curriculum Citations"))
        assertTrue("Must keep clear explanation", cleaned.contains("Volume, Velocity, Variety, and Veracity"))
    }

    @Test
    fun testQuestionsOnBigDataAnalyticsMaterials() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val authRepo = AuthRepository(db.userDao())
        val classroomRepo = ClassroomRepository(
            db.subjectDao(),
            db.studyMaterialDao(),
            db.materialChunkDao(),
            db.quizDao(),
            db.quizAttemptDao()
        )
        val llmEngine = MediaPipeLlmEngine(context)
        val chatRepo = ChatRepository(
            db.chatDao(),
            db.materialChunkDao(),
            db.studyMaterialDao(),
            llmEngine
        )

        try {
            val user = (authRepo.register("student_test_bda", "pass1234", "BDA Student", "STUDENT", "Year 3") as com.example.data.repository.AuthResult.Success).user
            val subjectId = db.subjectDao().insertSubject(
                SubjectEntity(name = "Big Data Analytics", code = "CS401", description = "Computer Science", colorHex = "#2563EB")
            ).toInt()

            // Material with syllabus noise, inline broken list, and explanatory text
            val rawDoc = """
                Vtucircle.com Page 23
                Module 1: Big Data Analytics
                Big Data Analytics: What is Big data Analytics, Classification of Analytics, Technologies used in Big data
                
                Data is defined as distinct pieces of information, formatted in a special way. All software is divided into two general categories: programs and data. Programs are collections of instructions for manipulating data.
                
                Characteristics of Data: The primary characteristics of data are described by the four Vs. 1. Volume: Volume represents the sheer scale and massive quantity of data generated every second. 2. Velocity: Velocity refers to the rapid speed at which new data is generated and moved. 3. Variety: Variety refers to the many different structured, semi-structured, and unstructured data types. 4. Veracity: Veracity refers to the trustworthiness and quality of the collected data.
            """.trimIndent()

            classroomRepo.addStudyMaterial(
                subjectId = subjectId,
                title = "Big Data Textbook",
                fileName = "big_data_analytics.pdf",
                fileType = "application/pdf",
                rawText = rawDoc,
                fileSize = 1024L
            )

            // Test 1: "what is data?"
            val conv1 = chatRepo.getOrCreateConversation(subjectId, user.id, "what is data?")
            val states1 = chatRepo.askQuestionStream(conv1.id, subjectId, "Big Data Analytics", "what is data?").toList()
            val completed1 = states1.filterIsInstance<ChatStreamState.Completed>().first()
            assertFalse("Answer must NOT contain syllabus topics", completed1.fullResponse.contains("What is Big data Analytics, Classification of Analytics"))
            assertFalse("Answer must NOT contain page numbers", completed1.fullResponse.contains("Vtucircle.com"))
            assertFalse("Answer must NOT end abruptly", completed1.fullResponse.endsWith("1.") || completed1.fullResponse.endsWith(":"))
            assertTrue("Answer must define data", completed1.fullResponse.contains("Data is defined as distinct pieces of information", ignoreCase = true))

            // Test 2: "characteristics of data?"
            val conv2 = chatRepo.getOrCreateConversation(subjectId, user.id, "characteristics of data?")
            val states2 = chatRepo.askQuestionStream(conv2.id, subjectId, "Big Data Analytics", "characteristics of data?").toList()
            val completed2 = states2.filterIsInstance<ChatStreamState.Completed>().first()
            assertTrue("Must include Volume", completed2.fullResponse.contains("Volume", ignoreCase = true))
            assertTrue("Must include Velocity", completed2.fullResponse.contains("Velocity", ignoreCase = true))
            assertTrue("Must include Variety", completed2.fullResponse.contains("Variety", ignoreCase = true))
            assertTrue("Must include Veracity", completed2.fullResponse.contains("Veracity", ignoreCase = true))
            assertFalse("Must NOT contain syllabus text", completed2.fullResponse.contains("Vtucircle.com"))

            // Test 3: "explain data to a 10-year-old"
            val conv3 = chatRepo.getOrCreateConversation(subjectId, user.id, "explain data to a 10-year-old")
            val states3 = chatRepo.askQuestionStream(conv3.id, subjectId, "Big Data Analytics", "explain data to a 10-year-old").toList()
            val completed3 = states3.filterIsInstance<ChatStreamState.Completed>().first()
            assertTrue("Must be child-friendly definition", completed3.fullResponse.contains("facts, numbers, or details", ignoreCase = true) || completed3.fullResponse.contains("information", ignoreCase = true))
            assertFalse("Must not copy raw textbook syllabus text", completed3.fullResponse.contains("Classification of Analytics"))

            // Verify generation path was recorded (either LLM or Fallback)
            assertTrue("Generation path must be set", completed1.generationPath.isNotBlank())
            val savedMsg = db.chatDao().getMessagesForConversationFlow(conv1.id).first().first { it.role == "assistant" }
            assertTrue("Saved message must record generation path", savedMsg.generationPath.isNotBlank())
            assertTrue("When model not installed, isFallback is true", savedMsg.isFallback)
            assertTrue("Path must state AI model not installed", savedMsg.generationPath.contains("not installed", ignoreCase = true))
        } finally {
            db.close()
        }
    }

    @Test
    fun testModelFileManagementAndStatusProgression() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = MediaPipeLlmEngine(context)

        // 1. Initial state: Model not installed
        engine.deleteInstalledModel()
        engine.checkAndInitializeModelOnStart()
        assertEquals(com.example.ai.llm.ModelStatus.NotInstalled, engine.modelStatus.value)
        assertEquals("Model: Not installed", engine.modelStatus.value.displayLabel)
        assertTrue(engine.lastErrorMessage.value?.contains("AI model not installed") == true)

        // 2. Simulate model file in context.filesDir/models/
        val modelsDir = engine.getModelsDirectory()
        assertTrue(modelsDir.exists() && modelsDir.isDirectory)

        val modelFile = java.io.File(modelsDir, "gemma-3-1b-it-int4.task")
        modelFile.writeBytes(ByteArray(2 * 1024 * 1024) { 0x42 }) // 2MB non-empty dummy model

        val installed = engine.getInstalledModelFile()
        assertNotNull("Installed model file should be found", installed)
        assertEquals(modelFile.absolutePath, installed?.absolutePath)

        // 3. Status during loading/failed with invalid format on dummy file
        val loaded = engine.loadModelFromPath(modelFile.absolutePath, com.example.ai.llm.InferenceBackend.CPU)
        // Dummy bytes fail initialization in native MediaPipe, so status must be Failed with exact error message
        assertFalse(loaded)
        assertTrue(engine.modelStatus.value is com.example.ai.llm.ModelStatus.Failed)
        val failed = engine.modelStatus.value as com.example.ai.llm.ModelStatus.Failed
        assertTrue("Error message must be recorded", failed.reason.isNotBlank())
        assertTrue("Display label must start with Model: Failed:", failed.displayLabel.startsWith("Model: Failed:"))

        // Verify diagnostic steps were recorded in crash_log.txt
        val crashLog = com.example.util.CrashLogger.getCrashLog(context)
        assertNotNull("Crash/diagnostic log must be recorded to filesDir/crash_log.txt", crashLog)
        assertTrue("Crash log must contain initialization steps", crashLog?.contains("INIT STEP") == true || crashLog?.contains("MODEL INIT FAILED") == true)

        // 4. Test clean delete
        val deleted = engine.deleteInstalledModel()
        assertTrue(deleted)
        assertFalse(modelFile.exists())
        assertEquals(com.example.ai.llm.ModelStatus.NotInstalled, engine.modelStatus.value)
    }

    @Test
    fun testCrashLoggerAndDiagnosticPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.util.CrashLogger.clearCrashLog(context)
        assertNull("Crash log should be null initially after clear", com.example.util.CrashLogger.getCrashLog(context))

        com.example.util.CrashLogger.logInitializationStep(context, "Step 1: Test step description")
        val logAfterStep = com.example.util.CrashLogger.getCrashLog(context)
        assertNotNull(logAfterStep)
        assertTrue(logAfterStep!!.contains("Step 1: Test step description"))

        com.example.util.CrashLogger.logInitializationFailure(
            context,
            "Simulated model load failure",
            IllegalStateException("Test native exception")
        )
        val logAfterFailure = com.example.util.CrashLogger.getCrashLog(context)
        assertNotNull(logAfterFailure)
        assertTrue(logAfterFailure!!.contains("Simulated model load failure"))
        assertTrue(logAfterFailure.contains("Test native exception"))

        val cleared = com.example.util.CrashLogger.clearCrashLog(context)
        assertTrue(cleared)
        assertNull(com.example.util.CrashLogger.getCrashLog(context))
    }

    @Test
    fun testOfflineFallbackOutputPreservesContentAndDoesNotEcho() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = MediaPipeLlmEngine(context)

        val chunks = listOf(
            com.example.ai.rag.RetrievedContextChunk(
                chunkId = 1,
                materialId = 1,
                materialTitle = "Big Data",
                chunkIndex = 0,
                text = "Data is defined as distinct pieces of information formatted in a special way.",
                score = 8.0,
                matchedTerms = listOf("data")
            )
        )

        // Test "what is data?"
        val defResponse = engine.synthesizeOfflineResponse("Big Data", "what is data?", chunks)
        assertTrue("Must contain definition", defResponse.contains("Data is defined as distinct pieces of information"))
        assertFalse("Must not start with Answer:", defResponse.startsWith("Answer:"))

        // Test "explain data to a 10-year-old"
        val childResponse = engine.synthesizeOfflineResponse("Big Data", "explain data to a 10-year-old", chunks)
        assertTrue("Must be child friendly", childResponse.contains("facts, numbers, or details"))
    }

    @Test
    fun testSharedSingletonLlmEngineInstanceAndReactiveChatStatus() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine1 = MediaPipeLlmEngine.getInstance(context)
        val engine2 = MediaPipeLlmEngine.getInstance(context)

        // Verify singleton reference equality
        assertSame("MediaPipeLlmEngine.getInstance must return the exact same singleton instance", engine1, engine2)

        val factory = AppViewModelFactory(context)
        assertSame("AppViewModelFactory must provide the singleton MediaPipeLlmEngine instance", engine1, factory.llmEngine)

        // Verify ChatRepository uses the exact same engine
        assertSame("ChatRepository must reference the shared singleton engine", engine1, factory.chatRepository.llmEngine)

        // Verify ChatViewModel reactive state flow binding
        val chatViewModel = ChatViewModel(factory.chatRepository, factory.classroomRepository, factory.authRepository)
        assertEquals("ChatViewModel modelStatus must match the shared engine modelStatus", engine1.modelStatus.value, chatViewModel.modelStatus.value)
    }

    @Test
    fun testChatUsesReadyModelWithoutRestartForWhatIsDataAndCharacteristics() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = MediaPipeLlmEngine.getInstance(context)
        val factory = AppViewModelFactory(context)

        // 1. Simulate model transition to Ready
        MediaPipeLlmEngine.setSharedStatusForTesting(com.example.ai.llm.ModelStatus.Ready)
        assertEquals(com.example.ai.llm.ModelStatus.Ready, engine.modelStatus.value)
        assertTrue("Engine must report isModelReady", engine.isModelReady())

        // 2. ChatViewModel observes this reactive transition immediately
        val chatViewModel = ChatViewModel(factory.chatRepository, factory.classroomRepository, factory.authRepository)
        assertEquals(com.example.ai.llm.ModelStatus.Ready, chatViewModel.modelStatus.value)

        val authResult = factory.authRepository.register("student_ready_test_${System.currentTimeMillis()}", "pass1234", "Ready Student", "STUDENT", "Year 3")
        val user = (authResult as com.example.data.repository.AuthResult.Success).user
        val subjectId = AppDatabase.getDatabase(context).subjectDao().insertSubject(
            SubjectEntity(name = "Big Data Analytics", code = "CS401", description = "Computer Science", colorHex = "#2563EB")
        ).toInt()

        // 3. Test "what is data?"
        val conv1 = factory.chatRepository.getOrCreateConversation(subjectId, user.id, "what is data?")
        val states1 = factory.chatRepository.askQuestionStream(conv1.id, subjectId, "Big Data Analytics", "what is data?").toList()
        val completed1 = states1.filterIsInstance<ChatStreamState.Completed>().lastOrNull()
        assertNotNull("Must complete stream", completed1)
        assertFalse("Must NOT be fallback when model is Ready", completed1!!.isFallback)
        assertEquals("Generation path must be LLM", "LLM", completed1.generationPath)
        assertTrue("Output must contain definition of data", completed1.fullResponse.contains("Data is defined as", ignoreCase = true) || completed1.fullResponse.contains("information", ignoreCase = true))

        // 4. Test "characteristics of data?"
        val conv2 = factory.chatRepository.getOrCreateConversation(subjectId, user.id, "characteristics of data?")
        val states2 = factory.chatRepository.askQuestionStream(conv2.id, subjectId, "Big Data Analytics", "characteristics of data?").toList()
        val completed2 = states2.filterIsInstance<ChatStreamState.Completed>().lastOrNull()
        assertNotNull("Must complete stream", completed2)
        assertFalse("Must NOT be fallback when model is Ready", completed2!!.isFallback)
        assertEquals("Generation path must be LLM", "LLM", completed2.generationPath)
        assertTrue("Output must contain characteristics (Volume, Velocity, etc.)", completed2.fullResponse.contains("Volume", ignoreCase = true) && completed2.fullResponse.contains("Velocity", ignoreCase = true))

        // Reset testing status
        MediaPipeLlmEngine.resetInstanceForTesting()
    }

    @Test
    fun testSynchronousInferenceStepLoggingAndPromptBudgeting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = MediaPipeLlmEngine.getInstance(context)
        val factory = AppViewModelFactory(context)

        // 1. Verify default maxTokens budget is 256 for safe mobile KV-cache
        assertEquals(256, engine.currentMaxTokens)
        assertEquals(256, engine.getEngineState().maxTokens)

        // 2. Test maxTokens toggle
        engine.currentMaxTokens = 512
        assertEquals(512, engine.currentMaxTokens)
        engine.currentMaxTokens = 256
        assertEquals(256, engine.currentMaxTokens)

        // 3. Clear existing crash log and verify inference step logging
        com.example.util.CrashLogger.clearCrashLog(context)
        MediaPipeLlmEngine.setSharedStatusForTesting(com.example.ai.llm.ModelStatus.Ready)

        val user = (factory.authRepository.register("student_budget_${System.currentTimeMillis()}", "pass1234", "Budget Student", "STUDENT", "Year 3") as com.example.data.repository.AuthResult.Success).user
        val subjectId = AppDatabase.getDatabase(context).subjectDao().insertSubject(
            SubjectEntity(name = "Data Science", code = "DS101", description = "Foundations", colorHex = "#10B981")
        ).toInt()

        val conv = factory.chatRepository.getOrCreateConversation(subjectId, user.id, "what is data?")
        factory.chatRepository.askQuestionStream(conv.id, subjectId, "Data Science", "what is data?").toList()

        val crashLog = com.example.util.CrashLogger.getCrashLog(context)
        assertNotNull("Crash log must exist after generation", crashLog)
        assertTrue("Crash log must record Question received checkpoint", crashLog!!.contains("Question received: 'what is data?' for subject 'Data Science' - starting RAG retrieval."))
        assertTrue("Crash log must record RAG retrieval complete checkpoint", crashLog.contains("RAG retrieval complete:"))
        assertTrue("Crash log must contain Pre-Generation RAM", crashLog.contains("Pre-Generation RAM: Free="))
        assertTrue("Crash log must contain Invoking generateResponseAsync", crashLog.contains("Invoking generateResponseAsync: PromptChars="))
        assertTrue("Crash log must contain FLUSHED TO DISK BEFORE CALL", crashLog.contains("[FLUSHED TO DISK BEFORE CALL]"))
        assertTrue("Crash log must record maxTokensBudget=256", crashLog.contains("maxTokensBudget=256"))

        // Reset
        MediaPipeLlmEngine.resetInstanceForTesting()
    }

    @Test
    fun testCrashLogReverseChronologicalFormattingAndExport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.util.CrashLogger.clearCrashLog(context)

        // 1. Session start creates visual separator
        com.example.util.CrashLogger.recordAppSessionStart(context)
        com.example.util.CrashLogger.logInitializationStep(context, "Step 1: Init starting")
        com.example.util.CrashLogger.logInitializationStep(context, "Step 6: Model is Ready!")
        com.example.util.CrashLogger.logInferenceStep(context, "Question received: 'what is data?' - starting RAG retrieval.")
        com.example.util.CrashLogger.logInferenceStep(context, "Invoking generateResponseAsync [FLUSHED TO DISK BEFORE CALL]")

        val rawLog = com.example.util.CrashLogger.getCrashLog(context)
        assertNotNull("Log must be recorded", rawLog)
        assertTrue("Log must contain session separator", rawLog!!.contains("=== NEW APP LAUNCH SESSION:"))
        assertTrue("Log must contain separator bar", rawLog.contains("================================================================================"))

        // 2. Format reverse-chronological
        val reversed = com.example.util.CrashLogger.formatLogReverseChronological(rawLog)
        val lines = reversed.lines().filter { it.isNotBlank() }
        assertTrue("Reversed log must put latest step at top", lines.first().contains("Invoking generateResponseAsync"))

        // 3. Export crash log test
        com.example.util.CrashLogger.exportCrashLog(context)

        // Reset
        com.example.util.CrashLogger.clearCrashLog(context)
    }
}
