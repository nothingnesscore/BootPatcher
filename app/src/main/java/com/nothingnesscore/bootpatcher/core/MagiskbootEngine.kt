package com.nothingnesscore.bootpatcher.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Runs the bundled `magiskboot` binary to unpack a boot image, swap the
 * kernel, and repack.
 *
 * The binary ships in `assets/` and is copied into the app's private storage
 * on first use, because assets are not executable on Android.
 */
class MagiskbootEngine(private val context: Context) {

    companion object {
        private const val TAG = "Magiskboot"
        private const val ASSET_NAME = "magiskboot"

        /** Name the binary is installed under as a native library. */
        private const val LIB_NAME = "libmagiskboot.so"

        /** A real magiskboot is ~780 KB; anything smaller is a truncated copy. */
        private const val MIN_BINARY_BYTES = 400_000L

        /**
         * Locates an executable magiskboot.
         *
         * Verified on a real peridot (Poco F6, Android 17 / HyperOS, SELinux
         * enforcing): an untrusted_app is denied `execute_no_trans` on files
         * under its own `app_data_file` context, so a binary extracted to
         * filesDir or cacheDir cannot be run no matter what mode bits it has:
         *
         *   avc: denied { execute_no_trans } for
         *   path="/data/data/<pkg>/files/magiskboot"
         *   scontext=u:r:untrusted_app_34 tcontext=u:object_r:app_data_file
         *
         * The APK ships the binary as lib/arm64-v8a/libmagiskboot.so, so the
         * package manager extracts it into the app's native library directory,
         * which is exec-permitted. That location is tried first. The app's own
         * data directory is only a fallback for devices with a laxer policy.
         */
        fun fromAssets(context: Context): File {
            val candidates = buildList {
                // Extracted by the package manager at install time.
                add(File(context.applicationInfo.nativeLibraryDir, LIB_NAME))
                // Fallbacks: copied out of assets if the above is unavailable.
                add(File(context.filesDir, ASSET_NAME))
                context.getExternalFilesDir(null)?.let { add(File(it, ASSET_NAME)) }
            }

            // The binary lives in the native library directory, installed and owned by
            // the package manager, so it is already 0755 and exec-permitted.
            // chmod-ing it only produces SELinux setattr denials.
            candidates.firstOrNull { it.exists() && it.length() > MIN_BINARY_BYTES }
                ?.let { return it }

            var lastError: Exception? = null
            for (target in candidates.drop(1)) {
                try {
                    target.parentFile?.mkdirs()
                    context.assets.open(ASSET_NAME).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(target.length() > MIN_BINARY_BYTES) {
                        "Bundled magiskboot is only ${target.length()} bytes; the asset is corrupt."
                    }
                    return target.makeExecutable()
                } catch (e: Exception) {
                    lastError = e
                    target.delete()
                }
            }
            throw IllegalStateException(
                "Could not locate magiskboot: ${lastError?.message}", lastError
            )
        }

        private fun File.makeExecutable(): File {
            setReadable(true, false)
            setReadable(true, true)
            setExecutable(true, false)
            setExecutable(true, true)
            return this
        }
    }

    class PatchFailure(message: String, val log: String) : Exception(message)

    private val workDir: File
        get() = File(context.cacheDir, "patch-work").also { it.mkdirs() }

    private fun bootDir(name: String): File = File(workDir, name).also { it.mkdirs() }

    /**
     * Patches [stockBoot] with [kernel], writing the result to [output].
     *
     * @throws PatchFailure if magiskboot exits non-zero at any stage. The
     *   caller must treat any failure as "do not flash".
     */
    fun patch(stockBoot: File, kernel: File, output: File): File {
        val magiskboot = fromAssets(context)
        val dir = bootDir("job-${System.nanoTime()}")
        val buildLog = StringBuilder()

        try {
            // magiskboot names its unpacked kernel "kernel" and its output
            // "<input>-new.img"; both are resolved explicitly rather than guessed.
            val localBoot = File(dir, "boot.img")
            stockBoot.copyTo(localBoot, overwrite = true)

            log(buildLog, dir) { run(magiskboot, listOf("unpack", localBoot.absolutePath), dir) }

            val unpackedKernel = File(dir, "kernel")
            if (!unpackedKernel.exists()) {
                throw PatchFailure(
                    "magiskboot unpack produced no kernel file; the boot image may be corrupt.",
                    buildLog.toString(),
                )
            }

            // The incoming kernel is the authoritative payload.
            kernel.copyTo(unpackedKernel, overwrite = true)

            log(buildLog, dir) {
                run(magiskboot, listOf("repack", localBoot.absolutePath), dir)
            }

            // Prefer the explicit name, then the documented fallback.
            val produced = sequenceOf(
                File(dir, "${localBoot.nameWithoutExtension}-new.img"),
                File(dir, "new-boot.img"),
            ).firstOrNull { it.exists() && it.length() > 0L }

            if (produced == null) {
                throw PatchFailure(
                    "magiskboot repack did not produce an output image.",
                    buildLog.toString(),
                )
            }

            output.parentFile?.mkdirs()
            produced.copyTo(output, overwrite = true)
            return output
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * What magiskboot reports about an unpacked boot image.
     *
     * Every field is nullable because an image that fails to unpack, or that
     * omits a key, must be reported as unknown rather than guessed.
     */
    data class InspectedBoot(
        val format: String?,
        val headerVersion: Int?,
        val osVersion: String?,
        val osPatchLevel: String?,
        val kernelBanner: String?,
        val kernelVersion: String?,
        val arch: String?,
        val error: String? = null,
    )

    /**
     * Unpacks [bootImage] with magiskboot and reports what it finds.
     *
     * This is the authoritative inspection path. Reading the raw header alone
     * is not enough, because on GKI images the kernel is usually stored
     * compressed (lz4 or gzip) and its "Linux version" banner simply does not
     * appear in the raw bytes. magiskboot decompresses it first, which is why
     * the existing Actions patcher inspects the same way.
     */
    fun inspect(bootImage: File): InspectedBoot {
        val magiskboot = try {
            fromAssets(context)
        } catch (e: Exception) {
            return InspectedBoot(null, null, null, null, null, null, null,
                e.message ?: "magiskboot is unavailable")
        }

        val dir = bootDir("inspect-${System.nanoTime()}")
        try {
            val local = File(dir, "boot.img")
            bootImage.copyTo(local, overwrite = true)

            val output = try {
                run(magiskboot, listOf("unpack", local.absolutePath), dir)
            } catch (e: PatchFailure) {
                return InspectedBoot(null, null, null, null, null, null, null,
                    "magiskboot could not unpack this image: ${e.message}")
            }

            // magiskboot does not always write a `header` file, and on v4
            // images the packed os_version word is frequently zero, so the
            // authoritative values come from what magiskboot printed while
            // unpacking. Fall back to a header file when one exists.
            val header = readHeaderFile(File(dir, "header"))
            val printed = parseUnpackOutput(output)

            val kernel = File(dir, "kernel")
            var banner: String? = null
            var version: String? = null
            var arch: String? = null
            if (kernel.exists() && kernel.length() > 0) {
                val scanned = KernelInspector.scanKernel(kernel)
                banner = scanned.banner
                version = scanned.version
                arch = scanned.arch
            }

            return InspectedBoot(
                format = header["format"] ?: printed["format"],
                headerVersion = header["header_version"]?.trim()?.toIntOrNull()
                    ?: printed["header_version"]?.toIntOrNull(),
                osVersion = header["os_version"] ?: printed["os_version"],
                osPatchLevel = header["os_patch_level"] ?: printed["os_patch_level"],
                kernelBanner = banner,
                kernelVersion = version,
                arch = arch,
                error = null,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** magiskboot writes a `header` file of key=value pairs. */
    private fun readHeaderFile(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val out = mutableMapOf<String, String>()
        file.readLines().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) out[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return out
    }

    /**
     * Parses the `KEY [value]` lines magiskboot prints while unpacking.
     *
     * Observed output for a v4 GKI image:
     *   HEADER_VER      [4]
     *   KERNEL_SZ       [37014016]
     *   PAGESIZE        [4096]
     *   KERNEL_FMT      [raw]
     */
    private fun log(sb: StringBuilder, dir: File, block: () -> Unit) {
        block()
        sb.appendLine("--- $dir ---")
    }

    private fun run(bin: File, args: List<String>, cwd: File): String {
        val proc = try {
            ProcessBuilder(bin.absolutePath, *args.toTypedArray())
                .directory(cwd)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            // A permission error here almost always means the binary was left
            // somewhere exec is blocked, so say so instead of surfacing a bare
            // "EACCES".
            throw PatchFailure(
                "Could not run magiskboot from ${bin.parent}. " +
                    "On this device that location is not executable.",
                e.message ?: e.toString(),
            )
        }

        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        Log.i(TAG, "magiskboot ${args.first()} exited $code: $out")
        if (code != 0) {
            throw PatchFailure(
                "magiskboot ${args.first()} failed (exit $code).",
                out,
            )
        }
        return out
    }
}

/**
 * Parses the `KEY [value]` lines magiskboot prints while unpacking.
 *
 * Captured from a real peridot stock boot image:
 *   HEADER_VER      [4]
 *   KERNEL_SZ       [37014016]
 *   PAGESIZE        [4096]
 *   KERNEL_FMT      [raw]
 *   PATCH_LEVEL     [2025-06]
 *   OS_VERSION      [14.0.0]
 *
 * magiskboot does not write a `header` file for every image, so this printed
 * output is the authoritative source for header version and page size.
 */
fun parseUnpackOutput(text: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    val re = Regex("""^\s*([A-Za-z_]+)\s+\[([^\]]*)]""", RegexOption.MULTILINE)
    for (m in re.findAll(text)) {
        val key = m.groupValues[1].lowercase()
        val value = m.groupValues[2].trim()
        when (key) {
            "header_ver" -> out["header_version"] = value
            "kernel_sz" -> out["kernel_size"] = value
            "ramdisk_sz" -> out["ramdisk_size"] = value
            "pagesize" -> out["page_size"] = value
            "kernel_fmt" -> out["format"] = value
            // magiskboot prints OS_VERSION and PATCH_LEVEL on v4 images, but
            // older output uses OS_VER and OS_PATCH_LEVEL. Accept both.
            "os_version", "os_ver" -> out["os_version"] = value
            "os_patch_level", "patch_level" -> out["os_patch_level"] = value
            "dtb_sz" -> out["dtb_size"] = value
        }
    }
    return out
}