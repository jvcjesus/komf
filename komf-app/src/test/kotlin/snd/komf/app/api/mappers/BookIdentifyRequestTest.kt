package snd.komf.app.api.mappers

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import snd.komf.api.metadata.KomfIdentifyBookRequest
import kotlin.test.*

class BookIdentifyRequestTest {
    @Test fun `request identifies one book and an explicit provider series`() {
        val request = Json.decodeFromString<KomfIdentifyBookRequest>("""{"bookId":"book-1","provider":"MANGA_BAKA","providerSeriesId":"1357"}""")
        assertEquals("book-1", request.bookId.value)
        assertEquals("1357", request.providerSeriesId.value)
        assertNull(request.libraryId)
        val roundTrip = Json.decodeFromString<KomfIdentifyBookRequest>(Json.encodeToString(KomfIdentifyBookRequest.serializer(), request))
        assertEquals(request, roundTrip)
    }

    @Test fun `request rejects batches and missing provider identity`() {
        for (body in listOf(
            """{"bookId":["book-1","book-2"],"provider":"MANGA_BAKA","providerSeriesId":"1357"}""",
            """{"bookId":"book-1","provider":"MANGA_BAKA"}""",
            """{"bookIds":["book-1","book-2"],"provider":"MANGA_BAKA","providerSeriesId":"1357"}""")) {
            assertFailsWith<SerializationException> { Json.decodeFromString<KomfIdentifyBookRequest>(body) }
        }
    }
}
