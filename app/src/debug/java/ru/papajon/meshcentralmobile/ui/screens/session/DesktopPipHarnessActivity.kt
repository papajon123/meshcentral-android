package ru.papajon.meshcentralmobile.ui.screens.session

import android.content.res.Configuration
import androidx.activity.ComponentActivity

/** Instrumentation host only; absent from the release APK and not exported. */
class DesktopPipHarnessActivity : ComponentActivity() {
    val pip by lazy { DesktopPipHost(this) }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.onModeChanged(isInPictureInPictureMode)
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); pip.onUserLeaveHint() }
}
