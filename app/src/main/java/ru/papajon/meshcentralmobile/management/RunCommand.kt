package ru.papajon.meshcentralmobile.management

import ru.papajon.meshcentralmobile.json.optText

import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights
import java.util.Base64

object RunCommand {
    const val OUTPUT_LIMIT = 65536
    // MeshAgent converts stdout/stderr buffers before serializing JSON. Lost bytes cannot be
    // recovered by guessing a Windows code page on Android. Opt-in only: shell syntax changes.
    internal const val POWERSHELL_UTF8 = "[Console]::InputEncoding = [System.Text.UTF8Encoding]::new(\u0024false); " +
        "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(\u0024false); " +
        "\u0024OutputEncoding = [Console]::OutputEncoding;\r\n"
    internal fun powershellScript(command: String): String {
        // -Command - reads stdin using the encoding chosen when PowerShell started, even after
        // InputEncoding changes. An ASCII envelope avoids destroying Cyrillic in the command too.
        // Dot-source the decoded body so variables and exit retain top-level PowerShell semantics.
        val body = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        return POWERSHELL_UTF8 + ". ([scriptblock]::Create([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$body'))))"
    }
    fun valid(command: String): Boolean = command.isNotBlank() && command.length <= 4096 && '\u0000' !in command
    fun allowed(device: MeshDevice, current: Boolean): Boolean = current && device.isMeshAgent && !device.isAndroidAgent &&
        device.connectivityKnown && device.conn and 1 != 0 && device.rights?.let { it and MeshRights.REMOTE_COMMAND != 0 } == true
    fun create(device: MeshDevice, current: Boolean, command: String, confirmed: Boolean, powershellUtf8: Boolean = false): JSONObject {
        require(allowed(device, current) && confirmed && valid(command))
        require(!powershellUtf8 || device.isWindows)
        return JSONObject().put("action", "runcommands").put("nodeids", JSONArray().put(device.nodeId))
            .put("type", if (powershellUtf8) 2 else 0)
            .put("cmds", if (powershellUtf8) powershellScript(command) else command).put("runAsUser", 0).put("reply", true)
    }
    fun output(reply: JSONObject): String {
        require(reply.optText("action") == "msg" && reply.optText("type") == "runcommands")
        return (reply.opt("result") as? String)?.take(OUTPUT_LIMIT) ?: throw IllegalArgumentException("Missing command output")
    }
    fun outputTruncated(reply: JSONObject): Boolean = reply.optBoolean("outputTruncated") ||
        ((reply.opt("result") as? String)?.length ?: 0) > OUTPUT_LIMIT
}
