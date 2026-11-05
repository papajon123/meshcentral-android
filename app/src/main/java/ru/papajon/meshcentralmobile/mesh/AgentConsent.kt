package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject

/** MeshCentral 1.3.1 default.handlebars and meshcore.js:4497; additive per-tunnel options. */
object AgentConsent {
    fun options(usage: Int, requested: Boolean, original: JSONObject? = null): JSONObject? {
        val flag = when (usage) { 1 -> 0x10; 2 -> 0x08; 5 -> 0x20; else -> 0 }
        val copy = original?.let { JSONObject(it.toString()) }
        if (!requested || flag == 0) return copy
        return (copy ?: JSONObject()).apply { put("consent", optInt("consent", 0) or flag) }
    }
}
