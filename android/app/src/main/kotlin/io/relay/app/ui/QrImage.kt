package io.relay.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders [content] as a QR bitmap: dark modules on white, quiet zone of 1 module.
 *
 * Encoding happens off the main thread — a 640×640 encode plus the pixel fill is
 * long enough to drop frames during the Preparing→Advertising transition, which
 * is exactly when the user is watching. Null until the first bitmap is ready;
 * callers show their own placeholder for that frame.
 */
@Composable
fun rememberQrBitmap(content: String, sizePx: Int = 640): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, content, sizePx) {
        value = withContext(Dispatchers.Default) { encode(content, sizePx) }
    }
    return bitmap
}

/**
 * Returns null when the content cannot be encoded. Today's payloads always fit
 * (the device name is capped), but a future payload that overflows the QR
 * capacity must degrade to "use the typed code" — never crash composition.
 */
private fun encode(content: String, sizePx: Int): ImageBitmap? {
    val matrix = try {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            ),
        )
    } catch (_: WriterException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    val dark = 0xFF14171D.toInt()
    val light = 0xFFFFFFFF.toInt()
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            pixels[y * sizePx + x] = if (matrix[x, y]) dark else light
        }
    }
    // RGB_565 for a two-colour image: same result, a quarter of the memory of
    // ARGB_8888 (0.8 MB vs 1.6 MB at 640²).
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565).asImageBitmap()
}
