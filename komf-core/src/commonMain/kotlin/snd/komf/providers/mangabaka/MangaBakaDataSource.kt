package snd.komf.providers.mangabaka

import snd.komf.mangabaka.model.MangaBakaCollection
import snd.komf.mangabaka.model.MangaBakaSeries
import snd.komf.mangabaka.model.MangaBakaSeriesId
import snd.komf.mangabaka.model.MangaBakaType
import snd.komf.mangabaka.model.MangaBakaWork

interface MangaBakaDataSource {
    suspend fun search(
        title: String,
        types: List<MangaBakaType>? = null,
        typesNot: List<MangaBakaType>? = null,
    ): List<MangaBakaSeries>

    suspend fun getSeries(id: MangaBakaSeriesId): MangaBakaSeries

    suspend fun getCollections(id: MangaBakaSeriesId): List<MangaBakaCollection> =
        error("This MangaBaka datasource does not support collections; use API mode")

    suspend fun getWorks(collectionId: String): List<MangaBakaWork> =
        error("This MangaBaka datasource does not support works; use API mode")
}
