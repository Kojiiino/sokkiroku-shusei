package com.kojiiino.sokkiroku

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** アプリ内で録音を再生する */
class AudioPlayer {
    private var mp: MediaPlayer? = null
    var playing by mutableStateOf(false)
        private set

    /** 読み込んで長さ（ミリ秒）を返す */
    fun load(file: File): Long {
        release()
        val m = MediaPlayer()
        m.setDataSource(file.absolutePath)
        m.prepare()
        m.setOnCompletionListener { playing = false }
        mp = m
        return m.duration.toLong()
    }

    fun position(): Long = mp?.currentPosition?.toLong() ?: 0L

    fun playFrom(ms: Long) {
        val m = mp ?: return
        m.seekTo(ms.coerceIn(0L, m.duration.toLong()).toInt())
        m.start()
        playing = true
    }

    fun toggle() {
        val m = mp ?: return
        if (m.isPlaying) {
            m.pause()
            playing = false
        } else {
            m.start()
            playing = true
        }
    }

    fun skip(deltaMs: Long) {
        val m = mp ?: return
        val to = (m.currentPosition + deltaMs).coerceIn(0L, m.duration.toLong())
        m.seekTo(to.toInt())
    }

    fun pause() {
        val m = mp ?: return
        if (m.isPlaying) m.pause()
        playing = false
    }

    fun release() {
        mp?.release()
        mp = null
        playing = false
    }
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
