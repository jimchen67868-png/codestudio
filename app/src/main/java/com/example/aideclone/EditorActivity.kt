package com.example.aideclone

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aideclone.compiler.CompileDiagnostic
import com.example.aideclone.compiler.CompileResultStore
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.io.File

/**
 * M1/M2 editor screen: opens a file into Sora Editor, applies Java syntax
 * highlighting, saves on exit, and — new in M2 — overlays inline squiggly
 * diagnostics from the last project-wide Compile run (see
 * CompileResultStore) plus jumps to a specific line when opened from the
 * build-output log panel.
 *
 * Note: Sora Editor's diagnostic API takes character offsets, not
 * line/column, so we resolve each diagnostic's 1-based line number to a
 * char range spanning that full line via the editor's Content object.
 * That's coarser than pointing at the exact token, but ECJ's console
 * output doesn't reliably give us column ranges to do better without a
 * deeper (non-console) integration — a good candidate for a later pass.
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var editor: CodeEditor
    private lateinit var currentFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        editor = findViewById(R.id.codeEditor)
        setSupportActionBar(findViewById(R.id.editorToolbar))

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
            ?: run { finish(); return }
        currentFile = File(path)
        title = currentFile.name

        loadFile()

        if (currentFile.extension == "java") {
            editor.setEditorLanguage(JavaLanguage())
        }
        editor.colorScheme = EditorColorScheme()

        applyDiagnostics()

        val gotoLine = intent.getIntExtra(EXTRA_GOTO_LINE, -1)
        if (gotoLine > 0) {
            editor.post { jumpToLine(gotoLine) }
        }
    }

    private fun loadFile() {
        val text = if (currentFile.exists()) currentFile.readText() else ""
        editor.setText(text)
    }

    private fun saveFile() {
        try {
            currentFile.writeText(editor.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyDiagnostics() {
        val diags = CompileResultStore.diagnosticsFor(currentFile.absolutePath)
        if (diags.isEmpty()) return

        val container = DiagnosticsContainer()
        val content = editor.text
        val lineCount = content.lineCount

        for (diag in diags) {
            val lineIndex = (diag.line - 1).coerceIn(0, lineCount - 1)
            val lineLength = content.getColumnCount(lineIndex)
            val start = content.getCharIndex(lineIndex, 0)
            val end = content.getCharIndex(lineIndex, lineLength)
            if (end <= start) continue

            val severity = when (diag.severity) {
                CompileDiagnostic.Severity.ERROR -> DiagnosticRegion.SEVERITY_ERROR
                CompileDiagnostic.Severity.WARNING -> DiagnosticRegion.SEVERITY_WARNING
            }
            container.addDiagnostic(
                DiagnosticRegion(start, end, severity, 0, DiagnosticDetail(diag.message))
            )
        }
        editor.diagnostics = container
    }

    private fun jumpToLine(line: Int) {
        val content = editor.text
        val lineIndex = (line - 1).coerceIn(0, content.lineCount - 1)
        editor.setSelection(lineIndex, 0)
        editor.ensurePositionVisible(lineIndex, 0)
    }

    override fun onPause() {
        super.onPause()
        if (::currentFile.isInitialized) saveFile()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, "Save")
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_SAVE) {
            saveFile()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_GOTO_LINE = "extra_goto_line"
        private const val MENU_SAVE = 1
    }
}
