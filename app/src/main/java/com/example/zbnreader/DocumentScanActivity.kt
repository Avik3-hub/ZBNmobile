package com.example.zbnreader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class DocumentScanActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var editorPanel: LinearLayout
    private lateinit var cropView: DocumentCropView
    private lateinit var reviewPanel: LinearLayout
    private lateinit var reviewImage: ImageView
    private lateinit var filterStatus: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var imageCapture: ImageCapture? = null
    private var sourceBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null
    private var currentCorners: Array<org.opencv.core.Point>? = null
    private var processingFilter = false
    private val worker = Executors.newSingleThreadExecutor()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Без доступа к камере съёмка невозможна", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "Не удалось запустить обработку документов", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        root.addView(A4GuideView(this), FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            text = "Расположите документ внутри рамки"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(16), dp(20), dp(16), 0)
        })

        captureButton = Button(this).apply {
            text = "СФОТОГРАФИРОВАТЬ"
            setOnClickListener { takePhoto() }
        }
        root.addView(captureButton, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM).apply {
            setMargins(dp(24), 0, dp(24), dp(28))
        })

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))

        cropView = DocumentCropView(this)
        editorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            addView(TextView(context).apply {
                text = "Передвиньте 4 точки точно на углы документа"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(cropView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(this@DocumentScanActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(8), dp(12), dp(20))
                addView(Button(context).apply {
                    text = "ПЕРЕСНЯТЬ"
                    setOnClickListener { showCamera() }
                }, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(6) })
                addView(Button(context).apply {
                    text = "ОБРЕЗАТЬ"
                    setOnClickListener { cropDocument() }
                }, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(editorPanel, FrameLayout.LayoutParams(-1, -1))

        reviewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        reviewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            addView(reviewImage, LinearLayout.LayoutParams(-1, 0, 1f))
            filterStatus = TextView(context).apply {
                text = "Фильтр: ЦВЕТ"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(6), 0, dp(2))
            }
            addView(filterStatus, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(this@DocumentScanActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                addFilterButton("ОРИГИНАЛ", DocumentProcessor.Filter.ORIGINAL)
                addFilterButton("ЦВЕТ", DocumentProcessor.Filter.COLOR)
                addFilterButton("Ч/Б", DocumentProcessor.Filter.BLACK_WHITE)
            }, LinearLayout.LayoutParams(-1, dp(54)))
            addView(LinearLayout(this@DocumentScanActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(8), dp(12), dp(20))
                addView(Button(context).apply {
                    text = "ИЗМЕНИТЬ УГЛЫ"
                    setOnClickListener {
                        reviewPanel.visibility = View.GONE
                        editorPanel.visibility = View.VISIBLE
                    }
                }, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(6) })
                addView(Button(context).apply {
                    text = "СОХРАНИТЬ JPEG"
                    setOnClickListener { savePhoto() }
                }, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(reviewPanel, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val target = targetSize(intent.getIntExtra(EXTRA_MEGAPIXELS, 5))
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(
                    target,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                ))
                .build()
            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(selector)
                .build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (error: Exception) {
                Toast.makeText(this, "Камера не запустилась: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        captureButton.isEnabled = false
        val rawFile = File.createTempFile("bur1_", ".jpg", cacheDir)
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(rawFile).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    status.text = "Определяю углы документа..."
                    progress.visibility = View.VISIBLE
                    worker.execute {
                        try {
                            val bitmap = loadOriented(rawFile)
                            val detection = DocumentProcessor.detectCorners(bitmap)
                            val automaticResult = if (detection.edgesFound) {
                                DocumentProcessor.crop(bitmap, detection.corners, DocumentProcessor.Filter.COLOR)
                            } else {
                                null
                            }
                            rawFile.delete()
                            runOnUiThread {
                                sourceBitmap = bitmap
                                currentCorners = detection.corners
                                cropView.setDocument(bitmap, detection.corners)
                                progress.visibility = View.GONE
                                if (automaticResult != null) {
                                    processedBitmap = automaticResult
                                    reviewImage.setImageBitmap(automaticResult)
                                    reviewPanel.visibility = View.VISIBLE
                                    status.text = "Документ найден и выровнен"
                                } else {
                                    editorPanel.visibility = View.VISIBLE
                                    status.text = "Уточните углы документа"
                                }
                            }
                        } catch (error: Exception) {
                            runOnUiThread {
                                rawFile.delete()
                                progress.visibility = View.GONE
                                captureButton.isEnabled = true
                                status.text = "Не удалось обработать снимок"
                                Toast.makeText(this@DocumentScanActivity, error.localizedMessage, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true
                    Toast.makeText(this@DocumentScanActivity, "Ошибка съёмки: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun showCamera() {
        editorPanel.visibility = View.GONE
        reviewPanel.visibility = View.GONE
        sourceBitmap?.recycle()
        sourceBitmap = null
        currentCorners = null
        processedBitmap?.recycle()
        processedBitmap = null
        captureButton.isEnabled = true
        status.text = "Расположите документ внутри рамки"
    }

    private fun cropDocument() {
        editorPanel.visibility = View.GONE
        currentCorners = cropView.documentCorners()
        applyFilter(DocumentProcessor.Filter.COLOR)
    }

    private fun LinearLayout.addFilterButton(label: String, filter: DocumentProcessor.Filter) {
        addView(Button(context).apply {
            text = label
            textSize = 11f
            setOnClickListener { applyFilter(filter) }
        }, LinearLayout.LayoutParams(0, -1, 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
    }

    private fun applyFilter(filter: DocumentProcessor.Filter) {
        val source = sourceBitmap ?: return
        val corners = currentCorners ?: return
        if (processingFilter) return
        processingFilter = true
        progress.visibility = View.VISIBLE
        status.text = "Применяю фильтр..."
        filterStatus.text = "Обработка..."
        worker.execute {
            try {
                val output = DocumentProcessor.crop(source, corners, filter)
                runOnUiThread {
                    processedBitmap?.recycle()
                    processedBitmap = output
                    reviewImage.setImageBitmap(output)
                    reviewPanel.visibility = View.VISIBLE
                    progress.visibility = View.GONE
                    processingFilter = false
                    filterStatus.text = "Фильтр: " + when (filter) {
                        DocumentProcessor.Filter.ORIGINAL -> "ОРИГИНАЛ"
                        DocumentProcessor.Filter.COLOR -> "ЦВЕТ"
                        DocumentProcessor.Filter.BLACK_WHITE -> "Ч/Б"
                    }
                    status.text = "Документ готов к сохранению"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    editorPanel.visibility = View.VISIBLE
                    progress.visibility = View.GONE
                    processingFilter = false
                    Toast.makeText(this, "Проверьте положение углов", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun savePhoto() {
        val bitmap = processedBitmap ?: return
        val tail = DocumentFileName.normalizeTailNumber(intent.getStringExtra(EXTRA_TAIL).orEmpty())
        val surname = intent.getStringExtra(EXTRA_SURNAME).orEmpty()
        val name = DocumentFileName.create(tail, surname)
        val folder = File(Environment.getExternalStorageDirectory(), "ZBNreader/Борт_${tail.ifEmpty { "Неизвестный_Борт" }}")
        if (!folder.exists() && !folder.mkdirs()) {
            Toast.makeText(this, "Не удалось создать папку для снимка", Toast.LENGTH_LONG).show()
            return
        }
        val output = File(folder, name)
        try {
            FileOutputStream(output).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) error("JPEG не записан")
            }
            Toast.makeText(this, "Сохранено: ${output.absolutePath}", Toast.LENGTH_LONG).show()
            finish()
        } catch (error: Exception) {
            Toast.makeText(this, "Ошибка сохранения: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadOriented(file: File): Bitmap {
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: error("Снимок не читается")
        val degrees = when (ExifInterface(file).rotationDegrees) { 90 -> 90f; 180 -> 180f; 270 -> 270f; else -> 0f }
        if (degrees == 0f) return source
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees) }, true)
        source.recycle()
        return rotated
    }

    private fun targetSize(megapixels: Int): Size = when (megapixels) {
        1 -> Size(1152, 864)
        2 -> Size(1632, 1224)
        3 -> Size(2000, 1500)
        8 -> Size(3264, 2448)
        12 -> Size(4000, 3000)
        else -> Size(2592, 1944)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        worker.shutdown()
        sourceBitmap?.recycle()
        processedBitmap?.recycle()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TAIL = "tail"
        const val EXTRA_SURNAME = "surname"
        const val EXTRA_MEGAPIXELS = "megapixels"
    }
}
