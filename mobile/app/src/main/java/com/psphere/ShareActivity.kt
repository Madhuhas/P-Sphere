package com.psphere

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.psphere.backup.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "PSphere Shelf" in Android's Share menu. Puts the shared text, link or files on the Shelf and
 * closes, so the user stays in the app they shared from. Has no screen of its own.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streams = streamsOf(intent)
        if (streams.isNotEmpty()) Toast.makeText(this, "Putting it on the Shelf…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            // The files are read before this screen closes: the permission to read them ends with it.
            val (added, failed) = withContext(Dispatchers.IO) {
                Engine.start(applicationContext)
                put(streams, intent.getStringExtra(Intent.EXTRA_TEXT))
            }
            if (added > 0) Engine.shareShelf()
            val message = when {
                added == 0 -> "Couldn't put that on the Shelf"
                failed > 0 -> "Put $added on the Shelf. $failed couldn't be read."
                added == 1 -> "Put on the Shelf"
                else -> "Put $added things on the Shelf"
            }
            Toast.makeText(this@ShareActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun streamsOf(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    /** Returns how many were put on the Shelf and how many couldn't be read. */
    private fun put(streams: List<Uri>, text: String?): Pair<Int, Int> {
        if (streams.isEmpty()) return (if (text != null && Engine.node.shelfAddText(text) != null) 1 to 0 else 0 to 1)
        var added = 0
        var failed = 0
        for (uri in streams) {
            val ok = runCatching {
                contentResolver.openInputStream(uri)!!.use { Engine.node.shelfAddFile(displayName(uri), it) }
            }.isSuccess
            if (ok) added++ else failed++
        }
        return added to failed
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "File"
}
