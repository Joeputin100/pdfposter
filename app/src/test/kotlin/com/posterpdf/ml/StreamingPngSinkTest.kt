package com.posterpdf.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Verifies [StreamingPngSink] emits a structurally valid, correctly-encoded RGB
 * PNG. We do NOT use javax.imageio/BitmapFactory — neither is on the Android
 * unit-test classpath (javax.imageio is the desktop java.desktop module;
 * BitmapFactory is a stubbed framework class). Instead we parse the PNG chunks
 * and inflate the IDAT zlib stream with java.util.zip (which IS available),
 * then assert dimensions and specific pixels. This also exercises our chunk
 * framing + scanline filtering directly — a stronger check than an opaque decode.
 */
class StreamingPngSinkTest {

    private fun int32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    @Test
    fun writes_valid_png_with_correct_size_and_pixels() {
        val w = 4
        val h = 3
        val out = ByteArrayOutputStream()
        StreamingPngSink(out, w, h).use { sink ->
            // 3 rows of 4 RGB pixels: row 0 red, row 1 green, row 2 blue.
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
        val png = out.toByteArray()

        // PNG 8-byte signature.
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue("bad PNG signature", png.copyOfRange(0, 8).contentEquals(sig))

        // Walk chunks: [len(4)][type(4)][data(len)][crc(4)] from offset 8.
        var p = 8
        var sawIHDR = false
        var sawIEND = false
        var ihdrW = -1
        var ihdrH = -1
        val idat = ByteArrayOutputStream()
        while (p + 8 <= png.size) {
            val len = int32(png, p); p += 4
            val type = String(png, p, 4, Charsets.US_ASCII); p += 4
            when (type) {
                "IHDR" -> { ihdrW = int32(png, p); ihdrH = int32(png, p + 4); sawIHDR = true }
                "IDAT" -> idat.write(png, p, len)
                "IEND" -> sawIEND = true
            }
            p += len + 4 // skip data + CRC
        }
        assertTrue("missing IHDR", sawIHDR)
        assertTrue("missing IEND", sawIEND)
        assertEquals("width", w, ihdrW)
        assertEquals("height", h, ihdrH)

        // Inflate IDAT → raw scanlines: each row = 1 filter byte + w*3 RGB bytes.
        val stride = 1 + w * 3
        val raw = ByteArray(h * stride)
        val inf = Inflater()
        inf.setInput(idat.toByteArray())
        var got = 0
        while (!inf.finished() && got < raw.size) {
            val n = inf.inflate(raw, got, raw.size - got)
            if (n == 0) break
            got += n
        }
        inf.end()
        assertEquals("inflated scanline byte count", raw.size, got)

        // Row 0: filter byte 0, first pixel red.
        assertEquals("row0 filter", 0, raw[0].toInt())
        assertEquals("row0 px0 R", 0xFF, raw[1].toInt() and 0xFF)
        assertEquals("row0 px0 G", 0x00, raw[2].toInt() and 0xFF)
        assertEquals("row0 px0 B", 0x00, raw[3].toInt() and 0xFF)
        // Row 2: last pixel blue.
        val r2 = 2 * stride + 1 + (w - 1) * 3
        assertEquals("row2 px3 R", 0x00, raw[r2].toInt() and 0xFF)
        assertEquals("row2 px3 G", 0x00, raw[r2 + 1].toInt() and 0xFF)
        assertEquals("row2 px3 B", 0xFF, raw[r2 + 2].toInt() and 0xFF)
    }
}
