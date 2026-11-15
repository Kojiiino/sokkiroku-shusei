package com.kojiiino.sokkiroku

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

class DictA(val from: String, val to: String, val date: String)
class DictB(val from: String, val cand: String, val cond: String, val date: String)

/** ドライブにある「用語辞書_承認済み_vN」の内容 */
class Dictionary(
    val version: Int,
    val a: List<DictA>,
    val b: List<DictB>,
    val rejected: List<String>,
) {
    fun knows(from: String): Boolean =
        a.any { it.from == from } || b.any { it.from == from } ||
            rejected.any { it.startsWith("$from→") || it.startsWith("$from＞") }
}

/** 辞書候補の決め方 */
enum class Choice { NONE, A, B, NO }

/** 修正から拾った辞書候補1件 */
class Cand(
    val from: String,
    val to: String,
    var ctx: String,
    var seen: Int,
    var fixed: Int,
) {
    var choice by mutableStateOf(Choice.NONE)
    var cond by mutableStateOf("")
    val key get() = "$from→$to"

    fun toJson(): JSONObject = JSONObject()
        .put("from", from).put("to", to).put("ctx", ctx)
        .put("seen", seen).put("fixed", fixed)
        .put("choice", choice.name).put("cond", cond)

    companion object {
        fun fromJson(o: JSONObject): Cand = Cand(
            o.getString("from"), o.getString("to"), o.optString("ctx"),
            o.optInt("seen"), o.optInt("fixed"),
        ).also {
            it.choice = try {
                Choice.valueOf(o.optString("choice", "NONE"))
            } catch (e: Exception) {
                Choice.NONE
            }
            it.cond = o.optString("cond")
        }

        fun listToJson(l: List<Cand>): String {
            val a = JSONArray()
            l.forEach { a.put(it.toJson()) }
            return a.toString()
        }

        fun listFromJson(s: String): List<Cand> = try {
            val a = JSONArray(s)
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

object DictCodec {
    private val TAG_RE = Regex("[\\[［][^\\]］]*[\\]］]")

    fun cleanCtx(s: String) = TAG_RE.replace(s, "").replace(Regex("\\s+"), " ").trim()

    /** 修正済みの速記録から、辞書候補を拾う（灰色の誤変換の疑いだけ。人名・社名・数字は対象外） */
    fun collect(pd: ParsedDoc, rules: List<Pair<String, String>>): List<Cand> {
        val map = LinkedHashMap<String, Cand>()
        fun ok(from: String, to: String) =
            from.length in 1..12 && to.isNotBlank() && from != to &&
                !from.any { it.isDigit() } && !to.any { it.isDigit() }

        for (it in pd.items) {
            if (!it.isGray || it.isFumei || it.status != Status.FIXED) continue
            val from = it.original.trim()
            val to = it.value.trim()
            if (!ok(from, to)) continue
            val k = "$from→$to"
            val c = map.getOrPut(k) {
                Cand(
                    from, to,
                    cleanCtx(it.before).takeLast(12) + from + cleanCtx(it.after).take(10),
                    pd.count(from).coerceAtLeast(1), 0,
                )
            }
            c.fixed += 1
        }
        for ((from, to) in rules) {
            if (!ok(from, to)) continue
            val k = "$from→$to"
            val seen = pd.count(from)
            val c = map.getOrPut(k) { Cand(from, to, "", seen.coerceAtLeast(1), 0) }
            c.fixed = maxOf(c.fixed, seen)
        }
        return map.values.toList()
    }

    /** ドライブの辞書ドキュメント（HTML）を読む */
    fun parse(html: String, version: Int): Dictionary {
        val doc = Jsoup.parse(html)
        val a = ArrayList<DictA>()
        val b = ArrayList<DictB>()
        val r = ArrayList<String>()
        var sec = ' '
        for (el in doc.body().select("h1,h2,h3,h4,table,p")) {
            val tag = el.tagName()
            if (tag != "table" && el.parents().any { it.tagName() == "table" }) continue
            if (tag.startsWith("h")) {
                val t = el.text()
                sec = when {
                    t.contains("不採用") -> 'R'
                    t.contains("候補のみ") -> 'B'
                    t.contains("置換") -> 'A'
                    else -> ' '
                }
                continue
            }
            if (tag == "table") {
                val rows = el.select("tr")
                for (row in rows) {
                    val c = row.select("td,th").map { it.text().trim() }
                    if (c.isEmpty() || c[0] == "速記録の語") continue
                    if (sec == 'A' && c.size >= 2) {
                        a.add(DictA(c[0], c[1], c.getOrElse(2) { "" }))
                    } else if (sec == 'B' && c.size >= 2) {
                        b.add(DictB(c[0], c[1], c.getOrElse(2) { "" }, c.getOrElse(3) { "" }))
                    }
                }
            } else if (sec == 'R') {
                val t = el.text().trim()
                if (t.isNotEmpty()) r.add(t)
            }
        }
        return Dictionary(version, a, b, r)
    }

    private const val TH =
        "style=\"background-color:#007086;color:#FFFFFF;font-weight:bold;" +
            "border:1px solid #D9D9D9;padding:4px 8px\""

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun table(head: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder("<table style=\"border-collapse:collapse\">\n<tr>")
        head.forEach { sb.append("<th $TH>${esc(it)}</th>") }
        sb.append("</tr>\n")
        rows.forEachIndexed { i, r ->
            val bg = if (i % 2 == 1) "background-color:#E0F2F6;" else ""
            sb.append("<tr>")
            r.forEach {
                sb.append(
                    "<td style=\"${bg}border:1px solid #D9D9D9;padding:4px 8px\">${esc(it)}</td>"
                )
            }
            sb.append("</tr>\n")
        }
        sb.append("</table>")
        return sb.toString()
    }

    /** 新しい版の辞書ドキュメント（HTML）を作る */
    fun build(d: Dictionary, date: String, note: String): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta charset=\"utf-8\"></head><body>\n")
        sb.append(
            "<h1 style=\"color:#007086;border-bottom:2px solid #0096B2\">" +
                "用語辞書　承認済み　v${d.version}</h1>\n"
        )
        sb.append(
            "<p style=\"color:#595959\">版：v${d.version}　／　承認日：${esc(date)}　／　" +
                "承認者：Iさん　／　元データ：${esc(note)}</p>\n"
        )
        sb.append(
            "<p style=\"color:#595959\">辞書を更新したら、新しい版を別ドキュメントとして作ります。" +
                "この版は上書きしません。</p>\n"
        )
        sb.append("<h2 style=\"color:#007086\">A　置換する</h2>\n")
        sb.append(table(listOf("速記録の語", "置換後", "承認日"), d.a.map { listOf(it.from, it.to, it.date) }))
        sb.append("\n<h2 style=\"color:#007086\">B　候補のみ（本文は直さない）</h2>\n")
        sb.append(
            table(
                listOf("速記録の語", "候補", "載せる条件", "承認日"),
                d.b.map { listOf(it.from, it.cand, it.cond.ifBlank { "常に" }, it.date) },
            )
        )
        sb.append("\n<h2 style=\"color:#007086\">不採用の記録</h2>\n")
        if (d.rejected.isEmpty()) sb.append("<p>なし</p>\n")
        d.rejected.forEach { sb.append("<p>${esc(it)}</p>\n") }
        sb.append("</body></html>")
        return sb.toString()
    }
}
