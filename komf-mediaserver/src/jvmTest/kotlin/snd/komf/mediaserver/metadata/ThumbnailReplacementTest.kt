package snd.komf.mediaserver.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import snd.komf.mediaserver.model.MediaServerThumbnailId
import snd.komf.model.Image
import kotlin.test.*

class ThumbnailReplacementTest {
    private val old = MediaServerThumbnailId("existing")
    private val new = MediaServerThumbnailId("replacement")
    private val image = Image(byteArrayOf(1, 2, 3))

    @Test fun `missing disabled invalid or download failure never uploads or deletes`() = runBlocking<Unit> {
        for (replacement in listOf(null, Image(byteArrayOf()))) {
            assertEquals(old, replaceManagedThumbnail(old, replacement,
                upload = { error("Must not upload") }, remove = { error("Must not delete") }))
        }
    }

    @Test fun `upload refusal or exception preserves existing cover`() = runBlocking<Unit> {
        assertEquals(old, replaceManagedThumbnail(old, image,
            upload = { null }, remove = { error("Must not delete") }))
        assertEquals(old, replaceManagedThumbnail(old, image,
            upload = { throw java.io.IOException("fixture") }, remove = { error("Must not delete") }))
    }

    @Test fun `successful upload precedes cleanup and returns new tracked id`() = runBlocking<Unit> {
        val events = mutableListOf<String>()
        assertEquals(new, replaceManagedThumbnail(old, image,
            upload = { events += "upload"; new }, remove = { assertEquals(old, it); events += "delete" }))
        assertEquals(listOf("upload", "delete"), events)
    }

    @Test fun `cleanup failure still tracks successful upload`() = runBlocking<Unit> {
        assertEquals(new, replaceManagedThumbnail(old, image,
            upload = { new }, remove = { throw java.io.IOException("fixture") }))
    }

    @Test fun `cancellation propagates without deleting existing cover`() = runBlocking<Unit> {
        assertFailsWith<CancellationException> {
            replaceManagedThumbnail(old, image,
                upload = { throw CancellationException("fixture") }, remove = { error("Must not delete") })
        }
    }
}
