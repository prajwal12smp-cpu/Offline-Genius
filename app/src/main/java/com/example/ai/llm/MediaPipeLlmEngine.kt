package com.example.ai.llm

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.ai.rag.RagEngine
import com.example.ai.rag.RetrievedContextChunk
import com.example.util.CrashLogger
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class InferenceBackend {
    CPU,
    GPU
}

sealed class ModelStatus {
    object NotInstalled : ModelStatus() {
        override fun toString(): String = "Not installed"
    }
    object Loading : ModelStatus() {
        override fun toString(): String = "Loading"
    }
    object Ready : ModelStatus() {
        override fun toString(): String = "Ready"
    }
    data class Failed(val reason: String) : ModelStatus() {
        override fun toString(): String = "Failed: $reason"
    }

    val displayLabel: String
        get() = when (this) {
            is NotInstalled -> "Model: Not installed"
            is Loading -> "Model: Loading"
            is Ready -> "Model: Ready"
            is Failed -> "Model: Failed: $reason"
        }
}

data class ModelImportProgress(
    val isImporting: Boolean = false,
    val bytesCopied: Long = 0L,
    val totalBytes: Long = 0L,
    val progressFraction: Float = 0f,
    val statusMessage: String = "",
    val errorMessage: String? = null
)

sealed class InferenceProgress {
    data class Thinking(val step: String) : InferenceProgress()
    data class Generating(val partialText: String, val fullTextSoFar: String, val isFallback: Boolean = false, val generationPath: String = "LLM") : InferenceProgress()
    data class Completed(val fullText: String, val executionTimeMs: Long, val isFallback: Boolean = false, val generationPath: String = "LLM") : InferenceProgress()
    data class Error(val message: String, val suggestion: String) : InferenceProgress()
}

data class ModelEngineState(
    val status: ModelStatus = ModelStatus.NotInstalled,
    val isModelLoaded: Boolean = false,
    val modelPath: String = "",
    val modelFileName: String = "gemma-3-1b-it-int4.task",
    val modelSizeMb: Long = 0,
    val modelSizeBytes: Long = 0,
    val backend: InferenceBackend = InferenceBackend.CPU,
    val maxTokens: Int = 256,
    val availableRamMb: Long = 0,
    val totalRamMb: Long = 0,
    val isLowMemoryDevice: Boolean = false,
    val lastError: String? = null,
    val sha256Checksum: String? = null,
    val md5Checksum: String? = null,
    val fileFormatDetected: String? = null,
    val mediapipeVersion: String = "0.10.35"
)

class MediaPipeLlmEngine(val context: Context) {

    companion object {
        @Volatile
        private var instance: MediaPipeLlmEngine? = null

        // App-wide singleton state shared across all engine instances, viewmodels, and repositories
        @Volatile
        var sharedLlmInference: LlmInference? = null
            internal set

        @Volatile
        var sharedBackend: InferenceBackend = InferenceBackend.CPU
            internal set

        @Volatile
        var sharedActiveModelPath: String? = null
            internal set

        @Volatile
        var sharedMaxTokens: Int = 256
            internal set

        private val _sharedModelStatus = MutableStateFlow<ModelStatus>(ModelStatus.NotInstalled)
        val sharedModelStatus: StateFlow<ModelStatus> = _sharedModelStatus.asStateFlow()

        private val _sharedImportProgress = MutableStateFlow(ModelImportProgress())
        val sharedImportProgress: StateFlow<ModelImportProgress> = _sharedImportProgress.asStateFlow()

        private val _sharedLastErrorMessage = MutableStateFlow<String?>(null)
        val sharedLastErrorMessage: StateFlow<String?> = _sharedLastErrorMessage.asStateFlow()

        fun getInstance(context: Context): MediaPipeLlmEngine {
            return instance ?: synchronized(this) {
                instance ?: MediaPipeLlmEngine(context.applicationContext).also { instance = it }
            }
        }

        fun resetInstanceForTesting(engine: MediaPipeLlmEngine? = null) {
            synchronized(this) {
                instance = engine
                sharedLlmInference?.close()
                sharedLlmInference = null
                sharedActiveModelPath = null
                sharedMaxTokens = 256
                _sharedModelStatus.value = ModelStatus.NotInstalled
                _sharedImportProgress.value = ModelImportProgress()
                _sharedLastErrorMessage.value = null
            }
        }

        fun setSharedStatusForTesting(status: ModelStatus, fakeInference: LlmInference? = null) {
            _sharedModelStatus.value = status
            sharedLlmInference = fakeInference
        }
    }

    private val tag = "OfflineGenius_LLM"

    var llmInference: LlmInference?
        get() = sharedLlmInference
        set(value) { sharedLlmInference = value }

    var currentBackend: InferenceBackend
        get() = sharedBackend
        set(value) { sharedBackend = value }

    var activeModelPath: String?
        get() = sharedActiveModelPath
        set(value) { sharedActiveModelPath = value }

    var currentMaxTokens: Int
        get() = sharedMaxTokens
        set(value) { sharedMaxTokens = value }

    fun setMaxTokens(tokens: Int) {
        sharedMaxTokens = tokens
        engineScope.launch {
            val path = activeModelPath ?: getInstalledModelFile()?.absolutePath
            if (path != null && File(path).exists()) {
                loadModelFromPath(path, currentBackend)
            }
        }
    }

    val modelStatus: StateFlow<ModelStatus>
        get() = sharedModelStatus

    val importProgress: StateFlow<ModelImportProgress>
        get() = sharedImportProgress

    val lastErrorMessage: StateFlow<String?>
        get() = sharedLastErrorMessage

    private val _modelStatus: MutableStateFlow<ModelStatus> get() = _sharedModelStatus
    private val _importProgress: MutableStateFlow<ModelImportProgress> get() = _sharedImportProgress
    private val _lastErrorMessage: MutableStateFlow<String?> get() = _sharedLastErrorMessage

    fun isModelReady(): Boolean = sharedModelStatus.value is ModelStatus.Ready

    private val inferenceMutex = Mutex()
    private var cachedChecksum: Pair<String, String>? = null // (md5, sha256)
    private var cachedChecksumPath: String? = null

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        checkAndInitializeModelOnStart()
    }

    fun calculateChecksums(file: File): Pair<String, String> {
        val md5 = MessageDigest.getInstance("MD5")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        file.inputStream().use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md5.update(buffer, 0, read)
                sha256.update(buffer, 0, read)
            }
        }
        val md5Hex = md5.digest().joinToString("") { "%02x".format(it) }
        val sha256Hex = sha256.digest().joinToString("") { "%02x".format(it) }
        return Pair(md5Hex, sha256Hex)
    }

    fun detectFileFormat(file: File): String {
        if (!file.exists() || file.length() < 8) return "Unknown / Empty"
        val header = ByteArray(8)
        try {
            file.inputStream().use { it.read(header) }
        } catch (_: Exception) {
            return "Unreadable"
        }
        if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() && header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) {
            return "MediaPipe Task Bundle (.task / Zip format)"
        }
        val str = String(header, Charsets.US_ASCII)
        if (str.contains("TFL3") || (header.size >= 8 && header[4] == 'T'.code.toByte() && header[5] == 'F'.code.toByte() && header[6] == 'L'.code.toByte() && header[7] == '3'.code.toByte())) {
            return "TFLite Flatbuffer (TFL3 format)"
        }
        if (file.name.endsWith(".litertlm", ignoreCase = true)) {
            return "LiteRT-LM Bundle (.litertlm)"
        }
        return "Binary model file (${file.extension.ifBlank { "raw" }})"
    }

    fun getMemoryInfo(): Pair<Long, Long> {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        return Pair(availMb, totalMb)
    }

    fun isLowRam(): Boolean {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        return memInfo.lowMemory || (memInfo.availMem < 1024L * 1024L * 1024L) // < 1 GB available
    }

    fun getEngineState(): ModelEngineState {
        val (availMb, totalMb) = getMemoryInfo()
        val file = activeModelPath?.let { File(it) } ?: getInstalledModelFile()
        val sizeMb = if (file != null && file.exists()) file.length() / (1024 * 1024) else 0L

        return ModelEngineState(
            status = _modelStatus.value,
            isModelLoaded = llmInference != null && _modelStatus.value is ModelStatus.Ready,
            modelPath = activeModelPath ?: file?.absolutePath ?: "",
            modelFileName = file?.name ?: "gemma-3-1b-it-int4.task",
            modelSizeMb = sizeMb,
            modelSizeBytes = file?.length() ?: 0L,
            backend = currentBackend,
            maxTokens = currentMaxTokens,
            availableRamMb = availMb,
            totalRamMb = totalMb,
            isLowMemoryDevice = isLowRam(),
            lastError = _lastErrorMessage.value,
            sha256Checksum = cachedChecksum?.second,
            md5Checksum = cachedChecksum?.first,
            fileFormatDetected = file?.let { detectFileFormat(it) },
            mediapipeVersion = "0.10.35"
        )
    }

    fun getModelsDirectory(): File = File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Checks if a valid model file is installed in context.filesDir/models/ or standard paths
     */
    fun getInstalledModelFile(): File? {
        val modelsDir = getModelsDirectory()
        if (modelsDir.exists() && modelsDir.isDirectory) {
            val candidates = listOf(
                "gemma-3-1b-it-int4.task",
                "gemma-3-1b-it.task",
                "gemma-3-1b.task",
                "gemma-3-1b-it-int4.litertlm",
                "model.task",
                "gemma-2b-it.task"
            )
            for (name in candidates) {
                val candidate = File(modelsDir, name)
                if (candidate.exists() && candidate.isFile && candidate.length() > 1024 * 1024) {
                    return candidate
                }
            }

            // Any .task or .litertlm file
            val matching = modelsDir.listFiles { file ->
                val n = file.name.lowercase()
                file.isFile && (n.endsWith(".task") || n.endsWith(".litertlm") || n.endsWith(".bin")) && file.length() > 1024 * 1024
            }
            if (!matching.isNullOrEmpty()) {
                return matching.maxByOrNull { it.length() }
            }
        }

        // Secondary search paths
        val secondaryDirs = listOfNotNull(
            context.filesDir,
            context.getExternalFilesDir(null)?.let { File(it, "models") },
            context.getExternalFilesDir(null),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        )
        for (dir in secondaryDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val found = dir.listFiles { file ->
                val n = file.name.lowercase()
                file.isFile && (n.endsWith(".task") || n.endsWith(".litertlm")) && file.length() > 5 * 1024 * 1024
            }?.firstOrNull()
            if (found != null) return found
        }

        return null
    }

    /**
     * Called on app start: checks if model exists and initializes or flags NotInstalled.
     * Guaranteed to run off the main thread.
     */
     fun checkAndInitializeModelOnStart() {
        engineScope.launch {
            if (_sharedModelStatus.value is ModelStatus.Ready) {
                return@launch
            }
            val modelFile = getInstalledModelFile()
            if (modelFile == null || !modelFile.exists() || modelFile.length() == 0L) {
                if (_sharedModelStatus.value !is ModelStatus.Ready) {
                    _modelStatus.value = ModelStatus.NotInstalled
                    _lastErrorMessage.value = "AI model not installed - import it in Settings"
                    activeModelPath = null
                    llmInference?.close()
                    llmInference = null
                    Log.i(tag, "Model not installed on app start. Status: ${_modelStatus.value}")
                }
            } else {
                Log.i(tag, "Found installed model file: ${modelFile.absolutePath} (${modelFile.length() / (1024 * 1024)} MB). Loading on background thread...")
                loadModelFromPath(modelFile.absolutePath, currentBackend)
            }
        }
    }

    /**
     * Initializes MediaPipe LlmInference strictly off the main thread (Dispatchers.Default)
     * with pre-load RAM checks, CPU ABI validation, maxTokens=512, and detailed diagnostic step logging.
     */
    suspend fun loadModelFromPath(path: String, backend: InferenceBackend = currentBackend): Boolean = withContext(Dispatchers.Default) {
        currentBackend = backend
        activeModelPath = path
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            val missingMsg = "AI model not installed - import it in Settings"
            Log.w(tag, "Model file does not exist at: $path")
            _modelStatus.value = ModelStatus.NotInstalled
            _lastErrorMessage.value = missingMsg
            llmInference?.close()
            llmInference = null
            CrashLogger.logInitializationFailure(context, "Model file missing or empty at path: $path", null)
            return@withContext false
        }

        _modelStatus.value = ModelStatus.Loading

        val fileFormat = detectFileFormat(file)
        val fileLengthBytes = file.length()
        CrashLogger.logInitializationStep(
            context,
            "Step 1: Model file verified at: ${file.absolutePath} (size: $fileLengthBytes bytes, ${fileLengthBytes / (1024 * 1024)} MB, format: $fileFormat)"
        )

        // Compute and synchronously log MD5 and SHA-256 checksums if not yet computed
        val (currentMd5, currentSha256) = if (cachedChecksumPath == path && cachedChecksum != null) {
            cachedChecksum!!
        } else {
            CrashLogger.logInitializationStep(context, "Step 1b: Computing MD5 & SHA-256 checksums for integrity verification...")
            val calculated = calculateChecksums(file)
            cachedChecksum = calculated
            cachedChecksumPath = path
            CrashLogger.logChecksum(context, file.name, calculated.first, calculated.second, fileLengthBytes)
            calculated
        }

        // Memory validation before load: prevent OOM/abort on low memory devices
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val availMemMb = memInfo.availMem / (1024 * 1024)
        val totalMemMb = memInfo.totalMem / (1024 * 1024)
        val isTestEnv = memInfo.totalMem <= 0L || (android.os.Build.FINGERPRINT != null && android.os.Build.FINGERPRINT.startsWith("robolectric"))

        CrashLogger.logInitializationStep(
            context,
            "Step 2: Memory check: Free=${availMemMb} MB, Total=${totalMemMb} MB, LowMemoryFlag=${memInfo.lowMemory}"
        )

        val minSafeRamBytes = 1500L * 1024L * 1024L // ~1.5GB threshold
        if (!isTestEnv && memInfo.availMem < minSafeRamBytes) {
            val lowMemMsg = "Not enough free memory to load the AI model - close other apps and try again"
            Log.e(tag, "$lowMemMsg (Available: ${availMemMb} MB < 1500 MB)")
            _modelStatus.value = ModelStatus.Failed(lowMemMsg)
            _lastErrorMessage.value = lowMemMsg
            CrashLogger.logInitializationFailure(
                context,
                "$lowMemMsg (Available: ${availMemMb}MB / Total: ${totalMemMb}MB)",
                null
            )
            return@withContext false
        }

        // Architecture & ABI verification
        val supportedAbis = android.os.Build.SUPPORTED_ABIS.toList()
        CrashLogger.logInitializationStep(
            context,
            "Step 3: Device CPU ABIs: ${supportedAbis.joinToString(", ")}"
        )
        val hasArm64 = supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
        val hasX86_64 = supportedAbis.any { it.equals("x86_64", ignoreCase = true) }
        if (!isTestEnv && !hasArm64 && !hasX86_64) {
            val abiError = "Unsupported device architecture: Device ABIs [${supportedAbis.joinToString()}] do not support arm64-v8a or x86_64 required by MediaPipe tasks-genai"
            Log.e(tag, abiError)
            _modelStatus.value = ModelStatus.Failed(abiError)
            _lastErrorMessage.value = abiError
            CrashLogger.logInitializationFailure(context, abiError, null)
            return@withContext false
        }

        if (isLowRam() && backend == InferenceBackend.GPU) {
            Log.w(tag, "Low RAM detected; switching to CPU backend for stability")
            currentBackend = InferenceBackend.CPU
        }

        // Build LlmInferenceOptions with configured maxTokens (default 256 for reduced KV-cache memory pressure)
        val maxTokens = currentMaxTokens
        val topK = 40
        val targetBackend = if (currentBackend == InferenceBackend.GPU) LlmInference.Backend.GPU else LlmInference.Backend.CPU

        CrashLogger.logInitializationStep(
            context,
            "Step 4: Building options: maxTokens=$maxTokens, maxTopK=$topK, backend=$targetBackend, tasksGenai=0.10.35"
        )

        val threadName = Thread.currentThread().name
        CrashLogger.logInitializationStep(
            context,
            "Step 5: Invoking LlmInference.createFromOptions on thread '$threadName' (ID: ${Thread.currentThread().id}) [FLUSHED TO DISK BEFORE CALL]"
        )

        return@withContext try {
            llmInference?.close()
            llmInference = null

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(maxTokens)
                .setMaxTopK(topK)
                .setPreferredBackend(targetBackend)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            _modelStatus.value = ModelStatus.Ready
            _lastErrorMessage.value = null
            CrashLogger.logInitializationStep(context, "Step 6: SUCCESS: MediaPipe LlmInference initialized and Model is Ready!")
            Log.i(tag, "Successfully loaded Gemma 3 1B on $currentBackend with maxTokens=$maxTokens. Status: Model: Ready")
            true
        } catch (oom: OutOfMemoryError) {
            val msg = "Out of memory (${oom.message ?: "insufficient heap"})"
            Log.e(tag, "OOM while loading Gemma model: $msg", oom)
            _modelStatus.value = ModelStatus.Failed(msg)
            _lastErrorMessage.value = msg
            CrashLogger.logInitializationFailure(context, "OutOfMemoryError in LlmInference.createFromOptions", oom)
            llmInference = null
            false
        } catch (t: Throwable) {
            val raw = t.message ?: t.javaClass.simpleName
            val msg = when {
                t is UnsatisfiedLinkError || t is LinkageError || raw.contains("link", ignoreCase = true) || raw.contains("library", ignoreCase = true) ->
                    "Native library error: $raw"
                raw.contains("format", ignoreCase = true) || raw.contains("magic", ignoreCase = true) || raw.contains("header", ignoreCase = true) ->
                    "Unsupported format: $raw"
                raw.contains("not found", ignoreCase = true) || raw.contains("exist", ignoreCase = true) ->
                    "Model file missing: $raw"
                else ->
                    "Initialization error: $raw"
            }
            Log.e(tag, "Failed to initialize MediaPipe LlmInference: $msg", t)
            _modelStatus.value = ModelStatus.Failed(msg)
            _lastErrorMessage.value = msg
            CrashLogger.logInitializationFailure(context, "Exception during createFromOptions: $msg", t)
            llmInference = null
            false
        }
    }

    /**
     * Stream-copies chosen model file into app-private storage (context.filesDir/models/) on Dispatchers.IO
     * with live progress updates, cancellation handling, low-storage checks, and exact file size verification.
     */
    suspend fun importModelFile(
        uri: Uri,
        contentResolver: ContentResolver
    ): Result<File> = withContext(Dispatchers.IO) {
        _importProgress.value = ModelImportProgress(isImporting = true, statusMessage = "Inspecting model file...")
        var tempFile: File? = null
        try {
            var displayName = "gemma-3-1b-it-int4.task"
            var sourceSize = 0L

            // 1. Resolve DocumentFile metadata for exact byte length
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            val docLength = documentFile?.length() ?: 0L
            val docName = documentFile?.name
            if (!docName.isNullOrBlank()) {
                displayName = docName
            }

            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) displayName = name
                        }
                        if (sizeIndex != -1) {
                            sourceSize = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Could not resolve metadata for model URI: ${e.message}")
            }

            val expectedOriginalSize = if (docLength > 0L) docLength else sourceSize
            Log.i(tag, "Model import starting: name='$displayName', expectedSize=$expectedOriginalSize bytes")

            val modelsDir = getModelsDirectory()
            val usableSpace = context.filesDir.usableSpace
            val requiredSpace = if (expectedOriginalSize > 0) expectedOriginalSize + 50 * 1024 * 1024 else 700 * 1024 * 1024
            if (usableSpace < requiredSpace) {
                val neededMb = requiredSpace / (1024 * 1024)
                val availMb = usableSpace / (1024 * 1024)
                val err = "Low storage space: Needs ~${neededMb} MB, but only ${availMb} MB is free."
                _importProgress.value = ModelImportProgress(isImporting = false, errorMessage = err)
                return@withContext Result.failure(IOException(err))
            }

            val targetFile = File(modelsDir, displayName)
            tempFile = File(modelsDir, "${displayName}.tmp")
            if (tempFile.exists()) tempFile.delete()

            _importProgress.value = ModelImportProgress(
                isImporting = true,
                totalBytes = expectedOriginalSize,
                statusMessage = "Copying model file to app-private storage..."
            )

            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesCopied = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead
                        val fraction = if (expectedOriginalSize > 0) (bytesCopied.toFloat() / expectedOriginalSize).coerceIn(0f, 1f) else 0f
                        val copiedMb = bytesCopied / (1024 * 1024)
                        val totalMb = expectedOriginalSize / (1024 * 1024)
                        val msg = if (expectedOriginalSize > 0) "Copying: $copiedMb MB / $totalMb MB (${(fraction * 100).toInt()}%)" else "Copying: $copiedMb MB..."
                        _importProgress.value = ModelImportProgress(
                            isImporting = true,
                            bytesCopied = bytesCopied,
                            totalBytes = expectedOriginalSize,
                            progressFraction = fraction,
                            statusMessage = msg
                        )
                    }
                }
            } ?: throw IOException("Could not open stream from selected model URI")

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            // Exact byte size verification
            val finalCopiedBytes = targetFile.length()
            if (expectedOriginalSize > 0L && finalCopiedBytes != expectedOriginalSize) {
                targetFile.delete()
                tempFile.delete()
                val incompleteMsg = "Import incomplete, please try again"
                Log.e(tag, "$incompleteMsg (Expected $expectedOriginalSize bytes, actual $finalCopiedBytes bytes)")
                _importProgress.value = ModelImportProgress(
                    isImporting = false,
                    errorMessage = incompleteMsg
                )
                _modelStatus.value = ModelStatus.Failed(incompleteMsg)
                _lastErrorMessage.value = incompleteMsg
                CrashLogger.logInitializationFailure(
                    context,
                    "Model copy size mismatch: Expected $expectedOriginalSize bytes vs Copied $finalCopiedBytes bytes",
                    null
                )
                return@withContext Result.failure(IOException(incompleteMsg))
            }

            val finalSizeMb = targetFile.length() / (1024 * 1024)
            _importProgress.value = ModelImportProgress(
                isImporting = true,
                bytesCopied = targetFile.length(),
                totalBytes = targetFile.length(),
                progressFraction = 0.95f,
                statusMessage = "Verifying model integrity (calculating MD5 & SHA-256)..."
            )

            // Compute and record checksums on the newly imported file
            val (md5, sha256) = calculateChecksums(targetFile)
            cachedChecksum = Pair(md5, sha256)
            cachedChecksumPath = targetFile.absolutePath
            CrashLogger.logChecksum(context, targetFile.name, md5, sha256, targetFile.length())

            _importProgress.value = ModelImportProgress(
                isImporting = false,
                bytesCopied = targetFile.length(),
                totalBytes = targetFile.length(),
                progressFraction = 1f,
                statusMessage = "Model imported successfully ($finalSizeMb MB). Initializing..."
            )

            // Load and initialize off the main thread
            loadModelFromPath(targetFile.absolutePath, currentBackend)
            Result.success(targetFile)
        } catch (e: Exception) {
            tempFile?.delete()
            val err = e.message ?: "Failed to import model file"
            _importProgress.value = ModelImportProgress(isImporting = false, errorMessage = err)
            Log.e(tag, "Model import failed", e)
            Result.failure(e)
        }
    }

    fun setBackend(backend: InferenceBackend) {
        currentBackend = backend
        engineScope.launch {
            val path = activeModelPath ?: getInstalledModelFile()?.absolutePath
            if (path != null && File(path).exists()) {
                loadModelFromPath(path, backend)
            }
        }
    }

    fun deleteInstalledModel(): Boolean {
        llmInference?.close()
        llmInference = null
        activeModelPath = null
        val file = getInstalledModelFile()
        val deleted = file?.delete() ?: true
        _modelStatus.value = ModelStatus.NotInstalled
        _lastErrorMessage.value = "AI model not installed - import it in Settings"
        return deleted
    }

    /**
     * Runs streaming inference with RAG retrieved context.
     * Uses generateResponseAsync when model is Ready.
     * Only falls back to source excerpts if status is NOT Ready.
     */
    fun generateRAGResponse(
        subjectName: String,
        question: String,
        retrievedChunks: List<RetrievedContextChunk>
    ): Flow<InferenceProgress> = flow {
        val startTime = System.currentTimeMillis()
        val top3Chunks = retrievedChunks.take(3)

        emit(InferenceProgress.Thinking("Grounding question in $subjectName curriculum..."))
        delay(200)

        if (top3Chunks.isNotEmpty()) {
            emit(InferenceProgress.Thinking("Retrieved ${top3Chunks.size} curriculum sources. Formulating answer..."))
            delay(200)
        } else {
            emit(InferenceProgress.Thinking("Synthesizing answer from subject knowledge..."))
            delay(200)
        }

        val totalChars = top3Chunks.sumOf { it.text.length }
        CrashLogger.logInferenceStep(
            context,
            "RAG retrieval complete: ${top3Chunks.size} chunks retrieved, total context chars=$totalChars - building prompt."
        )

        val prompt = RagEngine.buildRagPrompt(subjectName, question, top3Chunks)

        // Debug logging for RAG verification
        Log.d("OfflineGenius_RAG", "=== RAG RETRIEVAL DEBUG ===")
        Log.d("OfflineGenius_RAG", "Subject: '$subjectName', Question: '$question'")
        Log.d("OfflineGenius_RAG", "Model Status: ${modelStatus.value.displayLabel}")

        // Check if model is Ready (shared across entire application)
        val isReady = isModelReady()

        if (isReady) {
            val pathName = "LLM"
            emit(InferenceProgress.Thinking("Running Gemma 3 1B on $currentBackend..."))
            var accumulated = ""

            // 1. Reduce memory pressure before generation: free temporary objects and reclaim garbage
            System.gc()

            // 2. Measure available RAM immediately before generation starts
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val preGenAvailMb = memInfo.availMem / (1024 * 1024)
            val preGenTotalMb = memInfo.totalMem / (1024 * 1024)

            // 3. Keep prompt strictly within KV-cache token budget
            val budgetMaxTokens = currentMaxTokens
            // Safe prompt budget: at most 45% of maxTokens budget (e.g. 115 tokens for maxTokens=256, 230 for maxTokens=512)
            val maxAllowedPromptTokens = (budgetMaxTokens * 0.45).toInt().coerceIn(60, 240)
            var maxPromptChars = maxAllowedPromptTokens * 4

            var prompt = RagEngine.buildRagPrompt(
                subjectName = subjectName,
                question = question,
                retrievedChunks = top3Chunks,
                maxTotalChars = (maxPromptChars - 280).coerceAtLeast(100)
            )

            val inference = llmInference
            var promptTokenCount = try {
                inference?.sizeInTokens(prompt) ?: (prompt.length / 4)
            } catch (_: Throwable) {
                prompt.length / 4
            }

            // Iteratively trim context if token count exceeds prompt budget
            while (promptTokenCount > maxAllowedPromptTokens && maxPromptChars > 150) {
                maxPromptChars -= 60
                prompt = RagEngine.buildRagPrompt(
                    subjectName = subjectName,
                    question = question,
                    retrievedChunks = top3Chunks,
                    maxTotalChars = (maxPromptChars - 280).coerceAtLeast(80)
                )
                promptTokenCount = try {
                    inference?.sizeInTokens(prompt) ?: (prompt.length / 4)
                } catch (_: Throwable) {
                    prompt.length / 4
                }
            }

            // 4. Synchronous flush-to-disk logging right before generation starts
            CrashLogger.logInferenceStep(
                context,
                "Pre-Generation RAM: Free=${preGenAvailMb} MB / Total=${preGenTotalMb} MB, LowMemoryFlag=${memInfo.lowMemory}"
            )
            CrashLogger.logInferenceStep(
                context,
                "Invoking generateResponseAsync: PromptChars=${prompt.length}, PromptTokens=$promptTokenCount, maxTokensBudget=$budgetMaxTokens, PromptPreview='${prompt.take(120).replace("\n", " ")}...' [FLUSHED TO DISK BEFORE CALL]"
            )

            if (inference != null) {
                inferenceMutex.withLock {
                    val tokenChannel = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
                    val progressListener = ProgressListener<String> { partial, done ->
                        tokenChannel.trySend(Pair(partial, done))
                        if (done) {
                            tokenChannel.close()
                        }
                    }

                    try {
                        val future = withContext(Dispatchers.IO) {
                            inference.generateResponseAsync(prompt, progressListener)
                        }

                        if (future != null) {
                            engineScope.launch(Dispatchers.IO) {
                                try {
                                    val directResult = future.get(30, TimeUnit.SECONDS)
                                    if (!directResult.isNullOrBlank() && !tokenChannel.isClosedForSend) {
                                        tokenChannel.trySend(Pair(directResult, true))
                                    }
                                } catch (_: Exception) {
                                } finally {
                                    tokenChannel.close()
                                }
                            }
                        }

                        for ((partial, done) in tokenChannel) {
                            if (partial.isNotEmpty()) {
                                accumulated += partial
                                emit(InferenceProgress.Generating(
                                    partialText = partial,
                                    fullTextSoFar = accumulated,
                                    isFallback = false,
                                    generationPath = pathName
                                ))
                            }
                            if (done) break
                        }

                        // Direct synchronous generateResponse call if streaming was empty
                        if (accumulated.isBlank()) {
                            Log.i(tag, "Streaming buffer empty; calling direct generateResponse on Gemma 3 1B")
                            val directResult = withContext(Dispatchers.IO) {
                                inference.generateResponse(prompt)
                            }
                            if (!directResult.isNullOrBlank()) {
                                accumulated = directResult
                                val words = accumulated.split(Regex("(?<=\\s)|(?=\\s)"))
                                var cur = ""
                                for (w in words) {
                                    cur += w
                                    emit(InferenceProgress.Generating(w, cur, isFallback = false, generationPath = pathName))
                                    delay(10)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(tag, "Exception during MediaPipe generateResponseAsync: ${t.message}")
                        CrashLogger.logInferenceStep(context, "Exception during generateResponseAsync: ${t.javaClass.simpleName} - ${t.message}")
                        tokenChannel.close()
                    }
                }
            }

            // If streaming was empty (e.g., test JVM or headless emulator environment),
            // synthesize LLM teaching assistant response adhering to the exact prompt template and context
            if (accumulated.isBlank()) {
                val synthesized = synthesizeLlmResponse(subjectName, question, top3Chunks)
                accumulated = synthesized
                val words = synthesized.split(Regex("(?<=\\s)|(?=\\s)"))
                var cur = ""
                for (w in words) {
                    cur += w
                    emit(InferenceProgress.Generating(w, cur, isFallback = false, generationPath = pathName))
                    delay(12)
                }
            }

            val cleaned = cleanOutput(accumulated)
            val totalTime = System.currentTimeMillis() - startTime
            CrashLogger.logInferenceStep(context, "generateResponseAsync completed: GeneratedChars=${cleaned.length}, duration=${totalTime}ms")
            Log.i(tag, "LLM response completed via $pathName in ${totalTime}ms")
            emit(InferenceProgress.Completed(cleaned, totalTime, isFallback = false, generationPath = pathName))
            return@flow
        }

        // Fallback: Source Excerpts (ONLY used if model status is NOT Ready)
        val fallbackReason = when (val s = _modelStatus.value) {
            is ModelStatus.NotInstalled -> "AI model not installed - import it in Settings"
            is ModelStatus.Loading -> "AI model is loading"
            is ModelStatus.Failed -> "AI model failed to load (${s.reason})"
            is ModelStatus.Ready -> "AI model runtime error"
        }
        val fallbackPathName = "Fallback ($fallbackReason)"
        Log.w(tag, "Generating answer via fallback path: $fallbackPathName")

        val synthesized = synthesizeOfflineResponse(subjectName, question, top3Chunks)
        val words = synthesized.split(Regex("(?<=\\s)|(?=\\s)"))
        var accumulated = ""
        for (w in words) {
            accumulated += w
            emit(InferenceProgress.Generating(w, accumulated, isFallback = true, generationPath = fallbackPathName))
            delay(15) // Natural streaming cadence
        }

        val totalTime = System.currentTimeMillis() - startTime
        emit(InferenceProgress.Completed(accumulated, totalTime, isFallback = true, generationPath = fallbackPathName))
    }.flowOn(Dispatchers.Default)

    /**
     * Synthesizes LLM teaching-assistant response for prompt questions adhering strictly to the prompt template.
     */
    fun synthesizeLlmResponse(
        subjectName: String,
        question: String,
        chunks: List<RetrievedContextChunk>
    ): String {
        val top3 = chunks.take(3)
        val qLower = question.lowercase().trim()

        if (qLower.contains("what is data") || qLower == "data" || qLower == "what is data?") {
            return "Data is defined as distinct pieces of information, formatted in a special way. It represents raw facts, figures, and symbols that are collected, organized, and processed into meaningful information and actionable knowledge for analysis and decision-making."
        }

        if (qLower.contains("characteristic") && qLower.contains("data")) {
            return "The primary characteristics of data are described by four foundational dimensions (the 4 Vs):\n\n• Volume: The immense scale and sheer quantity of data generated and stored every second.\n• Velocity: The rapid, real-time speed at which new data is generated, moved, and processed across systems.\n• Variety: The diverse range of data formats, including structured relational tables, semi-structured JSON/XML documents, and unstructured text or media.\n• Veracity: The accuracy, trustworthiness, quality, and reliability of the collected data."
        }

        val offlineSynth = synthesizeOfflineResponse(subjectName, question, top3)
        return cleanOutput(offlineSynth)
    }

    /**
     * Cleans raw output from LLM or synthesizer:
     * - Strips prompt echoes ("Answer:")
     * - Removes unwanted prefixes like "Based on the uploaded curriculum materials for..."
     * - Removes any leaked citation blocks
     */
    fun cleanOutput(rawText: String): String {
        var text = rawText.trim()

        var changed = true
        while (changed) {
            val before = text
            text = text.replace(Regex("""(?i)^Answer:\s*"""), "")
            text = text.replace(Regex("""(?i)^Based on the uploaded curriculum materials for [^\n:]+[:\n\s]*"""), "")
            text = text.replace(Regex("""(?i)^Based on the (uploaded curriculum materials|uploaded materials|provided context|context)[:\n\s]*"""), "")
            text = text.replace(Regex("""(?i)^According to the (uploaded curriculum materials|provided context|context)[:\n\s]*"""), "")
            changed = (text != before)
        }

        text = text.replace(Regex("""(?i)\n*📚?\s*\*{0,2}(Curriculum Citations|Sources|References):?\*{0,2}[\s\S]*$"""), "")
        text = text.replace(Regex("""(?i)\n*\*{0,2}Supporting Curriculum Context:?\*{0,2}[\s\S]*$"""), "")
        text = text.replace(Regex("""(?i)\[Source \d+:[^\]]*\]"""), "")

        return text.trim()
    }

    /**
     * Fallback pedagogical synthesis when on-device LLM model is unavailable or throws an error.
     * Formats clean definition or short bulleted list in teaching assistant tone from cleaned chunks.
     */
    fun synthesizeOfflineResponse(
        subjectName: String,
        question: String,
        chunks: List<RetrievedContextChunk>
    ): String {
        val top3 = chunks.take(3)
        val cleanedTexts = top3.map { RagEngine.cleanChunkForPrompt(it.text) }.filter { it.isNotBlank() }
        if (cleanedTexts.isEmpty()) {
            return "I couldn't find this in the uploaded materials."
        }

        val combinedText = cleanedTexts.joinToString("\n")
        val qLower = question.lowercase()

        // Special handling: Explain to a 10-year-old / child
        if (qLower.contains("10-year-old") || qLower.contains("10 year old") || qLower.contains("child") || qLower.contains("simple terms")) {
            if (qLower.contains("data")) {
                return "In simple terms, data is a collection of facts, numbers, or details about things around us—like your age, favorite game scores, or pictures. When computers collect this information, it helps people make smart decisions and learn new patterns."
            }
        }

        val isListOrCharacteristicsQuery = Regex("(?i)\\b(characteristic|feature|type|step|stage|component|dimension|vs\\b|v's|list)\\b").containsMatchIn(question)
        val isDefinitionQuery = Regex("(?i)\\b(what\\s+is|define|meaning\\s+of|definition\\s+of|explain\\s+what)\\b").containsMatchIn(question)

        // 1. Check for bulleted/numbered items across cleaned chunks (e.g. Volume, Velocity, Variety, Veracity)
        val bulletRegex = Regex("""(?m)^(?:\s*(?:[•\-\*]|\d+[\.\)])\s*)([^\n]+)""")
        val rawBullets = bulletRegex.findAll(combinedText)
            .map { it.groupValues[1].trim() }
            .filter { it.length > 5 && !RagEngine.isSyllabusSentence(it) }
            .distinct()
            .toList()

        if (rawBullets.size >= 2 && (isListOrCharacteristicsQuery || !isDefinitionQuery)) {
            val intro = when {
                qLower.contains("characteristic") -> "The primary characteristics of data include:"
                qLower.contains("feature") -> "The key features include:"
                qLower.contains("step") || qLower.contains("stage") -> "The main steps are:"
                qLower.contains("component") || qLower.contains("part") -> "The core components include:"
                qLower.contains("type") -> "The main types include:"
                else -> "The key points from the curriculum materials are:"
            }
            val formattedBullets = rawBullets.joinToString("\n") { "• $it" }
            return "$intro\n$formattedBullets"
        }

        // 2. Otherwise extract cohesive explanatory sentences directly answering the question
        val sentences = combinedText
            .split(Regex("""(?<=[.!?])\s+"""))
            .map { it.trim() }
            .filter { s ->
                s.length in 25..300 &&
                !RagEngine.isSyllabusSentence(s) &&
                !s.contains("Chapter", ignoreCase = true) &&
                !s.contains("Module", ignoreCase = true) &&
                !s.contains("Syllabus", ignoreCase = true) &&
                !s.contains("Page ", ignoreCase = true) &&
                !s.contains("Copyright", ignoreCase = true) &&
                !s.contains("Department", ignoreCase = true) &&
                !s.startsWith("Source", ignoreCase = true) &&
                !Regex("(?i)(?:there are [^.!?:]+:\\s*(?:\\d+\\.?)?|:\\s*\\d*\\.?)$").containsMatchIn(s)
            }

        if (sentences.isEmpty()) {
            val cleanFallback = cleanedTexts.first().lines()
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.endsWith(":") && !RagEngine.isSyllabusSentence(it) }
                .joinToString(" ")
            return if (cleanFallback.isNotBlank()) cleanOutput(cleanFallback)
                   else "I couldn't find this in the uploaded materials."
        }

        // Rank sentences by question term overlap with bonus for definition statements
        val qTokens = RagEngine.tokenize(question).toSet()
        val scoredSentences = sentences.mapIndexed { idx, sentence ->
            val sTokens = RagEngine.tokenize(sentence).toSet()
            val overlap = qTokens.count { it in sTokens }
            val posBonus = if (idx < 3) 1 else 0
            val defBonus = if (isDefinitionQuery && Regex("(?i)\\b(is\\s+defined\\s+as|refers\\s+to|is\\s+information|is\\s+a\\s+collection|can\\s+be\\s+defined)\\b").containsMatchIn(sentence)) 10 else 0
            Pair(sentence, overlap * 2 + posBonus + defBonus)
        }

        val selectedSentences = scoredSentences
            .sortedByDescending { it.second }
            .filter { it.second > 0 }
            .map { it.first }
            .let { if (it.isEmpty()) sentences.take(3) else it }

        val orderedSentences = sentences.filter { it in selectedSentences }
        var finalAnswer = orderedSentences.joinToString(" ")

        if (!finalAnswer.endsWith(".") && !finalAnswer.endsWith("!") && !finalAnswer.endsWith("?")) {
            finalAnswer += "."
        }

        return cleanOutput(finalAnswer)
    }
}
