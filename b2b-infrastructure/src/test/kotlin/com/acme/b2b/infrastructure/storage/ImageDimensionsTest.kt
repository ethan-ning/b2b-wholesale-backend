package com.acme.b2b.infrastructure.storage

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A one-pixel PNG, as a browser would send one. */
private val ONE_PIXEL_PNG: ByteArray = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)

/** Encoded here rather than checked in as a blob, so the expected size is visible. */
private fun jpeg(width: Int, height: Int): ByteArray {
    val out = ByteArrayOutputStream()
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", out)
    return out.toByteArray()
}

class ImageDimensionsTest {

    @Test
    fun `reads a PNG's size from its bytes`() {
        assertEquals(1 to 1, ImageDimensions.of(ONE_PIXEL_PNG))
    }

    @Test
    fun `reads a JPEG's size, width first`() {
        // Deliberately not square: a transposed pair would pass on a square image.
        assertEquals(40 to 25, ImageDimensions.of(jpeg(40, 25)))
    }

    /**
     * The reason the columns are nullable. WebP has no reader in the JDK, and an upload we
     * cannot measure is still an upload worth keeping.
     */
    @Test
    fun `gives up rather than throwing when nothing can read the bytes`() {
        assertNull(ImageDimensions.of("not an image at all".toByteArray()))
        assertNull(ImageDimensions.of(ByteArray(0)))
    }

    /** Truncated uploads happen; a half-written file must not take the request down. */
    @Test
    fun `survives a file that starts like an image and stops`() {
        assertNull(ImageDimensions.of(ONE_PIXEL_PNG.copyOfRange(0, 8)))
    }
}
