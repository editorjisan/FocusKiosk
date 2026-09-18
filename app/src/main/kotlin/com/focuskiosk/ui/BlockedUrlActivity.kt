package com.focuskiosk.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * BlockedUrlActivity
 * ──────────────────
 * Intercepts deep links and web intent actions for prohibited domains
 * (e.g. facebook.com, fb.watch, instagram.com, tiktok.com, and adult domains).
 * When a link is tapped inside Messenger, WhatsApp, or any other app,
 * this activity catches the intent, alerts the user, and immediately closes
 * so the link never opens in any browser or webview.
 */
class BlockedUrlActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val url = intent?.dataString ?: "Blocked Content"
        android.util.Log.i("BlockedUrlActivity", "Intercepted prohibited deep link: $url")
        Toast.makeText(
            this,
            "Focus Kiosk: Access to this social/adult link is blocked.",
            Toast.LENGTH_LONG
        ).show()
        
        finish()
    }
}
