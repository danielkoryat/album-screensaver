package com.dk.albumdream

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.location.Geocoder
import android.media.ExifInterface
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Hourly run-and-exit sync: reads every album, merges them into one photo set, downloads what's
 * missing, deletes what was removed, and looks up where photos were taken. No service stays alive
 * between runs.
 *
 * Writes `meta.json`: id -> {"t": takenAt, "tz": offset, "u": [album urls], "loc": place, "g": geoCheckedAt}.
 */
class SyncJob : JobService() {
    @Volatile private var stopped = false
    private var deadline = 0L

    /** Jobs are cut off at 10 minutes; stop a little earlier so results get saved. */
    private fun outOfTime() = stopped || System.currentTimeMillis() > deadline
    private var forceGeo = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        forceGeo = params.extras.getBoolean(FORCE_GEO)
        Thread {
            val prefs = Prefs(this)
            try {
                synchronized(LOCK) {
                    prefs.syncing = true
                    sync(prefs)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "sync failed", t)
                prefs.lastSyncResult = "Sync failed: ${t.message}"
            } finally {
                prefs.syncing = false
            }
            // Hit our own time budget with work left (not stopped by the system): carry on in a
            // fresh run soon instead of waiting an hour. Alternate ids so we never replace ourselves.
            if (!stopped && System.currentTimeMillis() > deadline) {
                continueSoon(this, if (params.jobId == CONTINUE_ID) CONTINUE_ID + 1 else CONTINUE_ID)
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true
    }

    private fun sync(prefs: Prefs) {
        deadline = System.currentTimeMillis() + 8 * 60_000L + 30_000L
        File(filesDir, "photos").deleteRecursively() // v1 cache (1080p-fit downloads)
        val dir = photoDir(this)
        val oldMeta = readMeta(this)
        val meta = JSONObject()
        val status = JSONObject()
        val wanted = LinkedHashMap<String, AlbumFetcher.Photo?>()
        var failures = 0

        for (url in prefs.albums) {
            if (stopped) return
            try {
                val album = AlbumFetcher.fetch(url)
                for (p in album.photos) {
                    wanted[p.id] = p
                    val m = meta.optJSONObject(p.id) ?: JSONObject().also { meta.put(p.id, it) }
                    m.put("t", p.takenAt).put("tz", p.tzOffsetMs)
                    m.put("u", (m.optJSONArray("u") ?: JSONArray()).put(url))
                }
                status.put(url, JSONObject().put("name", album.name).put("count", album.photos.size)
                    .put("videos", album.videosSkipped).put("at", System.currentTimeMillis()))
            } catch (e: Exception) {
                // Keep showing what we already have for this album until it can be read again.
                failures++
                Log.w(TAG, "album fetch failed: $url", e)
                for (id in oldMeta.keys()) {
                    val old = oldMeta.getJSONObject(id)
                    if (!old.optJSONArray("u").contains(url)) continue
                    wanted.putIfAbsent(id, null)
                    val m = meta.optJSONObject(id) ?: JSONObject(old.toString()).put("u", JSONArray()).also { meta.put(id, it) }
                    m.getJSONArray("u").put(url)
                }
                val old = prefs.albumStatus.optJSONObject(url) ?: JSONObject()
                status.put(url, old.put("error", e.message ?: e.javaClass.simpleName))
            }
        }
        // Carry over what earlier runs worked out: places, photo analysis, download version.
        for (id in meta.keys()) {
            val old = oldMeta.optJSONObject(id) ?: continue
            val m = meta.getJSONObject(id)
            for (k in CARRIED) if (old.has(k)) m.put(k, old.get(k))
        }

        // Cap the cache: keep what's already downloaded first, then a random sample of the rest.
        val cached = dir.list()?.filter { it.endsWith(".jpg") }?.map { it.removeSuffix(".jpg") }?.toSet() ?: emptySet()
        val keep = if (wanted.size <= MAX_PHOTOS) wanted.keys else
            (wanted.keys.filter { it in cached }.shuffled() + wanted.keys.filter { it !in cached }.shuffled())
                .take(MAX_PHOTOS).toSet()

        dir.listFiles()?.forEach { f ->
            if (f.name.removeSuffix(".jpg") !in keep || f.name.endsWith(".tmp")) f.delete()
        }
        prefs.albumStatus = status
        writeMeta(meta)

        var bytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
        var dlErrors = 0
        /** Downloads [ids] (replacing existing files) until out of time; returns how many succeeded. */
        fun download(ids: List<String>, isNew: Boolean): Int {
            var n = 0
            for (id in ids) {
                if (outOfTime()) break
                if (isNew && bytes > MAX_BYTES) break
                val photo = wanted[id] ?: continue
                try {
                    val f = File(dir, "$id.jpg")
                    val before = f.length()
                    AlbumFetcher.download(photo, f)
                    bytes += f.length() - before
                    meta.getJSONObject(id).put("q", 1)
                    n++
                } catch (e: Exception) {
                    dlErrors++
                    Log.w(TAG, "download failed: $id", e)
                }
            }
            writeMeta(meta)
            return n
        }

        // Order matters when a run is cut short: new photos, then the analysis and places that
        // make them look good, and only then shrinking photos fetched before downloads were
        // right-sized ("q" missing).
        val downloaded = download(keep.filter { it !in cached }.shuffled(), isNew = true)

        val analyzed = analyzePhotos(meta, keep, dir)
        val dups = PhotoAnalyzer.markBursts(meta)
        writeMeta(meta)

        val located = lookUpPlaces(meta, keep, wanted)
        val borrowed = borrowPlaces(meta)
        writeMeta(meta)

        val outdated = keep.filter { it in cached && meta.optJSONObject(it)?.has("q") == false }
            .partition { File(dir, "$it.jpg").lastModified() < RESIZED_SINCE }
        outdated.second.forEach { meta.getJSONObject(it).put("q", 1) } // already right-sized
        val upgraded = download(outdated.first, isNew = false)

        prefs.lastSync = System.currentTimeMillis()
        val total = dir.list()?.count { it.endsWith(".jpg") } ?: 0
        prefs.lastSyncResult = buildString {
            append("$total photos")
            if (downloaded > 0) append(", $downloaded new")
            if (located > 0) append(", $located located")
            if (borrowed > 0) append(", $borrowed placed by time")
            if (upgraded > 0) append(", $upgraded shrunk")
            if (analyzed > 0) append(", $analyzed analysed")
            if (dups > 0) append(", $dups near-duplicates hidden")
            if (failures > 0) append(", $failures album(s) unreachable")
            if (dlErrors > 0) append(", $dlErrors download error(s)")
            if (outOfTime()) append(" (paused, will resume)")
        }
        Log.i(TAG, prefs.lastSyncResult)
    }

    /**
     * Reads GPS from each original's EXIF header and turns it into a place name. Photos without
     * GPS are re-checked weekly, in case the owner later allows location sharing.
     */
    private fun lookUpPlaces(meta: JSONObject, keep: Set<String>, wanted: Map<String, AlbumFetcher.Photo?>): Int {
        val geocoder = if (Geocoder.isPresent()) Geocoder(this, Locale.getDefault()) else null
        val now = System.currentTimeMillis()
        var found = 0
        var checked = 0
        for (id in keep) {
            if (outOfTime() || checked >= GEO_PER_RUN) break
            val m = meta.optJSONObject(id) ?: continue
            val photo = wanted[id] ?: continue
            if (m.optString("loc").isNotEmpty()) continue
            if (!forceGeo && now - m.optLong("g") < TimeUnit.DAYS.toMillis(7)) continue
            checked++
            try {
                val exif = ExifInterface(ByteArrayInputStream(AlbumFetcher.originalHead(photo)))
                val ll = FloatArray(2)
                if (exif.getLatLong(ll)) {
                    m.put("loc", placeName(geocoder, ll[0].toDouble(), ll[1].toDouble()))
                    found++
                }
                m.put("g", now)
            } catch (e: Exception) {
                Log.w(TAG, "geo lookup failed: $id", e)
            }
        }
        return found
    }

    private fun analyzePhotos(meta: JSONObject, keep: Set<String>, dir: File): Int {
        var n = 0
        for (id in keep) {
            if (outOfTime()) break
            val m = meta.optJSONObject(id) ?: continue
            if (m.has("h")) continue
            val f = File(dir, "$id.jpg")
            if (!f.exists()) continue
            try {
                if (PhotoAnalyzer.analyze(f, m)) n++
            } catch (e: Throwable) {
                Log.w(TAG, "analysis failed: $id", e)
            }
        }
        return n
    }

    /**
     * Photos without GPS (e.g. from a phone with camera location off) take the place of the photo
     * taken closest in time, if one within [BORROW_WINDOW_MS] has GPS. Stored as "loc2" so a real
     * GPS place found later still wins.
     */
    private fun borrowPlaces(meta: JSONObject): Int {
        val located = meta.keys().asSequence().map { meta.getJSONObject(it) }
            .filter { it.optString("loc").isNotEmpty() && it.optLong("t") > 0 }
            .map { it.getLong("t") to it.getString("loc") }
            .sortedBy { it.first }.toList()
        val times = located.map { it.first }
        var count = 0
        for (id in meta.keys()) {
            val m = meta.getJSONObject(id)
            m.remove("loc2")
            val t = m.optLong("t")
            if (m.optString("loc").isNotEmpty() || t <= 0 || located.isEmpty()) continue
            val i = times.binarySearch(t).let { if (it >= 0) it else -it - 1 }
            val nearest = listOfNotNull(located.getOrNull(i - 1), located.getOrNull(i))
                .minByOrNull { Math.abs(it.first - t) } ?: continue
            if (Math.abs(nearest.first - t) <= BORROW_WINDOW_MS) {
                m.put("loc2", nearest.second)
                count++
            }
        }
        return count
    }

    private fun placeName(geocoder: Geocoder?, lat: Double, lng: Double): String {
        try {
            @Suppress("DEPRECATION")
            val a = geocoder?.getFromLocation(lat, lng, 1)?.firstOrNull()
            if (a != null) {
                val parts = listOfNotNull(a.subLocality ?: a.locality ?: a.subAdminArea, a.adminArea ?: a.countryName)
                    .distinct()
                if (parts.isNotEmpty()) return parts.joinToString(", ")
            }
        } catch (e: Exception) {
            Log.w(TAG, "geocoder failed", e)
        }
        return String.format(Locale.US, "%.4f, %.4f", lat, lng)
    }

    private fun writeMeta(meta: JSONObject) {
        val tmp = File(filesDir, "meta.json.tmp")
        tmp.writeText(meta.toString())
        tmp.renameTo(File(filesDir, "meta.json"))
    }

    private fun JSONArray?.contains(s: String): Boolean {
        if (this == null) return false
        for (i in 0 until length()) if (optString(i) == s) return true
        return false
    }

    companion object {
        private const val TAG = "AlbumSync"
        private val LOCK = Any()
        private const val PERIODIC_ID = 1
        private const val NOW_ID = 2
        private const val CONTINUE_ID = 3
        private const val GEO_PER_RUN = 600
        /** When right-sized downloads shipped (2026-10-25 11:15 EDT); older files get replaced. */
        private const val RESIZED_SINCE = 1792941300000L
        private val CARRIED = listOf("loc", "g", "q", "sh", "br", "fc", "h")
        private val BORROW_WINDOW_MS = TimeUnit.HOURS.toMillis(2)
        private const val FORCE_GEO = "force_geo"
        const val MAX_PHOTOS = 4000
        private const val MAX_BYTES = 1200L * 1024 * 1024 // leaves >1 GB free on the 8 GB Mi Box

        fun photoDir(ctx: Context) = File(ctx.filesDir, "photos2").apply { mkdirs() }

        fun readMeta(ctx: Context): JSONObject = try {
            JSONObject(File(ctx.filesDir, "meta.json").readText())
        } catch (e: Exception) { JSONObject() }

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(PERIODIC_ID) != null) return
            js.schedule(JobInfo.Builder(PERIODIC_ID, ComponentName(ctx, SyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.HOURS.toMillis(1))
                .setPersisted(true)
                .build())
        }

        private fun continueSoon(ctx: Context, id: Int) {
            ctx.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(id, ComponentName(ctx, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(15_000)
                    .build())
        }

        /** [recheckPlaces]: look for GPS again in every photo without a place, not just weekly. */
        fun runNow(ctx: Context, recheckPlaces: Boolean = false) {
            ctx.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(NOW_ID, ComponentName(ctx, SyncJob::class.java))
                    .setExtras(android.os.PersistableBundle().apply { putBoolean(FORCE_GEO, recheckPlaces) })
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(0)
                    .build())
        }
    }
}
