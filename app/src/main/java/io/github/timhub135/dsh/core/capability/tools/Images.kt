package io.github.timhub135.dsh.core.capability.tools

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File

/** Keeps images model-friendly: longest side ≤ 1280px, always JPEG q80 (so the media type is always image/jpeg). */
object Images {
    const val MAX = 1280
    const val MIME = "image/jpeg"

    class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * Decodes any format the platform supports (JPEG, PNG, WebP, GIF, HEIC/HEIF, AVIF on newer
     * Android), applies EXIF rotation, and subsamples while decoding so huge camera photos are cheap.
     * Returns null when the file cannot be decoded (SVG, corrupt, unsupported).
     */
    fun downscale(file: File): Encoded? = runCatching {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val scale = MAX.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        encode(bmp)
    }.getOrNull()

    fun encode(bitmap: Bitmap): Encoded {
        val scale = MAX.toFloat() / maxOf(bitmap.width, bitmap.height)
        val b = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true) else bitmap
        val bytes = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        return Encoded(bytes, b.width, b.height)
    }
}
