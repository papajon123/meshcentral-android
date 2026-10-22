package ru.papajon.meshcentralmobile.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshGroup

class DeviceSearchTest {
    @Test fun emptyAndBlankQueriesReturnTheWholeList() {
        val devices = listOf(
            device("one", "Alpha"),
            device("two", "Beta"),
        )

        assertEquals(devices, DeviceSearch.filter(devices, ""))
        assertEquals(devices, DeviceSearch.filter(devices, "   "))
        assertFalse(DeviceSearch.hasQuery("\t\n "))
    }

    @Test fun tokensAreCombinedWithAndAcrossFields() {
        val devices = listOf(
            device("one", "Alpha", os = "Windows 11"),
            device("two", "Alpha", os = "Linux"),
            device("three", "Beta", os = "Windows 11"),
        )

        assertEquals(listOf("one"), DeviceSearch.filter(devices, "alpha windows").map { it.nodeId })
        assertEquals(listOf("one"), DeviceSearch.filter(devices, "WINDOWS alpha").map { it.nodeId })
        assertEquals(listOf("two"), DeviceSearch.filter(devices, "alpha linux").map { it.nodeId })
        assertTrue(DeviceSearch.filter(devices, "alpha macos").isEmpty())
    }

    @Test fun matchingIsCaseInsensitiveAndUnicodeNormalised() {
        val cyrillic = device("ru", "ПРОФЕССИОНАЛЬНЫЙ", os = "Windows 11")
        val fullWidth = device("wide", "Wide", os = "Ｗｉｎｄｏｗｓ　１１")

        assertEquals(listOf("ru"), DeviceSearch.filter(listOf(cyrillic), "профессиональный").map { it.nodeId })
        assertEquals(listOf("wide"), DeviceSearch.filter(listOf(fullWidth), "windows 11").map { it.nodeId })
        assertEquals(listOf("wide"), DeviceSearch.filter(listOf(fullWidth), "ＷＩＮＤＯＷＳ").map { it.nodeId })
    }

    @Test fun groupNameAndMeshNameAreSearchable() {
        val devices = listOf(
            device("office", "Desk", meshId = "mesh-office", meshName = "Office mesh"),
            device("home", "Laptop", meshId = "mesh-home", meshName = "Home mesh"),
        )
        val groups = listOf(
            MeshGroup(id = "mesh-office", name = "Рабочие компьютеры"),
            MeshGroup(id = "mesh-home", name = "Домашние компьютеры"),
        )

        assertEquals(listOf("office"), DeviceSearch.filter(devices, "рабочие", groups).map { it.nodeId })
        assertEquals(listOf("home"), DeviceSearch.filter(devices, "home mesh", groups).map { it.nodeId })
        assertTrue(DeviceSearch.filter(devices, "office", groups).any { it.nodeId == "office" })
    }

    @Test fun hostAddressAndOperatingSystemAreSearchable() {
        val byHost = device("host", "Desk", host = "desk-01.example")
        val byAddress = device("ip", "Desk", ipAddress = "192.0.2.15")
        val byOs = device("os", "Desk", os = "Ubuntu 24.04 LTS")

        assertEquals(listOf("host"), DeviceSearch.filter(listOf(byHost), "desk-01").map { it.nodeId })
        assertEquals(listOf("ip"), DeviceSearch.filter(listOf(byAddress), "192.0.2").map { it.nodeId })
        assertEquals(listOf("os"), DeviceSearch.filter(listOf(byOs), "ubuntu").map { it.nodeId })
    }

    @Test fun eachTokenMayMatchAnyFieldButNotAcrossFields() {
        val machine = device(
            "one",
            "Alpha",
            ipAddress = "10.0.0.5",
            os = "Windows 11",
        )

        assertEquals(listOf("one"), DeviceSearch.filter(listOf(machine), "alpha 10.0 windows").map { it.nodeId })
        assertTrue(DeviceSearch.filter(listOf(machine), "alpha10.0").isEmpty())
    }

    @Test fun rootLocaleLowerCasingIsUsed() {
        val machine = device("one", "ISTANBUL")

        assertEquals(listOf("one"), DeviceSearch.filter(listOf(machine), "istanbul").map { it.nodeId })
        assertEquals("i", DeviceSearch.normalize("I"))
    }

    private fun device(
        nodeId: String,
        name: String,
        meshId: String = "mesh",
        meshName: String = "",
        host: String? = null,
        ipAddress: String = "",
        os: String = "",
    ) = MeshDevice(
        nodeId = nodeId,
        meshId = meshId,
        name = name,
        meshName = meshName,
        host = host,
        ipAddress = ipAddress,
        osDescription = os,
    )
}
