package com.dk.albumdream

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Tiny LAN page for pasting an album link from a phone, because typing one on a TV remote is
 * painful. It only runs while the settings screen is open.
 */
class LinkServer(private val onLink: (String) -> String) {
    private var server: ServerSocket? = null

    fun start(): Boolean {
        if (server != null) return true
        return try {
            val s = ServerSocket(PORT)
            server = s
            Thread {
                while (!s.isClosed) {
                    try { s.accept().use { handle(it) } } catch (e: Exception) {
                        if (!s.isClosed) Log.w("LinkServer", "request failed", e)
                    }
                }
            }.start()
            true
        } catch (e: Exception) {
            Log.w("LinkServer", "can't listen on $PORT", e)
            false
        }
    }

    fun stop() {
        server?.close()
        server = null
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 10_000
        val input = sock.getInputStream().buffered()
        val requestLine = readLine(input) ?: return
        var length = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        var message = ""
        if (requestLine.startsWith("POST")) {
            val body = ByteArray(minOf(length, 16_384))
            var read = 0
            while (read < body.size) {
                val n = input.read(body, read, body.size - read)
                if (n < 0) break
                read += n
            }
            val form = String(body, 0, read)
            val text = form.split('&').firstOrNull { it.startsWith("link=") }
                ?.let { URLDecoder.decode(it.substringAfter('='), "UTF-8") } ?: ""
            val link = Prefs.findLink(text)
            message = if (link == null) "That doesn't look like a Google Photos shared-album link." else onLink(link)
        }
        val html = page(message).toByteArray()
        sock.getOutputStream().apply {
            write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(html)
            flush()
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 8192) sb.append(c.toChar())
        }
    }

    private fun page(message: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Add album to TV</title>
<style>
body{font-family:system-ui,sans-serif;background:#101114;color:#e8eaed;margin:0;padding:24px}
h1{font-size:22px;margin:0 0 6px}p{color:#9aa0a6;line-height:1.45}
textarea{width:100%;box-sizing:border-box;height:110px;font-size:16px;padding:12px;border-radius:12px;border:1px solid #3c4043;background:#1b1c20;color:#e8eaed}
button{margin-top:12px;width:100%;padding:14px;font-size:17px;border:0;border-radius:12px;background:#8ab4f8;color:#101114;font-weight:600}
.msg{background:#1e3a2b;color:#c5f0d0;padding:12px;border-radius:12px;margin:14px 0}
</style></head><body>
<h1>Add a Google Photos album to the TV</h1>
<p>In Google Photos, open the album, tap <b>Share</b>, then <b>Copy link</b>. Paste it below.</p>
${if (message.isEmpty()) "" else "<div class=\"msg\">${message.replace("<", "&lt;")}</div>"}
<form method="post"><textarea name="link" placeholder="https://photos.app.goo.gl/..."></textarea>
<button type="submit">Add to TV</button></form></body></html>"""

    companion object {
        const val PORT = 8765

        /** The box's LAN address, skipping VPN tunnels. */
        fun lanAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("wg") && !it.name.startsWith("ppp") }
                .sortedBy { if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) { null }
    }
}
