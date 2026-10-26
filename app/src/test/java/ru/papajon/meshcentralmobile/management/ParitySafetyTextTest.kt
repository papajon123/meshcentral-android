package ru.papajon.meshcentralmobile.management

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Safety disclosures are part of the command's contract, not decorative copy. */
class ParitySafetyTextTest {
    private val resources = sequenceOf(File("src/main/res"), File("app/src/main/res"))
        .first { it.isDirectory }
    private fun strings(locale: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resources, "$locale/parity_fixes_strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }
    @Test fun commandWarningDisclosesSharedOutputAndLinuxLabelDisclosesInterval() {
        val en = strings("values")
        assertTrue(en.getValue("parity_fixes_run_warning").contains("All users with access to this device can see the command output"))
        assertTrue(en.getValue("parity_fixes_linux_cpu_average").contains("average since the previous sample"))
        assertTrue(en.getValue("parity_fixes_linux_cpu_average").contains("other sessions"))
        val ru = strings("values-ru")
        assertTrue(ru.getValue("parity_fixes_run_warning").contains("Вывод видят все пользователи с правами на устройство"))
        assertTrue(ru.getValue("parity_fixes_linux_cpu_average").contains("среднее с прошлого замера"))
    }
    @Test fun allTwentyThreeLocalesHaveTheSafetyDisclosuresAndErrorPlaceholder() {
        val dirs = resources.listFiles()!!.filter { File(it, "parity_fixes_strings.xml").isFile }
        assertEquals(23, dirs.size)
        val expected = strings("values").keys
        for (dir in dirs) {
            val values = strings(dir.name)
            assertEquals(dir.name, expected, values.keys)
            assertTrue(dir.name, values.values.all { it.isNotBlank() })
            assertTrue(dir.name, values.getValue("parity_fixes_server_rejected").contains("%1\$s"))
        }
    }
}
