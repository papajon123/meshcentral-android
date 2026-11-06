package ru.papajon.meshcentralmobile.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshGroup

class DeviceFacetsTest {
    private val windows = MeshDevice("node//w", "mesh//office", "Accounting", osDescription = "Microsoft Windows 11 Pro", tags = listOf("Finance", "VIP"))
    private val linux = MeshDevice("node//l", "mesh//lab", "Build", osDescription = "Debian GNU/Linux 12", tags = listOf("ci"))
    private val mac = MeshDevice("node//m", "mesh//office", "Design", osDescription = "macOS 15 Sequoia")
    private val phone = MeshDevice("node//p", "mesh//lab", "Pixel", osDescription = "Android 15", agentId = 14, tags = listOf("finance"))
    private val all = listOf(windows, linux, mac, phone)

    @Test fun osFamiliesAreDetected() {
        assertEquals(listOf(OsFamily.Windows, OsFamily.Linux, OsFamily.MacOs, OsFamily.Android), all.map(OsFamily::of))
        assertEquals(OsFamily.Other, OsFamily.of(MeshDevice("node//x", "mesh//x", "x")))
    }

    @Test fun inactiveFacetsKeepEveryComputer() {
        assertFalse(DeviceFacets().active)
        assertEquals(all, DeviceFacets().filter(all))
    }

    @Test fun facetsCombineWithAnd() {
        assertEquals(listOf(windows, mac), DeviceFacets(groupId = "mesh//office").filter(all))
        assertEquals(listOf(windows), DeviceFacets(groupId = "mesh//office", os = OsFamily.Windows).filter(all))
        assertEquals(emptyList<MeshDevice>(), DeviceFacets(groupId = "mesh//lab", os = OsFamily.Windows).filter(all))
    }

    @Test fun tagsMatchCaseInsensitively() {
        assertEquals(listOf(windows, phone), DeviceFacets(tag = "FINANCE").filter(all))
        assertTrue(DeviceFacets(tag = "vip").matches(windows))
    }

    @Test fun optionsListOnlyValuesPresentInTheList() {
        val options = DeviceFacets.options(all, listOf(MeshGroup("mesh//office", "Office"), MeshGroup("mesh//lab", "Lab")))
        assertEquals(listOf("mesh//lab" to "Lab", "mesh//office" to "Office"), options.groups)
        assertEquals(listOf(OsFamily.Windows, OsFamily.Linux, OsFamily.MacOs, OsFamily.Android), options.systems)
        assertEquals(listOf("ci", "Finance", "VIP"), options.tags)
        assertFalse(options.isEmpty)
    }

    @Test fun singleGroupSingleOsWithoutTagsOffersNothing() {
        val options = DeviceFacets.options(listOf(linux.copy(tags = emptyList())), emptyList())
        assertTrue(options.isEmpty)
    }

    @Test fun searchAlsoMatchesTags() {
        assertTrue(DeviceSearch.matches(windows, "vip"))
        assertFalse(DeviceSearch.matches(linux, "vip"))
    }
}
