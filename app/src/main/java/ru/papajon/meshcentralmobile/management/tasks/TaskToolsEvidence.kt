package ru.papajon.meshcentralmobile.management.tasks

/** Conservative continuity checks; syntax alone does not establish a complete agent snapshot. */
object TaskToolsEvidence {
    fun processSnapshot(before: List<AgentProcess>, after: List<AgentProcess>): Boolean {
        if (before.isEmpty() || after.isEmpty()) return false
        val identities = after.map { it.pid to it.name }.toSet()
        return before.count { (it.pid to it.name) in identities } * 2 > before.size
    }

    fun processEnded(target: AgentProcess, before: List<AgentProcess>, after: List<AgentProcess>, windows: Boolean = false): Boolean =
        processSnapshot(before, after) && after.none { it.pid == target.pid && (!windows || it.name == target.name) }

    fun serviceSnapshot(before: List<AgentService>, after: List<AgentService>): Boolean {
        if (before.isEmpty() || after.isEmpty()) return false
        val names = after.map { it.name }.toSet()
        return before.count { it.name in names } * 2 > before.size
    }

    /** The embedded systemd manager returns RUNNING or no state. An empty running-table can
     * also mean a failed systemctl/JSON probe, so require surviving running witnesses. */
    fun systemdStoppedNames(before: List<AgentService>, after: List<AgentService>): Set<String> {
        if (!serviceSnapshot(before, after)) return emptySet()
        val previous = before.associateBy { it.name }
        val witnesses = before.filter { it.serviceType == "systemd" && it.state.equals("RUNNING", true) }.map { it.name }.toSet()
        val stillRunning = after.filter { it.state.equals("RUNNING", true) }.map { it.name }.toSet()
        val survivingWitnesses = witnesses.count { it in stillRunning }
        return after.asSequence().filter { target ->
            val old = previous[target.name]
            val witnessCount = witnesses.size - if (target.name in witnesses) 1 else 0
            val survivorCount = survivingWitnesses - if (target.name in witnesses && target.name in stillRunning) 1 else 0
            target.serviceType == "systemd" && target.state == null &&
                (old?.running == true || old?.observedNotRunning == true) &&
                witnessCount > 0 && survivorCount * 2 > witnessCount
        }.map { it.name }.toSet()
    }
}
