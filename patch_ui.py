import re

with open("app/src/main/java/com/nieche/emu/MainActivity.kt", "r") as f:
    content = f.read()

# 1. Remove toggleBtn declaration and usage
content = re.sub(r'private lateinit var toggleBtn: Button\n', '', content)
content = re.sub(r'private lateinit var funcRow: LinearLayout\n', '', content)
content = re.sub(r'private var controlsVisible = true\n', 'private var controlsVisible = true\n    private lateinit var controlsPanel: View\n    private lateinit var pullBar: TextView\n', content)

# 2. Remove toggleBtn from topBar and funcRow from buildUi()
buildUi_code = """
        // 收起 / 展开按钮
        toggleBtn = Button(this).apply {
            text = "收起"
            setOnClickListener { toggleControls() }
        }
        bar.addView(toggleBtn)
"""
content = content.replace(buildUi_code, "")

funcRow_code = """
        // 功能键行（上部：左软键、右软键、呼叫、挂断）
        val dp = resources.displayMetrics.density
        funcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6, 2, 6, 2)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * dp).toInt())
        }
        funcRow.addView(keyButton("lsk"))
        funcRow.addView(keyButton("rsk"))
        funcRow.addView(keyButton("call"))
        funcRow.addView(keyButton("end"))
        root.addView(funcRow)
"""
content = content.replace(funcRow_code, "")

# 3. Change keyButton to not set LinearLayout.LayoutParams automatically,
# or create a basicKeyButton.
basic_key_button = """
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
"""
# insert before keyButton
content = content.replace("    private fun keyButton(", basic_key_button + "\n    private fun keyButton(")


# 4. Rewrite buildControls()
new_buildControls = """
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
        
        val okSize = (joySize * 0.48).toInt()
        val funcSize = (joySize * 0.32).toInt()
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
"""
# Replace buildControls() and toggleControls()
start_idx = content.find("    private fun buildControls(): View {")
end_idx = content.find("    /// 让用户直接输入任意帧率")
if start_idx != -1 and end_idx != -1:
    # also remove the comment block above buildControls
    comment_start = content.rfind("    /**", 0, start_idx)
    if comment_start != -1:
        content = content[:comment_start] + new_buildControls + "\n" + content[end_idx:]

with open("app/src/main/java/com/nieche/emu/MainActivity.kt", "w") as f:
    f.write(content)

