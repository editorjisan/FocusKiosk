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
    private const val SKIN_THRESHOLD = 0.40f
    private const val TRASH_SKIN_THRESHOLD = 0.25f

    // Common movie release tokens that indicate legitimate feature films or television series
    private val MOVIE_RELEASE_TAGS = setOf(
        "1080p", "720p", "2160p", "4k", "bluray", "blu-ray", "web-dl", "webrip", "dvdrip", "hdrip",
        "x264", "x265", "hevc", "h264", "h265", "aac", "dts", "yts", "yify", "rarbg", "psa",
        "galaxyrg", "eztv", "subtitles", "season", "episode", "s01", "s02", "s03", "s04", "s05",
        "s06", "s07", "s08", "e01", "e02", "e03", "e04", "e05", "e06", "e07", "e08"
    )

    // Unambiguous, standalone adult tokens (matched strictly as whole words/tokens, never substrings of normal words)
    private val ADULT_EXACT_TOKENS = setOf(
        // Explicit terms
        "porn", "porno", "pornography", "xxx", "sex", "nude", "nudes", "nudity", "nsfw", "hentai",
        "boob", "boobs", "vagina", "dick", "pussy", "stripper", "onlyfans",
        "camgirl", "playboy", "hardcore", "softcore", "slut", "whore",
        "penetration", "masturbat", "masturbation", "ejaculat", "ejaculation", "blowjob", "handjob", "creampie",
        "milf", "bdsm", "fetish", "dildo", "cunt", "cock", "orgasm", "gangbang", "threesome", "incest",
        // Lingerie & Modesty / Sexual items
        "bra", "bras", "brassiere", "panty", "panties", "underwear", "undies", "undergarment",
        "lingerie", "thong", "thongs", "gstring", "corset", "nighty", "nightie",
        "babydoll", "bodysuit", "monokini", "bikini", "swimsuit", "swimwear",
        // Revealing Clothes & Body Exposure (Bangladeshi / Islamic context)
        "cleavage", "navel", "midriff", "barelegs", "miniskirt", "croptop", "hotpants",
        "topless", "bottomless", "deepneck", "lowcut", "backless",
        // Sexual posts, postures & social media
        "twerk", "twerking", "sideboob", "underboob", "naked", "erotica",
        // Desi / Bengali / Hindi transliterations
        "bhabhi", "boudi", "choti", "magi", "khanki", "choda", "chodi", "chudi", "chudai",
        "chodachodi", "gopon", "dudh", "gud", "nengta", "nangla", "langta", "callgirl", "escort",
        "savita", "khalifa", "kamasutra",
        // Major adult sites & studios (can also match in domain/URL/filename)
        "xhamster", "xvideos", "xnxx", "brazzers", "pornhub", "redtube", "youporn",
        "spankbang", "eporner", "beeg", "tubegalore", "chaturbate", "stripchat", "bangbros",
        "naughtyamerica", "evilangel", "realitykings", "twistys"
    )

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "jfif")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "3gp", "mov", "webm", "flv", "wmv", "ts")

    fun isMediaFile(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in IMAGE_EXTENSIONS || ext in VIDEO_EXTENSIONS
    }

    /**
     * Checks whether a file is sexually explicit or adult content.
     * Prevents false-positive deletion of downloaded movies, TV shows, and video projects.
     */
    fun isExplicit(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0) return false

        val nameLower = file.name.lowercase(Locale.ROOT)
        val pathLower = file.absolutePath.lowercase(Locale.ROOT)
        val isTrash = pathLower.contains("/.trash") || pathLower.contains("/.trashed") ||
                      pathLower.contains("/trash") || pathLower.contains("/.recycle") ||
                      pathLower.contains("/recycle") || nameLower.startsWith(".trash") ||
                      nameLower.startsWith(".trashed")

        // ── Tier 1: Token-based Keyword Matching (Eliminates collisions like Jurassic, Animal, Classic) ─
        val tokens = nameLower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        val pathTokens = pathLower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

        for (keyword in ADULT_EXACT_TOKENS) {
            if (tokens.contains(keyword) || pathTokens.contains(keyword)) {
                Log.w(TAG, "Tier 1 Triggered: File '${file.name}' matches explicit token '$keyword'")
                return true
            }
        }

        // Direct check for adult domain substrings in path or name (e.g. "pornhub", "xvideos", "xnxx", "xhamster")
        val adultDomains = listOf("pornhub", "xvideos", "xnxx", "xhamster", "brazzers", "redtube", "youporn", "hentai")
        for (domain in adultDomains) {
            if (nameLower.contains(domain) || pathLower.contains(domain)) {
                Log.w(TAG, "Tier 1 Triggered: File '${file.name}' matches explicit studio '$domain'")
                return true
            }
        }

        // ── Movie Protection Shield: Never delete feature-length films or movies by visual heuristics ──
        val ext = file.extension.lowercase(Locale.ROOT)
        val isVideo = ext in VIDEO_EXTENSIONS

        if (isVideo) {
            val fileSizeBytes = file.length()
            val isMovieDirectory = pathLower.contains("/movies/") || pathLower.contains("/movie/") ||
                                   pathLower.contains("/film/") || pathLower.contains("/films/")
            val hasMovieReleaseTag = tokens.any { it in MOVIE_RELEASE_TAGS }

            // Videos > 70 MB or containing release tags / in Movies folder are full-length movies / shows.
            // Do NOT apply crude skin tone heuristics to full-length films!
            if (fileSizeBytes > 70 * 1024 * 1024L || isMovieDirectory || hasMovieReleaseTag) {
                Log.i(TAG, "Movie Protection: Skipping visual skin check for full-length video '${file.name}' (${fileSizeBytes / (1024 * 1024)}MB)")
                return false
            }
        }

        // ── Tier 2: Multi-Color Space (YCbCr + HSV + RGB) Regional Classifier (Short clips / Images only) ─
        return try {
            when {
                ext in IMAGE_EXTENSIONS -> analyzeImage(file, isTrash)
                isVideo -> analyzeVideo(file, isTrash)
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing visual content for ${file.name}: ${e.message}")
            false
        }
    }

    private fun analyzeImage(file: File, isTrash: Boolean): Boolean {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            java.io.FileInputStream(file).use { fis ->
                BitmapFactory.decodeStream(fis, null, boundsOptions)
            }
        }.getOrNull() ?: BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
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
        val rawBitmap = runCatching {
            java.io.FileInputStream(file).use { fis ->
                BitmapFactory.decodeStream(fis, null, decodeOptions)
            }
        }.getOrNull() ?: BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return false
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

            // Feature-Length Film Guard: If video is > 15 minutes, never delete it via visual skin analysis!
            if (durationMs > 15 * 60 * 1000L) {
                Log.i(TAG, "Movie Protection: Video '${file.name}' is ${durationMs / 60000} mins long. Skipping visual heuristics.")
                return false
            }

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
                    // Require at least 3 distinct positive checkpoints with massive skin exposure
                    if (skinTriggerCount >= 3) {
                        Log.w(TAG, "Tier 2 Video Trigger: Multi-frame explicit content verified for '${file.name}'")
                        return true
                    }
                }
            }
            false
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
        val cleavageRatio: Float,
        val midriffRatio: Float,
        val lowerCenterRatio: Float,
        val lowerBodyRatio: Float,
        val torsoRatio: Float,
        val maxQuadrantRatio: Float
    )

    private fun isSkinPixel(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        // Fast integer YCbCr model:
        // Y = (299*r + 587*g + 114*b) / 1000
        val y = (299 * r + 587 * g + 114 * b) / 1000
        val cr = ((r - y) * 713) / 1000 + 128
        val cb = ((b - y) * 564) / 1000 + 128
        val isYcbcr = (cr in 115..188) && (cb in 65..148)

        // 1. Standard RGB human skin (Peer et al.)
        val isRgbStandard = (r > 60 && g > 25 && b > 15 &&
                            r >= g && (r - b) >= 4 &&
                            (maxOf(r, g, b) - minOf(r, g, b)) > 8)

        // 2. Fair / Asian / Porcelain / Filtered / Indoor Cool Skin
        val isRgbFair = (r > 140 && g > 110 && b > 80 &&
                         (r + 5) >= g && (r + g) > (2 * b) && Math.abs(r - g) <= 65)

        // 3. South Asian / Bengali / Olive / Wheatish / Brown / Dusky Skin
        val isRgbSouthAsian = (r in 50..230 && g in 30..190 && b in 15..155 &&
                               r >= g && (r - b) >= 6 && (r + g) > (2 * b))

        // 4. Fast HSV hue & saturation without object allocations
        val max = maxOf(r, maxOf(g, b))
        val min = minOf(r, minOf(g, b))
        val delta = max - min
        val v = max / 255.0f
        val s = if (max == 0) 0f else delta.toFloat() / max
        val h = when {
            delta == 0 -> 0f
            max == r -> (((g - b).toFloat() / delta) % 6f) * 60f
            max == g -> (((b - r).toFloat() / delta) + 2f) * 60f
            else -> (((r - g).toFloat() / delta) + 4f) * 60f
        }.let { if (it < 0f) it + 360f else it }

        val isHsv = (h in 0.0f..65.0f || h in 315.0f..360.0f) && (s in 0.02f..0.95f) && (v in 0.12f..1.0f)

        return (isYcbcr && isHsv) ||
               (isRgbStandard && isYcbcr) ||
               (isRgbFair && isHsv) ||
               (isRgbFair && isYcbcr) ||
               (isRgbSouthAsian && isYcbcr) ||
               (isRgbSouthAsian && isHsv) ||
               (isRgbStandard && isHsv && cr > 116)
    }

    private fun analyzeSkinZones(bitmap: Bitmap): SkinZoneResult {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val skinMask = BooleanArray(w * h)
        var totalSkin = 0

        for (i in pixels.indices) {
            if (isSkinPixel(pixels[i])) {
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

        // 2. Upper center (cleavage, breasts, bra cups, exposed chest & shoulders)
        val upperCenterRatio = ratioInBox(w / 6, (h * 10) / 100, (5 * w) / 6, (h * 50) / 100)

        // 3. Cleavage specific box (deep necklines, plunging bras/tops)
        val cleavageRatio = ratioInBox(w / 5, (h * 12) / 100, (4 * w) / 5, (h * 42) / 100)

        // 4. Midriff & Belly (navel, waist, crop tops, saree blouses, bra/panty gap)
        val midriffRatio = ratioInBox(w / 5, (h * 25) / 100, (4 * w) / 5, (h * 65) / 100)

        // 5. Lower center (panties, bikini bottom, groin, thighs, buttocks)
        val lowerCenterRatio = ratioInBox(w / 6, (h * 40) / 100, (5 * w) / 6, (h * 85) / 100)

        // 6. Lower body half (bare legs, thighs, underwear, swim briefs, shorts)
        val lowerBodyRatio = ratioInBox(0, h / 2, w, h)

        // 7. Torso vertical strip (cutout swimsuits, monokinis, lingerie, revealing dresses)
        val torsoRatio = ratioInBox(w / 4, (h * 15) / 100, (3 * w) / 4, (h * 85) / 100)

        // 8. 2x2 Quadrants + center quadrant
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
            cleavageRatio = cleavageRatio,
            midriffRatio = midriffRatio,
            lowerCenterRatio = lowerCenterRatio,
            lowerBodyRatio = lowerBodyRatio,
            torsoRatio = torsoRatio,
            maxQuadrantRatio = maxQuadrant
        )
    }

    private fun isSkinExcessive(z: SkinZoneResult, isTrash: Boolean): Boolean {
        return if (isTrash) {
            z.globalRatio >= TRASH_SKIN_THRESHOLD ||
            z.centerRatio >= 0.35f ||
            z.upperCenterRatio >= 0.30f ||
            z.cleavageRatio >= 0.30f ||
            z.midriffRatio >= 0.30f ||
            z.lowerCenterRatio >= 0.30f ||
            z.lowerBodyRatio >= 0.35f ||
            z.torsoRatio >= 0.35f ||
            z.maxQuadrantRatio >= 0.40f
        } else {
            // Require substantial explicit exposure (not just faces / hands / arms)
            (z.globalRatio >= SKIN_THRESHOLD && (z.centerRatio >= 0.45f || z.torsoRatio >= 0.40f || z.lowerCenterRatio >= 0.40f)) ||
            (z.centerRatio >= 0.55f && (z.torsoRatio >= 0.50f || z.lowerBodyRatio >= 0.50f)) ||
            (z.cleavageRatio >= 0.50f && z.upperCenterRatio >= 0.45f && z.globalRatio >= 0.30f) ||
            (z.lowerCenterRatio >= 0.50f && z.lowerBodyRatio >= 0.50f && z.globalRatio >= 0.30f)
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
