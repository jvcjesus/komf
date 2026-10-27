package snd.komf.providers.mangabaka

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import snd.komf.model.Image
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Artwork is optional. Failed/invalid downloads must not remove an existing cover. */
internal object MangaBakaWorkCover {
    private val logger = KotlinLogging.logger {}
    private const val MAX_BYTES = 16 * 1024 * 1024

    suspend fun fetch(client: HttpClient, url: String): Image? = try {
        withTimeoutOrNull(15_000) {
            val parsed = Url(url)
            require(parsed.protocol.name in setOf("http", "https") && parsed.user == null && parsed.password == null)
            client.prepareGet(url).execute { response ->
                require(response.status.isSuccess())
                val channel = response.bodyAsChannel()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    require(output.size().toLong() + read <= MAX_BYTES)
                    output.write(buffer, 0, read)
                }
                validate(output.toByteArray())
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        logger.warn { "MangaBaka work cover unavailable or invalid; existing cover retained" }
        null
    }

    internal fun validate(bytes: ByteArray): Image {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        return ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
            requireNotNull(input)
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext())
            val reader = readers.next()
            try {
                reader.input = input
                val format = reader.formatName.lowercase()
                require(format in setOf("jpeg", "jpg", "png", "webp"))
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width > 0 && height > 0 && width.toLong() * height <= 16_000_000)
                requireNotNull(reader.read(0))
                Image(bytes, "image/${if (format == "jpg") "jpeg" else format}")
            } finally {
                reader.dispose()
            }
        }
    }
}
