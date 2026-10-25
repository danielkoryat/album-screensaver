package com.dk.albumdream

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * TV settings: albums on the right with slideshow and caption options; live status on the left.
 * D-pad left/right changes a value, OK cycles it or opens actions.
 *
 * Links can also be added from a phone (see [LinkServer]) or from a PC:
 *   adb shell am start -n com.dk.albumdream/.SettingsActivity --es url https://photos.app.goo.gl/...
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var statPhotos: TextView
    private lateinit var statLine: TextView
    private lateinit var phoneHint: TextView
    private lateinit var heroImage: ImageView
    private var listSignature = ""
    private val handler = Handler(Looper.getMainLooper())
    private val server = LinkServer { link -> addFromServer(link) }
    private val ticker = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        SyncJob.schedule(this)

        val root = LinearLayout(this).apply { setBackgroundColor(BG) }
        root.addView(buildStatusPane(), LinearLayout.LayoutParams(0, MATCH_PARENT, 0.38f))
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(28), dp(40), dp(28))
        }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) },
            LinearLayout.LayoutParams(0, MATCH_PARENT, 0.62f))
        setContentView(root)

        rebuildList()
        loadHeroImage()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val ip = LinkServer.lanAddress()
        phoneHint.text = if (server.start() && ip != null) "http://$ip:${LinkServer.PORT}" else "Not available on this network"
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        server.stop()
        super.onPause()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("url")?.let { addAlbum(it) }
    }

    // ---- Left pane: hero photo and live status -------------------------------------------------

    private fun buildStatusPane(): View {
        val pane = FrameLayout(this)
        heroImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.55f, 0.55f, 0.55f, 1f) })
        }
        pane.addView(heroImage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        pane.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x66000000, BG))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(40), dp(24), dp(36))
        }
        col.addView(label("Album Screensaver", 26f, Color.WHITE, bold = true))
        col.addView(label("Your Google Photos albums, shuffled together on the TV", 13f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        statPhotos = label("0", 54f, Color.WHITE, bold = true)
        col.addView(statPhotos)
        statLine = label("", 14f, MUTED).apply { setLineSpacing(0f, 1.25f) }
        col.addView(statLine)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x33FFFFFF) }
        }
        card.addView(label("Add albums from your phone", 14f, Color.WHITE, bold = true))
        card.addView(label("Copy the album's share link, open this address on your phone and paste it:", 12f, MUTED)
            .apply { setPadding(0, dp(4), 0, dp(6)) })
        phoneHint = label("", 17f, ACCENT, bold = true)
        card.addView(phoneHint)
        col.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        pane.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return pane
    }

    private fun refreshStatus() {
        val count = SyncJob.photoDir(this).list()?.count { it.endsWith(".jpg") } ?: 0
        statPhotos.text = count.toString()
        val albums = prefs.albums.size
        val shown = prefs.enabledAlbums.size
        val last = prefs.lastSync.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it).toString() }
        statLine.text = buildString {
            append("photos on this TV\n")
            append(if (albums == shown) "$albums album${if (albums == 1) "" else "s"}" else "$shown of $albums albums shown")
            append("\n")
            append(when {
                prefs.syncing -> "Syncing now…"
                last != null -> "Synced $last · ${prefs.lastSyncResult}"
                albums > 0 -> "Waiting for first sync…"
                else -> "Add an album to get started"
            })
        }
        if (heroImage.drawable == null && count > 0) loadHeroImage()
        // Album rows show sync results; rebuild only when they change so focus isn't disturbed.
        if (signature() != listSignature) rebuildList()
    }

    private fun loadHeroImage() {
        val f = SyncJob.photoDir(this).listFiles { x -> x.name.endsWith(".jpg") }?.randomOrNull() ?: return
        Thread {
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return@Thread
            val soft = Bitmap.createScaledBitmap(bmp, maxOf(1, bmp.width / 6), maxOf(1, bmp.height / 6), true)
            bmp.recycle()
            runOnUiThread { heroImage.setImageBitmap(soft) }
        }.start()
    }

    // ---- Right pane: settings list -------------------------------------------------------------

    private fun signature() = prefs.albums.toString() + prefs.disabled + prefs.albumStatus

    private fun rebuildList() {
        val focusedKey = list.findFocus()?.tag as? String
        listSignature = signature()
        list.removeAllViews()

        section("Albums")
        val status = prefs.albumStatus
        for (url in prefs.albums) {
            val s = status.optJSONObject(url)
            val on = url !in prefs.disabled
            val sub = when {
                s == null -> "Syncing…"
                s.has("error") -> "Can't reach album: ${s.optString("error")}"
                else -> "${s.optInt("count")} photos" + (if (s.optInt("videos") > 0) " · ${s.optInt("videos")} videos skipped" else "")
            }
            val toggle = {
                prefs.disabled = if (on) prefs.disabled + url else prefs.disabled - url
                rebuildList()
            }
            row("album:$url", s?.optString("name")?.takeIf { it.isNotEmpty() } ?: url, sub,
                value = if (on) "Showing" else "Hidden", valueOn = on,
                onLeft = toggle, onRight = toggle) { albumActions(url, on) }
        }
        row("add", "+  Add album", "Paste a shared-album link, or use your phone (see left)") { addDialog() }

        section("Slideshow")
        choice("slide", "Time per photo", "How long each photo stays on screen",
            SLIDE_SECONDS.map(::formatSeconds), SLIDE_SECONDS.indexOf(prefs.slideSeconds).coerceAtLeast(0)) {
            prefs.slideSeconds = SLIDE_SECONDS[it]
        }
        choice("fade", "Transition", "Crossfade between photos",
            FADES.map { it.second }, FADES.indexOfFirst { it.first == prefs.fadeMs }.coerceAtLeast(0)) {
            prefs.fadeMs = FADES[it].first
        }
        toggle("motion", "Motion", "Slow zoom and drift on each photo", prefs.motion) { prefs.motion = it }
        choice("fit", "Photo fit", FIT_HELP[prefs.fitMode], FIT_NAMES, prefs.fitMode) { prefs.fitMode = it }
        choice("mix", "Album mix",
            if (prefs.mixEven) "Every album gets the same screen time" else "Every photo has the same chance",
            listOf("By photo", "Equal per album"), if (prefs.mixEven) 1 else 0) { prefs.mixEven = it == 1 }
        toggle("smart", "Smart selection",
            "Shows people and sharp photos more often, plus “on this day” memories; hides near-duplicate bursts",
            prefs.smartPick) { prefs.smartPick = it }

        section("On screen")
        toggle("clock", "Clock", "Time in the top corner", prefs.showClock) { prefs.showClock = it }
        toggle("date", "Date", "When the photo was taken", prefs.showDate) { prefs.showDate = it }
        toggle("loc", "Location",
            "From the photo's GPS; photos without it use the place of the closest photo in time", prefs.showLocation) { prefs.showLocation = it }
        toggle("album", "Album name", null, prefs.showAlbum) { prefs.showAlbum = it }

        section("Screensaver")
        row("preview", "Preview now", "Start the screensaver (press any button to exit)") {
            try {
                startActivity(Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
            } catch (e: Exception) { toast("Preview isn't available on this TV") }
        }
        row("sync", "Sync albums now", "Also re-checks photo locations. Albums sync automatically every hour") {
            SyncJob.runNow(this, recheckPlaces = true)
            toast("Syncing in the background")
        }
        row("system", "TV screensaver settings", if (isActiveDream()) "Album Screensaver is the active screensaver"
            else "Choose Album Screensaver here to turn it on") {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        }

        val restore = focusedKey?.let { list.findViewWithTag<View>(it) } ?: list.findViewWithTag("add")
        restore?.requestFocus()
    }

    private fun isActiveDream() = try {
        Settings.Secure.getString(contentResolver, "screensaver_components")?.startsWith(packageName) == true
    } catch (e: Exception) { false }

    private fun albumActions(url: String, on: Boolean) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(prefs.albumName(url))
            .setItems(arrayOf(if (on) "Hide from slideshow" else "Show in slideshow", "Remove album", "Cancel")) { _, which ->
                when (which) {
                    0 -> { prefs.disabled = if (on) prefs.disabled + url else prefs.disabled - url; rebuildList() }
                    1 -> confirmRemove(url)
                }
            }.show()
    }

    private fun confirmRemove(url: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Remove “${prefs.albumName(url)}”?")
            .setMessage("Its photos will be deleted from the TV. The album in Google Photos is not affected.")
            .setPositiveButton("Remove") { _, _ ->
                prefs.removeAlbum(url)
                SyncJob.runNow(this)
                rebuildList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addDialog() {
        val input = EditText(this).apply {
            hint = "https://photos.app.goo.gl/…"
            setSingleLine()
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add a shared album")
            .setMessage("Type or paste the album's share link.\nEasier: on your phone open ${phoneHint.text}")
            .setView(box)
            .setPositiveButton("Add") { _, _ -> addAlbum(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addAlbum(text: String): Boolean {
        val link = Prefs.findLink(text)
        return when {
            link == null -> { toast("That isn't a Google Photos shared-album link"); false }
            !prefs.addAlbum(link) -> { toast("That album is already added"); false }
            else -> { SyncJob.runNow(this); toast("Album added, downloading photos…"); rebuildList(); true }
        }
    }

    /** Called on the LinkServer thread. */
    private fun addFromServer(link: String): String {
        if (!prefs.addAlbum(link)) return "That album is already on the TV."
        SyncJob.runNow(this)
        runOnUiThread { toast("Album added from your phone"); rebuildList() }
        return "Added! Photos will appear on the TV in a few minutes."
    }

    // ---- Row builders ---------------------------------------------------------------------------

    private fun section(title: String) {
        list.addView(label(title.uppercase(), 12f, ACCENT, bold = true).apply {
            letterSpacing = 0.14f
            setPadding(dp(16), dp(if (list.childCount == 0) 4 else 22), 0, dp(6))
        })
    }

    private fun choice(key: String, title: String, sub: String?, options: List<String>, index: Int, set: (Int) -> Unit) {
        val change = { d: Int -> set((index + d + options.size) % options.size); rebuildList() }
        row(key, title, sub, value = options[index], arrows = true,
            onLeft = { change(-1) }, onRight = { change(1) }) { change(1) }
    }

    private fun toggle(key: String, title: String, sub: String?, on: Boolean, set: (Boolean) -> Unit) {
        val flip = { set(!on); rebuildList() }
        row(key, title, sub, value = if (on) "On" else "Off", valueOn = on, onLeft = flip, onRight = flip) { flip() }
    }

    private fun row(
        key: String, title: String, sub: String?, value: String? = null, valueOn: Boolean = true,
        arrows: Boolean = false, onLeft: (() -> Unit)? = null, onRight: (() -> Unit)? = null, onClick: () -> Unit,
    ) {
        val r = LinearLayout(this).apply {
            tag = key
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(56)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(FOCUS_BG))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f).setDuration(120).start() }
            setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> onLeft?.let { it(); true } ?: true
                    KeyEvent.KEYCODE_DPAD_RIGHT -> onRight?.let { it(); true } ?: true
                    else -> false
                }
            }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        texts.addView(label(title, 16f, 0).apply { setTextColor(states(Color.WHITE, FOCUS_TEXT)); isDuplicateParentStateEnabled = true })
        if (!sub.isNullOrEmpty()) texts.addView(label(sub, 12f, 0).apply {
            setTextColor(states(MUTED, 0xFF5F6368.toInt())); isDuplicateParentStateEnabled = true; maxLines = 2
        })
        r.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (value != null) {
            r.addView(label(if (arrows) "‹  $value  ›" else value, 15f, 0, bold = true).apply {
                setTextColor(states(if (valueOn) ACCENT else MUTED, if (valueOn) 0xFF1A73E8.toInt() else 0xFF5F6368.toInt()))
                isDuplicateParentStateEnabled = true
                setPadding(dp(16), 0, 0, 0)
            })
        }
        list.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun states(normal: Int, focused: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(focused, normal))

    private fun rounded(color: Int) = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(color) }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private val BG = Color.rgb(16, 17, 20)
        private val MUTED = 0xFF9AA0A6.toInt()
        private val ACCENT = 0xFF8AB4F8.toInt()
        private val FOCUS_BG = 0xFFE8EAED.toInt()
        private val FOCUS_TEXT = 0xFF202124.toInt()

        private val SLIDE_SECONDS = listOf(5, 10, 15, 20, 30, 45, 60, 120, 300, 600)
        private val FADES = listOf(0 to "Cut", 500 to "Quick", 1500 to "Smooth", 3000 to "Slow", 5000 to "Very slow")
        private val FIT_NAMES = listOf("Smart", "Fill screen", "Whole photo")
        private val FIT_HELP = listOf(
            "Landscape photos fill the screen; portrait ones show whole on a blurred backdrop",
            "Every photo fills the screen; edges may be cropped",
            "Never crop; empty space gets a blurred backdrop",
        )

        private fun formatSeconds(s: Int) = if (s < 60) "$s s" else "${s / 60} min"
    }
}
