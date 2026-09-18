package com.focuskiosk.media

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
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
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeObservers = mutableListOf<FileObserver>()
    private var mediaStoreObserver: ContentObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Initializing real-time media sentinel...")
        setupFileObservers()
        setupMediaStoreObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun setupFileObservers() {
        val watchPaths = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            File(Environment.getExternalStorageDirectory(), "Download"),
            File(Environment.getExternalStorageDirectory(), "Pictures"),
            File(Environment.getExternalStorageDirectory(), "DCIM/Camera")
        ).distinct()

        for (dir in watchPaths) {
            if (!dir.exists()) dir.mkdirs()
            runCatching {
                val observer = createFileObserver(dir)
                observer.startWatching()
                activeObservers.add(observer)
                Log.d(TAG, "Watching directory: ${dir.absolutePath}")
            }.onFailure {
                Log.w(TAG, "Could not watch ${dir.absolutePath}: ${it.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createFileObserver(directory: File): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(directory, mask) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(directory, path)
                }
            }
        } else {
            object : FileObserver(directory.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(directory, path)
                }
            }
        }
    }

    private fun handleFileEvent(directory: File, fileName: String?) {
        if (fileName == null) return
        val targetFile = File(directory, fileName)
        if (!targetFile.exists() || !AdultMediaDetector.isMediaFile(targetFile)) return

        serviceScope.launch {
            delay(400L) // Brief delay to ensure file write is finalized by downloading app
            if (AdultMediaDetector.isExplicit(targetFile)) {
                Log.w(TAG, "REAL-TIME INTERCEPTION: Adult media detected! Purging '${targetFile.name}'...")
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
        activeObservers.forEach { runCatching { it.stopWatching() } }
        activeObservers.clear()
        mediaStoreObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        serviceScope.cancel()
        Log.i(TAG, "RealtimeMediaObserverService destroyed.")
    }
}
