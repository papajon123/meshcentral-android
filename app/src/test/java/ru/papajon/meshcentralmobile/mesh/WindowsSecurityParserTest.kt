package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WindowsSecurityParserTest {
    @Test fun readsEveryCenterFieldWithoutConflatingUnknownAndBad() {
        for (field in listOf("antiVirus", "autoUpdate", "firewall")) {
            for ((wire, expected) in listOf("OK" to SecurityStatus.Ok, "PROBLEM" to SecurityStatus.Problem,
                "NA" to SecurityStatus.Unknown, "future" to SecurityStatus.Unknown, "" to SecurityStatus.Unknown,
                true to SecurityStatus.Unknown, 0 to SecurityStatus.Unknown, JSONObject.NULL to SecurityStatus.Unknown)) {
                val result = WindowsSecurityParser.parse(JSONObject().put("wsc", JSONObject().put(field, wire))).center
                assertEquals("$field=$wire", expected, when (field) {
                    "antiVirus" -> result.antiVirus; "autoUpdate" -> result.autoUpdate; else -> result.firewall
                })
            }
        }
    }

    @Test fun missingAndMalformedFieldsStayUnknown() {
        for (raw in listOf("{}", """{"wsc":null,"av":null}""", """{"wsc":5,"av":{}}""")) {
            val security = WindowsSecurityParser.parse(JSONObject(raw))
            assertEquals(WindowsSecurityCenter(), security.center)
            assertTrue(security.products.isEmpty())
            assertFalse(security.needsAttention)
        }
    }

    @Test fun parsesSeveralProductsWithStrictBooleanValues() {
        val result = WindowsSecurityParser.parse(JSONObject("""{"av":[
            {"product":"Defender","enabled":true,"updated":true},
            {"product":"Example AV","enabled":false,"updated":false},
            {"product":"Unknown AV","enabled":"false","updated":0},null,5,{}]}"""))
        assertEquals(4, result.products.size)
        assertEquals(AntivirusProduct("Defender", true, true), result.products[0])
        assertEquals(AntivirusProduct("Example AV", false, false), result.products[1])
        assertEquals(AntivirusProduct("Unknown AV", null, null), result.products[2])
        assertEquals(AntivirusProduct(null, null, null), result.products[3])
    }

    @Test fun nodesAndChangenodeUseTheSameParser() {
        val node = """{"_id":"node//example","wsc":{"firewall":"PROBLEM"},"av":[{"product":"AV","updated":false}]}"""
        val nodes = MeshControlParser.parse("""{"action":"nodes","nodes":{"mesh//example":[$node]}}""") as MeshControlEvent.Nodes
        val event = MeshControlParser.parse("""{"action":"event","event":{"action":"changenode","node":$node}}""") as MeshControlEvent.NodeUpdated
        assertEquals(nodes.devices.single().windowsSecurity, (event.event as MeshNodeEvent.NodeChanged).device.windowsSecurity)
        assertTrue(nodes.devices.single().windowsSecurity.needsAttention)
    }

    @Test fun partialEventsPreserveAbsentFieldsAndExplicitNullClearsThem() {
        val original = WindowsSecurityParser.parse(JSONObject("""{"wsc":{"firewall":"PROBLEM"},"av":[{"enabled":false}]}"""))
        assertEquals(original, original.updatedBy(WindowsSecurityParser.parse(JSONObject("{}"))))
        val cleared = original.updatedBy(WindowsSecurityParser.parse(JSONObject("""{"wsc":null,"av":[]} """)))
        assertFalse(cleared.needsAttention)
        assertEquals(SecurityStatus.Unknown, cleared.center.firewall)
    }

    @Test fun avUpdatesDoNotEraseCenterAndCenterUpdatesDoNotEraseProducts() {
        val original = WindowsSecurityParser.parse(JSONObject("""{"wsc":{"firewall":"PROBLEM"},"av":[{"enabled":false}]}"""))
        assertEquals(original.center, original.updatedBy(WindowsSecurityParser.parse(JSONObject("""{"av":[]} """))).center)
        assertEquals(original.products, original.updatedBy(WindowsSecurityParser.parse(JSONObject("""{"wsc":{}}"""))).products)
    }
}
