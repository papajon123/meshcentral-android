package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.localization.UiText

/**
 * `x-meshauth` header tests.
 *
 * MeshCentral 1.2.5 `webserver.js:9165` splits the header on `,`, base64 decodes every part and
 * accepts two or three parts; a three part header answers a two-factor challenge.
 */
class MeshAuthTest {
    @Test
    fun buildsTwoPartHeaderForUserAndPassword() {
        assertEquals("dGVzdC11c2Vy,cHc=", MeshAuth.headerValue("test-user", "pw"))
    }

    @Test
    fun buildsThreePartHeaderWhenATwoFactorTokenIsPresent() {
        assertEquals("dGVzdC11c2Vy,cHc=,MTIzNDU2", MeshAuth.headerValue("test-user", "pw", "123456"))
    }

    @Test
    fun emptyTokenIsTreatedAsMissing() {
        assertEquals("dGVzdC11c2Vy,cHc=", MeshAuth.headerValue("test-user", "pw", ""))
    }

    @Test
    fun tokenRequestMarkersMatchServerExpectations() {
        assertEquals("**email**", TwoFactorChannel.Email.requestToken)
        assertEquals("**sms**", TwoFactorChannel.Sms.requestToken)
        assertEquals("**msg**", TwoFactorChannel.Push.requestToken)
    }

    @Test
    fun classifiesServerCloseReasons() {
        assertEquals(
            MeshAuthFailure.TwoFactorRequired,
            MeshAuthDiagnostics.classify("noauth", "tokenrequired"),
        )
        assertEquals(
            MeshAuthFailure.BadCredentials,
            MeshAuthDiagnostics.classify("noauth", "noauth-2a"),
        )
        assertEquals(
            MeshAuthFailure.MalformedHeader,
            MeshAuthDiagnostics.classify("noauth", "noauth-2c"),
        )
        assertEquals(MeshAuthFailure.ToolsDisabled, MeshAuthDiagnostics.classify("notools", "notools"))
        assertEquals(MeshAuthFailure.Banned, MeshAuthDiagnostics.classify("banned", null))
        assertEquals(MeshAuthFailure.AccessKeyRequired, MeshAuthDiagnostics.classify("noauth", "nokey"))
        assertEquals(
            MeshAuthFailure.EmailValidationRequired,
            MeshAuthDiagnostics.classify("emailvalidation", null),
        )
    }

    @Test
    fun everyFailureHasAHumanReadableExplanation() {
        MeshAuthFailure.entries.forEach { failure ->
            val text = MeshAuthDiagnostics.describe(failure)
            assertTrue(failure.name, text is UiText.Resource)
            assertTrue(failure.name, (text as UiText.Resource).id != 0)
        }
    }
}
