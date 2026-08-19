# AIDEClone — M1 + M2: Editor, File Browser, On-Device Compiler

M1: project file tree + code editor. M2 (new): whole-project compilation
via ECJ, with errors shown both as inline squiggles in the editor and in
a bottom build-output log panel.

## What's here
- `MainActivity` — file/project tree (RecyclerView, expand/collapse folders,
  "New Project" FAB, "Compile" toolbar action, and the build-output log
  panel with a tap-to-jump diagnostics list).
- `EditorActivity` — opens a tapped file into **Sora Editor**
  (`io.github.Rosemoe.sora-editor`), with Java syntax highlighting,
  save-on-exit, inline squiggly diagnostics from the last compile run, and
  jump-to-line when opened from the log panel.
- `ProjectModel` — flattens a directory into a displayable tree; this is
  the seam where M3's real project/build model will replace the current
  bare-bones skeleton generator.
- `compiler/CompileEngine` — wraps ECJ (`org.eclipse.jdt:ecj`) in batch
  mode, compiling every `.java` file under the project root to
  `<project>/build/classes`, and parses ECJ's console output into
  structured `CompileDiagnostic`s.
- `compiler/CompileResultStore` — in-memory singleton bridging the last
  compile result from `MainActivity` to whichever file `EditorActivity`
  opens next.

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

## Next: M3
Full dex + resource pipeline: run `d8` over the compiled `.class` files,
compile resources with `aapt2`, and assemble/sign an installable APK.
