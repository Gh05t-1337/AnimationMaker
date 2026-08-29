package com.autismprime.animationmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

object BitmapUtils {

    /** Reads just the width/height of the image at [uri] without loading pixels. */
    fun readDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        return options.outWidth to options.outHeight
    }

    /**
     * Decodes [uri] downsampled so its largest dimension is close to
     * [reqSize], to keep memory usage predictable regardless of the
     * original photo resolution.
     */
    fun decodeSampled(context: Context, uri: Uri, reqSize: Int): Bitmap? {
        val (w, h) = readDimensions(context, uri)
        if (w <= 0 || h <= 0) return null

        var sampleSize = 1
        var longSide = maxOf(w, h)
        while (longSide / 2 >= reqSize) {
            sampleSize *= 2
            longSide /= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }
}
