package com.nieche.emu

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import com.chaquo.python.PyObject

class CheatPanel(
    private val ctx: Context,
    private val py: PyObject,
    private val ui: Handler,

    private val core: (() -> Unit) -> Unit,
    private val state: State,
) {

    class State {
        var width = 4
        var count = -1
        val locks = mutableListOf<Triple<Long, Int, Long>>()
        fun clear() {
            width = 4
            count = -1
            locks.clear()
        }
    }

    private val widths = listOf(4, 2, 1)
    private lateinit var input: EditText
    private lateinit var widthSpin: Spinner
    private lateinit var searchBtn: Button
    private lateinit var info: TextView
    private val rows = mutableListOf<Pair<Long, Long>>()
    private lateinit var rowAdapter: ArrayAdapter<String>
    private lateinit var lockAdapter: ArrayAdapter<String>

    fun show(onClose: () -> Unit) {
        val pad = (12 * ctx.resources.displayMetrics.density).toInt()
        val listH = (200 * ctx.resources.displayMetrics.density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(ctx).apply {
            hint = "游戏里看到的数值"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        top.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        widthSpin = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                                   widths.map { "$it 字节" })
            setSelection(widths.indexOf(state.width).coerceAtLeast(0))
        }
        top.addView(widthSpin)
        root.addView(top)

        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        searchBtn = Button(ctx).apply { setOnClickListener { search() } }
        btns.addView(searchBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(Button(ctx).apply {
            text = "重新开始"
            setOnClickListener { restart() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btns)

        info = TextView(ctx).apply { setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(info)

        rowAdapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, mutableListOf())
        root.addView(ListView(ctx).apply {
            adapter = rowAdapter
            setOnItemClickListener { _, _, i, _ -> rows.getOrNull(i)?.let { edit(it.first, it.second) } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listH))

        root.addView(TextView(ctx).apply {
            text = "已锁定（点一下解锁）"
            setPadding(0, pad / 2, 0, 0)
        })
        lockAdapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, mutableListOf())
        root.addView(ListView(ctx).apply {
            adapter = lockAdapter
            setOnItemClickListener { _, _, i, _ -> state.locks.getOrNull(i)?.let { unlock(it) } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listH / 2))

        refreshLabels()
        refreshLocks()
        if (state.count >= 0) loadRows()

        AlertDialog.Builder(ctx)
            .setTitle("修改器")
            .setView(root)
            .setPositiveButton("关闭", null)
            .setOnDismissListener { onClose() }
            .show()
    }

    private fun selectedWidth() = widths[widthSpin.selectedItemPosition.coerceIn(0, widths.size - 1)]

    private fun refreshLabels() {
        searchBtn.text = if (state.count < 0) "搜索" else "筛选"
        info.text = when {
            state.count < 0 -> "输入一个数值开始搜索。数值变化后（关掉修改器玩一会儿）再输新值筛选。"
            state.count == 0 -> "没有找到。换个宽度试试，或点「重新开始」。"
            state.count > ROW_LIMIT -> "找到 ${state.count} 个，太多了。回游戏让这个数值变一变，再输新值筛选。"
            else -> "找到 ${state.count} 个。点一个可以修改或锁定。"
        }
    }

    private fun search() {
        val v = input.text.toString().trim().toLongOrNull() ?: run {
            info.text = "先输入一个整数"
            return
        }
        val w = selectedWidth()
        searchBtn.isEnabled = false
        core {
            val n = py.callAttr("cheat_search", w, v).toInt()
            ui.post {
                state.width = w
                state.count = n
                searchBtn.isEnabled = true
                refreshLabels()
                loadRows()
            }
        }
    }

    private fun restart() {
        core {
            py.callAttr("cheat_reset")
            ui.post {
                state.count = -1
                rows.clear()
                rowAdapter.clear()
                refreshLabels()
            }
        }
    }

    private fun loadRows() {
        if (state.count <= 0 || state.count > ROW_LIMIT) {
            rows.clear()
            rowAdapter.clear()
            return
        }
        core {
            val s = py.callAttr("cheat_results").toString()
            val parsed = s.split(";").filter { it.isNotEmpty() }.mapNotNull {
                val p = it.split(":")
                if (p.size == 2) p[0].toLong() to p[1].toLong() else null
            }
            ui.post {
                rows.clear()
                rows.addAll(parsed)
                rowAdapter.clear()
                rowAdapter.addAll(parsed.map { (a, v) -> "0x%08X    当前值 %d".format(a, v) })
            }
        }
    }

    private fun refreshLocks() {
        lockAdapter.clear()
        lockAdapter.addAll(state.locks.map { (a, w, v) -> "0x%08X    锁定为 %d（%d 字节）".format(a, v, w) })
    }

    private fun edit(addr: Long, cur: Long) {
        val w = state.width
        val box = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(cur.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(ctx)
            .setTitle("0x%08X".format(addr))
            .setView(box)
            .setPositiveButton("改一次") { _, _ ->
                val v = box.text.toString().trim().toLongOrNull() ?: return@setPositiveButton
                core {
                    py.callAttr("cheat_write", addr, w, v)
                    ui.post { loadRows() }
                }
            }
            .setNeutralButton("锁定") { _, _ ->
                val v = box.text.toString().trim().toLongOrNull() ?: return@setNeutralButton
                core {
                    py.callAttr("cheat_write", addr, w, v)
                    py.callAttr("cheat_lock", addr, w, v, true)
                    ui.post {
                        state.locks.removeAll { it.first == addr }
                        state.locks.add(Triple(addr, w, v))
                        refreshLocks()
                        loadRows()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun unlock(l: Triple<Long, Int, Long>) {
        core {
            py.callAttr("cheat_lock", l.first, l.second, l.third, false)
            ui.post {
                state.locks.remove(l)
                refreshLocks()
            }
        }
    }

    companion object {

        const val ROW_LIMIT = 200
    }
}
