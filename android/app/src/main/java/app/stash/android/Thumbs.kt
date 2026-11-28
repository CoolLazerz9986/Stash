// Path: android/app/src/main/java/app/stash/android/Thumbs.kt
package app.stash.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Small JPEG previews (about 320 px on the long side, 10-30 KB) for photos and videos, uploaded next to the file so the
 * website's grid never has to load the originals. Best effort: any problem returns null and the backup carries on.
 * Nothing is read into memory at full size: photos are decoded at a reduced size straight from the stream.
 */
object Thumbs {
    private const val EDGE = 320
    private const val MAX_BYTES = 120_000 // the server accepts at most 128 KB

    fun make(c: Context, uri: Uri, mime: String): ByteArray? {
        return try {
            val bmp = when {
                mime.startsWith("image/") -> image(c, uri)
                mime.startsWith("video/") -> video(c, uri)
                else -> null
            } ?: return null
            try { encode(bmp) } finally { bmp.recycle() }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun image(c: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val turn = try {
            c.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        return fit(raw, turn)
    }

    private fun video(c: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c, uri)
            val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            return fit(frame, 0f)
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    /** Scales so the long side is at most EDGE, applying [turn] degrees. Recycles [src] when it makes a new bitmap. */
    private fun fit(src: Bitmap, turn: Float): Bitmap {
        val scale = minOf(1f, EDGE.toFloat() / maxOf(src.width, src.height))
        if (scale == 1f && turn == 0f) return src
        val m = Matrix().apply { postScale(scale, scale); if (turn != 0f) postRotate(turn) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun encode(b: Bitmap): ByteArray? {
        for (q in intArrayOf(72, 55, 40)) {
            val out = ByteArrayOutputStream()
            if (!b.compress(Bitmap.CompressFormat.JPEG, q, out)) return null
            if (out.size() <= MAX_BYTES) return out.toByteArray()
        }
        return null
    }
}
