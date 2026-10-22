package ru.papajon.meshcentralmobile.management

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemInfoParserTest {
    @Test
    fun windowsSystemInfoUsesWhitelistedMeshCentralFields() {
        val systemInfo = JSONObject(
            """
            {
              "node": {
                "name": "server",
                "rname": "Night PC",
                "osdesc": "Windows 11 Pro",
                "ip": "10.0.0.5",
                "users": [{"name": "SECRET-USER"}],
                "password": "SECRET-PASSWORD"
              },
              "hardware": {
                "windows": {
                  "osinfo": {
                    "Caption": "Microsoft Windows 11 Pro",
                    "Version": "10.0.22631",
                    "BuildNumber": "22631",
                    "OSArchitecture": "64-bit",
                    "TotalPhysicalMemory": 34359738368
                  },
                  "cpu": [{"Name": "Intel Core i7 from WMI"}],
                  "memory": [
                    {"Capacity": 17179869184, "Speed": 3200, "PartNumber": "SECRET-PART"},
                    {"Capacity": "8589934592", "Speed": "2666"}
                  ],
                  "volumes": {
                    "C:": {
                      "identifier": "SECRET-ID",
                      "recoveryPassword": "SECRET-RECOVERY"
                    }
                  },
                  "bitlocker": {
                    "SECRET-ID": {"rp": "SECRET-RECOVERY"}
                  }
                },
                "identifiers": {
                  "cpu_name": "Intel Core i7",
                  "product_name": "ThinkPad X1",
                  "bios_serial": "SECRET-BIOS",
                  "board_serial": "SECRET-BOARD",
                  "chassis_serial": "SECRET-CHASSIS",
                  "product_uuid": "SECRET-UUID",
                  "storage_devices": [
                    {"Caption": "Disk 0", "Model": "NVMe", "Size": 512110190592},
                    {"Caption": "Disk 1", "Model": "USB", "Size": -1}
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val networkInfo = JSONObject(
            """
            {
              "netif2": {
                "eth0": [
                  {"family": "IPv4", "address": "192.168.1.20", "netmask": "255.255.255.0"},
                  {"family": "IPv6", "address": "fe80::20"}
                ]
              },
              "netif": [
                {"name": "ignored", "v4addr": "10.0.0.5"}
              ]
            }
            """.trimIndent(),
        )

        val snapshot = SystemInfoParser.parse(systemInfo, networkInfo)

        assertEquals("Night PC", snapshot.deviceName)
        assertEquals("Microsoft Windows 11 Pro", snapshot.osName)
        assertEquals("10.0.22631", snapshot.osVersion)
        assertEquals("64-bit", snapshot.architecture)
        assertNull(snapshot.kernelRelease)
        assertEquals("Intel Core i7", snapshot.cpu)
        assertEquals("ThinkPad X1", snapshot.model)
        assertEquals(2, snapshot.memoryModules.size)
        assertEquals(17_179_869_184L, snapshot.memoryModules[0].capacityBytes)
        assertEquals("3200", snapshot.memoryModules[0].speed)
        assertEquals(8_589_934_592L, snapshot.memoryModules[1].capacityBytes)
        assertEquals("2666", snapshot.memoryModules[1].speed)
        assertEquals(25_769_803_776L, snapshot.memoryTotalBytes)
        assertEquals(2, snapshot.disks.size)
        assertEquals("Disk 0", snapshot.disks[0].name)
        assertEquals(512_110_190_592L, snapshot.disks[0].capacityBytes)
        assertNull(snapshot.disks[1].capacityBytes)
        assertEquals(2, snapshot.networkAddresses.size)
        assertTrue(
            snapshot.networkAddresses.any {
                it.interfaceName == "eth0" && it.address == "192.168.1.20" && it.family == "IPv4"
            },
        )
        assertTrue(
            snapshot.networkAddresses.any {
                it.interfaceName == "eth0" && it.address == "fe80::20" && it.family == "IPv6"
            },
        )
        assertFalse(snapshot.toString().contains("SECRET"))
        assertFalse(snapshot.toString().contains("34359738368"))
    }

    @Test
    fun windowsCpuFallsBackToTheWindowsCpuArray() {
        val snapshot = SystemInfoParser.parse(
            JSONObject(
                """
                {
                  "hardware": {
                    "windows": {"cpu": [{"Name": "AMD Ryzen 7"}]},
                    "identifiers": {"board_name": "B650"}
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("AMD Ryzen 7", snapshot.cpu)
        assertEquals("B650", snapshot.model)
    }

    @Test
    fun linuxSystemInfoUsesLinuxKernelAndMemoryShape() {
        val systemInfo = JSONObject(
            """
            {
              "node": {"rname": "nas", "osdesc": "Ubuntu 24.04"},
              "hardware": {
                "linux": {
                  "arch": "x86_64",
                  "kernel_release": "6.8.0-45-generic",
                  "board_name": "NAS Board",
                  "memory": {
                    "Memory_Device": [
                      {"Size": 8, "Speed": "3200 MT/s"},
                      {"Size": "-2"}
                    ]
                  },
                  "product_serial": "SECRET-PRODUCT"
                },
                "identifiers": {
                  "cpu_name": "AMD Ryzen 5",
                  "storage_devices": [
                    {"Caption": "sda", "Model": "WD Red", "Size": 4000787030016}
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val snapshot = SystemInfoParser.parse(systemInfo)

        assertEquals("nas", snapshot.deviceName)
        assertEquals("Ubuntu 24.04", snapshot.osName)
        assertNull(snapshot.osVersion)
        assertEquals("x86_64", snapshot.architecture)
        assertEquals("6.8.0-45-generic", snapshot.kernelRelease)
        assertEquals("AMD Ryzen 5", snapshot.cpu)
        assertEquals("NAS Board", snapshot.model)
        assertEquals(1, snapshot.memoryModules.size)
        assertEquals(8L * 1024 * 1024 * 1024, snapshot.memoryModules[0].capacityBytes)
        assertEquals("3200 MT/s", snapshot.memoryModules[0].speed)
        assertEquals(8L * 1024 * 1024 * 1024, snapshot.memoryTotalBytes)
        assertEquals(1, snapshot.disks.size)
        assertEquals("sda", snapshot.disks[0].name)
        assertEquals(4_000_787_030_016L, snapshot.disks[0].capacityBytes)
        assertTrue(snapshot.networkAddresses.isEmpty())
        assertFalse(snapshot.toString().contains("SECRET"))
    }

    @Test
    fun missingInformationStaysNullAndEmpty() {
        val empty = SystemInfoParser.parse(JSONObject())

        assertNull(empty.deviceName)
        assertNull(empty.osName)
        assertNull(empty.osVersion)
        assertNull(empty.architecture)
        assertNull(empty.kernelRelease)
        assertNull(empty.cpu)
        assertNull(empty.model)
        assertTrue(empty.memoryModules.isEmpty())
        assertTrue(empty.disks.isEmpty())
        assertTrue(empty.networkAddresses.isEmpty())
        assertNull(empty.memoryTotalBytes)
    }

    @Test
    fun corruptValuesDoNotThrowAndDoNotBecomeZero() {
        val corrupt = JSONObject(
            """
            {
              "node": "not-an-object",
              "hardware": {
                "windows": "not-an-object",
                "linux": [],
                "identifiers": {
                  "cpu_name": 123,
                  "product_name": [],
                  "storage_devices": "not-an-array"
                }
              },
              "netif2": [],
              "netif": 42
            }
            """.trimIndent(),
        )

        val snapshot = SystemInfoParser.parse(corrupt, corrupt)

        assertNull(snapshot.deviceName)
        assertNull(snapshot.cpu)
        assertNull(snapshot.model)
        assertTrue(snapshot.memoryModules.isEmpty())
        assertTrue(snapshot.disks.isEmpty())
        assertTrue(snapshot.networkAddresses.isEmpty())
        assertNull(snapshot.memoryTotalBytes)
    }

    @Test
    fun negativeAndZeroCapacitiesAreUnknownNotZero() {
        val snapshot = SystemInfoParser.parse(
            JSONObject(
                """
                {
                  "hardware": {
                    "windows": {
                      "memory": [
                        {"Capacity": -1, "Speed": "3200"},
                        {"Capacity": 0}
                      ]
                    },
                    "identifiers": {
                      "storage_devices": [
                        {"Caption": "Disk 0", "Size": "-5"},
                        {"Caption": "Disk 1", "Size": 0}
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1, snapshot.memoryModules.size)
        assertNull(snapshot.memoryModules[0].capacityBytes)
        assertEquals("3200", snapshot.memoryModules[0].speed)
        assertNull(snapshot.memoryTotalBytes)
        assertEquals(2, snapshot.disks.size)
        assertNull(snapshot.disks[0].capacityBytes)
        assertNull(snapshot.disks[1].capacityBytes)
    }

    @Test
    fun networkFallsBackToTheFlatNetifArray() {
        val networkInfo = JSONObject(
            """
            {
              "netif": [
                {"name": "Wi-Fi", "v4addr": "10.0.0.9"},
                {"name": "Wi-Fi", "v4addr": "0.0.0.0"}
              ]
            }
            """.trimIndent(),
        )

        val snapshot = SystemInfoParser.parse(JSONObject(), networkInfo)

        assertEquals(1, snapshot.networkAddresses.size)
        assertEquals("Wi-Fi", snapshot.networkAddresses[0].interfaceName)
        assertEquals("10.0.0.9", snapshot.networkAddresses[0].address)
        assertEquals("IPv4", snapshot.networkAddresses[0].family)
    }
}
