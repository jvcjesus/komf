package snd.komf.mangabaka.model

import kotlinx.serialization.Serializable

@Serializable
data class MangaBakaResponse<T>(
    val status: Int,
    val pagination: MangaBakaPagination? = null,
    val data: T
)

@Serializable
data class MangaBakaPagination(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val limit: Int = 0
)
