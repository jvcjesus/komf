package snd.komf.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import snd.komf.api.KomfCoreProviders
import snd.komf.api.KomfProviderSeriesId
import snd.komf.api.KomfServerBookId
import snd.komf.api.MediaServer
import snd.komf.api.metadata.KomfIdentifyBookRequest
import kotlin.test.*

class BookIdentifyClientTest {
    @Test fun `client sends an explicit single book request to the book-only endpoint`() = runBlocking<Unit> {
        var calls = 0
        HttpClient(MockEngine { request ->
            calls++
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/komga/metadata/identify/book", request.url.encodedPath)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("book-1", body.getValue("bookId").jsonPrimitive.content)
            assertFalse(body.containsKey("seriesId"))
            assertFalse(body.containsKey("bookIds"))
            respond("""{"jobId":"fixture-job"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            defaultRequest { url("http://fixture.invalid/") }
            install(ContentNegotiation) { json() }
        }.use { http ->
            val result = KomfMetadataClient(http, MediaServer.KOMGA).identifyBook(KomfIdentifyBookRequest(
                KomfServerBookId("book-1"), KomfCoreProviders.MANGA_BAKA, KomfProviderSeriesId("1357")))
            assertEquals("fixture-job", result.jobId.value)
        }
        assertEquals(1, calls)
    }
}
