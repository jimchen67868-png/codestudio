# AIDEClone — M1 + M2 + M3: Editor, Compiler, Dex/Package/Sign Pipeline

M1: project file tree + code editor. M2: whole-project ECJ compilation
with inline + log-panel diagnostics. M3 (new): dex + resource/manifest
packaging + signing → an actual installable APK, all pure-JVM (no native
aapt2/apksigner binaries needed).

## What's here
- `MainActivity` — file/project tree, **Compile**, **Build APK**, and
  **Import android.jar** toolbar actions, plus the build-output log panel.
- `EditorActivity` — Sora Editor integration: Java syntax highlighting,
  save-on-exit, inline squiggly diagnostics, jump-to-line.
- `ProjectModel` — file tree + tiny `project.properties` (package name,
  app name, main activity class) that M3's build needs. New project
  skeletons now scaffold a real `android.app.Activity` subclass instead
  of a plain Java class, so the resulting APK is actually launchable.
- `compiler/CompileEngine` — ECJ batch compilation with diagnostics,
  now accepting a classpath (for `android.jar`).
- `compiler/DexEngine` — wraps D8 to turn `.class` files into `classes.dex`.
- `packaging/ApkBuilder` — orchestrates dex → manifest/resources (via
  **ARSCLib**, a pure-Java aapt2 replacement) → zip → sign.
- `packaging/KeystoreManager` — generates and caches a self-signed debug
  signing key on-device using Bouncy Castle (Android's runtime lacks the
  JDK's own X.509 cert-builder classes, so plain `java.security` can't do
  this the way desktop `keytool` does).

## M3 workflow
1. **Import android.jar** (one-time): tap the toolbar action, pick a file.
   You need this from an actual Android SDK — e.g.
   `$ANDROID_HOME/platforms/android-34/android.jar` on a desktop install,
   or wherever your device's SDK/Termux setup keeps one. Without it,
   Compile will fail on any `android.app.Activity` reference — the plain
   JDK classpath ECJ uses by default has no idea what that class is.
2. **Compile** — as in M2, but now with `android.jar` on the classpath.
3. **Build APK** — runs Compile first; on success, dexes, packages, and
   signs. Prompts to install via the system installer (needs "install
   unknown apps" permission granted to this app, once).

## M3 known limitations
- No `res/` folder support yet (drawables, layouts, string resources
  beyond `app_name`) — ARSCLib supports building these, just not wired up.
  Good M4 candidate.
- No multi-dex — fine for small sample projects, will break on anything
  with >64K methods across dependencies.
- Signing is debug-only (self-signed, on-device generated key) — not
  suitable for Play Store distribution.
- `ApkBuilder`'s manifest only declares the main activity — no other
  components, permissions, or metadata from the source project.

## M2 notes / known limitations
- Diagnostics are parsed from ECJ's human-readable console output via
  regex, not from structured `IProblem` callbacks — simpler to embed, but
  means we only get line numbers, not exact columns. Inline squiggles
  therefore underline the whole line rather than just the bad token.
  Upgrading to ECJ's lower-level `Compiler` API with a custom
  `ICompilerRequestor` would fix this — worth doing before M3.
- Compilation always targets the **whole project**, not the single open
  file — there's no per-file "Compile this file" shortcut yet.
- Output goes to `.class` files under `<project>/build/classes`; nothing
  is dexed or packaged yet (that's M3).
- No incremental compilation — every Compile tap is a full rebuild.

## Setup
1. Open the `AIDEClone/` folder in Android Studio (Koala/2024.1+ recommended).
2. Let it sync — Studio will generate the Gradle wrapper jar automatically
   (I couldn't fetch it here since this environment has no network access).
3. Run on a device/emulator, API 24+.

First launch seeds a `SampleProject` in the app's private storage
(`filesDir/projects`) so the tree isn't empty.

## Known gaps / next milestones
- **No compilation.** Tapping a file just edits and saves text.
- **No Gradle-style build model.** `ProjectModel.createNewProject` writes a
  single stub `.java` file, nothing else.
- **Storage permission requested but unused yet** — kept for M2+ when you'll
  want to browse/import projects from shared storage, not just app-private.
- Kotlin file support isn't wired into Sora Editor yet — only `JavaLanguage()`
  is attached; `.kt` files open as plain text for now.

## Next: M4
Run/install feedback loop: logcat viewer for the installed app, real
`res/` folder compilation (layouts, drawables, string resources) via
ARSCLib, and multi-dex support.
