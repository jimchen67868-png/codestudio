package com.example.aideclone

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * In-app directory browser for picking where a project lives — either an
 * existing folder to open, or a parent folder to create a new project
 * inside. Deliberately plain java.io.File based (not SAF/DocumentFile)
 * since the whole compile/dex/package pipeline needs real filesystem
 * paths anyway (ECJ, D8, and ARSCLib all take java.io.File, not
 * content:// URIs). Browsing outside this app's own sandbox needs the
 * MANAGE_EXTERNAL_STORAGE permission granted (requested in the manifest,
 * but the user still has to flip it on in system settings).
 */
object FolderPickerDialog {

    /**
     * Entry point: shows a storage-volume chooser first (App Storage,
     * Internal Storage, and any SD card / USB storage the device
     * reports), then hands off to the folder browser once a starting
     * point is picked. This is what actually makes an SD card reachable
     * — it has its own volume root (e.g. /storage/1234-5678/), which
     * isn't a parent/child of primary shared storage, so plain "Up"
     * navigation from /storage/emulated/0 would never find it.
     */
    fun show(context: Context, appDefaultDir: File, title: String, onPicked: (File) -> Unit) {
        val roots = mutableListOf<Pair<String, File>>()
        roots.add("App Storage (always accessible)" to appDefaultDir)

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        storageManager?.storageVolumes?.forEach { volume ->
            val dir = volumeRootDir(volume)
            if (dir != null) {
                val label = if (volume.isPrimary) {
                    "Internal Storage"
                } else {
                    volume.getDescription(context) ?: "SD Card"
                }
                roots.add(label to dir)
            }
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(roots.map { it.first }.toTypedArray()) { _, which ->
                showForDir(context, roots[which].second, title, onPicked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Resolves a StorageVolume to a real java.io.File root, across API levels. */
    private fun volumeRootDir(volume: StorageVolume): File? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory
        } else {
            // StorageVolume.getPathFile()/getPath() were @hide pre-API30
            // but present at runtime — reflection is the standard way
            // apps have accessed this on older Android versions.
            try {
                val method = volume.javaClass.getMethod("getPathFile")
                method.invoke(volume) as? File
            } catch (e: Exception) {
                try {
                    val method = volume.javaClass.getMethod("getPath")
                    (method.invoke(volume) as? String)?.let { File(it) }
                } catch (e2: Exception) {
                    null
                }
            }
        }
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
