package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.*
import org.junit.Test

class RememberDeviceProtocolTest {
    @Test fun buildsTheAuditedRequestWithoutResponseIdOrSecrets() {
        assertEquals("{\"action\":\"twoFactorCookie\"}", MeshControlMessages.twoFactorCookie().toString())
    }

    @Test fun parsesCookieWithoutExposingItInEventDiagnostics() {
        val event = MeshControlParser.parse("""{"action":"twoFactorCookie","cookie":"fixture-cookie"}""") as MeshControlEvent.TwoFactorCookie
        assertEquals("fixture-cookie", event.cookie)
        assertFalse(event.toString().contains("fixture-cookie"))
    }

    @Test fun rejectsMalformedCookieResponses() {
        listOf("null", "123", "\"\"", "\"x; y=z\"").forEach {
            val event = MeshControlParser.parse("""{"action":"twoFactorCookie","cookie":$it}""") as MeshControlEvent.TwoFactorCookie
            assertNull(event.cookie)
        }
    }

    @Test fun readsAdvertisedDurationAndDefaultAndDisabledValues() {
        fun days(extra: String) = (MeshControlParser.parse("""{"action":"close","msg":"tokenrequired"$extra}""")
            as MeshControlEvent.Close).twoFactorChallenge!!.twoFactorCookieDays
        assertEquals(30, days(""))
        assertEquals(7, days(",\"twoFactorCookieDays\":7"))
        assertEquals(0, days(",\"twoFactorCookieDays\":0"))
    }

    @Test fun onlyAnEnteredCodeAllowsCookieIssuance() {
        listOf(null, "", " ", "**email**", "**sms**", "**msg**").forEach { assertFalse(MeshAuth.isVerificationCode(it)) }
        assertTrue(MeshAuth.isVerificationCode("123456"))
        assertTrue(MeshAuth.isVerificationCode("recovery-code"))
    }
}
