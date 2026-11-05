package ru.papajon.meshcentralmobile.testing

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JsonContentTest {
    @Test fun objectOrderAndEquivalentNumberTypesAreNotDifferences() {
        assertTrue(JSONObject("""{"a":1,"b":[null,{"x":true}]}""")
            .hasSameJsonContent(JSONObject("""{"b":[null,{"x":true}],"a":1.0}""")))
    }
    @Test fun extraKeysArrayOrderAndValueTypesAreDifferences() {
        val expected = JSONObject("""{"a":[1,2]}""")
        for (wrong in listOf("""{"a":[2,1]}""", """{"a":[1,2],"extra":null}""", """{"a":["1",2]}""")) {
            assertFalse(expected.hasSameJsonContent(JSONObject(wrong)))
        }
    }
}
