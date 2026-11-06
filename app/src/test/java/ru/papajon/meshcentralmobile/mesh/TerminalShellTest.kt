package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalShellTest {
    private fun device(os: String, agentId: Int? = null) =
        MeshDevice(nodeId = "node//n", meshId = "mesh//m", name = "pc", osDescription = os, agentId = agentId)

    @Test fun protocolNumbersMatchMeshCentralAgent() {
        assertEquals(listOf(1, 6, 8, 9), TerminalShell.entries.map { it.usage })
    }

    @Test fun windowsOffersPowerShellAndUserVariants() {
        assertEquals(TerminalShell.entries, TerminalShell.available(device("Microsoft Windows 11 Pro")))
    }

    @Test fun linuxOffersOnlyShellsTheAgentSupports() {
        assertEquals(listOf(TerminalShell.Default, TerminalShell.User),
            TerminalShell.available(device("Debian GNU/Linux 12 (bookworm)")))
    }

    @Test fun androidAndUnknownAgentsKeepTheDefaultShell() {
        assertEquals(listOf(TerminalShell.Default), TerminalShell.available(device("Android 15", agentId = 14)))
        assertEquals(listOf(TerminalShell.Default), TerminalShell.available(device("")))
    }

    @Test fun unknownStoredNameFallsBackToDefault() {
        assertEquals(TerminalShell.Default, TerminalShell.fromName(null))
        assertEquals(TerminalShell.Default, TerminalShell.fromName("Bash"))
        assertEquals(TerminalShell.PowerShell, TerminalShell.fromName("PowerShell"))
    }

    @Test fun everyTerminalShellRequestsTerminalConsent() {
        TerminalShell.entries.forEach { shell ->
            val options = AgentConsent.options(shell.usage, requested = true)
            assertEquals("consent for ${shell.name}", 0x10, JSONObject(options.toString()).getInt("consent"))
        }
    }
}
