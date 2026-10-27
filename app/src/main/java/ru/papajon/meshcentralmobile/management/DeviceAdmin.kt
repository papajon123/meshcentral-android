package ru.papajon.meshcentralmobile.management

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.*

/** Audited against MeshCentral 1.3.1 meshuser.js and default.handlebars. */
object DeviceAdmin {
    val inviteHours = listOf(1, 8, 24, 168)
    private fun has(rights: Int?, mask: Int) = rights != null && rights and mask == mask
    fun canEdit(device: MeshDevice, current: Boolean) = current && has(device.rights, MeshRights.MANAGE_COMPUTERS)
    fun canNote(device: MeshDevice, current: Boolean) = current && has(device.rights, MeshRights.SET_NOTES)
    fun canMove(device: MeshDevice, source: MeshGroup?, target: MeshGroup, current: Boolean) = current &&
        source != null && source.id == device.meshId && source.mtype != 0 && source.mtype == target.mtype && device.meshId != target.id &&
        has(device.rights, MeshRights.EDIT_MESH) && has(target.rights, MeshRights.EDIT_MESH or MeshRights.MANAGE_COMPUTERS)
    // The server accepts any viewable group. We deliberately expose enrollment only to group managers.
    fun canInvite(group: MeshGroup, state: MeshClientState) = state.socketConnected && state.devicesCurrent &&
        state.agentInstallAllowed && state.serverCapabilities.features?.let { it and 2L == 0L } == true &&
        group.mtype == 2 && has(group.rights, MeshRights.MANAGE_COMPUTERS)

    private fun safeSingleLine(value: String) = value.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
    private fun validName(value: String) = value.length <= 64 && value.trim().isNotEmpty() && safeSingleLine(value)
    private fun validDescription(value: String) = value.length <= 64 && safeSingleLine(value)
    private fun validTags(value: String) = value.length <= 4096 && safeSingleLine(value) && value.split(',').all { it.trim().length <= 63 }
    fun validMetadata(name: String, description: String, tags: String) = validName(name) && validDescription(description) && validTags(tags)
    private fun tagsEdited(original: MeshDevice, tags: String) =
        tags != original.tags.joinToString(", ") && tags != original.tags.joinToString(",")
    fun validMetadataEdit(original: MeshDevice, name: String, description: String, tags: String) =
        (name == original.name || validName(name)) && (description == original.description || validDescription(description)) &&
        (!tagsEdited(original, tags) || validTags(tags))
    fun normalizedTags(tags: String): List<String> = tags.split(',').map(String::trim).filter(String::isNotEmpty).distinct().sorted()

    fun edit(device: MeshDevice, current: Boolean, name: String, description: String, tags: String, confirmed: Boolean,
        original: MeshDevice = device): JSONObject {
        require(confirmed && canEdit(device, current) && validMetadataEdit(original, name, description, tags))
        require(device.nodeId == original.nodeId)
        return JSONObject().put("action", "changedevice").put("nodeid", device.nodeId)
            .apply {
                if (name != original.name) put("name", name.trim())
                if (description != original.description) put("desc", description)
                if (tagsEdited(original, tags) && normalizedTags(tags) != original.tags.sorted()) put("tags", JSONArray(normalizedTags(tags)))
            }
    }
    fun editMatches(command: JSONObject, device: MeshDevice): Boolean =
        (!command.has("name") || device.name == command.getString("name")) &&
        (!command.has("desc") || device.description == command.getString("desc")) &&
        (!command.has("tags") || device.tags.sorted() == command.getJSONArray("tags").let { a -> (0 until a.length()).map { a.getString(it) } })
    fun move(device: MeshDevice, source: MeshGroup?, target: MeshGroup, current: Boolean, confirmed: Boolean): JSONObject {
        require(confirmed && canMove(device, source, target, current))
        return JSONObject().put("action", "changeDeviceMesh").put("nodeids", JSONArray().put(device.nodeId)).put("meshid", target.id)
    }
    fun notes(device: MeshDevice, current: Boolean, notes: String, confirmed: Boolean): JSONObject {
        require(confirmed && canNote(device, current) && notes.length <= 16000)
        return JSONObject().put("action", "setNotes").put("id", device.nodeId).put("notes", notes)
    }
    fun readNotes(nodeId: String) = JSONObject().put("action", "getNotes").put("id", nodeId)
    fun parseNotes(reply: JSONObject, nodeId: String): String {
        require(reply.optString("action") == "getNotes" && reply.optString("id") == nodeId && reply.has("notes"))
        return if (reply.isNull("notes")) "" else (reply.opt("notes") as? String ?: error("Invalid notes"))
    }
    fun invite(group: MeshGroup, state: MeshClientState, hours: Int): JSONObject {
        require(canInvite(group, state) && hours in inviteHours)
        return JSONObject().put("action", "createInviteLink").put("meshid", group.id)
            .put("expire", hours).put("flags", 0).put("agents", 0)
    }
    fun requireSuccess(reply: JSONObject) { require(reply.opt("result") == "ok") { "Change not acknowledged" } }

    fun parseInvite(reply: JSONObject, groupId: String, hours: Int, server: HttpUrl, requestedAt: Long, loginKey: String? = null,
        serverInfo: MeshServerCapabilities = MeshServerCapabilities()): DeviceInvitation {
        require(reply.optString("action") == "createInviteLink" && reply.optString("meshid") == groupId)
        require(!reply.has("result") && reply.opt("expire") is Number && reply.getInt("expire") == hours && hours in inviteHours)
        val cookie = reply.opt("cookie") as? String ?: error("Missing invitation")
        require(cookie.length in 1..2048 && cookie.all { it.isLetterOrDigit() && it.code < 128 || it in "@$%_-=" })
        val url = invitationEndpoint(server, serverInfo).newBuilder()
            .addQueryParameter("c", cookie)
            .apply { if (!loginKey.isNullOrBlank()) addQueryParameter("key", loginKey) }.build().toString()
        require(url.toByteArray(Charsets.UTF_8).size <= 2000) { "Invitation too long for QR" }
        return DeviceInvitation(url, requestedAt + hours * 3_600_000L)
    }

    /** Mirrors default.handlebars:4072–4082, retaining the authenticated domain path. */
    fun invitationEndpoint(server: HttpUrl, info: MeshServerCapabilities): HttpUrl {
        val host = info.advertisedName?.takeIf { '.' in it && (info.features ?: 0L) and 2L == 0L } ?: server.host
        val scheme = info.advertisedHttps?.let { if (it) "https" else "http" } ?: server.scheme
        return server.newBuilder().username("").password("").query(null).fragment(null)
            .scheme(scheme).host(host).port(info.advertisedPort ?: server.port)
            .encodedPath(server.encodedPath.trimEnd('/') + "/agentinvite").build()
    }
}

/** Enrollment URLs are sensitive: never include them in generated data-class diagnostics. */
class DeviceInvitation(val url: String, val expiresAtMillis: Long) {
    val endpointLabel: String get() {
        val parsed = url.toHttpUrl()
        return (if (':' in parsed.host) "[${parsed.host}]" else parsed.host) + ":" + parsed.port
    }
    fun active(now: Long = System.currentTimeMillis()) = now < expiresAtMillis
    override fun toString() = "DeviceInvitation(redacted)"
}
