package ru.papajon.meshcentralmobile.discovery

import ru.papajon.meshcentralmobile.mesh.MeshDevice

object NeedsAttentionFilter {
    fun matches(device: MeshDevice): Boolean = device.isWindows && device.windowsSecurity.needsAttention
}
