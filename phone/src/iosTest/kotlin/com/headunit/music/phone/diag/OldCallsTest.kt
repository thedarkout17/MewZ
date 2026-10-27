package com.headunit.music.phone.diag

import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import platform.Foundation.NSLog
import kotlin.test.Test

/**
 * The two calls the first iPhone build made when the crash happened, each on its
 * own. CI runs this separately: if one of them takes the process down, the log
 * shows which. (Not used by the app any more.)
 */
class OldCallsTest {
    @Test fun nslogWithKotlinStringArgument() {
        println("MEWZ: diag NSLog(%@) start")
        NSLog("MewZ: %@", "Album \"Starboy\" by \"The Weeknd\" (#1): shown")
        println("MEWZ: diag NSLog(%@) survived")
    }

    @OptIn(ExperimentalTestApi::class)
    @Suppress("DEPRECATION")
    @Test fun localClipboardManager() = runComposeUiTest {
        println("MEWZ: diag LocalClipboardManager start")
        setContent {
            val cm = LocalClipboardManager.current
            println("MEWZ: diag LocalClipboardManager provided: ${cm::class.simpleName}")
        }
        waitForIdle()
        println("MEWZ: diag LocalClipboardManager survived")
    }
}
