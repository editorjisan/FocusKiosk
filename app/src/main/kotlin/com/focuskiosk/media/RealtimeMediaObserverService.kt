package com.focuskiosk.media

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * RealtimeMediaObserverService
 * ────────────────────────────
 * Continuous real-time sentinel service monitoring storage and MediaStore.
 * Detects newly downloaded or saved images & videos the moment they hit the filesystem,
 * classifies them via AdultMediaDetector, and permanently purges them on the spot.
 *
 * Features:
 * • Recursive directory observation across Download, DCIM, Pictures, Movies, Phoenix, Telegram, WhatsApp, etc.
 * • Dynamic watcher registration for newly created subfolders.
 * • Immediate full-device sweep on service startup.
 * • Zero CPU overhead when idle.
 */
class RealtimeMediaObserverService : Service() {

    companion object {
        private const val TAG = "RealtimeMediaObserver"

        fun start(context: Context) {
            try {
                val intent = Intent(context, RealtimeMediaObserverService::class.java)
                context.startService(intent)
                Log.i(TAG, "RealtimeMediaObserverService started.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start RealtimeMediaObserverService: ${e.message}")
            }
        }

        fun getRootMediaDirectories(): List<File> {
            val root = Environment.getExternalStorageDirectory()
            return listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                File(root, "Download"),
                File(root, "Downloads"),
                File(root, "Pictures"),
                File(root, "Movies"),
                File(root, "DCIM"),
                File(root, "DCIM/Camera"),
                File(root, "DCIM/Screenshots"),
                File(root, "Phoenix"),
                File(root, "Phoenix/download"),
                File(root, "Telegram"),
                File(root, "WhatsApp"),
                File(root, "WhatsApp/Media"),
                File(root, "Android/media/com.whatsapp/WhatsApp/Media"),
                File(root, "Android/media/org.telegram.messenger"),
                File(root, "Xender"),
                File(root, "Bluetooth"),
                // Trash / Recycle Bins (System, AI Gallery, Google Photos, SD)
                File(root, ".trash"),
                File(root, ".trashed"),
                File(root, "trash"),
                File(root, ".recycle"),
                File(root, ".RecycleBin"),
                File(root, "Pictures/.trash"),
                File(root, "DCIM/.trash"),
                File(root, "DCIM/.thumbnails"),
                File(root, "Pictures/.thumbnails"),
                File(root, "Android/data/com.transsion.ai_gallery/files/.trash"),
                File(root, "Android/data/com.transsion.ai_gallery/files/trash"),
                File(root, "Android/data/com.transsion.ai_gallery/files"),
                File(root, "Android/data/com.google.android.apps.photos/files/trash"),
                File(root, "Android/data/com.google.android.apps.photos/files")
            ).distinct()
        }

        /**
         * Deep scan of all media storage directories & MediaStore records.
         * Throttled with delay() to ensure zero UI lag or phone hang.
         * Returns (scannedCount, purgedCount).
         */
        suspend fun performFullSweep(
            context: Context,
            onProgress: ((scanned: Int, purged: Int) -> Unit)? = null
        ): Pair<Int, Int> = withContext(Dispatchers.IO) {
            Log.i(TAG, "Starting full-device adult media sweep...")
            var scannedCount = 0
            var purgedCount = 0

            val directories = getRootMediaDirectories()
            for (dir in directories) {
                if (dir.exists() && dir.isDirectory) {
                    try {
                        dir.walkTopDown().maxDepth(5).forEach { file ->
                            if (file.isFile && AdultMediaDetector.isMediaFile(file)) {
                                scannedCount++
                                if (scannedCount % 8 == 0) {
                                    delay(12L) // Gentle CPU throttling prevents UI frame drops and system hang
                                }
                                if (AdultMediaDetector.isExplicit(file)) {
                                    Log.w(TAG, "Sweep flagged explicit file: ${file.absolutePath}")
                                    if (AdultMediaDetector.purgeFile(context, file)) {
                                        purgedCount++
                                    }
                                }
                                onProgress?.invoke(scannedCount, purgedCount)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error walking directory ${dir.absolutePath}: ${e.message}")
                    }
                }
            }

            // Also check MediaStore records (including trashed items on API 30+)
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
            listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).forEach { uri ->
                runCatching {
                    val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val bundle = Bundle().apply {
                            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                        }
                        context.contentResolver.query(uri, projection, bundle, null)
                    } else {
                        context.contentResolver.query(uri, projection, null, null, null)
                    }

                    cursor?.use { c ->
                        val dataIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        while (c.moveToNext()) {
                            val path = c.getString(dataIdx) ?: continue
                            val file = File(path)
                            if (file.exists() && file.isFile && AdultMediaDetector.isMediaFile(file)) {
                                scannedCount++
                                if (scannedCount % 8 == 0) {
                                    delay(12L) // Gentle CPU throttling
                                }
                                if (AdultMediaDetector.isExplicit(file)) {
                                    Log.w(TAG, "MediaStore sweep flagged: $path")
                                    if (AdultMediaDetector.purgeFile(context, file)) {
                                        purgedCount++
                                    }
                                }
                                onProgress?.invoke(scannedCount, purgedCount)
                            }
                        }
                    }
                }
            }

            Log.i(TAG, "Full sweep completed: Scanned $scannedCount files, Purged $purgedCount explicit files.")
            Pair(scannedCount, purgedCount)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeObservers = mutableListOf<FileObserver>()
    private val watchedPaths = mutableSetOf<String>()
    private var mediaStoreObserver: ContentObserver? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "Screen off: launching idle background media sweep...")
                serviceScope.launch {
                    delay(3000L) // Wait for device idle settle
                    performFullSweep(applicationContext)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Initializing real-time recursive media sentinel...")
        setupRecursiveFileObservers()
        setupMediaStoreObserver()

        // Register screen-off receiver to sweep when phone is locked/idle
        runCatching {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            registerReceiver(screenReceiver, filter)
        }

        // Continuous recurring background sweep loop (every 30 minutes)
        serviceScope.launch {
            while (isActive) {
                performFullSweep(applicationContext)
                delay(30 * 60 * 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun setupRecursiveFileObservers() {
        val rootDirs = getRootMediaDirectories()
        for (dir in rootDirs) {
            attachObserverRecursively(dir)
        }
    }

    private fun attachObserverRecursively(directory: File) {
        if (!directory.exists()) {
            runCatching { directory.mkdirs() }
        }
        if (!directory.isDirectory) return

        try {
            directory.walkTopDown().maxDepth(3).forEach { subDir ->
                if (subDir.isDirectory && watchedPaths.add(subDir.absolutePath)) {
                    val observer = createFileObserver(subDir)
                    observer.startWatching()
                    activeObservers.add(observer)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed attaching observers to ${directory.absolutePath}: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createFileObserver(directory: File): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(directory, mask) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(directory, path, event)
                }
            }
        } else {
            object : FileObserver(directory.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(directory, path, event)
                }
            }
        }
    }

    private fun handleFileEvent(directory: File, fileName: String?, event: Int) {
        if (fileName == null) return
        val targetFile = File(directory, fileName)

        // If a new directory was created, dynamically attach a watcher to it
        if ((event and FileObserver.CREATE) != 0 && targetFile.isDirectory) {
            if (watchedPaths.add(targetFile.absolutePath)) {
                try {
                    val observer = createFileObserver(targetFile)
                    observer.startWatching()
                    activeObservers.add(observer)
                    Log.d(TAG, "Dynamically attached observer to new folder: ${targetFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Error watching new directory: ${e.message}")
                }
            }
            return
        }

        if (!targetFile.exists() || !AdultMediaDetector.isMediaFile(targetFile)) return

        serviceScope.launch {
            delay(350L) // Brief delay to ensure file write is finalized by downloading app
            if (AdultMediaDetector.isExplicit(targetFile)) {
                Log.w(TAG, "REAL-TIME INTERCEPTION: Explicit media detected! Purging '${targetFile.name}'...")
                AdultMediaDetector.purgeFile(applicationContext, targetFile)
            }
        }
    }

    private fun setupMediaStoreObserver() {
        val handler = Handler(Looper.getMainLooper())
        mediaStoreObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (uri == null) return
                serviceScope.launch {
                    processMediaStoreUri(uri)
                }
            }
        }

        runCatching {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver!!
            )
            contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver!!
            )
            Log.i(TAG, "MediaStore content observer registered.")
        }.onFailure { Log.w(TAG, "Failed registering MediaStore observer: ${it.message}") }
    }

    private fun processMediaStoreUri(uri: Uri) {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        runCatching {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val path = cursor.getString(dataIdx)
                    if (path != null) {
                        val file = File(path)
                        if (file.exists() && AdultMediaDetector.isExplicit(file)) {
                            Log.w(TAG, "MediaStore trigger: Explicit file detected at $path. Purging!")
                            AdultMediaDetector.purgeFile(applicationContext, file, uri)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenReceiver) }
        activeObservers.forEach { runCatching { it.stopWatching() } }
        activeObservers.clear()
        watchedPaths.clear()
        mediaStoreObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        serviceScope.cancel()
        Log.i(TAG, "RealtimeMediaObserverService destroyed.")
    }
}
