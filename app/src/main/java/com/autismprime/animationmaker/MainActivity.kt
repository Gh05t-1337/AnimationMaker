package com.autismprime.animationmaker

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.autismprime.animationmaker.databinding.ActivityMainBinding
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FrameAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private var fps = 12
    private val nextId = AtomicLong(0)
    private var isExporting = false

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) addFrames(uris)
        }

    private var pendingExportPermissionRequest = false
    private val requestWritePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startExport()
            } else {
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupList()
        setupFpsControl()

        binding.buttonAddImages.setOnClickListener {
            pickImages.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        binding.buttonExport.setOnClickListener { onExportClicked() }

        updateEmptyState()
    }

    private fun setupList() {
        adapter = FrameAdapter(
            onRemove = { position ->
                adapter.removeAt(position)
                updateEmptyState()
            },
            onStartDrag = { holder -> itemTouchHelper.startDrag(holder) }
        )
        binding.recyclerFrames.layoutManager = LinearLayoutManager(this)
        binding.recyclerFrames.adapter = adapter

        itemTouchHelper = ItemTouchHelper(DragCallback(adapter))
        itemTouchHelper.attachToRecyclerView(binding.recyclerFrames)
    }

    private fun setupFpsControl() {
        updateFpsLabel()
        binding.seekFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                fps = progress + 1 // SeekBar starts at 0; allow 1..30 fps
                updateFpsLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateFpsLabel() {
        binding.textFps.text = getString(R.string.fps_label, fps)
    }

    private fun addFrames(uris: List<Uri>) {
        val newItems = uris.map { FrameItem(nextId.getAndIncrement(), it) }
        adapter.submit(adapter.currentItems() + newItems)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val hasItems = adapter.itemCount > 0
        binding.recyclerFrames.visibility = if (hasItems) View.VISIBLE else View.GONE
        binding.textEmptyHint.visibility = if (hasItems) View.GONE else View.VISIBLE
    }

    private fun onExportClicked() {
        if (isExporting) return
        if (adapter.itemCount < 2) {
            Toast.makeText(this, R.string.need_at_least_two, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startExport()
    }

    private fun startExport() {
        val frames = adapter.currentItems()
        val fpsAtExport = fps

        isExporting = true
        setExportUiEnabled(false)
        binding.progressExport.visibility = View.VISIBLE
        binding.progressExport.max = frames.size
        binding.progressExport.progress = 0
        binding.textStatus.visibility = View.VISIBLE
        binding.textStatus.text = getString(R.string.exporting)

        Thread {
            try {
                exportVideo(frames, fpsAtExport) { done ->
                    runOnUiThread { binding.progressExport.progress = done }
                }
                runOnUiThread {
                    binding.textStatus.text = getString(R.string.export_success)
                    Toast.makeText(this, R.string.export_success, Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    binding.textStatus.text = getString(R.string.export_failed, t.message ?: "")
                    Toast.makeText(
                        this,
                        getString(R.string.export_failed, t.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                runOnUiThread {
                    isExporting = false
                    setExportUiEnabled(true)
                    binding.progressExport.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun setExportUiEnabled(enabled: Boolean) {
        binding.buttonExport.isEnabled = enabled
        binding.buttonAddImages.isEnabled = enabled
    }

    /** Runs on a background thread. Decodes each image, encodes to mp4, saves via MediaStore. */
    private fun exportVideo(frames: List<FrameItem>, fps: Int, onProgress: (Int) -> Unit) {
        // Target resolution: capped so encoding stays fast and memory-light,
        // based on the first frame's aspect ratio.
        val (firstW, firstH) = BitmapUtils.readDimensions(this, frames[0].uri)
        val maxDim = 1280
        val scale = minOf(1f, maxDim.toFloat() / maxOf(firstW, firstH))
        // H.264 requires even dimensions.
        var targetW = ((firstW * scale).toInt() / 2) * 2
        var targetH = ((firstH * scale).toInt() / 2) * 2
        if (targetW <= 0) targetW = 2
        if (targetH <= 0) targetH = 2

        val tempFile = File(cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val encoder = VideoEncoder(tempFile, targetW, targetH, fps)
        encoder.start()
        try {
            frames.forEachIndexed { index, frame ->
                val bmp = BitmapUtils.decodeSampled(this, frame.uri, maxDim)
                    ?: throw IllegalStateException("Could not read an image")
                encoder.addFrame(bmp)
                bmp.recycle()
                onProgress(index + 1)
            }
        } finally {
            encoder.finish()
        }

        saveToMovies(tempFile)
        tempFile.delete()
    }

    private fun saveToMovies(sourceFile: File) {
        val name = "animationmaker_${System.currentTimeMillis()}.mp4"
        val resolver = contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AnimationMaker")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create output file")

        var out: OutputStream? = null
        try {
            out = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open output stream")
            FileInputStream(sourceFile).use { input ->
                input.copyTo(out)
            }
        } finally {
            out?.close()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }
}
