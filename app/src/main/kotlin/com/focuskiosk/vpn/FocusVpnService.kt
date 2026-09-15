package com.focuskiosk.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.focuskiosk.storage.SecureStorage
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * FocusVpnService
 * ---------------
 * On-device local VPN for DNS-level domain blocking.
 *
 * Architecture:
 *  - Creates a TUN interface that captures all UDP port-53 (DNS) traffic.
 *  - Checks queried hostnames against a blocked-domains list.
 *  - Drops packets for blocked domains (browser sees NXDOMAIN / timeout).
 *  - All other traffic forwarded transparently. NO HTTPS inspection.
 *
 * Financial-app compliance (critical):
 *  - Each financial package is added via addDisallowedApplication().
 *  - Disallowed apps bypass the TUN interface entirely; their traffic goes
 *    straight to the network adapter.
 *  - This preserves SSL certificate pinning, root detection, and certificate
 *    transparency checks for banking apps — none of their TLS is intercepted.
 *
 * NOTE: The packet-processing loop here is a minimal demonstration.
 * A production deployment should use a battle-tested DNS filtering library
 * (e.g. dns4j or pcap4j) for robust DNS parsing.
 */
class FocusVpnService : VpnService() {

    companion object {
        private const val TAG          = "FocusVpnService"
        const val ACTION_START         = "com.focuskiosk.vpn.START"
        const val ACTION_STOP          = "com.focuskiosk.vpn.STOP"

        /** Default blocked domains — edit freely. */
        val BLOCKED_DOMAINS = setOf(
            "youtube.com", "www.youtube.com",
            "reddit.com",  "www.reddit.com",
            "twitter.com", "x.com",
            "instagram.com", "tiktok.com",
            "facebook.com", "www.facebook.com"
        )
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopVpn(); START_NOT_STICKY }
            else        -> { startVpn(); START_STICKY }
        }
    }

    private fun startVpn() {
        Log.i(TAG, "Starting Focus VPN...")

        val financialPkgs = SecureStorage.getPackageSet(
            applicationContext, SecureStorage.KEY_FINANCIAL_PACKAGES)

        val builder = Builder()
            .setSession("FocusKiosk VPN")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.2")   // Route DNS queries through our TUN.
            .addRoute("0.0.0.0", 0)     // Capture all IPv4 traffic.
            .addDisallowedApplication(packageName) // Kiosk app bypasses VPN (for updates).

        // Financial app VPN bypass (split tunnelling):
        // These apps send traffic directly to the NIC — TUN never sees it.
        financialPkgs.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
                .onSuccess { Log.i(TAG, "VPN bypass for financial app: $pkg") }
                .onFailure { Log.w(TAG, "Could not bypass $pkg: ${it.message}") }
        }

        tunInterface = builder.establish()
        if (tunInterface == null) {
            Log.e(TAG, "VPN establish() returned null — permission missing."); return
        }
        Log.i(TAG, "TUN interface established.")
        scope.launch { processDnsPackets() }
    }

    /**
     * DNS packet processing loop.
     * Reads raw IPv4 packets from the TUN file descriptor.
     * UDP/DNS packets are inspected; blocked hostnames are dropped.
     * Everything else is written back to the TUN (loopback forwarding).
     */
    private fun processDnsPackets() {
        val fd     = tunInterface ?: return
        val input  = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf    = ByteBuffer.allocate(32767)

        while (scope.isActive && tunInterface != null) {
            runCatching {
                buf.clear()
                val len = input.read(buf.array())
                if (len <= 0) return@runCatching
                buf.limit(len)

                // IPv4 protocol field is at byte offset 9.
                val proto = buf.get(9).toInt() and 0xFF
                if (proto == 0x11) { // UDP
                    // Destination port: bytes 22-23 (IP header=20 + UDP dest offset=2).
                    val destPort = ((buf.get(22).toInt() and 0xFF) shl 8) or
                                    (buf.get(23).toInt() and 0xFF)
                    if (destPort == 53) {
                        val host = extractDnsHostname(buf)
                        if (host != null && isBlocked(host)) {
                            Log.d(TAG, "Blocked DNS: $host")
                            return@runCatching // Drop the packet.
                        }
                    }
                }
                output.write(buf.array(), 0, len)
            }.onFailure { if (scope.isActive) Log.e(TAG, "Packet error: ${it.message}") }
        }
    }

    private fun isBlocked(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return BLOCKED_DOMAINS.any { d -> h == d || h.endsWith(".$d") }
    }

    /**
     * Minimal DNS name extractor.
     * DNS query name starts at byte 40 (IP=20 + UDP=8 + DNS header=12).
     * Each label is [length_byte][label_bytes], terminated by 0x00.
     */
    private fun extractDnsHostname(pkt: ByteBuffer): String? {
        return runCatching {
            val labels = mutableListOf<String>()
            var pos = 40
            while (pos < pkt.limit()) {
                val len = pkt.get(pos).toInt() and 0xFF
                if (len == 0 || pos + 1 + len > pkt.limit()) break
                labels.add(String(pkt.array(), pos + 1, len, Charsets.US_ASCII))
                pos += 1 + len
            }
            if (labels.isEmpty()) null else labels.joinToString(".")
        }.getOrNull()
    }

    private fun stopVpn() {
        scope.cancel()
        tunInterface?.close()
        tunInterface = null
        stopSelf()
        Log.i(TAG, "VPN stopped.")
    }

    override fun onDestroy() { super.onDestroy(); stopVpn() }

    private val CoroutineScope.isActive: Boolean
        get() = coroutineContext[Job]?.isActive == true
}
