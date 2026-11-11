package com.kojiiino.sokkiroku

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.IdentityHashMap

enum class Status { PENDING, FIXED, KEEP }

/** 速記録の中の要確認箇所1件 */
class Item(
    val id: Int,
    val label: String,
    val isGray: Boolean,
    val isFumei: Boolean,
    val wordNode: TextNode,
    val wordStart: Int,
    val wordEnd: Int,
    val original: String,
    val tagNode: TextNode,
    val tagStart: Int,
    val tagEnd: Int,
    val before: String,
    val mid: String,
    val after: String,
    val searchKey: String,
    /** 本文の先頭からの文字位置（タグを除く）。録音の時刻の見積もりに使う */
    val pos: Int,
) {
    var status by mutableStateOf(Status.PENDING)
    var value by mutableStateOf(if (isFumei) "" else original)
}

class ParsedDoc(
    val html: String,
    val doc: Document,
    val items: List<Item>,
    /** 本文の文字数（タグを除く） */
    val totalChars: Int,
    /** 本文の文字列（タグを除く）。置き換えの件数を数えるのに使う */
    val bodyText: String,
    /** 本文の文字ノード。置き換えを本文全体に当てるのに使う */
    val bodyNodes: List<TextNode>,
) {
    fun count(word: String): Int {
        if (word.isEmpty()) return 0
        var n = 0
        var i = bodyText.indexOf(word)
        while (i >= 0) {
            n++
            i = bodyText.indexOf(word, i + word.length)
        }
        return n
    }
}

private data class Edit(val start: Int, val end: Int, val repl: String)

object SokkiParser {
    // [要確認:人名の可能性] / [不明]（全角の括弧・コロンにも対応）
    private val TAG_RE =
        Regex("""[\[［](?:要確認[:：]([^\]］]*)|(不明))[\]］]""")
    private const val BLOCKS = "p, li, td, th, h1, h2, h3, h4, h5, h6"
    private const val PUNCT = "。、，．？！?!「」『』（）() 　\n"
    private const val CTX_MAX = 120

    fun parse(html: String): ParsedDoc {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<Item>()
        var id = 0
        val blocks = doc.body().select(BLOCKS).filter { it.select(BLOCKS).size == 1 }
        val isHeading = { e: Element -> e.tagName().matches(Regex("h[1-6]")) }
        // 「本文」の見出しがあれば、そこから文字数を数える
        var inBody = blocks.none { isHeading(it) && it.text().trim() == "本文" }
        var cum = 0
        val bodySb = StringBuilder()
        val bodyNodes = mutableListOf<TextNode>()
        for (b in blocks) {
            // 処理メモ以降は本文ではないので対象外
            if (isHeading(b) && b.text().contains("処理メモ")) break
            if (isHeading(b) && b.text().trim() == "本文") {
                inBody = true
                continue
            }
            val nodes = mutableListOf<TextNode>()
            collect(b, nodes)
            if (nodes.isEmpty()) continue
            val offsets = IntArray(nodes.size)
            val sb = StringBuilder()
            nodes.forEachIndexed { i, n ->
                offsets[i] = sb.length
                sb.append(n.wholeText)
            }
            val para = sb.toString()
            val paraBase = cum
            if (inBody) {
                val plain = TAG_RE.replace(para, "")
                cum += plain.length
                bodySb.append(plain).append('\n')
                bodyNodes.addAll(nodes)
            }
            if (!TAG_RE.containsMatchIn(para)) continue

            nodes.forEachIndexed { i, n ->
                val t = n.wholeText
                var segStart = 0
                for (m in TAG_RE.findAll(t)) {
                    val isFumei = m.groupValues[2].isNotEmpty()
                    val label = if (isFumei) "不明"
                    else m.groupValues[1].trim().ifEmpty { "要確認" }
                    val isGray = label.contains("誤変換")
                    var wNode = n
                    var wNodeIndex = i
                    var ws = segStart
                    var we = m.range.first
                    // 灰色のタグは、直前の文字列（別の文字装飾）に語がある
                    if (isGray && ws == we && i > 0) {
                        val prev = nodes[i - 1]
                        val pt = prev.wholeText
                        val lastTag = TAG_RE.findAll(pt).lastOrNull()
                            ?.range?.last?.plus(1) ?: 0
                        var k = pt.length
                        while (k > lastTag && pt.length - k < 12 && pt[k - 1] !in PUNCT) k--
                        wNode = prev
                        wNodeIndex = i - 1
                        ws = k
                        we = pt.length
                    }
                    val original = wNode.wholeText.substring(ws, we)
                    val absStart = offsets[wNodeIndex] + ws
                    val absEnd = offsets[i] + m.range.last + 1
                    val (before, after) = context(para, absStart, absEnd)
                    items += Item(
                        id = id++,
                        label = label,
                        isGray = isGray,
                        isFumei = isFumei,
                        wordNode = wNode,
                        wordStart = ws,
                        wordEnd = we,
                        original = original,
                        tagNode = n,
                        tagStart = m.range.first,
                        tagEnd = m.range.last + 1,
                        before = before,
                        mid = para.substring(absStart, absEnd),
                        after = after,
                        searchKey = searchKey(before, after),
                        pos = paraBase + TAG_RE.replace(para.substring(0, absStart), "").length,
                    )
                    segStart = m.range.last + 1
                }
            }
        }
        return ParsedDoc(html, doc, items, cum, bodySb.toString(), bodyNodes)
    }

    /** 修正内容を反映したHTMLを作る。元のHTMLから読み直して当てるので、何度呼んでも安全 */
    fun build(
        html: String,
        decisions: List<Pair<Status, String>>,
        rules: List<Pair<String, String>> = emptyList(),
    ): String {
        val pd = parse(html)
        val edits = IdentityHashMap<TextNode, MutableList<Edit>>()
        fun add(n: TextNode, s: Int, e: Int, r: String) {
            edits.getOrPut(n) { mutableListOf() }.add(Edit(s, e, r))
        }
        val resolvedTagNodes = mutableListOf<TextNode>()
        val pendingTagNodes = IdentityHashMap<TextNode, Boolean>()

        pd.items.forEachIndexed { i, it ->
            val (st, v) = decisions.getOrElse(i) { Status.PENDING to "" }
            when (st) {
                Status.PENDING -> pendingTagNodes[it.tagNode] = true
                Status.KEEP -> if (it.isFumei) {
                    pendingTagNodes[it.tagNode] = true
                } else {
                    add(it.tagNode, it.tagStart, it.tagEnd, "")
                    resolvedTagNodes += it.tagNode
                }
                Status.FIXED -> {
                    add(it.wordNode, it.wordStart, it.wordEnd, v)
                    add(it.tagNode, it.tagStart, it.tagEnd, "")
                    resolvedTagNodes += it.tagNode
                }
            }
        }

        for ((node, list) in edits) {
            var t = node.wholeText
            val sorted = list.sortedWith(
                compareByDescending<Edit> { it.start }.thenByDescending { it.end }
            )
            for (e in sorted) t = t.substring(0, e.start) + e.repl + t.substring(e.end)
            node.text(t)
        }

        // 置き換えを本文全体に当てる（置き換え後の語が元の語を含むときは、二重に当たるので本文には当てない）
        for ((from, to) in rules) {
            if (from.isEmpty() || to.contains(from)) continue
            for (n in pd.bodyNodes) {
                val w = n.wholeText
                if (w.contains(from)) n.text(w.replace(from, to))
            }
        }

        // 直した箇所は赤字・黄色の装飾を外す（未処理が残る装飾はそのまま）
        for (n in resolvedTagNodes) {
            if (pendingTagNodes.containsKey(n)) continue
            val p = n.parent()
            if (p is Element && p.tagName() == "span" && p.childNodeSize() == 1) {
                p.removeAttr("class")
                p.removeAttr("style")
            }
        }
        return pd.doc.outerHtml()
    }

    /** 直す前と後の違う部分を取り出す。1文字だと他の語に当たりやすいので、2文字以上に広げる */
    fun wordDiff(a: String, b: String): Pair<String, String> {
        var p = 0
        while (p < a.length && p < b.length && a[p] == b[p]) p++
        var s = 0
        while (s < a.length - p && s < b.length - p && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
        var ps = p
        var se = s
        while (a.length - ps - se < 2 && (ps > 0 || se > 0)) {
            if (ps > 0) ps-- else se--
        }
        return a.substring(ps, a.length - se) to b.substring(ps, b.length - se)
    }

    private fun collect(n: Node, out: MutableList<TextNode>) {
        for (c in n.childNodes()) {
            if (c is TextNode) out.add(c) else collect(c, out)
        }
    }

    /** 前後1文ずつ（長すぎるときは120字まで） */
    private fun context(p: String, s: Int, e: Int): Pair<String, String> {
        var start = 0
        var found = 0
        var k = s - 1
        while (k >= 0) {
            if (p[k] == '。') {
                found++
                if (found == 2) {
                    start = k + 1
                    break
                }
            }
            k--
        }
        if (s - start > CTX_MAX) start = s - CTX_MAX

        var end = p.length
        found = 0
        k = e
        while (k < p.length) {
            if (p[k] == '。') {
                found++
                if (found == 2) {
                    end = k + 1
                    break
                }
            }
            k++
        }
        if (end - e > CTX_MAX) end = e + CTX_MAX
        return p.substring(start, s) to p.substring(e, end)
    }

    /** レコーダーで検索するための語。直前の文字列から取り、短ければ直後から取る */
    private fun searchKey(before: String, after: String): String {
        val b = TAG_RE.replace(before, " ").substringAfterLast('。').trim()
        val key = b.substringAfterLast(' ').takeLast(8)
        if (key.length >= 4) return key
        val a = TAG_RE.replace(after, " ").trim().substringBefore(' ')
        return a.take(8).ifEmpty { key }
    }
}
