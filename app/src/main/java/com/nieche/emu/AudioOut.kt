package com.nieche.emu

import android.media.AudioAttributes
import android.media.MediaPlayer
import org.json.JSONObject

class AudioOut {
    private var mp: MediaPlayer? = null
    private var pausedByUs = false
    private var curPath: String? = null

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

                val path = ev.optString("path").takeIf { it.isNotEmpty() && it != "null" } ?: return
                if (enabled) play(path, ev.optBoolean("loop"))
            }
        }
    }

    private fun play(path: String, loop: Boolean) {

        val p = mp
        if (p != null && curPath == path) {
            try {
                p.isLooping = loop
                p.seekTo(0)
                if (!p.isPlaying) p.start()
                return
            } catch (_: IllegalStateException) {

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

            if (logged.add(path)) {
                android.util.Log.i("nieche", "音频开始播放 $path loop=$loop")
            }
        } catch (e: Throwable) {
            android.util.Log.w("nieche", "放不了 $path: ${e.message}")
            stop()
        }
    }

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
