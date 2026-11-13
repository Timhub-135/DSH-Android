package io.github.timhub135.dsh.core.capability

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One recognized line with its centre in screen pixels (tappable as `t<n>`). */
data class OcrLine(val text: String, val x: Int, val y: Int)

/**
 * On-device text recognition (ML Kit, bundled Chinese model, which also reads Latin script) for
 * screens whose accessibility tree has little text: custom-drawn UIs, Flutter, games, opaque WebViews.
 */
@Singleton
class ScreenOcr @Inject constructor() {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /** Lines top to bottom, then left to right. [bitmap] is in screen pixels. */
    suspend fun read(bitmap: Bitmap): List<OcrLine> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
        return result.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            val box = l.boundingBox ?: return@mapNotNull null
            l.text.trim().takeIf { it.isNotEmpty() }?.let { OcrLine(it, box.centerX(), box.centerY()) }
        }.sortedWith(compareBy({ it.y / ROW_PX }, { it.x }))
    }

    companion object {
        /** Lines whose centres are this close vertically count as one row when sorting. */
        private const val ROW_PX = 24

        /** Fewer readable elements than this: the tree is not enough, recognize the screenshot too. */
        const val SPARSE = 4

        fun render(lines: List<OcrLine>): String = lines.withIndex().joinToString("") { (i, l) -> "[t${i + 1}] \"${l.text}\" @${l.x},${l.y}\n" }
    }
}
