package com.example.instareels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import java.io.File

@Composable
fun AppRoot(vm: MainViewModel) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { MainScreen(vm) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("تحميل", "السجل", "الإعدادات")
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(title = { Text("InstaReels Saver") })
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> HomeTab(vm)
            1 -> HistoryTab()
            else -> SettingsTab()
        }
    }
}

@Composable
fun HomeTab(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val active by Repo.active.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = vm.input, onValueChange = { vm.input = it },
            label = { Text("رابط Instagram") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
            trailingIcon = { TextButton(onClick = { clipboard.getText()?.text?.let { vm.input = it } }) { Text("لصق") } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.process(vm.input, false) }, modifier = Modifier.weight(1f)) { Text("معاينة") }
            Button(onClick = { vm.process(vm.input, true) }, modifier = Modifier.weight(1f)) { Text("تحميل فوري") }
        }

        when (val s = state) {
            UiState.Idle -> {}
            UiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Text("جارٍ استخراج الفيديو...")
            }
            is UiState.Error -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.message)
                    Button(onClick = { vm.retry() }) { Text("إعادة المحاولة") }
                }
            }
            is UiState.Preview -> Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(s.info.thumbnail, null, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    Text("@" + s.info.title, style = MaterialTheme.typography.titleMedium)
                    Text("الحجم: " + (s.info.size?.let { formatSize(it) } ?: "غير معروف"))
                    Button(onClick = { vm.startDownload(s.info) }, modifier = Modifier.fillMaxWidth()) { Text("بدء التحميل") }
                }
            }
        }

        active?.let { a ->
            Card {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(a.thumb, null, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(a.title, maxLines = 1)
                        if (a.progress >= 0) {
                            LinearProgressIndicator(progress = { a.progress }, Modifier.fillMaxWidth())
                            Text("${(a.progress * 100).toInt()}%  •  ${formatSize(a.done)} / ${formatSize(a.total)}")
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryTab() {
    val ctx = LocalContext.current
    val items by Repo.history.collectAsStateWithLifecycle()
    var playing by remember { mutableStateOf<HistoryItem?>(null) }
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد تحميلات بعد") }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { item ->
            Card {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(Uri.parse(item.uri), null, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), imageLoader = loader, contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f)) {
                        Text(item.title, maxLines = 1)
                        Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { playing = item }) { Icon(Icons.Default.PlayArrow, "تشغيل") }
                    IconButton(onClick = { shareVideo(ctx, item) }) { Icon(Icons.Default.Share, "مشاركة") }
                    IconButton(onClick = { deleteItem(ctx, item) }) { Icon(Icons.Default.Delete, "حذف") }
                }
            }
        }
    }
    playing?.let { item ->
        Dialog(onDismissRequest = { playing = null }) {
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        setMediaController(MediaController(c).also { it.setAnchorView(this) })
                        setVideoURI(Uri.parse(item.uri)); setOnPreparedListener { start() }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(480.dp),
            )
        }
    }
}

@Composable
fun SettingsTab() {
    var auto by remember { mutableStateOf(Repo.prefs.autoDownload) }
    var backend by remember { mutableStateOf(Repo.prefs.backendUrl) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("التحميل السريع التلقائي", style = MaterialTheme.typography.titleMedium)
                Text("يبدأ التحميل فور المشاركة من Instagram", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = auto, onCheckedChange = { auto = it; Repo.prefs.autoDownload = it })
        }
        OutlinedTextField(
            value = backend, onValueChange = { backend = it; Repo.prefs.backendUrl = it },
            label = { Text("رابط خدمة الاستخراج (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("مثال: https://your-server.com/extract — يُستخدم أولاً، ثم الاستخراج المحلي كبديل") },
        )
    }
}

// ---- helpers ----
fun formatSize(b: Long): String = when {
    b <= 0 -> "—"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / 1048576.0)
}

fun shareVideo(ctx: Context, item: HistoryItem) {
    val uri = Uri.parse(item.uri)
    val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "مشاركة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun deleteItem(ctx: Context, item: HistoryItem) {
    runCatching {
        val u = Uri.parse(item.uri)
        if (u.scheme == "file") File(u.path!!).delete() else ctx.contentResolver.delete(u, null, null)
    }
    Repo.removeHistory(item.id)
}
