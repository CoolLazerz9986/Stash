// Path: android/app/src/main/java/app/stash/android/FileSource.kt
package app.stash.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat

data class BackupFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String,
    val modified: Long, // milliseconds, 0 = the folder did not say
)

object Perms {
    fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** Lists the files in the folders the user picked (Storage Access Framework, so no storage permission is needed). */
object FileSource {
    private const val MAX_DEPTH = 24

    /**
     * Newest first. Hidden files and folders (names starting with a dot) are left out.
     * Throws SecurityException if access to a picked folder is gone, and [UploadCancelled] if [cancelled] turns true.
     */
    fun list(ctx: Context, p: Prefs, cancelled: () -> Boolean): List<BackupFile> {
        val offTypes = p.offTypes
        val excluded = FileFilter.parseExtensions(p.excludedExtensions)
        val since = if (p.includeExisting) 0L else p.enabledSince
        val seen = HashSet<String>() // two picked folders may overlap: take each document once
        val out = ArrayList<BackupFile>()
        for (text in p.fileTrees) {
            val tree = Uri.parse(text)
            fun walk(docId: String, depth: Int) {
                if (cancelled()) throw UploadCancelled()
                for (e in WaSource.children(ctx, tree, docId)) {
                    if (e.name.startsWith(".")) continue
                    if (e.dir) {
                        if (depth < MAX_DEPTH) walk(e.id, depth + 1)
                    } else if (e.size > 0 && (e.modified == 0L || e.modified >= since) &&
                        FileFilter.accepts(e.name, e.mime, offTypes, excluded) && seen.add("${tree.authority}|${e.id}")
                    ) {
                        out.add(BackupFile(e.uri, e.name, e.size, e.mime.ifEmpty { "application/octet-stream" }, e.modified))
                    }
                }
            }
            walk(DocumentsContract.getTreeDocumentId(tree), 0)
        }
        out.sortByDescending { it.modified }
        return out
    }
}
