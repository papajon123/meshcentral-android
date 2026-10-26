package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject

/** Live agent session data, not cached system inventory. Missing fields remain unknown. */
data class PhoneBattery(val level: Double? = null, val state: String? = null) {
    val charging: Boolean? get() = when (state) { "ac" -> true; "dc" -> false; else -> null }

    companion object {
        fun parse(value: JSONObject?): PhoneBattery? {
            if (value == null) return null
            val level = (value.opt("level") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..100.0 }
            val state = (value.opt("state") as? String)?.takeIf { it == "ac" || it == "dc" }
            return PhoneBattery(level, state)
        }
    }
}
