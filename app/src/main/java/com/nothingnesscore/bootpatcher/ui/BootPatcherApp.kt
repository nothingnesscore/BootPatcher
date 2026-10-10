package com.nothingnesscore.bootpatcher.ui

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nothingnesscore.bootpatcher.ui.theme.BootPatcherTheme

private enum class Tab(val label: String) { PATCH("Patch"), FLAVOR("Flavor"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BootPatcherApp(vm: PatchViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.PATCH) }
    var blurTop by remember { mutableStateOf(true) }
    var blurBottom by remember { mutableStateOf(true) }
    var stockUri by remember { mutableStateOf<Uri?>(null) }
    var kernelUri by remember { mutableStateOf<Uri?>(null) }

    BootPatcherTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("BootPatcher", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (blurTop)
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                        else MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.statusBarsPadding(),
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = if (blurBottom)
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                    else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.PATCH -> Icons.Filled.Build
                                        Tab.FLAVOR -> Icons.Filled.Folder
                                        Tab.SETTINGS -> Icons.Filled.Settings
                                    },
                                    contentDescription = t.label,
                                )
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (tab) {
                    Tab.PATCH -> PatchScreen(
                        state = state,
                        stockUri = stockUri,
                        kernelUri = kernelUri,
                        onStock = { u -> stockUri = u; vm.inspect(u) },
                        onKernel = { u -> kernelUri = u },
                        onPatch = { s, k -> vm.patch(s, k, "nothingnesscore/BruhKernel", null, null) },
                        onReset = vm::reset,
                    )
                    Tab.FLAVOR -> FlavorScreen(state = state, vm = vm)
                    Tab.SETTINGS -> SettingsScreen(blurTop, { blurTop = it }, blurBottom, { blurBottom = it })
                }
            }
        }
    }
}

/** Rounded translucent surface used across the app for the "liquid" look. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        ),
    ) { Box(Modifier.padding(18.dp)) { content() } }
}

@Composable
private fun PatchScreen(
    state: UiState,
    stockUri: Uri?,
    kernelUri: Uri?,
    onStock: (Uri) -> Unit,
    onKernel: (Uri) -> Unit,
    onPatch: (Uri, Uri) -> Unit,
    onReset: () -> Unit,
) {
    val pickers = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { if (stockUri == null) onStock(it) else onKernel(it) } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Stock boot image", fontWeight = FontWeight.SemiBold)
                Text(
                    state.stockName ?: "No image selected",
                    style = MaterialTheme.typography.bodyMedium,
                )
                androidx.compose.material3.TextButton(onClick = { pickers.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose boot.img")
                }
            }
        }

        state.stockInfo?.let { info ->
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Detected", fontWeight = FontWeight.SemiBold)
                    InfoRow("Header", "v${info.headerVersion}")
                    InfoRow("Android", "Android ${info.androidMajor ?: "?"}")
                    InfoRow("Patch level", info.patchLevelString ?: "unknown")
                    InfoRow("Kernel", state.stockKernel?.version ?: "unreadable")
                    InfoRow("Arch", state.stockKernel?.arch ?: "unknown")
                }
            }
        }

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. Kernel image", fontWeight = FontWeight.SemiBold)
                Text(
                    kernelUri?.lastPathSegment?.substringAfterLast('/') ?: "No kernel selected",
                    style = MaterialTheme.typography.bodyMedium,
                )
                androidx.compose.material3.TextButton(onClick = { pickers.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose kernel")
                }
            }
        }

        if (state.blocking.isNotEmpty()) {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Cannot patch",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.blocking.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        androidx.compose.material3.Button(
            onClick = { stockUri?.let { s -> kernelUri?.let { k -> onPatch(s, k) } } },
            enabled = stockUri != null && kernelUri != null && state.step != Step.PATCHING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.step == Step.PATCHING) {
                CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text("Patch boot image")
        }

        if (state.step == Step.DONE && state.outputFile != null) {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Done", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Saved ${state.outputFile.name} (${state.outputFile.length() / 1024 / 1024} MB)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    androidx.compose.material3.TextButton(onClick = onReset) { Text("Start over") }
                }
            }
        }

        state.error?.let { err ->
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Error", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    Text(err, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FlavorScreen(state: UiState, vm: PatchViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Matching builds", fontWeight = FontWeight.SemiBold)
                Text(
                    state.status.ifBlank { "Inspect a stock boot image first, then look for a matching BruhKernel build." },
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.Button(
                    onClick = { vm.resolveArtifact("nothingnesscore/BruhKernel", null, null) },
                    enabled = state.stockInfo != null,
                ) { Text("Search BruhKernel") }
            }
        }
        GlassCard {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Builds are matched on Android version, kernel version and security patch " +
                        "level together. If nothing matches, fork BruhKernel and build the " +
                        "exact target first.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    blurTop: Boolean,
    onBlurTop: (Boolean) -> Unit,
    blurBottom: Boolean,
    onBlurBottom: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appearance", fontWeight = FontWeight.SemiBold)
                ToggleRow("Blur top bar", blurTop, onBlurTop)
                ToggleRow("Blur bottom bar", blurBottom, onBlurBottom)
                Text(
                    "Colours follow the system Monet palette where available.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Safety", fontWeight = FontWeight.SemiBold)
                Text(
                    "BootPatcher refuses to patch when the Android version, kernel version, " +
                        "security patch level, header version or architecture do not match. " +
                        "A wrong kernel here means a bootloop.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}