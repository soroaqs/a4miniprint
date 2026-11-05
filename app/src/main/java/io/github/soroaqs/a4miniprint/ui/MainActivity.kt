package io.github.soroaqs.a4miniprint.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.soroaqs.a4miniprint.R
import io.github.soroaqs.a4miniprint.config.PrintConfig
import io.github.soroaqs.a4miniprint.data.PhotoMetadata
import io.github.soroaqs.a4miniprint.data.PhotoFitMode
import io.github.soroaqs.a4miniprint.data.PhotoResolutionMode
import io.github.soroaqs.a4miniprint.data.PrintLayoutMode
import io.github.soroaqs.a4miniprint.databinding.ActivityMainBinding
import io.github.soroaqs.a4miniprint.databinding.DialogShareConfirmBinding
import io.github.soroaqs.a4miniprint.image.ImageMetadataLoader
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
    private lateinit var printJobSubmitter: PrintJobSubmitter
    private lateinit var shareIntentParser: ShareIntentParser

    private var selectedPhotos: List<PhotoMetadata> = emptyList()
    private var layoutMode: PrintLayoutMode = config.defaultLayoutMode
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
        layoutPlanner = PrintLayoutPlanner(config)
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
            startPrint(layoutMode, fitMode, resolutionMode)
        }
    }

    private fun handleShareIntent(intent: Intent) {
        val uris = shareIntentParser.parse(intent)
        if (uris.isEmpty()) {
            return
        }

        loadMetadata(uris) {
            showShareConfirmDialog()
        }
    }

    private fun loadMetadata(
        uris: List<Uri>,
        onFinished: (() -> Unit)? = null
    ) {
        binding.selectedCountText.text = getString(R.string.loading_photos)
        binding.printButton.isEnabled = false

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
                    binding.selectedCountText.text = getString(
                        R.string.selected_count,
                        selectedPhotos.size
                    )
                    binding.printButton.isEnabled = selectedPhotos.isNotEmpty()

                    onFinished?.invoke()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (isDestroyed || isFinishing) {
                        return@post
                    }
                    selectedPhotos = emptyList()
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

    private fun showShareConfirmDialog() {
        val dialogBinding = DialogShareConfirmBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.shareCountText.text = getString(
            R.string.selected_count,
            selectedPhotos.size
        )
        dialogBinding.shareConfirmButton.isEnabled = selectedPhotos.isNotEmpty()

        if (layoutMode == PrintLayoutMode.SINGLE_PER_PAGE) {
            dialogBinding.shareModeGroup.check(
                R.id.share_mode_single_radio
            )
        } else {
            dialogBinding.shareModeGroup.check(
                R.id.share_mode_grid_radio
            )
        }

        dialogBinding.shareModeGroup.setOnCheckedChangeListener { _, checkedId ->
            layoutMode = when (checkedId) {
                R.id.share_mode_single_radio -> PrintLayoutMode.SINGLE_PER_PAGE
                else -> PrintLayoutMode.A4_GRID
            }
        }

        if (fitMode == PhotoFitMode.COVER) {
            dialogBinding.shareFitModeGroup.check(
                R.id.share_fit_cover_radio
            )
        } else {
            dialogBinding.shareFitModeGroup.check(
                R.id.share_fit_contain_radio
            )
        }

        dialogBinding.shareFitModeGroup.setOnCheckedChangeListener { _, checkedId ->
            fitMode = when (checkedId) {
                R.id.share_fit_cover_radio -> PhotoFitMode.COVER
                else -> PhotoFitMode.CONTAIN
            }
        }

        if (resolutionMode == PhotoResolutionMode.ORIGINAL) {
            dialogBinding.shareResolutionModeGroup.check(
                R.id.share_resolution_original_radio
            )
        } else {
            dialogBinding.shareResolutionModeGroup.check(
                R.id.share_resolution_print_radio
            )
        }

        dialogBinding.shareResolutionModeGroup.setOnCheckedChangeListener { _, checkedId ->
            resolutionMode = when (checkedId) {
                R.id.share_resolution_original_radio -> PhotoResolutionMode.ORIGINAL
                else -> PhotoResolutionMode.PRINT_300_DPI
            }
        }

        dialogBinding.shareConfirmButton.setOnClickListener {
            dialog.dismiss()
            startPrint(layoutMode, fitMode, resolutionMode)
        }

        dialogBinding.shareCancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun startPrint(
        mode: PrintLayoutMode,
        fitMode: PhotoFitMode,
        resolutionMode: PhotoResolutionMode
    ) {
        if (selectedPhotos.isEmpty()) {
            Toast.makeText(this, R.string.select_photos_first, Toast.LENGTH_SHORT).show()
            return
        }

        val pages = layoutPlanner.plan(
            photos = selectedPhotos,
            mode = mode,
            fitMode = fitMode,
            resolutionMode = resolutionMode
        )
        printJobSubmitter.submit(pages)
    }
}
