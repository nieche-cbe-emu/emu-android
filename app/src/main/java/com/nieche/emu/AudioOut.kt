package com.nieche.emu

import android.media.AudioAttributes
import android.media.MediaPlayer
import org.json.JSONObject

/**
 * 出声。
 *
 * 核心自己不发声——它把音频落成文件，再把路径按事件交出来
 * （见 emucore 的 audio.rs）。安卓这边 MediaPlayer 连 MIDI 都能放，
 * 29 个模块里带音频的 21 个大半是 .mid，所以不用另找合成器。
 *
 * **所有方法都在界面线程上调。** MediaPlayer 不是线程安全的，
 * 而事件是从模拟线程来的，必须先 post 过来。
 */
class AudioOut {
    private var mp: MediaPlayer? = null
    private var pausedByUs = false
    private var curPath: String? = null
    /** 已经报过日志的文件，每个只报一次 */
    private val logged = mutableSetOf<String>()

    var enabled = true
        set(v) {
            field = v
            if (!v) stop()
        }

    fun handle(ev: JSONObject) {
        when (ev.optString("op")) {
            "stop" -> silence()
            "play" -> {
                // 核心没能落盘时 path 是 null，没有文件就没什么可放的
                val path = ev.optString("path").takeIf { it.isNotEmpty() && it != "null" } ?: return
                if (enabled) play(path, ev.optBoolean("loop"))
            }
        }
    }

    private fun play(path: String, loop: Boolean) {
        // **同一段音频又被触发时不要重建播放器。** 游戏的音效一秒能响好几次
        // （涂鸦跳跃的跳跃声 400 帧里 36 次），每次都 release + 新建 + prepare
        // 是在界面线程上做的，攒起来就是卡顿。倒回开头接着放就行。
        val p = mp
        if (p != null && curPath == path) {
            try {
                p.isLooping = loop
                p.seekTo(0)
                if (!p.isPlaying) p.start()
                return
            } catch (_: IllegalStateException) {
                // 播放器已经不能用了，按下面的流程重建
            }
        }
        stop()
        curPath = path
        try {
            mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(path)
                isLooping = loop
                setOnErrorListener { _, what, extra ->
                    android.util.Log.w("nieche", "音频出错 what=$what extra=$extra")
                    true
                }
                prepare()
                start()
            }
            // 每个文件报一次（logcat）。用户说"没声音"时，这一行能把
            // "事件没到"和"放了但听不见"分开。音效一秒好几次，不能逐次打。
            if (logged.add(path)) {
                android.util.Log.i("nieche", "音频开始播放 $path loop=$loop")
            }
        } catch (e: Throwable) {
            android.util.Log.w("nieche", "放不了 $path: ${e.message}")
            stop()
        }
    }

    /**
     * 核心说"停"：只是别出声，播放器留着。**不要顺手 release**——
     * 音效一秒能响好几次，重建播放器的开销全压在界面线程上。
     */
    fun silence() {
        pausedByUs = false
        val p = mp ?: return
        try {
            if (p.isPlaying) p.pause()
            p.seekTo(0)
        } catch (_: IllegalStateException) {
            stop()
        }
    }

    /** 退到后台时静音，但不丢掉当前这一段——回来接着放 */
    fun pause() {
        val p = mp ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                pausedByUs = true
            }
        } catch (_: IllegalStateException) {}
    }

    fun resume() {
        val p = mp ?: return
        if (!pausedByUs) return
        pausedByUs = false
        if (!enabled) return
        try { p.start() } catch (_: IllegalStateException) {}
    }

    fun stop() {
        pausedByUs = false
        curPath = null
        val p = mp ?: return
        mp = null
        try { p.stop() } catch (_: IllegalStateException) {}
        p.release()
    }
}
