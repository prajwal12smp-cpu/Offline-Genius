package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.local.entities.StudyMaterialEntity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object PdfStorageManager {

    private const val TAG = "PdfStorageManager"
    private const val MATERIALS_DIR_NAME = "study_materials"
    private const val PAGE_WIDTH = 595 // Standard A4 in points (72 dpi)
    private const val PAGE_HEIGHT = 842 // Standard A4 in points (72 dpi)
    private const val MAX_TEXT_EXTRACT_BYTES = 2 * 1024 * 1024 // 2MB max text scan to prevent OOM

    /**
     * Directory inside internal app storage dedicated to study material files.
     */
    fun getMaterialsDirectory(context: Context): File {
        val dir = File(context.filesDir, MATERIALS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Format byte count into human-readable string (e.g. "124 KB", "2.4 MB")
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = bytes / 1024.0
        return if (kb < 1024) {
            String.format(Locale.getDefault(), "%.1f KB", max(0.5, kb))
        } else {
            val mb = kb / 1024.0
            String.format(Locale.getDefault(), "%.1f MB", mb)
        }
    }

    /**
     * Resolves display file name from a content Uri
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "material_${System.currentTimeMillis()}.pdf"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val displayName = cursor.getString(nameIndex)
                    if (!displayName.isNullOrBlank()) {
                        name = displayName
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve file name from Uri: ${e.message}")
        }
        return name
    }

    /**
     * Copies an uploaded file from a Uri into the app's internal storage with a bounded 8KB buffer.
     * Prevents loading large files entirely into RAM.
     */
    suspend fun saveUploadedFile(
        context: Context,
        uri: Uri,
        customName: String? = null
    ): Pair<File, Long> = withContext(Dispatchers.IO) {
        val originalName = customName ?: getFileNameFromUri(context, uri)
        val cleanName = originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val finalFileName = if (cleanName.endsWith(".pdf", ignoreCase = true)) cleanName else "$cleanName.pdf"

        val dir = getMaterialsDirectory(context)
        val targetFile = File(dir, "${System.currentTimeMillis()}_$finalFileName")

        val inputStream: InputStream = try {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open input stream for Uri: $uri")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed opening input stream for Uri: $uri", e)
            throw IllegalStateException("Cannot access the chosen file: ${e.localizedMessage ?: "File inaccessible"}")
        }

        try {
            inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed copying file bytes to local target", e)
            if (targetFile.exists()) targetFile.delete()
            throw IllegalStateException("Failed to save PDF to local storage: ${e.localizedMessage}")
        }

        if (!targetFile.exists() || targetFile.length() == 0L) {
            if (targetFile.exists()) targetFile.delete()
            throw IllegalStateException("Uploaded PDF was empty (0 bytes).")
        }

        Log.d(TAG, "Uploaded file saved to: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
        Pair(targetFile, targetFile.length())
    }

    /**
     * Ensures that a StudyMaterialEntity has a valid local PDF file on disk.
     */
    suspend fun ensureMaterialPdfFile(
        context: Context,
        material: StudyMaterialEntity
    ): File = withContext(Dispatchers.IO) {
        val dir = getMaterialsDirectory(context)

        // Check if existing file is valid
        if (!material.localFilePath.isNullOrBlank()) {
            val existing = File(material.localFilePath)
            if (existing.exists() && existing.length() > 0) {
                return@withContext existing
            }
        }

        // Target file path
        val safeName = material.fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
        }
        val targetFile = File(dir, "mat_${material.id}_$safeName")
        if (targetFile.exists() && targetFile.length() > 0) {
            return@withContext targetFile
        }

        // Generate PDF from raw text
        generatePdfDocument(
            title = material.title,
            rawText = material.rawText,
            fileName = material.fileName,
            targetFile = targetFile
        )

        targetFile
    }

    data class TextValidationResult(
        val isValid: Boolean,
        val totalWords: Int,
        val validWordCount: Int,
        val validWordRatio: Double,
        val warningMessage: String? = null
    )

    /**
     * Initializes PDFBox resource loader safely once.
     */
    fun initPdfBox(context: Context) {
        if (!PDFBoxResourceLoader.isReady()) {
            try {
                PDFBoxResourceLoader.init(context.applicationContext)
            } catch (e: Throwable) {
                Log.w(TAG, "PDFBox initialization note: ${e.message}", e)
            }
        }
    }

    /**
     * Validates extracted text to verify it contains real readable content and not corrupted
     * gibberish, broken syllables ("mits val pa th or"), or binary noise.
     */
    fun validateExtractedText(text: String): TextValidationResult {
        if (text.isBlank() || text.length < 30) {
            return TextValidationResult(
                isValid = false,
                totalWords = 0,
                validWordCount = 0,
                validWordRatio = 0.0,
                warningMessage = "This PDF's text couldn't be extracted cleanly — try a text-based PDF instead of a scanned one"
            )
        }

        // 1. Check for raw unparsed PDF syntax artifacts or locale tags indicating corrupted extraction
        val hasRawPdfArtifacts = Regex("(?i)\\b(en|fr|es|de)[-_][a-zA-Z]{2,}\\b|/Lang|/Span|/Type|/Catalog|<<|>>|\\(\\s*Se\\s+").containsMatchIn(text)
        if (hasRawPdfArtifacts) {
            return TextValidationResult(
                isValid = false,
                totalWords = 0,
                validWordCount = 0,
                validWordRatio = 0.0,
                warningMessage = "This PDF's text couldn't be extracted cleanly — try a text-based PDF instead of a scanned one"
            )
        }

        // Tokenize words, stripping punctuation
        val tokens = text.split(Regex("[\\s\\p{Punct}&&[^-]]+")).filter { it.isNotBlank() }
        if (tokens.size < 8) {
            return TextValidationResult(
                isValid = false,
                totalWords = tokens.size,
                validWordCount = 0,
                validWordRatio = 0.0,
                warningMessage = "This PDF's text couldn't be extracted cleanly — try a text-based PDF instead of a scanned one"
            )
        }

        val vowels = setOf('a', 'e', 'i', 'o', 'u', 'y')
        val standardShortWords = setOf(
            "a", "i", "an", "as", "at", "be", "by", "do", "go", "he", "if", "in",
            "is", "it", "me", "my", "no", "of", "on", "or", "so", "to", "up", "us", "we"
        )

        var shortFragmentCount = 0
        var validWords = 0
        for (token in tokens) {
            val lower = token.lowercase()
            val letterCount = lower.count { it in 'a'..'z' }
            if (letterCount in 1..2 && lower !in standardShortWords) {
                shortFragmentCount++
            }
            if (letterCount >= 3) {
                val hasVowel = lower.any { it in vowels }
                val hasExtremeConsonants = Regex("[bcdfghjklmnpqrstvwxz]{5,}").containsMatchIn(lower)
                if (hasVowel && !hasExtremeConsonants) {
                    validWords++
                }
            } else if (lower in standardShortWords) {
                validWords++
            }
        }

        val ratio = validWords.toDouble() / tokens.size
        val avgWordLen = tokens.map { it.length }.average()
        val shortFragmentRatio = shortFragmentCount.toDouble() / tokens.size

        // Clean academic/curriculum text has high valid word ratio, normal word length (avg >= 3.6), and low fragment ratio (< 0.20)
        val isValid = ratio >= 0.60 && avgWordLen >= 3.6 && shortFragmentRatio < 0.20

        return TextValidationResult(
            isValid = isValid,
            totalWords = tokens.size,
            validWordCount = validWords,
            validWordRatio = ratio,
            warningMessage = if (!isValid) "This PDF's text couldn't be extracted cleanly — try a text-based PDF instead of a scanned one" else null
        )
    }

    /**
     * Extracts text content from a local file safely.
     * Uses PdfBox-Android with sortByPosition = true to extract multi-column PDF layouts in proper
     * reading order (left column top-to-bottom, then right column), with fallback to bounded stream reading.
     */
    suspend fun extractTextFromFile(
        file: File,
        displayName: String,
        context: Context? = null
    ): String = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext "Empty document: $displayName"
        }

        if (context != null) {
            initPdfBox(context)
        }

        val isPdf = displayName.endsWith(".pdf", ignoreCase = true) || isPdfHeader(file)

        // 1. Primary extractor: Apache PDFBox for Android
        if (isPdf) {
            try {
                val document = PDDocument.load(file)
                val stripper = PDFTextStripper()
                // Essential for multi-column layouts: orders text by geometric position (top-to-bottom, left-to-right)
                stripper.sortByPosition = true
                stripper.setShouldSeparateByBeads(true)
                val rawText = stripper.getText(document)
                document.close()

                if (!rawText.isNullOrBlank()) {
                    val cleaned = com.example.ai.rag.RagEngine.cleanPdfText(rawText)
                    if (cleaned.isNotBlank()) {
                        return@withContext cleaned
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "PdfBox extraction failed for ${file.name}, trying fallbacks: ${e.message}", e)
            }
        }

        // 2. Secondary fallback: direct plain text or Flate byte stream reading
        try {
            val bytesToRead = min(file.length(), MAX_TEXT_EXTRACT_BYTES.toLong()).toInt()
            val bytes = ByteArray(bytesToRead)
            FileInputStream(file).use { input ->
                var totalRead = 0
                while (totalRead < bytesToRead) {
                    val read = input.read(bytes, totalRead, bytesToRead - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
            }

            // Check if plain text
            val text = String(bytes, Charsets.UTF_8)
            if (text.isNotBlank() && isMostlyPrintable(text)) {
                val cleaned = com.example.ai.rag.RagEngine.cleanPdfText(text)
                return@withContext cleaned.ifBlank { text.take(60_000).trim() }
            }

            // Extract standard PDF text stream tokens safely
            val extractedPdfText = extractTextFromPdfBytes(bytes)
            if (extractedPdfText.isNotBlank()) {
                val cleaned = com.example.ai.rag.RagEngine.cleanPdfText(extractedPdfText)
                return@withContext cleaned
            }

            // For image-only/scanned PDFs
            "Study Material: $displayName\n\n(Curriculum document with visual or scanned diagrams. The full document is ready to view in the offline PDF reader.)"
        } catch (e: Throwable) {
            Log.e(TAG, "Error extracting text from file ${file.name}", e)
            "Study Material: $displayName\n\n(Curriculum content processed from file: $displayName)"
        }
    }

    private fun isPdfHeader(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read == 4 && header[0] == '%'.code.toByte() && header[1] == 'P'.code.toByte() &&
                        header[2] == 'D'.code.toByte() && header[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isMostlyPrintable(str: String): Boolean {
        if (str.isEmpty()) return false
        var printableCount = 0
        val sample = str.take(1000)
        for (ch in sample) {
            if (ch.isWhitespace() || (ch.code in 32..126)) {
                printableCount++
            }
        }
        return (printableCount.toFloat() / sample.length) > 0.80f
    }

    /**
     * Fast, linear O(N) extraction of text from PDF byte streams with FlateDecode decompression support.
     * Extracts text from both compressed PDF streams and uncompressed string literals.
     */
    private fun extractTextFromPdfBytes(bytes: ByteArray): String {
        try {
            val textBuilder = StringBuilder()
            val maxBytes = min(bytes.size, 2_000_000)

            // Step 1: Scan for PDF stream objects and decompress FlateDecode streams
            var idx = 0
            val streamKeyword = "stream".toByteArray(Charsets.US_ASCII)
            val endStreamKeyword = "endstream".toByteArray(Charsets.US_ASCII)
            val flateKeyword = "Flate".toByteArray(Charsets.US_ASCII)

            while (idx < maxBytes - streamKeyword.size && textBuilder.length < 50_000) {
                var matchStream = true
                for (k in streamKeyword.indices) {
                    if (bytes[idx + k] != streamKeyword[k]) {
                        matchStream = false
                        break
                    }
                }

                if (matchStream) {
                    val streamStart = idx + streamKeyword.size
                    var dataStart = streamStart
                    if (dataStart < maxBytes && bytes[dataStart] == '\r'.code.toByte()) dataStart++
                    if (dataStart < maxBytes && bytes[dataStart] == '\n'.code.toByte()) dataStart++

                    // Check if preceding dictionary contains "Flate"
                    val lookbackStart = max(0, idx - 350)
                    var isFlate = false
                    for (p in lookbackStart until idx) {
                        var foundFlate = true
                        for (fk in flateKeyword.indices) {
                            if (p + fk >= idx || bytes[p + fk] != flateKeyword[fk]) {
                                foundFlate = false
                                break
                            }
                        }
                        if (foundFlate) {
                            isFlate = true
                            break
                        }
                    }

                    // Find "endstream"
                    var dataEnd = dataStart
                    while (dataEnd < maxBytes - endStreamKeyword.size) {
                        var matchEnd = true
                        for (k in endStreamKeyword.indices) {
                            if (bytes[dataEnd + k] != endStreamKeyword[k]) {
                                matchEnd = false
                                break
                            }
                        }
                        if (matchEnd) break
                        dataEnd++
                    }

                    if (dataEnd > dataStart) {
                        val streamSlice = bytes.copyOfRange(dataStart, dataEnd)
                        if (isFlate) {
                            val inflated = decompressFlate(streamSlice)
                            if (inflated != null && inflated.isNotEmpty()) {
                                extractStringsFromStream(inflated, textBuilder)
                            }
                        } else {
                            extractStringsFromStream(streamSlice, textBuilder)
                        }
                    }
                    idx = dataEnd + endStreamKeyword.size
                } else {
                    idx++
                }
            }

            // Step 2: Also scan any uncompressed literal strings in the outer PDF
            if (textBuilder.length < 500) {
                extractStringsFromStream(bytes, textBuilder)
            }

            val rawResult = textBuilder.toString().trim()
            return if (rawResult.length > 20) com.example.ai.rag.RagEngine.cleanPdfText(rawResult) else ""
        } catch (e: Throwable) {
            Log.w(TAG, "PDF stream extraction failed: ${e.message}", e)
            return ""
        }
    }

    private fun decompressFlate(compressed: ByteArray): ByteArray? {
        return try {
            val inflater = java.util.zip.Inflater(false)
            inflater.setInput(compressed)
            val outputStream = java.io.ByteArrayOutputStream(compressed.size * 2)
            val buffer = ByteArray(4096)
            while (!inflater.finished() && !inflater.needsInput()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                outputStream.write(buffer, 0, count)
            }
            inflater.end()
            outputStream.toByteArray()
        } catch (_: Exception) {
            try {
                val inflater = java.util.zip.Inflater(true)
                inflater.setInput(compressed)
                val outputStream = java.io.ByteArrayOutputStream(compressed.size * 2)
                val buffer = ByteArray(4096)
                while (!inflater.finished() && !inflater.needsInput()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0) break
                    outputStream.write(buffer, 0, count)
                }
                inflater.end()
                outputStream.toByteArray()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun extractStringsFromStream(streamBytes: ByteArray, textBuilder: StringBuilder) {
        var i = 0
        val maxLen = min(streamBytes.size, 500_000)
        while (i < maxLen && textBuilder.length < 50_000) {
            if (streamBytes[i] == '('.code.toByte()) {
                i++
                val token = StringBuilder()
                var escaped = false
                var depth = 1

                while (i < maxLen && depth > 0 && token.length < 1000) {
                    val b = streamBytes[i]
                    if (escaped) {
                        when (b) {
                            'n'.code.toByte() -> token.append(' ')
                            'r'.code.toByte() -> token.append(' ')
                            't'.code.toByte() -> token.append(' ')
                            '('.code.toByte() -> token.append('(')
                            ')'.code.toByte() -> token.append(')')
                            '\\'.code.toByte() -> token.append('\\')
                            in 32..126 -> token.append(b.toInt().toChar())
                        }
                        escaped = false
                    } else if (b == '\\'.code.toByte()) {
                        escaped = true
                    } else if (b == '('.code.toByte()) {
                        depth++
                        token.append('(')
                    } else if (b == ')'.code.toByte()) {
                        depth--
                        if (depth > 0) token.append(')')
                    } else if (b in 32..126) {
                        token.append(b.toInt().toChar())
                    } else if (b == '\n'.code.toByte() || b == '\r'.code.toByte() || b == '\t'.code.toByte()) {
                        token.append(' ')
                    }
                    i++
                }

                val candidate = token.toString().trim()
                if (candidate.length >= 2 && candidate.any { it.isLetter() }) {
                    textBuilder.append(candidate).append(" ")
                }
            } else {
                i++
            }
        }
    }

    /**
     * Generates a multi-page PDF document using android.graphics.pdf.PdfDocument with pure Kotlin fallback.
     */
    fun generatePdfDocument(
        title: String,
        rawText: String,
        fileName: String,
        targetFile: File
    ) {
        try {
            val document = PdfDocument()

            val marginHorizontal = 40f
            val marginVertical = 45f
            val contentWidth = PAGE_WIDTH - (marginHorizontal * 2)
            val maxContentY = PAGE_HEIGHT - marginVertical

            // Paints
            val headerPaint = Paint().apply {
                color = Color.rgb(15, 23, 42)
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }

            val metaPaint = Paint().apply {
                color = Color.rgb(100, 116, 139)
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                isAntiAlias = true
            }

            val bodyPaint = Paint().apply {
                color = Color.rgb(30, 41, 59)
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                isAntiAlias = true
            }

            val dividerPaint = Paint().apply {
                color = Color.rgb(226, 232, 240)
                strokeWidth = 1.5f
                isAntiAlias = true
            }

            val bannerPaint = Paint().apply {
                color = Color.rgb(241, 245, 249)
                isAntiAlias = true
            }

            val primaryBarPaint = Paint().apply {
                color = Color.rgb(37, 99, 235)
                isAntiAlias = true
            }

            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            val dateString = dateFormat.format(Date())

            val paragraphs = rawText.split(Regex("\n+"))
            val lines = mutableListOf<String>()

            for (paragraph in paragraphs) {
                val words = paragraph.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.isEmpty()) {
                    lines.add("")
                    continue
                }

                var currentLine = StringBuilder()
                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    val measuredWidth = bodyPaint.measureText(testLine)
                    if (measuredWidth <= contentWidth) {
                        currentLine = StringBuilder(testLine)
                    } else {
                        if (currentLine.isNotEmpty()) {
                            lines.add(currentLine.toString())
                        }
                        currentLine = StringBuilder(word)
                    }
                }
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                }
                lines.add("")
            }

            var pageIndex = 1
            var lineCursor = 0

            while (lineCursor < lines.size || pageIndex == 1) {
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex).create()
                val page = document.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                canvas.drawColor(Color.WHITE)

                var currentY = marginVertical

                if (pageIndex == 1) {
                    canvas.drawRect(marginHorizontal, currentY, PAGE_WIDTH - marginHorizontal, currentY + 70f, bannerPaint)
                    canvas.drawRect(marginHorizontal, currentY, marginHorizontal + 6f, currentY + 70f, primaryBarPaint)

                    val displayTitle = if (title.length > 50) title.take(47) + "..." else title
                    canvas.drawText(displayTitle, marginHorizontal + 16f, currentY + 30f, headerPaint)

                    val metaText = "OFFLINE GENIUS SMART CLASSROOM • $dateString • $fileName"
                    canvas.drawText(metaText, marginHorizontal + 16f, currentY + 54f, metaPaint)

                    currentY += 85f
                    canvas.drawLine(marginHorizontal, currentY, PAGE_WIDTH - marginHorizontal, currentY, dividerPaint)
                    currentY += 24f
                } else {
                    canvas.drawText(title.take(60), marginHorizontal, currentY + 12f, metaPaint)
                    currentY += 24f
                    canvas.drawLine(marginHorizontal, currentY, PAGE_WIDTH - marginHorizontal, currentY, dividerPaint)
                    currentY += 24f
                }

                val lineHeight = 19f
                while (lineCursor < lines.size && currentY + lineHeight < maxContentY - 30f) {
                    val line = lines[lineCursor]
                    if (line.isNotEmpty()) {
                        canvas.drawText(line, marginHorizontal, currentY, bodyPaint)
                    }
                    currentY += lineHeight
                    lineCursor++
                }

                val footerText = "Page $pageIndex"
                val footerWidth = metaPaint.measureText(footerText)
                canvas.drawText(footerText, PAGE_WIDTH - marginHorizontal - footerWidth, PAGE_HEIGHT - 25f, metaPaint)
                canvas.drawText("Offline Curriculum Study Material", marginHorizontal, PAGE_HEIGHT - 25f, metaPaint)

                document.finishPage(page)
                pageIndex++

                if (lineCursor >= lines.size) break
            }

            FileOutputStream(targetFile).use { out ->
                document.writeTo(out)
            }
            document.close()
        } catch (_: Throwable) {
            generateSimplePdfFallback(title, rawText, fileName, targetFile)
        }
    }

    /**
     * Minimal pure-Kotlin PDF 1.4 generator fallback.
     */
    fun generateSimplePdfFallback(
        title: String,
        rawText: String,
        fileName: String,
        targetFile: File
    ) {
        val safeTitle = title.replace(Regex("[()\\\\]"), " ")
        val safeText = rawText.take(500).replace(Regex("[()\\\\]"), " ").replace("\n", " ")
        val contentStream = "BT /F1 14 Tf 50 780 Td ($safeTitle) Tj ET BT /F1 10 Tf 50 750 Td ($fileName) Tj ET BT /F1 10 Tf 50 710 Td ($safeText) Tj ET"
        val streamBytes = contentStream.toByteArray(Charsets.ISO_8859_1)

        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()

        fun addObj(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }

        addObj("<< /Type /Catalog /Pages 2 0 R >>")
        addObj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        addObj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
        addObj("<< /Length ${streamBytes.size} >>\nstream\n$contentStream\nendstream")
        addObj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

        val startXref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) {
            sb.append(String.format(Locale.US, "%010d 00000 n \n", offset))
        }
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\n")
        sb.append("startxref\n$startXref\n%%EOF\n")

        targetFile.writeText(sb.toString(), Charsets.ISO_8859_1)
    }

    /**
     * Copies a study material PDF file from internal app storage to the user's public Downloads folder.
     */
    suspend fun saveFileToPublicDownloads(
        context: Context,
        sourceFile: File,
        targetFileName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return@withContext Result.failure(IllegalStateException("Source PDF file does not exist on device."))
        }

        val cleanName = targetFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext Result.failure(Exception("Failed to access system Downloads repository."))

                try {
                    resolver.openOutputStream(uri)?.use { output ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: return@withContext Result.failure(Exception("Could not write to Downloads directory."))

                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)

                    Result.success("Saved to Downloads: $cleanName")
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!publicDownloadsDir.exists()) {
                    publicDownloadsDir.mkdirs()
                }

                val destFile = File(publicDownloadsDir, cleanName)
                sourceFile.copyTo(destFile, overwrite = true)
                Result.success("Saved to Downloads: ${destFile.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(Exception("Download failed: ${e.localizedMessage ?: "Low disk storage space."}"))
        }
    }

    /**
     * Renders pages of a PDF file into a list of Bitmaps using android.graphics.pdf.PdfRenderer.
     */
    suspend fun renderPdfPages(
        file: File,
        scale: Float = 1.6f,
        maxPages: Int = 30
    ): Result<List<Bitmap>> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext Result.failure(IllegalStateException("PDF file not found at: ${file.absolutePath}"))
        }

        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val bitmaps = mutableListOf<Bitmap>()

        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)

            val pageCount = min(renderer.pageCount, maxPages)

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val targetW = max(1, (page.width * scale).toInt())
                val targetH = max(1, (page.height * scale).toInt())

                val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                bitmaps.add(bitmap)
            }

            Result.success(bitmaps)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed rendering PDF pages", e)
            Result.failure(e)
        } finally {
            try {
                renderer?.close()
                pfd?.close()
            } catch (_: Exception) {}
        }
    }
}
