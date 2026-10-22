package ru.papajon.meshcentralmobile.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * Defensive, bounded parser for the companion plugin.
 *
 * It copies only allowlisted fields into typed models. Unknown keys, free-form server messages
 * and the `output` field are never read. Every string and array is capped; malformed required
 * fields become [CompanionErrorCode.PROTOCOL] instead of reaching the UI.
 */
internal object CompanionParser {
    private const val MAX_ID = 128
    private const val MAX_NODE_ID = 512
    private const val MAX_TEXT = 512
    private const val MAX_DISPLAY_TEXT = 256
    private const val MAX_DIAGNOSTIC_ITEMS = 100
    private const val MAX_ITEMS = 200
    private const val MAX_ADDRESSES = 32
    private const val MAX_JOBS = 40
    private const val MAX_EVENTS = 100

    private val controlChars = Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]")

    private class Bounds {
        var truncated = false
    }

    fun parseCapabilities(data: JSONObject): CompanionCapabilities {
        val protocol = intOrNull(data.value("protocolVersion"), 1, Int.MAX_VALUE)
            ?: fail()
        val schema = intOrNull(data.value("schemaVersion"), 0, Int.MAX_VALUE) ?: 1
        val operations = stringSet(data.value("operations"), 64, 32)
        val platforms = stringSet(data.value("platforms"), 64, 16)
        val eventKinds = stringSet(data.value("events"), 64, 32)
            .map { CompanionEventKind.fromWire(it) }
            .filter { it != CompanionEventKind.UNKNOWN }
            .toSet()
        val templatesRaw = array(data.value("templates"))
        val templates = buildList {
            for (index in 0 until templatesRaw.length().coerceAtMost(32)) {
                val item = templatesRaw.opt(index) as? JSONObject ?: continue
                val id = identifier(item.value("templateId"), 64) ?: continue
                add(
                    CompanionTemplateInfo(
                        id = id,
                        kind = cleanString(item.value("kind"), 16, Bounds()),
                        parameters = stringSet(item.value("parameters"), 64, 16),
                    ),
                )
            }
        }
        val limitsRaw = data.value("limits") as? JSONObject
        val limits = CompanionLimits(
            maxJobs = intOrNull(limitsRaw?.value("maxJobs"), 0, Int.MAX_VALUE),
            maxEvents = intOrNull(limitsRaw?.value("maxEvents"), 0, Int.MAX_VALUE),
            maxOutputBytes = intOrNull(limitsRaw?.value("maxOutputBytes"), 0, Int.MAX_VALUE),
            historyDays = intOrNull(limitsRaw?.value("historyDays"), 0, Int.MAX_VALUE),
            requestIdMaxLength = intOrNull(limitsRaw?.value("requestIdMaxLength"), 0, Int.MAX_VALUE),
            eventLimitMax = intOrNull(limitsRaw?.value("eventLimitMax"), 0, Int.MAX_VALUE),
            diagnosticsAgentWaitSeconds = intOrNull(
                limitsRaw?.value("diagnosticsAgentWaitSeconds"),
                0,
                Int.MAX_VALUE,
            ),
            executionTimeoutSeconds = intOrNull(
                limitsRaw?.value("executionTimeoutSeconds"),
                0,
                Int.MAX_VALUE,
            ),
        )
        return CompanionCapabilities(
            protocolVersion = protocol,
            schemaVersion = schema,
            operations = operations,
            templates = templates,
            platforms = platforms,
            eventKinds = eventKinds,
            limits = limits,
        )
    }

    fun parseJob(raw: JSONObject?): CompanionJob {
        val job = raw ?: fail()
        val id = identifier(job.value("id"), MAX_ID) ?: fail()
        val nodeId = identifier(job.value("nodeid"), MAX_NODE_ID) ?: fail()
        val templateId = identifier(job.value("templateId"), 64) ?: fail()
        val template = CompanionTemplate.fromId(templateId)
        val createdAt = longOrNull(job.value("createdAt"), 1L, Long.MAX_VALUE) ?: fail()
        val parameters = parseParameters(job.value("parameters"))
        val status = CompanionJobStatus.fromWire(cleanString(job.value("status"), 32, Bounds()))
        val exitCode = intOrNull(job.value("exitCode"), Int.MIN_VALUE, Int.MAX_VALUE)
        val errorCode = CompanionJobErrorCode.fromWire(
            cleanString(job.value("errorCode"), 64, Bounds()),
        )
        val rawResult = job.value("result")
        var parseFailed = false
        var result: CompanionResult? = null
        if (rawResult != null) {
            val resultObject = rawResult as? JSONObject
            if (resultObject == null) {
                parseFailed = true
            } else if (template == null) {
                parseFailed = true
            } else {
                try {
                    result = parseResult(template, job, resultObject)
                } catch (_: CompanionException) {
                    parseFailed = true
                }
            }
        }
        return CompanionJob(
            id = id,
            nodeId = nodeId,
            templateId = templateId,
            template = template,
            parameters = parameters,
            createdAt = createdAt,
            startedAt = longOrNull(job.value("startedAt"), 1L, Long.MAX_VALUE),
            finishedAt = longOrNull(job.value("finishedAt"), 1L, Long.MAX_VALUE),
            status = status,
            exitCode = exitCode,
            result = result,
            resultParseFailed = parseFailed,
            truncated = boolean(job.value("truncated")),
            errorCode = errorCode,
        )
    }

    fun parseJobOrNull(raw: Any?): CompanionJob? {
        val item = raw as? JSONObject ?: return null
        return try {
            parseJob(item)
        } catch (_: CompanionException) {
            null
        }
    }

    fun parseJobs(data: JSONObject): List<CompanionJob> {
        val array = data.value("jobs") as? JSONArray ?: fail()
        return buildList {
            for (index in 0 until array.length().coerceAtMost(MAX_JOBS)) {
                parseJobOrNull(array.opt(index))?.let { add(it) }
            }
        }
    }

    fun parseEvents(data: JSONObject): CompanionEventsPage {
        val array = data.value("items") as? JSONArray ?: fail()
        val items = buildList {
            for (index in 0 until array.length().coerceAtMost(MAX_EVENTS)) {
                val event = array.opt(index) as? JSONObject ?: continue
                parseEvent(event)?.let { add(it) }
            }
        }
        return CompanionEventsPage(
            items = items,
            cursor = longOrNull(data.value("cursor"), 0L, Long.MAX_VALUE) ?: 0L,
            resetCursor = boolean(data.value("resetCursor")),
        )
    }

    private fun parseEvent(raw: JSONObject): CompanionEvent? {
        val id = identifier(raw.value("id"), MAX_ID) ?: return null
        val sequence = longOrNull(raw.value("sequence"), 0L, Long.MAX_VALUE) ?: return null
        val time = longOrNull(raw.value("time"), 0L, Long.MAX_VALUE) ?: return null
        return CompanionEvent(
            id = id,
            sequence = sequence,
            kind = CompanionEventKind.fromWire(cleanString(raw.value("kind"), 64, Bounds())),
            nodeId = identifier(raw.value("nodeid"), MAX_NODE_ID),
            jobId = identifier(raw.value("jobId"), MAX_ID),
            time = time,
            expiresAt = longOrNull(raw.value("expiresAt"), 0L, Long.MAX_VALUE),
        )
    }

    private fun parseParameters(raw: Any?): Map<String, String> {
        val objectValue = raw as? JSONObject ?: return emptyMap()
        // The protocol allows only one parameter, `name`, for service mutations.
        val name = cleanString(objectValue.value("name"), MAX_DISPLAY_TEXT, Bounds())
        return if (name != null) mapOf("name" to name) else emptyMap()
    }

    private fun parseResult(
        template: CompanionTemplate,
        job: JSONObject,
        result: JSONObject,
    ): CompanionResult = when (template) {
        CompanionTemplate.DIAGNOSTICS -> parseDiagnostics(result)
        CompanionTemplate.SERVICES -> parseServices(result)
        CompanionTemplate.PROCESSES -> parseProcesses(result)
        CompanionTemplate.SERVICE_START,
        CompanionTemplate.SERVICE_STOP,
        CompanionTemplate.SERVICE_RESTART,
        -> parseServiceMutation(template, job, result)
    }

    private fun parseDiagnostics(raw: JSONObject): CompanionDiagnostics {
        val ctx = Bounds()
        val os = platform(raw.value("os"))
        val cpuRaw = raw.value("cpu") as? JSONObject ?: JSONObject()
        val memoryRaw = raw.value("memory") as? JSONObject ?: JSONObject()
        val disksRaw = array(raw.value("disks"))
        if (disksRaw.length() > MAX_DIAGNOSTIC_ITEMS) ctx.truncated = true
        val disks = buildList {
            for (index in 0 until disksRaw.length().coerceAtMost(MAX_DIAGNOSTIC_ITEMS)) {
                val disk = disksRaw.opt(index) as? JSONObject ?: continue
                add(
                    CompanionDisk(
                        device = cleanString(disk.value("device"), MAX_TEXT, ctx),
                        mount = cleanString(disk.value("mount"), MAX_TEXT, ctx),
                        totalBytes = longOrNull(disk.value("totalBytes"), 0L, Long.MAX_VALUE),
                        availableBytes = longOrNull(
                            disk.value("availableBytes"),
                            0L,
                            Long.MAX_VALUE,
                        ),
                        usedPercent = doubleOrNull(disk.value("usedPercent"), 0.0, 100.0),
                    ),
                )
            }
        }
        val networkRaw = array(raw.value("network"))
        if (networkRaw.length() > MAX_DIAGNOSTIC_ITEMS) ctx.truncated = true
        val network = buildList {
            for (index in 0 until networkRaw.length().coerceAtMost(MAX_DIAGNOSTIC_ITEMS)) {
                val iface = networkRaw.opt(index) as? JSONObject ?: continue
                val addressesRaw = array(iface.value("addresses"))
                if (addressesRaw.length() > MAX_ADDRESSES) ctx.truncated = true
                val addresses = buildList {
                    for (addressIndex in 0 until addressesRaw.length().coerceAtMost(MAX_ADDRESSES)) {
                        cleanString(addressesRaw.opt(addressIndex), MAX_TEXT, ctx)?.let { add(it) }
                    }
                }
                add(
                    CompanionNetworkInterface(
                        name = cleanString(iface.value("name"), MAX_TEXT, ctx),
                        mac = cleanString(iface.value("mac"), MAX_TEXT, ctx),
                        addresses = addresses,
                    ),
                )
            }
        }
        val diagnostics = CompanionDiagnostics(
            os = os,
            hostname = cleanString(raw.value("hostname"), MAX_TEXT, ctx),
            osDescription = cleanString(raw.value("osDescription"), MAX_TEXT, ctx),
            uptimeSeconds = longOrNull(raw.value("uptimeSeconds"), 0L, Long.MAX_VALUE),
            cpu = CompanionCpu(
                logicalCount = intOrNull(cpuRaw.value("logicalCount"), 1, Int.MAX_VALUE),
                load1 = doubleOrNull(cpuRaw.value("load1"), 0.0, Double.MAX_VALUE),
                loadPercent = doubleOrNull(cpuRaw.value("loadPercent"), 0.0, 100.0),
            ),
            memory = CompanionMemory(
                totalBytes = longOrNull(memoryRaw.value("totalBytes"), 0L, Long.MAX_VALUE),
                availableBytes = longOrNull(memoryRaw.value("availableBytes"), 0L, Long.MAX_VALUE),
                usedPercent = doubleOrNull(memoryRaw.value("usedPercent"), 0.0, 100.0),
            ),
            disks = disks,
            network = network,
            truncated = ctx.truncated || boolean(raw.value("truncated")),
        )
        val hasData = diagnostics.hostname != null ||
            diagnostics.osDescription != null ||
            diagnostics.uptimeSeconds != null ||
            diagnostics.cpu.logicalCount != null ||
            diagnostics.memory.totalBytes != null ||
            diagnostics.disks.isNotEmpty() ||
            diagnostics.network.isNotEmpty()
        if (!hasData) fail()
        return diagnostics
    }

    private fun parseServices(raw: JSONObject): CompanionServices {
        val ctx = Bounds()
        val itemsRaw = raw.value("items") as? JSONArray ?: fail()
        if (itemsRaw.length() > MAX_ITEMS) ctx.truncated = true
        val items = buildList {
            for (index in 0 until itemsRaw.length().coerceAtMost(MAX_ITEMS)) {
                val item = itemsRaw.opt(index) as? JSONObject ?: continue
                val name = cleanString(item.value("name"), MAX_DISPLAY_TEXT, ctx) ?: continue
                add(
                    CompanionServiceItem(
                        name = name,
                        displayName = cleanString(item.value("displayName"), MAX_DISPLAY_TEXT, ctx),
                        state = cleanString(item.value("state"), MAX_DISPLAY_TEXT, ctx),
                        startMode = cleanString(item.value("startMode"), MAX_DISPLAY_TEXT, ctx),
                    ),
                )
            }
        }
        return CompanionServices(
            os = platform(raw.value("os")),
            items = items,
            truncated = ctx.truncated || boolean(raw.value("truncated")),
        )
    }

    private fun parseProcesses(raw: JSONObject): CompanionProcesses {
        val ctx = Bounds()
        val itemsRaw = raw.value("items") as? JSONArray ?: fail()
        if (itemsRaw.length() > MAX_ITEMS) ctx.truncated = true
        val items = buildList {
            for (index in 0 until itemsRaw.length().coerceAtMost(MAX_ITEMS)) {
                val item = itemsRaw.opt(index) as? JSONObject ?: continue
                val pid = longOrNull(item.value("pid"), 0L, Long.MAX_VALUE) ?: continue
                val name = cleanString(item.value("name"), MAX_DISPLAY_TEXT, ctx) ?: continue
                add(
                    CompanionProcessItem(
                        pid = pid,
                        name = name,
                        cpu = doubleOrNull(item.value("cpu"), 0.0, Double.MAX_VALUE),
                        memoryBytes = longOrNull(item.value("memory"), 0L, Long.MAX_VALUE),
                    ),
                )
            }
        }
        return CompanionProcesses(
            os = platform(raw.value("os")),
            items = items,
            truncated = ctx.truncated || boolean(raw.value("truncated")),
        )
    }

    private fun parseServiceMutation(
        template: CompanionTemplate,
        job: JSONObject,
        raw: JSONObject,
    ): CompanionServiceMutation {
        val ctx = Bounds()
        val operation = cleanString(raw.value("operation"), 64, ctx) ?: fail()
        if (operation != template.id) fail()
        val name = cleanString(raw.value("name"), MAX_DISPLAY_TEXT, ctx) ?: fail()
        val requestedName = cleanString(
            (job.value("parameters") as? JSONObject)?.value("name"),
            MAX_DISPLAY_TEXT,
            Bounds(),
        )
        if (requestedName != null && requestedName != name) fail()
        val changed = raw.value("changed") as? Boolean ?: fail()
        return CompanionServiceMutation(
            os = platform(raw.value("os")),
            operation = operation,
            name = name,
            changed = changed,
            state = cleanString(raw.value("state"), MAX_DISPLAY_TEXT, ctx),
        )
    }

    private fun platform(value: Any?): String? {
        val text = cleanString(value, 16, Bounds()) ?: return null
        return text.takeIf { it == "windows" || it == "linux" }
    }

    private fun array(value: Any?): JSONArray = when (value) {
        null -> JSONArray()
        is JSONArray -> value
        else -> fail()
    }

    private fun stringSet(value: Any?, maxLength: Int, maxItems: Int): Set<String> {
        val array = value as? JSONArray ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length().coerceAtMost(maxItems)) {
                cleanString(array.opt(index), maxLength, Bounds())?.let { add(it) }
            }
        }
    }

    private fun identifier(value: Any?, maxLength: Int): String? {
        if (value !is String) return null
        val text = value.trim()
        if (text.isEmpty() || text.length > maxLength) return null
        if (text.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null
        return text
    }

    private fun cleanString(value: Any?, maxLength: Int, bounds: Bounds): String? {
        if (value !is String) return null
        var text = value.replace(controlChars, " ").trim()
        if (text.isEmpty()) return null
        if (text.length > maxLength) {
            text = text.take(maxLength)
            bounds.truncated = true
        }
        return text
    }

    private fun boolean(value: Any?): Boolean = value == true

    private fun intOrNull(value: Any?, min: Int, max: Int): Int? {
        val number = value as? Number ?: return null
        val asDouble = number.toDouble()
        if (!asDouble.isFinite() || asDouble % 1.0 != 0.0) return null
        if (asDouble < min.toDouble() || asDouble > max.toDouble()) return null
        return asDouble.toInt()
    }

    private fun longOrNull(value: Any?, min: Long, max: Long): Long? {
        val number = value as? Number ?: return null
        val asDouble = number.toDouble()
        if (!asDouble.isFinite() || asDouble % 1.0 != 0.0) return null
        if (asDouble < min.toDouble() || asDouble > max.toDouble()) return null
        return asDouble.toLong()
    }

    private fun doubleOrNull(value: Any?, min: Double, max: Double): Double? {
        val number = value as? Number ?: return null
        val asDouble = number.toDouble()
        if (!asDouble.isFinite() || asDouble < min || asDouble > max) return null
        return asDouble
    }

    private fun JSONObject.value(key: String): Any? {
        if (!has(key)) return null
        val value = opt(key) ?: return null
        return if (value === JSONObject.NULL) null else value
    }

    private fun fail(): Nothing = throw CompanionException(CompanionErrorCode.PROTOCOL)
}
