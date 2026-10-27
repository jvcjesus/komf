package snd.komf.mangabaka.external

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.Url
import java.net.URI
import snd.komf.mangabaka.model.MangaBakaCollection
import snd.komf.mangabaka.model.MangaBakaContentRating
import snd.komf.mangabaka.model.MangaBakaResponse
import snd.komf.mangabaka.model.MangaBakaSearchResponse
import snd.komf.mangabaka.model.MangaBakaSeries
import snd.komf.mangabaka.model.MangaBakaSeriesId
import snd.komf.mangabaka.model.MangaBakaSeriesTag
import snd.komf.mangabaka.model.MangaBakaTagId
import snd.komf.mangabaka.model.MangaBakaType
import snd.komf.mangabaka.model.MangaBakaWork
import snd.komf.providers.mangabaka.MangaBakaDataSource

class MangaBakaApiClient(private val ktor: HttpClient) : MangaBakaDataSource {
    private val baseUrl = "https://api.mangabaka.org"

    override suspend fun search(
        title: String,
        types: List<MangaBakaType>?,
        typesNot: List<MangaBakaType>?
    ): List<MangaBakaSeries> {
        return ktor.get("${baseUrl}/v1/series/search") {
            parameter("q", title)
            types?.forEach { parameter("type", it.name.lowercase()) }
            typesNot?.forEach { parameter("type_not", it.name.lowercase()) }
        }.body<MangaBakaSearchResponse>().data
    }

    override suspend fun getSeries(id: MangaBakaSeriesId): MangaBakaSeries {
        return ktor.get("${baseUrl}/v1/series/${id}").body<MangaBakaResponse<MangaBakaSeries>>().data
    }

    override suspend fun getCollections(id: MangaBakaSeriesId): List<MangaBakaCollection> =
        pages("$baseUrl/v1/series/$id/collections")

    override suspend fun getWorks(collectionId: String): List<MangaBakaWork> {
        require(collectionId.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid collection ID" }
        return pages("$baseUrl/v1/collections/$collectionId/works")
    }

    private suspend inline fun <reified T> pages(initial: String): List<T> {
        val result = mutableListOf<T>()
        val seen = mutableSetOf<String>()
        var next: String? = initial
        while (next != null) {
            val url = next
            val parsed = Url(url)
            require(parsed.protocol.name == "https" && parsed.host == "api.mangabaka.org" &&
                    parsed.port == 443 && parsed.user == null && parsed.password == null &&
                    parsed.encodedPath == Url(initial).encodedPath) { "Unsafe MangaBaka pagination URL" }
            require(seen.size < 100 && seen.add(url)) { "MangaBaka pagination is cyclic or too large" }
            val page = ktor.get(url).body<MangaBakaResponse<List<T>>>()
            require(page.status in 200..299) { "MangaBaka returned an unsuccessful page" }
            result.addAll(page.data)
            next = page.pagination?.next?.takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("?")) url.substringBefore('?') + it else URI(url).resolve(it).toString()
            }
        }
        return result
    }

    suspend fun getTags(
        query: String? = null,
        ids: List<MangaBakaTagId>? = null,
        parent: MangaBakaTagId? = null,
        depth: Int? = null,
        contentRatings: List<MangaBakaContentRating>? = null,
        isGenre: Boolean? = null,
        page: Int? = null,
        limit: Int? = null
    ): List<MangaBakaSeriesTag> {
        val response: MangaBakaResponse<List<MangaBakaSeriesTag>> = ktor.get("${baseUrl}/v1/tags") {
            query?.let { parameter("q", it) }
            ids?.forEach { parameter("id", it.value) }
            parent?.let { parameter("parent", it.value) }
            depth?.let { parameter("depth", it) }
            contentRatings?.forEach { parameter("content_rating", it.name.lowercase()) }
            isGenre?.let { parameter("is_genre", it) }
            page?.let { parameter("page", it) }
            limit?.let { parameter("limit", it) }
        }.body()
        return response.data
    }
}
