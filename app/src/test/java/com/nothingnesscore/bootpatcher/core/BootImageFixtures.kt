package com.nothingnesscore.bootpatcher.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Builds synthetic boot headers so parser behaviour is pinned without checking
 * multi-megabyte binaries into the repository.
 *
 * Layouts mirror AOSP `include/bootimg/bootimg.h` exactly, including the packed
 * os_version word (A[31:25] B[24:18] C[17:11] (Y-2000)[10:4] M[3:0]).
 */
object BootImageFixtures {

    const val PAGE_SIZE = 4096

    /** Packs A.B.C and Y-M into the single os_version u32. */
    fun packOsVersionPatchLevel(
        major: Int,
        minor: Int,
        patch: Int,
        year: Int,
        month: Int,
    ): Int = ((major and 0x7f) shl 25) or
        ((minor and 0x7f) shl 18) or
        ((patch and 0x7f) shl 11) or
        (((year - 2000) and 0x7f) shl 4) or
        (month and 0xf)

    /**
     * v3/v4: kernel_size@8, ramdisk_size@12, os_version@16, header_size@20,
     * reserved[4]@24, header_version@40. There is no page_size field in v3+.
     */
    fun v3Header(
        headerVersion: Int = 3,
        major: Int = 14,
        minor: Int = 1,
        patch: Int = 138,
        year: Int = 2025,
        month: Int = 6,
        headerSize: Int = 1644,
        kernelSize: Int = 0x2000000,
        ramdiskSize: Int = 0x01234567,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ANDROID!".toByteArray(Charsets.US_ASCII))
        val p = Pad(out)
        p.u32(kernelSize)                       // 8
        p.u32(ramdiskSize)                      // 12
        p.u32(packOsVersionPatchLevel(major, minor, patch, year, month)) // 16
        p.u32(headerSize)                       // 20
        repeat(4) { p.u32(0) }                 // reserved[4] -> 24..40
        p.u32(headerVersion)                    // 40
        p.padTo(1600)
        return out.toByteArray()
    }

    /** v0/v1/v2: page_size@36, header_version@40, os_version@44. */
    fun v2Header(
        headerVersion: Int = 2,
        major: Int = 14,
        minor: Int = 1,
        patch: Int = 138,
        year: Int = 2025,
        month: Int = 6,
        pageSize: Int = PAGE_SIZE,
        kernelSize: Int = 0x2000000,
        ramdiskSize: Int = 0x01234567,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ANDROID!".toByteArray(Charsets.US_ASCII))
        val p = Pad(out)
        p.u32(kernelSize)          // 8
        p.u32(0x40008000)          // 12 kernel_addr
        p.u32(ramdiskSize)         // 16
        p.u32(0x41000000)          // 20 ramdisk_addr
        p.u32(0)                   // 24 second_size
        p.u32(0)                   // 28 second_addr
        p.u32(0x10000100)          // 32 tags_addr
        p.u32(pageSize)            // 36
        p.u32(headerVersion)       // 40
        p.u32(packOsVersionPatchLevel(major, minor, patch, year, month)) // 44
        p.padTo(1640)              // name[16] + cmdline[512] + id[32] + extra_cmdline[1024]
        return out.toByteArray()
    }

    fun garbage(size: Int = 4096): ByteArray =
        ByteArray(size) { (it * 31 % 251).toByte() }

    /**
     * A kernel page: a minimal ELF header (so architecture detection has
     * something real to read) followed by the version banner.
     */
    fun kernelPage(banner: String, machine: Int = 0xb7): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(
            0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), // e_ident
            2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        ))
        out.write(byteArrayOf(2, 0))                       // e_type = ET_EXEC
        out.write(byteArrayOf(
            (machine and 0xff).toByte(), ((machine shr 8) and 0xff).toByte(),
        ))                                                  // e_machine
        while (out.size() < 64) out.write(0)
        out.write(banner.toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /** A boot image whose kernel page carries a Linux version banner. */
    fun withKernel(header: ByteArray, pageSize: Int, banner: String, machine: Int = 0xb7): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header)
        while (out.size() % pageSize != 0) out.write(0)
        out.write(kernelPage(banner, machine))
        out.write(ByteArray(pageSize))
        return out.toByteArray()
    }

    fun v3WithKernel(banner: String, headerVersion: Int = 3, machine: Int = 0xb7): ByteArray =
        withKernel(v3Header(headerVersion = headerVersion), PAGE_SIZE, banner, machine)

    fun v2WithKernel(banner: String, headerVersion: Int = 2, machine: Int = 0xb7): ByteArray =
        withKernel(v2Header(headerVersion = headerVersion), PAGE_SIZE, banner, machine)
}

/** Writes little-endian u32s sequentially and can pad the stream to a size. */
private class Pad(private val out: ByteArrayOutputStream) {
    fun u32(v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
        out.write((v shr 16) and 0xff)
        out.write((v shr 24) and 0xff)
    }

    fun padTo(size: Int) {
        while (out.size() < size) out.write(0)
    }
}

/** Parses an in-memory image, deleting the scratch file afterwards. */
internal fun parseBytes(bytes: ByteArray): BootImageInfo {
    val tmp = File.createTempFile("bootfix", ".img")
    try {
        tmp.writeBytes(bytes)
        return RandomAccessFile(tmp, "r").use { BootImageParser.parse(it) }
    } finally {
        tmp.delete()
    }
}

internal fun inspectBytes(bytes: ByteArray): Pair<BootImageInfo, KernelInspector.Result> {
    val tmp = File.createTempFile("bootfix", ".img")
    try {
        tmp.writeBytes(bytes)
        return RandomAccessFile(tmp, "r").use { raf ->
            val info = BootImageParser.parse(raf)
            info to KernelInspector.inspect(raf, info)
        }
    } finally {
        tmp.delete()
    }
}