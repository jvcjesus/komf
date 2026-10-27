package snd.komf.app.api.mappers

import kotlinx.serialization.json.Json
import snd.komf.api.config.KomfConfigUpdateRequest
import snd.komf.app.config.AppConfig
import kotlin.test.*

class MangaBakaConfigTest {
    private val mapper = AppConfigUpdateMapper()
    private fun patch(config: AppConfig, body: String): AppConfig = mapper.patch(config,
        Json.decodeFromString<KomfConfigUpdateRequest>("""{"metadataProviders":{"defaultProviders":{"mangaBaka":$body}}}"""))

    @Test fun `collection and independent book thumbnail settings survive configuration API round trip`() {
        val config = patch(AppConfig(), """{"collection":{"language":"en","edition":"Standard Edition","medium":"digital"},"bookMetadata":{"thumbnail":false}}""")
        val dto = AppConfigMapper().toDto(config, null, null).metadataProviders.defaultProviders.mangaBaka
        assertEquals("en", dto.collection?.language)
        assertEquals("Standard Edition", dto.collection?.edition)
        assertEquals("digital", dto.collection?.medium)
        assertEquals(false, dto.bookMetadata?.thumbnail)
        assertEquals(true, dto.seriesMetadata.thumbnail)
        assertEquals(config.metadataProviders.defaultProviders.mangaBaka,
            patch(config, """{"priority":22}""").metadataProviders.defaultProviders.mangaBaka.copy(priority = 10))
    }

    @Test fun `collection can be explicitly cleared without resetting book configuration`() {
        val config = patch(AppConfig(), """{"collection":{"language":"en","edition":"Standard Edition","medium":"digital"},"bookMetadata":{"thumbnail":false}}""")
        val updated = patch(config, """{"collection":null}""").metadataProviders.defaultProviders.mangaBaka
        assertNull(updated.collection)
        assertFalse(updated.bookMetadata.thumbnail)
    }
}
