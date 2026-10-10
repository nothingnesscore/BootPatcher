package com.nothingnesscore.bootpatcher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootImageParserTest {

    @Test
    fun `v3 header parses packed os version and patch level`() {
        val info = parseBytes(BootImageFixtures.v3Header(
            headerVersion = 3, major = 14, minor = 1, patch = 138, year = 2025, month = 6,
        ))
        assertEquals(3, info.headerVersion)
        assertEquals("2025-06", info.patchLevelString)
        assertEquals(14, info.androidMajor)
        // os_version A.B.C is the OS version, NOT the kernel version. The
        // kernel version (6.1.138) only ever comes from the kernel banner.
        assertEquals(14, BootImageInfo.decodeOsVersion(info.osVersionRaw)!!.major)
    }

    @Test
    fun `v3 kernel and ramdisk sizes come from the v3 layout`() {
        // v3 puts ramdisk_size at 12, where v0-v2 holds kernel_addr. Reading
        // the wrong layout yields 0x40008000 here.
        val info = parseBytes(BootImageFixtures.v3Header(
            kernelSize = 0x2000000, ramdiskSize = 0x01234567,
        ))
        assertEquals(0x2000000L, info.kernelSize)
        assertEquals(0x01234567L, info.ramdiskSize)
    }

    @Test
    fun `v3 header occupies one 4k page`() {
        val info = parseBytes(BootImageFixtures.v3Header(headerSize = 1644))
        assertEquals(4096L, info.kernelOffset)
    }

    @Test
    fun `v2 header parses packed os version and patch level`() {
        val info = parseBytes(BootImageFixtures.v2Header(
            headerVersion = 2, major = 14, minor = 1, patch = 138, year = 2025, month = 6,
        ))
        assertEquals(2, info.headerVersion)
        assertEquals("2025-06", info.patchLevelString)
        assertEquals(14, info.androidMajor)
    }

    @Test
    fun `v2 uses the page size from the header`() {
        val info = parseBytes(BootImageFixtures.v2Header(pageSize = 2048))
        assertEquals(2048, info.pageSize)
        assertEquals(2048L, info.kernelOffset)
    }

    @Test
    fun `v0 kernel and ramdisk sizes come from the v0-v2 layout`() {
        val info = parseBytes(BootImageFixtures.v2Header(
            headerVersion = 0, kernelSize = 0x2000000, ramdiskSize = 0x01234567,
        ))
        assertEquals(0, info.headerVersion)
        assertEquals(0x2000000L, info.kernelSize)
        assertEquals(0x01234567L, info.ramdiskSize)
    }

    @Test
    fun `v4 is recognised like v3`() {
        val info = parseBytes(BootImageFixtures.v3Header(headerVersion = 4))
        assertEquals(4, info.headerVersion)
        assertEquals("2025-06", info.patchLevelString)
    }

    @Test
    fun `os version decode rejects an all-zero word`() {
        assertNull(BootImageInfo.decodeOsVersion(0))
        assertNull(BootImageInfo.decodeOsPatchLevel(0))
    }

    @Test
    fun `patch level decode rejects an impossible month`() {
        val bad = BootImageFixtures.packOsVersionPatchLevel(14, 1, 138, 2025, 13)
        assertNull(BootImageInfo.decodeOsPatchLevel(bad))
    }

    @Test
    fun `os version decode splits seven bit fields`() {
        val v = BootImageInfo.decodeOsVersion(
            BootImageFixtures.packOsVersionPatchLevel(15, 2, 75, 2026, 12)
        )
        assertEquals(OsVersion(15, 2, 75), v)
        assertEquals("15.2.75", v.toString())
    }

    @Test
    fun `os version is reported independently of the kernel banner`() {
        val (info, k) = inspectBytes(BootImageFixtures.v3WithKernel(
            "Linux version 6.1.138-android14-11 (b@h) #1 SMP",
        ))
        assertEquals("6.1.138", k.version)
        // Header says Android 14 / SPL 2025-06; the banner says 6.1.138.
        // Conflating these two is the bug this test guards against.
        assertEquals(14, info.androidMajor)
        assertEquals("2025-06", info.patchLevelString)
    }

    @Test
    fun `zero patch sublevel is omitted from the version string`() {
        val v = BootImageInfo.decodeOsVersion(
            BootImageFixtures.packOsVersionPatchLevel(13, 0, 0, 2025, 6)
        )
        assertEquals("13.0", v.toString())
    }

    @Test
    fun `non boot image is rejected with a helpful message`() {
        val err = assertFailsWith<BootImageParseException> {
            parseBytes(BootImageFixtures.garbage())
        }
        assertTrue(err.message!!.contains("Not an Android boot image"))
    }

    @Test
    fun `truncated file is rejected`() {
        assertFailsWith<BootImageParseException> { parseBytes(ByteArray(16)) }
    }

    @Test
    fun `kernel banner is read past the header page`() {
        val (_, k) = inspectBytes(BootImageFixtures.v3WithKernel(
            "Linux version 6.1.138-android14-11-g1234abcd (build-user@host) #1 SMP PREEMPT"
        ))
        assertEquals("6.1.138", k.version)
        assertEquals("arm64", k.arch)
    }

    @Test
    fun `kernel banner is read past the header page on v2`() {
        val (_, k) = inspectBytes(BootImageFixtures.v2WithKernel(
            "Linux version 6.1.138-android14-11 (build@host) #1 SMP"
        ))
        assertEquals("6.1.138", k.version)
    }

    @Test
    fun `architecture comes from the elf header not the banner`() {
        // A real banner never contains the word "aarch64"; the ELF machine
        // field is the only trustworthy source.
        val (_, k) = inspectBytes(BootImageFixtures.v3WithKernel(
            "Linux version 6.1.138-android14-11 (b@h) #1 SMP PREEMPT",
            machine = 0xb7,
        ))
        assertEquals("arm64", k.arch)
    }

    @Test
    fun `x86_64 kernel is detected`() {
        val (_, k) = inspectBytes(BootImageFixtures.v3WithKernel(
            "Linux version 6.1.138 (b@h) #1 SMP", machine = 0x3e,
        ))
        assertEquals("x86_64", k.arch)
    }

    @Test
    fun `non elf kernel yields a null architecture`() {
        // Better to stop and ask than to guess arm64 and flash a mismatched
        // kernel to an unknown architecture.
        val (_, k) = inspectBytes(BootImageFixtures.withKernel(
            BootImageFixtures.v3Header(), 4096, "no elf header here", machine = 0,
        ).let { bytes ->
            // Strip the ELF magic so the kernel page is not a valid ELF object.
            val i = 4096
            bytes.copyOf().also { it[i] = 0x00 }
        })
        assertNull(k.arch)
    }

    @Test
    fun `absent banner yields nulls instead of a crash`() {
        val (_, k) = inspectBytes(BootImageFixtures.v3WithKernel("not a kernel here"))
        assertNull(k.banner)
        assertNull(k.version)
        // The ELF header is still valid, so arch survives a missing banner.
        assertEquals("arm64", k.arch)
    }
}