package com.posterpdf.ml

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class StreamingPngSinkTest {
    @Test fun writes_decodable_png_of_correct_size_and_pixels() {
        val w = 4; val h = 3
        val out = ByteArrayOutputStream()
        StreamingPngSink(out, w, h).use { sink ->
            // 3 rows, each 4 RGB pixels. Row 0 red, row 1 green, row 2 blue.
            val colors = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF)
            for (y in 0 until h) {
                val row = ByteArray(w * 3)
                val c = colors[y]
                for (x in 0 until w) {
                    row[x * 3] = ((c ushr 16) and 0xFF).toByte()
                    row[x * 3 + 1] = ((c ushr 8) and 0xFF).toByte()
                    row[x * 3 + 2] = (c and 0xFF).toByte()
                }
                sink.writeRow(row)
            }
        }
        val img = ImageIO.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        assertEquals(0xFF0000, img.getRGB(0, 0) and 0xFFFFFF) // row 0 red
        assertEquals(0x0000FF, img.getRGB(3, 2) and 0xFFFFFF) // row 2 blue
    }
}
