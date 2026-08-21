package com.nieche.emu

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var screen: ScreenView
    private lateinit var status: TextView
    private lateinit var py: PyObject

    private var thread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    @Volatile private var keyMask = 0

    private val keyLock = Any()
    private var keyLatch = 0
    private val touchLock = Any()

    private val touchQueue = ArrayDeque<Triple<Int, Int, String>>()
    private val softQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private var bmp: Bitmap? = null
    private var held = mutableSetOf<String>()
    private var fps = 30

    private val pickFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) importAndRun(uri) }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        py = Python.getInstance().getModule("nieche_bridge")

        setContentView(buildUi())
        fitLayout()

        val msg = py.callAttr("init",
            applicationInfo.nativeLibraryDir,
            File(filesDir, "nieche").absolutePath).toString()
        status.text = msg
        android.util.Log.i("nieche", "init: " + msg)

        intent?.getStringExtra("autorun")?.let { path -> startEmu(File(path)) }

        intent?.getStringExtra("selftest")?.let { path ->
            Thread {
                val r = py.callAttr("selftest", path, 40).toString()
                android.util.Log.i("nieche", "selftest: " + r)
            }.start()
        }
    }

    private fun buildUi(): View {
        rootBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101014.toInt())
        }
        val root = rootBox

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 4)
        }
        bar.addView(Button(this).apply {
            text = "打开 .cbe"
            setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        })
        bar.addView(Button(this).apply {
            text = "游戏库"
            setOnClickListener { showLibrary() }
        })
        bar.addView(Button(this).apply {
            text = "停止"
            setOnClickListener { stopEmu() }
        })
        bar.addView(Button(this).apply {
            text = "复位缩放"
            setOnClickListener { screen.resetZoom() }
        })
        topBar = bar
        root.addView(bar)

        status = TextView(this).apply {
            setPadding(16, 0, 16, 4)
            textSize = 11f
            setTextColor(0xFF9AA0A6.toInt())
        }
        root.addView(status)

        screen = ScreenView(this)
        screen.onTouchGuest = { x, y, st ->
            synchronized(touchLock) {

                if (st == "move" && touchQueue.lastOrNull()?.third == "move") {
                    touchQueue.removeLast()
                }
                if (touchQueue.size < 64) touchQueue.addLast(Triple(x, y, st))
            }
        }
        screen.onZoom = { z ->
            ui.post { status.text = "缩放 %.1fx（双指捏合缩放/平移，单指是游戏触摸）".format(z) }
        }
        root.addView(screen, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 4f))

        root.addView(buildKeypad())
        return root
    }

    private fun keyButton(id: String, weight: Float = 1f): Button =
        Button(this).apply {
            text = Keys.label[id] ?: id
            textSize = 13f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight).apply { setMargins(2, 2, 2, 2) }

            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        held.add(id); pushKeys(); v.isPressed = true

                        when (id) {
                            "lsk" -> softQueue.add("left")
                            "rsk" -> softQueue.add("right")
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> { held.remove(id); pushKeys(); v.isPressed = false }
                }
                true
            }
        }

    private val rowH by lazy { (32 * resources.displayMetrics.density).toInt() }
    private val padRows = mutableListOf<LinearLayout>()
    private lateinit var pad: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var rootBox: LinearLayout

    private fun row(vararg ids: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowH)
            ids.forEach { id ->
                if (id.isEmpty()) {
                    addView(View(this@MainActivity),
                        LinearLayout.LayoutParams(0, 1, 1f))
                } else {
                    addView(keyButton(id))
                }
            }
        }

    private fun buildKeypad(): View {
        pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 2, 6, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        listOf(
            arrayOf("lsk", "rsk"),
            arrayOf("call", "up", "end"),
            arrayOf("left", "ok", "right"),
            arrayOf("", "down", ""),
            arrayOf("k1", "k2", "k3"),
            arrayOf("k4", "k5", "k6"),
            arrayOf("k7", "k8", "k9"),
            arrayOf("star", "k0", "pound"),
        ).forEach { ids ->
            val r = row(*ids)
            padRows.add(r)
            pad.addView(r)
        }
        return pad
    }

    private val navBarH: Int by lazy {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun fitLayout() {
        rootBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val vis = android.graphics.Rect()
            rootBox.getWindowVisibleDisplayFrame(vis)
            val padH = rowH * padRows.size + pad.paddingTop + pad.paddingBottom
            val h = vis.height() - navBarH - rootBox.top - topBar.height - status.height - padH
            if (h <= 0) return@addOnLayoutChangeListener
            val lp = screen.layoutParams as LinearLayout.LayoutParams
            if (lp.height != h || lp.weight != 0f) {
                lp.height = h
                lp.weight = 0f
                screen.layoutParams = lp
            }
        }
    }

    private fun pushKeys() {
        val m = held.fold(0) { a, id -> a or (Keys.mask[id] ?: 0) }
        synchronized(keyLock) {
            keyLatch = keyLatch or (m and keyMask.inv())
            keyMask = m
        }
    }

    private fun gamesDir() = File(filesDir, "games").apply { mkdirs() }

    private fun importAndRun(uri: Uri) {
        val name = queryName(uri) ?: "game.cbe"
        val dst = File(gamesDir(), name)

        contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
        startEmu(dst)
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun showLibrary() {
        val files = gamesDir().listFiles { f -> f.name.lowercase().endsWith(".cbe") }
            ?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("游戏库是空的")
                .setMessage("模拟器不自带游戏。用「打开 .cbe」从手机存储里选一个，" +
                            "选过的会留在库里。")
                .setPositiveButton("知道了", null).show()
            return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("游戏库")
            .setItems(names) { _, i -> startEmu(files[i]) }
            .setNegativeButton("取消", null).show()
    }

    private fun startEmu(file: File) {
        stopEmu()
        running = true
        held.clear()
        keyMask = 0
        synchronized(touchLock) { touchQueue.clear() }
        synchronized(keyLock) { keyLatch = 0 }
        val t = Thread {
            try {
                val size = py.callAttr("start", file.absolutePath, false).toString()
                val (w, h) = size.split(",").map { it.trim().toInt() }
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                ui.post {
                    bmp = b
                    status.text = "${file.name}  ${w}x${h}"
                    title = file.nameWithoutExtension
                }
                loop(b)
            } catch (e: Throwable) {
                ui.post { status.text = "启动失败：${e.message}" }
            } finally {

                try { py.callAttr("stop") } catch (_: Throwable) {}
            }
        }
        thread = t
        t.start()
    }

    private fun loop(b: Bitmap) {
        val period = 1000L / fps
        var lastMask = -1
        while (running) {
            val t0 = System.currentTimeMillis()

            val m = synchronized(keyLock) {
                val v = keyMask or keyLatch
                keyLatch = 0
                v
            }
            py.callAttr("set_keys", m)
            if (m != lastMask) {
                android.util.Log.i("nieche", "keys=0x%08x".format(m))
                lastMask = m
            }

            while (true) {
                val t = synchronized(touchLock) { touchQueue.removeFirstOrNull() } ?: break
                py.callAttr("set_touch", t.first, t.second, t.third)
            }
            while (true) {
                val side = softQueue.poll() ?: break
                py.callAttr("soft_key", side, true)
            }
            val px = py.callAttr("step").toJava(ByteArray::class.java)
            if (px.isNotEmpty()) {

                b.copyPixelsFromBuffer(ByteBuffer.wrap(px))
                ui.post { screen.setFrame(b) }
            }
            val ev = py.callAttr("events").toString()
            if (ev.contains("\"exit\"")) {
                ui.post { stopEmu() }
                return
            }
            val dt = System.currentTimeMillis() - t0
            if (dt < period) try { Thread.sleep(period - dt) } catch (_: InterruptedException) {}
        }
    }

    private fun stopEmu() {
        if (!running && thread == null) return
        running = false

        thread?.join(3000)
        thread = null
        bmp = null
        held.clear()
        keyMask = 0
        screen.clear()
        status.text = "已停止"
    }

    override fun onDestroy() {
        stopEmu()
        super.onDestroy()
    }
}
