# The boot image parser and compatibility checker are the safety critical
# path: if R8 removed or renamed them the app would silently stop validating,
# so they are kept explicitly along with their data types.
-keep class com.nothingnesscore.bootpatcher.core.BootImageParser { *; }
-keep class com.nothingnesscore.bootpatcher.core.BootImageInfo { *; }
-keep class com.nothingnesscore.bootpatcher.core.OsVersion { *; }
-keep class com.nothingnesscore.bootpatcher.core.CompatibilityChecker { *; }
-keep class com.nothingnesscore.bootpatcher.core.TargetSpec { *; }
-keep class com.nothingnesscore.bootpatcher.core.Verdict { *; }
-keep class com.nothingnesscore.bootpatcher.core.KernelInspector { *; }
-keep class com.nothingnesscore.bootpatcher.core.MagiskbootEngine { *; }
-keep class com.nothingnesscore.bootpatcher.core.ArtifactResolver { *; }

# The UI layer is reached from Compose, which resolves it at runtime by name
# through saved state and the ViewModel store. R8 cannot see those edges, so
# without these rules it strips the activity and view model from the release
# dex entirely and the app launches to nothing.
-keep class com.nothingnesscore.bootpatcher.ui.MainActivity { *; }
-keep class com.nothingnesscore.bootpatcher.ui.BootPatcherAppKt { *; }
-keep class com.nothingnesscore.bootpatcher.ui.PatchViewModel { *; }
-keep class com.nothingnesscore.bootpatcher.ui.UiState { *; }
-keep class com.nothingnesscore.bootpatcher.ui.Step { *; }

# The glass primitives are @Composable entry points resolved by name.
-keep class com.nothingnesscore.bootpatcher.ui.theme.GlassKt { *; }

# Compose reads LocalLifecycleOwner reflectively; keep the owner plumbing that
# provides it.
-keep class androidx.lifecycle.compose.** { *; }
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt { *; }

# Kotlin coroutines internals used by viewModelScope.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile