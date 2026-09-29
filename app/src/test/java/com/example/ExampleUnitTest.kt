package com.example

import com.example.ai.rag.CandidateChunk
import com.example.ai.rag.RagEngine
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testSyllabusDetection() {
        val syllabusText = "Syllabus: Course Code CS401. Module 1: Introduction to Data, Characteristics, Volume, Variety. Module 2: Hadoop HDFS, MapReduce. Evaluation Scheme: Midterm 30%, Final 50%."
        assertTrue("Should detect course syllabus text", RagEngine.isSyllabusOrIndexChunk(syllabusText))

        val explanatoryText = "Characteristics of Data: Big data is characterized by four primary dimensions, often referred to as the 4 Vs: Volume, Variety, Velocity, and Veracity. Volume refers to the massive scale of data generated daily. Variety describes the different formats, ranging from structured SQL tables to unstructured sensor logs and video streams."
        assertFalse("Should NOT flag explanatory content as syllabus", RagEngine.isSyllabusOrIndexChunk(explanatoryText))
    }

    @Test
    fun testRetrievalRanksCharacteristicsChunkOverSyllabusAndFaultTolerance() {
        val syllabusChunk = CandidateChunk(
            chunkId = 1,
            materialId = 101,
            materialTitle = "Big Data Systems Syllabus",
            chunkIndex = 0,
            text = "Syllabus & Course Outline: Course Code CS501. Module 1: Data concepts, characteristics of data, volume, velocity. Module 2: Hadoop HDFS, MapReduce. Evaluation Scheme: 50% End Term.",
            wordCount = 28,
            termFrequencies = RagEngine.computeTermFrequencies(RagEngine.tokenize("Syllabus & Course Outline: Course Code CS501. Module 1: Data concepts, characteristics of data, volume, velocity. Module 2: Hadoop HDFS, MapReduce. Evaluation Scheme: 50% End Term.")),
            isIndexOrSyllabus = true
        )

        val characteristicsChunk = CandidateChunk(
            chunkId = 2,
            materialId = 101,
            materialTitle = "Unit 1: Fundamentals of Big Data",
            chunkIndex = 3,
            text = "Characteristics of Big Data: The four fundamental characteristics of data are Volume, Velocity, Variety, and Veracity. 1. Volume represents the sheer quantity of data generated every second. 2. Velocity refers to the speed at which data is produced and processed in real time. 3. Variety encompasses structured, semi-structured, and unstructured data forms.",
            wordCount = 58,
            termFrequencies = RagEngine.computeTermFrequencies(RagEngine.tokenize("Characteristics of Big Data: The four fundamental characteristics of data are Volume, Velocity, Variety, and Veracity. 1. Volume represents the sheer quantity of data generated every second. 2. Velocity refers to the speed at which data is produced and processed in real time. 3. Variety encompasses structured, semi-structured, and unstructured data forms.")),
            isIndexOrSyllabus = false
        )

        val faultToleranceChunk = CandidateChunk(
            chunkId = 3,
            materialId = 101,
            materialTitle = "Unit 2: Hadoop Architecture",
            chunkIndex = 12,
            text = "Hadoop Fault Tolerance and High Availability: In Hadoop HDFS, data reliability is achieved through block replication. If a DataNode crashes, the NameNode detects the failure through missing heartbeats and initiates automatic replica creation on healthy nodes to ensure continuous availability.",
            wordCount = 45,
            termFrequencies = RagEngine.computeTermFrequencies(RagEngine.tokenize("Hadoop Fault Tolerance and High Availability: In Hadoop HDFS, data reliability is achieved through block replication. If a DataNode crashes, the NameNode detects the failure through missing heartbeats and initiates automatic replica creation on healthy nodes to ensure continuous availability.")),
            isIndexOrSyllabus = false
        )

        val results = RagEngine.rankChunksHybrid(
            query = "characteristics of data?",
            candidateChunks = listOf(syllabusChunk, faultToleranceChunk, characteristicsChunk),
            topK = 3
        )

        assertFalse("Results should not be empty", results.isEmpty())
        assertEquals("Top retrieved chunk MUST be the characteristics explanatory chunk", 2, results.first().chunkId)
        assertTrue("Top chunk score must be higher than other chunks", results.first().score > 5.0)
    }

    @Test
    fun testChunkTextPreservesHeadingsTogetherWithParagraphs() {
        val document = """
            Chapter 1: Overview of Distributed Systems
            A distributed system consists of multiple autonomous computing entities that communicate through a computer network.
            
            Key Characteristics of Systems:
            Concurrency of components is a fundamental aspect where multiple processes execute actions simultaneously. Lack of a global clock necessitates logical time algorithms. Independent failures mean components can fail in isolation without stopping the whole network.
        """.trimIndent()

        val chunks = RagEngine.chunkText(document, targetWordsPerChunk = 100, maxWordsPerChunk = 200, minWordsPerChunk = 20)
        assertTrue("Should produce chunks", chunks.isNotEmpty())
        assertTrue("Chunk should retain heading and explanation together", chunks.any { it.text.contains("Key Characteristics of Systems:") && it.text.contains("Concurrency") })
    }

    @Test
    fun testPromptTemplateMatchesGemmaTeachingAssistantRequirement() {
        val chunks = listOf(
            com.example.ai.rag.RetrievedContextChunk(
                chunkId = 1,
                materialId = 10,
                materialTitle = "Data Science 101",
                chunkIndex = 0,
                text = "Data characteristics include Volume (size), Velocity (speed), Variety (types), and Veracity (truthfulness).",
                score = 9.5,
                matchedTerms = listOf("characteristics", "data")
            ),
            com.example.ai.rag.RetrievedContextChunk(
                chunkId = 2,
                materialId = 10,
                materialTitle = "Data Storage",
                chunkIndex = 1,
                text = "Volume represents the scale of data collected from users and devices daily.",
                score = 7.2,
                matchedTerms = listOf("data")
            ),
            com.example.ai.rag.RetrievedContextChunk(
                chunkId = 3,
                materialId = 10,
                materialTitle = "Data Streams",
                chunkIndex = 2,
                text = "Velocity describes real-time ingestion pipelines.",
                score = 6.0,
                matchedTerms = listOf("velocity")
            ),
            com.example.ai.rag.RetrievedContextChunk(
                chunkId = 4,
                materialId = 10,
                materialTitle = "Extra Material",
                chunkIndex = 3,
                text = "This fourth chunk should NOT be in context window.",
                score = 5.0,
                matchedTerms = listOf("extra")
            )
        )

        val prompt = RagEngine.buildRagPrompt(
            subjectName = "Big Data Analytics",
            question = "characteristics of data?",
            retrievedChunks = chunks
        )

        // Verifies exact template structure required by user
        assertTrue(prompt.startsWith("You are a teaching assistant for Big Data Analytics. Using ONLY the context below, answer the student's question clearly in your own words. Give a short definition first, then bullet points if the context lists items. Do not copy text word for word. Do not mention the context or page numbers. If the context does not contain the answer, reply exactly: 'I couldn't find this in the uploaded materials.'\n\nContext:\n"))
        assertTrue(prompt.contains("Data characteristics include Volume (size), Velocity (speed), Variety (types), and Veracity (truthfulness)."))
        assertTrue(prompt.contains("Volume represents the scale of data collected from users and devices daily."))
        assertTrue(prompt.contains("Velocity describes real-time ingestion pipelines."))
        // Confirms limited strictly to top 3 chunks
        assertFalse("4th chunk must NOT be included in prompt context", prompt.contains("This fourth chunk should NOT be in context window"))
        assertTrue(prompt.contains("\n\nQuestion: characteristics of data?\n\nAnswer:"))
    }

    @Test
    fun testSentenceLevelSyllabusAndWebsiteCleanup() {
        val rawChunk = """
            Vtucircle.com Page 23
            Module 1
            Big Data Analytics: What is Big data Analytics, Classification of Analytics, Technologies used in Big data
            Data is a collection of facts, numbers, words, measurements, observations or descriptions of things.
            There are two main types of data: 1.
        """.trimIndent()

        val cleaned = RagEngine.cleanChunkForPrompt(rawChunk)

        assertFalse("Must strip page footer", cleaned.contains("Vtucircle.com"))
        assertFalse("Must strip module heading", cleaned.contains("Module 1"))
        assertFalse("Must strip syllabus topic outline", cleaned.contains("What is Big data Analytics, Classification of Analytics"))
        assertFalse("Must strip trailing dangling incomplete sentence", cleaned.contains("There are two main types of data: 1."))
        assertTrue("Must keep core explanatory sentence", cleaned.contains("Data is a collection of facts"))
    }

    @Test
    fun testReSplitInlineBrokenLists() {
        val inlineList = "Characteristics of data include: 1. Volume: massive scale of datasets. 2. Velocity: fast streaming speed. 3. Variety: structured and unstructured formats."
        val cleaned = RagEngine.cleanChunkForPrompt(inlineList)

        assertTrue(cleaned.contains("1. Volume: massive scale of datasets."))
        assertTrue(cleaned.contains("\n2. Velocity: fast streaming speed."))
        assertTrue(cleaned.contains("\n3. Variety: structured and unstructured formats."))
    }

    @Test
    fun testHeaderTokenAndCourseOutlineCleaning() {
        val raw = """
            BigDataAnalytics-BCS714D-Module1
            BCS714D-Module1
            Data is defined as distinct pieces of information formatted in a special way.
            Vtucircle.com Page 45
            www.vtucircle.com
        """.trimIndent()
        val cleaned = RagEngine.cleanPdfText(raw)

        assertFalse("Must strip repeated course token", cleaned.contains("BigDataAnalytics-BCS714D-Module1"))
        assertFalse("Must strip module token", cleaned.contains("BCS714D-Module1"))
        assertFalse("Must strip website watermark", cleaned.contains("www.vtucircle.com"))
        assertFalse("Must strip page number", cleaned.contains("Page 45"))
        assertTrue("Must preserve data definition", cleaned.contains("Data is defined as distinct pieces of information"))
    }

    @Test
    fun testEmptyBulletDropAndReattach() {
        val raw = """
            1. Volume:
            Volume represents the sheer quantity of data generated every second.
            2. Velocity:
            3. Variety: Variety refers to structured and unstructured forms.
        """.trimIndent()
        val cleaned = RagEngine.cleanPdfText(raw)

        // 1. Volume was re-attached to its explanation on next line
        assertTrue("Must re-attach Volume to its explanation", cleaned.contains("1. Volume: Volume represents the sheer quantity"))
        // 2. Velocity had no content and was followed by another bullet, so it was dropped
        assertFalse("Must drop empty bullet Velocity", cleaned.contains("2. Velocity:"))
        // 3. Variety is retained
        assertTrue("Must retain Variety with content", cleaned.contains("3. Variety: Variety refers to"))
    }

    @Test
    fun testModelStatusDisplayLabels() {
        val notInstalled = com.example.ai.llm.ModelStatus.NotInstalled
        val loading = com.example.ai.llm.ModelStatus.Loading
        val ready = com.example.ai.llm.ModelStatus.Ready
        val failed = com.example.ai.llm.ModelStatus.Failed("Out of memory")

        assertEquals("Model: Not installed", notInstalled.displayLabel)
        assertEquals("Model: Loading", loading.displayLabel)
        assertEquals("Model: Ready", ready.displayLabel)
        assertEquals("Model: Failed: Out of memory", failed.displayLabel)
    }
}

