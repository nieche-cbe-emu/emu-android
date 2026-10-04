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
import org.json.JSONArray

/**
 * 竖屏布局：上面是功能键和画面，下面是虚拟摇杆。触屏是一等公民——
 * 尼彩原机就是触屏机，软键和对话框按钮很多**只吃触摸**，按键位无效。
 *
 * v2 布局：取消了八行数字键盘，改成摇杆 + OK + 上部功能键的形式，
 * 占用空间更小，画面显示更大，并支持收起/展开按键区。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var screen: ScreenView
    private lateinit var status: TextView
    private lateinit var py: PyObject

    /** 模拟跑在自己的线程上，绝不能占主线程 */
    private var thread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    /**
     * 退到后台时暂停。安卓不会让后台进程一直跑——继续按帧推进只是白烧电，
     * 而且系统随时可能直接把进程杀掉，那时 AppStop 不会执行，**存档就没了**。
     * 所以后台先停帧（保住会话），超过 [IDLE_STOP_MS] 还没回来就正经收工，
     * 让模块把存档落盘。
     */
    @Volatile private var paused = false
    private val idleStop = Runnable {
        if (running) {
            stopEmu()
            status.text = "已保存并停止"
        }
    }
    /**
     * 输入状态。**不能用 Handler.post 把输入送进模拟线程**——那个线程一直卡在
     * 自己的 while 循环里，永远不回到 Looper，post 进去的消息一条都不会执行
     * （按键和触摸全部石沉大海，连收工时的 AppStop 也一样，存档因此从没落过盘）。
     * 改成这边只写状态、循环里每帧读一次，和桌面版引擎的做法一致。
     */
    @Volatile private var keyMask = 0
    /**
     * 短按锁存。一次点击的按下和抬起可能落在同一帧之内（33ms），循环读到时
     * mask 已经归零，这一下就整帧丢掉了——屏幕键盘点了没反应就是这么来的。
     * 按下的位先攒进这里，循环取走后再清。
     */
    private val keyLock = Any()
    private var keyLatch = 0
    private val touchLock = Any()
    /**
     * 触摸事件**队列**，不是单个槽位。原先只留最后一次，一次快速点击的
     * down 会在同一帧内被随后的 up 覆盖掉，游戏根本收不到按下——
     * 表现就是"触屏不灵敏、点了没反应"。按下/移动/抬起必须一个不漏地按序送达。
     */
    private val touchQueue = ArrayDeque<Triple<Int, Int, String>>()
    /** 待下发的软键（两段式，核心自己先戳屏幕角落，几帧后才补按键位） */
    private val softQueue = ArrayDeque<String>()

    private val audio = AudioOut()
    private var bmp: Bitmap? = null
    private var held = mutableSetOf<String>()
    /// 目标帧率。**这不只是流畅度，它直接决定游戏快慢**——
    /// 模块的动画和计时都是按帧推进的（每帧虚拟时钟走 frame_ms），
    /// 跑得越快游戏就越快。真机上 MSW8533 跑这些游戏大概只有 10-15fps，
    /// Rust 核心轻松跑满，不压着就会快得没法玩。
    /// **必须 @Volatile**：循环在别的线程上跑，改了要立刻生效。
    @Volatile private var fps = 20
    /// 状态栏里不随帧率刷新的那部分（模块名和尺寸）
    private var statusBase = ""

    private val pickFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) importAndRun(uri) }

    /// 和引擎侧一致的夹取范围。下限 1 是给逐帧看画面用的。
    private val FPS_MIN = 1
    private val FPS_MAX = 240
    private lateinit var fpsButton: Button
    private lateinit var topBar: LinearLayout
    private lateinit var rootBox: LinearLayout

    // ---- v2 布局：摇杆 + 功能键 ----
        private lateinit var controlsArea: View
        private lateinit var joystick: JoystickView
    private var controlsVisible = true
    private lateinit var controlsPanel: LinearLayout
    private lateinit var pullBar: TextView

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        py = Python.getInstance().getModule("nieche_bridge")

        setContentView(buildUi())
        // 不再需要 fitLayout()——画面用 weight=1 自动填充剩余空间

        // 先把两个原生库的位置告诉 Python，再做任何模拟相关的事
        val msg = py.callAttr("init",
            applicationInfo.nativeLibraryDir,
            File(filesDir, "nieche").absolutePath).toString()
        // 版本号放在最前面：用户反馈问题时一眼能看出装的是哪一版
        val ver = packageManager.getPackageInfo(packageName, 0).versionName
        status.text = "v$ver  $msg"
        android.util.Log.i("nieche", "init: v$ver " + msg)

        // 自检入口：adb shell am start -n com.nieche.emu/.MainActivity --es selftest <路径>
        // ATD 模拟器不渲染界面，截图看不出核心跑没跑，只能靠这条路子分辨。
        intent?.getStringExtra("autorun")?.let { path -> startEmu(File(path)) }

        intent?.getStringExtra("selftest")?.let { path ->
            Thread {
                val r = py.callAttr("selftest", path, 40).toString()
                android.util.Log.i("nieche", "selftest: " + r)
            }.start()
        }
    }

    // ------------------------------------------------------------------ 界面
    private fun buildUi(): View {
        rootBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101014.toInt())
        }
        val root = rootBox

        // 顶部：一行操作
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
        val prefs = getSharedPreferences("nieche", MODE_PRIVATE)
        audio.enabled = prefs.getBoolean("sound", true)
        bar.addView(Button(this).apply {
            text = if (audio.enabled) "声音 开" else "声音 关"
            setOnClickListener {
                audio.enabled = !audio.enabled
                text = if (audio.enabled) "声音 开" else "声音 关"
                prefs.edit().putBoolean("sound", audio.enabled).apply()
            }
        })
        fps = prefs.getInt("fps", fps)
        fpsButton = Button(this).apply {
            text = "${fps}fps"
            setOnClickListener { askFps() }
        }
        bar.addView(fpsButton)
        topBar = bar
        // **要能横向滚**：按钮总宽已经超过窄屏的宽度，直接放会把最左边那个
        // 挤出屏幕（加了帧率按钮之后「打开 .cbe」就点不到了）。
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        })

        status = TextView(this).apply {
            setPadding(16, 0, 16, 4)
            textSize = 11f
            setTextColor(0xFF9AA0A6.toInt())
        }
        root.addView(status)

        // 中间：画面，占满剩余空间（weight=1 自动填充，不再需要手动算高度）
        screen = ScreenView(this)
        screen.onTouchGuest = { x, y, st ->
            synchronized(touchLock) {
                // move 事件可以合并（只有最后的位置有意义），
                // down 和 up 是边沿，一个都不能丢。
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
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部：摇杆 + OK 按钮（取代原来的八行数字键盘）
        controlsArea = buildControls()
        root.addView(controlsArea)
        return root
    }


    private fun basicKeyButton(id: String): Button =
        Button(this).apply {
            text = Keys.label[id] ?: id
            textSize = 13f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minHeight = 0
            minimumHeight = 0
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        held.add(id); pushKeys(); v.isPressed = true
                        if (id == "rsk") synchronized(touchLock) { softQueue.add("right") }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> { held.remove(id); pushKeys(); v.isPressed = false }
                }
                true
            }
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
            // 按下就按住、抬起才放——功能机上长按方向键是要连续走的。
            // 自动重复交给 Python 那一层（先停顿再补），这里只报状态。
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        held.add(id); pushKeys(); v.isPressed = true
                        // 右软键走两段式，和 macOS、Windows 两端一致
                        if (id == "rsk") synchronized(touchLock) { softQueue.add("right") }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> { held.remove(id); pushKeys(); v.isPressed = false }
                }
                true
            }
        }


    /**
     * 底部操控区：带下拉条的垂直容器，内部包含摇杆和功能键（环绕OK分布）
     */
    private fun buildControls(): View {
        val dp = resources.displayMetrics.density
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(0xFF101014.toInt())
        }
        
        pullBar = TextView(this).apply {
            text = "▼  收起按键  ▼"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { toggleControls() }
        }
        wrapper.addView(pullBar)

        val h = (180 * dp).toInt()
        controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), (12 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        }

        // 摇杆（左侧）
        joystick = JoystickView(this)
        joystick.onDirection = { dirs ->
            held.removeAll(setOf("up", "down", "left", "right"))
            held.addAll(dirs)
            pushKeys()
        }
        val joySize = h - (12 * dp).toInt()
        controlsPanel.addView(joystick, LinearLayout.LayoutParams(joySize, joySize))

        // 弹性间距
        controlsPanel.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // 功能键区（右侧：OK 在中间，四个功能键在四角）
        val rightPanel = android.widget.RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(joySize, joySize)
        }
        
        val okSize = (joySize * 0.46).toInt()
        val funcSize = (joySize * 0.27).toInt()
        val margin = (4 * dp).toInt()
        
        val okBtn = basicKeyButton("ok").apply { textSize = 20f }
        val okLp = android.widget.RelativeLayout.LayoutParams(okSize, okSize).apply {
            addRule(android.widget.RelativeLayout.CENTER_IN_PARENT)
        }
        rightPanel.addView(okBtn, okLp)

        val lskBtn = basicKeyButton("lsk")
        val lskLp = android.widget.RelativeLayout.LayoutParams(funcSize, funcSize).apply {
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT)
        }
        rightPanel.addView(lskBtn, lskLp)
        
        val rskBtn = basicKeyButton("rsk")
        val rskLp = android.widget.RelativeLayout.LayoutParams(funcSize, funcSize).apply {
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT)
        }
        rightPanel.addView(rskBtn, rskLp)
        
        val callBtn = basicKeyButton("call")
        val callLp = android.widget.RelativeLayout.LayoutParams(funcSize, funcSize).apply {
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT)
        }
        rightPanel.addView(callBtn, callLp)
        
        val endBtn = basicKeyButton("end")
        val endLp = android.widget.RelativeLayout.LayoutParams(funcSize, funcSize).apply {
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT)
        }
        rightPanel.addView(endBtn, endLp)

        controlsPanel.addView(rightPanel)
        
        wrapper.addView(controlsPanel)
        return wrapper
    }

    /** 收起 / 展开底部控件，最大化画面显示面积 */
    private fun toggleControls() {
        controlsVisible = !controlsVisible
        controlsPanel.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        pullBar.text = if (controlsVisible) "▼  收起按键  ▼" else "▲  展开按键  ▲"
    }

    /// 让用户直接输入任意帧率，不限在几档预设里。
    private fun askFps() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(fps.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("帧率")
            .setMessage("这就是游戏速度：模块按帧推进，跑多快游戏就多快。\n" +
                        "真机上这些游戏大概只有 10-15fps。范围 $FPS_MIN-$FPS_MAX。")
            .setView(input)
            .setPositiveButton("好") { _, _ ->
                // 输入框里可以敲出任何数（也可能是空的），这里夹一下再生效
                val v = input.text.toString().toIntOrNull() ?: fps
                setFps(v.coerceIn(FPS_MIN, FPS_MAX))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setFps(v: Int) {
        fps = v
        fpsButton.text = "${fps}fps"
        getSharedPreferences("nieche", MODE_PRIVATE).edit().putInt("fps", fps).apply()
    }

    private fun pushKeys() {
        val m = held.fold(0) { a, id -> a or (Keys.mask[id] ?: 0) }
        synchronized(keyLock) {
            keyLatch = keyLatch or (m and keyMask.inv())   // 这一下新按下的位
            keyMask = m
        }
    }

    // ------------------------------------------------------------------ 游戏库
    private fun gamesDir() = File(filesDir, "games").apply { mkdirs() }

    private fun importAndRun(uri: Uri) {
        val name = queryName(uri) ?: "game.cbe"
        val dst = File(gamesDir(), name)
        // 拷进 app 私有目录再跑。直接用 SAF 的 uri 不行——Python 侧要的是
        // 一个真实路径，而且外部 uri 的权限随时会失效。
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

    // ------------------------------------------------------------------ 运行
    private fun startEmu(file: File) {
        stopEmu()
        ui.removeCallbacks(idleStop)
        running = true
        paused = false
        held.clear()
        keyMask = 0
        synchronized(touchLock) { touchQueue.clear(); softQueue.clear() }
        synchronized(keyLock) { keyLatch = 0 }
        val t = Thread {
            try {
                val size = py.callAttr("start", file.absolutePath, false).toString()
                val (w, h) = size.split(",").map { it.trim().toInt() }
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                ui.post {
                    bmp = b
                    statusBase = "${file.name}  ${w}x${h}"
                    status.text = statusBase
                    title = file.nameWithoutExtension
                }
                loop(b)
            } catch (e: Throwable) {
                ui.post { status.text = "启动失败：${e.message}" }
            } finally {
                // 收工必须在**这条线程**上做：Python 的 Session 是它建的，
                // 而且 AppStop 是游戏落存档的地方，不能漏。
                try { py.callAttr("stop") } catch (_: Throwable) {}
            }
        }
        thread = t
        t.start()
    }

    private fun loop(b: Bitmap) {
        var lastMask = -1
        // 实际帧率每 60 帧报一次。**游戏快慢就等于这个数**——
        // 模块是按帧推进的，所以"太快了"要看的是它，不是画面流畅度。
        var tick = 0
        var mark = System.currentTimeMillis()
        while (running) {
            // 后台期间一个 Python 调用都不要发：这条线程持有会话，
            // 停在这儿就等于把整台机器冻在当前这一帧上。
            if (paused) {
                lastMask = -1
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
                continue
            }
            // **每帧重算**，不要在循环外算一次——那样运行中改帧率不生效
            val period = 1000L / fps
            val t0 = System.currentTimeMillis()
            // 每帧读一次输入状态。**必须每帧都下发**——只在"和上一帧不同"时发的话，
            // 一次帧间完成的点击（按下+抬起都在 33ms 内）会被完全跳过。
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
            // 全部送进核心，由核心自己按帧消化（一帧一个）。前端这边不要限量，
            // 也不要合并——限量会让积压的事件越拖越远，合并会吃掉 down/up 边沿。
            while (true) {
                val t = synchronized(touchLock) { touchQueue.removeFirstOrNull() } ?: break
                py.callAttr("set_touch", t.first, t.second, t.third)
            }
            while (true) {
                val s = synchronized(touchLock) { softQueue.removeFirstOrNull() } ?: break
                py.callAttr("soft_key", s)
            }
            val px = py.callAttr("step").toJava(ByteArray::class.java)
            if (px.isNotEmpty()) {
                // RGB565 的字节序和 Android 的 Bitmap.Config.RGB_565 在 ARM 上一致，
                // 可以直接灌进去，不用逐像素转换。
                b.copyPixelsFromBuffer(ByteBuffer.wrap(px))
                ui.post { screen.setFrame(b) }
            }
            // 事件按条解析：音频要带着路径交给 MediaPlayer，
            // 只搜 "exit" 字样的老写法接不住它们。
            var bye = false
            val ev = py.callAttr("events").toString()
            if (ev.length > 2) {
                try {
                    val arr = JSONArray(ev)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        when (o.optString("kind")) {
                            "exit" -> bye = true
                            "audio" -> ui.post { audio.handle(o) }
                        }
                    }
                } catch (e: org.json.JSONException) {
                    bye = ev.contains("\"exit\"")
                }
            }
            if (bye) {
                ui.post { stopEmu() }
                return
            }
            val dt = System.currentTimeMillis() - t0
            if (dt < period) try { Thread.sleep(period - dt) } catch (_: InterruptedException) {}
            // 每 30 帧结算一次实测帧率，报到状态栏。
            // **游戏快慢看的就是这个数**，不是画面流不流畅。
            if (++tick >= 30) {
                val now = System.currentTimeMillis()
                val real = 30000.0 / (now - mark).coerceAtLeast(1)
                tick = 0
                mark = now
                ui.post { status.text = "%s  实测 %.1f fps".format(statusBase, real) }
            }
        }
    }

    private fun stopEmu() {
        if (!running && thread == null) return
        running = false
        paused = false
        audio.stop()
        // 等模拟线程自己退出——它的 finally 里会跑 AppStop 把存档落盘。
        // 给足 3 秒，逾期就不等了（宁可丢一次存档也不能卡住界面）。
        thread?.join(3000)
        thread = null
        bmp = null
        held.clear()
        keyMask = 0
        screen.clear()
        status.text = "已停止"
    }

    override fun onStart() {
        super.onStart()
        ui.removeCallbacks(idleStop)
        if (running && paused) {
            paused = false
            audio.resume()
            // 按住的键在后台期间不算按住，回来时一律松开
            held.clear()
            pushKeys()
            status.text = statusBase
        }
    }

    override fun onStop() {
        if (running && !isFinishing) {
            paused = true
            audio.pause()
            status.text = "$statusBase  已暂停"
            ui.postDelayed(idleStop, IDLE_STOP_MS)
        }
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacks(idleStop)
        stopEmu()
        super.onDestroy()
    }

    private companion object {
        /** 后台超过这么久就收工落存档 */
        const val IDLE_STOP_MS = 30_000L
    }
}
