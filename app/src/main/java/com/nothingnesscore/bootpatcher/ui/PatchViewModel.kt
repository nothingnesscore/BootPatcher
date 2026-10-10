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
) {
    val canPatch: Boolean get() = step == Step.IDLE && stockInfo != null
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
                val scanned = try {
                    withContext(Dispatchers.IO) {
                        val tmp = File(work, "stock.img")
                        ctx.contentResolver.openInputStream(uri)!!.use { i ->
                            tmp.outputStream().use { o -> i.copyTo(o) }
                        }
                        RandomAccessFile(tmp, "r").use { raf ->
                            val info = BootImageParser.parse(raf)
                            val kernel = KernelInspector.inspect(raf, info)
                            info to kernel
                        }
                    }
                } finally {
                    work.deleteRecursively()
                }

                val boot = scanned.first
                val kernel = scanned.second

                _state.update {
                    it.copy(
                        step = Step.IDLE,
                        status = "Inspected",
                        stockName = uri.lastPathSegment?.substringAfterLast('/'),
                        stockInfo = boot,
                        stockKernel = kernel,
                    )
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
                        val inspected = RandomAccessFile(stock, "r").use { KernelInspector.inspect(it, info) }

                        val spec = TargetSpec(
                            androidMajor = inspected.version?.substringBefore('.')?.toIntOrNull()
                                ?: info.androidMajor ?: 0,
                            kernelVersion = inspected.version ?: "",
                            osPatchLevel = info.patchLevelString ?: "",
                            bootHeaderVersion = info.headerVersion,
                            arch = inspected.arch,
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