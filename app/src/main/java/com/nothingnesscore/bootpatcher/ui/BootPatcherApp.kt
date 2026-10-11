package com.nothingnesscore.bootpatcher.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nothingnesscore.bootpatcher.ui.theme.BootPatcherTheme
import com.nothingnesscore.bootpatcher.ui.theme.GlassButton
import com.nothingnesscore.bootpatcher.ui.theme.GlassNavigationBar
import com.nothingnesscore.bootpatcher.ui.theme.GlassShapeSmall
import com.nothingnesscore.bootpatcher.ui.theme.GlassSurface

private enum class Tab(val label: String, val icon: ImageVector) {
    PATCH("Patch", Icons.Filled.Build),
    FLAVOR("Flavor", Icons.Filled.FolderOpen),
    SETTINGS("Settings", Icons.Filled.Tune),
}

@Composable
fun BootPatcherApp(vm: PatchViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.PATCH) }
    var blurTop by remember { mutableStateOf(true) }
    var blurBottom by remember { mutableStateOf(true) }

    BootPatcherTheme {
        // Ambient gradient wash so the glass has something to refract.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                        )
                    )
                )
        ) {
            Column(Modifier.fillMaxSize()) {
                // Floating glass title bar, no opaque AppBar.
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(26.dp),
                    blurRadius = if (blurTop) 20.dp else 0.dp,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "BootPatcher",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.Filled.Verified,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.PATCH -> PatchScreen(state, vm)
                        Tab.FLAVOR -> FlavorScreen(state, vm)
                        Tab.SETTINGS -> SettingsScreen(blurTop, { blurTop = it }, blurBottom, { blurBottom = it })
                    }
                }

                Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
                    GlassNavigationBar(
                        Modifier.fillMaxWidth(),
                        blurRadius = if (blurBottom) 20.dp else 0.dp,
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tab.entries.forEach { t ->
                                GlassNavItem(
                                    tab = t,
                                    selected = tab == t,
                                    onSelect = { tab = t },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassNavItem(tab: Tab, selected: Boolean, onSelect: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navTint",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(tab.label, color = tint, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun PatchScreen(state: UiState, vm: PatchViewModel) {
    var stockUri by remember { mutableStateOf<Uri?>(null) }
    var kernelUri by remember { mutableStateOf<Uri?>(null) }

    val stockPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { stockUri = it; vm.inspect(it) } }

    val kernelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { kernelUri = it } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            GlassPanel("1 · Stock boot image", state.stockName ?: "Nothing selected") {
                GlassButton(Modifier.fillMaxWidth(), onClick = { stockPicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose boot.img")
                }
            }
        }

        if (state.stockInfo != null) {
            item { DetectedPanel(state) }
        }

        item {
            GlassPanel("2 · Kernel image", kernelUri?.lastPathSegment ?: "Nothing selected") {
                GlassButton(
                    Modifier.fillMaxWidth(),
                    onClick = { kernelPicker.launch(arrayOf("*/*")) },
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose kernel")
                }
            }
        }

        if (state.blocking.isNotEmpty()) {
            item { BlockingPanel(state.blocking) }
        }

        item {
            GlassButton(
                Modifier.fillMaxWidth(),
                enabled = stockUri != null && kernelUri != null && state.step != Step.PATCHING,
                onClick = {
                    val s = stockUri; val k = kernelUri
                    if (s != null && k != null) vm.patch(s, k, "nothingnesscore/BruhKernel", null, null)
                },
            ) {
                Text(if (state.step == Step.PATCHING) "Patching…" else "Patch boot image")
            }
        }

        state.outputFile?.let { f ->
            item {
                GlassPanel("Done", "${f.name} · ${f.length() / 1024 / 1024} MB") {
                    Text(
                        "Saved to the app folder. Flash it with fastboot or a custom recovery.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        state.error?.let { item { ErrorPanel(it) } }
        state.inspectError?.let { item { WarningPanel(it) } }
    }
}

@Composable
private fun DetectedPanel(state: UiState) {
    GlassPanel(
        "Detected",
        if (state.stockKernel?.version != null) "Stock image read successfully"
        else "Header read, kernel banner not found",
    ) {
        val rows = buildList {
            add("Format" to (state.detectedFormat ?: "boot v${state.detectedHeaderVersion ?: state.stockInfo?.headerVersion}"))
            add("Header" to "v${state.detectedHeaderVersion ?: state.stockInfo?.headerVersion ?: "?"}")
            add("Android" to (state.detectedOsVersion ?: "unknown"))
            add("Patch level" to (state.detectedPatchLevel ?: "unknown"))
            add("Kernel" to (state.stockKernel?.version ?: "not found"))
            add("Arch" to (state.stockKernel?.arch ?: "unknown"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (k, v) -> InfoRow(k, v) }
            state.stockKernel?.banner?.let { b ->
                Spacer(Modifier.height(4.dp))
                Text(
                    b.take(160),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BlockingPanel(items: List<String>) {
    GlassSurface(
        shape = GlassShapeSmall,
        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Cannot patch",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ErrorPanel(message: String) {
    GlassSurface(
        shape = GlassShapeSmall,
        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Error", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WarningPanel(message: String) {
    GlassSurface(
        shape = GlassShapeSmall,
        tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Note", fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FlavorScreen(state: UiState, vm: PatchViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            GlassPanel("Matching builds", state.status.ifBlank { "Inspect a boot image first." }) {
                GlassButton(
                    Modifier.fillMaxWidth(),
                    enabled = state.stockInfo != null,
                    onClick = { vm.resolveArtifact("nothingnesscore/BruhKernel", null, null) },
                ) { Text("Search BruhKernel") }
            }
        }
        item {
            GlassPanel("Strict matching", "Android version, kernel version and security patch level must all agree.") {
                Text(
                    "If nothing matches, fork BruhKernel and build the exact target first.",
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            GlassPanel("Appearance", "Liquid Glass surfaces and blur") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleRow("Blur top bar", blurTop, onBlurTop)
                    ToggleRow("Blur bottom bar", blurBottom, onBlurBottom)
                }
            }
        }
        item {
            GlassPanel("Safety", "Patching is refused on any mismatch") {
                Text(
                    "A kernel built for a different Android version or security patch level " +
                        "will not boot, so BootPatcher stops instead of guessing.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun GlassPanel(title: String, subtitle: String, content: @Composable () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
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
        Text(label, style = MaterialTheme.typography.bodyMedium)
        SwitchPill(checked, onChange)
    }
}

@Composable
private fun SwitchPill(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "switchTrack",
    )
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(track)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onChange(!checked) }
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(Color.White.copy(alpha = 0.92f))
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}