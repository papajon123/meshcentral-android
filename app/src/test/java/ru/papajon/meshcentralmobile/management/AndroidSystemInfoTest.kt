package ru.papajon.meshcentralmobile.management

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidSystemInfoTest {
    @Test fun mobileHardwareAndNetif2UseOfficialAgentFields() {
        val snapshot = SystemInfoParser.parse(JSONObject("""{"hardware":{"mobile":{"model":"Test phone","androidrelease":"16","androidapi":36,"user":"ignored"},"identifiers":{"storage_devices":[{"Size":128000000000,"Caption":"Onboard Storage"}]}}}"""),
            JSONObject("""{"netif2":{"wlan0":[{"address":"192.0.2.2","family":"IPv4"},{"address":"2001:db8::2","family":"IPv6"}]}}"""))
        assertEquals("Android", snapshot.osName)
        assertEquals("16", snapshot.osVersion)
        assertEquals("Test phone", snapshot.model)
        assertEquals(128000000000L, snapshot.disks.single().capacityBytes)
        assertEquals(2, snapshot.networkAddresses.size)
        assertNull(snapshot.memoryTotalBytes)
        assertNull(snapshot.cpu)
    }
}
