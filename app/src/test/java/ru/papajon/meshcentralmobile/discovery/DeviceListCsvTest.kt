package ru.papajon.meshcentralmobile.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshGroup

class DeviceListCsvTest {
    @Test fun writesHeaderAndOneRowPerComputer() {
        val csv = DeviceListCsv.build(
            listOf(
                MeshDevice("node//a", "mesh//o", "Accounting", osDescription = "Windows 11", ipAddress = "10.0.0.5", conn = 1, tags = listOf("Finance", "VIP")),
                MeshDevice("node//b", "mesh//o", "Spare", meshName = "Fallback"),
            ),
            listOf(MeshGroup("mesh//o", "Office")),
        )
        val lines = csv.split("\r\n")
        assertEquals("Name,Group,Operating system,Address,Status,Tags", lines[0])
        assertEquals("Accounting,Office,Windows 11,10.0.0.5,online,Finance; VIP", lines[1])
        assertEquals("Spare,Office,,,offline,", lines[2])
        assertEquals("", lines[3])
        assertFalse(csv.contains("node//"))
    }

    @Test fun quotesSeparatorsAndQuotes() {
        assertEquals("\"Lab, room 2\"", DeviceListCsv.cell("Lab, room 2"))
        assertEquals("\"Say \"\"hi\"\"\"", DeviceListCsv.cell("Say \"hi\""))
        assertEquals("\"two\nlines\"", DeviceListCsv.cell("two\nlines"))
    }

    @Test fun neutralisesSpreadsheetFormulas() {
        assertEquals("'=HYPERLINK(1)", DeviceListCsv.cell("=HYPERLINK(1)"))
        assertEquals("'+cmd", DeviceListCsv.cell("+cmd"))
        assertEquals("'-2", DeviceListCsv.cell("-2"))
        assertEquals("'@SUM(A1)", DeviceListCsv.cell("@SUM(A1)"))
        assertEquals("plain", DeviceListCsv.cell("plain"))
    }
}
