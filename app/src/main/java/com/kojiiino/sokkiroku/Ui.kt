package com.kojiiino.sokkiroku

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
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            if (vm.receivedPending) {
                Text(
                    "録音を受け取りました。この録音を使う速記録を選んでください。",
                    Modifier.fillMaxWidth().background(Mark).padding(12.dp),
                    color = Ink,
                    fontSize = 14.sp,
                )
            }
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
            AudioPanel(vm)
            RulesPanel(vm)
            if (visible.isEmpty()) {
                Text("直す箇所はありません。", Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { item ->
                    ItemCard(
                        item = item,
                        onFix = { vm.fixItem(item) },
                        estimate = vm.estimate(item.pos),
                        audioReady = vm.audioReady,
                        onPlay = { vm.playItem(item) },
                        onAnchor = {
                            vm.setAnchor(item)
                            Toast.makeText(ctx, "基準にしました", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }

    vm.proposal?.let { ProposalDialog(vm, it) }

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
fun AudioPanel(vm: MainViewModel) {
    var showAdjust by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(vm.audioReady) {
        while (vm.audioReady) {
            pos = vm.player.position()
            delay(500)
        }
    }
    Column(
        Modifier.fillMaxWidth().background(TitleBg).padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (vm.audioReady) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.player.skip(-10_000) }) { Text("－10秒") }
                Spacer(Modifier.width(6.dp))
                Button(onClick = { vm.player.toggle() }) {
                    Text(if (vm.player.playing) "一時停止" else "再生")
                }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { vm.player.skip(10_000) }) { Text("＋10秒") }
                Spacer(Modifier.weight(1f))
                Text(
                    "${fmtTime(pos)}／${fmtTime(vm.durationMs ?: 0L)}",
                    fontSize = 13.sp,
                    color = Ink,
                )
            }
        } else {
            Text(
                "録音を取り込むと、各箇所を聞けます。レコーダーで録音を開き、" +
                    "「共有 → ファイル → 音声」でこのアプリを選んでください。",
                fontSize = 13.sp,
                color = Ink,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.pickAudio?.invoke() }) { Text("ファイルから選ぶ") }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = vm.manualMinutes,
                    onValueChange = {
                        vm.manualMinutes = it
                        vm.saveDocPrefs()
                    },
                    label = { Text("録音の長さ（分）") },
                    singleLine = true,
                    modifier = Modifier.width(150.dp),
                )
            }
        }
        TextButton(onClick = { showAdjust = !showAdjust }) {
            Text(if (showAdjust) "位置の調整を閉じる" else "位置の調整（パート分けのとき）")
        }
        if (showAdjust) {
            Text(
                "この速記録が録音の途中から始まる場合（パート02など）は、開始と終了を分で入れます。",
                fontSize = 12.sp,
                color = Gray90,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vm.startMin,
                    onValueChange = {
                        vm.startMin = it
                        vm.saveDocPrefs()
                    },
                    label = { Text("開始（分）") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = vm.endMin,
                    onValueChange = {
                        vm.endMin = it
                        vm.saveDocPrefs()
                    },
                    label = { Text("終了（分）") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.resetAnchors() }, enabled = vm.anchors.isNotEmpty()) {
                    Text("基準${vm.anchors.size}件を消す")
                }
            }
        }
    }
}

@Composable
fun RulesPanel(vm: MainViewModel) {
    val pd = vm.parsed ?: return
    if (vm.rules.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(Blue80).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            "この会議の置き換え（保存時に本文全体へ当てます。ほかのパートにも使います）",
            fontSize = 12.sp,
            color = Navy,
        )
        for ((from, to) in vm.rules) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "「$from」→「$to」（このパートに${pd.count(from)}か所）",
                    Modifier.weight(1f),
                    fontSize = 14.sp,
                    color = Ink,
                )
                TextButton(onClick = { vm.removeRule(from) }) { Text("外す") }
            }
        }
    }
}

@Composable
fun ProposalDialog(vm: MainViewModel, p: Proposal) {
    val pd = vm.parsed ?: return
    var from by remember(p) { mutableStateOf(p.from) }
    var to by remember(p) { mutableStateOf(p.to) }
    val others = (pd.count(from) - (if (p.item.original.contains(from)) 1 else 0)).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = { vm.proposal = null },
        title = { Text("ほかの箇所も直しますか") },
        text = {
            Column {
                Text(
                    "このパートのほかの箇所に「$from」が${others}か所あります。" +
                        "すべて「$to」に直しますか。同じ会議のほかのパートを開いたときにも当てます。",
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it },
                    label = { Text("直す前の語") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    label = { Text("直した後の語") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.applyRule(from, to) },
                enabled = from.isNotEmpty() && from != to,
            ) { Text("すべて直す") }
        },
        dismissButton = {
            TextButton(onClick = { vm.proposal = null }) { Text("この箇所だけ") }
        },
    )
}

@Composable
fun ItemCard(
    item: Item,
    onFix: () -> Unit,
    estimate: Long?,
    audioReady: Boolean,
    onPlay: () -> Unit,
    onAnchor: () -> Unit,
) {
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
            if (estimate != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (audioReady) {
                        TextButton(onClick = onPlay) { Text("約${fmtTime(estimate)}から聞く") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onAnchor) { Text("今の再生位置を基準に") }
                    } else {
                        Text(
                            "録音の約${fmtTime(estimate)}あたり",
                            fontSize = 13.sp,
                            color = Navy,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (pending) {
                OutlinedTextField(
                    value = item.value,
                    onValueChange = { item.value = it },
                    label = {
                        Text(
                            when {
                                item.isFumei -> "聞き取った内容"
                                item.isGray -> "直す部分（直前の語も含めて表示）"
                                else -> "正しい語"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onFix,
                        enabled = !(item.isFumei && item.value.isBlank()),
                    ) { Text("直す") }
                    OutlinedButton(onClick = { item.status = Status.KEEP }) {
                        Text(if (item.isFumei) "不明のまま" else "このまま")
                    }
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

private fun formatTime(s: String): String = try {
    val t = OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault())
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").format(t)
} catch (e: Exception) {
    s
}
