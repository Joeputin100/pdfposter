package com.posterpdf.ml

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Streams an RGB (8-bit, color-type 2) PNG one scanline at a time so the full
 * image is never held in memory. Writes signature + IHDR up front, emits the
 * zlib stream as a sequence of IDAT chunks as the Deflater produces output,
 * and writes IEND on close(). Filter byte 0 (none) per scanline.
 *
 * RC76: this is the band-streaming output side of the OOM fix — the upscaler
 * composites one horizontal band at a time and hands its rows here, so peak
 * memory is bounded by a band, not by the (4x) output image.
 *
 * Note: the whole image is a SINGLE continuous zlib stream split across IDAT
 * chunks; you cannot resume by appending a second Deflater's output to a
 * partially-written file (see UpscalerOnDevice.upscaleToFile resume note).
 */
internal class StreamingPngSink(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
) : AutoCloseable {
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    private val zbuf = ByteArray(16 * 1024)

    init {
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        writeIntBE(ihdr, 0, width); writeIntBE(ihdr, 4, height)
        ihdr[8] = 8   // bit depth
        ihdr[9] = 2   // color type: truecolor RGB
        ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0 // compression / filter / interlace
        writeChunk("IHDR", ihdr, 0, ihdr.size)
    }

    /** Append one scanline of [width]*3 RGB bytes (top-to-bottom order). */
    fun writeRow(rgb: ByteArray) {
        require(rgb.size == width * 3) { "row must be width*3 bytes" }
        // PNG scanline = 1 filter byte (0 = none) followed by the RGB bytes.
        // Feed each to the Deflater and drain whatever complete output it has.
        deflater.setInput(FILTER_NONE)
        drain()
        deflater.setInput(rgb)
        drain()
    }

    /** Compress all currently-set input, emitting IDAT chunks. Loops until the
     *  Deflater has consumed the input (needsInput) so the next setInput is
     *  safe and no scanline bytes are dropped. */
    private fun drain() {
        while (!deflater.needsInput()) {
            val n = deflater.deflate(zbuf)
            if (n > 0) writeChunk("IDAT", zbuf, 0, n) else break
        }
    }

    override fun close() {
        deflater.finish()
        while (!deflater.finished()) {
            val n = deflater.deflate(zbuf)
            if (n > 0) writeChunk("IDAT", zbuf, 0, n)
        }
        deflater.end()
        writeChunk("IEND", EMPTY, 0, 0)
        out.flush()
    }

    private fun writeChunk(type: String, data: ByteArray, off: Int, len: Int) {
        val lenb = ByteArray(4); writeIntBE(lenb, 0, len); out.write(lenb)
        val typeb = type.toByteArray(Charsets.US_ASCII); out.write(typeb)
        if (len > 0) out.write(data, off, len)
        val crc = CRC32(); crc.update(typeb); if (len > 0) crc.update(data, off, len)
        val crcb = ByteArray(4); writeIntBE(crcb, 0, crc.value.toInt()); out.write(crcb)
    }

    private fun writeIntBE(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    private companion object {
        val FILTER_NONE = byteArrayOf(0)
        val EMPTY = ByteArray(0)
    }
}
