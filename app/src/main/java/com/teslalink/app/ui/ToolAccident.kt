package com.teslalink.app.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.teslalink.app.AppContainer
import com.teslalink.app.tools.AccidentHelper
import com.teslalink.app.tools.AccidentLocation
import com.teslalink.app.tools.EMERGENCY_NUMBERS
import com.teslalink.app.tools.INSURANCE_HOTLINES
import com.teslalink.app.tools.PHOTO_TASKS
import com.teslalink.app.ui.components.ActionTile
import com.teslalink.app.ui.components.CollapsibleSection
import com.teslalink.app.ui.components.HintBar
import com.teslalink.app.ui.components.Panel
import com.teslalink.app.ui.components.Pill
import com.teslalink.app.ui.components.SectionLabel
import com.teslalink.app.ui.components.Tone
import com.teslalink.app.ui.theme.TL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 事故处理助手：按 1→6 步走的强引导（大字号、一步一件事），含定位报址、
 * 拍照取证表单（每项可多拍、页内缩略图、点图看原图、单张删除与一键清空）与保险报案。
 */
@Composable
fun AccidentSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var amapKey by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<AccidentLocation?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val profile by container.profile.profile.collectAsState()
    val licenseUri by container.profile.license.collectAsState()
    val vehicleUri by container.profile.vehicle.collectAsState()
    var exporting by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<Pair<Tone, String>?>(null) }
    var permGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // 取证表单：每项一组照片（同类允许多拍），另有一个手动勾选位。
    // 必须是 saveable：拉相机时 vivo 这类 ROM 会回收我们的进程，回来 Map 若不在 Bundle 里就全空了
    // （表现就是"拍完照没有预览"）。
    var shots by rememberSaveable(stateSaver = shotsSaver) { mutableStateOf<Map<String, List<Uri>>>(emptyMap()) }
    var manualDone by rememberSaveable(stateSaver = manualSaver) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var clearArmed by remember { mutableStateOf(false) }
    /** 点开缩略图看大图（app 内浮层，不丢给外部相册），大图里才能移除。 */
    var viewing by remember { mutableStateOf<Pair<String, Uri>?>(null) }

    val capture = rememberCameraCapture { uri, task ->
        shots = shots + (task to (shots[task].orEmpty() + uri))
    }

    fun startLocate() {
        scope.launch {
            locating = true
            error = null
            AccidentHelper.locate(context, amapKey)
                .onSuccess { info = it }
                .onFailure { error = it.message }
            locating = false
        }
    }

    LaunchedEffect(Unit) {
        amapKey = container.prefs.getString("amap_key")?.ifBlank { null }
        if (permGranted) startLocate()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permGranted = granted ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startLocate()
    }

    fun dial(number: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("事故处理", "按 1→6 步做；每步只做一件事")

        BigPanel("第 1 步 · 先保命") {
            BigLine("开双闪，人先离开车道")
            BigLine("三角牌：城市路 50 米外，高速 150 米外")
            BigLine("车上所有人站到护栏外，别留在车里理论")
        }

        BigPanel("第 2 步 · 有没有人受伤") {
            ActionTile(
                "有人受伤 → 120",
                onClick = { dial("120") },
                modifier = Modifier.fillMaxWidth(),
                emphasize = true,
                tone = Tone.Bad,
            )
            Text(
                "有受伤：先 120，再 122，尽量别动车。没人受伤就直接往下走第 3 步。",
                style = MaterialTheme.typography.labelMedium,
                color = TL.textLow,
            )
        }

        BigPanel("第 3 步 · 打 122，把位置说清楚") {
            when {
                locating -> {
                    Text("定位中…（车内首次可能 30 秒）", color = TL.textMid, fontSize = 15.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = TL.accent)
                }
                info != null -> {
                    val r = info!!
                    Text(
                        r.address ?: "暂时无文字地址（坐标见下）",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TL.textHi,
                    )
                    Text(
                        String.format(Locale.US, "GPS %.5f, %.5f", r.lat, r.lng) +
                            (r.accuracyM?.let { " · ±${it.toInt()}m" } ?: "") +
                            " · ${r.geocodeSource}来源" +
                            if (r.fromCache) " · 缓存位置（正在更新）" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (r.fromCache) TL.warn else TL.textLow,
                    )
                    r.geocodeNote?.let {
                        Text("地址翻译：$it", style = MaterialTheme.typography.labelSmall, color = TL.textLow)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionTile(
                            "复制位置",
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "location",
                                        (r.address ?: "事故位置") +
                                            String.format(Locale.US, " GPS %.5f, %.5f", r.lat, r.lng)
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                            emphasize = true,
                            tone = Tone.Bad,
                        )
                        ActionTile("刷新", onClick = { startLocate() }, modifier = Modifier.width(96.dp))
                    }
                    ActionTile(
                        "拨打 122",
                        onClick = { dial("122") },
                        modifier = Modifier.fillMaxWidth(),
                        emphasize = true,
                        tone = Tone.Bad,
                    )
                }
                else -> {
                    HintBar(
                        error ?: "还没拿到位置",
                        if (error != null) Tone.Bad else Tone.Neutral,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionTile(
                            if (permGranted) "重新定位" else "授权定位",
                            onClick = {
                                if (permGranted) startLocate()
                                else permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            },
                            modifier = Modifier.weight(1f),
                            emphasize = true,
                            tone = Tone.Info,
                        )
                        ActionTile(
                            "定位设置",
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            stateLine = "GPS 关着时点这里打开",
                        )
                    }
                }
            }
        }

        // ---- 第 4 步：拍照取证表单 ----
        val total = PHOTO_TASKS.size
        fun itemDone(title: String): Boolean =
            manualDone[title] == true || shots[title].orEmpty().isNotEmpty()
        val doneCount = PHOTO_TASKS.count { itemDone(it.first) }
        val missing = PHOTO_TASKS.filter { !itemDone(it.first) }
        val shotCount = shots.values.sumOf { it.size }

        BigPanel("第 4 步 · 拍照取证（$doneCount/$total 项 · 共 $shotCount 张）") {
            LinearProgressIndicator(
                progress = { doneCount.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
                color = if (missing.isEmpty()) TL.good else TL.accent,
                trackColor = TL.hairline,
            )
            // 上一次拍照的结果就地说，别扔回页面顶部（他在第 4 步根本看不到顶部）。
            capture.note?.let { HintBar(it.second, it.first) }
            exportNote?.let { HintBar(it.second, it.first) }

            // 一键出 PDF：清单 + 现场照片 + 证件照 + 个人信息，直接弹分享面板。
            ActionTile(
                if (exporting) "正在生成 PDF…" else "导出取证清单 PDF",
                onClick = {
                    if (exporting) return@ActionTile
                    exporting = true
                    exportNote = null
                    val shotsCopy = shots.toList()
                    val manualCopy = manualDone.toList()
                    val infoCopy = info
                    val profileCopy = profile
                    val ids = listOfNotNull(
                        licenseUri?.let { "驾驶证" to it },
                        vehicleUri?.let { "行驶证" to it },
                    )
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                com.teslalink.app.tools.EvidencePdf.build(
                                    context = context,
                                    profile = profileCopy,
                                    timeText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                        .format(Date(infoCopy?.fixTimeMs ?: System.currentTimeMillis())),
                                    placeText = infoCopy?.let {
                                        (it.address ?: "无文字地址（见坐标）") +
                                            String.format(
                                                Locale.US, " GPS %.5f, %.5f（%s%s）",
                                                it.lat, it.lng, it.geocodeSource,
                                                if (it.fromCache) " · 缓存位置" else "",
                                            )
                                    } ?: "未定位（PDF 里没有坐标）",
                                    checklist = PHOTO_TASKS.map { task ->
                                        task.first to (shotsCopy.firstOrNull { it.first == task.first }?.second?.isNotEmpty() == true ||
                                            manualCopy.firstOrNull { it.first == task.first }?.second == true)
                                    },
                                    photos = PHOTO_TASKS.map { task ->
                                        task.first to (shotsCopy.firstOrNull { it.first == task.first }?.second ?: emptyList())
                                    },
                                    idPhotos = ids,
                                )
                            }
                        }
                        exporting = false
                        outcome.onSuccess { r ->
                            val saved = withContext(Dispatchers.IO) {
                                com.teslalink.app.tools.EvidencePdf.saveToDownloads(context, r.file)
                            }
                            val shared = com.teslalink.app.tools.EvidencePdf.share(
                                context, r.file, "${context.packageName}.fileprovider",
                            )
                            exportNote = (if (r.failed > 0) Tone.Warn else Tone.Good) to
                                "已生成 ${r.pages} 页 · 共 ${r.photos} 张照片" +
                                (if (r.failed > 0) "，${r.failed} 张读不出来（PDF 里已标出，重拍或重选）" else "") +
                                (if (saved != null) "，已存到「下载」目录" else "，存下载目录失败（不影响发送）") +
                                (if (shared) "，分享面板已打开" else "，但分享面板没拉起来")
                        }.onFailure {
                            exportNote = Tone.Bad to "PDF 生成失败：${it.javaClass.simpleName} ${it.message ?: ""}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                emphasize = true,
                tone = Tone.Info,
                stateLine = "清单 + 现场照片 + 证件照 + 个人信息，一键发给保险/交警",
            )
            if (profile.name.isBlank() || profile.phone.isBlank() || profile.plate.isBlank()) {
                Text(
                    "提示：姓名/手机/车牌还没在「设置 → 个人信息」里填，PDF 里这几项会是空的。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TL.warn,
                )
            }
            PHOTO_TASKS.forEach { (title, hint) ->
                val done = itemDone(title)
                val photos = shots[title].orEmpty()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (done) TL.good.copy(alpha = 0.08f) else TL.raised)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 标题占满整行（原来被右边的按钮挤成两行），按钮挪到照片那一排。
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { manualDone = manualDone + (title to (manualDone[title] != true)) },
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (done) "✓" else "○",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (done) TL.good else TL.textLow,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (photos.isEmpty() && manualDone[title] == true) TL.textMid else TL.textHi,
                            )
                            Text(hint, style = MaterialTheme.typography.labelSmall, color = TL.textLow)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (photos.isEmpty()) {
                            Text(
                                "还没拍",
                                style = MaterialTheme.typography.labelSmall,
                                color = TL.textLow,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Row(
                                Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                photos.forEach { uri ->
                                    Box(
                                        Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(TL.base)
                                            .clickable { viewing = title to uri },
                                    ) {
                                        PhotoThumb(uri, Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                        ActionTile(
                            "拍照",
                            onClick = { capture.launch(title) },
                            modifier = Modifier.width(72.dp),
                            tone = Tone.Info,
                        )
                        // 相机不配合（不写我们给的文件）时的零权限出路：系统照片选择器。
                        ActionTile(
                            "选图",
                            onClick = { capture.pick(title) },
                            modifier = Modifier.width(72.dp),
                            tone = Tone.Neutral,
                        )
                    }
                }
            }
            if (missing.isEmpty()) {
                HintBar("取证完整，可以挪车或走「交管 12123」在线快处。", Tone.Good)
            } else {
                HintBar("还差 ${missing.size} 项：${missing.joinToString("、") { it.first }}", Tone.Warn)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(
                    "复制取证清单",
                    onClick = {
                        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        val text = buildString {
                            appendLine("事故取证清单 ${fmt.format(Date(info?.fixTimeMs ?: System.currentTimeMillis()))}")
                            info?.let {
                                appendLine(
                                    "位置：${it.address ?: ""}" +
                                        String.format(Locale.US, " GPS %.5f, %.5f", it.lat, it.lng)
                                )
                            }
                            PHOTO_TASKS.forEach { (t, _) ->
                                val n = shots[t].orEmpty().size
                                appendLine(
                                    when {
                                        n > 0 -> "☑ $t（$n 张）"
                                        manualDone[t] == true -> "☑ $t（已拍，未记在本页）"
                                        else -> "☐ $t"
                                    }
                                )
                            }
                        }
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("checklist", text))
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionTile(
                    if (clearArmed) "确认清空？" else "清空本页清单",
                    onClick = {
                        if (!clearArmed) {
                            clearArmed = true
                        } else {
                            // 只清本页：取证照片是证据，相册里的文件一律不删。
                            shots = emptyMap()
                            manualDone = emptyMap()
                            clearArmed = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = shotCount > 0 || manualDone.isNotEmpty(),
                    tone = if (clearArmed) Tone.Bad else Tone.Warn,
                    stateLine = if (clearArmed) "再点一次清掉本页列表（相册里的照片保留）" else null,
                )
            }
            Text(
                "照片存到相册 Pictures/TeslaLink；同一类可以拍多张，点缩略图看大图，大图里才能「从清单移除」。" +
                    "移除和清空都只动本页列表，相册里的文件不删。用别的相机拍的，点左边的 ○ 手动记为已完成。",
                style = MaterialTheme.typography.labelSmall,
                color = TL.textLow,
            )
        }

        viewing?.let { (task, uri) ->
            Dialog(onDismissRequest = { viewing = null }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TL.surface)
                        .border(1.dp, TL.hairline, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val all = shots[task].orEmpty()
                    Text(
                        "$task · 第 ${all.indexOf(uri) + 1} / ${all.size} 张",
                        style = MaterialTheme.typography.titleMedium,
                        color = TL.textHi,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(TL.base),
                    ) {
                        PhotoThumb(uri, Modifier.fillMaxSize())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionTile("关闭", onClick = { viewing = null }, modifier = Modifier.weight(1f))
                        ActionTile(
                            "从清单移除",
                            onClick = {
                                // 只从本页列表去掉，相册里的原图不动。
                                val rest = all - uri
                                shots = if (rest.isEmpty()) shots - task else shots + (task to rest)
                                viewing = null
                            },
                            modifier = Modifier.weight(1f),
                            tone = Tone.Bad,
                        )
                    }
                }
            }
        }

        BigPanel("第 5 步 · 拍完再挪车") {
            BigLine("责任清楚、能开动：拍照后把车挪到路边，别堵主路")
            BigLine("有争议或有伤：保持现场，等交警")
            BigLine("「交管 12123」App 可在线快处、生成协议书")
        }

        BigPanel("第 6 步 · 报保险（48 小时内）") {
            val mine = profile.insurer.trim()
            // 名单与「设置 → 个人信息 → 保险公司」下拉同源；他手动填的写法（"平安"）也能对上"平安产险"。
            val mineRows = INSURANCE_HOTLINES.filter { (name, _) ->
                mine.isNotBlank() && (name.contains(mine) || mine.contains(name))
            }
            // 特斯拉道路救援跟投保哪家公司无关，定损前就要打，永远留在第一屏。
            val top = (mineRows + INSURANCE_HOTLINES.filter { it.first.startsWith("Tesla") }).distinct()
            val rest = INSURANCE_HOTLINES - top.toSet()
            when {
                mine.isBlank() -> HintBar("还没在「设置 → 个人信息」里选保险公司，这里先全列出来。", Tone.Neutral)
                mineRows.isEmpty() -> HintBar("「$mine」没对上常用列表，号码全列着。", Tone.Warn)
            }
            top.forEach { (name, number) ->
                HotlineRow(
                    name,
                    number,
                    emphasized = mineRows.any { it.first == name },
                    onClick = { dial(number) },
                )
            }
            if (rest.isNotEmpty()) {
                CollapsibleSection(
                    "其它保险公司号码",
                    "${rest.size} 家 · 报对方保险时也从这里找",
                ) {
                    Panel {
                        rest.forEach { (name, number) ->
                            HotlineRow(name, number, emphasized = false, onClick = { dial(number) })
                        }
                    }
                }
            }
            Text(
                "特斯拉维修先联系服务中心或道路救援，再和保险定损对齐。",
                style = MaterialTheme.typography.labelSmall,
                color = TL.textLow,
            )
        }

        CollapsibleSection("其他号码", "110 / 120 / 12122 等备用") {
            Panel {
                EMERGENCY_NUMBERS.forEach { (label, number) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { dial(number) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, color = TL.textHi)
                        Pill("拨打", Tone.Neutral)
                    }
                }
            }
        }


        if (amapKey.isNullOrBlank()) {
            HintBar(
                "想要中文地址：到「设置 → 高德 Key（通用）」填一次 Web服务 Key 即可（不需要安全密钥）。",
                Tone.Neutral,
            )
        }
    }
}

/**
 * 取证拍照：把 FileProvider 的中转文件交给系统相机（EXTRA_OUTPUT），拍完由我们收进
 * 相册 Pictures/TeslaLink。不需要相机权限也不需要存储权限。
 *
 * 为什么不再用 MediaStore 的 IS_PENDING 占位（vivo 实测）：pending 条目只有我们自己看得见，
 * com.android.camera 干脆不写，回来还是 0 字节 + RESULT_CANCELED，照片被它自己存进 DCIM，
 * 而我们没相册读取权限就看不见。FileProvider 是各家相机都认的写法，成败还能直接 stat 文件。
 *
 * 三个踩过的坑：
 *  1) EXTRA_OUTPUT 里的 URI 不随 intent flags 自动授权（系统只授 data/clipData）→ 显式 grantUriPermission + 挂 clipData；
 *  2) 拉相机时进程可能被 ROM 回收 → 待处理状态必须 saveable，否则回来什么都不知道；
 *  3) 国产相机照片写成功了照样回 RESULT_CANCELED → 判定只看文件有没有内容。
 */
private class CameraCapture(
    val launch: (String) -> Unit,
    val pick: (String) -> Unit,
    val note: Pair<Tone, String>?,
)

/** 把任意来源的字节收进相册（我们自己的行 → 永久可读，进程重启也认）。失败返回 null 并清掉半成品。 */
private fun copyIntoGallery(context: Context, open: () -> java.io.InputStream?, name: String): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TeslaLink")
        // 自己写也要 pending：中途崩了相册里不会留一张 0 字节的空照片。
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        val input = open() ?: error("读不出来源文件")
        val out = context.contentResolver.openOutputStream(uri) ?: error("打不开相册输出流")
        input.use { i -> out.use { o -> i.copyTo(o) } }
        runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
        uri
    } catch (e: Exception) {
        runCatching { context.contentResolver.delete(uri, null, null) }   // 不留 0 字节半成品
        null
    }
}

@Composable
private fun rememberCameraCapture(onSaved: (Uri, String) -> Unit): CameraCapture {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authority = "${context.packageName}.fileprovider"
    // 待处理状态必须 saveable：相机把我们的进程挤掉后，结果还是要能对上"刚才在拍哪一项"。
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTask by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPkg by rememberSaveable { mutableStateOf<String?>(null) }
    var pickTask by rememberSaveable { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<Pair<Tone, String>?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = pendingPath
        val task = pendingTask
        val pkg = pendingPkg
        pendingPath = null
        pendingTask = null
        pendingPkg = null
        if (path == null || task == null) return@rememberLauncherForActivityResult
        val file = File(path)
        val size = if (file.exists()) file.length() else -1L
        val okByCode = result.resultCode == Activity.RESULT_OK
        runCatching {
            pkg?.let { context.revokeUriPermission(FileProvider.getUriForFile(context, authority, file), permissionFlags) }
        }
        if (size > 0) {
            // 拍到了。存进相册这件事要在 IO 线程做，并且成败要说得出来。
            // NonCancellable：取消点一旦落在拷贝中间，相册里已插入的 IS_PENDING 行没人收尾、
            // 中转文件残留，而照片其实已经拍到了——收档必须做完，中途切页也不能半途而废。
            scope.launch {
                val stored = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    copyIntoGallery(context, { file.inputStream() }, file.name)
                }
                runCatching { file.delete() }
                if (stored != null) {
                    note = Tone.Good to "已拍 · ${"%.0f".format(size / 1024f)} KB" +
                        if (okByCode) "" else "（相机回了「取消」，但照片确实写进来了，已保留）"
                    onSaved(stored, task)
                } else {
                    note = Tone.Bad to "照片拍到了（${"%.0f".format(size / 1024f)} KB），但存进相册失败——" +
                        "手机存储可能被禁用或空间不足；文件暂时留在 ${file.name}"
                }
            }
            return@rememberLauncherForActivityResult
        }
        runCatching { file.delete() }
        note = Tone.Bad to ("相机（${pkg ?: "未知"}）没把照片写进我们给的文件" +
            if (okByCode) "" else "，还回了「取消」") +
            "。它可能把照片存成了自己的文件（DCIM 里，我们没相册权限看不见）。" +
            "不用换相机：直接点「选图」，从相册里把刚拍的那张挑进来。"
    }

    // 零权限兜底：系统照片选择器（Photo Picker），相机不配合时用它把刚拍的那张挑进来。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val task = pickTask
        pickTask = null
        if (uri == null || task == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = "TeslaLink取证_选图_" +
                SimpleDateFormat("MMdd_HHmmss", Locale.getDefault()).format(Date()) + ".jpg"
            // NonCancellable：与拍照同理由——IS_PENDING 行与流必须在收档里走完。
            val stored = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                copyIntoGallery(context, { context.contentResolver.openInputStream(uri) }, name)
            }
            if (stored != null) {
                note = Tone.Good to "已加入你选的那张"
                onSaved(stored, task)
            } else {
                note = Tone.Bad to "读不出你选的那张图"
            }
        }
    }

    return CameraCapture(
        launch = { task ->
            val dir = File(context.cacheDir, "shots").apply { mkdirs() }
            runCatching { dir.listFiles()?.forEach { if (it.length() == 0L) it.delete() } }
            val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "TeslaLink取证_$stamp.jpg")
            val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
            if (uri == null) {
                note = Tone.Bad to "FileProvider 没配好（authority=$authority），换「选图」这条路"
            } else {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                val pkg = intent.resolveActivity(context.packageManager)?.packageName
                if (pkg == null) {
                    note = Tone.Bad to "手机上找不到能响应「拍照」的应用"
                } else {
                    runCatching { context.grantUriPermission(pkg, uri, permissionFlags) }
                    intent.clipData = ClipData.newRawUri("output", uri)
                    intent.addFlags(permissionFlags)
                    pendingPath = file.absolutePath
                    pendingTask = task
                    pendingPkg = pkg
                    note = null
                    runCatching { launcher.launch(intent) }.onFailure {
                        pendingPath = null; pendingTask = null; pendingPkg = null
                        note = Tone.Bad to "拉不起相机：${it.javaClass.simpleName}"
                    }
                }
            }
        },
        pick = { task ->
            pickTask = task
            runCatching {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }.onFailure { note = Tone.Bad to "系统照片选择器拉不起来：${it.javaClass.simpleName}" }
        },
        note = note,
    )
}


private val permissionFlags =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

// Map 进不了 Bundle；进程被相机挤掉时靠这两个 Saver 把取证表单拍平存下来。
// 分隔符选的是 MediaStore Uri 和中文标题里都不会出现的组合（"||" 分项目、"::" 分键值、"," 分 Uri）。
private val shotsSaver = Saver<Map<String, List<Uri>>, String>(
    save = { m ->
        m.entries.filter { it.value.isNotEmpty() }
            .joinToString("||") { (k, v) -> k + "::" + v.joinToString(",") { it.toString() } }
    },
    restore = { s ->
        s.split("||").mapNotNull { part ->
            val i = part.indexOf("::")
            if (i <= 0) null
            else part.substring(0, i) to part.substring(i + 2).split(",").filter { it.isNotBlank() }.map { Uri.parse(it) }
        }.filter { it.second.isNotEmpty() }.toMap()
    },
)

private val manualSaver = Saver<Map<String, Boolean>, String>(
    save = { m -> m.filterValues { it }.keys.joinToString("||") },
    restore = { s -> if (s.isBlank()) emptyMap() else s.split("||").filter { it.isNotBlank() }.associate { it to true } },
)


/** 页内缩略图：按需降采样解码（不引第三方图片库）。默认完整显示，不裁不拉。 */
@Composable
internal fun PhotoThumb(
    uri: Uri,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var err by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        val out = withContext(Dispatchers.IO) {
            runCatching {
                // 1/8 降采样：64dp 的缩略图不需要整张原图，滚动也流畅。
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                } ?: error("打不开这个 Uri")
            }
        }
        bmp = out.getOrNull()?.asImageBitmap()
        // 解不出来一定要显出来，否则"没预览"到底是没拍成还是拍成了读不出，分不清。
        err = out.exceptionOrNull()?.let { "读不出" }
    }
    Box(modifier.background(TL.base), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = "取证照片",
                contentScale = scale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                err ?: "…",
                color = if (err != null) TL.bad else TL.textLow,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun BigPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TL.raised)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = TL.textHi,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

/** 报案号码行：自己那家高亮并写明"你选的"，其余收进折叠区（报对方保险时还要用）。 */
@Composable
private fun HotlineRow(name: String, number: String, emphasized: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (emphasized) Modifier
                    .background(TL.accent.copy(alpha = 0.10f))
                    .border(1.dp, TL.accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 15.sp,
                color = if (emphasized) TL.textHi else TL.textMid,
            )
            if (emphasized) {
                Text("你选的保险公司 · 48 小时内报案", fontSize = 10.sp, color = TL.accent)
            }
        }
        Text(number, style = TL.mono, color = TL.accent, fontSize = 16.sp)
    }
}

@Composable
private fun BigLine(text: String) {
    Text(text, fontSize = 16.sp, color = TL.textMid, lineHeight = 24.sp)
}

