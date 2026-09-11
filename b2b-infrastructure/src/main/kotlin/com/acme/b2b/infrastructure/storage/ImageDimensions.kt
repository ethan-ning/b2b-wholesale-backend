package com.acme.b2b.infrastructure.storage

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Pixel size, read from the bytes rather than trusted from the client.
 *
 * Lives beside the stores because it is a capability of the runtime, not a business rule:
 * ImageIO reads whatever formats the JVM has registered readers for, and WebP has none in
 * the JDK. Null when nothing can read it — the dimensions are shown in the library, not
 * relied upon, so an unknown size costs a line of the display and nothing else.
 */
internal object ImageDimensions {

    fun of(content: ByteArray): Pair<Int, Int>? =
        runCatching {
            ImageIO.createImageInputStream(ByteArrayInputStream(content)).use { stream ->
                val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
                try {
                    reader.input = stream
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
}
