package com.kojiiino.sokkiroku

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 営業部デザイン基準v2の色
val FutureBlue = Color(0xFF0099B3)
val Navy = Color(0xFF006680)
val Blue80 = Color(0xFFCCEBF0)
val TitleBg = Color(0xFFDFECEF)
val Ink = Color(0xFF1C1C1C)
val Accent = Color(0xFFEB3503)
val Mark = Color(0xFFFCE7CC)
val Gray90 = Color(0xFF5A5A5A)

const val RECORDER_PKG = "com.google.android.apps.recorder"

@Composable
fun SokkiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = FutureBlue,
            onPrimary = Color.White,
            primaryContainer = Blue80,
            onPrimaryContainer = Navy,
            secondary = Navy,
            error = Accent,
            background = Color.White,
            surface = Color.White,
            onBackground = Ink,
            onSurface = Ink,
        ),
        content = content,
    )
}

@Composable
fun App(vm: MainViewModel) {
    when (vm.screen) {
        Screen.Files -> FilesScreen(vm)
        Screen.Edit -> EditScreen(vm)
    }
    val msg = vm.message
    if (msg != null) {
        AlertDialog(
            onDismissRequest = { vm.message = null },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("閉じる") } },
            title = { Text("うまくいきませんでした") },
            text = { Text(msg) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun barColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Navy,
    titleContentColor = Color.White,
    navigationIconContentColor = Color.White,
    actionIconContentColor = Color.White,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) {
        if (!vm.loadedOnce) vm.loadFiles()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("速記録の修正") },
                colors = barColors(),
                actions = {
                    TextButton(onClick = { vm.loadFiles() }, enabled = !vm.busy) {
                        Text("再読込", color = Color.White)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "「${Drive.FOLDER_NAME}」フォルダの速記録",
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = Gray90,
                fontSize = 13.sp,
            )
            val names = vm.files.map { it.name }
            val list = vm.files.filter { !it.name.contains("修正済") }
            if (list.isEmpty() && vm.loadedOnce && !vm.busy) {
                Text("速記録が見つかりません。", Modifier.padding(16.dp))
            }
            if (!vm.loadedOnce && !vm.busy) {
                Button(onClick = { vm.loadFiles() }, modifier = Modifier.padding(16.dp)) {
                    Text("ドライブから読み込む")
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { f ->
                    val done = names.any { it.startsWith(f.name + "_修正済") }
                    Card(
                        onClick = { vm.open(f) },
                        enabled = !vm.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Blue80),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(f.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatTime(f.modified), color = Gray90, fontSize = 13.sp)
                                if (done) {
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "修正済あり",
                                        color = Navy,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: MainViewModel) {
    val pd = vm.parsed ?: return
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var confirmBack by remember { mutableStateOf(false) }

    val visible = pd.items.filter { vm.showGray || !it.isGray }
    val pendingCount = visible.count { it.status == Status.PENDING }
    val touched = pd.items.any { it.status != Status.PENDING }

    fun goBack() {
        if (touched && vm.savedName == null) confirmBack = true else vm.backToFiles()
    }
    BackHandler { goBack() }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = { goBack() }) { Text("戻る", color = Color.White) }
                },
                title = {
                    Text(
                        vm.current?.name ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp,
                    )
                },
                colors = barColors(),
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = TitleBg) {
                Text(
                    "未処理 $pendingCount 件／全 ${visible.size} 件",
                    Modifier.weight(1f).padding(start = 16.dp),
                    color = Ink,
                    fontSize = 15.sp,
                )
                Button(
                    onClick = { vm.save() },
                    enabled = !vm.busy && touched,
                    modifier = Modifier.padding(end = 12.dp),
                ) { Text("保存") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("灰色（語の誤変換の疑い）も表示", Modifier.weight(1f), fontSize = 14.sp)
                Switch(checked = vm.showGray, onCheckedChange = { vm.showGray = it })
            }
            if (visible.isEmpty()) {
                Text("直す箇所はありません。", Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { item ->
                    ItemCard(item) { openRecorder(ctx, item.searchKey, clip) }
                }
            }
        }
    }

    if (confirmBack) {
        AlertDialog(
            onDismissRequest = { confirmBack = false },
            title = { Text("保存していません") },
            text = { Text("直した内容は消えます。戻りますか。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBack = false
                    vm.backToFiles()
                }) { Text("保存せずに戻る") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBack = false }) { Text("続ける") }
            },
        )
    }

    val saved = vm.savedName
    if (saved != null) {
        val ask = "「$saved」を議事録にして"
        AlertDialog(
            onDismissRequest = {},
            title = { Text("保存しました") },
            text = {
                Text(
                    "「$saved」として保存しました。元の速記録はそのまま残っています。\n\n" +
                        "Claudeアプリで次のように頼んでください。\n$ask"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clip.setText(AnnotatedString(ask))
                    Toast.makeText(ctx, "依頼文をコピーしました", Toast.LENGTH_SHORT).show()
                    vm.finishSaved()
                }) { Text("依頼文をコピー") }
            },
            dismissButton = {
                TextButton(onClick = { vm.finishSaved() }) { Text("閉じる") }
            },
        )
    }
}

@Composable
fun ItemCard(item: Item, onRecorder: () -> Unit) {
    val accent = if (item.isGray) Gray90 else Accent
    val pending = item.status == Status.PENDING
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = if (pending) Color.White else TitleBg),
        border = BorderStroke(1.dp, if (pending) accent else Blue80),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.label,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(accent).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(statusText(item.status), fontSize = 12.sp, color = Navy)
            }
            Spacer(Modifier.height(8.dp))
            Text(contextText(item), fontSize = 15.sp, lineHeight = 24.sp, color = Ink)
            Spacer(Modifier.height(8.dp))
            if (pending) {
                OutlinedTextField(
                    value = item.value,
                    onValueChange = { item.value = it },
                    label = { Text(if (item.isFumei) "聞き取った内容" else "正しい語") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { item.status = Status.FIXED },
                        enabled = !(item.isFumei && item.value.isBlank()),
                    ) { Text("直す") }
                    OutlinedButton(onClick = { item.status = Status.KEEP }) {
                        Text(if (item.isFumei) "不明のまま" else "このまま")
                    }
                    TextButton(onClick = onRecorder) { Text("録音") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(resultText(item), Modifier.weight(1f), fontSize = 14.sp, color = Ink)
                    TextButton(onClick = { item.status = Status.PENDING }) { Text("やり直す") }
                }
            }
        }
    }
}

private fun statusText(s: Status) = when (s) {
    Status.PENDING -> "未処理"
    Status.FIXED -> "修正済"
    Status.KEEP -> "このまま"
}

private fun resultText(item: Item): String = when (item.status) {
    Status.FIXED ->
        if (item.value.isEmpty()) "この語を削除します" else "「${item.value}」に直します"
    Status.KEEP ->
        if (item.isFumei) "［不明］のまま残します"
        else "「${item.original}」のまま、印だけ消します"
    Status.PENDING -> ""
}

private fun contextText(item: Item): AnnotatedString = buildAnnotatedString {
    append(item.before)
    withStyle(
        SpanStyle(
            color = if (item.isGray) Gray90 else Accent,
            fontWeight = FontWeight.Bold,
            background = Mark,
        )
    ) { append(item.mid) }
    append(item.after)
}

private fun openRecorder(ctx: Context, key: String, clip: ClipboardManager) {
    if (key.isNotBlank()) clip.setText(AnnotatedString(key))
    val intent = ctx.packageManager.getLaunchIntentForPackage(RECORDER_PKG)
    if (intent == null) {
        Toast.makeText(ctx, "レコーダーが見つかりません", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(
        ctx,
        "「$key」をコピーしました。レコーダーで録音を開き、検索に貼り付けてください",
        Toast.LENGTH_LONG,
    ).show()
    ctx.startActivity(intent)
}

private fun formatTime(s: String): String = try {
    val t = OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault())
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").format(t)
} catch (e: Exception) {
    s
}
