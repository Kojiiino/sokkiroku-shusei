package com.kojiiino.sokkiroku

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Screen { Files, Edit, Dict, Speaker }

/** 「ほかの箇所も直しますか」の提案 */
class Proposal(val item: Item, val from: String, val to: String)

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
    var showGray by mutableStateOf(true)
    var showSearch by mutableStateOf(false)
    var savedName by mutableStateOf<String?>(null)

    // 同じ会議の中で使う置き換え（例：中継→中計）
    var rules by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var proposal by mutableStateOf<Proposal?>(null)

    // 一時保存（途中の作業）
    var restoredCount by mutableStateOf(0)
    var draftIds by mutableStateOf<Set<String>>(emptySet())

    // 録音
    val player = AudioPlayer()
    var audioReady by mutableStateOf(false)
    var durationMs by mutableStateOf<Long?>(null)
    var receivedPending by mutableStateOf(false)
    var manualMinutes by mutableStateOf("")
    var startMin by mutableStateOf("")
    var endMin by mutableStateOf("")
    var anchors by mutableStateOf<List<Pair<Int, Long>>>(emptyList())

    // 話者づけ（段落の番号 → 先方／当社）
    val spk = mutableStateMapOf<Int, Spk>()

    // 辞書候補
    var cands by mutableStateOf<List<Cand>>(emptyList())
    var dict by mutableStateOf<Dictionary?>(null)
    var dictPublished by mutableStateOf<String?>(null)
    var poolCount by mutableStateOf(0)

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
        draftIds = files.map { it.id }.filter { prefs.contains("draft:$it") }.toSet()
        loadedOnce = true
        poolCount = loadPool().size
    }

    fun open(f: Drive.DFile) = task {
        val t = token()
        val html = withContext(Dispatchers.IO) { Drive.exportHtml(t, f.id) }
        val pd = withContext(Dispatchers.Default) { SokkiParser.parse(html) }
        current = f
        parsed = pd
        showGray = true
        savedName = null
        loadDocPrefs(f.id)
        loadRules()
        restoredCount = restoreDraft()
        loadSpk()
        applyRulesToItems()
        proposal = null
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
            val rs = rules
            val marks = spk.mapValues { it.value.mark }
            val html = withContext(Dispatchers.Default) {
                SokkiParser.build(pd.html, decisions, rs, marks)
            }
            var name = f.name + "_修正済"
            if (files.any { it.name == name }) {
                name += "_" + LocalTime.now().format(DateTimeFormatter.ofPattern("HHmm"))
            }
            withContext(Dispatchers.IO) { Drive.createDoc(t, folder, name, html) }
            addToPool(DictCodec.collect(pd, rs))
            clearDraft()
            clearSpk()
            savedName = name
        }
    }

    fun backToFiles() {
        saveDraft()
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

    // ---------- 話者づけ ----------

    private fun spkKey() = current?.id?.let { "spk:$it" }

    fun setSpk(i: Int, v: Spk) {
        if (v == Spk.NONE) spk.remove(i) else spk[i] = v
        saveSpk()
    }

    /** 直前の段落と逆の話者にする（質問と回答が交互に続く場面向け） */
    fun flipFromPrevious(i: Int) {
        val prev = parsed?.paras?.take(i)?.reversed()
            ?.firstNotNullOfOrNull { spk[it.index]?.takeIf { v -> v == Spk.SEN || v == Spk.TOU } }
            ?: return
        setSpk(i, if (prev == Spk.SEN) Spk.TOU else Spk.SEN)
    }

    private fun saveSpk() {
        val key = spkKey() ?: return
        if (spk.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val o = JSONObject()
        spk.forEach { (i, v) -> o.put(i.toString(), v.name) }
        prefs.edit().putString(key, o.toString()).apply()
    }

    private fun loadSpk() {
        spk.clear()
        val key = spkKey() ?: return
        val s = prefs.getString(key, null) ?: return
        try {
            val o = JSONObject(s)
            val n = parsed?.paras?.size ?: 0
            o.keys().forEach { k ->
                val i = k.toInt()
                if (i in 0 until n) spk[i] = Spk.valueOf(o.getString(k))
            }
        } catch (e: Exception) {
            // 壊れた設定は無視する
        }
    }

    private fun clearSpk() {
        val key = spkKey() ?: return
        prefs.edit().remove(key).apply()
        spk.clear()
    }

    fun openSpeaker() {
        screen = Screen.Speaker
    }

    fun playPara(p: Para) {
        val t = estimate(p.pos) ?: return
        player.playFrom((t - 3_000).coerceAtLeast(0))
    }

    // ---------- 辞書候補 ----------

    private fun loadPool(): List<Cand> =
        prefs.getString("pool", null)?.let { Cand.listFromJson(it) } ?: emptyList()

    private fun savePool(l: List<Cand>) {
        prefs.edit().putString("pool", Cand.listToJson(l)).apply()
        poolCount = l.size
    }

    /** 保存した速記録から拾った候補を、たまっている候補に合流させる */
    private fun addToPool(found: List<Cand>) {
        if (found.isEmpty()) return
        val pool = loadPool().toMutableList()
        for (c in found) {
            val ex = pool.firstOrNull { it.key == c.key }
            if (ex == null) {
                pool.add(c)
            } else {
                ex.seen += c.seen
                ex.fixed += c.fixed
                if (ex.ctx.isEmpty()) ex.ctx = c.ctx
            }
        }
        savePool(pool)
    }

    /** 辞書候補の画面を開く。ドライブの最新の辞書を読んで、すでにある語は除く */
    fun openDict() = task {
        val t = token()
        val f = folderId ?: withContext(Dispatchers.IO) { Drive.findFolder(t) }
        folderId = f
        val latest = withContext(Dispatchers.IO) { Drive.latestDictionary(t, f) }
        dict = if (latest == null) {
            Dictionary(0, emptyList(), emptyList(), emptyList())
        } else {
            val h = withContext(Dispatchers.IO) { Drive.exportHtml(t, latest.second.id) }
            withContext(Dispatchers.Default) { DictCodec.parse(h, latest.first) }
        }
        val d = dict!!
        cands = loadPool().filter { !d.knows(it.from) }.sortedByDescending { it.fixed }
        dictPublished = null
        screen = Screen.Dict
    }

    fun saveCands() {
        // 画面に出ていない（辞書にすでにある）候補は、そのまま残す
        val shown = cands.map { it.key }.toSet()
        val rest = loadPool().filter { it.key !in shown }
        savePool(rest + cands)
    }

    fun backFromDict() {
        saveCands()
        screen = Screen.Files
    }

    /** 決めた候補を、辞書の新しい版としてドライブに保存する */
    fun publishDict() {
        val d = dict ?: return
        val decided = cands.filter { it.choice != Choice.NONE }
        if (decided.isEmpty()) {
            message = "A・B・入れない のどれかを選んだ候補がありません"
            return
        }
        task {
            val folder = folderId ?: throw IllegalStateException("保存先のフォルダが分かりません")
            val t = token()
            val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
            val a = d.a.toMutableList()
            val b = d.b.toMutableList()
            val r = d.rejected.toMutableList()
            for (c in decided) {
                when (c.choice) {
                    Choice.A -> a.add(DictA(c.from, c.to, date))
                    Choice.B -> b.add(DictB(c.from, c.to, c.cond.trim(), date))
                    Choice.NO -> r.add("${c.from}→${c.to}（$date、Iさんが×。辞書には入れない）")
                    Choice.NONE -> {}
                }
            }
            val nd = Dictionary(d.version + 1, a, b, r)
            val note = "アプリが修正内容から作った候補（${decided.size}件を判断）"
            val html = withContext(Dispatchers.Default) { DictCodec.build(nd, date, note) }
            val name = "用語辞書_承認済み_v${nd.version}"
            withContext(Dispatchers.IO) { Drive.createDoc(t, folder, name, html) }
            // 判断した候補を、たまっている候補から外す
            val done = decided.map { it.key }.toSet()
            val shown = cands.map { it.key }.toSet()
            savePool(loadPool().filter { it.key !in shown } + cands.filter { it.key !in done })
            cands = cands.filter { it.key !in done }
            dict = nd
            dictPublished = name
        }
    }

    // ---------- 直す・置き換え ----------

    /** 「直す」を押したとき。ほかにも同じ語があれば、まとめて直すか提案する */
    fun fixItem(item: Item) {
        item.status = Status.FIXED
        val pd = parsed ?: return
        if (item.isFumei || item.original.isEmpty() || item.value == item.original) return
        val (from, to) = SokkiParser.wordDiff(item.original, item.value)
        if (from.isEmpty()) return
        val others = pd.count(from) - (if (item.original.contains(from)) 1 else 0)
        if (others > 0) proposal = Proposal(item, from, to)
    }

    fun applyRule(from: String, to: String) {
        proposal = null
        if (from.isEmpty() || from == to) return
        rules = rules.filter { it.first != from } + (from to to)
        applyRulesToItems()
        saveRules()
    }

    fun removeRule(from: String) {
        rules = rules.filter { it.first != from }
        saveRules()
    }

    /** 未処理のカードのうち、置き換える語を含むものを直す */
    private fun applyRulesToItems() {
        val pd = parsed ?: return
        for (item in pd.items) {
            if (item.status != Status.PENDING || item.isFumei || item.original.isEmpty()) continue
            for ((from, to) in rules) {
                if (item.original.contains(from)) {
                    item.value = item.original.replace(from, to)
                    item.status = Status.FIXED
                    break
                }
            }
        }
    }

    /** パート01・02…は同じ会議として、置き換えを共有する */
    private fun meetingKey(): String? =
        current?.name?.substringBefore("_速記録")?.let { "rules:$it" }

    private fun loadRules() {
        rules = emptyList()
        val key = meetingKey() ?: return
        val s = prefs.getString(key, null) ?: return
        try {
            val a = JSONArray(s)
            rules = (0 until a.length()).map {
                val p = a.getJSONArray(it)
                p.getString(0) to p.getString(1)
            }
        } catch (e: Exception) {
            // 壊れた設定は無視する
        }
    }

    private fun saveRules() {
        val key = meetingKey() ?: return
        val a = JSONArray()
        rules.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    // ---------- 一時保存 ----------

    private fun draftKey() = current?.id?.let { "draft:$it" }

    /** 途中の作業をスマホの中に保存する（ドライブには保存しない） */
    fun saveDraft() {
        val key = draftKey() ?: return
        val pd = parsed ?: return
        if (savedName != null) return
        if (pd.items.none { it.status != Status.PENDING }) {
            prefs.edit().remove(key).apply()
            draftIds = draftIds - key.removePrefix("draft:")
            return
        }
        val a = JSONArray()
        pd.items.forEach { a.put(JSONArray().put(it.status.name).put(it.value).put(it.original)) }
        val o = JSONObject().put("modified", current?.modified ?: "").put("items", a)
        prefs.edit().putString(key, o.toString()).apply()
        draftIds = draftIds + key.removePrefix("draft:")
    }

    /** 一時保存があれば戻す。戻した件数を返す */
    private fun restoreDraft(): Int {
        val key = draftKey() ?: return 0
        val pd = parsed ?: return 0
        val s = prefs.getString(key, null) ?: return 0
        return try {
            val o = JSONObject(s)
            val a = o.getJSONArray("items")
            // 速記録が別の場所で直されていたら、箇所の対応がずれるので使わない
            if (o.optString("modified") != (current?.modified ?: "") || a.length() != pd.items.size) {
                prefs.edit().remove(key).apply()
                message = "速記録がドライブ上で変わっていたため、一時保存は使えませんでした"
                return 0
            }
            var n = 0
            pd.items.forEachIndexed { i, item ->
                val e = a.getJSONArray(i)
                if (e.getString(2) != item.original) return@forEachIndexed
                val st = Status.valueOf(e.getString(0))
                item.value = e.getString(1)
                item.status = st
                if (st != Status.PENDING) n++
            }
            n
        } catch (e: Exception) {
            0
        }
    }

    /** 一時保存を捨てて、最初からやり直す */
    fun discardDraft() {
        val pd = parsed ?: return
        clearDraft()
        pd.items.forEach {
            it.status = Status.PENDING
            it.value = if (it.isFumei) "" else it.original
        }
        applyRulesToItems()
    }

    private fun clearDraft() {
        val key = draftKey() ?: return
        prefs.edit().remove(key).apply()
        draftIds = draftIds - key.removePrefix("draft:")
        restoredCount = 0
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
