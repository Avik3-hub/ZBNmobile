package com.example.zbnreader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.os.Bundle
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
    private lateinit var palette: ZbnPalette
    private val uiBackground get() = palette.background
    private val uiSurface get() = palette.surface
    private val uiSurfaceRaised get() = palette.surfaceContainer
    private val uiBorder get() = palette.border
    private val uiAccent get() = palette.accent
    private val uiAccentText get() = palette.accentText
    private val uiAmber get() = palette.amber
    private val uiText get() = palette.text
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var editorPanel: LinearLayout
    private lateinit var cropView: DocumentCropView
    private lateinit var reviewPanel: FrameLayout
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
        palette = ZbnTheme.palette(this)
        ZbnTheme.applySystemBars(this, palette)
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
        val root = FrameLayout(this).apply { setBackgroundColor(uiBackground) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(uiBackground)
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            text = "Расположите документ внутри рамки"
            textSize = 13f
            typeface = resources.getFont(R.font.zbn_sans_bold)
            gravity = Gravity.CENTER
            setTextColor(uiText)
            background = rounded(uiSurface, 8f, uiBorder)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(16), dp(14), dp(16), 0)
        })

        captureButton = Button(this).apply {
            text = "СФОТОГРАФИРОВАТЬ"
            stylePrimaryButton(this)
            setOnClickListener { takePhoto() }
        }
        val captureControls = FrameLayout(this).apply {
            addView(captureButton, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM).apply {
                setMargins(dp(22), 0, dp(22), dp(66))
            })
        }
        root.addView(controlsBackdrop(captureControls), FrameLayout.LayoutParams(-1, dp(196), Gravity.BOTTOM))

        // Draw after the lower dock so its bottom edge cannot hide the A4 outline.
        root.addView(A4GuideView(this).apply {
            translationY = -dp(28).toFloat()
        }, FrameLayout.LayoutParams(-1, -1))

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))

        cropView = DocumentCropView(this)
        editorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(uiBackground)
            addView(TextView(context).apply {
                text = "Передвиньте 4 точки точно на углы документа"
                textSize = 13f
                typeface = resources.getFont(R.font.zbn_sans_bold)
                gravity = Gravity.CENTER
                setTextColor(uiText)
                setPadding(dp(12), dp(14), dp(12), dp(10))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(cropView, LinearLayout.LayoutParams(-1, 0, 1f))
            val actions = LinearLayout(this@DocumentScanActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                setPadding(dp(12), dp(10), dp(12), dp(14))
                addView(Button(context).apply {
                    text = "ПЕРЕСНЯТЬ"
                    styleSecondaryButton(this)
                    setOnClickListener { showCamera() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
                addView(Button(context).apply {
                    text = "ОБРЕЗАТЬ"
                    stylePrimaryButton(this)
                    setOnClickListener { cropDocument() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
            }
            addView(controlsBackdrop(actions), LinearLayout.LayoutParams(-1, dp(146)))
        }
        root.addView(editorPanel, FrameLayout.LayoutParams(-1, -1))

        reviewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }
        reviewPanel = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(uiBackground)
            addView(helipadImage(alphaValue = 0.72f, zoom = 2.45f),
                FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM))
            addView(View(this@DocumentScanActivity).apply {
                setBackgroundColor(if (palette.isLight) {
                    Color.argb(150, 238, 242, 246)
                } else {
                    Color.argb(45, 8, 10, 13)
                })
            }, FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM))
            addView(reviewImage, FrameLayout.LayoutParams(-1, -1).apply {
                bottomMargin = dp(200)
            })
            val controls = LinearLayout(this@DocumentScanActivity).apply {
                orientation = LinearLayout.VERTICAL
                filterStatus = TextView(context).apply {
                    text = "ФИЛЬТР: ЦВЕТ"
                    textSize = 11f
                    letterSpacing = 0.08f
                    typeface = resources.getFont(R.font.zbn_sans_bold)
                    gravity = Gravity.CENTER
                    setTextColor(uiAmber)
                    setPadding(0, dp(7), 0, dp(4))
                }
                addView(filterStatus, LinearLayout.LayoutParams(-1, dp(30)))
                addView(LinearLayout(this@DocumentScanActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(8), dp(2), dp(8), dp(4))
                    addFilterButton("ОРИГИНАЛ", DocumentProcessor.Filter.ORIGINAL)
                    addFilterButton("ЦВЕТ", DocumentProcessor.Filter.COLOR)
                    addFilterButton("Ч/Б", DocumentProcessor.Filter.BLACK_WHITE)
                }, LinearLayout.LayoutParams(-1, dp(44)))
                addView(LinearLayout(this@DocumentScanActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), dp(5), dp(12), dp(12))
                    addView(Button(context).apply {
                        text = "ИЗМЕНИТЬ УГЛЫ"
                        styleSecondaryButton(this)
                        setOnClickListener {
                            reviewPanel.visibility = View.GONE
                            editorPanel.visibility = View.VISIBLE
                        }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
                    addView(Button(context).apply {
                        text = "СОХРАНИТЬ JPG"
                        stylePrimaryButton(this)
                        setOnClickListener { savePhoto() }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
                }, LinearLayout.LayoutParams(-1, dp(65)))
            }
            addView(controls, FrameLayout.LayoutParams(-1, dp(139), Gravity.BOTTOM).apply {
                bottomMargin = dp(54)
            })
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
            textSize = 10.5f
            styleSecondaryButton(this)
            setOnClickListener { applyFilter(filter) }
        }, LinearLayout.LayoutParams(0, -1, 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
    }

    private fun controlsBackdrop(content: View): FrameLayout = FrameLayout(this).apply {
        setBackgroundColor(uiBackground)
        addView(helipadImage(alphaValue = 0.34f, zoom = 2.15f), FrameLayout.LayoutParams(-1, -1))
        addView(View(this@DocumentScanActivity).apply {
            setBackgroundColor(if (palette.isLight) {
                Color.argb(170, 238, 242, 246)
            } else {
                Color.argb(92, 8, 10, 13)
            })
        }, FrameLayout.LayoutParams(-1, -1))
        addView(content, FrameLayout.LayoutParams(-1, -1))
    }

    /** Uniformly enlarges the artwork while keeping its lower edge anchored. */
    private fun helipadImage(alphaValue: Float, zoom: Float) = ImageView(this).apply {
        setImageResource(if (palette.isLight) {
            R.drawable.zbn_helipad_light
        } else {
            R.drawable.zbn_helipad_night
        })
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = alphaValue
        contentDescription = null
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        post {
            pivotX = width / 2f
            pivotY = height.toFloat()
            scaleX = zoom
            scaleY = zoom
        }
    }

    private fun stylePrimaryButton(button: Button) = button.apply {
        textSize = 12f
        typeface = resources.getFont(R.font.zbn_sans_bold)
        setTextColor(uiAccentText)
        background = rounded(uiAccent, 7f)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun styleSecondaryButton(button: Button) = button.apply {
        textSize = 11f
        typeface = resources.getFont(R.font.zbn_sans_bold)
        setTextColor(uiText)
        background = rounded(uiSurfaceRaised, 7f, uiBorder)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
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
        val fallbackName = DocumentFileName.create(tail, surname)
        val name = DocumentFileName.normalizeCustomFileName(
            intent.getStringExtra(EXTRA_FILE_NAME).orEmpty(), fallbackName
        )
        val folder = File(ZbnStorage.rootFolder(), "Борт_${tail.ifEmpty { "Неизвестный_Борт" }}")
        if (!folder.exists() && !folder.mkdirs()) {
            Toast.makeText(this, "Не удалось создать папку для снимка", Toast.LENGTH_LONG).show()
            return
        }
        val output = File(folder, name)
        try {
            FileOutputStream(output).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) error("JPEG не записан")
            }
            MediaScannerConnection.scanFile(
                this,
                arrayOf(output.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
            ZbnStorage.markPassportSaved(this)
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
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

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
        const val EXTRA_FILE_NAME = "file_name"
    }
}
