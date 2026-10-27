package ru.papajon.meshcentralmobile.discovery

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.ui.screens.DevicesContent
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/** Same synthetic workload before/after; elapsed UI time is not a physical-device FPS claim. */
class LargeListBenchmarkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun scrollAndUpdateTwoThousandDevices() {
        val devices = (0 until 2000).map { MeshDevice(nodeId = "node//$it", meshId = "mesh//lab",
            name = "Workstation %04d".format(it), conn = 1, agentId = 1) }
        val state = mutableStateOf(MeshClientState(devices = devices, socketConnected = true,
            devicesCurrent = true, requiresSignIn = false, groups = listOf(MeshGroup("mesh//lab", "Lab"))))
        compose.setContent { MeshCentralMobileTheme {
            DevicesContent(state.value, emptyList(), { _, _ -> }, {}, {}, {}, {}, {})
        } }
        compose.waitForIdle()
        val list = compose.onNode(hasScrollToIndexAction())
        repeat(3) { list.performScrollToIndex(it * 300); compose.waitForIdle() }
        val start = SystemClock.elapsedRealtime()
        repeat(20) { list.performScrollToIndex((it * 97) % 1900 + 3); compose.waitForIdle() }
        val scrolling = SystemClock.elapsedRealtime() - start
        val updateStart = SystemClock.elapsedRealtime()
        repeat(30) { i ->
            compose.runOnIdle { state.value = state.value.copy(devices = state.value.devices.mapIndexed { j, d ->
                if (j == i * 61) d.copy(name = d.name + ".") else d
            }) }
            compose.waitForIdle()
        }
        Log.i("LargeListBenchmark", "devices=2000 scroll20_ms=$scrolling updates30_ms=${SystemClock.elapsedRealtime() - updateStart}")
        list.performScrollToIndex(2002)
        compose.onNodeWithText("Workstation 1999").assertExists()
    }
}
