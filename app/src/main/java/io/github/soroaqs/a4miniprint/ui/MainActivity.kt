package io.github.soroaqs.a4miniprint.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.github.soroaqs.a4miniprint.R
import io.github.soroaqs.a4miniprint.config.PrintConfig
import io.github.soroaqs.a4miniprint.data.PhotoMetadata
import io.github.soroaqs.a4miniprint.data.PhotoFitMode
import io.github.soroaqs.a4miniprint.data.PhotoResolutionMode
import io.github.soroaqs.a4miniprint.data.PrintLayoutMode
import io.github.soroaqs.a4miniprint.data.PrintTaskMode
import io.github.soroaqs.a4miniprint.databinding.ActivityMainBinding
import io.github.soroaqs.a4miniprint.image.ImageMetadataLoader
import io.github.soroaqs.a4miniprint.image.PreviewBitmapLoader
import io.github.soroaqs.a4miniprint.layout.ColumnSplitLayoutPlanner
import io.github.soroaqs.a4miniprint.layout.PrintLayoutPlanner
import io.github.soroaqs.a4miniprint.print.PrintJobSubmitter
import io.github.soroaqs.a4miniprint.share.ShareIntentParser
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val config = PrintConfig()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var binding: ActivityMainBinding
    private lateinit var metadataLoader: ImageMetadataLoader
    private lateinit var layoutPlanner: PrintLayoutPlanner
    private lateinit var columnSplitLayoutPlanner: ColumnSplitLayoutPlanner
    private lateinit var previewBitmapLoader: PreviewBitmapLoader
    private lateinit var printJobSubmitter: PrintJobSubmitter
    private lateinit var shareIntentParser: ShareIntentParser

    private var selectedPhotos: List<PhotoMetadata> = emptyList()
    private var previewBitmap: Bitmap? = null
    private var previewLoadGeneration = 0
    private var layoutMode: PrintLayoutMode = config.defaultLayoutMode
    private var taskMode: PrintTaskMode = config.defaultTaskMode
    private var columnCount: Int = config.defaultColumnCount
    private var splitPositions: MutableList<Int> =
        defaultSplitPositions(config.defaultColumnCount).toMutableList()
    private var splitFitMode: PhotoFitMode = PhotoFitMode.CONTAIN
    private var fitMode: PhotoFitMode = config.defaultFitMode
    private var resolutionMode: PhotoResolutionMode = config.defaultResolutionMode

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris ->
        if (uris.isNotEmpty()) {
            loadMetadata(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        metadataLoader = ImageMetadataLoader(contentResolver)
        previewBitmapLoader = PreviewBitmapLoader(contentResolver, config)
        layoutPlanner = PrintLayoutPlanner(config)
        columnSplitLayoutPlanner = ColumnSplitLayoutPlanner(config)
        printJobSubmitter = PrintJobSubmitter(this, config)
        shareIntentParser = ShareIntentParser()

        setupMainUi()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        releasePreviewBitmap()
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setupMainUi() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        binding.versionText.text = getString(
            R.string.app_version,
            versionName ?: "unknown"
        )
        binding.selectedCountText.text = getString(R.string.selected_count, selectedPhotos.size)
        binding.presetLabelText.text = config.preset.displayName

        binding.taskModeGroup.setOnCheckedChangeListener { _, checkedId ->
            taskMode = when (checkedId) {
                R.id.task_split_radio -> PrintTaskMode.COLUMN_SPLIT
                else -> PrintTaskMode.PHOTO_4X6
            }
            updateTaskModeSections()
            refreshPreviewForTaskMode()
        }

        binding.columnCountGroup.setOnCheckedChangeListener { _, checkedId ->
            columnCount = when (checkedId) {
                R.id.column_count_2_radio -> 2
                R.id.column_count_4_radio -> 4
                else -> 3
            }
            splitPositions = defaultSplitPositions(columnCount).toMutableList()
            updateSplitLineControls()
        }

        setSeekProgressListener(binding.splitLine1Seek) { progress ->
            updateSplitPosition(0, progress)
        }

        setSeekProgressListener(binding.splitLine2Seek) { progress ->
            updateSplitPosition(1, progress)
        }

        setSeekProgressListener(binding.splitLine3Seek) { progress ->
            updateSplitPosition(2, progress)
        }

        binding.splitFitModeGroup.setOnCheckedChangeListener { _, checkedId ->
            splitFitMode = when (checkedId) {
                R.id.split_fit_fill_radio -> PhotoFitMode.FILL
                else -> PhotoFitMode.CONTAIN
            }
        }

        binding.selectPhotosButton.setOnClickListener {
            photoPicker.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }

        binding.layoutModeGroup.setOnCheckedChangeListener { _, checkedId ->
            layoutMode = when (checkedId) {
                R.id.mode_single_radio -> PrintLayoutMode.SINGLE_PER_PAGE
                else -> PrintLayoutMode.A4_GRID
            }
        }

        binding.fitModeGroup.setOnCheckedChangeListener { _, checkedId ->
            fitMode = when (checkedId) {
                R.id.fit_cover_radio -> PhotoFitMode.COVER
                else -> PhotoFitMode.CONTAIN
            }
        }

        binding.resolutionModeGroup.setOnCheckedChangeListener { _, checkedId ->
            resolutionMode = when (checkedId) {
                R.id.resolution_original_radio -> PhotoResolutionMode.ORIGINAL
                else -> PhotoResolutionMode.PRINT_300_DPI
            }
        }

        binding.printButton.setOnClickListener {
            startPrint(taskMode, layoutMode, fitMode, resolutionMode)
        }

        updateTaskModeSections()
        updateSplitLineControls()
    }

    private fun setSeekProgressListener(
        seekBar: SeekBar,
        onProgressChanged: (Int) -> Unit
    ) {
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        onProgressChanged(progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
    }

    private fun defaultSplitPositions(columnCount: Int): List<Int> {
        return (1 until columnCount).map { index ->
            index * 1000 / columnCount
        }
    }

    private fun updateTaskModeSections() {
        val isPhotoMode = taskMode == PrintTaskMode.PHOTO_4X6
        binding.photoOptionsSection.visibility = if (isPhotoMode) View.VISIBLE else View.GONE
        binding.splitOptionsSection.visibility = if (isPhotoMode) View.GONE else View.VISIBLE
    }

    private fun updateSplitLineControls() {
        val lineCount = columnCount - 1
        val lineViews = listOf(
            binding.splitLine1Label to binding.splitLine1Seek,
            binding.splitLine2Label to binding.splitLine2Seek,
            binding.splitLine3Label to binding.splitLine3Seek
        )

        lineViews.forEachIndexed { index, (label, seekBar) ->
            val visible = index < lineCount
            label.visibility = if (visible) View.VISIBLE else View.GONE
            seekBar.visibility = if (visible) View.VISIBLE else View.GONE

            if (visible) {
                seekBar.progress = splitPositions[index]
            }
        }

        binding.splitPreview.update(
            bitmap = previewBitmap,
            positions = splitPositions.take(columnCount - 1)
        )
    }

    private fun updateSplitPosition(index: Int, progress: Int) {
        if (index >= splitPositions.size) {
            return
        }

        val previous = splitPositions.getOrNull(index - 1) ?: 1
        val next = splitPositions.getOrNull(index + 1) ?: 999
        splitPositions[index] = progress.coerceIn(previous + 1, next - 1)
        updateSplitLineControls()
    }

    /**
     * 只维护一份预览 Bitmap，主界面和分享弹窗共享同一个对象。
     * 替换或清理前先把所有 View 引用切走，再回收旧 Bitmap，避免预览控件使用已回收对象。
     */
    private fun setPreviewBitmap(bitmap: Bitmap?) {
        if (previewBitmap === bitmap) {
            return
        }

        val oldBitmap = previewBitmap
        previewBitmap = bitmap
        binding.splitPreview.update(
            bitmap = bitmap,
            positions = splitPositions.take(columnCount - 1)
        )
        oldBitmap?.recycle()
    }

    private fun releasePreviewBitmap() {
        previewLoadGeneration++
        setPreviewBitmap(null)
    }

    private fun refreshPreviewForTaskMode() {
        if (taskMode == PrintTaskMode.COLUMN_SPLIT) {
            loadSplitPreviewBitmap()
        } else {
            releasePreviewBitmap()
        }
    }

    private fun loadSplitPreviewBitmap() {
        val photo = selectedPhotos.singleOrNull() ?: run {
            releasePreviewBitmap()
            return
        }

        val generation = ++previewLoadGeneration
        executor.execute {
            val bitmap = try {
                previewBitmapLoader.load(photo)
            } catch (_: Exception) {
                null
            }

            mainHandler.post {
                if (isDestroyed || isFinishing || generation != previewLoadGeneration) {
                    bitmap?.recycle()
                    return@post
                }
                setPreviewBitmap(bitmap)
            }
        }
    }

    private fun handleShareIntent(intent: Intent) {
        val uris = shareIntentParser.parse(intent)
        if (uris.isEmpty()) {
            return
        }

        loadMetadata(uris)
    }

    private fun loadMetadata(
        uris: List<Uri>
    ) {
        binding.selectedCountText.text = getString(R.string.loading_photos)
        binding.printButton.isEnabled = false
        releasePreviewBitmap()

        executor.execute {
            try {
                val metadata = uris.map { uri ->
                    metadataLoader.load(uri)
                }

                mainHandler.post {
                    if (isDestroyed || isFinishing) {
                        return@post
                    }
                    selectedPhotos = metadata
                    // 每次收到新的图片任务都回到默认 4×6 模式，
                    // 避免上一次的分栏选择影响下一次打印。
                    taskMode = config.defaultTaskMode
                    binding.taskModeGroup.check(R.id.task_photo_radio)
                    binding.selectedCountText.text = getString(
                        R.string.selected_count,
                        selectedPhotos.size
                    )
                    binding.printButton.isEnabled = selectedPhotos.isNotEmpty()
                    refreshPreviewForTaskMode()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (isDestroyed || isFinishing) {
                        return@post
                    }
                    selectedPhotos = emptyList()
                    releasePreviewBitmap()
                    binding.selectedCountText.text = getString(
                        R.string.selected_count,
                        0
                    )
                    binding.printButton.isEnabled = false
                    Toast.makeText(
                        this,
                        getString(R.string.read_photo_failed, error.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun startPrint(
        taskMode: PrintTaskMode,
        layoutMode: PrintLayoutMode,
        fitMode: PhotoFitMode,
        resolutionMode: PhotoResolutionMode
    ) {
        if (selectedPhotos.isEmpty()) {
            Toast.makeText(this, R.string.select_photos_first, Toast.LENGTH_SHORT).show()
            return
        }

        val pages = when (taskMode) {
            PrintTaskMode.PHOTO_4X6 -> layoutPlanner.plan(
                photos = selectedPhotos,
                mode = layoutMode,
                fitMode = fitMode,
                resolutionMode = resolutionMode
            )
            PrintTaskMode.COLUMN_SPLIT -> {
                if (selectedPhotos.size != 1) {
                    Toast.makeText(
                        this,
                        R.string.split_requires_single_photo,
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }

                columnSplitLayoutPlanner.plan(
                    photo = selectedPhotos.first(),
                    columnCount = columnCount,
                    splitPositions = splitPositions.take(columnCount - 1),
                    fitMode = splitFitMode,
                    resolutionMode = resolutionMode
                )
            }
        }

        printJobSubmitter.submit(pages)
    }
}
