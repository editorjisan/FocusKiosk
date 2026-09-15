package com.focuskiosk.updater

import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * ApkVerifier
 * -----------
 * Verifies the SHA-256 hash of a downloaded APK before installation.
 *
 * Why this matters:
 *  - Prevents installing a corrupted download.
 *  - Prevents installing a tampered APK if the CDN URL is hijacked
 *    (the manifest SHA-256 is fetched over HTTPS from a trusted endpoint).
 */
object ApkVerifier {

    private const val TAG = "ApkVerifier"

    fun verify(file: File, expectedHex: String): Boolean {
        return runCatching {
            val actual = sha256Hex(file.inputStream())
            val ok = actual.equals(expectedHex.trim(), ignoreCase = true)
            if (ok) Log.i(TAG, "APK integrity OK: $actual")
            else Log.e(TAG, "SHA-256 MISMATCH! Expected: $expectedHex | Actual: $actual")
            ok
        }.getOrElse { Log.e(TAG, "Verification error: ${it.message}"); false }
    }

    private fun sha256Hex(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf    = ByteArray(8192)
        stream.use { while (true) { val n = it.read(buf); if (n == -1) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
