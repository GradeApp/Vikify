package vikify.vpn.ui.home

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.IOException

internal object SubscriptionQr {
    private fun decode(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    }

    fun cameraFrame(image: ImageProxy): String? {
        val plane = image.planes.first()
        val buffer = plane.buffer.duplicate()
        val start = buffer.position()
        val width = image.width
        val height = image.height
        val luminance = ByteArray(width * height)
        for (y in 0 until height) {
            buffer.position(start + y * plane.rowStride)
            buffer.get(luminance, y * width, width)
        }
        return decode(PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false))
    }

    fun image(resolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        fun load() = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        return try {
            load()
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            // Bound bitmap and decoder memory even for a full-resolution camera photo.
            var sample = 1
            while (maxOf(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            val bitmap = load() ?: return null
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            try { bitmap.getPixels(pixels, 0, w, 0, 0, w, h) } finally { bitmap.recycle() }
            val source = RGBLuminanceSource(w, h, pixels)
            decode(source) ?: decode(source.invert())
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
