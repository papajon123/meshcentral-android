package ru.papajon.meshcentralmobile.backup

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupJsonSyntaxTest {
    @Test fun acceptsStandardLiteralsEscapesNumbersAndNestedValues() {
        BackupJsonSyntax.validate(""" {"text":"quote\" slash\\ unicode\u263a", "array":[null,true,false,-12.5e+2,0], "object":{}} """)
        BackupJsonSyntax.validate("{\"command\":\"" + "[".repeat(500) + "\"}")
    }
    @Test fun rejectsCommentsUnquotedOrSingleQuotedKeysAndTrailingSeparators() {
        listOf("{key:1}", "{'key':1}", "{\"key\":/*\"*/1}", "{\"key\":# comment\n1}",
            "{\"key\":1,}", "{\"key\":[1,]}", "{\"key\":01}", "{\"key\":truex}", "{\"key\":NaN}",
            "{\"key\":\"\\x20\"}", "{\"key\":\"line\nline\"}", "{} {}", "[]").forEach {
            assertThrows(IllegalArgumentException::class.java) { BackupJsonSyntax.validate(it) }
        }
    }
    @Test fun rejectsDuplicateMembersIncludingEscapedEquivalentKeys() {
        listOf("{\"key\":1,\"key\":2}", "{\"key\":1,\"\\u006bey\":2}").forEach {
            assertThrows(IllegalArgumentException::class.java) { BackupJsonSyntax.validate(it) }
        }
    }
    @Test fun enforcesDepthBeforePlatformParserRecursion() {
        val deeplyNested = "{\"key\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"
        assertThrows(IllegalArgumentException::class.java) { BackupJsonSyntax.validate(deeplyNested) }
    }
}
