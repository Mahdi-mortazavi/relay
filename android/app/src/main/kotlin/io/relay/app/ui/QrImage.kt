package io.relay.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Renders [content] as a QR bitmap: dark modules on white, quiet zone of 1 module. */
@Composable
fun rememberQrBitmap(content: String, sizePx: Int = 640): ImageBitmap =
    remember(content, sizePx) {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            ),
        )
        val dark = 0xFF14171D.toInt()
        val light = 0xFFFFFFFF.toInt()
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] = if (matrix[x, y]) dark else light
            }
        }
        Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
