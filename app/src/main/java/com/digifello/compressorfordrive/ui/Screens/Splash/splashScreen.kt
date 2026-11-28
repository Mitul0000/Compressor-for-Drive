package com.digifello.compressorfordrive.ui.Screens.Splash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.digifello.compressorfordrive.R
import kotlinx.coroutines.delay

private const val SPLASH_MILLIS = 2200L

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val currentOnFinished by rememberUpdatedState(onFinished)
    var shown by remember { mutableStateOf(false) }
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(700), label = "fade")
    val grow by animateFloatAsState(if (shown) 1f else 0.85f, tween(700), label = "grow")

    LaunchedEffect(Unit) {
        shown = true
        delay(SPLASH_MILLIS)
        currentOnFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF12433E)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                alpha = fade
                scaleX = grow
                scaleY = grow
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "Storage Compressor logo",
                modifier = Modifier.size(200.dp),
            )
            Text(
                "Storage Compressor",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFF4EFE6),
            )
            Text(
                "Works completely offline",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD2C9B6),
            )
        }
    }
}