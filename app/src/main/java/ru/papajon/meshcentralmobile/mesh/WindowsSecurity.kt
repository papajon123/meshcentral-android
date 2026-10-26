package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject

enum class SecurityStatus { Ok, Warning, Problem, Unknown }

data class WindowsSecurityCenter(
    val antiVirus: SecurityStatus = SecurityStatus.Unknown,
    val autoUpdate: SecurityStatus = SecurityStatus.Unknown,
    val firewall: SecurityStatus = SecurityStatus.Unknown,
)

data class AntivirusProduct(val product: String?, val enabled: Boolean?, val updated: Boolean?)

/** Null/absent and unrecognised values are not evidence of a security problem. */
data class WindowsSecurity(
    val center: WindowsSecurityCenter = WindowsSecurityCenter(),
    val products: List<AntivirusProduct> = emptyList(),
    val reportedFields: Set<String> = emptySet(),
) {
    val needsAttention: Boolean get() =
        listOf(center.antiVirus, center.autoUpdate, center.firewall).any {
            it == SecurityStatus.Warning || it == SecurityStatus.Problem
        } || (products.isNotEmpty() && products.all { it.enabled == false }) ||
            products.any { it.enabled == true && it.updated == false }

    /** changenode may omit unrelated fields; an explicit null clears a previously known value. */
    fun updatedBy(incoming: WindowsSecurity) = WindowsSecurity(
        center = if ("wsc" in incoming.reportedFields) incoming.center else center,
        products = if ("av" in incoming.reportedFields) incoming.products else products,
        reportedFields = reportedFields + incoming.reportedFields,
    )
}

object WindowsSecurityParser {
    fun parse(node: JSONObject): WindowsSecurity {
        val wsc = node.optJSONObject("wsc")
        val av = node.optJSONArray("av")
        return WindowsSecurity(
            center = WindowsSecurityCenter(status(wsc?.opt("antiVirus")), status(wsc?.opt("autoUpdate")), status(wsc?.opt("firewall"))),
            products = if (av == null) emptyList() else (0 until minOf(av.length(), 100)).mapNotNull { index ->
                val item = av.optJSONObject(index) ?: return@mapNotNull null
                AntivirusProduct((item.opt("product") as? String)?.trim()?.take(256)?.takeIf { it.isNotEmpty() },
                    item.opt("enabled") as? Boolean, item.opt("updated") as? Boolean)
            },
            reportedFields = setOf("wsc", "av").filterTo(mutableSetOf()) { node.has(it) },
        )
    }

    private fun status(value: Any?): SecurityStatus = when (value) {
        "OK" -> SecurityStatus.Ok
        "WARNING" -> SecurityStatus.Warning
        "PROBLEM" -> SecurityStatus.Problem
        else -> SecurityStatus.Unknown
    }
}
