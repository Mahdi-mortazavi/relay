package io.relay.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.relay.app.R
import io.relay.app.ui.theme.LocalGlass
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
fun QrCameraScannerDialog(
    onQrScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val glass = LocalGlass.current
    val composeLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss), color = glass.accent)
            }
        },
        title = {
            Text(
                stringResource(R.string.scan_pc_qr_title),
                style = MaterialTheme.typography.titleMedium,
                color = glass.textPrimary
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }

                        val targetLifecycle = ctx.findLifecycleOwner() ?: composeLifecycleOwner
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                        val mlKitScanner = BarcodeScanning.getClient(
                            BarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .build()
                        )

                        val zxingReader = QRCodeReader()
                        val zxingHints = mapOf(
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                            DecodeHintType.TRY_HARDER to true,
                            DecodeHintType.CHARACTER_SET to "UTF-8"
                        )

                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                var isScanned = false

                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    if (isScanned) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }

                                    // 1. Try Google ML Kit
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val inputImage = InputImage.fromMediaImage(
                                            mediaImage,
                                            imageProxy.imageInfo.rotationDegrees
                                        )

                                        mlKitScanner.process(inputImage)
                                            .addOnSuccessListener { barcodes ->
                                                if (!isScanned && barcodes.isNotEmpty()) {
                                                    val rawValue = barcodes.firstOrNull()?.rawValue
                                                    if (!rawValue.isNullOrBlank()) {
                                                        isScanned = true
                                                        Log.d("QR_SCAN", "MLKit detected QR: $rawValue")
                                                        vibratePhone(ctx)
                                                        androidx.core.content.ContextCompat.getMainExecutor(ctx).execute {
                                                            onQrScanned(rawValue)
                                                        }
                                                    }
                                                }
                                            }
                                            .addOnFailureListener { e ->
                                                Log.w("QR_SCAN", "MLKit analyze fallback: ${e.message}")
                                            }
                                    }

                                    // 2. Dual Engine: Instant offline ZXing fallback on the same frame
                                    if (!isScanned) {
                                        val zxingResult = decodeZxing(imageProxy, zxingReader, zxingHints)
                                        if (!zxingResult.isNullOrBlank() && !isScanned) {
                                            isScanned = true
                                            Log.d("QR_SCAN", "ZXing detected QR: $zxingResult")
                                            vibratePhone(ctx)
                                            androidx.core.content.ContextCompat.getMainExecutor(ctx).execute {
                                                onQrScanned(zxingResult)
                                            }
                                        }
                                    }

                                    imageProxy.close()
                                }

                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    targetLifecycle,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Reticle frame overlay
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .border(3.dp, glass.accent, RoundedCornerShape(16.dp))
                )
            }
        }
    )
}

private fun decodeZxing(
    imageProxy: ImageProxy,
    reader: QRCodeReader,
    hints: Map<DecodeHintType, Any>
): String? {
    val plane = imageProxy.planes.getOrNull(0) ?: return null
    val buffer = plane.buffer
    val width = imageProxy.width
    val height = imageProxy.height
    val rotation = imageProxy.imageInfo.rotationDegrees

    val yBytes = ByteArray(buffer.remaining())
    val pos = buffer.position()
    buffer.get(yBytes)
    buffer.position(pos)

    val (rotatedData, newW, newH) = rotateY(yBytes, width, height, rotation)
    val source = PlanarYUVLuminanceSource(rotatedData, newW, newH, 0, 0, newW, newH, false)

    // 1. HybridBinarizer
    try {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = reader.decode(bitmap, hints).text
        reader.reset()
        if (!result.isNullOrBlank()) return result
    } catch (_: Exception) {
        reader.reset()
    }

    // 2. GlobalHistogramBinarizer
    try {
        val bitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
        val result = reader.decode(bitmap, hints).text
        reader.reset()
        if (!result.isNullOrBlank()) return result
    } catch (_: Exception) {
        reader.reset()
    }

    // 3. Unrotated fallback
    if (rotation != 0) {
        val rawSource = PlanarYUVLuminanceSource(yBytes, width, height, 0, 0, width, height, false)
        try {
            val bitmap = BinaryBitmap(HybridBinarizer(rawSource))
            val result = reader.decode(bitmap, hints).text
            reader.reset()
            if (!result.isNullOrBlank()) return result
        } catch (_: Exception) {
            reader.reset()
        }
    }

    return null
}

private fun rotateY(src: ByteArray, width: Int, height: Int, rotation: Int): Triple<ByteArray, Int, Int> {
    return when (rotation) {
        90 -> {
            val rotated = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val srcIdx = y * width + x
                    val dstIdx = x * height + (height - 1 - y)
                    if (srcIdx < src.size && dstIdx < rotated.size) {
                        rotated[dstIdx] = src[srcIdx]
                    }
                }
            }
            Triple(rotated, height, width)
        }
        270 -> {
            val rotated = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val srcIdx = y * width + x
                    val dstIdx = (width - 1 - x) * height + y
                    if (srcIdx < src.size && dstIdx < rotated.size) {
                        rotated[dstIdx] = src[srcIdx]
                    }
                }
            }
            Triple(rotated, height, width)
        }
        180 -> {
            val rotated = ByteArray(width * height)
            for (i in 0 until minOf(src.size, width * height)) {
                rotated[width * height - 1 - i] = src[i]
            }
            Triple(rotated, width, height)
        }
        else -> Triple(src, width, height)
    }
}

private fun vibratePhone(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(80)
        }
    } catch (_: Exception) { }
}

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var cur: Context? = this
    while (cur is android.content.ContextWrapper) {
        if (cur is LifecycleOwner) return cur
        cur = cur.baseContext
    }
    return null
}
