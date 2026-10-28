package snd.komf.providers.mangabaka

import com.fleeksoft.ksoup.Ksoup
import io.github.reactivecircus.cache4k.Cache
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.contentType
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.LocalDate
import snd.komf.mangabaka.model.MangaBakaCollection
import snd.komf.mangabaka.model.MangaBakaSeries
import snd.komf.mangabaka.model.MangaBakaSeriesId
import snd.komf.mangabaka.model.MangaBakaType
import snd.komf.mangabaka.model.MangaBakaWork
import snd.komf.mangabaka.model.text
import snd.komf.model.BookMetadata
import snd.komf.model.BookRange
import snd.komf.model.Image
import snd.komf.model.MatchQuery
import snd.komf.model.MediaType
import snd.komf.model.ProviderBookId
import snd.komf.model.ProviderBookMetadata
import snd.komf.model.ProviderSeriesId
import snd.komf.model.ProviderSeriesMetadata
import snd.komf.model.SeriesBook
import snd.komf.model.SeriesSearchResult
import snd.komf.providers.BookMetadataConfig
import snd.komf.providers.CoreProviders
import snd.komf.providers.MangaBakaCollectionTarget
import snd.komf.providers.MetadataConfigApplier
import snd.komf.providers.MetadataProvider
import snd.komf.util.NameSimilarityMatcher

class MangaBakaMetadataProvider(
    private val dataSource: MangaBakaDataSource,
    private val metadataMapper: MangaBakaMetadataMapper,
    private val nameMatcher: NameSimilarityMatcher,
    private val coverFetchClient: HttpClient?,
    mediaType: MediaType,
    private val collectionTarget: MangaBakaCollectionTarget? = null,
    private val bookConfig: BookMetadataConfig = BookMetadataConfig(),
    private val workCoverFetchClient: HttpClient? = null,
    private val includeBooks: Boolean = true,
    private val includeTotalCount: Boolean = true,
) : MetadataProvider {
    private val typeExcludes: List<MangaBakaType>? = when (mediaType) {
        MediaType.MANGA -> listOf(MangaBakaType.NOVEL)
        else -> null
    }
    private val typeIncludes: List<MangaBakaType>? = when (mediaType) {
        MediaType.MANGA -> null
        MediaType.NOVEL -> listOf(MangaBakaType.NOVEL)
        MediaType.COMIC -> listOf(MangaBakaType.OEL, MangaBakaType.OTHER)
        MediaType.WEBTOON -> listOf(MangaBakaType.MANHUA, MangaBakaType.MANHWA)
    }

    private val cache = Cache.Builder<MangaBakaSeriesId, MangaBakaSeries>()
        .expireAfterWrite(30.minutes)
        .build()

    private data class Selected(val collection: MangaBakaCollection, val works: List<MangaBakaWork>)
    private val selections = Cache.Builder<MangaBakaSeriesId, Selected>().expireAfterWrite(30.minutes).build()

    private suspend fun selected(id: MangaBakaSeriesId): Selected = selections.get(id) {
        val target = requireNotNull(collectionTarget) { "MangaBaka collection target is not configured" }
        require(listOf(target.language, target.edition, target.medium).all { it.isNotBlank() }) {
            "MangaBaka collection language, edition and medium must all be specified"
        }
        val matches = dataSource.getCollections(id).filter {
            it.seriesId == id.value &&
                    it.language.text("iso", "code").equals(target.language.trim(), true) &&
                    it.edition.text("name", "title").equals(target.edition.trim(), true) &&
                    it.medium.equals(target.medium.trim(), true) &&
                    (target.type == null || it.type?.equals(target.type.trim(), true) == true)
        }
        val resolvedMatches = if (matches.size > 1 && target.type == null) {
            matches.filter { it.type?.equals("volume", true) == true }
        } else {
            matches
        }
        require(resolvedMatches.size == 1) { "Expected exactly one MangaBaka collection for the configured language/edition/medium; found ${resolvedMatches.size}" }
        val collection = resolvedMatches.single()
        require(collection.countMain == null || collection.countMain >= 0) { "Invalid collection count_main" }
        val works = dataSource.getWorks(collection.id)
        require(works.map { it.id }.distinct().size == works.size) { "Duplicate MangaBaka work IDs" }
        require(works.all { it.id.isNotBlank() && it.number() != null }) { "MangaBaka work has no exact numeric sequence" }
        require(works.map { it.number() }.distinct().size == works.size) { "Ambiguous MangaBaka work sequences" }
        Selected(collection, works)
    }

    private suspend fun metadata(series: MangaBakaSeries): ProviderSeriesMetadata {
        val mapped = metadataMapper.toSeriesMetadata(series, fetchCover(series))
        if (collectionTarget == null) return mapped
        val selected = selected(series.id)
        return mapped.copy(
            metadata = mapped.metadata.copy(totalBookCount = selected.collection.countMain.takeIf { includeTotalCount }),
            books = if (!includeBooks) emptyList() else selected.works.map { work ->
                SeriesBook(ProviderBookId(work.id), BookRange(requireNotNull(work.number())),
                    work.title ?: work.name, "volume", null)
            }
        )
    }

    override fun providerName() = CoreProviders.MANGA_BAKA

    override suspend fun getSeriesMetadata(seriesId: ProviderSeriesId): ProviderSeriesMetadata {
        val id = seriesId.toMangaBakaId()
        val series = cache.get(id) { dataSource.getSeries(id) }
        return metadata(series)
    }

    override suspend fun getSeriesCover(seriesId: ProviderSeriesId): Image? {
        val id = seriesId.toMangaBakaId()
        val series = cache.get(id) { dataSource.getSeries(id) }
        return fetchCover(series)
    }

    override suspend fun getBookMetadata(
        seriesId: ProviderSeriesId,
        bookId: ProviderBookId
    ): ProviderBookMetadata {
        val id = seriesId.toMangaBakaId()
        val work = selected(id).works.singleOrNull { it.id == bookId.id }
            ?: error("Work does not belong to the selected MangaBaka collection")
        val url = work.images.firstNotNullOfOrNull { record ->
            if (record.workId != work.id || record.seriesId?.let { it != id.value } == true ||
                record.language?.equals(collectionTarget?.language?.trim(), true) == false ||
                record.type?.equals("volume", true) == false) null
            else record.image.raw?.url ?: record.image.x350?.x3 ?: record.image.x350?.x2 ?: record.image.x350?.x1
        }
        val cover = if (bookConfig.thumbnail && workCoverFetchClient != null && url != null)
            MangaBakaWorkCover.fetch(workCoverFetchClient, url) else null
        val mapped = MetadataConfigApplier.apply(ProviderBookMetadata(
            id = bookId, seriesId = seriesId,
            metadata = BookMetadata(
                title = work.title ?: work.name,
                number = BookRange(requireNotNull(work.number())),
                numberSort = work.number(),
                summary = work.description?.text("desc", "description")?.let { Ksoup.parse(it).wholeText() },
                releaseDate = work.releaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                isbn = work.identifiers.firstOrNull { it.name.equals("isbn", true) }?.id,
                thumbnail = cover,
            )
        ), bookConfig)
        return mapped.copy(seriesId = seriesId)
    }

    override suspend fun searchSeries(
        seriesName: String,
        limit: Int
    ): Collection<SeriesSearchResult> {
        val results = dataSource.search(
            title = seriesName,
            types = typeIncludes,
            typesNot = typeExcludes,
        )
        results.forEach { cache.put(it.id, it) }

        return results.take(limit).map { metadataMapper.toSeriesSearchResult(it) }
    }

    override suspend fun matchSeriesMetadata(matchQuery: MatchQuery): ProviderSeriesMetadata? {
        val seriesName = matchQuery.seriesName
        val searchResults = dataSource.search(
            title = seriesName.take(400),
            types = typeIncludes,
            typesNot = typeExcludes
        )
        searchResults.forEach { cache.put(it.id, it) }

        val match = searchResults.firstOrNull { series ->
            val titles = series.titles?.map { it.title } ?: emptyList()
            nameMatcher.matches(seriesName, titles)
        }

        return match?.let { metadata(it) }
    }

    private suspend fun fetchCover(series: MangaBakaSeries): Image? {
        if (coverFetchClient == null || series.cover.x350?.x1 == null) return null

        val response = coverFetchClient.get(series.cover.x350.x1)
        return Image(
            response.body(),
            response.contentType()?.let { "${it.contentType}/${it.contentSubtype}" }
        )
    }

    private fun ProviderSeriesId.toMangaBakaId() = MangaBakaSeriesId(this.value.toLong())
}
