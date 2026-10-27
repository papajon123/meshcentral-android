package ru.papajon.meshcentralmobile.mesh

import ru.papajon.meshcentralmobile.json.optText

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser for MeshCentral `control.ashx` JSON frames.
 *
 * Shapes verified against MeshCentral 1.2.5 (`meshuser.js` command handlers, `webserver.js`
 * authentication replies). Parsing stays tolerant: unknown fields are ignored and unknown
 * actions become [MeshControlEvent.Unknown] so a server upgrade cannot crash the client.
 */
object MeshControlParser {
    fun parse(text: String): MeshControlEvent {
        val json = ru.papajon.meshcentralmobile.mesh.control.ControlFrameDecoder.decode(text)
            ?: return MeshControlEvent.Unknown(action = null, rawLength = text.length)
        return parse(json, text.length)
    }

    fun parse(json: JSONObject, rawLength: Int = 0): MeshControlEvent {
        val action = json.optText("action").takeIf { it.isNotBlank() }
        return when (action) {
            "serverinfo" -> {
                val info = json.optJSONObject("serverinfo")
                MeshControlEvent.ServerInfo(
                    name = info?.optText("name")?.takeIf { it.isNotBlank() }
                        ?: json.optText("name").takeIf { it.isNotBlank() },
                    domain = info?.optText("domain")?.takeIf { it.isNotBlank() },
                    capabilities = MeshServerCapabilities(
                        features = (info?.opt("features") as? Number)?.toLong(),
                        advertisedName = (info?.opt("name") as? String)?.takeIf { it.isNotBlank() },
                        advertisedPort = info?.optIntOrNull("port")?.takeIf { it in 1..65535 },
                        advertisedHttps = info?.opt("https") as? Boolean,
                        maxDeviceView = info?.optIntOrNull("maxdeviceview")?.takeIf { it > 0 },
                        features2 = (info?.opt("features2") as? Number)?.toLong(),
                        consent = info?.optIntOrNull("consent"),
                        linuxShell = info?.optText("linuxshell")?.takeIf { it.isNotBlank() },
                        domainSuffix = info?.optText("domainsuffix")?.takeIf { it.isNotBlank() },
                        manageAllDeviceGroups = info?.optBoolean("manageAllDeviceGroups", false) == true,
                    ),
                )
            }

            "userinfo" -> {
                val info = json.optJSONObject("userinfo")
                MeshControlEvent.UserInfo(
                    userId = info?.optText("_id")?.takeIf { it.isNotBlank() },
                    name = info?.optText("name")?.takeIf { it.isNotBlank() },
                    siteAdmin = readSiteAdmin(info?.opt("siteadmin")),
                    userGroupIds = parseLinks(info?.optJSONObject("links")).keys.filter { it.startsWith("ugrp/") }.toSet(),
                    removeRights = readRights(info?.opt("removeRights")),
                    agentInstallAllowed = info != null && (readSiteAdmin(info.opt("siteadmin")) ||
                        ((readRights(info.opt("siteadmin")) ?: 0) and 4096 == 0)),
                )
            }

            "meshes" -> MeshControlEvent.Meshes(parseMeshes(json.opt("meshes")))
            "nodes" -> MeshControlEvent.Nodes(
                parseNodes(json.opt("nodes")),
                responseId = json.optText("responseid").takeIf { it.isNotBlank() },
                skip = json.optIntOrNull("skip")?.takeIf { it >= 0 },
                limit = json.optIntOrNull("limit")?.takeIf { it > 0 },
                totalCount = json.optIntOrNull("totalcount")?.takeIf { it >= 0 },
                filter = json.optText("filter").takeIf { json.has("filter") },
                result = json.optText("result").takeIf { it.isNotBlank() },
            )

            "authcookie" -> MeshControlEvent.AuthCookie(
                cookie = json.optText("cookie").takeIf { it.isNotBlank() },
                relayCookie = json.optText("rcookie").takeIf { it.isNotBlank() },
            )

            "twoFactorCookie" -> MeshControlEvent.TwoFactorCookie(
                (json.opt("cookie") as? String)?.takeIf {
                    ru.papajon.meshcentralmobile.security.RememberedDeviceStore.validCookie(it)
                },
            )

            "event" -> {
                val event = json.optJSONObject("event")
                if (event == null) {
                    MeshControlEvent.Unknown(action, rawLength)
                } else {
                    MeshControlEvent.NodeUpdated(parseNodeEvent(event))
                }
            }

            "msg" -> parseMessage(json, rawLength)
            "close" -> MeshControlEvent.Close(
                cause = json.optText("cause").takeIf { it.isNotBlank() },
                message = json.optText("msg").takeIf { it.isNotBlank() },
                twoFactorChallenge = parseTwoFactor(json),
            )

            else -> MeshControlEvent.Unknown(action = action, rawLength = rawLength)
        }
    }

    /** `{action:'msg', type:'getclip'|'setclip', ...}` and `{action:'msg', result:'OK'|'Unable to route'}`. */
    private fun parseMessage(json: JSONObject, rawLength: Int): MeshControlEvent {
        val type = json.optText("type")
        if (type == "getclip" || type == "setclip") {
            return MeshControlEvent.ClipMessage(
                type = type,
                success = json.opt("success")?.let { if (it is Boolean) it else null },
                data = json.optText("data").takeIf { json.has("data") && !json.isNull("data") },
                tag = json.optText("tag").takeIf { it.isNotBlank() },
                sessionId = json.optText("sessionid").takeIf { it.isNotBlank() },
            )
        }
        if (type.isBlank() && json.has("result")) {
            return MeshControlEvent.RoutingResult(
                result = json.optText("result").take(512).takeIf { it.isNotBlank() },
                tag = json.optText("tag").takeIf { it.isNotBlank() },
                responseId = json.optText("responseid").takeIf { it.isNotBlank() },
            )
        }
        return MeshControlEvent.Unknown(action = "msg", rawLength = rawLength)
    }

    private fun parseTwoFactor(json: JSONObject): TwoFactorChallenge? {
        if (json.optText("msg") != "tokenrequired") return null
        return TwoFactorChallenge(
            twoFactorCookieDays = ru.papajon.meshcentralmobile.security.RememberedDeviceStore.durationDays(json.opt("twoFactorCookieDays")),
            email = json.optBoolean("email2fa", false),
            sms = json.optBoolean("sms2fa", false),
            push = json.optBoolean("msg2fa", false),
            emailSent = json.optBoolean("email2fasent", false),
            smsSent = json.optBoolean("sms2fasent", false),
            pushSent = json.optBoolean("msg2fasent", false),
        )
    }

    private fun parseNodeEvent(event: JSONObject): MeshNodeEvent {
        return when (event.optText("action")) {
            "devicesessions" -> MeshNodeEvent.DeviceSessions(
                nodeId = event.optText("nodeid"),
                battery = PhoneBattery.parse(event.optJSONObject("sessions")?.optJSONObject("battery")),
            )
            "nodeconnect" -> MeshNodeEvent.NodeConnected(
                nodeId = event.optText("nodeid"),
                conn = event.optInt("conn", 0),
                pwr = event.optInt("pwr", 0),
            )

            "changenode" -> parseNode(event.optJSONObject("node"), fallbackMeshId = "")
                ?.let { MeshNodeEvent.NodeChanged(it) }
                ?: MeshNodeEvent.MeshChanged(null)

            "addnode" -> parseNode(event.optJSONObject("node"), fallbackMeshId = "")
                ?.let { MeshNodeEvent.NodeAdded(it) }
                ?: MeshNodeEvent.MeshChanged(null)

            "removenode" -> MeshNodeEvent.NodeRemoved(event.optText("nodeid"))
            // The old group also receives this event. Re-read visibility rather than retaining a
            // moved node whose destination may no longer be accessible to this account.
            "nodemeshchange" -> MeshNodeEvent.MeshChanged(event.optText("newMeshId").takeIf { it.isNotBlank() },
                event.optText("nodeid").ifBlank { event.optJSONObject("node")?.optText("_id").orEmpty() }.takeIf { it.isNotBlank() })

            "meshchange", "createmesh", "deletemesh" -> {
                MeshNodeEvent.MeshChanged(event.optText("meshid").takeIf { it.isNotBlank() })
            }

            // Any other mesh level event only means the cached lists may be stale.
            else -> MeshNodeEvent.MeshChanged(null)
        }
    }

    private fun parseMeshes(value: Any?): List<MeshGroup> {
        val array = value as? JSONArray ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optText("_id").takeIf { it.isNotBlank() } ?: continue
                add(
                    MeshGroup(
                        id = id,
                        name = item.optText("name").ifBlank { id },
                        description = item.optText("desc"),
                        rightsByUser = parseLinks(item.optJSONObject("links")),
                        relayNodeId = item.optText("relayid").takeIf { it.isNotBlank() },
                        mtype = item.optInt("mtype", 0),
                    ),
                )
            }
        }
    }

    /** `links: { 'user/domain/name': { rights: 4294967295 } }` (also tolerates a bare number). */
    private fun parseLinks(links: JSONObject?): Map<String, Int> {
        if (links == null) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        val keys = links.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = links.opt(key)
            val rights = when (value) {
                is JSONObject -> readRights(value.opt("rights"))
                else -> readRights(value)
            }
            if (rights != null) result[key] = rights
        }
        return result
    }

    private fun parseNodes(value: Any?): List<MeshDevice> {
        return when (value) {
            is JSONArray -> parseNodeArray(value, fallbackMeshId = "")
            is JSONObject -> buildList {
                // Documented shape: meshid -> [node, ...]. Some server builds answer with
                // nodeid -> node, so both shapes are accepted.
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val entry = value.opt(key)) {
                        is JSONArray -> addAll(parseNodeArray(entry, fallbackMeshId = key))
                        is JSONObject -> parseNode(entry, fallbackMeshId = key, fullSnapshot = true)?.let { add(it) }
                        else -> Unit
                    }
                }
            }

            else -> emptyList()
        }
    }

    private fun parseNodeArray(array: JSONArray, fallbackMeshId: String): List<MeshDevice> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val node = parseNode(item, fallbackMeshId, fullSnapshot = true) ?: continue
                add(node)
            }
        }
    }

    fun parseNode(item: JSONObject?, fallbackMeshId: String, fullSnapshot: Boolean = false): MeshDevice? {
        if (item == null) return null
        val nodeId = item.optText("_id").takeIf { it.isNotBlank() } ?: return null
        val agent = item.optJSONObject("agent")
        return MeshDevice(
            nodeId = nodeId,
            meshId = item.optText("meshid").ifBlank { fallbackMeshId },
            name = item.optText("name").ifBlank { nodeId },
            icon = item.optInt("icon", 0),
            conn = item.optInt("conn", 0),
            pwr = item.optInt("pwr", 0),
            osDescription = item.optText("osdesc"),
            description = item.optText("desc"),
            tags = item.optJSONArray("tags")?.let { tags ->
                (0 until tags.length()).mapNotNull { tags.opt(it) as? String }
            }.orEmpty(),
            ipAddress = item.optText("ip"),
            mtype = item.optInt("mtype", 0),
            agentId = agent?.optIntOrNull("id"),
            agentVersion = agent?.optText("ver")?.takeIf { it.isNotBlank() },
            agentCaps = agent?.optIntOrNull("caps"),
            host = item.optText("host").takeIf { it.isNotBlank() },
            sshPort = item.optIntOrNull("sshport")?.takeIf { it in 1..65535 },
            rightsByUser = parseLinks(item.optJSONObject("links")),
            connectivityKnown = fullSnapshot || (item.has("conn") && !item.isNull("conn")),
            battery = PhoneBattery.parse(item.optJSONObject("sessions")?.optJSONObject("battery")),
            sessionsKnown = item.has("sessions"),
            windowsSecurity = WindowsSecurityParser.parse(item),
            lastBootMillis = (item.opt("lastbootuptime") as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it > 0 && it <= 4_102_444_800_000.0 && it % 1 == 0.0 }?.toLong(),
            lastBootKnown = item.has("lastbootuptime"),
        )
    }

    /**
     * MeshCentral reports node rights through the owning mesh `links` entry of the signed-in user,
     * so device rights can only be resolved once `userinfo` and `meshes` have both arrived.
     */
    fun resolveRights(
        devices: List<MeshDevice>,
        groups: List<MeshGroup>,
        userId: String?,
        siteAdmin: Boolean,
        userGroupIds: Set<String> = emptySet(),
        manageAllDeviceGroups: Boolean = false,
        removeRights: Int? = null,
    ): List<MeshDevice> {
        if (devices.isEmpty()) return devices
        val rightsByMesh = groups.mapNotNull { group ->
            val rights = group.rightsFor(userId) ?: return@mapNotNull null
            group.id to rights
        }.toMap()
        return devices.map { device ->
            val group = groups.firstOrNull { it.id == device.meshId }
            val grants = listOfNotNull(rightsByMesh[device.meshId], device.rightsByUser[userId]) +
                userGroupIds.mapNotNull { group?.rightsByUser?.get(it) } + userGroupIds.mapNotNull { device.rightsByUser[it] }
            val rights = if (siteAdmin && manageAllDeviceGroups) {
                MeshRights.FULL
            } else {
                grants.reduceOrNull { accumulated, grant -> accumulated or grant } ?: MeshRights.NONE
            }
            device.copy(rights = restrictRights(rights, removeRights), relayNodeId = group?.relayNodeId, meshName = group?.name ?: device.meshName)
        }
    }

    /** MeshCentral 1.3.1 webserver.js removeUserRights, including FULL with an explicit mask of zero. */
    private fun restrictRights(rights: Int, removeRights: Int?): Int {
        if (removeRights == null) return rights
        val add = removeRights and (0x10000 or 0x100 or 0x200 or 0x400 or 0x400000 or 0x800000)
        val subtract = removeRights and (0x8 or 0x10 or 0x8000 or 0x20000 or 0x40 or 0x40000)
        val base = if (rights == MeshRights.FULL) {
            1 or 2 or 4 or 8 or 32 or 64 or 128 or 16384 or 32768 or 131072 or 262144 or 524288 or 1048576
        } else rights
        return (base or add) and subtract.inv()
    }

    private fun JSONObject.optIntOrNull(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    /**
     * Rights and `siteadmin` flags are sent as unsigned 32-bit values (`4294967295` = all rights),
     * which do not fit into a Kotlin `Int` through `optInt`, so they are read as `Long`.
     */
    private fun readRights(value: Any?): Int? {
        val long = when (value) {
            null, JSONObject.NULL -> null
            is Boolean -> if (value) 0xFFFFFFFFL else 0L
            is Number -> value.toLong()
            is String -> value.trim().let { text ->
                when {
                    text.isEmpty() -> null
                    text.startsWith("0x", ignoreCase = true) -> text.substring(2).toLongOrNull(16)
                    else -> text.toLongOrNull()
                }
            }

            else -> null
        }
        return long?.toInt()
    }

    private fun readSiteAdmin(value: Any?): Boolean {
        return when (value) {
            null, JSONObject.NULL -> false
            is Boolean -> value
            is Number -> value.toLong().toInt() == MeshRights.FULL
            is String -> value.equals("true", ignoreCase = true) || value.toLongOrNull()?.toInt() == MeshRights.FULL
            else -> false
        }
    }
}
