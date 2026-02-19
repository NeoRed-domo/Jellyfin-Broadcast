package com.jellyfinbroadcast.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

private const val TAG = "QrScanner"

@Composable
fun QrScannerScreen(
    onQrCodeScanned: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var scanned by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission && !scanned) {
            CameraPreviewWithScanner(
                onQrCodeDetected = { url ->
                    if (!scanned) {
                        scanned = true
                        Log.i(TAG, "QR code scanned: $url")
                        onQrCodeScanned(url)
                    }
                }
            )

            // Scanning frame overlay
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Semi-transparent overlay
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // QR frame
                    Box(
                        modifier = Modifier
                            .size(250.dp)
                            .border(3.dp, Color(0xFF00A4DC), RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Placez le QR code dans le cadre",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (!hasCameraPermission) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Permission caméra requise\npour scanner le QR code",
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun CameraPreviewWithScanner(
    onQrCodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    val scanner = remember { BarcodeScanning.getClient(options) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val inputImage = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        scanner.process(inputImage)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    barcode.rawValue?.let { value ->
                                        if (value.startsWith("http")) {
                                            onQrCodeDetected(value)
                                        }
                                    }
                                }
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } else {
                        imageProxy.close()
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}
