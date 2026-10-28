package snd.komf.mangabaka.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class MangaBakaCollection(
    val id: String,
    @SerialName("series_id") val seriesId: Long,
    val language: JsonElement,
    val edition: JsonElement,
    val medium: String,
    val type: String? = null,
    @SerialName("count_main") val countMain: Int? = null,
)

@Serializable
data class MangaBakaWork(
    val id: String,
    @SerialName("sequence_numeric") val sequenceNumeric: Double? = null,
    val sequence: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val description: JsonElement? = null,
    val identifiers: List<MangaBakaWorkIdentifier> = emptyList(),
    // Catalog data only: never used as the archive's actual page count.
    val pages: Int? = null,
    val images: List<MangaBakaWorkImage> = emptyList(),
) {
    fun number(): Double? = (sequenceNumeric ?: sequence?.toDoubleOrNull())?.takeIf { it.isFinite() }
}

@Serializable
data class MangaBakaWorkIdentifier(val id: String, val name: String)

@Serializable
data class MangaBakaWorkImage(
    @SerialName("work_id") val workId: String? = null,
    @SerialName("series_id") val seriesId: Long? = null,
    val language: String? = null,
    val type: String? = null,
    val image: MangaBakaCover,
)

internal fun JsonElement.text(vararg keys: String): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> keys.firstNotNullOfOrNull { (get(it) as? JsonPrimitive)?.contentOrNull }
    else -> null
}
