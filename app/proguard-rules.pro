# R8 rules for the release build.
#
# Most libraries here (Compose, Hilt, Room, Firebase, ML Kit, OkHttp, Coil,
# Media3) ship their own consumer rules, so this file only covers the two things
# R8 cannot reason about on its own: names that outlive the build, and code
# reached from native or reflection.

# ── Crash reports ────────────────────────────────────────────────────────
# Without these, Play Console stack traces are unreadable line-number soup.
# renamesourcefileattribute still hides the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── kotlinx.serialization ────────────────────────────────────────────────
# These names escape the build in two ways that R8 can't see:
#   1. VideoAttachment is written to disk as JSON in `diary_entries.videos`.
#      A property name IS the JSON key, so renaming it makes every row already
#      on a user's device fail to parse — silent data loss on update.
#   2. EchoDestinations routes are serialized into navigation arguments.
# Keeping the annotated classes, their members, and the compiler-generated
# $$serializer / Companion pairs keeps both stable.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-keep @kotlinx.serialization.Serializable class com.dhaval.echo.** { *; }
-keep,includedescriptorclasses class com.dhaval.echo.**$$serializer { *; }
-keepclassmembers class com.dhaval.echo.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── ONNX Runtime (Whisper transcription + e5 embeddings) ─────────────────
# The JNI layer looks these classes and members up by name from native code,
# which R8 cannot trace, so shrinking them produces UnsatisfiedLinkError /
# NoSuchMethodError at the first inference instead of at build time.
-keep class ai.onnxruntime.** { *; }
-keep class ai.onnxruntime.extensions.** { *; }
-dontwarn ai.onnxruntime.**

# ── WorkManager ──────────────────────────────────────────────────────────
# Workers are constructed by the framework, not by any call site R8 can see.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# ── Enums ────────────────────────────────────────────────────────────────
# values()/valueOf() are used reflectively by Room type converters and
# kotlinx.serialization when reading persisted enum columns.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
