package com.autismprime.animationmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes a sequence of still images into an H.264 / MP4 video using only
 * platform APIs (MediaCodec + MediaMuxer). No external encoding library
 * is used, which keeps the app small and dependency-free.
 *
 * One call to [addFrame] = one frame of output video. The output frame
 * rate is [fps], so image duration on screen = 1/fps seconds.
 */
class VideoEncoder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int
) {
    private val mimeType = "video/avc"
    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private lateinit var inputSurface: Surface
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    // A Surface obtained via Canvas lock/unlock stamps each frame with the
    // wall-clock time it was posted (there's no public API to set an explicit
    // presentation time on a Canvas-backed Surface, unlike a GLES-backed one).
    // So to get correct frame durations in the output file, we pace the real
    // submission time to match the target fps instead.
    private val frameDurationMs = 1000L / fps
    private var scheduledNextFrameAt = -1L

    fun start() {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            // Reasonable bitrate for stills-based footage; scales with resolution.
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 4))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        encoder = MediaCodec.createEncoderByType(mimeType)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Draws [bitmap] as the next frame, letterboxed to fit [width]x[height]. */
    fun addFrame(bitmap: Bitmap) {
        pacedWait()

        val canvas: Canvas = inputSurface.lockCanvas(null)
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, fitMatrix(bitmap.width, bitmap.height), null)
        } finally {
            inputSurface.unlockCanvasAndPost(canvas)
        }
        drainEncoder(endOfStream = false)
    }

    private fun pacedWait() {
        val now = System.currentTimeMillis()
        if (scheduledNextFrameAt < 0) {
            scheduledNextFrameAt = now + frameDurationMs
            return
        }
        val waitMs = scheduledNextFrameAt - now
        if (waitMs > 0) {
            Thread.sleep(waitMs)
        }
        // Schedule from the target time, not "now", so a slow frame doesn't
        // push every later frame back (no cumulative drift).
        scheduledNextFrameAt += frameDurationMs
    }

    fun finish() {
        drainEncoder(endOfStream = true)
        encoder.stop()
        encoder.release()
        if (muxerStarted) {
            muxer.stop()
        }
        muxer.release()
        inputSurface.release()
    }

    private fun fitMatrix(srcW: Int, srcH: Int): Matrix {
        val scale = minOf(width.toFloat() / srcW, height.toFloat() / srcH)
        val dstW = srcW * scale
        val dstH = srcH * scale
        val dx = (width - dstW) / 2f
        val dy = (height - dstH) / 2f
        val m = Matrix()
        m.postScale(scale, scale)
        m.postTranslate(dx, dy)
        return m
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            encoder.signalEndOfInputStream()
        }
        while (true) {
            val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return else continue
                }
                outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Output format changed twice" }
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputBufferId >= 0 -> {
                    val encodedData: ByteBuffer =
                        encoder.getOutputBuffer(outputBufferId) ?: continue

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Codec config data is already captured via addTrack/outputFormat.
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }

                    encoder.releaseOutputBuffer(outputBufferId, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return
                    }
                }
            }
        }
    }
}
