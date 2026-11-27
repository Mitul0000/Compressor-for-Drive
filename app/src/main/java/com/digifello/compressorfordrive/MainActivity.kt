package com.digifello.compressorfordrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.digifello.compressorfordrive.ui.Screens.Navigation.AppNavGraph
import com.digifello.compressorfordrive.ui.theme.CompressorForDriveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompressorForDriveTheme {
                AppNavGraph()
            }
        }
    }
}