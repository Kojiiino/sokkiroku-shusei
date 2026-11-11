package com.kojiiino.sokkiroku

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Google ドライブ API（必要な分だけ） */
object Drive {
    data class DFile(val id: String, val name: String, val modified: String)

    const val FOLDER_NAME = "速記録議事録"
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val DOC_MIME = "application/vnd.google-apps.document"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(
        url: String,
        token: String,
        method: String = "GET",
        contentType: String? = null,
        body: ByteArray? = null,
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("ドライブとの通信でエラーになりました（$code）\n${text.take(300)}")
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    fun findFolder(token: String): String {
        val q = "name = '$FOLDER_NAME' and " +
            "mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val url = "$API?q=${enc(q)}&fields=${enc("files(id,name)")}"
        val a = JSONObject(request(url, token)).optJSONArray("files")
        if (a == null || a.length() == 0) {
            throw IOException("ドライブに「$FOLDER_NAME」フォルダが見つかりません")
        }
        return a.getJSONObject(0).getString("id")
    }

    fun listFiles(token: String, folderId: String): List<DFile> {
        val q = "'$folderId' in parents and trashed = false and " +
            "mimeType = '$DOC_MIME' and name contains '速記録'"
        val url = "$API?q=${enc(q)}" +
            "&fields=${enc("files(id,name,modifiedTime)")}" +
            "&orderBy=${enc("modifiedTime desc")}&pageSize=100"
        val a = JSONObject(request(url, token)).optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            DFile(o.getString("id"), o.getString("name"), o.optString("modifiedTime"))
        }
    }

    fun exportHtml(token: String, fileId: String): String =
        request("$API/$fileId/export?mimeType=${enc("text/html")}", token)

    /** HTMLをGoogleドキュメントに変換して新規保存する */
    fun createDoc(token: String, folderId: String, name: String, html: String): String {
        val b = "sokki" + System.currentTimeMillis()
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", DOC_MIME)
            .put("parents", JSONArray().put(folderId))
            .toString()
        val body = "--$b\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
            "$meta\r\n" +
            "--$b\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n\r\n" +
            "$html\r\n" +
            "--$b--\r\n"
        val res = request(
            "$UPLOAD?uploadType=multipart&fields=id,name",
            token,
            "POST",
            "multipart/related; boundary=$b",
            body.toByteArray(Charsets.UTF_8),
        )
        return JSONObject(res).getString("id")
    }
}
