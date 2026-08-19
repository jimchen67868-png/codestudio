package com.example.aideclone

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aideclone.compiler.CompileDiagnostic
import com.example.aideclone.compiler.CompileEngine
import com.example.aideclone.compiler.CompileResultStore
import com.example.aideclone.compiler.DiagnosticsAdapter
import java.io.File
import java.util.concurrent.Executors

/**
 * M1/M2 entry point: file/project tree, "Compile" action that runs ECJ
 * over the whole project on a background thread, and a build-output log
 * panel that lists diagnostics and jumps into the editor on tap.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileTreeAdapter
    private var projectModel: ProjectModel? = null

    private lateinit var logPanel: View
    private lateinit var diagnosticsRecycler: RecyclerView
    private lateinit var diagnosticsAdapter: DiagnosticsAdapter

    private val compileExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val MENU_COMPILE = 1
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
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_COMPILE) {
            runCompile()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun runCompile() {
        val project = projectModel ?: return
        Toast.makeText(this, "Compiling…", Toast.LENGTH_SHORT).show()

        compileExecutor.execute {
            val result = CompileEngine.compileProject(project.rootDir)
            CompileResultStore.update(result)

            runOnUiThread {
                diagnosticsAdapter.submitList(result.diagnostics)
                logPanel.visibility = View.VISIBLE
                val summary = if (result.success && result.diagnostics.none {
                        it.severity == CompileDiagnostic.Severity.ERROR
                    }) {
                    "Compile succeeded"
                } else {
                    val errorCount = result.diagnostics.count { it.severity == CompileDiagnostic.Severity.ERROR }
                    "Compile finished with $errorCount error(s)"
                }
                Toast.makeText(this, summary, Toast.LENGTH_SHORT).show()
            }
        }
    }

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
        compileExecutor.shutdown()
    }
}
