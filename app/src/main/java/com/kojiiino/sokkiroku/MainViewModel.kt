package com.kojiiino.sokkiroku

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Screen { Files, Edit }

class MainViewModel : ViewModel() {
    var tokenProvider: (suspend () -> String)? = null

    var screen by mutableStateOf(Screen.Files)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var files by mutableStateOf<List<Drive.DFile>>(emptyList())
    var loadedOnce by mutableStateOf(false)
    var current by mutableStateOf<Drive.DFile?>(null)
    var parsed by mutableStateOf<ParsedDoc?>(null)
    var showGray by mutableStateOf(false)
    var savedName by mutableStateOf<String?>(null)

    private var folderId: String? = null

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
        screen = Screen.Files
        parsed = null
        current = null
        savedName = null
    }

    fun finishSaved() {
        backToFiles()
        loadFiles()
    }
}
