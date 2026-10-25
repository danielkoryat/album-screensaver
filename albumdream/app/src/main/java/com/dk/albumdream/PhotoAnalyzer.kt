package com.dk.albumdream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.FaceDetector
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Cheap on-device photo signals for picking the more interesting photos, computed once per photo
 * on a ~320 px copy: sharpness, brightness, face count and a perceptual hash for spotting bursts.
 *
 * Stored in meta.json as "sh" (sharpness), "br" (0-255 brightness), "fc" (faces), "h" (dHash).
 */
object PhotoAnalyzer {
    private const val WIDTH = 320

    fun analyze(file: File, m: JSONObject): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return false
        // FaceDetector needs RGB_565 and an even width.
        val h = (raw.height * WIDTH / raw.width.toFloat()).toInt() and 1.inv()
        val small = Bitmap.createScaledBitmap(raw, WIDTH, maxOf(2, h), true)
        if (small !== raw) raw.recycle()
        try {
            val w = small.width
            val hh = small.height
            val px = IntArray(w * hh)
            small.getPixels(px, 0, w, 0, 0, w, hh)
            val lum = IntArray(px.size) { (px[it] shr 16 and 0xFF) * 299 + (px[it] shr 8 and 0xFF) * 587 + (px[it] and 0xFF) * 114 }
            for (i in lum.indices) lum[i] /= 1000
            m.put("br", lum.average().toInt())
            m.put("sh", laplacianVariance(lum, w, hh))
            m.put("h", dHash(lum, w, hh))
            val faces = arrayOfNulls<FaceDetector.Face>(8)
            m.put("fc", FaceDetector(w, hh, faces.size).findFaces(small, faces))
        } finally {
            small.recycle()
        }
        return true
    }

    /** Variance of the Laplacian: low for blurry photos. */
    private fun laplacianVariance(lum: IntArray, w: Int, h: Int): Double {
        var sum = 0.0
        var sq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = (4 * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - w] - lum[i + w]).toDouble()
            sum += l; sq += l * l; n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sq / n - mean * mean
    }

    /** 64-bit difference hash: each bit says whether a cell is brighter than its right neighbour. */
    private fun dHash(lum: IntArray, w: Int, h: Int): Long {
        val cells = Array(8) { IntArray(9) }
        val counts = Array(8) { IntArray(9) }
        for (y in 0 until h) for (x in 0 until w) {
            val cy = y * 8 / h
            val cx = x * 9 / w
            cells[cy][cx] += lum[y * w + x]
            counts[cy][cx]++
        }
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val a = cells[y][x] / maxOf(1, counts[y][x])
            val b = cells[y][x + 1] / maxOf(1, counts[y][x + 1])
            hash = hash shl 1 or (if (a > b) 1L else 0L)
        }
        return hash
    }

    /**
     * Marks near-duplicates ("dup": true): photos taken within a couple of minutes of each other
     * that look alike. The sharpest of each group (with a bonus for faces) stays visible.
     */
    fun markBursts(meta: JSONObject): Int {
        val photos = meta.keys().asSequence().map { it to meta.getJSONObject(it) }
            .filter { it.second.has("h") && it.second.optLong("t") > 0 }
            .sortedBy { it.second.getLong("t") }.toList()
        photos.forEach { it.second.remove("dup") }
        var hidden = 0
        var group = ArrayList<JSONObject>()
        fun close() {
            if (group.size > 1) {
                val best = group.maxByOrNull { quality(it) }
                group.filter { it !== best }.forEach { it.put("dup", true); hidden++ }
            }
            group = ArrayList()
        }
        for ((_, m) in photos) {
            val prev = group.lastOrNull()
            if (prev != null && (m.getLong("t") - prev.getLong("t") > 120_000 ||
                    java.lang.Long.bitCount(m.getLong("h") xor prev.getLong("h")) > 10)) close()
            group.add(m)
        }
        close()
        return hidden
    }

    private fun quality(m: JSONObject) = m.optDouble("sh", 0.0) * (1 + m.optInt("fc"))

    /**
     * Relative chance of showing a photo. Never zero except for hidden duplicates, so every photo
     * still comes up eventually. [sharpCutoff] is the library's 15th-percentile sharpness.
     */
    fun weight(m: JSONObject, sharpCutoff: Double, today: Calendar): Double {
        if (m.optBoolean("dup")) return 0.0
        var w = 1.0
        if (m.has("sh") && m.getDouble("sh") < sharpCutoff) w *= 0.25
        if (m.has("br") && m.getInt("br") < 45) w *= 0.4
        if (m.optInt("fc") > 0) w *= 1.8
        val t = m.optLong("t")
        if (t > 0) {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = t + m.optLong("tz") }
            val days = abs(c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR))
            if (c.get(Calendar.YEAR) < today.get(Calendar.YEAR) && minOf(days, 365 - days) <= 3) w *= 3.0
        }
        return w
    }
}
