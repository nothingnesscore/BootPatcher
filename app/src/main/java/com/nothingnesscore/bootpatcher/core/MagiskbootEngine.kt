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

        /** A real magiskboot is ~780 KB; anything smaller is a truncated copy. */
        private const val MIN_BINARY_BYTES = 400_000L

        /**
         * Extracts the bundled binary to a location where it can actually be
         * executed.
         *
         * Android 10 (API 29) enforces W^X on the app's home directory: a file
         * under `filesDir` or `cacheDir` can be written but not run, so
         * `ProcessBuilder` fails with a permission error on every supported
         * device. The app-specific external directory is exec-capable, which is
         * the standard workaround for shipping a native helper in an APK.
         */
        fun fromAssets(context: Context): File {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val target = File(dir, ASSET_NAME)

            if (target.exists() && target.length() > MIN_BINARY_BYTES) {
                target.setExecutable(true, false)
                return target
            }

            context.assets.open(ASSET_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            check(target.setExecutable(true, false)) {
                "Could not mark magiskboot executable in ${dir.absolutePath}"
            }
            check(target.length() > MIN_BINARY_BYTES) {
                "Bundled magiskboot is only ${target.length()} bytes; the asset is corrupt."
            }
            return target
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

            try {
                run(magiskboot, listOf("unpack", local.absolutePath), dir)
            } catch (e: PatchFailure) {
                return InspectedBoot(null, null, null, null, null, null, null,
                    "magiskboot could not unpack this image: ${e.message}")
            }

            val header = readHeaderFile(File(dir, "header"))
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
                format = header["format"],
                headerVersion = header["header_version"]?.trim()?.toIntOrNull(),
                osVersion = header["os_version"],
                osPatchLevel = header["os_patch_level"],
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