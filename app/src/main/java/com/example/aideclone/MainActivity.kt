package com.example.aideclone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aideclone.compiler.CompileDiagnostic
import com.example.aideclone.compiler.CompileEngine
import com.example.aideclone.compiler.CompileResultStore
import com.example.aideclone.compiler.DiagnosticsAdapter
import com.example.aideclone.compiler.KotlinCompileEngine
import com.example.aideclone.packaging.ApkBuilder
import java.io.File
import java.util.concurrent.Executors

/**
 * M1/M2/M3 entry point: file/project tree, Compile (ECJ), Build APK
 * (dex + package + sign, M3), and Install APK, plus the build-output log
 * panel shared by both Compile and Build.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileTreeAdapter
    private var projectModel: ProjectModel? = null

    private lateinit var logPanel: View
    private lateinit var diagnosticsRecycler: RecyclerView
    private lateinit var diagnosticsAdapter: DiagnosticsAdapter

    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    // Where an imported android.jar (needed to compile real Activity
    // subclasses) is cached. See importAndroidJarLauncher below.
    private val sdkJarFile: File by lazy { File(filesDir, "sdk/android.jar") }

    // Bundled at build time (see app/build.gradle.kts' bundleKotlinStdlib
    // task) as an asset, extracted to a real file here on first use since
    // the Kotlin compiler needs an actual classpath-usable jar, not an
    // APK-internal asset stream.
    private val kotlinStdlibFile: File by lazy { File(filesDir, "sdk/kotlin-stdlib.jar") }

    private fun ensureKotlinStdlib(): File {
        if (!kotlinStdlibFile.exists()) {
            kotlinStdlibFile.parentFile?.mkdirs()
            assets.open("kotlin-stdlib.jar").use { input ->
                kotlinStdlibFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return kotlinStdlibFile
    }

    private val importAndroidJarLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importAndroidJar(uri)
        }

    private val prefs by lazy { getSharedPreferences("aideclone", MODE_PRIVATE) }

    companion object {
        private const val MENU_COMPILE = 1
        private const val MENU_BUILD_APK = 2
        private const val MENU_IMPORT_SDK = 3
        private const val MENU_RAW_OUTPUT = 4
        private const val MENU_OPEN_PROJECT = 5
        private const val MENU_STORAGE_PERMISSION = 6
        private const val PREF_LAST_PROJECT_PATH = "last_project_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        recyclerView = findViewById(R.id.fileTreeRecycler)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = FileTreeAdapter(
            onFileClick = { node -> openFile(node.file) },
            onDirClick = { node -> toggleDir(node) }
        )
        recyclerView.adapter = adapter

        logPanel = findViewById(R.id.logPanel)
        diagnosticsRecycler = findViewById(R.id.diagnosticsRecycler)
        diagnosticsRecycler.layoutManager = LinearLayoutManager(this)
        diagnosticsAdapter = DiagnosticsAdapter { diag -> openDiagnostic(diag) }
        diagnosticsRecycler.adapter = diagnosticsAdapter

        findViewById<View>(R.id.logPanelClose).setOnClickListener {
            logPanel.visibility = View.GONE
        }

        findViewById<View>(R.id.fabNewProject).setOnClickListener {
            promptNewProject()
        }

        val defaultProjectsDir = File(filesDir, "projects").apply { mkdirs() }
        val sampleProjectDir = File(defaultProjectsDir, "SampleProject")
        if (!sampleProjectDir.exists()) {
            ProjectModel.createNewProject(defaultProjectsDir, "SampleProject", "com.example.sample")
        }

        // Reopen whatever project was last open, if it still exists —
        // otherwise fall back to the bundled sample project.
        val lastPath = prefs.getString(PREF_LAST_PROJECT_PATH, null)
        val startDir = if (lastPath != null && File(lastPath).isDirectory) File(lastPath) else sampleProjectDir
        loadProject(startDir)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_COMPILE, 0, "Compile")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_BUILD_APK, 1, "Build APK")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_IMPORT_SDK, 2, "Import android.jar")
        menu.add(0, MENU_RAW_OUTPUT, 3, "Show Raw Compiler Output")
        menu.add(0, MENU_OPEN_PROJECT, 4, "Open Project")
        menu.add(0, MENU_STORAGE_PERMISSION, 5, "Grant Storage Access")
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_COMPILE -> { runCompile { }; true }
            MENU_BUILD_APK -> { runBuildApk(); true }
            MENU_IMPORT_SDK -> { importAndroidJarLauncher.launch(arrayOf("*/*")); true }
            MENU_RAW_OUTPUT -> {
                val raw = CompileResultStore.lastResult?.rawOutput
                showBuildLog(if (raw.isNullOrBlank()) "No compile run yet, or ECJ produced no console output." else raw)
                true
            }
            MENU_OPEN_PROJECT -> {
                val start = projectModel?.rootDir?.parentFile ?: android.os.Environment.getExternalStorageDirectory()
                FolderPickerDialog.show(this, start, "Open Project") { picked ->
                    loadProject(picked)
                    Toast.makeText(this, "Opened ${picked.name}", Toast.LENGTH_SHORT).show()
                }
                true
            }
            MENU_STORAGE_PERMISSION -> {
                requestStorageAccess()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun requestStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // "All files access" is a special app-level setting, not a
            // normal runtime permission — it doesn't show up on the
            // app's own Permissions page on most Android skins. This
            // intent jumps straight to the correct toggle for this
            // specific app, sidestepping OEM navigation differences.
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    // Some OEMs don't support the per-app variant of this
                    // intent — fall back to the general all-files-access
                    // list, where the user picks AIDEClone manually.
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Pre-API 30: legacy runtime storage permissions instead.
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                1001
            )
        }
    }

    // ---- Compile (M2) ----

    private fun runCompile(onDone: (success: Boolean) -> Unit) {
        val project = projectModel ?: return

        val androidJar = if (sdkJarFile.exists()) sdkJarFile else null
        if (androidJar == null) {
            Toast.makeText(
                this,
                "No android.jar imported yet — see Import android.jar.",
                Toast.LENGTH_LONG
            ).show()
        }

        showProgress("Compiling…")

        backgroundExecutor.execute {
            try {
                // Language detection: route to the Kotlin compiler if the
                // project has any .kt files, otherwise ECJ (Java). Mixed
                // Java+Kotlin projects aren't supported yet — that needs a
                // proper kapt-style stub-generation pipeline, out of scope
                // for now.
                val hasKotlin = project.rootDir.walkTopDown()
                    .any { it.isFile && it.extension == "kt" && !it.path.contains("/build/") }

                val result = if (hasKotlin) {
                    val stdlib = ensureKotlinStdlib()
                    KotlinCompileEngine.compileProject(project.rootDir, androidJar, stdlib)
                } else {
                    CompileEngine.compileProject(project.rootDir, androidJar)
                }
                CompileResultStore.update(result)

                runOnUiThread {
                    hideProgress()
                    diagnosticsAdapter.submitList(result.diagnostics)
                    logPanel.visibility = View.VISIBLE
                    val errorCount = result.diagnostics.count { it.severity == CompileDiagnostic.Severity.ERROR }
                    // CompileEngine.success now checks for internal-crash
                    // markers and verifies .class files actually landed on
                    // disk, not just ECJ's raw return code — trustworthy
                    // again after the -bootclasspath / NoClassDefFoundError
                    // fix (see CompileEngine's class doc).
                    val success = result.success
                    Toast.makeText(
                        this,
                        if (success) "Compile succeeded" else "Compile finished with $errorCount error(s)",
                        Toast.LENGTH_SHORT
                    ).show()
                    onDone(success)
                }
            } catch (t: Throwable) {
                // Belt-and-suspenders: CompileEngine already catches
                // Throwable internally, but this guards anything outside
                // it (e.g. CompileResultStore, adapter updates) so a bug
                // there shows an error dialog instead of crashing the app.
                runOnUiThread {
                    hideProgress()
                    showBuildLog("Compile crashed:\n${t.stackTraceToString()}")
                    onDone(false)
                }
            }
        }
    }

    // ---- Build APK (M3) ----

    private fun runBuildApk() {
        runCompile { compileSucceeded ->
            if (!compileSucceeded) return@runCompile
            val project = projectModel ?: return@runCompile

            showProgress("Building APK (dex + package + sign)…")

            backgroundExecutor.execute {
                try {
                    val signingDir = File(filesDir, "signing")
                    val result = ApkBuilder.build(
                        projectRoot = project.rootDir,
                        packageName = project.packageName,
                        mainActivityClass = project.mainActivityClass,
                        appName = project.appName,
                        signingStorageDir = signingDir
                    )

                    runOnUiThread {
                        hideProgress()
                        if (result.success && result.apkFile != null) {
                            Toast.makeText(this, "APK built: ${result.apkFile.name}", Toast.LENGTH_LONG).show()
                            promptInstall(result.apkFile)
                        } else {
                            Toast.makeText(this, "Build failed — see log (also saved to build/build-log.txt)", Toast.LENGTH_LONG).show()
                            refreshList()
                            showBuildLog(result.log)
                        }
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        hideProgress()
                        showBuildLog("Build crashed:\n${t.stackTraceToString()}")
                    }
                }
            }
        }
    }

    private var progressDialog: android.app.ProgressDialog? = null

    private fun showProgress(message: String) {
        hideProgress()
        progressDialog = android.app.ProgressDialog(this).apply {
            setMessage(message)
            setCancelable(false)
            show()
        }
    }

    private fun hideProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun showBuildLog(log: String) {
        AlertDialog.Builder(this)
            .setTitle("Build Output")
            .setMessage(log)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun promptInstall(apkFile: File) {
        AlertDialog.Builder(this)
            .setTitle("Build succeeded")
            .setMessage("Install ${apkFile.name}?")
            .setPositiveButton("Install") { _, _ -> installApk(apkFile) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Import android.jar ----

    private fun importAndroidJar(uri: Uri) {
        Toast.makeText(this, "Importing android.jar…", Toast.LENGTH_SHORT).show()
        backgroundExecutor.execute {
            try {
                sdkJarFile.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    sdkJarFile.outputStream().use { output -> input.copyTo(output) }
                }
                runOnUiThread {
                    Toast.makeText(this, "android.jar imported (${sdkJarFile.length() / 1024} KB)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---- File tree / project management ----

    private fun openDiagnostic(diag: CompileDiagnostic) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, diag.filePath)
        intent.putExtra(EditorActivity.EXTRA_GOTO_LINE, diag.line)
        startActivity(intent)
    }

    private fun loadProject(dir: File) {
        projectModel = ProjectModel(dir)
        prefs.edit().putString(PREF_LAST_PROJECT_PATH, dir.absolutePath).apply()
        refreshList()
    }

    private fun refreshList() {
        adapter.submitList(projectModel?.buildVisibleList() ?: emptyList())
    }

    private fun toggleDir(node: FileNode) {
        projectModel?.toggleExpand(node)
        refreshList()
    }

    private fun openFile(file: File) {
        if (!file.isFile) return
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, file.absolutePath)
        startActivity(intent)
    }

    private fun promptNewProject() {
        val input = EditText(this).apply { hint = "ProjectName" }
        val languageGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            addView(android.widget.RadioButton(this@MainActivity).apply {
                text = "Java"
                id = View.generateViewId()
                isChecked = true
            })
            addView(android.widget.RadioButton(this@MainActivity).apply {
                text = "Kotlin"
                id = View.generateViewId()
            })
        }
        val kotlinRadioId = languageGroup.getChildAt(1).id

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
            addView(languageGroup)
        }
        AlertDialog.Builder(this)
            .setTitle("New Project")
            .setView(container)
            .setPositiveButton("Choose Location…") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Project name required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val language = if (languageGroup.checkedRadioButtonId == kotlinRadioId) {
                    ProjectModel.Companion.Language.KOTLIN
                } else {
                    ProjectModel.Companion.Language.JAVA
                }
                val defaultParent = File(filesDir, "projects")
                FolderPickerDialog.show(this, defaultParent, "Save New Project In") { parentDir ->
                    val pkg = "com.example." + name.lowercase().replace(Regex("[^a-z0-9]"), "")
                    val model = ProjectModel.createNewProject(parentDir, name, pkg, language)
                    loadProject(model.rootDir)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideProgress()
        backgroundExecutor.shutdown()
    }
}
