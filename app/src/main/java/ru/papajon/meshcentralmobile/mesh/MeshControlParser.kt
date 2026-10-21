package ru.papajon.meshcentralmobile.mesh

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
        val json = runCatching { JSONObject(text) }.getOrElse {
            return MeshControlEvent.Unknown(action = null, rawLength = text.length)
        }
        val action = json.optString("action").takeIf { it.isNotBlank() }
        return when (action) {
            "serverinfo" -> {
                val info = json.optJSONObject("serverinfo")
                MeshControlEvent.ServerInfo(
                    name = info?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: json.optString("name").takeIf { it.isNotBlank() },
                    domain = info?.optString("domain")?.takeIf { it.isNotBlank() },
                )
            }

            "userinfo" -> {
                val info = json.optJSONObject("userinfo")
                MeshControlEvent.UserInfo(
                    userId = info?.optString("_id")?.takeIf { it.isNotBlank() },
                    name = info?.optString("name")?.takeIf { it.isNotBlank() },
                    siteAdmin = readSiteAdmin(info?.opt("siteadmin")),
                )
            }

            "meshes" -> MeshControlEvent.Meshes(parseMeshes(json.opt("meshes")))
            "nodes" -> MeshControlEvent.Nodes(parseNodes(json.opt("nodes")))

            "authcookie" -> MeshControlEvent.AuthCookie(
                cookie = json.optString("cookie").takeIf { it.isNotBlank() },
                relayCookie = json.optString("rcookie").takeIf { it.isNotBlank() },
            )

            "event" -> {
                val event = json.optJSONObject("event")
                if (event == null) {
                    MeshControlEvent.Unknown(action, text.length)
                } else {
                    MeshControlEvent.NodeUpdated(parseNodeEvent(event))
                }
            }

            "msg" -> parseMessage(json, text.length)
            "close" -> MeshControlEvent.Close(
                cause = json.optString("cause").takeIf { it.isNotBlank() },
                message = json.optString("msg").takeIf { it.isNotBlank() },
                twoFactorChallenge = parseTwoFactor(json),
            )

            else -> MeshControlEvent.Unknown(action = action, rawLength = text.length)
        }
    }

    /** `{action:'msg', type:'getclip'|'setclip', ...}` and `{action:'msg', result:'OK'|'Unable to route'}`. */
    private fun parseMessage(json: JSONObject, rawLength: Int): MeshControlEvent {
        val type = json.optString("type")
        if (type == "getclip" || type == "setclip") {
            return MeshControlEvent.ClipMessage(
                type = type,
                success = json.opt("success")?.let { if (it is Boolean) it else null },
                data = json.optString("data").takeIf { json.has("data") && !json.isNull("data") },
                tag = json.optString("tag").takeIf { it.isNotBlank() },
                sessionId = json.optString("sessionid").takeIf { it.isNotBlank() },
            )
        }
        if (json.has("result")) {
            return MeshControlEvent.RoutingResult(
                result = json.optString("result").takeIf { it.isNotBlank() },
                tag = json.optString("tag").takeIf { it.isNotBlank() },
                responseId = json.optString("responseid").takeIf { it.isNotBlank() },
            )
        }
        return MeshControlEvent.Unknown(action = "msg", rawLength = rawLength)
    }

    private fun parseTwoFactor(json: JSONObject): TwoFactorChallenge? {
        if (json.optString("msg") != "tokenrequired") return null
        return TwoFactorChallenge(
            email = json.optBoolean("email2fa", false),
            sms = json.optBoolean("sms2fa", false),
            push = json.optBoolean("msg2fa", false),
            emailSent = json.optBoolean("email2fasent", false),
            smsSent = json.optBoolean("sms2fasent", false),
            pushSent = json.optBoolean("msg2fasent", false),
        )
    }

    private fun parseNodeEvent(event: JSONObject): MeshNodeEvent {
        return when (event.optString("action")) {
            "nodeconnect" -> MeshNodeEvent.NodeConnected(
                nodeId = event.optString("nodeid"),
                conn = event.optInt("conn", 0),
                pwr = event.optInt("pwr", 0),
            )

            "changenode" -> parseNode(event.optJSONObject("node"), fallbackMeshId = "")
                ?.let { MeshNodeEvent.NodeChanged(it) }
                ?: MeshNodeEvent.MeshChanged(null)

            "addnode" -> parseNode(event.optJSONObject("node"), fallbackMeshId = "")
                ?.let { MeshNodeEvent.NodeAdded(it) }
                ?: MeshNodeEvent.MeshChanged(null)

            "removenode" -> MeshNodeEvent.NodeRemoved(event.optString("nodeid"))
            "nodemeshchange", "meshchange", "createmesh", "deletemesh" -> {
                MeshNodeEvent.MeshChanged(event.optString("meshid").takeIf { it.isNotBlank() })
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
                val id = item.optString("_id").takeIf { it.isNotBlank() } ?: continue
                add(
                    MeshGroup(
                        id = id,
                        name = item.optString("name").ifBlank { id },
                        description = item.optString("desc"),
                        rightsByUser = parseLinks(item.optJSONObject("links")),
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
                        is JSONObject -> parseNode(entry, fallbackMeshId = key)?.let { add(it) }
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
                val node = parseNode(item, fallbackMeshId) ?: continue
                add(node)
            }
        }
    }

    fun parseNode(item: JSONObject?, fallbackMeshId: String): MeshDevice? {
        if (item == null) return null
        val nodeId = item.optString("_id").takeIf { it.isNotBlank() } ?: return null
        val agent = item.optJSONObject("agent")
        return MeshDevice(
            nodeId = nodeId,
            meshId = item.optString("meshid").ifBlank { fallbackMeshId },
            name = item.optString("name").ifBlank { nodeId },
            icon = item.optInt("icon", 0),
            conn = item.optInt("conn", 0),
            pwr = item.optInt("pwr", 0),
            osDescription = item.optString("osdesc"),
            ipAddress = item.optString("ip"),
            mtype = item.optInt("mtype", 0),
            agentId = agent?.optIntOrNull("id"),
            agentVersion = agent?.optString("ver")?.takeIf { it.isNotBlank() },
            agentCaps = agent?.optIntOrNull("caps"),
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
    ): List<MeshDevice> {
        if (devices.isEmpty()) return devices
        val rightsByMesh = groups.mapNotNull { group ->
            val rights = group.rightsFor(userId) ?: return@mapNotNull null
            group.id to rights
        }.toMap()
        return devices.map { device ->
            val rights = if (siteAdmin) {
                MeshRights.FULL
            } else {
                rightsByMesh[device.meshId]
            }
            device.copy(rights = rights ?: device.rights)
        }
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
            is Number -> value.toLong() != 0L
            is String -> value.equals("true", ignoreCase = true) || (value.toLongOrNull() ?: 0L) != 0L
            else -> false
        }
    }
}
