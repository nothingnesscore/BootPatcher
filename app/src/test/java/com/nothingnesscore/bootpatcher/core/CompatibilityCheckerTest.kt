package com.nothingnesscore.bootpatcher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompatibilityCheckerTest {

    private val target = TargetSpec(
        androidMajor = 14,
        kernelVersion = "6.1.138",
        osPatchLevel = "2025-06",
        bootHeaderVersion = 3,
        arch = "arm64",
    )

    private fun check(
        bootBytes: ByteArray,
        spec: TargetSpec = target,
    ): Verdict {
        val (info, k) = inspectBytes(bootBytes)
        return CompatibilityChecker.compare(info, k, spec)
    }

    private fun matchingV3() = BootImageFixtures.v3WithKernel(
        "Linux version 6.1.138-android14-11-gabc (b@h) #1 SMP PREEMPT",
        headerVersion = 3,
    )

    @Test
    fun `fully matching image passes with no advisories`() {
        val v = check(matchingV3())
        assertTrue(v.ok, v.blockingText)
        assertTrue(v.advisory.isEmpty(), v.advisoryText)
    }

    @Test
    fun `android version mismatch blocks`() {
        val bytes = BootImageFixtures.v3Header(major = 15, minor = 0, patch = 0, month = 6)
            .let { h -> BootImageFixtures.withKernel(h, 4096, "Linux version 6.1.138 (b@h) #1 SMP") }
        val v = check(bytes)
        assertFalse(v.ok)
        assertTrue(v.blocking.any { it.contains("Android version mismatch") }, v.blockingText)
    }

    @Test
    fun `kernel version mismatch blocks`() {
        val bytes = BootImageFixtures.v3Header(major = 14, minor = 1, patch = 75)
            .let { h -> BootImageFixtures.withKernel(h, 4096, "Linux version 6.1.75 (b@h) #1 SMP") }
        val v = check(bytes)
        assertFalse(v.ok)
        assertTrue(v.blocking.any { it.contains("Kernel version mismatch") }, v.blockingText)
    }

    @Test
    fun `security patch level mismatch blocks`() {
        val bytes = BootImageFixtures.v3Header(major = 14, minor = 1, patch = 138, month = 5)
            .let { h -> BootImageFixtures.withKernel(h, 4096, "Linux version 6.1.138 (b@h) #1 SMP") }
        val v = check(bytes)
        assertFalse(v.ok)
        assertTrue(v.blocking.any { it.contains("Security patch level mismatch") }, v.blockingText)
    }

    @Test
    fun `boot header version mismatch blocks`() {
        val v = check(BootImageFixtures.v3WithKernel(
            "Linux version 6.1.138 (b@h) #1 SMP", headerVersion = 4,
        ))
        assertFalse(v.ok)
        assertTrue(v.blocking.any { it.contains("Boot header version mismatch") }, v.blockingText)
    }

    @Test
    fun `architecture mismatch blocks`() {
        val v = check(BootImageFixtures.v3WithKernel(
            "Linux version 6.1.138 (b@h) #1 SMP", machine = 0x3e, // x86_64
        ))
        assertFalse(v.ok)
        assertTrue(v.blocking.any { it.contains("Architecture mismatch") }, v.blockingText)
    }

    @Test
    fun `unreadable kernel blocks`() {
        val v = check(BootImageFixtures.v3WithKernel("garbage, no banner"))
        assertFalse(v.ok)
        assertTrue(v.blocking.any { it.contains("Linux version") }, v.blockingText)
    }

    @Test
    fun `unknown architecture blocks rather than passing silently`() {
        val bytes = BootImageFixtures.v3WithKernel("Linux version 6.1.138 (b@h) #1 SMP")
            .copyOf().also { it[4096] = 0x00 } // break the ELF magic
        val v = check(bytes)
        assertFalse(v.ok)
        assertTrue(v.blocking.any { it.contains("architecture") }, v.blockingText)
    }

    @Test
    fun `v2 image with matching spl passes`() {
        val bytes = BootImageFixtures.v2WithKernel(
            "Linux version 6.1.138 (b@h) #1 SMP",
            headerVersion = 2,
        )
        val v = CompatibilityChecker.run {
            val (info, k) = inspectBytes(bytes)
            compare(info, k, target.copy(bootHeaderVersion = 2))
        }
        assertTrue(v.ok, v.blockingText)
    }

    @Test
    fun `v2 image still enforces the patch level`() {
        // The SPL is packed into os_version on v2 as well; a regression here
        // would silently disable the SPL guard for older images.
        val bytes = BootImageFixtures.v2Header(major = 14, minor = 1, patch = 138, month = 5)
            .let { h -> BootImageFixtures.withKernel(h, 4096, "Linux version 6.1.138 (b@h) #1 SMP") }
        val v = CompatibilityChecker.run {
            val (info, k) = inspectBytes(bytes)
            compare(info, k, target.copy(bootHeaderVersion = 2))
        }
        assertFalse(v.ok)
        assertTrue(v.blocking.any { it.contains("Security patch level mismatch") }, v.blockingText)
    }

    @Test
    fun `null optional target fields are not checked`() {
        val v = check(
            matchingV3(),
            target.copy(bootHeaderVersion = null, arch = null),
        )
        assertTrue(v.ok, v.blockingText)
    }

    @Test
    fun `versions compare numerically not lexically`() {
        assertTrue(CompatibilityChecker.versionsMatch("6.1.138", "6.1.138"))
        assertFalse(CompatibilityChecker.versionsMatch("6.1.75", "6.1.138"))
        assertTrue(CompatibilityChecker.versionsMatch("6.1", "6.1.0"))
        assertFalse(CompatibilityChecker.versionsMatch("5.15.148", "6.1.138"))
    }

    @Test
    fun `patch level normalisation accepts both forms`() {
        assertEquals("2025-06", CompatibilityChecker.normalizeSpl("2025-06"))
        assertEquals("2025-06", CompatibilityChecker.normalizeSpl("202506"))
    }
}