package com.headunit.music.phone

import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.UIKit.UIViewController

private var observing = false

/** What the Swift app shows: the whole app, in Compose. Called from iosApp/iosApp/MewZApp.swift. */
fun MainViewController(): UIViewController {
    if (!observing) {
        observing = true
        // Leaving the app (or it's being closed): remember the song and where it's at.
        val nc = NSNotificationCenter.defaultCenter
        for (name in listOf(UIApplicationDidEnterBackgroundNotification, UIApplicationWillTerminateNotification)) {
            nc.addObserverForName(name, null, NSOperationQueue.mainQueue) { _ -> AppState.player.saveState() }
        }
    }
    return ComposeUIViewController(configure = {
        // The screens make room for the keyboard themselves (search suggestions, name fields).
        onFocusBehavior = OnFocusBehavior.DoNothing
    }) {
        App()
    }
}
