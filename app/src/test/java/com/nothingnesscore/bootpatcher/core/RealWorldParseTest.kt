package com.nothingnesscore.bootpatcher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers parsing of real magiskboot output and real kernel formats.
 *
 * The expected strings in these tests were captured from magiskboot unpacking
 * an actual stock boot image from a peridot (Poco F6, Android 14 GKI) device,
 * and from the ARM64 kernel image that boot image contains.
 */
class RealWorldParseTest {

    /** Verbatim magiskboot output from a real peridot stock boot.img. */
    private val realUnpackOutput = """
        Parsing boot image: [/data/local/tmp/stock_boot.img]
        HEADER_VER      [4]
        KERNEL_SZ       [37014016]
        RAMDISK_SZ      [0]
        PAGESIZE        [4096]
        CMDLINE         []
        KERNEL_FMT      [raw]
        VBMETA
        DTB             [0]
        PATCH_LEVEL     [2025-06]
        OS_VERSION      [14.0.0]
        VBMETA_VERITY   [1]
    """.trimIndent()

    @Test
    fun `parses real magiskboot v4 output`() {
        val m = parseUnpackOutput(realUnpackOutput)
        assertEquals("4", m["header_version"])
        assertEquals("37014016", m["kernel_size"])
        assertEquals("4096", m["page_size"])
        assertEquals("raw", m["format"])
        assertEquals("2025-06", m["os_patch_level"])
        assertEquals("14.0.0", m["os_version"])
    }

    @Test
    fun `header version is a usable int`() {
        val m = parseUnpackOutput(realUnpackOutput)
        assertEquals(4, m["header_version"]?.toIntOrNull())
    }

    @Test
    fun `empty values do not become bogus data`() {
        // CMDLINE [] is present and must not be mistaken for a real value.
        val m = parseUnpackOutput(realUnpackOutput)
        assertTrue(m.values.none { it.contains("[") })
    }

    @Test
    fun `unparseable output yields an empty map rather than throwing`() {
        assertTrue(parseUnpackOutput("").isEmpty())
        assertTrue(parseUnpackOutput("random noise").isEmpty())
    }

    @Test
    fun `pe coff arm64 kernel is recognised`() {
        // GKI kernels are PE/COFF, not ELF. Reading them as ELF returned null,
        // which blocked every patch on a real peridot.
        val head = ByteArray(512)
        head[0] = 'M'.code.toByte()
        head[1] = 'Z'.code.toByte()
        // e_lfanew = 0x40
        head[0x3c] = 0x40
        head[0x40] = 'P'.code.toByte()
        head[0x41] = 'E'.code.toByte()
        head[0x44] = 0x64.toByte()  // machine low  = 0xAA64
        head[0x45] = 0xAA.toByte()  // machine high = 0xAA64
        assertEquals("arm64", KernelInspector.archFrom(head, head.size))
    }

    @Test
    fun `pe coff x86_64 kernel is recognised`() {
        val head = ByteArray(512)
        head[0] = 'M'.code.toByte()
        head[1] = 'Z'.code.toByte()
        head[0x3c] = 0x40
        head[0x40] = 'P'.code.toByte()
        head[0x41] = 'E'.code.toByte()
        head[0x44] = 0x64.toByte()  // 0x8664 -> low byte 0x64
        head[0x45] = 0x86.toByte()
        assertEquals("x86_64", KernelInspector.archFrom(head, head.size))
    }

    @Test
    fun `pe without a valid signature is not trusted`() {
        val head = ByteArray(512)
        head[0] = 'M'.code.toByte()
        head[1] = 'Z'.code.toByte()
        head[0x3c] = 0x40
        head[0x40] = 'X'.code.toByte() // not "PE"
        assertNull(KernelInspector.archFrom(head, head.size))
    }

    @Test
    fun `pe offset outside the buffer is rejected`() {
        val head = ByteArray(512)
        head[0] = 'M'.code.toByte()
        head[1] = 'Z'.code.toByte()
        head[0x3c] = 0xFF.toByte()
        head[0x3d] = 0xFF.toByte()
        head[0x3e] = 0xFF.toByte()
        head[0x3f] = 0x7F.toByte() // huge e_lfanew
        assertNull(KernelInspector.archFrom(head, head.size))
    }

    @Test
    fun `elf still works after pe support was added`() {
        val head = ByteArray(64)
        head[0] = 0x7f.toByte()
        head[1] = 'E'.code.toByte()
        head[2] = 'L'.code.toByte()
        head[3] = 'F'.code.toByte()
        head[18] = 0xB7.toByte()
        head[19] = 0x00
        assertEquals("arm64", KernelInspector.archFrom(head, head.size))
    }

    @Test
    fun `unknown machine in pe is not guessed`() {
        val head = ByteArray(512)
        head[0] = 'M'.code.toByte()
        head[1] = 'Z'.code.toByte()
        head[0x3c] = 0x40
        head[0x40] = 'P'.code.toByte()
        head[0x41] = 'E'.code.toByte()
        head[0x44] = 0x99.toByte()
        head[0x45] = 0x00
        assertNull(KernelInspector.archFrom(head, head.size))
    }

    @Test
    fun `real v4 header carries no os version so it must not be invented`() {
        // The real peridot image has zero at every v0-v2 and v3 os_version
        // offset. Reporting Android 0 or SPL 2000-00 would be a fabrication,
        // so decodeOsVersion must return null and the UI must say unknown.
        assertNull(BootImageInfo.decodeOsVersion(0))
        assertNull(BootImageInfo.decodeOsPatchLevel(0))
    }

    @Test
    fun `real peridot kernel version parses to the expected triple`() {
        val banner = "Linux version 6.1.138-android14-11-g0c3d559bcd85-ab14529422 " +
            "(build-user@build-host) (Android (10087095, +pgo, +bolt, +lto, -mlgo, " +
            "based on r487747c) clang version 17.0.2) #1 SMP PREEMPT Thu Jan 1 00:00:00 UTC 1970"
        val m = Regex("""Linux version (\d+\.\d+(?:\.\d+)?)""").find(banner)
        assertEquals("6.1.138", m?.groupValues?.get(1))
        assertTrue(CompatibilityChecker.versionsMatch("6.1.138", "6.1.138"))
        // The distro suffix must not be mistaken for part of the version.
        assertEquals(false, CompatibilityChecker.versionsMatch("6.1.138-android14-11", "6.1.138"))
    }
}