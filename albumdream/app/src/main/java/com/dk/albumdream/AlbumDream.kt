package com.dk.albumdream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.view.animation.LinearInterpolator
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shuffled slideshow over the enabled albums combined. Each slide is a full-screen layer (blurred
 * backdrop, photo, caption) and two layers crossfade. At most two full-size bitmaps are alive.
 */
class AlbumDream : DreamService() {
    private val handler = Handler(Looper.getMainLooper())
    private var decoder: ExecutorService? = null
    private lateinit var slides: Array<Slide>
    private lateinit var emptyText: TextView
    private lateinit var prefs: Prefs
    private var front = 0
    @Volatile private var running = false
    private var picker: Picker? = null
    private lateinit var clock: TextClock

    /** What the decoder thread hands back to the UI thread. */
    private class Shown(val photo: Bitmap, val backdrop: Bitmap?, val crop: Boolean, val caption: Caption)
    private class Caption(val album: String, val date: String, val place: String)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        prefs = Prefs(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        slides = Array(2) { Slide(this).also { root.addView(it.root, FrameLayout.LayoutParams(-1, -1)) } }
        emptyText = TextView(this).apply {
            text = "No photos yet.\nOpen \"Album Screensaver\" from the home screen to add a shared album."
            setTextColor(Color.GRAY)
            textSize = 22f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyText, FrameLayout.LayoutParams(-1, -1))
        clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 34f
            setTextColor(Color.WHITE)
            setShadowLayer(10f, 0f, 2f, 0xAA000000.toInt())
            val pad = Slide.dp(this@AlbumDream, 40)
            setPadding(pad, Slide.dp(this@AlbumDream, 28), pad, 0)
        }
        root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        running = true
        clock.visibility = if (prefs.showClock) View.VISIBLE else View.GONE
        decoder = Executors.newSingleThreadExecutor()
        SyncJob.schedule(this)
        showNext()
    }

    override fun onDreamingStopped() {
        running = false
        handler.removeCallbacksAndMessages(null)
        decoder?.shutdownNow()
        decoder = null
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        slides.forEach { it.clear() }
        super.onDetachedFromWindow()
    }

    private fun showNext() {
        if (!running) return
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        decoder?.execute {
            val p = picker ?: Picker(this, prefs).also { picker = it }
            var shown: Shown? = null
            var empty = false
            for (attempt in 0 until 5) {
                val pick = p.next()
                if (pick == null) { empty = true; break }
                shown = load(pick, w, h) ?: continue
                break
            }
            handler.post {
                when {
                    !running -> shown?.let { it.photo.recycle(); it.backdrop?.recycle() }
                    shown != null -> { emptyText.visibility = View.GONE; crossfade(shown) }
                    empty -> { emptyText.visibility = View.VISIBLE; handler.postDelayed(::showNext, 60_000) }
                    else -> handler.postDelayed(::showNext, 2_000)
                }
            }
        }
    }

    private fun crossfade(s: Shown) {
        val fade = prefs.fadeMs.toLong()
        val out = slides[front]
        val incoming = slides[1 - front]
        incoming.show(s, prefs, prefs.slideSeconds * 1000L + 2 * fade)
        incoming.root.animate().alpha(1f).setDuration(fade).start()
        out.root.animate().alpha(0f).setDuration(fade).withEndAction { out.clear() }.start()
        front = 1 - front
        handler.postDelayed(::showNext, prefs.slideSeconds * 1000L)
    }

    private fun load(pick: Picker.Pick, screenW: Int, screenH: Int): Shown? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(pick.file.path, bounds)
        val bw = bounds.outWidth
        val bh = bounds.outHeight
        if (bw <= 0 || bh <= 0) null else {
            val crop = when (prefs.fitMode) {
                Prefs.FIT_CROP -> true
                Prefs.FIT_WHOLE -> false
                else -> bw.toFloat() / bh >= 1.3f // landscape fills the screen; portrait keeps its edges
            }
            // Decode straight to the size it will be drawn at, to keep memory low.
            val scale = if (crop) maxOf(screenW.toFloat() / bw, screenH.toFloat() / bh)
                        else minOf(screenW.toFloat() / bw, screenH.toFloat() / bh)
            val opts = BitmapFactory.Options()
            if (scale < 1f) {
                opts.inDensity = 10_000
                opts.inTargetDensity = (10_000 * scale).toInt()
                opts.inScaled = true
            }
            val photo = BitmapFactory.decodeFile(pick.file.path, opts)
            if (photo == null) null else Shown(photo, if (crop) null else backdrop(photo), crop, pick.caption)
        }
    } catch (e: Throwable) { null } // deleted mid-sync, corrupt, or OOM: skip it

    /** A tiny, dimmed copy of the photo; scaled up to full screen it reads as a soft blur. */
    private fun backdrop(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(src, maxOf(1, src.width / 8), maxOf(1, src.height / 8), true)
        val tiny = Bitmap.createScaledBitmap(small, maxOf(1, src.width / 40), maxOf(1, src.height / 40), true)
        small.recycle()
        return tiny
    }

    /** One full-screen layer: blurred backdrop, the photo, and a caption over a bottom scrim. */
    private class Slide(ctx: Context) {
        val root = FrameLayout(ctx).apply { alpha = 0f }
        private val back = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.45f, 0.45f, 0.45f, 1f) })
        }
        private val photo = ImageView(ctx)
        private val caption = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 48)
            setPadding(pad, 0, pad, dp(ctx, 40))
        }
        private val albumText = text(ctx, 15f, 0xB3FFFFFF.toInt()).apply { letterSpacing = 0.12f; isAllCaps = true }
        private val dateText = text(ctx, 30f, Color.WHITE)
        private val placeText = text(ctx, 20f, 0xE6FFFFFF.toInt())

        init {
            root.addView(back, FrameLayout.LayoutParams(-1, -1))
            root.addView(photo, FrameLayout.LayoutParams(-1, -1))
            val scrim = View(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xB0000000.toInt(), 0x00000000))
            }
            root.addView(scrim, FrameLayout.LayoutParams(-1, dp(ctx, 220), Gravity.BOTTOM))
            caption.addView(albumText)
            caption.addView(dateText)
            caption.addView(placeText)
            root.addView(caption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        }

        fun show(s: Shown, prefs: Prefs, durationMs: Long) {
            photo.scaleType = if (s.crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            photo.setImageBitmap(s.photo)
            if (prefs.motion) {
                // Slow zoom with a slight drift in a random direction, over the photo's whole time on screen.
                val drift = root.resources.displayMetrics.widthPixels * 0.025f
                photo.animate().scaleX(1.08f).scaleY(1.08f)
                    .translationX(drift * (Math.random().toFloat() * 2 - 1))
                    .translationY(drift * 0.5f * (Math.random().toFloat() * 2 - 1))
                    .setInterpolator(LinearInterpolator()).setDuration(durationMs).start()
            }
            back.setImageBitmap(s.backdrop)
            set(albumText, prefs.showAlbum, s.caption.album)
            set(dateText, prefs.showDate, s.caption.date)
            set(placeText, prefs.showLocation, s.caption.place.let { if (it.isEmpty()) it else "⌖  $it" })
        }

        fun clear() {
            root.animate().cancel()
            photo.animate().cancel()
            photo.scaleX = 1f; photo.scaleY = 1f
            photo.translationX = 0f; photo.translationY = 0f
            for (v in arrayOf(photo, back)) {
                val bmp = (v.drawable as? BitmapDrawable)?.bitmap
                v.setImageDrawable(null)
                bmp?.recycle()
            }
        }

        private fun set(v: TextView, enabled: Boolean, value: String) {
            v.text = value
            v.visibility = if (enabled && value.isNotEmpty()) View.VISIBLE else View.GONE
        }

        companion object {
            fun dp(ctx: Context, v: Int) =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

            fun text(ctx: Context, size: Float, color: Int) = TextView(ctx).apply {
                textSize = size
                setTextColor(color)
                setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
            }
        }
    }

    /**
     * Chooses photos from the enabled albums. "Even" mode picks an album first, so a small album
     * isn't drowned out by a big one; otherwise every photo has the same chance. Shuffle bags mean
     * nothing repeats until everything has been shown, and refills pick up newly synced photos.
     */
    private class Picker(private val ctx: Context, private val prefs: Prefs) {
        class Pick(val file: File, val caption: Caption)

        private var meta = JSONObject()
        private val bags = HashMap<String, ArrayDeque<String>>() // album url ("" = all) -> photo ids
        private var last: String? = null

        fun next(): Pick? {
            val albums = prefs.enabledAlbums
            if (albums.isEmpty()) return null
            val key = if (prefs.mixEven) albums.random() else ""
            val scope = if (key.isEmpty()) albums else listOf(key)
            val bag = bags.getOrPut(key) { ArrayDeque() }
            repeat(50) { // skip photos deleted by a sync since the bag was filled
                if (bag.isEmpty()) refill(bag, scope)
                val id = bag.removeFirstOrNull() ?: return null
                val file = File(SyncJob.photoDir(ctx), "$id.jpg")
                if (file.exists()) {
                    last = id
                    return Pick(file, caption(id, scope))
                }
            }
            return null
        }

        private fun refill(bag: ArrayDeque<String>, albums: List<String>) {
            meta = SyncJob.readMeta(ctx)
            val onDisk = SyncJob.photoDir(ctx).list()?.map { it.removeSuffix(".jpg") }?.toHashSet() ?: return
            val ids = meta.keys().asSequence().filter { id ->
                id in onDisk && meta.getJSONObject(id).optJSONArray("u")?.let { u ->
                    (0 until u.length()).any { u.getString(it) in albums }
                } == true
            }.toMutableList()
            val ordered = if (prefs.smartPick) weightedOrder(ids) else ids.shuffled()
            val list = ordered.toMutableList()
            if (list.size > 1 && list[0] == last) list.add(list.removeAt(0))
            bag.addAll(list)
        }

        /**
         * Weighted shuffle (Efraimidis-Spirakis): higher-weighted photos tend to come earlier in each
         * round, but everything except hidden duplicates still comes up before the round repeats.
         */
        private fun weightedOrder(ids: List<String>): List<String> {
            val sharp = ids.mapNotNull { meta.getJSONObject(it).takeIf { m -> m.has("sh") }?.getDouble("sh") }.sorted()
            val cutoff = if (sharp.size < 20) 0.0 else sharp[sharp.size * 15 / 100]
            val today = java.util.Calendar.getInstance()
            return ids.mapNotNull { id ->
                val w = PhotoAnalyzer.weight(meta.getJSONObject(id), cutoff, today)
                if (w <= 0) null else id to Math.pow(Math.random(), 1.0 / w)
            }.sortedByDescending { it.second }.map { it.first }
        }

        private fun caption(id: String, albums: List<String>): Caption {
            val m = meta.optJSONObject(id) ?: return Caption("", "", "")
            val u = m.optJSONArray("u")
            val url = (0 until (u?.length() ?: 0)).map { u!!.getString(it) }.firstOrNull { it in albums }
            val t = m.optLong("t")
            val date = if (t <= 0) "" else {
                val fmt = DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEdMMMMyyyy")
                java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(t + m.optLong("tz")))
            }
            val place = m.optString("loc").ifEmpty { m.optString("loc2") }
            return Caption(url?.let { prefs.albumName(it) } ?: "", date, place)
        }
    }
}
