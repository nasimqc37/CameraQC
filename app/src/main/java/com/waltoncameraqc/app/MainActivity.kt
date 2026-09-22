package com.waltoncameraqc.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * CameraQC starter app.
 * Captures a photo and scores it for:
 *  - Sharpness: Laplacian variance (higher = sharper)
 *  - Noise: Immerkær noise estimation (higher = noisier)
 *
 * Same metrics used in the Mobile QM CameraQC tool.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var captureButton: Button
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        resultText = findViewById(R.id.resultText)
        captureButton = findViewById(R.id.captureButton)
        cameraExecutor = Executors.newSingleThreadExecutor()

        captureButton.setOnClickListener { capturePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val photoFile = File(cacheDir, "$name.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    cameraExecutor.execute {
                        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                        if (bitmap != null) {
                            val gray = toGrayscale(bitmap)
                            val sharpness = laplacianVarianceScore(gray)
                            val noise = immerkaerNoiseScore(gray)
                            runOnUiThread {
                                resultText.text = "Sharpness: %.1f  |  Noise: %.2f".format(sharpness, noise)
                            }
                        }
                    }
                }
            }
        )
    }

    /** Converts a bitmap to a 2D grayscale intensity array (0-255). */
    private fun toGrayscale(bitmap: Bitmap): Array<IntArray> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = Array(height) { IntArray(width) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                gray[y][x] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }
        }
        return gray
    }

    /**
     * Sharpness via variance of the Laplacian (kernel: 0 -1 0 / -1 4 -1 / 0 -1 0).
     * Higher variance = more edge energy = sharper image.
     */
    private fun laplacianVarianceScore(gray: Array<IntArray>): Double {
        val height = gray.size
        val width = gray[0].size
        val values = ArrayList<Double>()

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val lap = (4 * gray[y][x]) - gray[y - 1][x] - gray[y + 1][x] -
                        gray[y][x - 1] - gray[y][x + 1]
                values.add(lap.toDouble())
            }
        }

        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return variance
    }

    /**
     * Noise estimation per Immerkær (1996): convolve with a Laplacian-of-Gaussian-like
     * mask (1 -2 1 / -2 4 -2 / 1 -2 1), then sigma = sqrt(pi/2) * sum(|conv|) / (6*(W-2)*(H-2)).
     */
    private fun immerkaerNoiseScore(gray: Array<IntArray>): Double {
        val height = gray.size
        val width = gray[0].size
        var sumAbs = 0.0

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val conv = 1 * gray[y - 1][x - 1] - 2 * gray[y - 1][x] + 1 * gray[y - 1][x + 1] +
                        -2 * gray[y][x - 1] + 4 * gray[y][x] - 2 * gray[y][x + 1] +
                        1 * gray[y + 1][x - 1] - 2 * gray[y + 1][x] + 1 * gray[y + 1][x + 1]
                sumAbs += abs(conv.toDouble())
            }
        }

        val denom = 6.0 * (width - 2) * (height - 2)
        return sqrt(PI / 2.0) * sumAbs / denom
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
