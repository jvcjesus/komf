package snd.komf.mediaserver.metadata

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import snd.komf.mediaserver.model.MediaServerThumbnailId
import snd.komf.model.Image

/** A null replacement means preserve, never reset. Upload must succeed before removal. */
internal suspend fun replaceManagedThumbnail(
    existing: MediaServerThumbnailId?,
    replacement: Image?,
    upload: suspend (Image) -> MediaServerThumbnailId?,
    remove: suspend (MediaServerThumbnailId) -> Unit,
): MediaServerThumbnailId? {
    if (replacement == null || replacement.bytes.isEmpty()) return existing
    val uploaded = try {
        upload(replacement) ?: return existing
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        KotlinLogging.logger {}.warn { "Thumbnail upload failed; existing cover retained" }
        return existing
    }
    if (existing != null && existing != uploaded) {
        try {
            remove(existing)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the successful new upload tracked even if old-image cleanup failed.
            KotlinLogging.logger {}.warn { "Previous thumbnail cleanup failed after successful upload" }
        }
    }
    return uploaded
}
