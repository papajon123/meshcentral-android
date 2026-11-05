package ru.papajon.meshcentralmobile.testing

import org.json.JSONArray
import org.json.JSONObject

/** Structural assertions using the Android JSON API, independent of JVM-only convenience APIs. */
fun JSONObject.hasSameJsonContent(other: JSONObject): Boolean = normalize(this) == normalize(other)

private fun normalize(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value.keys().asSequence().associateWith { normalize(value.get(it)) }
    is JSONArray -> (0 until value.length()).map { normalize(value.get(it)) }
    is Number -> value.toString().toBigDecimal().stripTrailingZeros()
    else -> value
}
