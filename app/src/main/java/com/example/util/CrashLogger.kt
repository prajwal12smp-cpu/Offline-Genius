package com.example.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val CRASH_FILE_NAME = "crash_log.txt"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(context, thread, throwable)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to record uncaught exception", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        // Add clear session separator for each app-launch session
        recordAppSessionStart(context)
    }

    fun recordAppSessionStart(context: Context) {
        try {
            val file = getCrashFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val sessionHeader = buildString {
                appendLine()
                appendLine("================================================================================")
                appendLine("=== NEW APP LAUNCH SESSION: $timestamp ===")
                appendLine("================================================================================")
            }
            writeSynchronouslyToDisk(file, sessionHeader)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write session banner", e)
        }
    }

    fun getCrashFile(context: Context): File {
        return File(context.filesDir, CRASH_FILE_NAME)
    }

    fun getCrashLog(context: Context): String? {
        val file = getCrashFile(context)
        return if (file.exists() && file.length() > 0) {
            try {
                file.readText()
            } catch (e: Exception) {
                "Error reading crash log: ${e.message}"
            }
        } else {
            null
        }
    }

    /**
     * Formats raw crash log into reverse-chronological order (most recent entries at the top).
     * Preserves multi-line stack traces, checksum blocks, and session headers within each block.
     */
    fun formatLogReverseChronological(rawLog: String): String {
        if (rawLog.isBlank()) return rawLog
        val lines = rawLog.lines()
        val blocks = mutableListOf<String>()
        val currentBlock = StringBuilder()

        for (line in lines) {
            val isBlockStart = line.startsWith("[") ||
                    line.startsWith("===") ||
                    line.startsWith("CRASH DETECTED")

            if (isBlockStart && currentBlock.isNotBlank()) {
                blocks.add(currentBlock.toString().trimEnd())
                currentBlock.clear()
            }
            currentBlock.appendLine(line)
        }
        if (currentBlock.isNotBlank()) {
            blocks.add(currentBlock.toString().trimEnd())
        }

        return blocks.asReversed().joinToString("\n\n")
    }

    fun exportCrashLog(context: Context) {
        val file = getCrashFile(context)
        val text = getCrashLog(context) ?: "No crash or diagnostic log recorded."
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Offline Genius Diagnostic & Crash Log")
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_TITLE, "crash_log.txt")
                if (file.exists() && file.length() > 0) {
                    try {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newRawUri("Crash Log", uri)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not attach file URI, sending as text: ${e.message}")
                    }
                }
            }
            val chooser = Intent.createChooser(sendIntent, "Export Diagnostic & Crash Log").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export crash log", e)
        }
    }

    fun clearCrashLog(context: Context): Boolean {
        val file = getCrashFile(context)
        return if (file.exists()) file.delete() else true
    }

    private val syncLock = Any()

    private fun writeSynchronouslyToDisk(file: File, text: String) {
        synchronized(syncLock) {
            try {
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                java.io.FileOutputStream(file, true).use { fos ->
                    fos.write(text.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    // Force OS kernel to sync physical storage blocks to disk immediately.
                    // This guarantees bytes are preserved even on instant SIGSEGV or SIGABRT.
                    try {
                        fos.fd.sync()
                    } catch (syncEx: Throwable) {
                        Log.w(TAG, "fd.sync() warning: ${syncEx.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing to crash log file synchronously", e)
            }
        }
    }

    fun logInitializationStep(context: Context, stepDescription: String) {
        try {
            val file = getCrashFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "[$timestamp] [INIT STEP] $stepDescription\n"
            writeSynchronouslyToDisk(file, line)
            Log.i("ModelInitStep", stepDescription)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write init step to crash log", e)
        }
    }

    fun logInferenceStep(context: Context, stepDescription: String) {
        try {
            val file = getCrashFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "[$timestamp] [INFERENCE STEP] $stepDescription\n"
            writeSynchronouslyToDisk(file, line)
            Log.i("InferenceStep", stepDescription)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write inference step to crash log", e)
        }
    }

    fun logInferenceStep(stepDescription: String) {
        val ctx = appContext
        if (ctx != null) {
            logInferenceStep(ctx, stepDescription)
        } else {
            Log.i("InferenceStep", stepDescription)
        }
    }

    fun logChecksum(context: Context, fileName: String, md5: String, sha256: String, bytes: Long) {
        val desc = "Model Checksums: File='$fileName', Size=$bytes bytes (~${bytes / (1024 * 1024)} MB)\n  MD5:    $md5\n  SHA256: $sha256"
        logInitializationStep(context, desc)
    }

    fun logInitializationFailure(context: Context, message: String, throwable: Throwable?) {
        try {
            val file = getCrashFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val sw = StringWriter()
            throwable?.printStackTrace(PrintWriter(sw))
            val log = buildString {
                appendLine("[$timestamp] [MODEL INIT FAILED]")
                appendLine("Message: $message")
                if (throwable != null) {
                    appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                    appendLine("Stack Trace:")
                    appendLine(sw.toString())
                }
            }
            writeSynchronouslyToDisk(file, log)
            Log.e(TAG, "Logged model init failure: $message", throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log init failure", e)
        }
    }

    private fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val log = buildString {
            appendLine("==========================================")
            appendLine("CRASH DETECTED AT: $timestamp")
            appendLine("Thread: ${thread.name} (id: ${thread.id})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine("Stack Trace:")
            appendLine(sw.toString())
            appendLine("==========================================")
        }
        val file = getCrashFile(context)
        writeSynchronouslyToDisk(file, log)
        Log.e(TAG, log)
    }
}
