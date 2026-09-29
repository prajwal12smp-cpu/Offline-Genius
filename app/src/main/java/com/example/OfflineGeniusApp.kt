package com.example

import android.app.Application
import com.example.ai.llm.MediaPipeLlmEngine
import com.example.util.CrashLogger

class OfflineGeniusApp : Application() {

    companion object {
        lateinit var instance: OfflineGeniusApp
            private set
    }

    lateinit var llmEngine: MediaPipeLlmEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLogger.init(this)
        llmEngine = MediaPipeLlmEngine.getInstance(this)
    }
}

