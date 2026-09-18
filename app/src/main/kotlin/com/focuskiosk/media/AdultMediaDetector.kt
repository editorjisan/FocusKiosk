package com.focuskiosk.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * AdultMediaDetector
 * ──────────────────
 * High-performance on-device adult / explicit media detector & purger.
 *
 * Strategy:
 * 1. Tier 1: Instant filename & directory keyword match against global & regional adult lexicon.
 * 2. Tier 2: Multi-frame visual HSV skin-tone classification across video/image checkpoints.
 * 3. Purge: Truncates file to 0 bytes, deletes from filesystem, wipes from MediaStore,
 *    and broadcasts to MediaScanner so gallery & file explorers instantly refresh.
 */
object AdultMediaDetector {

    private const val TAG = "AdultMediaDetector"
    private const val THUMB_SIZE = 64
    private const val SKIN_THRESHOLD = 0.22f // 22% skin-tone coverage flags as explicit/revealing (bikini, swimwear, memes)
    private const val TRASH_SKIN_THRESHOLD = 0.16f // 16% threshold for items found in trash/recycle bins

    private val ADULT_KEYWORDS = setOf(
        // English standard & explicit
        "porn", "xxx", "sex", "nude", "nudity", "erotic", "adult", "nsfw", "hentai",
        "boob", "boobs", "vagina", "dick", "pussy", "strip", "stripper", "onlyfans",
        "sensual", "camgirl", "playboy", "hardcore", "softcore", "slut", "whore",
        "penetration", "masturbat", "ejaculat", "blowjob", "handjob", "creampie",
        "milf", "bdsm", "fetish", "anal", "dildo", "tits", "titties", "ass", "cunt",
        "cock", "orgasm", "gangbang", "threesome", "incest", "taboo", "panties", "lingerie",
        // Revealing / Swimwear / Bikini / Revealing Memes
        "bikini", "swimsuit", "swimwear", "bra", "panty", "underwear", "thong",
        "cleavage", "navel", "belly", "waist", "thigh", "thighs", "mini_skirt", "miniskirt",
        "crop_top", "croptop", "exposed", "hot_model", "sexy", "romance", "kiss", "kissing",
        "bed_scene", "intimate", "deep_neck", "saree_hot", "blouse_hot", "hot_meme", "meme_hot",
        // Desi / Bengali / Hindi transliterations
        "bhabhi", "boudi", "choti", "magi", "khanki", "choda", "chodi", "chudi", "chudai",
        "gopon", "sexvideo", "desi", "mal", "viral", "scandal", "leak", "leaked", "mms",
        "hot_video", "hotvideo", "callgirl", "escort", "savita", "sunny", "mia", "khalifa",
        "actress", "aunty", "room_video", "hotel_video", "kamasutra", "boudi_hot", "bhabhi_hot",
        "desi_hot", "tiktok_hot", "reels_hot", "actress_hot", "viral_hot",
        // Major adult sites & studios
        "xhamster", "xvideos", "xnxx", "brazzers", "pornhub", "redtube", "youporn",
        "spankbang", "eporner", "beeg", "tubegalore", "chaturbate", "stripchat", "bangbros",
        "naughtyamerica", "evilangel", "realitykings", "twistys"
    )

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "3gp", "mov", "webm", "flv", "wmv", "ts")

    fun isMediaFile(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in IMAGE_EXTENSIONS || ext in VIDEO_EXTENSIONS
    }

    /**
     * Checks whether a file is sexually explicit or adult content.
     */
    fun isExplicit(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0) return false

        val nameLower = file.name.lowercase(Locale.ROOT)
        val pathLower = file.absolutePath.lowercase(Locale.ROOT)
        val isTrash = pathLower.contains("/.trash") || pathLower.contains("/.trashed") ||
                      pathLower.contains("/trash") || pathLower.contains("/.recycle") ||
                      pathLower.contains("/recycle") || nameLower.startsWith(".trash") ||
                      nameLower.startsWith(".trashed")

        // ── Tier 1: Fast Name / Path Keyword Check ────────────────────────────
        for (keyword in ADULT_KEYWORDS) {
            if (nameLower.contains(keyword) || pathLower.contains("/$keyword") ||
                pathLower.contains("_$keyword") || pathLower.contains("-$keyword")) {
                Log.w(TAG, "Tier 1 Triggered: File '${file.name}' matches adult keyword '$keyword'")
                return true
            }
        }

        // ── Tier 2: Visual HSV Skin-Tone Classification ───────────────────────
        val ext = file.extension.lowercase(Locale.ROOT)
        return try {
            when {
                ext in IMAGE_EXTENSIONS -> analyzeImage(file, isTrash)
                ext in VIDEO_EXTENSIONS -> analyzeVideo(file, isTrash)
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing visual content for ${file.name}: ${e.message}")
            false
        }
    }

    private fun analyzeImage(file: File, isTrash: Boolean): Boolean {
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

        val ratio = calculateSkinRatio(thumb)
        thumb.recycle()

        val threshold = if (isTrash) TRASH_SKIN_THRESHOLD else SKIN_THRESHOLD
        val isSkinHeavy = ratio >= threshold
        if (isSkinHeavy) {
            Log.w(TAG, "Tier 2 Image Trigger: Skin ratio $ratio >= $threshold for '${file.name}' (isTrash=$isTrash)")
        }
        return isSkinHeavy
    }

    private fun analyzeVideo(file: File, isTrash: Boolean): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 10_000L
            val durationUs = durationMs * 1000L

            // Sample 5 distinct checkpoints across the video: 10%, 25%, 50%, 75%, 90%
            val checkpoints = if (durationUs > 3_000_000L) {
                listOf(
                    (durationUs * 0.10).toLong(),
                    (durationUs * 0.25).toLong(),
                    (durationUs * 0.50).toLong(),
                    (durationUs * 0.75).toLong(),
                    (durationUs * 0.90).toLong()
                )
            } else {
                listOf(500_000L, 1_500_000L)
            }

            val singleThreshold = if (isTrash) 0.22f else 0.28f
            val multiThreshold = if (isTrash) 0.14f else 0.18f

            var skinTriggerCount = 0
            for (timeUs in checkpoints) {
                val rawFrame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val thumb = Bitmap.createScaledBitmap(rawFrame, THUMB_SIZE, THUMB_SIZE, true)
                if (thumb != rawFrame) rawFrame.recycle()

                val ratio = calculateSkinRatio(thumb)
                thumb.recycle()

                if (ratio >= multiThreshold) {
                    skinTriggerCount++
                    if (ratio >= singleThreshold || skinTriggerCount >= 2) {
                        Log.w(TAG, "Tier 2 Video Trigger: Skin ratio $ratio at ${timeUs / 1000}ms for '${file.name}' (isTrash=$isTrash)")
                        return true
                    }
                }
            }
            val triggered = skinTriggerCount > 0 && isTrash
            if (triggered) {
                Log.w(TAG, "Tier 2 Trash Video Triggered: Skin checkpoints positive for '${file.name}'")
            }
            triggered
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing video ${file.name}: ${e.message}")
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun calculateSkinRatio(bitmap: Bitmap): Float {
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
            if (h in 0.0f..50.0f && s in 0.15f..0.75f && v in 0.28f..1.0f) {
                skinCount++
            }
        }
        return skinCount.toFloat() / (THUMB_SIZE * THUMB_SIZE)
    }

    /**
     * Permanently deletes a file and removes it from MediaStore and gallery indexes.
     * Uses zero-byte truncation failsafe so data is destroyed even if a read lock exists.
     */
    fun purgeFile(context: Context, file: File, contentUri: Uri? = null): Boolean {
        Log.i(TAG, "PURGING explicit media file: ${file.absolutePath}")
        var purged = false

        // 1. Zero-byte truncation: Overwrite data immediately
        runCatching {
            if (file.exists() && file.isFile) {
                FileOutputStream(file).use { out ->
                    out.write(ByteArray(0))
                    out.flush()
                }
                purged = true
            }
        }.onFailure { Log.w(TAG, "Zero-byte truncation failed: ${it.message}") }

        // 2. Direct filesystem file deletion
        runCatching {
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) purged = true
            }
        }.onFailure { Log.w(TAG, "Direct file deletion failed: ${it.message}") }

        // 3. MediaStore deletion via ContentResolver (including Trashed media)
        runCatching {
            val cr = context.contentResolver
            if (contentUri != null) {
                cr.delete(contentUri, null, null)
            }
            val where = "${MediaStore.MediaColumns.DATA}=?"
            val args = arrayOf(file.absolutePath)
            cr.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, where, args)
            cr.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, where, args)
            cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, where, args)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cr.delete(MediaStore.Files.getContentUri("external"), where, args)
            }
            purged = true
        }.onFailure { Log.w(TAG, "MediaStore resolver deletion failed: ${it.message}") }

        // 4. Delete associated thumbnails from .thumbnails folders
        runCatching {
            val baseName = file.nameWithoutExtension
            val parent = file.parentFile
            val thumbDirs = listOfNotNull(
                parent?.let { File(it, ".thumbnails") },
                File(android.os.Environment.getExternalStorageDirectory(), "DCIM/.thumbnails"),
                File(android.os.Environment.getExternalStorageDirectory(), "Pictures/.thumbnails")
            )
            for (tDir in thumbDirs) {
                if (tDir.exists() && tDir.isDirectory) {
                    tDir.listFiles { _, name -> name.contains(baseName) }?.forEach { thumbFile ->
                        thumbFile.delete()
                    }
                }
            }
        }

        // 5. Trigger media scan so gallery and file manager indices update immediately
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }

        return purged
    }
}
