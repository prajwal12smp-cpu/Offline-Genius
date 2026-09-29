package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.navigation.MainApp
import com.example.ui.theme.OfflineGeniusTheme
import com.example.ui.viewmodels.AppViewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModelFactory by lazy {
        AppViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OfflineGeniusTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainApp(viewModelFactory = viewModelFactory)
                }
            }
        }
    }
}
