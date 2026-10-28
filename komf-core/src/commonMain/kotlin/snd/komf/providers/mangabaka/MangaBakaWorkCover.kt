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
    private const val MAX_KOMGA_BYTES = 1048575

    private fun compressToLimit(image: java.awt.image.BufferedImage, maxBytes: Int = MAX_KOMGA_BYTES): ByteArray {
        var currentImage = image
        if (currentImage.width > 1800) {
            val scale = 1800.0 / currentImage.width
            val newHeight = (currentImage.height * scale).toInt()
            val scaled = java.awt.image.BufferedImage(1800, newHeight, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = scaled.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(currentImage, 0, 0, 1800, newHeight, null)
            g.dispose()
            currentImage = scaled
        } else if (currentImage.type != java.awt.image.BufferedImage.TYPE_INT_RGB) {
            val rgbImage = java.awt.image.BufferedImage(currentImage.width, currentImage.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = rgbImage.createGraphics()
            g.drawImage(currentImage, 0, 0, null)
            g.dispose()
            currentImage = rgbImage
        }

        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        var quality = 0.85f
        for (attempt in 0..4) {
            val param = writer.defaultWriteParam.apply {
                compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            val baos = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(baos).use { ios ->
                writer.output = ios
                writer.write(null, javax.imageio.IIOImage(currentImage, null, null), param)
            }
            val result = baos.toByteArray()
            if (result.size <= maxBytes || quality <= 0.4f) {
                writer.dispose()
                return result
            }
            quality -= 0.15f
        }
        writer.dispose()
        val halfW = (currentImage.width / 2).coerceAtLeast(1)
        val halfH = (currentImage.height / 2).coerceAtLeast(1)
        val halfScaled = java.awt.image.BufferedImage(halfW, halfH, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = halfScaled.createGraphics()
        g.drawImage(currentImage, 0, 0, halfW, halfH, null)
        g.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(halfScaled, "jpeg", baos)
        return baos.toByteArray()
    }

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
                val image = requireNotNull(reader.read(0))
                if (bytes.size <= MAX_KOMGA_BYTES) {
                    Image(bytes, "image/${if (format == "jpg") "jpeg" else format}")
                } else {
                    val compressed = compressToLimit(image, MAX_KOMGA_BYTES)
                    Image(compressed, "image/jpeg")
                }
            } finally {
                reader.dispose()
            }
        }
    }
}
