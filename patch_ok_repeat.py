import re

with open("app/src/main/java/com/nieche/emu/MainActivity.kt", "r") as f:
    content = f.read()

old_func = """    private fun basicKeyButton(id: String): Button =
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
        }"""

new_func = """    private fun basicKeyButton(id: String, autoRepeat: Boolean = false): Button =
        Button(this).apply {
            text = Keys.label[id] ?: id
            textSize = 13f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minHeight = 0
            minimumHeight = 0
            
            var repeatTask: Runnable? = null
            
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        held.add(id); pushKeys(); v.isPressed = true
                        if (id == "rsk") synchronized(touchLock) { softQueue.add("right") }
                        if (autoRepeat) {
                            repeatTask = object : Runnable {
                                var down = true
                                override fun run() {
                                    down = !down
                                    if (down) held.add(id) else held.remove(id)
                                    pushKeys()
                                    // 模拟按下和抬起，由于按键是边沿触发，需要间隔交替
                                    // 100ms(按下) -> 100ms(抬起) 的频率
                                    v.postDelayed(this, 80)
                                }
                            }
                            v.postDelayed(repeatTask, 400)
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> { 
                        held.remove(id); pushKeys(); v.isPressed = false 
                        if (autoRepeat) {
                            repeatTask?.let { v.removeCallbacks(it) }
                            repeatTask = null
                        }
                    }
                }
                true
            }
        }"""

content = content.replace(old_func, new_func)

content = content.replace('val okBtn = basicKeyButton("ok").apply { textSize = 20f }',
                          'val okBtn = basicKeyButton("ok", autoRepeat = true).apply { textSize = 20f }')

with open("app/src/main/java/com/nieche/emu/MainActivity.kt", "w") as f:
    f.write(content)

