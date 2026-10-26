package com.headunit.music.phone

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** What the Swift app shows: the whole app, in Compose. Called from iosApp/iosApp/MewZApp.swift. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    App()
}
