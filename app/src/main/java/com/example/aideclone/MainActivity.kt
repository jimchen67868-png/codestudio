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

    private val importAndroidJarLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importAndroidJar(uri)
        }

    companion object {
        private const val MENU_COMPILE = 1
        private const val MENU_BUILD_APK = 2
        private const val MENU_IMPORT_SDK = 3
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
        if (defaultProjectsDir.listFiles().isNullOrEmpty()) {
            ProjectModel.createNewProject(defaultProjectsDir, "SampleProject", "com.example.sample")
        }
        loadProject(defaultProjectsDir)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_COMPILE, 0, "Compile")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_BUILD_APK, 1, "Build APK")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_IMPORT_SDK, 2, "Import android.jar")
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_COMPILE -> { runCompile { }; true }
            MENU_BUILD_APK -> { runBuildApk(); true }
            MENU_IMPORT_SDK -> { importAndroidJarLauncher.launch(arrayOf("*/*")); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---- Compile (M2) ----

    private fun runCompile(onDone: (success: Boolean) -> Unit) {
        val project = projectModel ?: return

        val classpath = if (sdkJarFile.exists()) listOf(sdkJarFile) else emptyList()
        if (classpath.isEmpty()) {
            Toast.makeText(
                this,
                "No android.jar imported yet — Activity classes won't resolve. See Import android.jar.",
                Toast.LENGTH_LONG
            ).show()
        }

        showProgress("Compiling…")

        backgroundExecutor.execute {
            try {
                val result = CompileEngine.compileProject(project.rootDir, classpath)
                CompileResultStore.update(result)

                runOnUiThread {
                    hideProgress()
                    diagnosticsAdapter.submitList(result.diagnostics)
                    logPanel.visibility = View.VISIBLE
                    val errorCount = result.diagnostics.count { it.severity == CompileDiagnostic.Severity.ERROR }
                    // Deliberately NOT using result.success (ECJ's raw
                    // BatchCompiler.compile() return value) here — with
                    // -proceedOnError set, it seems to report non-success
                    // completion even on a genuinely clean compile (0
                    // parsed errors), which silently blocked Build APK
                    // from ever proceeding. The diagnostics list is the
                    // real ground truth we already show the user, so
                    // trust that instead.
                    val success = errorCount == 0
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
                            Toast.makeText(this, "Build failed — see log", Toast.LENGTH_LONG).show()
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
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("New Project")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Project name required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val projectsDir = File(filesDir, "projects")
                val pkg = "com.example." + name.lowercase().replace(Regex("[^a-z0-9]"), "")
                val model = ProjectModel.createNewProject(projectsDir, name, pkg)
                loadProject(model.rootDir)
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
