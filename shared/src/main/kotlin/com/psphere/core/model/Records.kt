package com.psphere.core.model

/** Which files a backup folder includes. */
enum class Include { MEDIA, ALL }

enum class Media {
    PHOTO, VIDEO, OTHER;

    companion object {
        private val PHOTO_EXT = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "dng", "raw", "cr2", "nef", "arw", "avif")
        private val VIDEO_EXT = setOf("mp4", "mov", "m4v", "3gp", "mkv", "webm", "avi")

        fun of(fileName: String): Media = when (fileName.substringAfterLast('.', "").lowercase()) {
            in PHOTO_EXT -> PHOTO
            in VIDEO_EXT -> VIDEO
            else -> OTHER
        }
    }
}

/** Where an item stands on the device it came from. */
enum class ItemState {
    /** The file is on its original device. */
    PRESENT,
    /** Deleted with Free up space: copies exist elsewhere and the device keeps a preview. */
    FREED,
    /** Deleted on the original device some other way. Backups keep their copies. */
    DELETED,
}

/**
 * One entry in the replicated catalog.
 *
 * Every record belongs to the device that wrote it ([origin]) and only that device ever changes it.
 * Each device numbers its own writes with an increasing [seq]; replicas keep the highest seq per
 * key. Because there is only one writer per record, replicas can never conflict.
 */
sealed class Rec {
    abstract val origin: String
    abstract val seq: Long
    /** Unique within [origin]. */
    abstract val key: String
    abstract fun withSeq(seq: Long): Rec
}

/** The origin device's display name. */
data class DeviceRec(
    override val origin: String,
    override val seq: Long,
    val name: String,
) : Rec() {
    override val key get() = "d"
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/** A backup folder on [origin] and the devices it goes to. The local folder location is not replicated. */
data class RuleRec(
    override val origin: String,
    override val seq: Long,
    val ruleId: String,
    val label: String,
    val destinations: Set<String>,
    val include: Include,
    val showInLibrary: Boolean,
    val removed: Boolean = false,
) : Rec() {
    override val key get() = "r:$ruleId"
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/** A file in one of [origin]'s backup folders. */
data class ItemRec(
    override val origin: String,
    override val seq: Long,
    val ruleId: String,
    /** Path inside the backup folder, '/'-separated. */
    val relPath: String,
    val hash: String,
    val size: Long,
    val modified: Long,
    /** Capture time when the platform knows it, otherwise [modified]. */
    val takenAt: Long,
    val media: Media,
    val state: ItemState,
) : Rec() {
    override val key get() = itemKey(ruleId, relPath)
    override fun withSeq(seq: Long) = copy(seq = seq)

    companion object {
        fun itemKey(ruleId: String, relPath: String) = "i:$ruleId/$relPath"
    }
}

/** [origin] has (or no longer has) a full copy of the file with [hash]. */
data class HoldRec(
    override val origin: String,
    override val seq: Long,
    val hash: String,
    val present: Boolean,
) : Rec() {
    override val key get() = "h:$hash"
    override fun withSeq(seq: Long) = copy(seq = seq)
}
