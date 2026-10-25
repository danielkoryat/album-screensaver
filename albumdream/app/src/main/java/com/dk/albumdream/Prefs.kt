package com.dk.albumdream

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Album links, per-album status, and slideshow settings. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var albums: List<String>
        get() = sp.getString("albums", "[]")!!.toList()
        set(value) = sp.edit().putString("albums", JSONArray(value).toString()).apply()

    /** Albums kept in sync but left out of the slideshow. */
    var disabled: Set<String>
        get() = sp.getString("disabled", "[]")!!.toList().toSet()
        set(value) = sp.edit().putString("disabled", JSONArray(value.toList()).toString()).apply()

    val enabledAlbums get() = albums.filter { it !in disabled }

    var slideSeconds: Int
        get() = sp.getInt("slide_seconds", 15)
        set(value) = sp.edit().putInt("slide_seconds", value).apply()

    var fadeMs: Int
        get() = sp.getInt("fade_ms", 1500)
        set(value) = sp.edit().putInt("fade_ms", value).apply()

    /** One of FIT_SMART, FIT_CROP, FIT_WHOLE. */
    var fitMode: Int
        get() = sp.getInt("fit_mode", FIT_SMART)
        set(value) = sp.edit().putInt("fit_mode", value).apply()

    /** true: every album gets equal screen time; false: every photo does. */
    var mixEven: Boolean
        get() = sp.getBoolean("mix_even", false)
        set(value) = sp.edit().putBoolean("mix_even", value).apply()

    var showDate: Boolean
        get() = sp.getBoolean("show_date", true)
        set(value) = sp.edit().putBoolean("show_date", value).apply()

    var showLocation: Boolean
        get() = sp.getBoolean("show_location", true)
        set(value) = sp.edit().putBoolean("show_location", value).apply()

    var showAlbum: Boolean
        get() = sp.getBoolean("show_album", true)
        set(value) = sp.edit().putBoolean("show_album", value).apply()

    var showClock: Boolean
        get() = sp.getBoolean("show_clock", true)
        set(value) = sp.edit().putBoolean("show_clock", value).apply()

    /** Slow zoom-and-drift on each photo. */
    var motion: Boolean
        get() = sp.getBoolean("motion", true)
        set(value) = sp.edit().putBoolean("motion", value).apply()

    /** Favour sharp photos, people and "on this day"; hide near-duplicate bursts. */
    var smartPick: Boolean
        get() = sp.getBoolean("smart_pick", true)
        set(value) = sp.edit().putBoolean("smart_pick", value).apply()

    /** url -> {"name","count","videos","error","at"}; written by SyncJob. */
    var albumStatus: JSONObject
        get() = JSONObject(sp.getString("album_status", "{}")!!)
        set(value) = sp.edit().putString("album_status", value.toString()).apply()

    var lastSync: Long
        get() = sp.getLong("last_sync", 0)
        set(value) = sp.edit().putLong("last_sync", value).apply()

    var lastSyncResult: String
        get() = sp.getString("last_sync_result", "")!!
        set(value) = sp.edit().putString("last_sync_result", value).apply()

    /** Expires on its own, so a sync killed mid-run (update, reboot) doesn't show as running forever. */
    var syncing: Boolean
        get() = System.currentTimeMillis() - sp.getLong("syncing_since", 0) < 11 * 60_000L
        set(value) = sp.edit().putLong("syncing_since", if (value) System.currentTimeMillis() else 0).apply()

    fun addAlbum(url: String): Boolean {
        val clean = url.trim()
        if (!isAlbumLink(clean) || clean in albums) return false
        albums = albums + clean
        return true
    }

    fun removeAlbum(url: String) {
        albums = albums - url
        disabled = disabled - url
        albumStatus = albumStatus.apply { remove(url) }
    }

    fun albumName(url: String): String =
        albumStatus.optJSONObject(url)?.optString("name")?.takeIf { it.isNotEmpty() } ?: "Album"

    private fun String.toList(): List<String> {
        val arr = JSONArray(this)
        return List(arr.length()) { arr.getString(it) }
    }

    companion object {
        const val FIT_SMART = 0
        const val FIT_CROP = 1
        const val FIT_WHOLE = 2

        private val LINK = Regex("""https://(photos\.app\.goo\.gl/[A-Za-z0-9]+|photos\.google\.com/share/[^\s"'<>]+)""")

        fun isAlbumLink(url: String) = LINK.matches(url.trim())

        /** Pulls the album link out of text like "Check out my album! https://photos.app.goo.gl/...". */
        fun findLink(text: String): String? = LINK.find(text)?.value
    }
}
