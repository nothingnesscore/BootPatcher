package com.nothingnesscore.bootpatcher.core

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal Android boot image header parser.
 *
 * Offsets and bit packing are taken from AOSP mkbootimg
 * (`include/bootimg/bootimg.h`). Both header families pack the OS version and
 * the security patch level into a single u32 named `os_version`:
 *
 *   os_version = A[31:25] B[24:18] C[17:11] (Y-2000)[10:4] M[3:0]
 *
 * That is why this file never reads a separate "patch level" word: on a v0-v2
 * image the SPL simply lives in the low bits of os_version too, and treating it
 * as absent would silently disable the SPL safety check.
 *
 * v0/v1/v2 (struct boot_img_hdr_v0 + v1/v2 tail), little-endian:
 *     0   magic[8]          "ANDROID!"
 *     8   kernel_size       u32
 *     12  kernel_addr       u32
 *     16  ramdisk_size      u32
 *     20  ramdisk_addr      u32
 *     24  second_size       u32
 *     28  second_addr       u32
 *     32  tags_addr         u32
 *     36  page_size         u32
 *     40  header_version    u32
 *     44  os_version        u32   (A.B.C + Y-M packed)
 *     48  name[16]
 *     64  cmdline[512]
 *     576 id[32]
 *     608 extra_cmdline[1024]
 *
 * v3/v4 (struct boot_img_hdr_v3):
 *     0   magic[8]
 *     8   kernel_size       u32
 *     12  ramdisk_size      u32
 *     16  os_version        u32   (A.B.C + Y-M packed)
 *     20  header_size       u32
 *     24  reserved[4]       (16 bytes, no page_size field in v3+)
 *     40  header_version    u32
 *     44  cmdline[1536]
 */
data class BootImageInfo(
    val headerVersion: Int,
    val pageSize: Int,
    val headerSize: Int,
    val kernelSize: Long,
    val ramdiskSize: Long,
    val osVersionRaw: Int,
) {
    /**
     * Number of pages the header occupies; content starts after them.
     * For v0-v2 the header is one page; for v3+ it is header_size bytes,
     * rounded up to whole pages (always at least one).
     */
    val headerPages: Int
        get() {
            val bytes = if (headerSize <= 0) 4096 else headerSize
            return Math.floorDiv(bytes + pageSize - 1, pageSize).coerceAtLeast(1)
        }

    val kernelOffset: Long get() = headerPages.toLong() * pageSize

    /** "2025-06" - exactly how BruhKernel names the SPL in artifact names. */
    val patchLevelString: String?
        get() = decodeOsPatchLevel(osVersionRaw)

    /** "android14" */
    val androidMajor: Int? get() = decodeOsVersion(osVersionRaw)?.major

    companion object {
        const val DEFAULT_PAGE_SIZE = 4096

        fun decodeOsVersion(raw: Int): OsVersion? {
            val a = (raw ushr 25) and 0x7f
            val b = (raw ushr 18) and 0x7f
            val c = (raw ushr 11) and 0x7f
            if (a == 0 && b == 0 && c == 0) return null
            return OsVersion(a, b, c)
        }

        /** Returns "YYYY-MM" from the packed low 11 bits, or null if invalid. */
        fun decodeOsPatchLevel(raw: Int): String? {
            val yearOffset = (raw ushr 4) and 0x7f
            val month = raw and 0xf
            if (yearOffset == 0 && month == 0) return null
            if (month !in 1..12) return null
            return "%04d-%02d".format(2000 + yearOffset, month)
        }
    }
}

data class OsVersion(val major: Int, val minor: Int, val patch: Int) {
    override fun toString() =
        if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
}

class BootImageParseException(message: String) : Exception(message)

object BootImageParser {

    const val MIN_HEADER_BYTES = 48

    fun parse(file: RandomAccessFile): BootImageInfo {
        val header = ByteArray(4096)
        file.seek(0)
        val read = file.read(header)
        if (read < MIN_HEADER_BYTES) {
            throw BootImageParseException(
                "File is only $read bytes; too small to be a boot image."
            )
        }
        val magic = String(header, 0, 8, Charsets.US_ASCII)
        if (magic != "ANDROID!") {
            throw BootImageParseException(
                "Not an Android boot image (magic is \"${magic.escape()}\", " +
                    "expected \"ANDROID!\"). This is probably a raw kernel - " +
                    "patch a boot.img instead."
            )
        }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // header_version is at offset 40 in BOTH the v0-v2 and v3+ layouts.
        val version = buf.getInt(40)
        return if (version >= 3) parseV3(buf, version) else parseV02(buf, version)
    }

    private fun parseV02(buf: ByteBuffer, version: Int): BootImageInfo {
        val pageSize = buf.getInt(36)
        // v0-v2 headers are one page; header_size only exists from v1 and is a
        // u64 split across bytes, so page-based sizing is authoritative here.
        return BootImageInfo(
            headerVersion = version,
            pageSize = if (pageSize in 256..65536) pageSize else BootImageInfo.DEFAULT_PAGE_SIZE,
            headerSize = if (pageSize in 256..65536) pageSize else BootImageInfo.DEFAULT_PAGE_SIZE,
            kernelSize = buf.getInt(8).toLong() and 0xffffffffL,
            ramdiskSize = buf.getInt(16).toLong() and 0xffffffffL,
            osVersionRaw = buf.getInt(44),
        )
    }

    private fun parseV3(buf: ByteBuffer, version: Int): BootImageInfo {
        val declaredHeaderSize = buf.getInt(20)
        // v3+ has no page_size field; flash page size is fixed at 4096.
        val pageSize = BootImageInfo.DEFAULT_PAGE_SIZE
        return BootImageInfo(
            headerVersion = version,
            pageSize = pageSize,
            headerSize = if (declaredHeaderSize in 1644..65536) declaredHeaderSize else 1644,
            kernelSize = buf.getInt(8).toLong() and 0xffffffffL,
            ramdiskSize = buf.getInt(12).toLong() and 0xffffffffL,
            osVersionRaw = buf.getInt(16),
        )
    }

    private fun String.escape() = map { if (it.isISOControl()) '.' else it }.joinToString("")
}

/**
 * Reads the kernel out of a boot image without fully unpacking it.
 *
 * Returns only a bounded window around the "Linux version ..." banner so
 * inspecting a large image stays cheap.
 */
object KernelInspector {

    private const val WINDOW = 2 * 1024 * 1024
    private const val ELF_HEADER_BYTES = 64

    data class Result(val banner: String?, val version: String?, val arch: String?)

    private val VERSION_RE = Regex("""Linux version (\d+\.\d+(?:\.\d+)?)""")

    fun inspect(file: RandomAccessFile, info: BootImageInfo): Result {
        val arch = detectArch(file, info.kernelOffset)
        val banner = findBanner(file, info.kernelOffset)
        if (banner == null) return Result(null, null, arch)

        var end = banner.indexOf('\n')
        if (end < 0) end = Math.min(banner.length, 300)
        val line = banner.substring(0, end).trim()

        val version = VERSION_RE.find(line)?.groupValues?.getOrNull(1)
        return Result(line, version, arch)
    }

    /**
     * Reads the architecture from the kernel's own header.
     *
     * The "Linux version" banner does not reliably name the architecture, so
     * the ELF machine field is used instead. Compressed or otherwise
     * non-ELF kernels return null, which the checker treats as a hard stop
     * rather than assuming arm64.
     */
    private fun detectArch(file: RandomAccessFile, kernelStart: Long): String? {
        file.seek(kernelStart)
        val hdr = ByteArray(ELF_HEADER_BYTES)
        var n = 0
        while (n < hdr.size) {
            val r = file.read(hdr, n, hdr.size - n)
            if (r <= 0) break
            n += r
        }
        if (n < 20) return null
        if (hdr[0] != 0x7f.toByte() || hdr[1] != 'E'.code.toByte() ||
            hdr[2] != 'L'.code.toByte() || hdr[3] != 'F'.code.toByte()
        ) return null

        val machine = ((hdr[19].toInt() and 0xff) shl 8) or (hdr[18].toInt() and 0xff)
        return when (machine) {
            0x3e -> "x86_64"     // EM_X86_64
            0xb7 -> "arm64"      // EM_AARCH64
            0xf3 -> "riscv64"    // EM_RISCV
            0x28 -> "arm"        // EM_ARM
            else -> null
        }
    }

    private fun findBanner(file: RandomAccessFile, kernelStart: Long): String? {
        file.seek(kernelStart)
        val buf = ByteArray(WINDOW)
        var n = 0
        while (n < WINDOW) {
            val r = file.read(buf, n, WINDOW - n)
            if (r <= 0) break
            n += r
        }
        if (n <= 0) return null
        val text = String(buf, 0, n, Charsets.ISO_8859_1)
        val idx = text.indexOf("Linux version ")
        return if (idx < 0) null else text.substring(idx)
    }
}