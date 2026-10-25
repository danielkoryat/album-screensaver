package com.dk.albumdream

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Reads a public Google Photos shared-album link without signing in.
 *
 * Unofficial: it parses the album page's embedded `ds:1` data block and pages through the rest with
 * the web client's `snAcKc` batchexecute RPC. If Google changes the page, this is what breaks.
 */
object AlbumFetcher {
    /** [takenAt] is UTC millis; [tzOffsetMs] is the offset where it was taken. */
    data class Photo(val id: String, val baseUrl: String, val takenAt: Long, val tzOffsetMs: Long,
                     val width: Int, val height: Int)
    data class Album(val name: String, val photos: List<Photo>, val videosSkipped: Int)

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private const val RPC = "https://photos.google.com/_/PhotosUi/data/batchexecute"
    private const val VIDEO_MARKER = "76647426"
    private const val MAX_PAGES = 100

    fun fetch(link: String): Album {
        val html = httpGet(link)
        val data = JSONArray(extractDs1(html))
        val meta = data.getJSONArray(3)
        val albumId = meta.getString(0)
        val name = meta.optString(1, "Album")
        val authKey = meta.optString(19, "")

        val photos = ArrayList<Photo>()
        var videos = 0
        var page = data
        var pages = 0
        while (true) {
            val items = page.optJSONArray(1) ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONArray(i)
                val extra = item.opt(item.length() - 1)
                if (extra is JSONObject && extra.has(VIDEO_MARKER)) { videos++; continue }
                val img = item.getJSONArray(1)
                photos += Photo(item.getString(0), img.getString(0), item.optLong(2), item.optLong(4),
                    img.optInt(1), img.optInt(2))
            }
            val token = page.opt(2) as? String
            if (token.isNullOrEmpty() || ++pages >= MAX_PAGES) break
            page = fetchPage(albumId, token, authKey)
        }
        return Album(name, photos, videos)
    }

    private fun extractDs1(html: String): String {
        val key = html.indexOf("key: 'ds:1'")
        if (key < 0) throw IOException("album data not found (link sharing off, or page format changed)")
        val start = html.indexOf("data:", key) + 5
        val end = html.indexOf(", sideChannel", start)
        if (start < 5 || end < 0) throw IOException("album data block malformed")
        return html.substring(start, end)
    }

    private fun fetchPage(albumId: String, token: String, authKey: String): JSONArray {
        val args = JSONArray().put(albumId).put(token).put(JSONObject.NULL).put(authKey).toString()
        val req = JSONArray().put(JSONArray().put(JSONArray().put("snAcKc").put(args).put(JSONObject.NULL).put("generic")))
        val body = "f.req=" + URLEncoder.encode(req.toString(), "UTF-8")
        val resp = httpPost(RPC, body)
        val line = resp.lineSequence().firstOrNull { it.startsWith("[[\"wrb.fr\"") }
            ?: throw IOException("unexpected batchexecute response")
        return JSONArray(JSONArray(line).getJSONArray(0).getString(2))
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }

    private fun httpGet(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for album page")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun httpPost(url: String, body: String): String {
        val c = open(url)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} paging album")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /**
     * Downloads [photo] at the size a 1080p screen needs, already EXIF-rotated by Google: landscape
     * wide enough to crop-fill the screen, portrait tall enough to fit it. JPEG quality 85 roughly
     * halves the file size versus the default with no visible difference on a TV.
     */
    fun download(photo: Photo, dest: java.io.File) {
        val wide = photo.width.toFloat() / photo.height.coerceAtLeast(1)
        val size = when {
            photo.width <= 0 || photo.height <= 0 -> "s1920"
            wide >= 1.3f && wide <= 16f / 9f -> "w1920"
            else -> "h1080"
        }
        val c = open(photo.baseUrl + "=$size-l85")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} downloading ${photo.id}")
            val tmp = java.io.File(dest.path + ".tmp")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) throw IOException("rename failed for ${dest.name}")
        } finally { c.disconnect() }
    }

    /**
     * First 64 KB of the original file, where the EXIF block lives. GPS is only there if the owner
     * turned off "Remove geo location in items that you share by link" in Google Photos.
     */
    fun originalHead(photo: Photo): ByteArray {
        val c = open(photo.baseUrl + "=d")
        try {
            c.setRequestProperty("Range", "bytes=0-65535")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} reading EXIF")
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            c.inputStream.use { input ->
                while (out.size() < 65536) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }
}
