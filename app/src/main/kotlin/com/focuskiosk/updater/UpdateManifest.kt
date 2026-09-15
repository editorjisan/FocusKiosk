package com.focuskiosk.updater

/**
 * UpdateManifest
 * --------------
 * Maps to the remote JSON update manifest.
 *
 * Example JSON hosted on your secure server:
 * {
 *   "versionCode"  : 2,
 *   "versionName"  : "1.1.0",
 *   "apkUrl"       : "https://your-server.com/releases/focuskiosk-1.1.0.apk",
 *   "sha256"       : "e3b0c44298fc1c149afbf4c8996fb924...",
 *   "releaseNotes" : "Bug fixes.",
 *   "minSdkVersion": 28
 * }
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    /** SHA-256 hex digest of the APK for integrity verification (optional). */
    val sha256: String = "",
    val releaseNotes: String = "",
    val minSdkVersion: Int = 28
)
