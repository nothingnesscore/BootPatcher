package com.nothingnesscore.bootpatcher.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nothingnesscore.bootpatcher.core.ArtifactResolver
import com.nothingnesscore.bootpatcher.core.BootImageInfo
import com.nothingnesscore.bootpatcher.core.BootImageParser
import com.nothingnesscore.bootpatcher.core.CompatibilityChecker
import com.nothingnesscore.bootpatcher.core.KernelInspector
import com.nothingnesscore.bootpatcher.core.MagiskbootEngine
import com.nothingnesscore.bootpatcher.core.TargetSpec
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Step { IDLE, INSPECTING, PATCHING, DONE, FAILED }

data class UiState(
    val step: Step = Step.IDLE,
    val status: String = "",
    val stockName: String? = null,
    val stockInfo: BootImageInfo? = null,
    val stockKernel: KernelInspector.Result? = null,
    val blocking: List<String> = emptyList(),
    val advisory: List<String> = emptyList(),
    val outputFile: File? = null,
    val error: String? = null,
    val detectedFormat: String? = null,
    val detectedOsVersion: String? = null,
    val detectedPatchLevel: String? = null,
    val detectedHeaderVersion: Int? = null,
    val inspectError: String? = null,
) {
    val canPatch: Boolean get() = step == Step.IDLE && stockInfo != null
}

/** magiskboot reports the patch level as YYYY-MM-DD; the UI shows YYYY-MM. */
fun MagiskbootEngine.InspectedBoot.patchLevelFrom(raw: String?): String? {
    val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return CompatibilityChecker.normalizeSpl(v)
}

class PatchViewModel(app: Application) : AndroidViewModel(app) {

    private val resolver = ArtifactResolver()
    private val engine = MagiskbootEngine(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Reads the picked stock boot image and shows what it actually is. */
    fun inspect(uri: Uri) {
        viewModelScope.launch {
            _state.value = UiState(step = Step.INSPECTING, status = "Reading boot image...")
            val ctx = getApplication<Application>()
            try {
                val work = File(ctx.cacheDir, "inspect-${System.nanoTime()}")
                work.mkdirs()

                try {
                    val stock = withContext(Dispatchers.IO) {
                        val tmp = File(work, "stock.img")
                        val stream = ctx.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Could not open the selected file.")
                        stream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        tmp
                    }

                    // Prefer magiskboot: it decompresses the kernel, which is
                    // the only reliable way to read the version banner, since
                    // on GKI images the kernel is stored lz4/gzip compressed
                    // and the string is absent from the raw bytes. Fall back to
                    // the raw header parser if magiskboot cannot run.
                    val unpacked = withContext(Dispatchers.IO) { engine.inspect(stock) }
                    val raw = withContext(Dispatchers.IO) {
                        RandomAccessFile(stock, "r").use { raf ->
                            val info = BootImageParser.parse(raf)
                            val k = KernelInspector.inspect(raf, info)
                            info to k
                        }
                    }

                    val (boot, rawKernel) = raw
                    val kernelVersion = unpacked.kernelVersion ?: rawKernel.version
                    val kernelBanner = unpacked.kernelBanner ?: rawKernel.banner
                    val arch = unpacked.arch ?: rawKernel.arch

                    // If the kernel could not be identified at all, say so.
                    // Previously this degraded silently to the raw parser,
                    // which reports "unreadable" for any compressed GKI kernel
                    // and hides the real reason from the user.
                    if (kernelVersion == null) {
                        _state.update {
                            it.copy(
                                step = Step.FAILED,
                                status = "Could not read the kernel",
                                stockName = uri.lastPathSegment?.substringAfterLast('/'),
                                stockInfo = boot,
                                stockKernel = KernelInspector.Result(kernelBanner, kernelVersion, arch),
                                detectedFormat = unpacked.format ?: "boot v${boot.headerVersion}",
                                detectedHeaderVersion = unpacked.headerVersion ?: boot.headerVersion,
                                error = unpacked.error
                                    ?: "The kernel version could not be read from this image, " +
                                    "so it cannot be verified as safe to patch.",
                            )
                        }
                        return@launch
                    }

                    _state.update {
                        it.copy(
                            step = Step.IDLE,
                            status = if (kernelVersion != null) "Inspected" else
                                (unpacked.error ?: "Inspected (kernel banner not found)"),
                            stockName = uri.lastPathSegment?.substringAfterLast('/'),
                            stockInfo = boot,
                            stockKernel = KernelInspector.Result(kernelBanner, kernelVersion, arch),
                            // magiskboot's header is authoritative when it parsed.
                            detectedFormat = unpacked.format,
                            detectedOsVersion = unpacked.osVersion
                                ?: boot.androidMajor?.let { "Android $it" },
                            detectedPatchLevel = unpacked.patchLevelFrom(unpacked.osPatchLevel)
                                ?: boot.patchLevelString,
                            detectedHeaderVersion = unpacked.headerVersion ?: boot.headerVersion,
                            inspectError = unpacked.error,
                        )
                    }
                } finally {
                    // Never leave a copy of a multi-megabyte boot image behind.
                    work.deleteRecursively()
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(step = Step.FAILED, error = e.message ?: "Could not read the image.")
                }
            }
        }
    }

    /**
     * Patches [stockUri] with [kernelUri] after proving they match.
     *
     * The compatibility verdict is recomputed here rather than trusted from the
     * UI, so a stale screen can never lead to a mismatched flash.
     */
    fun patch(stockUri: Uri, kernelUri: Uri, repo: String, variant: String?, token: String?) {
        viewModelScope.launch {
            _state.update { it.copy(step = Step.PATCHING, status = "Patching...", error = null) }
            val ctx = getApplication<Application>()
            try {
                val result = withContext(Dispatchers.IO) {
                    val work = File(ctx.cacheDir, "job-${System.nanoTime()}").apply { mkdirs() }
                    try {
                        val stock = File(work, "stock.img").also { f ->
                            ctx.contentResolver.openInputStream(stockUri)!!.use { i ->
                                f.outputStream().use { o -> i.copyTo(o) }
                            }
                        }
                        val kernel = File(work, "kernel").also { f ->
                            ctx.contentResolver.openInputStream(kernelUri)!!.use { i ->
                                f.outputStream().use { o -> i.copyTo(o) }
                            }
                        }

                        val info = RandomAccessFile(stock, "r").use { BootImageParser.parse(it) }

                        // Validate against the unpacked kernel, not the raw
                        // bytes: on GKI images the kernel is compressed, so the
                        // raw scan cannot see the banner and would either read
                        // an empty version or silently compare against the
                        // wrong one.
                        val unpacked = engine.inspect(stock)
                        val rawKernel = RandomAccessFile(stock, "r").use { KernelInspector.inspect(it, info) }
                        val kernelVersion = unpacked.kernelVersion ?: rawKernel.version
                        val arch = unpacked.arch ?: rawKernel.arch

                        if (kernelVersion.isNullOrBlank()) {
                            throw CompatibilityException(
                                listOf(
                                    "Could not read a kernel version from this boot image, so " +
                                        "compatibility cannot be proven.",
                                    unpacked.error
                                        ?: "The kernel may be compressed or the image may not be a boot.img.",
                                )
                            )
                        }

                        val inspected = KernelInspector.Result(
                            unpacked.kernelBanner ?: rawKernel.banner,
                            kernelVersion,
                            arch,
                        )

                        val spec = TargetSpec(
                            androidMajor = info.androidMajor ?: 0,
                            kernelVersion = kernelVersion,
                            osPatchLevel = info.patchLevelString ?: "",
                            bootHeaderVersion = info.headerVersion,
                            arch = arch,
                        )
                        val verdict = CompatibilityChecker.compare(info, inspected, spec)
                        if (!verdict.ok) {
                            throw CompatibilityException(verdict.blocking)
                        }

                        val out = File(ctx.filesDir, "patched-${System.nanoTime()}.img")
                        engine.patch(stock, kernel, out)
                        out to verdict
                    } finally {
                        work.deleteRecursively()
                    }
                }

                _state.update {
                    it.copy(
                        step = Step.DONE,
                        status = "Patched successfully",
                        outputFile = result.first,
                        advisory = result.second.advisory,
                        blocking = emptyList(),
                    )
                }
            } catch (e: CompatibilityException) {
                _state.update {
                    it.copy(
                        step = Step.FAILED,
                        error = "Refusing to patch:\n\n" + e.reasons.joinToString("\n"),
                        blocking = e.reasons,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(step = Step.FAILED, error = e.message ?: "Patching failed.")
                }
            }
        }
    }

    fun resolveArtifact(repo: String, variant: String?, token: String?) {
        viewModelScope.launch {
            val spec = _state.value.stockInfo?.let {
                TargetSpec(
                    androidMajor = it.androidMajor ?: 0,
                    kernelVersion = _state.value.stockKernel?.version ?: "",
                    osPatchLevel = it.patchLevelString ?: "",
                    bootHeaderVersion = it.headerVersion,
                    arch = _state.value.stockKernel?.arch,
                )
            } ?: return@launch

            _state.update { it.copy(status = "Searching $repo for a matching build...") }
            val res = resolver.findLatest(repo, spec, variant, token)
            _state.update { it.copy(status = res.reason) }
        }
    }

    fun reset() {
        _state.value = UiState()
    }
}

class CompatibilityException(val reasons: List<String>) :
    Exception(reasons.joinToString("\n"))