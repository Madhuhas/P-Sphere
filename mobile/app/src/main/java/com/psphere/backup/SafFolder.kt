package com.psphere.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.psphere.core.storage.FileInfo
import com.psphere.core.storage.Folder
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * A folder picked with Android's system folder picker (a SAF tree URI). Works for any folder the
 * user can pick, without broad storage permissions, and lets PSphere write and delete there.
 *
 * Uses DocumentsContract queries directly: DocumentFile is far too slow for folders with
 * thousands of photos. Document IDs are cached by path and refreshed when an entry disappears.
 */
class SafFolder(context: Context, val treeUri: Uri) : Folder {
    private val resolver: ContentResolver = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val ids = ConcurrentHashMap<String, String>().apply { put("", rootId) }

    private data class Child(val id: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    override val displayName: String get() = describe(rootId)

    fun uriOf(relPath: String): Uri? = find(relPath)?.let(::docUri)

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val out = ArrayList<Child>()
        resolver.query(uri, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Child(
                    id = c.getString(0),
                    name = c.getString(1) ?: continue,
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }
        return out
    }

    /** Document ID for [relPath], or null if it doesn't exist. */
    private fun find(relPath: String): String? {
        ids[relPath]?.let { return it }
        var parentPath = ""
        var parentId = rootId
        for (segment in relPath.split('/').filter { it.isNotEmpty() }) {
            val path = if (parentPath.isEmpty()) segment else "$parentPath/$segment"
            val known = ids[path]
            val id = known ?: run {
                val kids = children(parentId)
                kids.forEach { ids[join(parentPath, it.name)] = it.id }
                kids.firstOrNull { it.name == segment }?.id
            } ?: return null
            parentPath = path
            parentId = id
        }
        return parentId
    }

    private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

    private fun forget(relPath: String) {
        ids.keys.removeIf { it == relPath || it.startsWith("$relPath/") }
    }

    override fun list(): List<FileInfo> {
        val out = ArrayList<FileInfo>()
        fun walk(id: String, prefix: String) {
            for (c in children(id)) {
                if (c.name.startsWith(".")) continue
                val rel = join(prefix, c.name)
                ids[rel] = c.id
                if (c.isDir) walk(c.id, rel) else out += FileInfo(rel, c.size, c.modified)
            }
        }
        walk(rootId, "")
        return out
    }

    override fun info(relPath: String): FileInfo? {
        repeat(2) { attempt ->
            val id = find(relPath) ?: return null
            resolver.query(docUri(id), PROJECTION, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (c.getString(2) == Document.MIME_TYPE_DIR) return null
                    return FileInfo(relPath, if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
                }
            }
            // The cached ID went stale (file deleted or renamed outside PSphere): look it up again.
            forget(relPath)
            if (attempt == 1) return null
        }
        return null
    }

    override fun openRead(relPath: String): InputStream {
        val id = find(relPath) ?: throw FileNotFoundException(relPath)
        return resolver.openInputStream(docUri(id)) ?: throw IOException("Couldn't open $relPath")
    }

    override fun openWrite(relPath: String, append: Boolean): OutputStream {
        val id = find(relPath) ?: createFile(relPath)
        return resolver.openOutputStream(docUri(id), if (append) "wa" else "wt") ?: throw IOException("Couldn't write $relPath")
    }

    private fun createFile(relPath: String): String {
        val parent = relPath.substringBeforeLast('/', "")
        val name = relPath.substringAfterLast('/')
        val parentId = ensureDir(parent)
        // octet-stream keeps the name exactly as given (no extension is added).
        val uri = DocumentsContract.createDocument(resolver, docUri(parentId), "application/octet-stream", name)
            ?: throw IOException("Couldn't create $relPath")
        val id = DocumentsContract.getDocumentId(uri)
        ids[relPath] = id
        return id
    }

    private fun ensureDir(relPath: String): String {
        if (relPath.isEmpty()) return rootId
        find(relPath)?.let { return it }
        val parentId = ensureDir(relPath.substringBeforeLast('/', ""))
        val uri = DocumentsContract.createDocument(resolver, docUri(parentId), Document.MIME_TYPE_DIR, relPath.substringAfterLast('/'))
            ?: throw IOException("Couldn't create folder $relPath")
        return DocumentsContract.getDocumentId(uri).also { ids[relPath] = it }
    }

    override fun move(from: String, to: String): Boolean {
        if (find(to) != null) return false
        val srcId = find(from) ?: return false
        val fromParent = from.substringBeforeLast('/', "")
        val toParent = to.substringBeforeLast('/', "")
        val newName = to.substringAfterLast('/')
        return try {
            var uri = docUri(srcId)
            if (fromParent != toParent) {
                val target = ensureDir(toParent)
                uri = DocumentsContract.moveDocument(resolver, uri, docUri(find(fromParent)!!), docUri(target)) ?: return copyMove(from, to)
            }
            if (from.substringAfterLast('/') != newName) {
                uri = DocumentsContract.renameDocument(resolver, uri, newName) ?: return false
            }
            forget(from)
            ids[to] = DocumentsContract.getDocumentId(uri)
            true
        } catch (e: Exception) {
            copyMove(from, to)
        }
    }

    /** Fallback for providers that can't move between folders. */
    private fun copyMove(from: String, to: String): Boolean = runCatching {
        openRead(from).use { input -> openWrite(to).use { input.copyTo(it, 64 * 1024) } }
        delete(from)
    }.getOrDefault(false)

    override fun delete(relPath: String): Boolean {
        val id = find(relPath) ?: return true
        val ok = runCatching { DocumentsContract.deleteDocument(resolver, docUri(id)) }.getOrDefault(false)
        if (ok) forget(relPath)
        return ok
    }

    override fun freeSpace(): Long = runCatching {
        // Tree IDs look like "primary:DCIM/Camera" (internal storage) or "1A2B-3C4D:Photos" (SD card).
        val volume = rootId.substringBefore(':')
        val dir = if (volume == "primary") Environment.getExternalStorageDirectory() else java.io.File("/storage/$volume")
        StatFs(dir.path).availableBytes
    }.getOrDefault(0L)

    companion object {
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        /** "primary:DCIM/Camera" → "Internal storage/DCIM/Camera". */
        fun describe(treeDocId: String): String {
            val volume = treeDocId.substringBefore(':')
            val path = treeDocId.substringAfter(':', "")
            val base = if (volume == "primary") "Internal storage" else "SD card"
            return if (path.isEmpty()) base else "$base/$path"
        }

        fun describe(uri: Uri): String = runCatching { describe(DocumentsContract.getTreeDocumentId(uri)) }.getOrDefault(uri.toString())

        /**
         * True if two folder locations (tree URIs) are the same folder or one is inside the other,
         * e.g. "primary:Pictures" and "primary:Pictures/PSphere".
         */
        fun overlaps(a: String, b: String): Boolean {
            val ua = Uri.parse(a); val ub = Uri.parse(b)
            if (ua.authority != ub.authority) return false
            val x = runCatching { DocumentsContract.getTreeDocumentId(ua) }.getOrNull() ?: return false
            val y = runCatching { DocumentsContract.getTreeDocumentId(ub) }.getOrNull() ?: return false
            // A volume root's ID ends in ':' ("primary:"); its children are "primary:DCIM".
            fun inside(child: String, parent: String) = child.startsWith(if (parent.endsWith(":")) parent else "$parent/")
            return x == y || inside(x, y) || inside(y, x)
        }
    }
}
