package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject

/** Capability, never a substitute for server rights or the phone owner's per-tunnel consent. */
enum class PhoneRemoteInput {
    Unknown, Unsupported, Disabled, Enabled;

    companion object {
        /** MeshCentral 1.3.1 returns the agent's sysinfo.data document as getsysinfo's root. */
        fun fromSystemInfo(reply: JSONObject): PhoneRemoteInput {
            if (reply.optBoolean("noinfo")) return Unknown
            return when (reply.opt("remoteInput")) {
                "unsupported" -> Unsupported
                "disabled" -> Disabled
                "enabled" -> Enabled
                else -> Unknown
            }
        }
    }
}
