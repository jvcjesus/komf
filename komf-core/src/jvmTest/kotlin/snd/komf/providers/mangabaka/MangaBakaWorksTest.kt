package snd.komf.providers.mangabaka

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import snd.komf.mangabaka.external.MangaBakaApiClient
import snd.komf.mangabaka.model.*
import snd.komf.model.*
import snd.komf.providers.*
import snd.komf.util.NameSimilarityMatcher
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class MangaBakaWorksTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/mangabaka/$name.json")).readText()
    private val series get() = json.decodeFromString<MangaBakaSeries>(resource("series"))
    private val collections get() = json.decodeFromString<List<MangaBakaCollection>>(resource("collections"))
    private val digital get() = json.decodeFromString<List<MangaBakaWork>>(resource("digital-works"))
    private val paperback get() = json.decodeFromString<List<MangaBakaWork>>(resource("paperback-works"))
    private val target = MangaBakaCollectionTarget("en", "Standard Edition", "digital")
    private val id = ProviderSeriesId("1357")
    private val expected = listOf(
        "019e1d1b-b538-708a-945d-09649968641f", "019e1d1b-b5a6-7d63-805d-3f0bf39f8685",
        "019e1d1b-bcd4-77f9-a6eb-12318da0333b", "019e1d1b-b581-7936-a9d1-5d6cbd1ceb70",
        "019e1d1b-b594-7742-9560-1d7fd70daa50", "019e1d1b-bcdd-78b7-aedb-41743f8a88fa",
        "019e1d1b-bcc8-71d7-b864-fcbb51753381"
    )

    private fun source(cols: List<MangaBakaCollection> = collections, works: List<MangaBakaWork>? = null) =
        object : MangaBakaDataSource {
            override suspend fun search(title: String, types: List<MangaBakaType>?, typesNot: List<MangaBakaType>?) = listOf(series)
            override suspend fun getSeries(id: MangaBakaSeriesId) = series
            override suspend fun getCollections(id: MangaBakaSeriesId) = cols
            override suspend fun getWorks(collectionId: String) = works ?: if (collectionId == collections[1].id) digital else paperback
        }

    private fun provider(
        data: MangaBakaDataSource = source(), selection: MangaBakaCollectionTarget? = target,
        client: HttpClient? = null, bookConfig: BookMetadataConfig = BookMetadataConfig(),
    ) = MangaBakaMetadataProvider(data,
        MangaBakaMetadataMapper(SeriesMetadataConfig(), listOf(AuthorRole.WRITER), listOf(AuthorRole.PENCILLER)),
        NameSimilarityMatcher.nameSimilarityMatcher(NameSimilarityMatcher.NameMatchingMode.EXACT),
        null, MediaType.MANGA, selection, bookConfig, client)

    @Test fun `English digital collection has seven books despite thirteen in parent`() = runBlocking<Unit> {
        val p = provider()
        assertEquals("13", series.finalVolume)
        val result = p.getSeriesMetadata(id)
        assertEquals(7, result.metadata.totalBookCount)
        assertEquals(expected, result.books.map { it.id.id })
        assertEquals((1..7).map { it.toDouble() }, result.books.map { it.number?.start })
        for ((index, book) in result.books.withIndex()) {
            assertEquals(book.number, snd.komf.util.BookNameParser.getVolumes("Goodnight Punpun v%02d.cbz".format(index + 1)))
            val metadata = p.getBookMetadata(id, book.id)
            assertEquals(book.id, metadata.id)
            assertEquals(id, metadata.seriesId)
            assertEquals(digital[index].identifiers.single().id, metadata.metadata.isbn)
            assertEquals(digital[index].releaseDate, metadata.metadata.releaseDate.toString())
            assertEquals(digital[index].description?.text("desc"), metadata.metadata.summary)
        }
    }

    @Test fun `paperback selection stays separate and has no borrowed digital covers`() = runBlocking<Unit> {
        val p = provider(selection = target.copy(medium = "paperback"))
        val result = p.getSeriesMetadata(id)
        assertEquals(paperback.map { it.id }, result.books.map { it.id.id })
        assertEquals(7, result.metadata.totalBookCount)
        assertNull(p.getBookMetadata(id, result.books.first().id).metadata.thumbnail)
        assertFailsWith<IllegalStateException> { p.getBookMetadata(id, ProviderBookId(expected.first())) }
    }

    @Test fun `ambiguous absent and incomplete targets fail closed`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { provider(source(collections + collections[1])).getSeriesMetadata(id) }
        for (selection in listOf(target.copy(language = "fr"), target.copy(edition = "Omnibus Edition"), target.copy(medium = ""))) {
            assertFailsWith<IllegalArgumentException> { provider(selection = selection).getSeriesMetadata(id) }
        }
        assertFailsWith<IllegalArgumentException> { provider(source(works = digital + digital[0])).getSeriesMetadata(id) }
        assertFailsWith<IllegalArgumentException> { provider(source(works = listOf(digital[0], digital[1].copy(sequenceNumeric = 1.0)))).getSeriesMetadata(id) }
    }

    @Test fun `zero and missing collection counts never fall back to thirteen`() = runBlocking<Unit> {
        for (count in listOf(0, null)) {
            val cols = listOf(collections[1].copy(countMain = count))
            assertEquals(count, provider(source(cols)).getSeriesMetadata(id).metadata.totalBookCount)
        }
        assertEquals(13, provider(selection = null).getSeriesMetadata(id).metadata.totalBookCount)
    }

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    @Test fun `each thumbnail request is for the exact work image`() = runBlocking<Unit> {
        val requested = mutableListOf<String>()
        val bytes = png()
        HttpClient(MockEngine { request -> requested += request.url.toString(); respond(bytes) }).use { client ->
            val p = provider(client = client)
            for (work in digital) assertContentEquals(bytes, p.getBookMetadata(id, ProviderBookId(work.id)).metadata.thumbnail?.bytes)
        }
        assertEquals(digital.map { it.images.single().image.raw?.url }, requested)
    }

    @Test fun `missing foreign disabled malformed and failed covers are optional`() = runBlocking<Unit> {
        var requests = 0
        HttpClient(MockEngine { requests++; respond("not an image", HttpStatusCode.OK) }).use { client ->
            val original = digital[0]
            val image = original.images.single()
            val invalidImages = listOf(emptyList(), digital[1].images,
                listOf(image.copy(seriesId = 999)), listOf(image.copy(language = "ja")),
                listOf(image.copy(type = "series")))
            for (images in invalidImages) {
                val work = original.copy(images = images)
                assertNull(provider(source(works = listOf(work)), client = client).getBookMetadata(id, ProviderBookId(work.id)).metadata.thumbnail)
            }
            assertEquals(0, requests)
            assertNull(provider(client = client, bookConfig = BookMetadataConfig(thumbnail = false)).getBookMetadata(id, ProviderBookId(expected[0])).metadata.thumbnail)
            assertEquals(0, requests)
            assertNull(provider(client = client).getBookMetadata(id, ProviderBookId(expected[0])).metadata.thumbnail)
        }
        HttpClient(MockEngine { respond("failure", HttpStatusCode.ServiceUnavailable) }).use { client ->
            assertNull(provider(client = client).getBookMetadata(id, ProviderBookId(expected[0])).metadata.thumbnail)
        }
        HttpClient(MockEngine { throw java.io.IOException("fixture failure") }).use { client ->
            assertNull(provider(client = client).getBookMetadata(id, ProviderBookId(expected[0])).metadata.thumbnail)
        }
    }

    @Test fun `collections and works paginate without guessing or dropping later pages`() = runBlocking<Unit> {
        val requests = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            requests += request.url.toString()
            val data = if (request.url.encodedPath.endsWith("collections")) resource("collections") else resource("digital-works")
            val all = json.parseToJsonElement(data) as kotlinx.serialization.json.JsonArray
            val page2 = request.url.parameters["page"] == "2"
            val page = if (page2) all.drop(1) else all.take(1)
            val next = if (page2) "null" else "\"${request.url.encodedPath}?page=2\""
            respond("""{"status":200,"pagination":{"next":$next},"data":${page.joinToString(prefix="[",postfix="]")}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(this@MangaBakaWorksTest.json) } }.use { client ->
            val api = MangaBakaApiClient(client)
            assertEquals(2, api.getCollections(MangaBakaSeriesId(1357)).size)
            assertEquals(expected, api.getWorks(collections[1].id).map { it.id })
            assertEquals(4, requests.size)
        }
    }

    @Test fun `pagination refuses loops and foreign endpoints`() = runBlocking<Unit> {
        for (next in listOf("https://evil.invalid/data", "https://api.mangabaka.org/v1/series/2/collections", "?page=1")) {
            var requests = 0
            HttpClient(MockEngine {
                requests++
                respond("""{"status":200,"pagination":{"next":"$next"},"data":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }) { install(ContentNegotiation) { json(this@MangaBakaWorksTest.json) } }.use { client ->
                assertFailsWith<IllegalArgumentException> { MangaBakaApiClient(client).getCollections(MangaBakaSeriesId(1357)) }
                assertTrue(requests <= 2)
            }
        }
    }
}
