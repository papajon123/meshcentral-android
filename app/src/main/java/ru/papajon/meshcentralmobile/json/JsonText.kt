package ru.papajon.meshcentralmobile.json

import org.json.JSONArray
import org.json.JSONObject

/** Android optString coerces JSONObject.NULL to "null", unlike the JVM org.json dependency.
 * Treat an explicit JSON null exactly like a missing value, retaining all other coercions.
 * The literal string "null" is data and must not be discarded.
 */
fun JSONObject.optText(name: String, fallback: String = ""): String =
    if (isNull(name)) fallback else optString(name, fallback)

fun JSONArray.optText(index: Int, fallback: String = ""): String =
    if (isNull(index)) fallback else optString(index, fallback)
