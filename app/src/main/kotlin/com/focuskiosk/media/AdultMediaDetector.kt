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
    private const val THUMB_SIZE = 96
    private const val SKIN_THRESHOLD = 0.12f
    private const val TRASH_SKIN_THRESHOLD = 0.08f

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

        // ── Tier 2: Multi-Color Space (YCbCr + HSV + RGB) Regional Classifier ─
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
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return false
        val thumb = Bitmap.createScaledBitmap(rawBitmap, THUMB_SIZE, THUMB_SIZE, true)
        if (thumb != rawBitmap) rawBitmap.recycle()

        val zones = analyzeSkinZones(thumb)
        thumb.recycle()

        val isSkinHeavy = isSkinExcessive(zones, isTrash)
        if (isSkinHeavy) {
            Log.w(
                TAG,
                "Tier 2 Image Trigger: '${file.name}' (isTrash=$isTrash) -> " +
                "Global=${zones.globalRatio}, Center=${zones.centerRatio}, " +
                "UpperCenter=${zones.upperCenterRatio}, LowerCenter=${zones.lowerCenterRatio}, " +
                "MaxQuad=${zones.maxQuadrantRatio}"
            )
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

            var skinTriggerCount = 0
            for (timeUs in checkpoints) {
                val rawFrame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val thumb = Bitmap.createScaledBitmap(rawFrame, THUMB_SIZE, THUMB_SIZE, true)
                if (thumb != rawFrame) rawFrame.recycle()

                val zones = analyzeSkinZones(thumb)
                thumb.recycle()

                if (isSkinExcessive(zones, isTrash)) {
                    skinTriggerCount++
                    if (zones.globalRatio >= 0.25f || zones.centerRatio >= 0.30f || skinTriggerCount >= 2) {
                        Log.w(TAG, "Tier 2 Video Trigger: Positive frame at ${timeUs / 1000}ms for '${file.name}'")
                        return true
                    }
                }
            }
            val triggered = skinTriggerCount > 0 && isTrash
            if (triggered) {
                Log.w(TAG, "Tier 2 Trash Video Triggered: Checkpoints positive for '${file.name}'")
            }
            triggered
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing video ${file.name}: ${e.message}")
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private data class SkinZoneResult(
        val globalRatio: Float,
        val centerRatio: Float,
        val upperCenterRatio: Float,
        val lowerCenterRatio: Float,
        val torsoRatio: Float,
        val maxQuadrantRatio: Float
    )

    private fun isSkinPixel(color: Int, hsv: FloatArray): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        // 1. Standard RGB human skin heuristics (Peer et al.)
        val isRgbStandard = (r > 70 && g > 30 && b > 15 &&
                            r > g && r > b && (r - g) > 6 &&
                            (maxOf(r, g, b) - minOf(r, g, b)) > 10)

        // 2. High-key / pale / fair / Asian / beauty-filtered / studio glamour skin:
        // Filtered skin typically has high R, G, B with slight warmth (R >= G and R >= B)
        val isRgbFair = (r > 160 && g > 125 && b > 95 &&
                         r >= g && (r - b) >= 4 && Math.abs(r - g) <= 55)

        // 3. YCbCr color model (extended bounds for fair/pale/Asian and beauty-filtered skin)
        // Y = 0.299R + 0.587G + 0.114B
        // Cr = (R - Y) * 0.713 + 128
        // Cb = (B - Y) * 0.564 + 128
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cr = (r - y) * 0.713f + 128f
        val cb = (b - y) * 0.564f + 128f
        val isYcbcrSkin = cr in 118.0f..180.0f && cb in 70.0f..138.0f

        // 4. HSV model with wrap-around hue (325° - 360° and 0° - 55°)
        // Saturation threshold lowered to 0.03f to capture beauty filters and bright indoor light
        Color.colorToHSV(color, hsv)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]
        val isHsvSkin = (h in 0.0f..55.0f || h in 325.0f..360.0f) &&
                        (s in 0.03f..0.92f) &&
                        (v in 0.15f..1.0f)

        return (isYcbcrSkin && isHsvSkin) ||
               (isRgbStandard && isYcbcrSkin) ||
               (isRgbFair && isHsvSkin) ||
               (isRgbStandard && isHsvSkin && cr > 120f)
    }

    private fun analyzeSkinZones(bitmap: Bitmap): SkinZoneResult {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val hsv = FloatArray(3)
        val skinMask = BooleanArray(w * h)
        var totalSkin = 0

        for (i in pixels.indices) {
            if (isSkinPixel(pixels[i], hsv)) {
                skinMask[i] = true
                totalSkin++
            }
        }

        val globalRatio = totalSkin.toFloat() / (w * h)

        fun ratioInBox(x1: Int, y1: Int, x2: Int, y2: Int): Float {
            var count = 0
            val total = (x2 - x1) * (y2 - y1)
            if (total <= 0) return 0f
            for (y in y1 until y2) {
                val rowOffset = y * w
                for (x in x1 until x2) {
                    if (skinMask[rowOffset + x]) count++
                }
            }
            return count.toFloat() / total
        }

        // 1. Center box (middle 50% width and 50% height)
        val centerRatio = ratioInBox(w / 4, h / 4, (3 * w) / 4, (3 * h) / 4)

        // 2. Upper center (cleavage, breasts, bikini tops, exposed chest)
        val upperCenterRatio = ratioInBox(w / 6, (h * 15) / 100, (5 * w) / 6, (h * 55) / 100)

        // 3. Lower center (pelvic, bikini bottom, thighs, buttocks)
        val lowerCenterRatio = ratioInBox(w / 6, (h * 45) / 100, (5 * w) / 6, (h * 85) / 100)

        // 4. Torso vertical strip (central 50% width, 20% to 80% height)
        val torsoRatio = ratioInBox(w / 4, (h * 20) / 100, (3 * w) / 4, (h * 80) / 100)

        // 5. 2x2 Quadrants + center quadrant
        val halfW = w / 2
        val halfH = h / 2
        val q1 = ratioInBox(0, 0, halfW, halfH)
        val q2 = ratioInBox(halfW, 0, w, halfH)
        val q3 = ratioInBox(0, halfH, halfW, h)
        val q4 = ratioInBox(halfW, halfH, w, h)
        val maxQuadrant = maxOf(q1, q2, q3, q4, centerRatio)

        return SkinZoneResult(
            globalRatio = globalRatio,
            centerRatio = centerRatio,
            upperCenterRatio = upperCenterRatio,
            lowerCenterRatio = lowerCenterRatio,
            torsoRatio = torsoRatio,
            maxQuadrantRatio = maxQuadrant
        )
    }

    private fun isSkinExcessive(z: SkinZoneResult, isTrash: Boolean): Boolean {
        return if (isTrash) {
            z.globalRatio >= TRASH_SKIN_THRESHOLD ||
            z.centerRatio >= 0.10f ||
            z.upperCenterRatio >= 0.10f ||
            z.lowerCenterRatio >= 0.10f ||
            z.torsoRatio >= 0.10f ||
            z.maxQuadrantRatio >= 0.12f
        } else {
            z.globalRatio >= SKIN_THRESHOLD ||      // >= 0.12f
            z.centerRatio >= 0.14f ||
            z.upperCenterRatio >= 0.12f ||          // Sensitive to cleavage/bikini top/selfies
            z.lowerCenterRatio >= 0.12f ||          // Sensitive to buttocks/thongs/pelvic/thighs
            z.torsoRatio >= 0.14f ||                // Sensitive to swimsuits/lingerie/monokinis
            z.maxQuadrantRatio >= 0.16f             // Sensitive to close-up shots
        }
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

        // 3. MediaStore deletion via ContentResolver by exact path and ID
        runCatching {
            val cr = context.contentResolver
            if (contentUri != null) {
                cr.delete(contentUri, null, null)
                purged = true
            }
            val where = "${MediaStore.MediaColumns.DATA}=?"
            val args = arrayOf(file.absolutePath)

            val filesUri = MediaStore.Files.getContentUri("external")
            cr.query(filesUri, arrayOf(MediaStore.MediaColumns._ID), where, args, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val itemUri = android.content.ContentUris.withAppendedId(filesUri, id)
                    cr.delete(itemUri, null, null)
                    purged = true
                }
            }
            cr.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, where, args)
            cr.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, where, args)
            cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, where, args)
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
