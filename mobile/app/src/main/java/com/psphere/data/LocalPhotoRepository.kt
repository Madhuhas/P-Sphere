package com.psphere.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.content.ContentResolver
import android.os.Bundle
import android.provider.MediaStore
import com.psphere.network.PhotoDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

data class LocalPhoto(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateTaken: Long,   // epoch millis; 0 if unknown
    val width: Int,
    val height: Int,
    val size: Long,
    val isVideo: Boolean = false,
)

fun LocalPhoto.toPhotoDto(): PhotoDto = PhotoDto(
    id        = id.toString(),
    taken_at  = if (dateTaken > 0) Instant.ofEpochMilli(dateTaken).toString() else null,
    thumb_url = uri.toString(),
    width     = width,
    height    = height,
    camera    = null,
)

object LocalPhotoRepository {

    private val IMAGE_PROJECTION = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.SIZE,
    )

    private val VIDEO_PROJECTION = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATE_TAKEN,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.SIZE,
    )

    suspend fun getCount(context: Context): Int = withContext(Dispatchers.IO) {
        val images = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), null, null, null
        )?.use { it.count } ?: 0

        val videos = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID), null, null, null
        )?.use { it.count } ?: 0

        images + videos
    }

    /**
     * Returns up to [limit] photos+videos starting at [offset], ordered newest-first.
     * Images and videos are merged and re-sorted by date after querying separately.
     */
    suspend fun getPage(context: Context, limit: Int, offset: Int = 0): List<LocalPhoto> =
        withContext(Dispatchers.IO) {
            // Over-fetch to ensure we can fill limit after merge + sort
            val fetchLimit = limit + offset
            val images = queryMediaStore(
                context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                IMAGE_PROJECTION, fetchLimit, isVideo = false
            )
            val videos = queryMediaStore(
                context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                VIDEO_PROJECTION, fetchLimit, isVideo = true
            )
            (images + videos)
                .sortedByDescending { it.dateTaken }
                .drop(offset)
                .take(limit)
        }

    private fun queryMediaStore(
        context: Context,
        uri: android.net.Uri,
        projection: Array<String>,
        limit: Int,
        isVideo: Boolean,
    ): List<LocalPhoto> {
        val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                      else        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(if (isVideo) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN)
                )
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            context.contentResolver.query(uri, projection, args, null)
        } else {
            val sortCol = if (isVideo) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN
            context.contentResolver.query(uri, projection, null, null, "$sortCol DESC")
        }

        return buildList {
            cursor?.use { c ->
                val idCol   = c.getColumnIndexOrThrow(if (isVideo) MediaStore.Video.Media._ID else MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(if (isVideo) MediaStore.Video.Media.DISPLAY_NAME else MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(if (isVideo) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN)
                val wCol    = c.getColumnIndexOrThrow(if (isVideo) MediaStore.Video.Media.WIDTH else MediaStore.Images.Media.WIDTH)
                val hCol    = c.getColumnIndexOrThrow(if (isVideo) MediaStore.Video.Media.HEIGHT else MediaStore.Images.Media.HEIGHT)
                val szCol   = c.getColumnIndexOrThrow(if (isVideo) MediaStore.Video.Media.SIZE else MediaStore.Images.Media.SIZE)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    add(LocalPhoto(
                        id          = id,
                        uri         = ContentUris.withAppendedId(baseUri, id),
                        displayName = c.getString(nameCol) ?: "",
                        dateTaken   = c.getLong(dateCol),
                        width       = c.getInt(wCol),
                        height      = c.getInt(hCol),
                        size        = c.getLong(szCol),
                        isVideo     = isVideo,
                    ))
                }
            }
        }
    }

    // Kept for compatibility with SyncOrchestrator / SyncWorker
    suspend fun getPhotos(context: Context, limit: Int = 500): List<LocalPhoto> =
        getPage(context, limit, 0)
}
