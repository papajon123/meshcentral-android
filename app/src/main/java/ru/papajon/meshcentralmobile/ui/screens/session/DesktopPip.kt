package ru.papajon.meshcentralmobile.ui.screens.session

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.mesh.RemoteViewport
import kotlin.math.roundToInt

/** Activity-owned PiP state. Eligibility is scoped to the visible, unlocked desktop composition. */
class DesktopPipHost(private val activity: Activity) {
    var active by mutableStateOf(activity.isInPictureInPictureMode)
        private set
    val supported: Boolean get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private var eligible = false
    private var automatic = false
    private var params = PictureInPictureParams.Builder().build()
    fun configure(enabled: Boolean, autoEnter: Boolean = false, width: Int = 16, height: Int = 9) {
        eligible = enabled && supported
        automatic = autoEnter
        if (!supported) return
        // Android accepts a bounded aspect ratio; ultrawide/portrait screens are letterboxed.
        val ratio = (width.toFloat() / height.coerceAtLeast(1)).coerceIn(0.42f, 2.38f)
        params = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).roundToInt(), 1000))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(eligible && automatic)
                    setSeamlessResizeEnabled(false)
                }
            }.build()
        runCatching { activity.setPictureInPictureParams(params) }
    }
    fun enter(): Boolean = eligible && runCatching { activity.enterPictureInPictureMode(params) }.getOrDefault(false)
    fun onUserLeaveHint() { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && automatic) enter() }
    fun onModeChanged(inPip: Boolean) { active = inPip }
}

val LocalDesktopPip = staticCompositionLocalOf<DesktopPipHost?> { null }

/** Pure viewing surface: no gesture recognizers, focus targets, IME, buttons or remote input. */
@Composable
fun DesktopPipPreview(state: RemoteDesktopState, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize().background(Color.Black).testTag("desktop_pip_preview")) {
        val frame = state.framebuffer ?: return@Canvas
        val viewport = RemoteViewport(size.width.toInt(), size.height.toInt(), state.remoteWidth, state.remoteHeight)
        drawImage(frame.asImageBitmap(), dstOffset = IntOffset(viewport.left.roundToInt(), viewport.top.roundToInt()),
            dstSize = IntSize(viewport.drawnWidth.roundToInt().coerceAtLeast(1), viewport.drawnHeight.roundToInt().coerceAtLeast(1)))
    }
}

/** A locked PiP never draws the retained framebuffer or offers a PIN keyboard in the small window. */
@Composable
fun LockedDesktopPip() {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(Color.Black).testTag("desktop_pip_locked"),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(ru.papajon.meshcentralmobile.R.string.lock_message_locked),
            color = Color.White,
        )
    }
}
