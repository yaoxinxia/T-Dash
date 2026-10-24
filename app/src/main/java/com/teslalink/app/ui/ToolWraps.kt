package com.teslalink.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.teslalink.app.AppContainer
import com.teslalink.app.tools.WrapImage
import com.teslalink.app.tools.WrapImport
import com.teslalink.app.tools.WrapStore
import com.teslalink.app.ui.components.ActionTile
import com.teslalink.app.ui.components.CollapsibleSection
import com.teslalink.app.ui.components.HintBar
import com.teslalink.app.ui.components.Pill
import com.teslalink.app.ui.components.SectionLabel
import com.teslalink.app.ui.components.Tone
import com.teslalink.app.ui.theme.TL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车机皮肤管理（喷漆车间 / U 盘根目录 Wraps）。
 * 只碰 Wraps 这一个目录，不动记录仪文件；写权限由他自己选目录时授予。
 */
@Composable
fun WrapsSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var treeUri by remember { mutableStateOf<String?>(null) }
    var dir by remember { mutableStateOf<DocumentFile?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<WrapImage>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var refreshAt by remember { mutableLongStateOf(0L) }
    var note by remember { mutableStateOf<Pair<Tone, String>?>(null) }
    var results by remember { mutableStateOf<List<WrapImport>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WrapImage?>(null) }

    LaunchedEffect(Unit) { treeUri = container.prefs.getString("wraps_tree") }

    // 列目录要读每张图的头 + 走 SAF，全在 IO 线程做。
    LaunchedEffect(treeUri, refreshAt) {
        val raw = treeUri
        if (raw == null) {
            dir = null; items = emptyList(); return@LaunchedEffect
        }
        scanning = true
        // 拔盘/授权过期时 SAF 会抛 SecurityException：读目录的意外不能带走 app，
        // 但要把原因写在页面上，不能只留一个空列表。
        val triple: Triple<String?, DocumentFile?, List<WrapImage>>? = try {
            withContext(Dispatchers.IO) {
                val u = Uri.parse(raw)
                val picked = DocumentFile.fromTreeUri(context, u)
                val d = WrapStore.wrapsDir(context, u)
                Triple(picked?.name, d, d?.let { WrapStore.list(context, it) } ?: emptyList())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.teslalink.app.debug.DebugLog.error(
                "wraps", "列目录异常：${e.javaClass.name}: ${e.message}", e,
            )
            note = Tone.Bad to "读取 U 盘失败：${e.javaClass.simpleName} ${e.message ?: ""}——" +
                "盘还在的话点「重新读取」，不行就重新授权一次目录"
            null
        }
        if (triple != null) {
            pickedName = triple.first
            dir = triple.second
            items = triple.third
        }
        scanning = false
    }

    // U 盘目录：读+写一起要（导入/删除都得写）。不支持写时退回只读并说明。
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 换盘前释放旧授权：persistable grant 有系统级上限（~128 条），反复选盘不释放
        // 会被静默逐出最旧的——逐出的可能正是 prefs 里还存着的那个 treeUri。
        treeUri?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val writable = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        val granted = if (writable) writable else runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        }.isSuccess
        if (!granted) {
            note = Tone.Bad to "系统没给这个盘授权（SAF 授权失败），换一次目录试试"
            return@rememberLauncherForActivityResult
        }
        note = if (writable) Tone.Good to "已授权读写：可以看、能导入、能删"
        else Tone.Warn to "这个盘只肯给只读：能看不能改（换 exFAT/NTFS 驱动或重插一次）"
        scope.launch { container.prefs.putString("wraps_tree", uri.toString()) }
        treeUri = uri.toString()
        results = emptyList()
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val d = dir
        if (d == null) {
            note = Tone.Bad to "还没有 Wraps 目录，先导一个目录出来"
            return@rememberLauncherForActivityResult
        }
        if (busy) {
            // 重入保护：上一批还在压图/写盘时再选一批，两路并发写同一个目录会互相踩。
            note = Tone.Warn to "上一批还在导入，等它完成再选图"
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                uris.map { WrapStore.import(context, d, it) }
            }
            results = out + results
            busy = false
            refreshAt = System.currentTimeMillis()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("车机皮肤", "U 盘根目录 Wraps · 玩具箱 → 喷漆车间 → Wraps")

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(if (treeUri == null) "未选 U 盘" else "U 盘已连接", if (treeUri == null) Tone.Neutral else Tone.Good)
            if (dir != null) {
                val bad = items.count { it.problem != null }
                Pill(
                    "${items.size}/${WrapStore.MAX_COUNT} 张",
                    if (items.isEmpty()) Tone.Neutral else Tone.Data,
                )
                if (bad > 0) Pill("$bad 张不合规", Tone.Bad) else if (items.isNotEmpty()) Pill("全部可用", Tone.Good)
                if (items.size > WrapStore.MAX_COUNT) Pill("超出 10 张", Tone.Warn)
            }
        }
        if (scanning || busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = TL.accent)

        ActionTile(
            if (treeUri == null) "选择 U 盘根目录" else "换 U 盘 / 重新授权",
            onClick = { treePicker.launch(null) },
            modifier = Modifier.fillMaxWidth(),
            emphasize = treeUri == null,
            tone = Tone.Info,
            stateLine = "要读写授权才能导入和删图（只给读也能看）",
        )

        if (treeUri != null && dir == null && !scanning) {
            if (pickedName.equals("TeslaCam", ignoreCase = true)) {
                // 皮肤只认 U 盘根目录下的 Wraps；建到 TeslaCam 里车机扫不到，反而多一个垃圾目录。
                HintBar(
                    "你选的是 TeslaCam（记录仪那步的习惯）。皮肤目录必须在 U 盘**根目录**下，" +
                        "请重新选择上一级——也就是这个盘本身。",
                    Tone.Bad,
                )
            } else {
                HintBar("这个盘根目录里没有 Wraps 文件夹。点下面这个按钮就地建一个（只建 Wraps，不碰别的）。", Tone.Warn)
                ActionTile(
                    "在 U 盘根目录创建 Wraps",
                    onClick = {
                        scope.launch {
                            val raw = treeUri ?: return@launch
                            val created = withContext(Dispatchers.IO) {
                                WrapStore.createWrapsDir(context, Uri.parse(raw))
                            }
                            note = if (created != null) Tone.Good to "已创建 Wraps/"
                            else Tone.Bad to "创建失败：这个文件系统拒绝了写请求（U 盘只读开关？NTFS 驱动？）"
                            refreshAt = System.currentTimeMillis()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    tone = Tone.Info,
                )
            }
        }

        if (dir != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(
                    "从相册导入",
                    onClick = { importer.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                    emphasize = true,
                    tone = Tone.Info,
                    stateLine = "自动居中裁方 + 压到 512~1024 的 PNG",
                )
                ActionTile(
                    "重新扫描",
                    onClick = { refreshAt = System.currentTimeMillis() },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        note?.let { HintBar(it.second, it.first) }

        results.forEach { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (r.ok) TL.good.copy(alpha = 0.08f) else TL.bad.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (r.ok) "✓" else "✕", color = if (r.ok) TL.good else TL.bad, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(r.name, style = MaterialTheme.typography.bodyMedium, color = TL.textHi)
                    Text(r.detail, style = MaterialTheme.typography.labelSmall, color = TL.textMid)
                }
            }
        }

        items.forEach { img ->
            val bad = img.problem
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (bad == null) TL.raised else TL.bad.copy(alpha = 0.10f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkinThumb(img.uri, Modifier.size(56.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(img.name, style = MaterialTheme.typography.bodyMedium, color = TL.textHi)
                    Text(
                        buildString {
                            if (img.width > 0) append("${img.width}×${img.height} · ")
                            append("%.0f KB".format(img.sizeBytes / 1024f))
                            if (img.modifiedMs > 0) {
                                append(" · ")
                                append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(img.modifiedMs)))
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bad == null) TL.textLow else TL.bad,
                    )
                    bad?.let { Text("车机不会显示：$it", style = MaterialTheme.typography.labelSmall, color = TL.bad) }
                }
                if (pendingDelete == img) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("确认删除", Tone.Bad, selected = true) {
                            scope.launch {
                                val err = withContext(Dispatchers.IO) { WrapStore.deleteImage(context, img) }
                                note = if (err == null) Tone.Good to "已删除 ${img.name}"
                                else Tone.Bad to "删除失败：${img.name}——$err"
                                pendingDelete = null
                                refreshAt = System.currentTimeMillis()
                            }
                        }
                        Pill("取消", Tone.Neutral, selected = true) { pendingDelete = null }
                    }
                } else {
                    Pill("删除", Tone.Neutral, selected = true) { pendingDelete = img }
                }
            }
        }

        CollapsibleSection(
            "皮肤规则（官方口径 + 你盘里实测）",
            if (dir == null) "先选 U 盘才看具体合规情况"
            else "${items.count { it.problem == null }} 张能上、${items.count { it.problem != null }} 张不行",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "· U 盘根目录建 Wraps（大写 W），里面放 PNG，车机只认这个目录",
                    style = MaterialTheme.typography.bodySmall, color = TL.textMid,
                )
                Text(
                    "· 必须正方形，边长 512~1024 px，单张 ≤1 MB，最多 10 张",
                    style = MaterialTheme.typography.bodySmall, color = TL.textMid,
                )
                Text(
                    "· 你这块盘里三张全是 1024×1024 RGBA PNG（188~798 KB），文件名车机不会改，" +
                        "那个 54978661417222145.png 是下载时自带的编号",
                    style = MaterialTheme.typography.bodySmall, color = TL.textLow,
                )
                Text(
                    "· 导入时本工具会：居中裁成正方形 → 按 1024/896/768/640/512 逐档压 PNG，" +
                        "取第一个不超 1 MB 的档位写入；原图短边不足 512 直接拒（放大没意义）",
                    style = MaterialTheme.typography.bodySmall, color = TL.textMid,
                )
                Text(
                    "· 应用：U 盘插回手套箱 → 车机 玩具箱 → 喷漆车间 → Wraps 页签；" +
                        "Tesla App 4.59+ 也能自己传（创作 → 车衣 → 上传），两条路都绕不开车机确认",
                    style = MaterialTheme.typography.bodySmall, color = TL.textMid,
                )
                Text(
                    "· 删除是真删 U 盘上的文件，删前会二次确认；本工具不会动 TeslaCam 里的记录仪数据",
                    style = MaterialTheme.typography.bodySmall, color = TL.warn,
                )
            }
        }
    }
}

/** 皮肤缩略图：这些图是 1024×1024 的整图，按边界探测后再采样，别整张进内存。 */
@Composable
private fun SkinThumb(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, probe) }
                var sample = 1
                while (probe.outWidth / (sample * 2) >= 112) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }.getOrNull()?.asImageBitmap()
        }
    }
    Box(modifier.background(TL.base), contentAlignment = Alignment.Center) {
        bmp?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
