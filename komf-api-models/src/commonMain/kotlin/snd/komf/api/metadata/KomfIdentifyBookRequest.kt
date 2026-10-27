package snd.komf.api.metadata

import kotlinx.serialization.Serializable
import snd.komf.api.KomfProviderSeriesId
import snd.komf.api.KomfProviders
import snd.komf.api.KomfServerBookId
import snd.komf.api.KomfServerLibraryId

/** Identifies one book only. libraryId, when supplied, is an assertion, not routing authority. */
@Serializable
data class KomfIdentifyBookRequest(
    val bookId: KomfServerBookId,
    val provider: KomfProviders,
    val providerSeriesId: KomfProviderSeriesId,
    val libraryId: KomfServerLibraryId? = null,
)
