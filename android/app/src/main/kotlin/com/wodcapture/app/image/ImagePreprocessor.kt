package com.wodcapture.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Result of preparing one capture: the stored file and its pixel size (for the cost estimate). */
data class PreparedImage(val file: File, val width: Int, val height: Int)

/**
 * Pre-processing before upload (REQ-AC05): apply EXIF rotation, downscale so the long edge is
 * ≤ 2576 px, encode once as JPEG quality 90 and drop all EXIF (location, device) by re-encoding
 * from pixels. The result is stored in app-private storage (REQ-AC07).
 */
class ImagePreprocessor(private val context: Context) {
    private val dir = File(context.filesDir, "captures").apply { mkdirs() }

    fun prepare(uri: Uri): PreparedImage {
        val orientation = context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = sampleSize(max(bounds.outWidth, bounds.outHeight))
        val decoded = context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Not an image")
        val rotated = rotate(decoded, orientation)
        val scaled = downscale(rotated, MAX_LONG_EDGE)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val file = File(dir, "${UUID.randomUUID()}.jpg").apply { writeBytes(out.toByteArray()) }
        return PreparedImage(file, scaled.width, scaled.height)
    }

    /** Power-of-two subsampling that keeps at least the target resolution. */
    private fun sampleSize(longEdge: Int): Int {
        var s = 1
        while (longEdge / (s * 2) >= MAX_LONG_EDGE) s *= 2
        return s
    }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun downscale(b: Bitmap, longEdge: Int): Bitmap {
        val edge = max(b.width, b.height)
        if (edge <= longEdge) return b
        val f = longEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt(), (b.height * f).roundToInt(), true)
    }

    companion object {
        const val MAX_LONG_EDGE = 2576
        const val JPEG_QUALITY = 90
    }
}
