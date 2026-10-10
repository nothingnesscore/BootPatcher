package com.nothingnesscore.bootpatcher.core

import java.util.Locale

/**
 * The set of values a boot image and a kernel artifact must agree on before
 * patching is allowed. Getting any of these wrong bricks the device, so the
 * checker is deliberately strict and never coerces a mismatch into a warning.
 */
data class TargetSpec(
    val androidMajor: Int,
    val kernelVersion: String,
    val osPatchLevel: String,
    val bootHeaderVersion: Int?,
    val arch: String?,
)

data class Verdict(
    val ok: Boolean,
    val blocking: List<String>,
    val advisory: List<String>,
) {
    val blockingText: String get() = blocking.joinToString("\n")
    val advisoryText: String get() = advisory.joinToString("\n")
}

object CompatibilityChecker {

    /**
     * Compares a stock boot image against the kernel artifact it will receive.
     *
     * Rule: every field the stock image *knows about* must match. A field the
     * stock image does not carry (v0-v2 has no os_patch_level at all) is
     * reported as advisory rather than silently passing, because the artifact
     * cannot be proven wrong on that axis.
     */
    fun compare(
        boot: BootImageInfo,
        bootKernel: KernelInspector.Result,
        target: TargetSpec,
    ): Verdict {
        val blocking = mutableListOf<String>()
        val advisory = mutableListOf<String>()

        // --- Android version -------------------------------------------------
        val bootAndroid = boot.androidMajor
        if (bootAndroid == null) {
            blocking += "The stock boot image does not declare an Android version, " +
                "so its compatibility cannot be proven."
        } else if (bootAndroid != target.androidMajor) {
            blocking += "Android version mismatch: stock boot is Android $bootAndroid, " +
                "but this kernel is built for Android ${target.androidMajor}."
        }

        // --- Kernel version --------------------------------------------------
        val bootVersion = bootKernel.version
        if (bootVersion == null) {
            blocking += "Could not read a \"Linux version\" string from the stock " +
                "kernel. The boot image may be corrupted or may not be a boot.img."
        } else if (!versionsMatch(bootVersion, target.kernelVersion)) {
            blocking += "Kernel version mismatch: stock boot reports $bootVersion, " +
                "the kernel is $target.kernelVersion."
        }

        // --- Security patch level -------------------------------------------
        // The SPL is packed into the low 11 bits of os_version on every header
        // version, so this check applies to v0-v2 and v3+ alike.
        val bootSpl = boot.patchLevelString
        val wantSpl = normalizeSpl(target.osPatchLevel)
        if (bootSpl == null) {
            blocking += "This boot image header does not declare a security patch " +
                "level, so SPL compatibility cannot be proven. Refusing to patch."
        } else if (bootSpl != wantSpl) {
            blocking += "Security patch level mismatch: stock boot is $bootSpl, " +
                "the kernel is built for $wantSpl. A kernel built for a different " +
                "SPL will not boot."
        }

        // --- Boot header version --------------------------------------------
        target.bootHeaderVersion?.let { want ->
            if (boot.headerVersion != want) {
                blocking += "Boot header version mismatch: stock boot is v" +
                    "${boot.headerVersion}, the kernel expects v$want."
            }
        }

        // --- Architecture ----------------------------------------------------
        target.arch?.let { want ->
            val have = bootKernel.arch
            if (have == null) {
                blocking += "Could not determine the stock kernel architecture, " +
                    "so it cannot be confirmed to be $want."
            } else if (!have.equals(want, ignoreCase = true)) {
                blocking += "Architecture mismatch: stock boot is $have, " +
                    "the kernel is $want."
            }
        }

        return Verdict(blocking.isEmpty(), blocking, advisory)
    }

    /**
     * Compares dotted kernel versions numerically so that 6.1.75 is correctly
     * older than 6.1.138 rather than newer because of string ordering.
     */
    fun versionsMatch(a: String, b: String): Boolean {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        if (pa.isEmpty() || pb.isEmpty()) return a == b
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return false
        }
        return true
    }

    fun normalizeSpl(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.length >= 6) return "${digits.take(4)}-${digits.substring(4, 6)}"
        return raw.trim().lowercase(Locale.ROOT)
    }
}