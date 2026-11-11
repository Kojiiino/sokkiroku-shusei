package com.kojiiino.sokkiroku

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Screen { Files, Edit }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    var tokenProvider: (suspend () -> String)? = null
    var pickAudio: (() -> Unit)? = null

    var screen by mutableStateOf(Screen.Files)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var files by mutableStateOf<List<Drive.DFile>>(emptyList())
    var loadedOnce by mutableStateOf(false)
    var current by mutableStateOf<Drive.DFile?>(null)
    var parsed by mutableStateOf<ParsedDoc?>(null)
    var showGray by mutableStateOf(false)
    var savedName by mutableStateOf<String?>(null)

    // 録音
    val player = AudioPlayer()
    var audioReady by mutableStateOf(false)
    var durationMs by mutableStateOf<Long?>(null)
    var receivedPending by mutableStateOf(false)
    var manualMinutes by mutableStateOf("")
    var startMin by mutableStateOf("")
    var endMin by mutableStateOf("")
    var anchors by mutableStateOf<List<Pair<Int, Long>>>(emptyList())

    private var folderId: String? = null
    private val audioDir = File(app.filesDir, "audio").apply { mkdirs() }
    private val receivedFile = File(audioDir, "_received.audio")
    private val prefs = app.getSharedPreferences("docs", 0)

    private suspend fun token(): String =
        tokenProvider?.invoke() ?: throw IllegalStateException("準備中です。もう一度お試しください")

    private fun task(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            message = null
            try {
                block()
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun loadFiles() = task {
        val t = token()
        val f = folderId ?: withContext(Dispatchers.IO) { Drive.findFolder(t) }
        folderId = f
        files = withContext(Dispatchers.IO) { Drive.listFiles(t, f) }
        loadedOnce = true
    }

    fun open(f: Drive.DFile) = task {
        val t = token()
        val html = withContext(Dispatchers.IO) { Drive.exportHtml(t, f.id) }
        val pd = withContext(Dispatchers.Default) { SokkiParser.parse(html) }
        current = f
        parsed = pd
        showGray = false
        savedName = null
        loadDocPrefs(f.id)
        if (receivedPending) {
            attachReceived()
        } else {
            loadAudioFor(f.id)
        }
        screen = Screen.Edit
    }

    fun save() {
        val pd = parsed ?: return
        val f = current ?: return
        val decisions = pd.items.map { it.status to it.value }
        task {
            val folder = folderId ?: throw IllegalStateException("保存先のフォルダが分かりません")
            val t = token()
            val html = withContext(Dispatchers.Default) { SokkiParser.build(pd.html, decisions) }
            var name = f.name + "_修正済"
            if (files.any { it.name == name }) {
                name += "_" + LocalTime.now().format(DateTimeFormatter.ofPattern("HHmm"))
            }
            withContext(Dispatchers.IO) { Drive.createDoc(t, folder, name, html) }
            savedName = name
        }
    }

    fun backToFiles() {
        player.release()
        audioReady = false
        durationMs = null
        screen = Screen.Files
        parsed = null
        current = null
        savedName = null
    }

    fun finishSaved() {
        backToFiles()
        loadFiles()
    }

    // ---------- 録音の取り込み ----------

    /** レコーダーからの共有、またはファイル選択で受け取った録音 */
    fun receiveAudio(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cr = getApplication<Application>().contentResolver
                    val input = cr.openInputStream(uri)
                        ?: throw IllegalStateException("録音ファイルを開けませんでした")
                    input.use { i -> receivedFile.outputStream().use { o -> i.copyTo(o) } }
                }
                if (screen == Screen.Edit && current != null) {
                    attachReceived()
                } else {
                    receivedPending = true
                }
            } catch (e: Exception) {
                message = "録音を取り込めませんでした（${e.message}）"
            }
        }
    }

    private fun audioFileFor(docId: String) = File(audioDir, "$docId.audio")

    private fun attachReceived() {
        val f = current ?: return
        if (!receivedFile.exists()) return
        val dest = audioFileFor(f.id)
        dest.delete()
        if (!receivedFile.renameTo(dest)) {
            receivedFile.copyTo(dest, overwrite = true)
            receivedFile.delete()
        }
        receivedPending = false
        // 別の録音に替えたので、基準はやり直す
        anchors = emptyList()
        saveDocPrefs()
        cleanupAudio(keep = 5)
        loadAudioFor(f.id)
    }

    private fun loadAudioFor(docId: String) {
        val file = audioFileFor(docId)
        if (!file.exists()) {
            player.release()
            audioReady = false
            durationMs = null
            return
        }
        try {
            durationMs = player.load(file)
            audioReady = true
        } catch (e: Exception) {
            audioReady = false
            durationMs = null
            message = "録音を再生できませんでした（${e.message}）"
        }
    }

    private fun cleanupAudio(keep: Int) {
        val list = audioDir.listFiles { f -> f.name != receivedFile.name } ?: return
        list.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    // ---------- 時刻の見積もり ----------

    private fun minToMs(s: String): Long? = s.trim().toDoubleOrNull()?.let { (it * 60_000).toLong() }

    fun totalMs(): Long? = durationMs ?: minToMs(manualMinutes)

    /** 本文の文字位置から、録音の時刻を見積もる */
    fun estimate(pos: Int): Long? {
        val total = totalMs() ?: return null
        val pd = parsed ?: return null
        val start = minToMs(startMin) ?: 0L
        val end = minToMs(endMin) ?: total
        val pts = (listOf(0 to start) + anchors + listOf(pd.totalChars to end))
            .sortedBy { it.first }
        for (i in 0 until pts.size - 1) {
            val (p0, t0) = pts[i]
            val (p1, t1) = pts[i + 1]
            if (pos in p0..p1) {
                if (p1 == p0) return t0
                return t0 + (t1 - t0) * (pos - p0) / (p1 - p0)
            }
        }
        return end
    }

    fun playItem(item: Item) {
        val t = estimate(item.pos) ?: return
        player.playFrom((t - 15_000).coerceAtLeast(0))
    }

    /** 今の再生位置を、この箇所の正しい時刻として登録する */
    fun setAnchor(item: Item) {
        val t = player.position()
        val p = item.pos
        // 位置と時刻の前後が逆転する古い基準は捨てる（新しく登録したものを優先）
        val kept = anchors.filter {
            (it.first < p && it.second < t) || (it.first > p && it.second > t)
        }
        anchors = (kept + (p to t)).sortedBy { it.first }
        saveDocPrefs()
    }

    fun resetAnchors() {
        anchors = emptyList()
        saveDocPrefs()
    }

    // ---------- 速記録ごとの設定 ----------

    private fun loadDocPrefs(docId: String) {
        val s = prefs.getString(docId, null)
        manualMinutes = ""
        startMin = ""
        endMin = ""
        anchors = emptyList()
        if (s == null) return
        try {
            val o = JSONObject(s)
            manualMinutes = o.optString("manual")
            startMin = o.optString("start")
            endMin = o.optString("end")
            val a = o.optJSONArray("anchors") ?: JSONArray()
            anchors = (0 until a.length()).map {
                val p = a.getJSONArray(it)
                p.getInt(0) to p.getLong(1)
            }
        } catch (e: Exception) {
            // 壊れた設定は無視する
        }
    }

    fun saveDocPrefs() {
        val f = current ?: return
        val a = JSONArray()
        anchors.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        val o = JSONObject()
            .put("manual", manualMinutes)
            .put("start", startMin)
            .put("end", endMin)
            .put("anchors", a)
        prefs.edit().putString(f.id, o.toString()).apply()
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
