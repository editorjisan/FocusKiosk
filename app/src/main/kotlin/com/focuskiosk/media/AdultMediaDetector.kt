package com.focuskiosk.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * AdultMediaDetector
 * ──────────────────
 * High-performance on-device adult / explicit media detector & purger.
 * 
 * Strategy:
 * 1. Tier 1: Instant filename & directory keyword match.
 * 2. Tier 2: Visual thumbnail sampling (64x64) with HSV human skin-tone classification.
 * 3. Deletion: Permanently purges flagged images/videos from storage and MediaStore.
 */
object AdultMediaDetector {

    private const val TAG = "AdultMediaDetector"
    private const val THUMB_SIZE = 64
    private const val SKIN_THRESHOLD = 0.40f // 40% skin-tone coverage flags as explicit

    private val ADULT_KEYWORDS = setOf(
        "porn", "xxx", "sex", "nude", "nudity", "erotic", "xhamster", "xvideos", "xnxx",
        "brazzers", "adult", "nsfw", "hentai", "boobs", "vagina", "dick", "pussy",
        "strip", "stripper", "onlyfans", "bhabhi", "choti", "hot_video", "leak", "leaked",
        "sensual", "camgirl", "playboy", "hardcore", "softcore", "slut", "whore",
        "penetration", "masturbat", "ejaculat", "blowjob", "handjob", "creampie",
        "milf", "bdsm", "fetish", "anal", "dildo", "boob", "tits", "titties", "ass"
    )

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "3gp", "mov", "webm", "flv")

    fun isMediaFile(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in IMAGE_EXTENSIONS || ext in VIDEO_EXTENSIONS
    }

    /**
     * Checks whether a file is sexually explicit or adult content.
     */
    fun isExplicit(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0) return false

        // ── Tier 1: Fast Name / Path Keyword Check ────────────────────────────
        val nameLower = file.name.lowercase(Locale.ROOT)
        val pathLower = file.absolutePath.lowercase(Locale.ROOT)
        
        for (keyword in ADULT_KEYWORDS) {
            if (nameLower.contains(keyword) || pathLower.contains("/$keyword")) {
                Log.w(TAG, "Tier 1 Triggered: File '${file.name}' matches adult keyword '$keyword'")
                return true
            }
        }

        // ── Tier 2: Visual HSV Skin-Tone Classification ───────────────────────
        val ext = file.extension.lowercase(Locale.ROOT)
        return try {
            when {
                ext in IMAGE_EXTENSIONS -> analyzeImage(file)
                ext in VIDEO_EXTENSIONS -> analyzeVideo(file)
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing visual content for ${file.name}: ${e.message}")
            false
        }
    }

    private fun analyzeImage(file: File): Boolean {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) return false

        var sampleSize = 1
        while ((width / sampleSize) > THUMB_SIZE * 2 || (height / sampleSize) > THUMB_SIZE * 2) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val rawBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return false
        val thumb = Bitmap.createScaledBitmap(rawBitmap, THUMB_SIZE, THUMB_SIZE, true)
        if (thumb != rawBitmap) rawBitmap.recycle()

        val isSkinHeavy = evaluateSkinRatio(thumb)
        thumb.recycle()
        return isSkinHeavy
    }

    private fun analyzeVideo(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            // Sample frame at 1.5 seconds (1,500,000 microseconds)
            val rawFrame = retriever.getFrameAtTime(1_500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)
                ?: return false

            val thumb = Bitmap.createScaledBitmap(rawFrame, THUMB_SIZE, THUMB_SIZE, true)
            if (thumb != rawFrame) rawFrame.recycle()

            val isSkinHeavy = evaluateSkinRatio(thumb)
            thumb.recycle()
            isSkinHeavy
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun evaluateSkinRatio(bitmap: Bitmap): Boolean {
        val pixels = IntArray(THUMB_SIZE * THUMB_SIZE)
        bitmap.getPixels(pixels, 0, THUMB_SIZE, 0, 0, THUMB_SIZE, THUMB_SIZE)

        var skinCount = 0
        val hsv = FloatArray(3)

        for (pixel in pixels) {
            Color.colorToHSV(pixel, hsv)
            val h = hsv[0] // 0 to 360
            val s = hsv[1] // 0.0 to 1.0
            val v = hsv[2] // 0.0 to 1.0

            // Human skin tone in HSV color space across diverse ethnicities
            if (h in 0.0f..50.0f && s in 0.15f..0.72f && v in 0.30f..1.0f) {
                skinCount++
            }
        }

        val ratio = skinCount.toFloat() / (THUMB_SIZE * THUMB_SIZE)
        val isExplicit = ratio >= SKIN_THRESHOLD
        if (isExplicit) {
            Log.w(TAG, "Tier 2 Triggered: Skin tone ratio $ratio exceeds threshold $SKIN_THRESHOLD")
        }
        return isExplicit
    }

    /**
     * Permanently deletes a file and removes it from MediaStore and gallery indexes.
     */
    fun purgeFile(context: Context, file: File, contentUri: Uri? = null): Boolean {
        Log.i(TAG, "PURGING explicit media file: ${file.absolutePath}")
        var deleted = false

        // 1. Direct file deletion
        runCatching {
            if (file.exists()) {
                deleted = file.delete()
            }
        }.onFailure { Log.w(TAG, "Direct file deletion failed: ${it.message}") }

        // 2. MediaStore deletion via ContentResolver
        runCatching {
            val cr = context.contentResolver
            if (contentUri != null) {
                cr.delete(contentUri, null, null)
            } else {
                val where = "${MediaStore.MediaColumns.DATA}=?"
                val args = arrayOf(file.absolutePath)
                cr.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, where, args)
                cr.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, where, args)
                cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, where, args)
            }
            deleted = true
        }.onFailure { Log.w(TAG, "MediaStore resolver deletion failed: ${it.message}") }

        // 3. Trigger media scan so gallery updates immediately
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }

        return deleted
    }
}
