package com.example.ai.rag

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class ChunkResult(
    val index: Int,
    val text: String,
    val wordCount: Int,
    val termFrequencies: Map<String, Int>,
    val isIndexOrSyllabus: Boolean = false
)

data class RetrievedContextChunk(
    val chunkId: Int,
    val materialId: Int,
    val materialTitle: String,
    val chunkIndex: Int,
    val text: String,
    val score: Double,
    val matchedTerms: List<String>,
    val semanticScore: Double = 0.0,
    val explanatoryBoost: Double = 0.0
)

data class CandidateChunk(
    val chunkId: Int,
    val materialId: Int,
    val materialTitle: String,
    val chunkIndex: Int,
    val text: String,
    val wordCount: Int,
    val termFrequencies: Map<String, Int>,
    val isIndexOrSyllabus: Boolean = false
)

object RagEngine {

    val STOP_WORDS = setOf(
        "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any", "are",
        "aren't", "as", "at", "be", "because", "been", "before", "being", "below", "between", "both",
        "but", "by", "can't", "cannot", "could", "couldn't", "did", "didn't", "do", "does", "doesn't",
        "doing", "don't", "down", "during", "each", "few", "for", "from", "further", "had", "hadn't",
        "has", "hasn't", "have", "haven't", "having", "he", "he'd", "he'll", "he's", "her", "here",
        "here's", "hers", "herself", "him", "himself", "his", "how", "how's", "i", "i'd", "i'll",
        "i'm", "i've", "if", "in", "into", "is", "isn't", "it", "it's", "its", "itself", "let's", "me",
        "more", "most", "mustn't", "my", "myself", "no", "nor", "not", "of", "off", "on", "once",
        "only", "or", "other", "ought", "our", "ours", "ourselves", "out", "over", "own", "same",
        "shan't", "she", "she'd", "she'll", "she's", "should", "shouldn't", "so", "some", "such",
        "than", "that", "that's", "the", "their", "theirs", "them", "themselves", "then", "there",
        "there's", "these", "they", "they'd", "they'll", "they're", "they've", "this", "those",
        "through", "to", "too", "under", "until", "up", "very", "was", "wasn't", "we", "we'd", "we'll",
        "we're", "we've", "were", "weren't", "what", "what's", "when", "when's", "where", "where's",
        "which", "while", "who", "who's", "whom", "why", "why's", "with", "won't", "would", "wouldn't",
        "you", "you'd", "you'll", "you're", "you've", "your", "yours", "yourself", "yourselves"
    )

    /**
     * Cleans raw PDF text by:
     * - Stripping embedded metadata, locale tags, spellcheck artifacts, web page stamps
     * - Removing syllabus/topic-list sentences and page headers/footers
     * - Re-splitting numbered points joined inline ("1. Volume ... 2. Velocity ...") onto distinct lines
     * - De-hyphenating split words and repairing kerning/drop-caps
     * - Rejoining broken line wraps inside sentences while preserving paragraph breaks
     */
    fun cleanPdfText(rawText: String): String {
        if (rawText.isBlank()) return ""
        var text = rawText.replace("\r\n", "\n").replace("\r", "\n")

        // 1. Remove page number patterns (e.g., "Page 1 of 5", "page 2", "- 3 -", "[4]", "1 / 10")
        text = text.replace(Regex("(?i)(?:^|\\n)\\s*(?:page\\s*\\d+\\s*(?:of|/)\\s*\\d+|page\\s*\\d+|-\\s*\\d+\\s*-|\\[\\s*\\d+\\s*\\]|\\b\\d+\\s*/\\s*\\d+\\b)\\s*(?:\\n|$)"), "\n")

        // 2. Remove standard document header/footer artifacts, course tokens, and website stamps (e.g. "Vtucircle.com Page 23", "BigDataAnalytics-BCS714D-Module1")
        text = text.replace(Regex("(?i)OFFLINE GENIUS SMART CLASSROOM.*?\\n"), "\n")
        text = text.replace(Regex("(?i)Offline Curriculum Study Material.*?\\n"), "\n")
        text = text.replace(Regex("(?i)\\bBigDataAnalytics-BCS714D-Module\\d*\\b"), " ")
        text = text.replace(Regex("(?i)\\b[a-zA-Z0-9]+-[a-zA-Z0-9]+-(Module|Unit|Chapter|Part)\\d*\\b"), " ")
        text = text.replace(Regex("(?i)\\b[a-zA-Z0-9]+-(?:Module|Unit|Chapter)\\d*\\b"), " ")
        text = text.replace(Regex("(?i)\\b[A-Z]{2,}\\d{3,}[A-Z]?(?:-(?:Module|Unit)\\d*)?\\b"), " ")
        text = text.replace(Regex("(?i)[a-zA-Z0-9.-]+\\.(com|org|edu|in|net)\\s+(page\\s*\\d+|\\d+)?"), " ")
        text = text.replace(Regex("(?i)www\\.[a-zA-Z0-9.-]+\\.(com|org|edu|in|net)"), " ")

        // 2b. Drop bullets that have a label but no content (e.g., "Velocity:" with nothing after it)
        text = text.replace(Regex("""(?m)^\s*(?:[•*\-]|\d+[.)])\s*[A-Za-z0-9\s]{2,30}:\s*(?=\n\s*(?:[•*\-]|\d+[.)]|\n|$))"""), "")
        text = text.replace(Regex("""(?m)^\s*[A-Za-z0-9\s]{2,30}:\s*(?=\n\s*(?:[•*\-]|\d+[.)]|\n|$))"""), "")

        // 2c. Re-attach list items to their explanation when split onto the next line (unless next line is another bullet)
        text = text.replace(Regex("""(?m)^(\s*(?:[•*\-]|\d+[.)])?\s*[A-Za-z0-9\s]{2,30}:)\s*\n\s*(?!(?:[•*\-]|\d+[.)]))([A-Za-z0-9"'])"""), "$1 $2")

        // 3. Strip PDF language/locale and spellcheck tags (e.g. "en-US", "(Se en-US)", "en-US (Se en-US", "zh-CN")
        text = text.replace(Regex("(?i)\\(\\s*Se\\s+en[-_][a-z]{2,}\\s*\\)"), " ")
        text = text.replace(Regex("(?i)\\(\\s*Se\\s+[^)]*\\)"), " ")
        text = text.replace(Regex("(?i)\\bSe\\s+en[-_][a-z]{2,}\\b"), " ")
        text = text.replace(Regex("(?i)\\b(en|fr|es|de|zh|ja|it|pt|ru|hi|ar)[-_]([a-z]{2}|[A-Z]{2}|[a-zA-Z]{4,})\\b"), " ")
        text = text.replace(Regex("(?i)/Lang\\s*\\([^)]*\\)"), " ")
        text = text.replace(Regex("(?i)/Span\\b|/Type\\b|/Catalog\\b|/StructTreeRoot\\b"), " ")
        text = text.replace(Regex("<<[^>]*>>"), " ")

        // 4. Strip unprintable ASCII control characters (keep \n and \t) and unicode replacement char \uFFFD
        text = text.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\\uFFFD]"), "")

        // 5. De-hyphenate words split across line breaks (e.g. "Hado-\noop" -> "Hadoop", "biologi-\ncal" -> "biological")
        text = text.replace(Regex("([a-zA-Z]{2,})-\\s*\\n+\\s*([a-zA-Z]{2,})"), "$1$2")

        // 6. De-hyphenate words with broken space on the same line (e.g. "Hado- oop" -> "Hadoop")
        text = text.replace(Regex("([a-zA-Z]{2,})-\\s+([a-z]{2,})"), "$1$2")

        // 7. Repair drop-cap / kerning single-letter breaks (e.g. "H adoop" -> "Hadoop", "B ig" -> "Big", "D ata" -> "Data")
        text = text.replace(Regex("\\b([B-HJ-Z])\\s+([a-z]{2,})\\b"), "$1$2")
        text = text.replace(Regex("(?i)\\bA\\s+(nalytics|lgorithm|ssignment|rchitecture|pplication|rtificial)\\b"), "A$1")
        text = text.replace(Regex("(?i)\\bI\\s+(nformation|nfrastructure|ntelligence|nterface|teration)\\b"), "I$1")

        // 8. CRITICAL: Re-split numbered points that are joined inline ("1. Volume ... 2. Velocity ... 3. Variety") onto separate lines
        text = text.replace(Regex("(?<=[^\\n])\\s+(?=\\b(?:\\d+|[a-zA-Z])[.)]\\s+[A-Za-z])"), "\n")
        text = text.replace(Regex("(?<=[^\\n])\\s+(?=[•*\\-]\\s+[A-Za-z])"), "\n")

        // 9. Sentence-level cleanup: remove syllabus / course metadata lines before rejoining paragraphs
        val nonSyllabusLines = text.lines().mapNotNull { rawLine ->
            val line = rawLine.trim()
            if (isSyllabusSentence(line)) null else rawLine
        }
        text = nonSyllabusLines.joinToString("\n")

        // 10. Rejoin broken line breaks inside sentences (line ends without terminal punctuation, next starts with lowercase letter or comma continuation)
        text = text.replace(Regex("([a-zA-Z0-9,;])\\n\\s*([a-z])"), "$1 $2")
        text = text.replace(Regex("([a-zA-Z0-9],)\\n\\s*([A-Za-z])"), "$1 $2")

        // 11. Normalize whitespace and collapse excessive blank lines
        text = text.replace("\t", " ")
        text = text.replace(Regex("[ ]+"), " ")
        text = text.replace(Regex("\\n{3,}"), "\n\n")

        return text.trim()
    }

    /**
     * Checks if an individual sentence or line is syllabus / topic-list noise
     */
    fun isSyllabusSentence(sentence: String): Boolean {
        val s = sentence.trim()
        if (s.isEmpty()) return false
        val lower = s.lowercase()

        // Page footer or website stamp (e.g., "Vtucircle.com Page 23", "vtucircle.com")
        if (Regex("(?i)^[a-zA-Z0-9.-]+\\.(com|org|edu|in|net)\\b").containsMatchIn(s)) return true
        if (Regex("(?i)\\b[a-zA-Z0-9.-]+\\.(com|org|edu|in|net)\\s+(page\\s*\\d+|\\d+)?").containsMatchIn(s)) return true
        if (Regex("(?i)^page\\s*\\d+(\\s*of\\s*\\d+)?$").matches(s)) return true

        // Syllabus / module metadata lines (e.g. "Module 1", "Unit 1", "Course Code CS401")
        if (Regex("(?i)^(module|unit|chapter|paper|subject code|course code)\\s*\\d*[:\\s-]*.*").matches(s) &&
            !s.contains("is defined", ignoreCase = true) && !s.contains("consists of", ignoreCase = true) && !s.contains("deals with", ignoreCase = true)) {
            if (s.count { it == ',' } >= 2 || s.length < 80) return true
        }
        if (Regex("(?i)^(syllabus|course code|course outcomes?|course objectives?|evaluation scheme|credits\\s*:|reference books?:?|text books?:?)\\b").containsMatchIn(lower)) return true

        // Explanatory definition phrases and descriptions are NEVER syllabus sentences
        if (Regex("(?i)\\b(is\\s+defined\\s+as|are\\s+defined\\s+as|is\\s+a\\s+collection\\s+of|is\\s+information\\s+that|refers\\s+to|refer\\s+to|can\\s+be\\s+defined\\s+as|characteristics\\s+of\\s+data|primary\\s+characteristics|are\\s+described\\s+by|all\\s+software\\s+is\\s+divided|programs\\s+are\\s+collections)\\b").containsMatchIn(s)) {
            return false
        }
        if (Regex("(?i)\\b(include|includes|consists\\s+of|composed\\s+of)\\b").containsMatchIn(s) &&
            !Regex("(?i)\\b(syllabus|course|credits|evaluation|textbook)\\b").containsMatchIn(lower)) {
            return false
        }

        // Syllabus topic-list patterns e.g. "Big Data Analytics: What is Big data Analytics, Classification of Analytics, Technologies used..."
        if (s.contains(":") && (s.contains("Classification of", ignoreCase = true) || s.contains("Technologies used", ignoreCase = true) || Regex("(?i)\\bwhat\\s+is\\b.*,").containsMatchIn(s))) {
            return true
        }

        // Non-grammatical comma lists without explanatory verbs
        val words = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        val verbs = setOf("is", "are", "was", "were", "means", "refers", "refer", "defined", "consists", "include", "includes", "involves", "provides", "used", "describes", "represents", "divided")
        val verbCount = words.count { it.lowercase().trimEnd(',', '.', ';', ':', '(', ')') in verbs }
        if (verbCount == 0 && words.size >= 3 && s.count { it == ',' } >= 2) {
            return true
        }

        return false
    }

    /**
     * Detects table-of-contents / syllabus-style text (short comma-separated topic lists,
     * module headers like "Module 1", course codes, evaluation schemes) that should not compete
     * with actual explanatory content chunks.
     */
    fun isSyllabusOrIndexChunk(text: String): Boolean {
        if (text.isBlank()) return false
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 6) return false

        val lower = text.lowercase()

        // Explicit course outline / syllabus keywords
        val explicitMarkers = listOf(
            "syllabus", "table of contents", "course code", "course outcome", "course outcomes",
            "course objectives", "prerequisites", "evaluation scheme", "credits :", "credit units",
            "contact hours", "hours/week", "lecture hours", "text books:", "reference books:"
        )
        val hasExplicitMarker = explicitMarkers.any { lower.contains(it) }

        // Module/Unit outline markers e.g. "Module 1: ... Module 2: ... Module 3: ..."
        val moduleMatches = Regex("(?i)\\b(module|unit|chapter)\\s*\\d+\\b").findAll(text).count()

        val commaCount = text.count { it == ',' }
        val colonCount = text.count { it == ':' }
        val punctDensity = (commaCount + colonCount).toDouble() / words.size

        val explanatoryVerbs = setOf(
            "is", "are", "was", "were", "means", "refers", "defined", "consists",
            "includes", "characterized", "involves", "provides", "describes",
            "causes", "results", "used", "enables", "helps", "allows", "contains",
            "represents", "requires", "operates"
        )
        val verbCount = words.count { it.lowercase().trimEnd(',', '.', ';', ':') in explanatoryVerbs }
        val verbRatio = verbCount.toDouble() / words.size

        // If explicit syllabus/course code marker exists with low explanatory verb ratio
        if (hasExplicitMarker && (verbRatio < 0.05 || punctDensity > 0.045)) {
            return true
        }

        // Multiple module/unit outlines packed together without explanatory prose
        if (moduleMatches >= 2 && verbRatio < 0.04) {
            return true
        }

        // Comma-separated lists of topics with high punctuation and low narrative verbs
        if (punctDensity > 0.08 && verbRatio < 0.025) {
            return true
        }

        return false
    }

    /**
     * Identifies if a line represents a heading or section title that must NOT be orphaned
     * at the tail end of a chunk without its subsequent content.
     */
    fun isHeadingLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.endsWith(":")) return true
        if (trimmed.startsWith("#")) return true
        if (Regex("^(Module|Unit|Chapter|Section|Part|Topic)\\s*\\d+[:\\-\\s]", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return true
        val words = trimmed.split(Regex("\\s+"))
        if (words.size in 1..8 && trimmed.length < 65) {
            val lastChar = trimmed.last()
            if (lastChar !in listOf('.', '!', '?', ';', ',')) {
                if (trimmed[0].isUpperCase() || trimmed[0].isDigit()) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Splits text into cohesive, sentence-bounded chunks of ~200–400 words (target ~250 words)
     * with slight overlap (~40 words) across consecutive chunks to preserve semantic context.
     *
     * BOUNDARY PRESERVATION:
     * - Keeps headings/subheadings with the paragraph(s) and lists that follow them in the SAME chunk.
     *   Never places a heading at the tail end of a chunk without its explanation.
     * - Detects and flags table-of-contents / syllabus lists so they don't compete with explanatory content.
     * - Sentences are NEVER cut in the middle.
     */
    fun chunkText(
        rawText: String,
        targetWordsPerChunk: Int = 250,
        minWordsPerChunk: Int = 100,
        maxWordsPerChunk: Int = 380,
        overlapWords: Int = 40
    ): List<ChunkResult> {
        val cleaned = cleanPdfText(rawText)
        if (cleaned.isBlank()) return emptyList()

        // Split into structural paragraphs
        val rawParagraphs = cleaned.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
        if (rawParagraphs.isEmpty()) return emptyList()

        // First pass: break paragraphs into sentences while keeping headings together with their following content
        val sentenceUnits = mutableListOf<String>()
        for (paragraph in rawParagraphs) {
            val lines = paragraph.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue

            // If a line inside paragraph is a heading, treat it as its own unit
            var idx = 0
            while (idx < lines.size) {
                val line = lines[idx]
                if (isHeadingLine(line)) {
                    // Combine heading with next line if next line exists to avoid separating heading
                    if (idx + 1 < lines.size) {
                        sentenceUnits.add("$line\n${lines[idx + 1]}")
                        idx += 2
                    } else {
                        sentenceUnits.add(line)
                        idx++
                    }
                } else {
                    // Split ordinary paragraph text on sentence boundaries
                    val sList = line.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                    if (sList.isNotEmpty()) {
                        sentenceUnits.addAll(sList)
                    } else {
                        sentenceUnits.add(line)
                    }
                    idx++
                }
            }
        }

        if (sentenceUnits.isEmpty()) return emptyList()

        val totalWordsInDoc = sentenceUnits.sumOf { countWords(it) }
        if (totalWordsInDoc <= maxWordsPerChunk) {
            val content = sentenceUnits.joinToString(" ")
            val tf = computeTermFrequencies(tokenize(content))
            val isSyllabus = isSyllabusOrIndexChunk(content)
            return listOf(ChunkResult(index = 0, text = content, wordCount = totalWordsInDoc, termFrequencies = tf, isIndexOrSyllabus = isSyllabus))
        }

        val chunks = mutableListOf<ChunkResult>()
        var unitIdx = 0
        var chunkIdx = 0

        while (unitIdx < sentenceUnits.size) {
            val currentUnits = mutableListOf<String>()
            var currentWordCount = 0

            var i = unitIdx
            while (i < sentenceUnits.size) {
                val unit = sentenceUnits[i]
                val unitWords = countWords(unit)

                // If adding this unit exceeds maxWords, check if the upcoming unit is a heading
                if (currentWordCount + unitWords > maxWordsPerChunk && currentUnits.isNotEmpty()) {
                    break
                }

                // If upcoming unit is a heading and adding it would push current chunk over target,
                // close chunk BEFORE the heading so the heading starts the new chunk with its explanation
                if (isHeadingLine(unit) && currentWordCount >= minWordsPerChunk && i + 1 < sentenceUnits.size) {
                    val nextUnitWords = countWords(sentenceUnits[i + 1])
                    if (currentWordCount + unitWords + nextUnitWords > maxWordsPerChunk) {
                        break
                    }
                }

                currentUnits.add(unit)
                currentWordCount += unitWords
                i++

                // If current unit is a list item (e.g. "1. Volume" or "2. Velocity"),
                // try to keep consecutive list items together in the same chunk instead of breaking mid-list
                val isListItem = Regex("^\\d+[.)]\\s+").containsMatchIn(unit)
                val nextUnit = if (i < sentenceUnits.size) sentenceUnits[i] else null
                val nextIsListItem = nextUnit != null && Regex("^\\d+[.)]\\s+").containsMatchIn(nextUnit)

                if (currentWordCount >= targetWordsPerChunk) {
                    // If the next unit is part of the same list and we haven't hit maxWords, keep pulling list items
                    if (isListItem && nextIsListItem && currentWordCount + (nextUnit?.let { countWords(it) } ?: 0) <= maxWordsPerChunk + 80) {
                        // continue loop to include next list item
                    } else {
                        // If the last added item was a solitary heading, pull the next unit so it's not orphaned
                        if (isHeadingLine(unit) && i < sentenceUnits.size) {
                            val pullUnit = sentenceUnits[i]
                            currentUnits.add(pullUnit)
                            currentWordCount += countWords(pullUnit)
                            i++
                        }
                        break
                    }
                }
            }

            // Prevent ending chunk abruptly with dangling incomplete sentences like "There are two main types of data: 1." or trailing colons
            while (currentUnits.isNotEmpty()) {
                val lastUnit = currentUnits.last().trim()
                if (Regex("(?i)(?:there are [^.!?:]+:\\s*(?:\\d+\\.?)?|:\\s*\\d*\\.?)$").containsMatchIn(lastUnit) ||
                    (lastUnit.endsWith(":") && !lastUnit.startsWith("#") && !isHeadingLine(lastUnit))) {
                    currentUnits.removeAt(currentUnits.size - 1)
                    i--
                } else {
                    break
                }
            }

            val chunkContent = currentUnits.joinToString(" ")
            val tf = computeTermFrequencies(tokenize(chunkContent))
            val isSyllabus = isSyllabusOrIndexChunk(chunkContent)

            chunks.add(
                ChunkResult(
                    index = chunkIdx,
                    text = chunkContent,
                    wordCount = currentWordCount,
                    termFrequencies = tf,
                    isIndexOrSyllabus = isSyllabus
                )
            )
            chunkIdx++

            if (i >= sentenceUnits.size) {
                break
            }

            // Calculate overlap: step back 1 or 2 sentences for context continuity (~overlapWords)
            // If the boundary was mid-list, ensure the overlap starts before the list or includes the preceding list item
            var overlapCount = 0
            var stepBack = 0
            var j = i - 1
            while (j >= unitIdx && overlapCount < overlapWords) {
                // Don't overlap with a solitary heading
                if (!isHeadingLine(sentenceUnits[j])) {
                    overlapCount += countWords(sentenceUnits[j])
                }
                stepBack++
                j--
            }

            // If the upcoming chunk starts on a list item > 1 (e.g. 2. Velocity or 3. Variety),
            // step back to include item 1 (1. Volume) and the list header so Volume stays with Velocity and Variety!
            val upcomingIdx = (i - stepBack).coerceAtLeast(0)
            if (upcomingIdx in 0 until sentenceUnits.size && Regex("^[2-9]\\d*[.)]\\s+").containsMatchIn(sentenceUnits[upcomingIdx])) {
                var k = upcomingIdx - 1
                while (k >= unitIdx && k >= upcomingIdx - 6) {
                    if (Regex("^1[.)]\\s+").containsMatchIn(sentenceUnits[k]) || Regex("(?i)(characteristics|features|types|dimension|dimensions)\\b").containsMatchIn(sentenceUnits[k])) {
                        stepBack = i - k
                        break
                    }
                    k--
                }
            }

            val nextStart = (i - stepBack).coerceAtLeast(unitIdx + 1)
            unitIdx = nextStart
        }

        // If the last chunk is very small (< minWordsPerChunk) and we have a previous chunk, merge it
        if (chunks.size > 1 && chunks.last().wordCount < minWordsPerChunk) {
            val last = chunks.removeAt(chunks.size - 1)
            val prev = chunks.removeAt(chunks.size - 1)
            val mergedText = "${prev.text} ${last.text}"
            val mergedWords = prev.wordCount + last.wordCount
            val mergedTf = computeTermFrequencies(tokenize(mergedText))
            val isSyllabus = isSyllabusOrIndexChunk(mergedText)
            chunks.add(
                ChunkResult(
                    index = prev.index,
                    text = mergedText,
                    wordCount = mergedWords,
                    termFrequencies = mergedTf,
                    isIndexOrSyllabus = isSyllabus
                )
            )
        }

        return chunks
    }

    private fun countWords(text: String): Int {
        return text.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    /**
     * Tokenizes and normalizes text:
     * - Lowercases and removes non-alphanumeric punctuation
     * - Retains essential 2-character acronyms and scientific symbols ("ai", "ml", "ph", "fe", "cu", "o2", "pi", "dna")
     * - Strips standard English stop words
     * - Normalizes morphological variations (e.g. "gravitation", "gravitational" -> root)
     */
    fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { token ->
                token.length >= 2 && token !in STOP_WORDS
            }
            .map { stem(it) }
    }

    private fun stem(word: String): String {
        return when {
            // Suffix normalizations that preserve semantic roots
            word.endsWith("ically") && word.length > 7 -> word.dropLast(6)
            word.endsWith("ational") && word.length > 8 -> word.dropLast(5)
            word.endsWith("ation") && word.length > 6 -> word.dropLast(3)
            word.endsWith("tional") && word.length > 7 -> word.dropLast(4)
            word.endsWith("ingly") && word.length > 6 -> word.dropLast(5)
            word.endsWith("ing") && word.length > 5 -> word.dropLast(3)
            word.endsWith("ness") && word.length > 6 -> word.dropLast(4)
            word.endsWith("ment") && word.length > 6 -> word.dropLast(4)
            word.endsWith("able") && word.length > 6 -> word.dropLast(4)
            word.endsWith("ible") && word.length > 6 -> word.dropLast(4)
            word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
            word.endsWith("ed") && word.length > 4 -> word.dropLast(2)
            word.endsWith("es") && word.length > 4 && !word.endsWith("ses") -> word.dropLast(2)
            word.endsWith("s") && word.length > 3 && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is") -> word.dropLast(1)
            else -> word
        }
    }

    fun computeTermFrequencies(tokens: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (token in tokens) {
            map[token] = (map[token] ?: 0) + 1
        }
        return map
    }

    /**
     * Computes a 64-dimensional dense semantic embedding vector:
     * - Dimensions 0..7 encode key academic curriculum concepts (Characteristics, Definitions,
     *   Architecture, Storage, Processing, Fault Tolerance, Classification, Evolution)
     * - Dimensions 8..63 use hash projection with Smooth Inverse Frequency (SIF) weighting:
     *   down-weights high-frequency common terms (like "data") and boosts discriminative terms
     * - Normalized to unit length (L2 norm = 1.0) for cosine similarity
     */
    fun computeSemanticVector(
        text: String,
        tokens: List<String>,
        docFreq: Map<String, Int>,
        totalDocs: Int
    ): FloatArray {
        val vec = FloatArray(64) { 0f }
        val lower = text.lowercase()

        // 1. Core Academic Semantic Concept Clusters (Dimensions 0..7)
        val charTerms = listOf("characteristic", "characteristics", "feature", "features", "property", "properties", "attribute", "attributes", "dimension", "dimensions", "volume", "variety", "velocity", "veracity", "value", "variability")
        val defTerms = listOf("definition", "define", "defined", "meaning", "concept", "what is", "refers", "term")
        val archTerms = listOf("architecture", "component", "components", "framework", "layer", "namenode", "datanode", "master", "worker", "yarn", "resourcemanager", "nodemanager")
        val storageTerms = listOf("storage", "hdfs", "file system", "distributed", "block", "replica", "replication", "disk")
        val procTerms = listOf("processing", "computation", "mapreduce", "mapper", "reducer", "spark", "batch", "stream", "job")
        val faultTerms = listOf("fault", "tolerance", "tolerant", "recovery", "failure", "heartbeat", "resilience", "redundancy")
        val classTerms = listOf("types", "classification", "structured", "unstructured", "semi-structured")
        val histTerms = listOf("evolution", "history", "traditional", "generation", "era")

        vec[0] = charTerms.count { lower.contains(it) }.toFloat() * 1.5f
        vec[1] = defTerms.count { lower.contains(it) }.toFloat() * 1.2f
        vec[2] = archTerms.count { lower.contains(it) }.toFloat() * 1.2f
        vec[3] = storageTerms.count { lower.contains(it) }.toFloat() * 1.2f
        vec[4] = procTerms.count { lower.contains(it) }.toFloat() * 1.2f
        vec[5] = faultTerms.count { lower.contains(it) }.toFloat() * 1.5f
        vec[6] = classTerms.count { lower.contains(it) }.toFloat() * 1.2f
        vec[7] = histTerms.count { lower.contains(it) }.toFloat() * 1.2f

        // 2. Hash projection with SIF weighting (Dimensions 8..63)
        val nDocs = max(1, totalDocs)
        for (token in tokens) {
            val df = docFreq[token] ?: 1
            val p = df.toDouble() / nDocs
            // Common words like "data" (p ≈ 0.9) get weight ≈ 0.05
            // Specific words like "characteristics" (p ≈ 0.05) get weight ≈ 0.95
            val sifWeight = (0.005 / (0.005 + p)).toFloat()

            val bucket = 8 + (Math.abs(token.hashCode()) % 56)
            vec[bucket] += sifWeight
        }

        // 3. Normalize to unit length (L2 norm)
        var sumSquares = 0.0
        for (v in vec) {
            sumSquares += (v * v).toDouble()
        }
        val norm = Math.sqrt(sumSquares)
        if (norm > 0.0001) {
            for (i in vec.indices) {
                vec[i] = (vec[i] / norm).toFloat()
            }
        }

        return vec
    }

    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Double {
        if (v1.size != v2.size) return 0.0
        var dot = 0.0
        for (i in v1.indices) {
            dot += (v1[i] * v2[i]).toDouble()
        }
        return dot.coerceIn(0.0, 1.0)
    }

    /**
     * Hybrid Re-Ranking Engine:
     * 1. Excludes or penalizes syllabus/TOC index chunks so they don't compete with actual explanatory content
     * 2. Computes on-device dense semantic cosine similarity as the primary ranking signal
     * 3. Attenuates common terms (like "data", "big data") so they don't dominate keyword overlap
     * 4. Applies Stage-2 Explanatory Pattern Re-Ranking (definitions, lists of characteristics Volume/Variety/Velocity/Veracity, heading matches)
     * 5. Scoped strictly to the subject's candidate chunks
     */
    fun rankChunksHybrid(
        query: String,
        candidateChunks: List<CandidateChunk>,
        topK: Int = 4
    ): List<RetrievedContextChunk> {
        if (candidateChunks.isEmpty()) return emptyList()

        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        val totalDocs = candidateChunks.size
        val avgDocLength = candidateChunks.map { it.wordCount }.average().coerceAtLeast(1.0)

        // Document frequency per term across candidate chunks in this subject
        val docFrequency = mutableMapOf<String, Int>()
        for (term in queryTokens) {
            var count = 0
            for (chunk in candidateChunks) {
                if (chunk.termFrequencies.containsKey(term)) {
                    count++
                }
            }
            docFrequency[term] = count
        }

        // Query dense semantic embedding vector
        val queryEmbedding = computeSemanticVector(query, queryTokens, docFrequency, totalDocs)

        // Query bigrams for contiguous phrase boosting
        val queryBigrams = mutableListOf<String>()
        val rawWords = query.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").split(Regex("\\s+")).filter { it.length >= 2 }
        for (idx in 0 until rawWords.size - 1) {
            queryBigrams.add("${rawWords[idx]} ${rawWords[idx + 1]}")
        }

        // Detect query intent
        val lowerQuery = query.lowercase()
        val isAskingCharacteristics = lowerQuery.contains("characteristic") || lowerQuery.contains("property") ||
                lowerQuery.contains("properties") || lowerQuery.contains("features") || lowerQuery.contains("dimensions") ||
                lowerQuery.contains("attributes")
        val isAskingDefinition = lowerQuery.contains("what is") || lowerQuery.contains("define") || lowerQuery.contains("definition") ||
                lowerQuery.contains("meaning")
        val isAskingComparison = lowerQuery.contains("difference") || lowerQuery.contains("compare") || lowerQuery.contains("versus") ||
                lowerQuery.contains("vs")
        val isAskingArchitecture = lowerQuery.contains("architecture") || lowerQuery.contains("framework") || lowerQuery.contains("component")
        val isAskingFaultTolerance = lowerQuery.contains("fault") || lowerQuery.contains("tolerance") || lowerQuery.contains("failure") ||
                lowerQuery.contains("heartbeat") || lowerQuery.contains("recovery")
        val isAskingSyllabus = lowerQuery.contains("syllabus") || lowerQuery.contains("course code") || lowerQuery.contains("module list")

        // Characteristic core dimensions
        val charDimensions = listOf("volume", "variety", "velocity", "veracity", "value", "variability")

        val scored = candidateChunks.map { chunk ->
            val chunkTokens = tokenize(chunk.text)
            val chunkEmbedding = computeSemanticVector(chunk.text, chunkTokens, docFrequency, totalDocs)

            // 1. On-device Semantic Cosine Similarity
            val semanticSim = cosineSimilarity(queryEmbedding, chunkEmbedding).coerceAtLeast(0.0)

            // 2. Discriminative BM25 with dynamic term frequency down-weighting
            var bm25Score = 0.0
            val matchedTerms = mutableListOf<String>()
            val docLen = chunk.wordCount.coerceAtLeast(1)

            for (term in queryTokens) {
                val tf = chunk.termFrequencies[term] ?: 0
                if (tf > 0) {
                    matchedTerms.add(term)
                    val df = docFrequency[term] ?: 0
                    val docFreqRatio = df.toDouble() / totalDocs

                    // Dynamic down-weighting: ubiquitous terms in > 40% of docs (e.g. "data" in Big Data)
                    // have near-zero discriminative value
                    val attenuation = if (docFreqRatio >= 0.40) {
                        max(0.04, 1.0 - docFreqRatio)
                    } else {
                        1.0
                    }

                    val idf = ln((totalDocs - df + 0.5) / (df + 0.5) + 1.0).coerceAtLeast(0.05)
                    val numerator = tf * (1.5 + 1.0)
                    val denominator = tf + 1.5 * (1.0 - 0.75 + 0.75 * (docLen / avgDocLength))
                    bm25Score += (idf * (numerator / denominator)) * attenuation
                }
            }

            // Bigram contiguous phrase bonus
            val lowerChunkText = chunk.text.lowercase()
            for (bigram in queryBigrams) {
                if (lowerChunkText.contains(bigram)) {
                    bm25Score += 2.0
                }
            }

            // Syllabus / Index Detection & Penalty
            val isSyllabus = chunk.isIndexOrSyllabus || isSyllabusOrIndexChunk(chunk.text)
            val syllabusPenalty = if (isSyllabus && !isAskingSyllabus) -35.0 else 0.0

            // Stage 2: Explanatory Pattern Re-Ranking Boosts
            var explanatoryBoost = 0.0

            if (isAskingCharacteristics && !isSyllabus) {
                // If question is asking for characteristics of data:
                // Check if chunk has a heading matching characteristics
                if (Regex("(?i)(characteristics|properties|features|dimensions)\\s+of\\s+(data|big\\s*data)").containsMatchIn(chunk.text)) {
                    explanatoryBoost += 5.5
                }
                // Check how many characteristic dimensions are explained
                val matchedDims = charDimensions.count { lowerChunkText.contains(it) }
                if (matchedDims >= 4) {
                    explanatoryBoost += 6.0
                } else if (matchedDims >= 2) {
                    explanatoryBoost += 3.5
                }
                // Check for bulleted/numbered explanations (e.g. "1. Volume", "2. Variety")
                if (Regex("(?i)(\\d+\\.|•|\\*|-)\\s*(volume|variety|velocity|veracity)").containsMatchIn(chunk.text)) {
                    explanatoryBoost += 3.0
                }
                // Penalize chunks talking purely about Hadoop fault tolerance or Namenode when asking characteristics of data
                if (lowerChunkText.contains("fault tolerance") && matchedDims == 0) {
                    explanatoryBoost -= 5.0
                }
            }

            if (isAskingDefinition && !isSyllabus) {
                if (Regex("(?i)(is\\s+defined\\s+as|refers\\s+to|can\\s+be\\s+defined|is\\s+a\\s+collection\\s+of|is\\s+information)").containsMatchIn(chunk.text)) {
                    explanatoryBoost += 4.0
                }
            }

            if (isAskingComparison && !isSyllabus) {
                if (Regex("(?i)(whereas|on\\s+the\\s+other\\s+hand|in\\s+contrast|differs?\\s+from|comparison\\s+between)").containsMatchIn(chunk.text)) {
                    explanatoryBoost += 3.5
                }
            }

            if (isAskingArchitecture && !isSyllabus) {
                if (Regex("(?i)(architecture|namenode|datanode|master|worker|layer|hdfs)").containsMatchIn(chunk.text)) {
                    explanatoryBoost += 4.0
                }
            }

            if (isAskingFaultTolerance && !isSyllabus) {
                if (Regex("(?i)(fault\\s*tolerance|heartbeat|replication|recovery|failure)").containsMatchIn(chunk.text)) {
                    explanatoryBoost += 4.5
                }
            }

            val finalScore = if (matchedTerms.isEmpty() && semanticSim < 0.35) {
                0.0
            } else {
                (semanticSim * 12.0) + (min(bm25Score, 8.0) * 0.6) + explanatoryBoost + syllabusPenalty
            }

            Triple(chunk, finalScore, Pair(semanticSim, explanatoryBoost))
        }

        // Rank by final hybrid score
        return scored
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(topK)
            .map { (chunk, totalScore, scores) ->
                RetrievedContextChunk(
                    chunkId = chunk.chunkId,
                    materialId = chunk.materialId,
                    materialTitle = chunk.materialTitle,
                    chunkIndex = chunk.chunkIndex,
                    text = chunk.text,
                    score = Math.round(totalScore * 100.0) / 100.0,
                    matchedTerms = tokenize(query).filter { chunk.text.lowercase().contains(it) }.distinct(),
                    semanticScore = Math.round(scores.first * 100.0) / 100.0,
                    explanatoryBoost = Math.round(scores.second * 100.0) / 100.0
                )
            }
    }

    /**
     * Backward-compatible BM25 caller that delegates to rankChunksHybrid
     */
    fun rankChunksBM25(
        query: String,
        candidateChunks: List<CandidateChunk>,
        k1: Double = 1.5,
        b: Double = 0.75,
        topK: Int = 4
    ): List<RetrievedContextChunk> {
        return rankChunksHybrid(query, candidateChunks, topK)
    }

    /**
     * Builds the structured curriculum prompt for Gemma 3 1B using the exact required format:
     *
     * You are a helpful teacher assistant. Using ONLY the context below, answer the student's question.
     * If the answer isn't in the context, say you don't have that information in the uploaded materials.
     *
     * Context:
     * {retrieved_chunks}
     *
     * Question: {student_question}
     *
     * Answer:
     *
     * Truncates lowest-ranked chunks first if the combined prompt approaches Gemma 3 1B context window.
     */
    /**
     * Cleans an individual chunk for model prompting:
     * - Removes syllabus / course outline sentences
     * - Fixes inline numbered lists onto separate lines
     * - Strips website stamps / page numbers
     */
    /**
     * Cleans an individual chunk for model prompting:
     * - Removes syllabus / course outline sentences and topic lists
     * - Re-splits inline numbered points onto separate lines
     * - Strips website stamps / page numbers
     * - Strips trailing incomplete fragments like "There are two main types of data: 1."
     */
    fun cleanChunkForPrompt(chunkText: String): String {
        if (chunkText.isBlank()) return ""
        var text = cleanPdfText(chunkText)

        // Re-split numbered points that are joined inline ("1. Volume ... 2. Velocity ... 3. Variety") onto separate lines
        text = text.replace(Regex("(?<=[^\\n])\\s+(?=\\b(?:\\d+|[a-zA-Z])[.)]\\s+[A-Za-z])"), "\n")
        text = text.replace(Regex("(?<=[^\\n])\\s+(?=[•*\\-]\\s+[A-Za-z])"), "\n")

        // Split into lines and individual sentences
        val cleanedLines = mutableListOf<String>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isBlank() || isSyllabusSentence(line)) continue

            // If line contains multiple sentences, filter out syllabus sentences from inside the line
            val sentences = line.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotBlank() }
            val validSentences = sentences.filterNot { isSyllabusSentence(it) }
            if (validSentences.isNotEmpty()) {
                cleanedLines.add(validSentences.joinToString(" "))
            }
        }

        // Clean dangling incomplete sentences at the end (e.g. "There are two main types of data: 1." or empty label "Velocity:")
        while (cleanedLines.isNotEmpty()) {
            val last = cleanedLines.last().trim()
            if (Regex("(?i)(?:there are [^.!?:]+:\\s*(?:\\d+\\.?)?|:\\s*\\d*\\.?)$").containsMatchIn(last) ||
                Regex("""^\s*(?:[•*\-]|\d+[.)])?\s*[A-Za-z0-9\s]{2,30}:\s*$""").matches(last) ||
                (last.endsWith(":") && !last.startsWith("#") && !isHeadingLine(last))) {
                cleanedLines.removeAt(cleanedLines.size - 1)
            } else {
                break
            }
        }

        return cleanedLines.joinToString("\n").trim()
    }

    /**
     * Builds the structured curriculum prompt for Gemma 3 1B using the exact required format:
     *
     * You are a teaching assistant for {subject}. Using ONLY the context below, answer the student's question clearly in your own words. Give a short definition first, then bullet points if the context lists items. Do not copy text word for word. Do not mention the context or page numbers. If the context does not contain the answer, reply exactly: 'I couldn't find this in the uploaded materials.'
     *
     * Context:
     * {top 3 cleaned chunks}
     *
     * Question: {question}
     *
     * Answer:
     */
    fun buildRagPrompt(
        subjectName: String,
        question: String,
        retrievedChunks: List<RetrievedContextChunk>,
        maxTotalChars: Int = 4500
    ): String {
        val top3Chunks = retrievedChunks.take(3)
        val cleanedChunks = top3Chunks.map { cleanChunkForPrompt(it.text) }.filter { it.isNotBlank() }

        val contextText = if (cleanedChunks.isNotEmpty()) {
            cleanedChunks.joinToString("\n\n")
        } else {
            "I couldn't find this in the uploaded materials."
        }

        return "You are a teaching assistant for $subjectName. Using ONLY the context below, answer the student's question clearly in your own words. Give a short definition first, then bullet points if the context lists items. Do not copy text word for word. Do not mention the context or page numbers. If the context does not contain the answer, reply exactly: 'I couldn't find this in the uploaded materials.'\n\nContext:\n$contextText\n\nQuestion: $question\n\nAnswer:"
    }
}
