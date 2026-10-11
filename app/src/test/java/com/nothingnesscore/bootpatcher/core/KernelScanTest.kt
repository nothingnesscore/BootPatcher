package com.nothingnesscore.bootpatcher.core

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the standalone kernel scanner, which is what actually finds the
 * version banner on a real GKI image once magiskboot has decompressed it.
 */
class KernelScanTest {

    private fun elfHeader(machine: Int): ByteArray = ByteArray(64).also { h ->
        h[0] = 0x7f; h[1] = 'E'.code.toByte(); h[2] = 'L'.code.toByte(); h[3] = 'F'.code.toByte()
        h[18] = (machine and 0xff).toByte()
        h[19] = ((machine shr 8) and 0xff).toByte()
    }

    private fun writeTemp(name: String, build: (java.io.OutputStream) -> Unit): File {
        val f = File.createTempFile("kernelfix", ".img")
        f.deleteOnExit()
        f.outputStream().use { build(it) }
        return f
    }

    @Test
    fun `finds banner in a small kernel`() {
        val f = writeTemp("k1") { o ->
            o.write(elfHeader(0xb7))
            o.write("\n\nLinux version 6.1.138-android14-11-gabc (build@host) #1 SMP\n".toByteArray())
        }
        val r = KernelInspector.scanKernel(f)
        assertEquals("6.1.138", r.version)
        assertEquals("arm64", r.arch)
    }

    @Test
    fun `banner past the first megabyte is still found`() {
        // Real GKI kernels are tens of megabytes and the banner is not near
        // the start, so a single 2MB peek would miss it.
        val f = writeTemp("k2") { o ->
            o.write(elfHeader(0xb7))
            repeat(3 * 1024 * 1024) { o.write(0) }
            o.write("Linux version 6.1.75-android14-1 (b@h) #1 SMP".toByteArray())
        }
        val r = KernelInspector.scanKernel(f)
        assertEquals("6.1.75", r.version)
    }

    @Test
    fun `banner split across a chunk boundary is still found`() {
        // The chunk size is 1MB and the carry overlap is small, so this places
        // the banner so it straddles the boundary.
        val banner = "Linux version 6.1.99-android14-5 (b@h) #1 SMP"
        val f = writeTemp("k3") { o ->
            o.write(elfHeader(0xb7))
            val padding = (1024 * 1024) - (banner.length / 2)
            repeat(padding) { o.write(0) }
            o.write(banner.toByteArray())
        }
        val r = KernelInspector.scanKernel(f)
        assertEquals("6.1.99", r.version)
    }

    @Test
    fun `x86_64 kernel reports x86_64`() {
        val f = writeTemp("k4") { o ->
            o.write(elfHeader(0x3e))
            o.write("Linux version 6.1.1 (b@h) #1 SMP".toByteArray())
        }
        assertEquals("x86_64", KernelInspector.scanKernel(f).arch)
    }

    @Test
    fun `non elf kernel yields a null architecture rather than a guess`() {
        val f = writeTemp("k5") { o ->
            o.write(ByteArray(16))
            o.write("Linux version 6.1.1 (b@h) #1 SMP".toByteArray())
        }
        val r = KernelInspector.scanKernel(f)
        assertEquals("6.1.1", r.version)
        assertNull(r.arch)
    }

    @Test
    fun `no banner yields nulls instead of throwing`() {
        val f = writeTemp("k6") { o -> o.write(elfHeader(0xb7)); repeat(4096) { o.write(0) } }
        val r = KernelInspector.scanKernel(f)
        assertNull(r.banner)
        assertNull(r.version)
        // The ELF header was still readable.
        assertEquals("arm64", r.arch)
    }

    @Test
    fun `empty and missing files do not crash`() {
        val empty = writeTemp("k7") { }
        val r1 = KernelInspector.scanKernel(empty)
        assertNull(r1.version)
        val missing = File(System.getProperty("java.io.tmpdir"), "definitely-missing-kernel.bin")
        val r2 = KernelInspector.scanKernel(missing)
        assertNull(r2.version)
    }

    @Test
    fun `arch machine mapping covers the kernels Android ships`() {
        assertEquals("arm64", KernelInspector.archForMachine(0xb7))
        assertEquals("x86_64", KernelInspector.archForMachine(0x3e))
        assertEquals("arm", KernelInspector.archForMachine(0x28))
        assertEquals("riscv64", KernelInspector.archForMachine(0xf3))
        assertNull(KernelInspector.archForMachine(0x1234))
    }

    @Test
    fun `arch can be read at an offset in a boot image`() {
        // Simulates reading the ELF header of an uncompressed kernel directly
        // out of a boot image, the fallback path.
        val f = writeTemp("k8") { o ->
            o.write(ByteArray(4096))          // header page
            o.write(elfHeader(0xb7))
            o.write("Linux version 6.1.138-android14-11 (b@h) #1 SMP".toByteArray())
        }
        RandomAccessFile(f, "r").use { raf ->
            assertEquals("arm64", KernelInspector.archAt(raf, 4096L))
        }
    }

    @Test
    fun `patch level normalises the magiskboot yyyy-mm-dd form`() {
        assertEquals("2025-06", CompatibilityChecker.normalizeSpl("2025-06-01"))
        assertEquals("2025-06", CompatibilityChecker.normalizeSpl("2025-06"))
        assertEquals("2025-06", CompatibilityChecker.normalizeSpl("202506"))
    }

    @Test
    fun `magiskboot style header key value lines parse`() {
        // Mirrors the header file magiskboot writes after unpack, which is the
        // authoritative source for format, header version and patch level.
        val header = """
            format=v4
            kernel_size=33554432
            os_version=14.0.0
            os_patch_level=2025-06-01
            header_version=4
        """.trimIndent()

        val map = header.lines()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
            }.toMap()

        assertEquals("v4", map["format"])
        assertEquals(4, map["header_version"]?.toIntOrNull())
        assertEquals("14.0.0", map["os_version"])
        assertEquals("2025-06", CompatibilityChecker.normalizeSpl(map["os_patch_level"] ?: ""))
    }

    @Test
    fun `banner longer than the read window still yields a usable line`() {
        val long = "Linux version 6.1.138-android14-11-abcdefgh (builder with a very long name) #1 SMP PREEMPT Thu Jan 1 00:00:00 UTC 1970"
        val f = writeTemp("k9") { o ->
            o.write(elfHeader(0xb7))
            o.write(long.toByteArray())
        }
        val r = KernelInspector.scanKernel(f)
        assertNotNull(r.banner)
        assertEquals("6.1.138", r.version)
        assertTrue(r.banner!!.contains("PREEMPT"))
    }
}