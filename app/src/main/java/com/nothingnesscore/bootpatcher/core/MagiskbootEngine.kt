package com.nothingnesscore.bootpatcher.core

import android.content.Context
import android.util.Log
import java.io.File

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

        fun fromAssets(context: Context): File {
            val target = File(context.filesDir, ASSET_NAME)
            if (target.exists() && target.canExecute()) return target
            context.assets.open(ASSET_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            check(target.setExecutable(true, false)) {
                "Could not mark magiskboot executable"
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

    /** Reads the header magiskboot produced, used for post-patch verification. */
    fun readHeader(bootImage: File): Map<String, String> {
        val magiskboot = fromAssets(context)
        val dir = bootDir("hdr-${System.nanoTime()}")
        return try {
            val local = File(dir, "boot.img")
            bootImage.copyTo(local, overwrite = true)
            runCatching { run(magiskboot, listOf("unpack", local.absolutePath), dir) }
            val header = File(dir, "header")
            if (!header.exists()) return emptyMap()
            header.readLines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null
                    else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }
                .toMap()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun log(sb: StringBuilder, dir: File, block: () -> Unit) {
        block()
        sb.appendLine("--- $dir ---")
    }

    private fun run(bin: File, args: List<String>, cwd: File): String {
        val proc = ProcessBuilder(bin.absolutePath, *args.toTypedArray())
            .directory(cwd)
            .redirectErrorStream(true)
            .start()

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