package ru.papajon.meshcentralmobile.management.tasks

import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights

data class AgentProcess(
    val pid: Long,
    val name: String,
    val user: String? = null,
    val command: String? = null,
)

data class AgentService(
    val name: String,
    val displayName: String,
    val state: String? = null,
    val startType: String? = null,
    val pid: Long? = null,
    val serviceType: String? = null,
    val observedNotRunning: Boolean = false,
) {
    /** Null means the agent did not report a state, or reported one this client does not interpret. */
    val running: Boolean?
        get() = when (state?.trim()?.uppercase(Locale.ROOT)) {
            "RUNNING" -> true
            "STOPPED" -> false
            else -> if (observedNotRunning) false else null
        }
}

enum class TaskServiceAction(val wire: String) {
    Start("serviceStart"),
    Stop("serviceStop"),
    Restart("serviceRestart"),
}

/** MeshCentral 1.3.1 task tools: `ps`, `services`, `psinfo`, `pskill` and service start/stop/restart. */
object TaskToolsProtocol {
    private val CRITICAL_PROCESS_NAMES = setOf(
        "system",
        "smss",
        "csrss",
        "wininit",
        "winlogon",
        "services",
        "lsass",
        "init",
        "systemd",
        "meshagent",
        "meshcentral",
        "meshcentralagent",
    )

    private val CRITICAL_SERVICE_NAMES = setOf(
        "rpcss",
        "dcomlaunch",
        "rpceptmapper",
        "samss",
        "lsm",
        "wininit",
        "eventlog",
        "power",
        "plugplay",
        "winmgmt",
    )

    private val MESH_AGENT_SERVICE_NAMES = setOf(
        "meshagent",
        "meshagentservice",
        "meshcentral",
        "meshcentralagent",
        "meshcentralservice",
    )

    // Audited psinfo fields. Everything else is deliberately not retained.
    private val DETAIL_FIELDS = listOf(
        "processName",
        "processUser",
        "processDomain",
        "cmd",
        "path",
        "userName",
        "startTime",
        "mainWindowTitle",
        "machineName",
        "privateMemorySize",
        "virtualMemorySize",
        "workingSet",
        "peakWorkingSet",
        "peakVirtualMemorySize",
        "peakPagedMemorySize",
        "pagedSystemMemorySize",
        "pagedMemorySize",
        "nonpagedSystemMemorySize",
        "totalProcessorTime",
        "userProcessorTime",
        "privilegedProcessorTime",
        "sessionId",
        "handleCount",
        "PriorityBoostEnabled",
    )
    private val DETAIL_FIELDS_BY_LOWER = DETAIL_FIELDS.associateBy { it.lowercase(Locale.ROOT) }

    /**
     * The agent sends `ps` as a JSON string keyed by PID (the server may also deliver the object).
     * Malformed entries invalidate the list. An empty display result is not verification evidence.
     */
    fun processes(reply: JSONObject): List<AgentProcess> {
        val source = when (val value = replyValue(reply)) {
            is JSONObject -> value
            is String -> parseJson(value) as? JSONObject ?: invalid("Process list is not an object")
            else -> invalid("Process list is not an object")
        }
        val seen = HashSet<Long>()
        val header = source.optJSONObject("PID")
        val hasHeader = header?.opt("pid") == "PID" && header.opt("user") == "USER"
        val rows = source.keys().asSequence().filterNot { it == "PID" && hasHeader }.map { key ->
            val pid = parsePidKey(key)
            require(seen.add(pid)) { "Duplicate PID" }
            val item = source.opt(key) as? JSONObject ?: invalid("Invalid process entry")
            val name = requiredString(item, "cmd")
            require(name.isNotBlank()) { "Empty process name" }
            AgentProcess(
                pid = pid,
                name = name,
                user = optionalString(item, "user"),
                command = optionalString(item, "path"),
            )
        }.sortedBy { it.pid }.toList()
        // Linux process-manager sometimes includes the ps column header. A header alone is not
        // a trustworthy empty snapshot and must never confirm that a process disappeared.
        require(!hasHeader || rows.isNotEmpty()) { "Header without processes" }
        return rows.filter { it.pid != 0L }
    }

    /**
     * Services arrive as an array or an object with numeric keys. Windows status and PID are optional for display but must be valid
     * when present. An empty list is valid; a malformed list is never collapsed to one.
     */
    fun services(reply: JSONObject): List<AgentService> {
        val parsed = when (val value = replyValue(reply)) {
            is JSONObject, is JSONArray -> value
            is String -> parseJson(value)
            else -> invalid("Service list is not a list")
        }
        val entries: List<Pair<Int, JSONObject>> = when (parsed) {
            is JSONArray -> (0 until parsed.length()).map { index ->
                index to (parsed.opt(index) as? JSONObject ?: invalid("Invalid service entry"))
            }
            is JSONObject -> {
                val seen = HashSet<Int>()
                parsed.keys().asSequence().map { key ->
                    val index = parseIndexKey(key)
                    require(seen.add(index)) { "Duplicate service index" }
                    index to (parsed.opt(key) as? JSONObject ?: invalid("Invalid service entry"))
                }.sortedBy { it.first }.toList()
            }
            else -> invalid("Service list is not a list")
        }
        // The Linux manager can enumerate each systemd unit twice. Identical rows are safe to
        // collapse; contradictory states for one name are not a verification snapshot.
        val services = entries.map { (_, item) -> parseService(item) }.distinct()
        require(services.map { it.name }.toSet().size == services.size) { "Duplicate service name" }
        return services
    }

    /**
     * Returns only the audited psinfo fields whose values are scalar. The requested PID must match
     * the top-level `pid`; a reply for another process is not this process's details.
     */
    fun details(reply: JSONObject, pid: Long): Map<String, String> {
        require(pid >= 0L) { "Invalid PID" }
        require(integralPid(reply.opt("pid")) == pid) { "PID mismatch" }
        val source = when (val value = replyValue(reply)) {
            is JSONObject -> value
            is String -> parseJson(value) as? JSONObject ?: invalid("Process details are not an object")
            else -> invalid("Process details are not an object")
        }
        val details = linkedMapOf<String, String>()
        for (key in source.keys()) {
            val canonical = DETAIL_FIELDS_BY_LOWER[key.lowercase(Locale.ROOT)] ?: continue
            val value = source.opt(key)
            if (value == null || value === JSONObject.NULL) continue
            val text = when (value) {
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> continue
            }
            details[canonical] = text
        }
        return details
    }

    fun serviceDetails(reply: JSONObject, name: String): AgentService {
        require(!reply.has("error")) { "Service details unavailable" }
        val value = replyValue(reply)
        val obj = if (value is String) parseJson(value) as? JSONObject else value as? JSONObject
        require(obj != null && obj.opt("name") == name) { "Service mismatch" }
        // getService on Unix can return empty strings for absent status/startType.
        if (obj.opt("status") == "") obj.remove("status")
        return parseService(obj)
    }

    /** Windows, Linux and known macOS agents expose task tools; Android and unknown agents do not. */
    fun visible(device: MeshDevice): Boolean {
        if (!device.isMeshAgent || device.isAndroidAgent) return false
        val os = device.osDescription.lowercase(Locale.ROOT)
        val mac = device.agentId == 11 || device.agentId == 16 || device.agentId == 29 ||
            os == "mac" || os.contains("macos") || os.contains("mac os") ||
            os.contains("macintosh") || os.contains("darwin")
        return device.isWindows || device.isLinux || mac
    }

    /** Lists need a known current agent connection and remote-control or remote-view rights. */
    fun canRead(device: MeshDevice, current: Boolean): Boolean {
        if (!current || !device.connectivityKnown || (device.conn and 1) == 0) return false
        val rights = device.rights ?: return false
        if (rights == MeshRights.FULL) return true
        return rights >= 0 && (rights and (MeshRights.REMOTE_CONTROL or MeshRights.REMOTE_VIEW)) != 0
    }

    /** Matches web inputAllowed: Android is read-only; absent features2 has no set bits. */
    fun canMutate(device: MeshDevice, current: Boolean, features2: Long? = null): Boolean =
        canRead(device, current) && !device.isAndroidAgent && (features2 ?: 0L) and 0x2000L == 0L &&
            MeshRights.allowsUnrestrictedInput(device.rights)

    /** Only the two list reads are exposed here; psinfo is requested separately by PID. */
    fun read(nodeId: String, type: String): JSONObject {
        require(nodeId.isNotBlank() && nodeId.none { it.isISOControl() }) { "Invalid node id" }
        require(type == "ps" || type == "services") { "Unsupported task read" }
        return JSONObject().put("action", "msg").put("nodeid", nodeId).put("type", type)
    }

    /** `value` is `"<pid>|<name>"`; the name is also used by the server's audit log. */
    fun kill(device: MeshDevice, current: Boolean, process: AgentProcess, confirmed: Boolean, features2: Long? = null): JSONObject {
        require(confirmed) { "Confirmation required" }
        require(canMutate(device, current, features2)) { "Permission denied" }
        require(process.pid > 0L) { "Invalid PID" }
        require(validAuditName(process.name)) { "Invalid process name" }
        return JSONObject().put("action", "msg").put("type", "pskill").put("nodeid", device.nodeId)
            .put("value", "${process.pid}|${process.name}")
    }

    /** The agent addresses a service by its exact name in the `serviceName` field. */
    fun service(
        device: MeshDevice,
        current: Boolean,
        target: AgentService,
        action: TaskServiceAction,
        confirmed: Boolean,
        features2: Long? = null,
    ): JSONObject {
        require(confirmed) { "Confirmation required" }
        require(canMutate(device, current, features2)) { "Permission denied" }
        require(validServiceName(target.name)) { "Invalid service name" }
        return JSONObject().put("action", "msg").put("type", action.wire)
            .put("nodeid", device.nodeId).put("serviceName", target.name)
    }

    fun critical(process: AgentProcess): Boolean {
        if (process.pid == 0L || process.pid == 1L || process.pid == 4L) return true
        val name = compactName(process.name.replace(Regex("\\s+-.*$"), ""))
        return name in CRITICAL_PROCESS_NAMES
    }

    fun critical(service: AgentService): Boolean = sequenceOf(service.name, service.displayName).any { name ->
        val compact = compactName(name)
        val unit = name.lowercase(Locale.ROOT).removeSuffix(".service")
        compact in CRITICAL_SERVICE_NAMES || compact in MESH_AGENT_SERVICE_NAMES ||
            unit in setOf("ssh", "sshd", "networkmanager", "networking", "meshagent") ||
            unit.startsWith("systemd-") || unit.startsWith("meshagent@")
    }

    private fun parseService(item: JSONObject): AgentService {
        val name = requiredString(item, "name")
        require(name.isNotBlank()) { "Empty service name" }
        val displayName = optionalString(item, "displayName")?.takeIf { it.isNotBlank() } ?: name
        val status = optionalObject(item, "status")
        return AgentService(
            name = name,
            displayName = displayName,
            state = status?.let { optionalString(it, "state") } ?: optionalString(item, "state"),
            startType = optionalString(item, "startType")?.takeIf { it.isNotBlank() },
            pid = status?.let { optionalPid(it, "pid") },
            serviceType = optionalString(item, "serviceType"),
        )
    }

    private fun replyValue(reply: JSONObject): Any {
        val value = reply.opt("value")
        if (value == null || value === JSONObject.NULL) invalid("Missing value")
        return value
    }

    private fun requiredString(obj: JSONObject, key: String): String {
        val value = obj.opt(key)
        return value as? String ?: invalid("Invalid $key")
    }

    private fun optionalString(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.opt(key)
        return value as? String ?: invalid("Invalid $key")
    }

    private fun optionalObject(obj: JSONObject, key: String): JSONObject? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.opt(key)
        return value as? JSONObject ?: invalid("Invalid $key")
    }

    private fun optionalPid(obj: JSONObject, key: String): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return integralPid(obj.opt(key))
    }

    private fun integralPid(value: Any?): Long {
        val number = value as? Number ?: invalid("Invalid PID")
        val decimal = number.toDouble()
        require(decimal.isFinite() && decimal >= 0.0 && decimal % 1.0 == 0.0) { "Invalid PID" }
        val pid = number.toLong()
        require(pid >= 0L && pid.toDouble() == decimal) { "Invalid PID" }
        return pid
    }

    private fun parsePidKey(key: String): Long {
        require(key.isNotEmpty() && key.all { it in '0'..'9' }) { "Invalid PID" }
        return key.toLongOrNull()?.takeIf { it >= 0L } ?: invalid("Invalid PID")
    }

    private fun parseIndexKey(key: String): Int {
        require(key.isNotEmpty() && key.all { it in '0'..'9' }) { "Invalid service index" }
        return key.toIntOrNull() ?: invalid("Invalid service index")
    }

    private fun parseJson(text: String): Any {
        require(text.isNotBlank()) { "Empty JSON" }
        try {
            val tokener = JSONTokener(text)
            val parsed = tokener.nextValue()
            require(tokener.nextClean() == '\u0000') { "Trailing JSON data" }
            return parsed
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid JSON", error)
        }
    }

    private fun validAuditName(name: String): Boolean =
        name.isNotBlank() && '|' !in name && name.none { it.isISOControl() }

    private fun validServiceName(name: String): Boolean =
        name.isNotBlank() && name.none { it.isISOControl() }

    /** Basename, optional `.exe`, then letters/digits only, so spaces and hyphens cannot evade matching. */
    private fun compactName(value: String): String {
        val base = value.trim().trim('"', '\'')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val withoutExe = if (base.endsWith(".exe", ignoreCase = true)) base.dropLast(4) else base
        val compact = StringBuilder(withoutExe.length)
        for (character in withoutExe) {
            if (character.isLetterOrDigit()) compact.append(character.lowercaseChar())
        }
        return compact.toString()
    }

    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}
