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
import io.github.soroaqs.a4miniprint.data.PhotoSourceType
import io.github.soroaqs.a4miniprint.data.PrintLayoutMode
import io.github.soroaqs.a4miniprint.data.PrintTaskMode
import io.github.soroaqs.a4miniprint.databinding.ActivityMainBinding
import io.github.soroaqs.a4miniprint.image.ImageMetadataLoader
import io.github.soroaqs.a4miniprint.image.PdfMetadataLoader
import io.github.soroaqs.a4miniprint.image.PdfSource
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
    private lateinit var pdfMetadataLoader: PdfMetadataLoader
    private lateinit var layoutPlanner: PrintLayoutPlanner
    private lateinit var columnSplitLayoutPlanner: ColumnSplitLayoutPlanner
    private lateinit var previewBitmapLoader: PreviewBitmapLoader
    private lateinit var printJobSubmitter: PrintJobSubmitter
    private lateinit var shareIntentParser: ShareIntentParser

    private var selectedPhotos: List<PhotoMetadata> = emptyList()
    private var selectedPdf: PdfSource? = null
    private var previewBitmap: Bitmap? = null
    private var previewLoadGeneration = 0
    private var layoutMode: PrintLayoutMode = config.defaultLayoutMode
    private var taskMode: PrintTaskMode = config.defaultTaskMode
    private var columnCount: Int = config.defaultColumnCount
    // 每个源页一份切分线；图片只有一项，PDF 每页各自独立。
    private var splitPositionsByPage: MutableList<MutableList<Int>> =
        mutableListOf(defaultSplitPositions(config.defaultColumnCount).toMutableList())
    private var previewPageIndex: Int = 0
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

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            loadPdfMetadata(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        metadataLoader = ImageMetadataLoader(contentResolver)
        pdfMetadataLoader = PdfMetadataLoader(contentResolver, cacheDir)
        previewBitmapLoader = PreviewBitmapLoader(contentResolver, config)
        layoutPlanner = PrintLayoutPlanner(config)
        columnSplitLayoutPlanner = ColumnSplitLayoutPlanner(config)
        printJobSubmitter = PrintJobSubmitter(this, config)
        shareIntentParser = ShareIntentParser()

        setupMainUi()
        if (savedInstanceState == null) {
            // 只在冷启动清理旧的 PDF 临时副本；旋转屏幕时可能还有打印任务
            // 正在使用当前副本，不能顺手删掉。
            executor.execute { pdfMetadataLoader.clearCachedPdfs() }
        }
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
            // 栏数变化后旧切分线无法沿用，所有页都回到等分。
            resetSplitPositionsForAllPages()
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

        binding.splitPrevPageButton.setOnClickListener {
            showPreviewPage(previewPageIndex - 1)
        }

        binding.splitNextPageButton.setOnClickListener {
            showPreviewPage(previewPageIndex + 1)
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

        binding.selectPdfButton.setOnClickListener {
            pdfPicker.launch(arrayOf(MIME_PDF))
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

    /**
     * 栏数变化时重建全部源页的切分线，旧线无法按新栏数沿用。
     */
    private fun resetSplitPositionsForAllPages() {
        val pageCount = splitPositionsByPage.size.coerceAtLeast(1)
        splitPositionsByPage = MutableList(pageCount) {
            defaultSplitPositions(columnCount).toMutableList()
        }
    }

    /**
     * 载入新输入时清空旧的逐页切分线，全部回到等分。
     */
    private fun resetSplitPositionsForSelection(pageCount: Int) {
        previewPageIndex = 0
        splitPositionsByPage = MutableList(pageCount.coerceAtLeast(1)) {
            defaultSplitPositions(columnCount).toMutableList()
        }
    }

    private fun currentSplitPositions(): MutableList<Int> {
        return splitPositionsByPage.getOrNull(previewPageIndex)
            ?: splitPositionsByPage.first()
    }

    private fun updateTaskModeSections() {
        val isPhotoMode = taskMode == PrintTaskMode.PHOTO_4X6
        binding.photoOptionsSection.visibility = if (isPhotoMode) View.VISIBLE else View.GONE
        binding.splitOptionsSection.visibility = if (isPhotoMode) View.GONE else View.VISIBLE

        // PDF 只用于分栏拆印：锁定 4×6 选项，固定 300DPI，并提示逐页切分线。
        val isPdf = selectedPhotos.firstOrNull()?.sourceType == PhotoSourceType.PDF_PAGE
        binding.taskPhotoRadio.isEnabled = !isPdf
        binding.resolutionSection.visibility = if (isPdf) View.GONE else View.VISIBLE
        binding.splitPdfNoteText.visibility = if (isPdf) View.VISIBLE else View.GONE
        updatePageNavigation()
    }

    /**
     * PDF 多页时显示翻页按钮，切分线滑杆只编辑当前页。
     */
    private fun updatePageNavigation() {
        val pageCount = selectedPdf?.pageCount ?: 0
        val showNavigation = pageCount > 1
        binding.splitPageNav.visibility = if (showNavigation) View.VISIBLE else View.GONE
        if (!showNavigation) {
            return
        }

        binding.splitPageIndicatorText.text = getString(
            R.string.pdf_page_indicator,
            previewPageIndex + 1,
            pageCount
        )
        binding.splitPrevPageButton.isEnabled = previewPageIndex > 0
        binding.splitNextPageButton.isEnabled = previewPageIndex < pageCount - 1
    }

    private fun updateSplitLineControls() {
        updateSplitSliderValues()
        binding.splitPreview.update(
            bitmap = previewBitmap,
            positions = currentSplitPositions().take(columnCount - 1)
        )
        updatePageNavigation()
    }

    /**
     * 只更新滑杆数值，不动预览上的红线。
     *
     * 翻页时要等新预览图加载完成，再让红线、滑杆一起切到新页，
     * 避免红线先于图片跳到新位置。
     */
    private fun updateSplitSliderValues() {
        val positions = currentSplitPositions()
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
                seekBar.progress = positions[index]
            }
        }
    }

    private fun updateSplitPosition(index: Int, progress: Int) {
        val positions = currentSplitPositions()
        if (index >= positions.size) {
            return
        }

        val previous = positions.getOrNull(index - 1) ?: 1
        val next = positions.getOrNull(index + 1) ?: 999
        positions[index] = progress.coerceIn(previous + 1, next - 1)
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
            positions = currentSplitPositions().take(columnCount - 1)
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
        // 图片分栏仍要求单张；PDF 加载当前预览页，切分线逐页独立。
        val photo = when {
            selectedPhotos.isEmpty() -> null
            selectedPhotos.first().sourceType == PhotoSourceType.PDF_PAGE ->
                selectedPhotos.getOrNull(previewPageIndex)
            else -> selectedPhotos.singleOrNull()
        } ?: run {
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
                // 翻页时滑杆与红线一起等新预览图就绪后再更新。
                updateSplitSliderValues()
            }
        }
    }

    /**
     * 切换 PDF 预览页。切分线滑杆随之切到该页自己的那一份。
     */
    private fun showPreviewPage(index: Int) {
        val pageCount = selectedPdf?.pageCount ?: return
        if (index !in 0 until pageCount) {
            return
        }

        previewPageIndex = index
        // 先只响应页码和按钮；预览图、红线、滑杆在加载完成后一起更新。
        updatePageNavigation()
        loadSplitPreviewBitmap()
    }

    private fun handleShareIntent(intent: Intent) {
        val uris = shareIntentParser.parse(intent)
        if (uris.isEmpty()) {
            return
        }

        val pdfUris = uris.filter { isPdf(it, intent.type) }
        when {
            pdfUris.isEmpty() -> loadMetadata(uris)
            // 一次只支持一个 PDF，也不支持图片和 PDF 混合。
            uris.size > 1 -> Toast.makeText(
                this,
                R.string.pdf_single_file_only,
                Toast.LENGTH_LONG
            ).show()
            else -> loadPdfMetadata(pdfUris.first())
        }
    }

    private fun isPdf(uri: Uri, intentType: String?): Boolean {
        // 有些 provider 把 PDF 返回成 application/octet-stream，
        // 因此 intent 自带的 MIME 也作为正向信号。
        val type = contentResolver.getType(uri)
        if (type == MIME_PDF || intentType == MIME_PDF) {
            return true
        }
        // file:// 来源的 getType 通常为 null，用扩展名兜底。
        return uri.scheme == "file" &&
            uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true
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
                    val previousWasPdf = selectedPdf != null
                    selectedPdf = null
                    selectedPhotos = metadata
                    if (previousWasPdf) {
                        // 从 PDF 切回图片：旧的多页切分线不适用，回到等分。
                        resetSplitPositionsForSelection(pageCount = 1)
                    } else {
                        // 图片之间沿用上一次调整的切分线（保持原有行为）。
                        previewPageIndex = 0
                    }
                    // 每次收到新的图片任务都回到默认 4×6 模式，
                    // 避免上一次的分栏选择影响下一次打印。
                    taskMode = config.defaultTaskMode
                    binding.taskModeGroup.check(R.id.task_photo_radio)
                    binding.selectedCountText.text = getString(
                        R.string.selected_count,
                        selectedPhotos.size
                    )
                    binding.printButton.isEnabled = selectedPhotos.isNotEmpty()
                    updateTaskModeSections()
                    updateSplitLineControls()
                    refreshPreviewForTaskMode()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (isDestroyed || isFinishing) {
                        return@post
                    }
                    selectedPdf = null
                    selectedPhotos = emptyList()
                    resetSplitPositionsForSelection(pageCount = 1)
                    releasePreviewBitmap()
                    binding.selectedCountText.text = getString(
                        R.string.selected_count,
                        0
                    )
                    binding.printButton.isEnabled = false
                    updateTaskModeSections()
                    updateSplitLineControls()
                    Toast.makeText(
                        this,
                        getString(R.string.read_photo_failed, error.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun loadPdfMetadata(uri: Uri) {
        binding.selectedCountText.text = getString(R.string.loading_pdf)
        binding.printButton.isEnabled = false
        releasePreviewBitmap()

        executor.execute {
            try {
                // 选择新 PDF 时清理旧副本；不要等到 onWrite 结束再删。
                pdfMetadataLoader.clearCachedPdfs()
                val source = pdfMetadataLoader.load(uri)

                mainHandler.post {
                    if (isDestroyed || isFinishing) {
                        source.cachedFile?.delete()
                        return@post
                    }
                    selectedPdf = source
                    selectedPhotos = source.pages
                    // 每页各有一份切分线，载入时全部回到等分。
                    resetSplitPositionsForSelection(source.pageCount)
                    // PDF 只用于分栏拆印，载入后直接切到该模式。
                    val alreadySplit =
                        binding.taskModeGroup.checkedRadioButtonId == R.id.task_split_radio
                    taskMode = PrintTaskMode.COLUMN_SPLIT
                    binding.taskModeGroup.check(R.id.task_split_radio)
                    binding.selectedCountText.text = getString(
                        R.string.selected_pdf_pages,
                        source.pageCount
                    )
                    binding.printButton.isEnabled = true
                    updateTaskModeSections()
                    updateSplitLineControls()
                    if (alreadySplit) {
                        // check() 在已选中时不会触发监听，手动刷新预览。
                        refreshPreviewForTaskMode()
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (isDestroyed || isFinishing) {
                        return@post
                    }
                    selectedPdf = null
                    selectedPhotos = emptyList()
                    resetSplitPositionsForSelection(pageCount = 1)
                    releasePreviewBitmap()
                    binding.selectedCountText.text = getString(
                        R.string.selected_count,
                        0
                    )
                    binding.printButton.isEnabled = false
                    updateTaskModeSections()
                    updateSplitLineControls()
                    Toast.makeText(
                        this,
                        getString(R.string.read_pdf_failed, error.message ?: ""),
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
            PrintTaskMode.PHOTO_4X6 -> {
                if (selectedPhotos.any { it.sourceType == PhotoSourceType.PDF_PAGE }) {
                    Toast.makeText(
                        this,
                        R.string.pdf_requires_split_mode,
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                layoutPlanner.plan(
                    photos = selectedPhotos,
                    mode = layoutMode,
                    fitMode = fitMode,
                    resolutionMode = resolutionMode
                )
            }
            PrintTaskMode.COLUMN_SPLIT -> {
                val isPdf =
                    selectedPhotos.first().sourceType == PhotoSourceType.PDF_PAGE
                if (isPdf) {
                    if (selectedPhotos.any {
                            it.sourceType != PhotoSourceType.PDF_PAGE
                        }
                    ) {
                        Toast.makeText(
                            this,
                            R.string.pdf_mixed_sources,
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }

                    // PDF 每页展开成 N 张 A4：源页 1 的左→右，源页 2 的左→右……
                    // 每页使用自己的切分线。
                    selectedPhotos.flatMapIndexed { index, photo ->
                        val positions = splitPositionsByPage.getOrNull(index)
                            ?: defaultSplitPositions(columnCount)
                        columnSplitLayoutPlanner.plan(
                            photo = photo,
                            columnCount = columnCount,
                            splitPositions = positions.take(columnCount - 1),
                            fitMode = splitFitMode,
                            // PDF 没有「原始像素」概念，固定 300DPI。
                            resolutionMode = PhotoResolutionMode.PRINT_300_DPI
                        )
                    }
                } else {
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
                        splitPositions = (splitPositionsByPage.firstOrNull()
                            ?: defaultSplitPositions(columnCount)).take(columnCount - 1),
                        fitMode = splitFitMode,
                        resolutionMode = resolutionMode
                    )
                }
            }
        }

        printJobSubmitter.submit(pages)
    }

    companion object {
        private const val MIME_PDF = "application/pdf"
    }
}
