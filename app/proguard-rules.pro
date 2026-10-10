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

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile