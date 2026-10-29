package ru.papajon.meshcentralmobile.mesh

/** Agent defaults are observations, not permission to replace the user's selected monitor. */
internal fun preferredDisplaySelection(wanted: Int?, ids: List<Int>, reported: Int): Int? {
    if (ids.isEmpty()) return wanted
    if (wanted == KvmPacketParser.ALL_DISPLAYS_INDEX || wanted != null && wanted in ids) return wanted
    if (reported != KvmPacketParser.ALL_DISPLAYS_INDEX && reported in ids) return reported
    if (wanted == null && ids.size == 1) return KvmPacketParser.ALL_DISPLAYS_INDEX
    return ids.firstOrNull { it != KvmPacketParser.ALL_DISPLAYS_INDEX } ?: KvmPacketParser.ALL_DISPLAYS_INDEX
}
