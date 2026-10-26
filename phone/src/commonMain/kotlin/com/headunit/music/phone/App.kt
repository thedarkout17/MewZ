package com.headunit.music.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** The whole iPhone app. */
@Composable
fun App() {
    Box(Modifier.fillMaxSize().background(Color(0xFFC4001A)), contentAlignment = Alignment.Center) {
        BasicText("MewZ")
    }
}
