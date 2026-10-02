package com.example.aideclone

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * In-app file browser for picking a single file (android.jar,
 * framework-res.apk, a library .jar/.aar) — same philosophy and
 * conventions as FolderPickerDialog (plain java.io.File, the same
 * StorageManager/StorageVolume multi-volume root handling, same
 * permission-denied handling), extended with a sort order the picker
 * itself remembers.
 *
 * Built specifically to replace the system document picker
 * (ACTION_OPEN_DOCUMENT) for these three import actions, because that
 * picker has no API for remembering sort order at all — there's no
 * Intent extra for it, and whatever sort memory it has is entirely up
 * to the picker app handling it, outside this app's control. A real
 * device test confirmed it kept resetting to "File name (A to Z)" on
 * every import despite repeatedly choosing "Modified (newest first)".
 * Building our own picker gives us a sort order WE remember in
 * SharedPreferences, same as every other remembered setting here.
 */
object FilePickerDialog {

    private const val PREF_SORT_ORDER = "file_picker_sort_order"

    private enum class SortOrder(val label: String) {
        NAME_ASC("File name (A to Z)"),
        NAME_DESC("File name (Z to A)"),
        MODIFIED_NEWEST("Modified (newest first)"),
        MODIFIED_OLDEST("Modified (oldest first)");

        companion object {
            fun fromName(name: String?): SortOrder =
                entries.firstOrNull { it.name == name } ?: NAME_ASC
        }
    }

    private fun sortFiles(files: List<File>, order: SortOrder): List<File> = when (order) {
        SortOrder.NAME_ASC -> files.sortedBy { it.name.lowercase() }
        SortOrder.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
        SortOrder.MODIFIED_NEWEST -> files.sortedByDescending { it.lastModified() }
        SortOrder.MODIFIED_OLDEST -> files.sortedBy { it.lastModified() }
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

    private fun resolveRoots(context: Context, appDefaultDir: File): List<Pair<String, File>> {
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
        return roots
    }

    /**
     * @param appDefaultDir shown as a first, always-accessible option,
     *        same as FolderPickerDialog — useful since browsing outside
     *        the app's sandbox needs MANAGE_EXTERNAL_STORAGE granted.
     * @param extensionFilter if non-null, only files whose extension
     *        (case-insensitive, no leading dot) is in this list are
     *        shown/selectable; directories are always shown.
     */
    fun show(
        context: Context,
        appDefaultDir: File,
        prefs: SharedPreferences,
        title: String,
        extensionFilter: List<String>? = null,
        onPicked: (File) -> Unit
    ) {
        val roots = resolveRoots(context, appDefaultDir)
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(roots.map { it.first }.toTypedArray()) { _, which ->
                showForDir(context, roots[which].second, prefs, title, extensionFilter, onPicked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Same as [show], but lets the user check any number of files within
     * a single directory before confirming — e.g. picking several
     * library jars at once. Checked state does NOT carry across
     * navigating into a different folder; everything resets per
     * directory shown, to keep this simple.
     */
    fun showMulti(
        context: Context,
        appDefaultDir: File,
        prefs: SharedPreferences,
        title: String,
        extensionFilter: List<String>? = null,
        onPicked: (List<File>) -> Unit
    ) {
        val roots = resolveRoots(context, appDefaultDir)
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(roots.map { it.first }.toTypedArray()) { _, which ->
                showMultiForDir(context, roots[which].second, prefs, title, extensionFilter, onPicked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Shared listing logic for both the single-pick and multi-pick browsers. */
    private fun listEntries(dir: File, extensionFilter: List<String>?, order: SortOrder): Pair<List<String>, List<File?>> {
        val rawList = dir.listFiles()
        val subdirs = sortFiles(
            rawList?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList(),
            order
        )
        val matchingFiles = sortFiles(
            rawList?.filter {
                it.isFile && !it.name.startsWith(".") &&
                    (extensionFilter == null || it.extension.lowercase() in extensionFilter)
            } ?: emptyList(),
            order
        )

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
        for (file in matchingFiles) {
            items.add("📄 ${file.name} (${file.length() / 1024} KB)")
            targets.add(file)
        }
        return items to targets
    }

    private fun showForDir(
        context: Context,
        dir: File,
        prefs: SharedPreferences,
        title: String,
        extensionFilter: List<String>?,
        onPicked: (File) -> Unit
    ) {
        val currentOrder = SortOrder.fromName(prefs.getString(PREF_SORT_ORDER, null))
        val (items, targets) = listEntries(dir, extensionFilter, currentOrder)

        AlertDialog.Builder(context)
            .setTitle("$title\n${dir.absolutePath}")
            .setItems(items.toTypedArray()) { _, which ->
                val target = targets[which]
                when {
                    target == null -> {}
                    target.isDirectory -> showForDir(context, target, prefs, title, extensionFilter, onPicked)
                    else -> onPicked(target)
                }
            }
            .setNeutralButton("Sort: ${currentOrder.label}") { _, _ ->
                showSortMenu(context, prefs) { showForDir(context, dir, prefs, title, extensionFilter, onPicked) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Multi-select variant: directories still navigate on tap; files
     * toggle a checkbox instead of picking immediately, confirmed via
     * the positive button. AlertDialog's multi-choice list fires its
     * click listener for every row including directories, so a
     * directory tap is special-cased to dismiss and navigate rather
     * than being treated as a selection toggle.
     */
    private fun showMultiForDir(
        context: Context,
        dir: File,
        prefs: SharedPreferences,
        title: String,
        extensionFilter: List<String>?,
        onPicked: (List<File>) -> Unit
    ) {
        val currentOrder = SortOrder.fromName(prefs.getString(PREF_SORT_ORDER, null))
        val (items, targets) = listEntries(dir, extensionFilter, currentOrder)
        val checked = BooleanArray(items.size)

        var dialogRef: AlertDialog? = null
        val builder = AlertDialog.Builder(context)
            .setTitle("$title\n${dir.absolutePath}\n(check files, then Select)")
            .setMultiChoiceItems(items.toTypedArray(), checked) { _, which, isChecked ->
                val target = targets[which]
                when {
                    target == null -> checked[which] = false
                    target.isDirectory -> {
                        // Navigating away - dismiss this dialog instead of
                        // treating the tap as a selection.
                        dialogRef?.dismiss()
                        showMultiForDir(context, target, prefs, title, extensionFilter, onPicked)
                    }
                    else -> checked[which] = isChecked
                }
            }
            .setPositiveButton("Select") { _, _ ->
                val picked = targets.indices
                    .filter { checked[it] && targets[it]?.isFile == true }
                    .mapNotNull { targets[it] }
                if (picked.isNotEmpty()) onPicked(picked)
            }
            .setNeutralButton("Sort: ${currentOrder.label}") { _, _ ->
                showSortMenu(context, prefs) { showMultiForDir(context, dir, prefs, title, extensionFilter, onPicked) }
            }
            .setNegativeButton("Cancel", null)
        dialogRef = builder.show()
    }

    private fun showSortMenu(context: Context, prefs: SharedPreferences, onChanged: () -> Unit) {
        val labels = SortOrder.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Sort by")
            .setItems(labels) { _, which ->
                prefs.edit().putString(PREF_SORT_ORDER, SortOrder.entries[which].name).apply()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
