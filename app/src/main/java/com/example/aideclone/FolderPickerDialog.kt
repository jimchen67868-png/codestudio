package com.example.aideclone

import android.app.AlertDialog
import android.content.Context
import java.io.File

/**
 * Minimal in-app directory browser for picking where a project lives —
 * either an existing folder to open, or a parent folder to create a new
 * project inside. Deliberately plain java.io.File based (not SAF/
 * DocumentFile) since the whole compile/dex/package pipeline needs real
 * filesystem paths anyway (ECJ, D8, and ARSCLib all take java.io.File,
 * not content:// URIs). Browsing outside this app's own sandbox needs
 * the MANAGE_EXTERNAL_STORAGE permission granted (requested in the
 * manifest, but the user still has to flip it on in system settings).
 */
object FolderPickerDialog {

    fun show(context: Context, startDir: File, title: String, onPicked: (File) -> Unit) {
        val start = if (startDir.exists() && startDir.isDirectory) {
            startDir
        } else {
            android.os.Environment.getExternalStorageDirectory()
        }
        showForDir(context, start, title, onPicked)
    }

    private fun showForDir(context: Context, dir: File, title: String, onPicked: (File) -> Unit) {
        val rawList = dir.listFiles()
        val subdirs = rawList
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        val items = mutableListOf<String>()
        val targets = mutableListOf<File?>()
        if (dir.parentFile != null) {
            items.add("⬆  ..")
            targets.add(dir.parentFile)
        }
        if (rawList == null) {
            // listFiles() returns null (not an empty array) specifically
            // when the directory can't be read — usually a permission
            // problem (e.g. browsing outside the app's sandbox without
            // "All files access" granted in system settings).
            items.add("⚠️  Can't read this folder (permission denied?)")
            targets.add(null)
        }
        for (sub in subdirs) {
            items.add("📁 ${sub.name}")
            targets.add(sub)
        }

        AlertDialog.Builder(context)
            .setTitle("$title\n${dir.absolutePath}")
            .setItems(items.toTypedArray()) { _, which ->
                val target = targets[which]
                if (target != null) showForDir(context, target, title, onPicked)
            }
            .setPositiveButton("Use This Folder") { _, _ -> onPicked(dir) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
