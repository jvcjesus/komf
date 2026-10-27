package snd.komf.mediaserver.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import snd.komf.comicinfo.ComicInfoWriter
import snd.komf.mangabaka.model.*
import snd.komf.mediaserver.MediaServerClient
import snd.komf.mediaserver.jobs.*
import snd.komf.mediaserver.jobs.repository.KomfJobsRepository
import snd.komf.mediaserver.match.repository.*
import snd.komf.mediaserver.model.*
import snd.komf.model.*
import snd.komf.providers.*
import snd.komf.providers.mangabaka.*
import snd.komf.util.NameSimilarityMatcher
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.test.*

class BookIdentificationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/mangabaka/$name.json")).readText()
    private val collections get() = json.decodeFromString<List<MangaBakaCollection>>(resource("collections"))
    private val works get() = json.decodeFromString<List<MangaBakaWork>>(resource("digital-works"))
    private val series get() = json.decodeFromString<MangaBakaSeries>(resource("series"))
    private val seriesId = MediaServerSeriesId("local-series")
    private val libraryId = MediaServerLibraryId("local-library")
    private val targetId = MediaServerBookId("book-1")
    private val providerSeriesId = ProviderSeriesId("1357")
    private val expectedWork = "019e1d1b-b538-708a-945d-09649968641f"

    private inner class Fixture(
        cols: List<MangaBakaCollection> = collections,
        selectedWorks: List<MangaBakaWork> = works,
        downloadFailure: Boolean = false,
        uploadFailure: Boolean = false,
        covers: Boolean = true,
        modes: Set<UpdateMode> = setOf(UpdateMode.API),
        aggregate: Boolean = false,
    ) : AutoCloseable {
        val canonical = Files.createTempFile("komf-book-fixture-", ".cbz").also { Files.write(it, byteArrayOf(4, 3, 2, 1)) }
        val originalBytes = Files.readAllBytes(canonical)
        val client = FakeServer(canonical.toString()).also { it.uploadFailure = uploadFailure }
        private val dbFile = Files.createTempFile("komf-book-test-", ".sqlite")
        private val dbUrl = "jdbc:sqlite:$dbFile"
        private val keeper = DriverManager.getConnection(dbUrl).also { connection ->
            connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            requireNotNull(javaClass.getResource("/db/migration/V1__migrate_from_sqldelight.sql")).readText()
                .split(';').filter { it.isNotBlank() }.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
        }
        private val database = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        val jobs = KomfJobsRepository(database)
        private val startupMarker = MetadataJob(seriesId = seriesId).also { jobs.save(it) }
        val bookThumbnails = BookThumbnailsRepository(database, MediaServer.KOMGA)
        val seriesMatches = SeriesMatchRepository(database, MediaServer.KOMGA)
        init { bookThumbnails.save(targetId, seriesId, MediaServerThumbnailId("old-book-1")) }
        private val tracker = KomfJobTracker(jobs)
        var selectedCollection: String? = null
        val requestedImages = mutableListOf<String>()
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        private val coverClient = HttpClient(MockEngine { request ->
            requestedImages += request.url.toString()
            if (downloadFailure) throw java.io.IOException("fixture download failure")
            respond(png)
        })
        private val source = object : MangaBakaDataSource {
            override suspend fun search(title: String, types: List<MangaBakaType>?, typesNot: List<MangaBakaType>?) = listOf(series)
            override suspend fun getSeries(id: MangaBakaSeriesId) = series
            override suspend fun getCollections(id: MangaBakaSeriesId) = cols
            override suspend fun getWorks(collectionId: String): List<MangaBakaWork> {
                selectedCollection = collectionId
                return selectedWorks
            }
        }
        val provider = MangaBakaMetadataProvider(source,
            MangaBakaMetadataMapper(SeriesMetadataConfig(), listOf(AuthorRole.WRITER), listOf(AuthorRole.PENCILLER)),
            NameSimilarityMatcher.nameSimilarityMatcher(NameSimilarityMatcher.NameMatchingMode.EXACT), null, MediaType.MANGA,
            MangaBakaCollectionTarget("en", "Standard Edition", "digital"), BookMetadataConfig(), coverClient)
        private val container = ProvidersModule.MetadataProvidersContainer(
            mangaupdates = null, mangaupdatesPriority = 0, mal = null, malPriority = 0,
            anilist = null, anilistPriority = 0, yenPress = null, yenPressPriority = 0,
            viz = null, vizPriority = 0, bookwalker = null, bookwalkerPriority = 0,
            mangaDex = null, mangaDexPriority = 0, bangumi = null, bangumiPriority = 0,
            comicVine = null, comicVinePriority = 0, mangaBaka = provider, mangaBakaPriority = 0,
            webtoons = null, webtoonsPriority = 0)
        val updater = MetadataUpdater(client, SeriesThumbnailsRepository(database, MediaServer.KOMGA), bookThumbnails,
            MetadataMapper(), MetadataPostProcessor(MediaType.MANGA, false, null, false, emptyList(), false,
                null, null, false, null, null, emptyList()), ComicInfoWriter.getInstance(false),
            modes, true, covers, true, false)
        val service = MetadataService(client, ProvidersModule.MetadataProviders(container, emptyMap()), aggregate,
            MetadataMerger(false, false), updater, seriesMatches, MediaType.MANGA, tracker)

        private suspend fun awaitStartup() {
            // Deterministically await the tracker's existing startup cancellation before registering a job.
            withTimeout(5000) { while (jobs.get(startupMarker.id)?.status == MetadataJobStatus.RUNNING) delay(5) }
        }

        suspend fun identify(): MetadataJob {
            awaitStartup()
            return awaitJob(service.setBookMetadata(targetId, CoreProviders.MANGA_BAKA, providerSeriesId, libraryId))
        }

        suspend fun identifySeries(): MetadataJob {
            awaitStartup()
            return awaitJob(service.setSeriesMetadata(seriesId, CoreProviders.MANGA_BAKA, providerSeriesId, null))
        }

        private suspend fun awaitJob(id: MetadataJobId): MetadataJob {
            return withTimeout(5000) {
                var result = requireNotNull(jobs.get(id))
                while (result.status == MetadataJobStatus.RUNNING) {
                    delay(5)
                    result = requireNotNull(jobs.get(id))
                }
                result
            }
        }

        fun assertIsolated(metadataExpected: Boolean) {
            assertEquals(if (metadataExpected) listOf(targetId) else emptyList(), client.bookUpdates.map { it.first })
            assertTrue(client.mutations.all { it.second == targetId.value }, client.mutations.toString())
            assertEquals(0, client.siblingReads)
            assertEquals("Original 2", client.books[MediaServerBookId("book-2")]?.metadata?.title)
            assertEquals("Original 3", client.books[MediaServerBookId("book-3")]?.metadata?.title)
            assertEquals("Original series", client.localSeries.metadata.title)
            for (n in 2..3) assertEquals(listOf("old-book-$n"),
                client.thumbnails[MediaServerBookId("book-$n")]!!.map { it.id.value })
            assertNull(seriesMatches.find(seriesId))
            assertContentEquals(originalBytes, Files.readAllBytes(canonical))
        }

        override fun close() {
            coverClient.close()
            keeper.close()
            Files.deleteIfExists(canonical)
            Files.deleteIfExists(dbFile)
            Files.deleteIfExists(java.nio.file.Path.of("$dbFile-wal"))
            Files.deleteIfExists(java.nio.file.Path.of("$dbFile-shm"))
        }
    }

    private inner class FakeServer(path: String) : MediaServerClient {
        override var supportsBookScopedMetadataUpdates = true
        val localSeries = MediaServerSeries(seriesId, libraryId, "Goodnight Punpun", 3,
            MediaServerSeriesMetadata(SeriesStatus.COMPLETED, "Original series", "Original series", emptyList(), "original", null,
                null, emptySet(), null, "en", emptyList(), emptyList(), 13, emptyList(), null, emptyList(),
                false, false, false, false, false, false, false, false, false, false, false, false, false, false, false), path, false)
        val books = (1..3).associate { n -> MediaServerBookId("book-$n") to MediaServerBook(
            MediaServerBookId("book-$n"), seriesId, libraryId, "Goodnight Punpun", "Goodnight Punpun v%02d.cbz".format(n), path, n, false,
            MediaServerBookMetadata("Original $n", "old", "$n", "$n", null, emptyList(), emptyList(), null, emptyList(),
                false, false, false, false, false, false, false, false, false), false)
        }.toMutableMap()
        val mutations = mutableListOf<Pair<String, String>>()
        val bookUpdates = mutableListOf<Pair<MediaServerBookId, MediaServerBookMetadataUpdate>>()
        val thumbnails = books.keys.associateWith { mutableListOf(MediaServerBookThumbnail(MediaServerThumbnailId("old-${it.value}"), it, "UPLOADED", true)) }.toMutableMap()
        var uploadFailure = false
        var allowSeries = false
        var siblingReads = 0
        var transform: ((MediaServerBook, Int) -> MediaServerBook)? = null
        private var reads = 0
        override suspend fun getBook(bookId: MediaServerBookId): MediaServerBook {
            require(bookId == targetId) { "Unexpected sibling read" }
            val book = requireNotNull(books[bookId])
            return transform?.invoke(book, ++reads) ?: book
        }
        override suspend fun getSeries(seriesId: MediaServerSeriesId) = localSeries
        override suspend fun getLibrary(libraryId: MediaServerLibraryId) = MediaServerLibrary(libraryId, "Manga", emptyList())
        override suspend fun getBookThumbnails(bookId: MediaServerBookId) = requireNotNull(thumbnails[bookId]).toList()
        override suspend fun getBookThumbnail(bookId: MediaServerBookId): Image? = null
        override suspend fun updateBookMetadata(bookId: MediaServerBookId, metadata: MediaServerBookMetadataUpdate) {
            mutations += "metadata" to bookId.value
            bookUpdates += bookId to metadata
            val book = requireNotNull(books[bookId])
            books[bookId] = book.copy(metadata = book.metadata.copy(title = metadata.title ?: book.metadata.title,
                summary = metadata.summary ?: book.metadata.summary, isbn = metadata.isbn ?: book.metadata.isbn,
                releaseDate = metadata.releaseDate ?: book.metadata.releaseDate))
        }
        override suspend fun uploadBookThumbnail(bookId: MediaServerBookId, thumbnail: Image, selected: Boolean, lock: Boolean): MediaServerBookThumbnail? {
            if (uploadFailure) throw java.io.IOException("fixture upload failure")
            mutations += "thumbnail" to bookId.value
            return MediaServerBookThumbnail(MediaServerThumbnailId("new-${bookId.value}"), bookId, "UPLOADED", selected)
                .also { requireNotNull(thumbnails[bookId]).add(it) }
        }
        override suspend fun deleteBookThumbnail(bookId: MediaServerBookId, thumbnailId: MediaServerThumbnailId) {
            mutations += "delete-thumbnail" to bookId.value
            thumbnails[bookId]?.removeAll { it.id == thumbnailId }
        }
        override suspend fun getBooks(seriesId: MediaServerSeriesId): Collection<MediaServerBook> {
            siblingReads++
            check(allowSeries) { "Must not enumerate sibling books" }
            return books.values.toList()
        }
        override suspend fun getSeries(libraryId: MediaServerLibraryId, pageNumber: Int): Page<MediaServerSeries> = error("Must not enumerate library")
        override suspend fun getLibraries(): List<MediaServerLibrary> = error("Must not enumerate libraries")
        override suspend fun getSeriesThumbnail(seriesId: MediaServerSeriesId): Image? = error("Must not fetch series thumbnail")
        override suspend fun getSeriesThumbnails(seriesId: MediaServerSeriesId): Collection<MediaServerSeriesThumbnail> {
            check(allowSeries) { "Must not access series thumbnails" }
            return emptyList()
        }
        override suspend fun updateSeriesMetadata(seriesId: MediaServerSeriesId, metadata: MediaServerSeriesMetadataUpdate) {
            check(allowSeries) { "Must not mutate series" }
            mutations += "series-metadata" to seriesId.value
        }
        override suspend fun deleteSeriesThumbnail(seriesId: MediaServerSeriesId, thumbnailId: MediaServerThumbnailId): Unit = error("Must not mutate series thumbnail")
        override suspend fun uploadSeriesThumbnail(seriesId: MediaServerSeriesId, thumbnail: Image, selected: Boolean, lock: Boolean): MediaServerSeriesThumbnail? = error("Must not mutate series thumbnail")
        override suspend fun resetBookMetadata(bookId: MediaServerBookId, bookName: String, bookNumber: Int?): Unit = error("Must not reset book")
        override suspend fun resetSeriesMetadata(seriesId: MediaServerSeriesId, seriesName: String): Unit = error("Must not reset series")
        override suspend fun refreshMetadata(libraryId: MediaServerLibraryId, seriesId: MediaServerSeriesId): Unit = error("Must not refresh series")
    }

    @Test fun `identifies one exact work updates only target and preserves canonical bytes`() = runBlocking<Unit> {
        Fixture(aggregate = true).use { f ->
            val metadata = f.provider.getSeriesMetadata(providerSeriesId)
            assertEquals(7, metadata.metadata.totalBookCount)
            assertEquals(expectedWork, metadata.books.first().id.id)
            assertEquals("019e1d1b-b52d-7a5e-b609-18f87987c7a4", f.selectedCollection)
            assertEquals(MetadataJobStatus.COMPLETED, f.identify().status)
            f.assertIsolated(true)
            val update = f.client.bookUpdates.single().second
            assertEquals("1", update.number)
            assertEquals("9781421589770", update.isbn)
            assertEquals("2016-03-15", update.releaseDate.toString())
            assertTrue(update.summary!!.startsWith("Meet Punpun"))
            assertEquals(listOf(works.first().images.single().image.raw!!.url), f.requestedImages)
            assertEquals(listOf("new-book-1"), f.client.thumbnails[targetId]!!.map { it.id.value })
            assertEquals("new-book-1", f.bookThumbnails.findFor(targetId)?.thumbnailId?.value)
        }
    }

    @Test fun `ambiguous collection missing work and duplicate sequence fail before any mutation`() = runBlocking<Unit> {
        val fixtures = listOf(Fixture(cols = collections + collections[1]), Fixture(selectedWorks = listOf(works[1])),
            Fixture(selectedWorks = works + works[1].copy(id = "duplicate", sequenceNumeric = 1.0)))
        for (f in fixtures) f.use {
            assertEquals(MetadataJobStatus.FAILED, it.identify().status)
            it.assertIsolated(false)
            assertTrue(it.client.mutations.isEmpty())
        }
    }

    @Test fun `cover download upload failure and disabled covers preserve target and sibling covers`() = runBlocking<Unit> {
        for (f in listOf(Fixture(downloadFailure = true), Fixture(uploadFailure = true), Fixture(covers = false))) f.use {
            assertEquals(MetadataJobStatus.COMPLETED, it.identify().status)
            it.assertIsolated(true)
            assertEquals(listOf("old-book-1"), it.client.thumbnails[targetId]!!.map { image -> image.id.value })
            assertEquals("old-book-1", it.bookThumbnails.findFor(targetId)?.thumbnailId?.value)
            assertTrue(it.client.mutations.all { mutation -> mutation.first == "metadata" })
        }
    }

    @Test fun `deleted mismatched and moved targets fail closed`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.client.transform = { b, _ -> b.copy(libraryId = MediaServerLibraryId("wrong")) }
            assertFailsWith<IllegalArgumentException> { f.identify() }
            f.assertIsolated(false)
        }
        Fixture().use { f ->
            f.client.transform = { b, _ -> b.copy(id = MediaServerBookId("book-2")) }
            assertFailsWith<IllegalArgumentException> { f.identify() }
            f.assertIsolated(false)
        }
        Fixture().use { f ->
            assertFailsWith<IllegalArgumentException> {
                f.service.setBookMetadata(targetId, CoreProviders.MANGA_BAKA, providerSeriesId, MediaServerLibraryId("wrong"))
            }
            f.assertIsolated(false)
        }
        Fixture().use { f ->
            f.client.transform = { b, _ -> b.copy(deleted = true) }
            assertFailsWith<IllegalArgumentException> { f.identify() }
            f.assertIsolated(false)
        }
        Fixture().use { f ->
            f.client.transform = { b, read -> if (read > 1) b.copy(name = "Changed v02.cbz") else b }
            assertEquals(MetadataJobStatus.FAILED, f.identify().status)
            f.assertIsolated(false)
        }
    }

    @Test fun `ComicInfo or combined update modes are refused without archive or API writes`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.client.supportsBookScopedMetadataUpdates = false
            assertFailsWith<IllegalArgumentException> { f.identify() }
            f.assertIsolated(false)
        }
        for (modes in listOf(setOf(UpdateMode.COMIC_INFO), setOf(UpdateMode.API, UpdateMode.COMIC_INFO), emptySet())) {
            Fixture(modes = modes).use { f ->
                assertFailsWith<IllegalArgumentException> { f.identify() }
                f.assertIsolated(false)
            }
        }
    }

    @Test fun `existing series identification still updates series and all local books`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.client.allowSeries = true
            assertEquals(MetadataJobStatus.COMPLETED, f.identifySeries().status)
            assertEquals(listOf("book-1", "book-2", "book-3"), f.client.bookUpdates.map { it.first.value })
            assertTrue(f.client.mutations.contains("series-metadata" to seriesId.value))
            assertEquals(1, f.client.siblingReads)
            assertEquals(providerSeriesId, f.seriesMatches.find(seriesId)?.providerSeriesId)
            assertContentEquals(f.originalBytes, Files.readAllBytes(f.canonical))
        }
    }

    @Test fun `book-only oneshot processing retains exact work ISBN date and cover`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.client.books[targetId] = f.client.books[targetId]!!.copy(oneshot = true)
            assertEquals(MetadataJobStatus.COMPLETED, f.identify().status)
            assertEquals("9781421589770", f.client.bookUpdates.single().second.isbn)
            assertEquals("2016-03-15", f.client.bookUpdates.single().second.releaseDate.toString())
            f.assertIsolated(true)
        }
    }
}
