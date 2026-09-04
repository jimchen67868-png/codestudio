# R8 keep rules for AIDEClone.
#
# Goal: enable minification specifically to shrink kotlin-compiler-
# embeddable (a huge jar bundling large chunks of IntelliJ platform code
# we never actually reach). Everything else here is defensive — keeping
# libraries that are complex, reflection-heavy, and would very likely
# break in confusing, hard-to-diagnose ways (silent ClassNotFoundException
# at runtime, not a build-time error) if R8 tried to shrink/rename them
# too. This is a genuine experiment: none of it has been runtime-tested.

# --- Our own app code: keep entirely, it's small and we want real
# stack traces if something goes wrong. ---
-keep class com.example.aideclone.** { *; }

# --- ECJ (Java compiler): complex internal wiring, not what we're
# trying to shrink. Keep fully. ---
-keep class org.eclipse.jdt.** { *; }
-dontwarn org.eclipse.jdt.**

# --- D8/R8 itself (used at runtime by DexEngine to dex compiled
# projects) — ironic to be shrinking an app that itself embeds a dexer,
# but this is a separate runtime dependency, not related to the build
# tool shrinking us right now. Keep fully; it's reflection-heavy. ---
-keep class com.android.tools.r8.** { *; }
-dontwarn com.android.tools.r8.**

# --- ARSCLib (resource/manifest packaging) ---
-keep class com.reandroid.** { *; }
-dontwarn com.reandroid.**

# --- Bouncy Castle: JCE provider lookups are reflection-based
# (Class.forName against algorithm names), a classic case R8 can't trace
# statically and will silently break without a keep rule. ---
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Sora Editor: language definitions may be looked up reflectively. ---
-keep class io.github.rosemoe.** { *; }
-dontwarn io.github.rosemoe.**

# --- Our own SourceVersion stub — must survive with its exact
# package/class name, since ECJ looks it up by that literal name. ---
-keep class javax.lang.model.SourceVersion { *; }

# --- kotlin-compiler-embeddable: deliberately NOT blanket-kept — this is
# the library we actually want R8 to shrink. Only suppressing build-time
# "unresolved reference" warnings, which are expected and normal for a
# library this large referencing optional/desktop-only dependencies we
# don't have (this is exactly what -dontwarn is for, and doesn't affect
# what gets kept vs stripped). If shrinking breaks actual compilation at
# runtime, that'll show up as errors from the compiler entry point call
# itself, and specific -keep rules can be added for whatever turns out to
# be reflectively accessed. ---
-dontwarn org.jetbrains.kotlin.**
-dontwarn com.intellij.**
-dontwarn org.jetbrains.org.objectweb.asm.**
-dontwarn com.google.protobuf.**

# General Android keep rules AGP already applies by default for
# manifest-declared components (activities, providers, etc.) — no need
# to duplicate those here.
