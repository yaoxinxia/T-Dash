package com.teslalink.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.teslalink.app.AppContainer
import com.teslalink.app.tools.DashcamEvent
import com.teslalink.app.tools.DashcamStore
import com.teslalink.app.ui.components.ActionTile
import com.teslalink.app.ui.components.CollapsibleSection
import com.teslalink.app.ui.components.KeyValue
import com.teslalink.app.ui.components.HintBar
import com.teslalink.app.ui.components.PageHeader
import com.teslalink.app.ui.components.Panel
import com.teslalink.app.ui.components.Pill
import com.teslalink.app.ui.components.SectionLabel
import com.teslalink.app.ui.components.Tone
import com.teslalink.app.ui.theme.TL
import com.teslalink.app.vm.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 工具页：子页签呈现不同工具，一页一职，互不干扰。 */
@Composable
fun ToolsScreen(container: AppContainer, dashboard: com.teslalink.app.vm.DashboardViewModel) {
    var tool by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableIntStateOf(0)
    }

    // 车速流不在整页持有：快捷启动/事故/皮肤这几个 tab 用不到 BLE，
    // 挂着轮询会白耗电、还可能把休眠的车唤醒。记录仪与零百各自按引用计数拿。

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader("工具", "小工具合集：快捷启动 · 事故处理 · 行车记录仪 · 零百计时 · 车机皮肤")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("快捷启动", "事故处理", "行车记录仪", "零百计时", "车机皮肤").forEachIndexed { i, name ->
                Pill(
                    name,
                    if (i == tool) Tone.Data else Tone.Neutral,
                    selected = i == tool,
                    onClick = { tool = i },
                )
            }
        }
        when (tool) {
            0 -> QuickLaunchPanel()
            1 -> AccidentSection(container)
            2 -> DashcamTool(container, dashboard)
            3 -> AccelToolSection(dashboard)
            else -> WrapsSection(container)
        }
    }
}

@Composable
private fun AccelToolSection(dashboard: com.teslalink.app.vm.DashboardViewModel) {
    // 计时吃的就是仪表那条 250ms 车速流，本页签期间保持它在线。
    DisposableEffect(Unit) {
        dashboard.startBlePolling()
        onDispose { dashboard.stopBlePolling() }
    }
    val note by dashboard.accelNote.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("零百成绩测试", "蓝牙车速采样 · 与仪表联动 · 封闭场地使用")
        AccelTool(dashboard)
        note?.let { HintBar(it, Tone.Warn) }
    }
}

/**
 * 快捷启动表。包名必须在 Manifest 的 <queries> 里声明过，否则 Android 11+ 上
 * getLaunchIntentForPackage 直接返回 null（表现就是"点了没反应"）——加 App 记得同步改 Manifest。
 */
private data class QuickApp(
    val label: String,
    val pkg: String,
    val scheme: String?,
    val stateLine: String,
)

private val QUICK_APPS = listOf(
    QuickApp(
        "高德地图",
        "com.autonavi.minimap",
        "androidamap://viewMap?sourceApplication=TeslaLink",
        "导航 · 实时路况 · 找充电站",
    ),
    QuickApp(
        "支付宝",
        "com.eg.android.AlipayGphone",
        null,
        "付款 · 出行 · 本地生活",
    ),
    QuickApp(
        "微信",
        "com.tencent.mm",
        null,
        "社交 · 扫码 · 传文件",
    ),
    QuickApp(
        "美团",
        "com.sankuai.meituan",
        null,
        "找美食 · 订酒店 · 团购",
    ),
)

@Composable
private fun QuickLaunchPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var result by remember { mutableStateOf<Pair<Tone, String>?>(null) }

    fun launch(app: QuickApp) {
        result = try {
            val intent = context.packageManager.getLaunchIntentForPackage(app.pkg)
            when {
                intent != null -> {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Tone.Good to "已拉起${app.label}"
                }
                // 包名查不到（未安装，或系统仍限制可见性）→ 有公开协议的退协议。
                app.scheme != null -> {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(app.scheme),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Tone.Good to "已用协议拉起${app.label}"
                }
                else -> Tone.Bad to "没找到${app.label}——手机上没装这个 App"
            }
        } catch (e: Exception) {
            Tone.Bad to "没拉起来${app.label}：${e.javaClass.simpleName}${e.message?.let { " · $it" } ?: ""}" +
                "——可能是 vivo 拦了跳转（「权限与隐私 → 其他权限 → 关联应用/后台弹出界面」允许）"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("快捷启动", "一键拉起常用 App")
        Panel {
            QUICK_APPS.forEach { app ->
                ActionTile(
                    app.label,
                    onClick = { launch(app) },
                    modifier = Modifier.fillMaxWidth(),
                    emphasize = true,
                    tone = Tone.Info,
                    stateLine = app.stateLine,
                )
            }
            result?.let { HintBar(it.second, it.first) }
        }
    }
}

/**
 * 行车记录仪工具：SAF 选 U 盘 → 事件列表 → 多视角同步连续播放 → 导出/剪辑 → MP4 体检。
 * 车速叠加来自本 app 的 BLE 采样序列。
 */
@Composable
private fun DashcamTool(container: AppContainer, dashboard: com.teslalink.app.vm.DashboardViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var treeUri by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf<List<DashcamEvent>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DashcamEvent?>(null) }
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("全部") }
    val speedPoints by dashboard.speed.collectAsState()

    // 计时工具需要车速流活着：工具页也加入仪表/统计的轮询引用计数。
    DisposableEffect(Unit) {
        dashboard.startBlePolling()
        onDispose { dashboard.stopBlePolling() }
    }

    LaunchedEffect(Unit) {
        treeUri = container.prefs.getString("dashcam_tree")
    }
    // 重新扫描的触发键：原来写的是 treeUri = treeUri——赋同一个值，LaunchedEffect 的键没变，
    // 那颗按钮从来不会真的重扫（他一眼看出"看起来没有用"）。
    var scanTick by remember { mutableIntStateOf(0) }
    var scanNote by remember { mutableStateOf<Pair<Tone, String>?>(null) }
    LaunchedEffect(treeUri, scanTick) {
        val uri = treeUri ?: return@LaunchedEffect
        scanning = true
        // 盘被拔掉/授权过期时 SAF 会抛 SecurityException，扫描里任何意外也不能带走 app；
        // 但失败必须看得见，否则就是"点了重扫，列表空了，不知道为什么"。
        // 协程取消（离开本页）要照常往上抛，不能被当成"扫描失败"。
        val result = try {
            Result.success(withContext(Dispatchers.IO) { DashcamStore.scan(context, Uri.parse(uri)) })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.teslalink.app.debug.DebugLog.error(
                "dashcam", "扫描异常：${e.javaClass.name}: ${e.message}", e,
            )
            Result.failure<DashcamStore.ScanResult>(e)
        }
        val scan = result.getOrNull()
        events = scan?.events ?: emptyList()
        scanNote = when {
            scan == null -> {
                val e = result.exceptionOrNull()
                Tone.Bad to "扫描失败：${e?.javaClass?.simpleName ?: "?"} ${e?.message ?: ""}——" +
                    "U 盘还在的话点「重新扫描」，不行就重新「换 U 盘」授权一次。"
            }
            // 有目录读不动 ≠ 目录里没数据：拔盘/授权失效和"真没有分段"必须分开说。
            scan.unreadable.isNotEmpty() && scan.events.isEmpty() -> Tone.Bad to
                "U 盘读不动（${scan.unreadable.size} 个目录失败，可能被拔出或授权失效）——" +
                    "插好盘点「重新扫描」；授权没了就重新「换 U 盘」授权一次。"
            scan.unreadable.isNotEmpty() -> Tone.Warn to
                "已列出 ${scan.events.size} 组，但另有 ${scan.unreadable.size} 个目录读不动（盘可能松了）——需要的话重新「换 U 盘」授权。"
            scan.events.isEmpty() -> Tone.Warn to "这个目录里没找到 TeslaCam 的分段（RecentClips/SentryClips/SavedClips）。"
            else -> null
        }
        scanning = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // 换盘前释放旧授权：persistable grant 有系统级上限（~128 条），反复选盘不释放
            // 会被静默逐出最旧的——逐出的可能正是 prefs 里还存着的 dashcam_tree。
            treeUri?.let { old ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope.launch { container.prefs.putString("dashcam_tree", uri.toString()) }
            treeUri = uri.toString()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("行车记录仪", "U 盘经 OTG 插手机 · 6 机位 · RecentClips 平铺 + Sentry/Saved 每事件一目录")
        Panel {
            when {
                treeUri == null -> {
                    Text(
                        "把行车记录仪 U 盘用 OTG 插到手机，选择 U 盘里的 TeslaCam 文件夹（或 U 盘根目录）。" +
                            "本工具只读不写，不需要任何存储权限。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TL.textMid,
                    )
                    ActionTile(
                        "选择 U 盘文件夹",
                        onClick = { picker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                        emphasize = true,
                        tone = Tone.Info,
                    )
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("U 盘已连接", Tone.Good)
                        Pill("${events.size} 个事件", if (events.isEmpty()) Tone.Neutral else Tone.Data)
                    }
                    if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = TL.accent)
                    scanNote?.let { (tone, msg) -> HintBar(msg, tone) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionTile(
                            if (scanning) "正在扫描…" else "重新扫描",
                            onClick = {
                                // 重扫会换掉事件对象；留着旧选中项会让播放器和导出指向已经不存在的分段。
                                selected = null
                                scanTick++
                            },
                            modifier = Modifier.weight(1f),
                            stateLine = "U 盘里加了新事件、或换了盘，点这里重列（不重新选目录）",
                        )
                        ActionTile("换 U 盘", onClick = { picker.launch(null) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (events.isEmpty() && !scanning && treeUri != null) {
            HintBar("没找到事件文件夹：确认 U 盘里有 TeslaCam/RecentClips 等目录，或视频在根目录（也会被识别）。", Tone.Warn)
        }

        // 来源分类：实测一块盘里同时有哨兵、手动保存和大量滚动分段，先按来源筛掉噪音。
        val shown = events.filter {
            when (filter) {
                "哨兵" -> it.source == "Sentry"
                "手动" -> it.source == "Saved"
                "滚动" -> it.source == "Recent"
                "有加速度" -> it.isImpact
                else -> true
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("全部", "有加速度", "哨兵", "手动", "滚动").forEach { f ->
                Pill(
                    "$f ${if (f == "全部") events.size else events.count { e -> when (f) {
                        "哨兵" -> e.source == "Sentry"; "手动" -> e.source == "Saved"
                        "滚动" -> e.source == "Recent"; "有加速度" -> e.isImpact; else -> false
                    } }}",
                    if (f == filter) Tone.Data else Tone.Neutral,
                    selected = f == filter,
                    onClick = { filter = f },
                )
            }
        }

        shown.forEach { e ->
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val minutes = ((e.endMs - e.startMs) / 60_000).coerceAtLeast(1)
            // 纯文字列表（他要的：时间+类型+地址）：缩略图让每行滚动时都要去 U 盘读一次
            // thumb.png，几十行滚下来又卡又"图片慢慢蹦"，信息量却抵不上第二行的地址。
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(TL.raised)
                    .clickable { selected = if (selected == e) null else e }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        fmt.format(Date(e.startMs)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected == e) TL.accent else TL.textHi,
                    )
                    Text(e.source, style = MaterialTheme.typography.labelMedium, color = TL.textMid)
                    if (e.isImpact) {
                        Text("碰撞", style = MaterialTheme.typography.labelMedium, color = TL.bad)
                    }
                    Box(Modifier.weight(1f))
                    Text(
                        if (selected == e) "▲" else "▼",
                        color = TL.textMid,
                        fontSize = 14.sp,
                    )
                }
                Text(
                    e.city?.let { c -> listOfNotNull(c, e.street).joinToString(" · ") } ?: "地址未记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (e.city == null) TL.textLow else TL.textMid,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    "约 $minutes 分钟 · ${e.segmentCount} 个分段 · ${e.clips.size} 机位 · ${e.reasonText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TL.textLow,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // 点开就地展开：播放器和导出排在整张列表最底下的话，几十行翻不到（他实测反馈）。
            if (selected == e) {
                DashcamPlayer(e, speedPoints)
                // 导出 / 剪辑：同相机分段 remux 拼接成单个 mp4 存相册。
                ExportPanel(e, context)
            }
        }
        if (shown.isEmpty() && events.isNotEmpty()) HintBar("这个分类下没有事件。", Tone.Neutral)

        // MP4 体检：解剖选中事件的前视第一段，看看文件里除视频/音频外还有没有藏数据轨。
        // 有 → 车速叠加可以切到文件自带数据（不再依赖本 app 采样）；没有 → 维持现状。
        selected?.let { ev ->
            var result by remember(ev) { mutableStateOf<com.teslalink.app.tools.Mp4Inspector.Result?>(null) }
            LaunchedEffect(ev) {
                val clip = ev.clips["front"]?.firstOrNull()
                    ?: ev.clips.values.firstOrNull()?.firstOrNull()
                    ?: return@LaunchedEffect
                result = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openFileDescriptor(clip.uri, "r")?.use { pfd ->
                            com.teslalink.app.tools.Mp4Inspector.inspect(pfd.fileDescriptor)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 盘被拔掉、或这个文件系统不让随机读：留在页面上就是"解析失败"那行，
                    // 但绝不能因此让 app 崩。
                    com.teslalink.app.debug.DebugLog.error(
                        "dashcam", "MP4 体检失败：${e.javaClass.name}: ${e.message}", e,
                    )
                    null
                }
            }
            CollapsibleSection(
                "MP4 体检（前视第一段）",
                result?.let {
                    if (it.hasTelemetryTrack) "发现非视频/音频轨道！遥测轨可能存在"
                    else "单视频轨、无数据轨 · 车速只能靠本 app 蓝牙采样叠加"
                } ?: "无法解析",
                initiallyExpanded = result?.hasTelemetryTrack == true,
            ) {
                Panel(tight = true) {
                    val r = result
                    if (r == null) {
                        KeyValue("结果", "解析失败（文件系统不支持随机读取？）")
                    } else {
                        KeyValue("顶层结构", r.topBoxes.joinToString(" → "))
                        r.tracks.forEach { t ->
                            KeyValue(
                                "轨道 #${t.index + 1}",
                                listOfNotNull(
                                    t.handler,
                                    t.codec,
                                    t.width?.let { w -> "${w}x${t.height}" },
                                ).joinToString(" · "),
                            )
                        }
                        KeyValue("udta 自定义容器", if (r.foundUdta) "存在" else "无")
                        if (r.metaBoxes.isNotEmpty()) KeyValue("meta 容器", r.metaBoxes.joinToString(" / "))
                    }
                }
            }
        }

    }
}

@Composable
private fun AccelTool(dashboard: DashboardViewModel) {
    val run by dashboard.accelRun.collectAsState()
    val results by dashboard.accelResults.collectAsState()

    Panel {
        when (val r = run) {
            null -> ActionTile(
                "开始零百测试",
                onClick = dashboard::armAccel,
                modifier = Modifier.fillMaxWidth(),
                emphasize = true,
                tone = Tone.Info,
                stateLine = "待命后从静止起步即自动计时，到 100 km/h 停表",
            )
            else -> {
                Text(
                    when {
                        r.t0Ms == null -> "待命中：车辆停稳后起步即自动开始"
                        r.t100Ms == null -> "计时中：${"%.2f".format((System.currentTimeMillis() - r.t0Ms) / 1000f)} s"
                        else -> "完成"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = TL.accent,
                )
                ActionTile("取消测试", onClick = dashboard::cancelAccel, modifier = Modifier.fillMaxWidth(), tone = Tone.Warn)
            }
        }
    }

    if (results.isEmpty()) {
        HintBar("还没有成绩。测试时保持手机在车内、已连接；仪表页会同步显示实时秒表。", Tone.Neutral)
    } else {
        results.forEachIndexed { i, r ->
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(TL.raised)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "#${results.size - i} · ${fmt.format(Date(r.t0Ms))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TL.textHi,
                    )
                    Text(
                        buildString {
                            r.t0To60Ms?.let { append("0-60 ${"%.2f".format(it / 1000f)} s") }
                            r.t60To100Ms?.let {
                                if (isNotEmpty()) append(" · ")
                                append("60-100 ${"%.2f".format(it / 1000f)} s")
                            }
                            append(" · 峰值 ${r.maxPowerKw} kW")
                            if (r.socStart != null) {
                                append(" · SOC ${r.socStart}%→${r.socEnd ?: "?"}%")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TL.textLow,
                    )
                }
                Text(
                    "${"%.2f".format((r.t0To100Ms ?: 0) / 1000f)} s",
                    style = MaterialTheme.typography.titleLarge,
                    color = TL.accent,
                )
            }
        }
        HintBar("计时来自 250 ms 车速流的线性插值，精度约 ±0.05 s；比车机 HUD 差一点，但零成本。", Tone.Neutral)
    }
}

@Composable
private fun ExportPanel(event: DashcamEvent, context: android.content.Context) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cams = event.clips.keys.filter { event.clips[it]?.isNotEmpty() == true }
    // 画面机位多选（1=全幅原生，2-4=2×2），全部带左下角时间码——他要的"导出必须有时间显示"。
    // 带时间码就必须重编码（remux 改不了像素），所以导出只有这一条合成管线。
    var views by remember(event) { mutableStateOf(cams.take(4)) }
    val clips = event.clips[views.firstOrNull() ?: "front"] ?: emptyList()
    var fromIdx by remember(views, event) { mutableIntStateOf(0) }
    var toIdx by remember(views, event) { mutableIntStateOf((clips.size - 1).coerceAtLeast(0)) }
    var exporting by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var progress by remember { mutableStateOf(0 to 0) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageTone by remember { mutableStateOf(Tone.Good) }

    CollapsibleSection(
        "导出 / 剪辑",
        "合成单个 mp4 · 1 路全幅 / 2-4 路 2×2 · 左下角带时间码 · 存到相册 Movies/TeslaLink",
    ) {
        Panel {
            if (cams.isEmpty()) {
                HintBar("这个事件里没有可导出的视频分段。", Tone.Warn)
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    cams.forEach { c ->
                        val on = views.contains(c)
                        Pill(
                            "${camLabel(c)} ${event.clips[c]?.size ?: 0}",
                            if (on) Tone.Data else Tone.Neutral,
                            selected = on,
                            onClick = {
                                val next = if (views.contains(c)) views - c else views + c
                                when {
                                    next.isEmpty() -> { messageTone = Tone.Warn; message = "至少保留一路画面。" }
                                    next.size > 4 -> { messageTone = Tone.Warn; message = "最多同时合成 4 路。" }
                                    else -> {
                                        message = null
                                        views = next.sortedBy { cams.indexOf(it) }
                                    }
                                }
                            },
                        )
                    }
                }
                HintBar(
                    "${if (views.size == 1) "单路=全幅原生分辨率" else "${views.size} 路=2×2，前视原生分辨率作主格"}，" +
                        "码率拉满不缩小；左下角烙墙钟时间码。导出走 解码→合成→高码率重编码，" +
                        "速度约为实时的 1/2~1/3，比拷贝慢是物理的——每帧都要解码、合成、再编码。",
                    Tone.Neutral,
                )
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RangeStepper(
                        "起始段",
                        fromIdx,
                        clips.size,
                        clips.getOrNull(fromIdx)?.let { timeFmt.format(Date(it.startEpochMs)) } ?: "--",
                    ) { fromIdx = it.coerceIn(0, toIdx) }
                    RangeStepper(
                        "结束段",
                        toIdx,
                        clips.size,
                        clips.getOrNull(toIdx)?.let { timeFmt.format(Date(it.startEpochMs)) } ?: "--",
                    ) { toIdx = it.coerceIn(fromIdx, clips.size - 1) }
                }

                if (exporting) {
                    LinearProgressIndicator(
                        progress = {
                            if (progress.second == 0) 0f
                            else progress.first.toFloat() / progress.second
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = TL.accent,
                    )
                    Text(
                        "拷贝中 ${progress.first + 1}/${progress.second} 段…（U 盘速度决定快慢）",
                        style = MaterialTheme.typography.labelSmall,
                        color = TL.textLow,
                    )
                }
                if (exporting) {
                    ActionTile(
                        "取消导出",
                        onClick = {
                            exportJob?.cancel()
                            messageTone = Tone.Warn
                            message = "已取消导出（半成品已清理，没有留下坏文件）"
                            exporting = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        tone = Tone.Bad,
                        stateLine = "导出期间请留在本页：切走页签同样会中断导出（会自动清理半成品）",
                    )
                }
                ActionTile(
                    if (exporting) "导出中…" else "导出 ${toIdx - fromIdx + 1} 段 · ${views.size} 路（含时间码）",
                    onClick = {
                        exporting = true
                        message = null
                        val job = scope.launch {
                            message = try {
                                val name = "TeslaLink-${event.folderName.replace(":", "-")}-${
                                    if (views.size == 1) views.first() else "multi${views.size}"
                                }"
                                val safeName = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
                                val byCam = views.mapNotNull { c -> event.clips[c]?.let { c to it } }.toMap()
                                require(byCam.isNotEmpty()) { "没有可导出的分段" }
                                val subs = byCam.mapValues { (_, list) ->
                                    val a = fromIdx.coerceIn(0, list.size - 1)
                                    val b = (toIdx + 1).coerceIn(a + 1, list.size)
                                    list.subList(a, b)
                                }
                                withContext(Dispatchers.IO) {
                                    com.teslalink.app.tools.CompositeExporter.stitch(
                                        context, views, subs, safeName,
                                        { d, t -> progress = d to t },
                                        isCancelled = { exportJob?.isActive == false },
                                    )
                                }
                                messageTone = Tone.Good
                                "已导出到 相册 → Movies/TeslaLink"
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                messageTone = Tone.Bad
                                "导出失败：${e.message ?: e.javaClass.simpleName}"
                            }
                            exporting = false
                        }
                        exportJob = job
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !exporting && clips.isNotEmpty(),
                    emphasize = true,
                    tone = Tone.Info,
                )
                message?.let { HintBar(it, messageTone) }
            }
        }
    }
}

/** 段序号步进器：-/+ 调整，显示段号与该段起始时刻。 */
@Composable
private fun RangeStepper(
    label: String,
    value: Int,
    count: Int,
    timeText: String,
    onChange: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = TL.eyebrow)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("−", Tone.Neutral, selected = true, onClick = { onChange(value - 1) })
            Text(
                "${value + 1}/$count",
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = TL.textHi,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            Pill("+", Tone.Neutral, selected = true, onClick = { onChange(value + 1) })
        }
        Text(timeText, style = MaterialTheme.typography.labelSmall, color = TL.textLow)
    }
}

/** 相机显示名。 */
private fun camLabel(cam: String): String = DashcamStore.camLabel(cam)

/** 全屏播放器标题行：事件起点，与列表行的格式一致。 */
private fun fmtEventTitle(event: DashcamEvent): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(event.startMs))

/**
 * 每段时长先估：车机按整分钟切段，相邻文件名的墙钟差就是段长，最后一段没有"下一段"
 * 就按标称 59.5s。误差 ≤ 每段 0.5s，进度条一打开就能按真实比例画。
 */
private fun segDurationsMs(event: DashcamEvent, cam: String): List<Long> {
    val clips = event.clips[cam] ?: return emptyList()
    return clips.mapIndexed { i, c ->
        val next = clips.getOrNull(i + 1)?.startEpochMs
        ((next ?: (c.startEpochMs + com.teslalink.app.tools.DashcamEvent.SEGMENT_MS)) - c.startEpochMs).coerceAtLeast(1_000L)
    }
}

/** wall-clock 时间码：文件名里的分段起点 + 播放进度。 */
private fun timecodeOf(startEpochMs: Long, posMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(startEpochMs + posMs))

/** 把 media3 的错误码翻译成人话 + 下一步怎么办（黑屏不能只说"失败了"）。 */
private fun explainPlaybackError(e: androidx.media3.common.PlaybackException): String {
    val cause = e.cause?.let { "${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
    return when (e.errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "读不到这个文件（U 盘可能已拔出或路径变了）$cause"
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
            "从 U 盘读流失败：OTG 上这个提供方可能不支持随机读取$cause"
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            "解码器开不出来——手机撑不住这个分辨率/路数，减少同屏机位再试$cause"
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ->
            "解码中途失败（文件损坏或码流异常）$cause"
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "容器解析失败：这个 mp4 的封装/编码车机版可能变了$cause"
        else -> "${e.errorCodeName} $cause"
    }
}

/** 播放控制条：内嵌视图和全屏 Dialog 共用同一份，按钮语义一致才不会"进了全屏像换了软件"。 */
@Composable
private fun PlaybackControls(
    playing: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    combinedMs: Long,
    totalMs: Long,
    wallText: String,
    trigger: Pair<String, () -> Unit>?,
    multiCam: Boolean,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(if (playing) "暂停" else "播放", Tone.Info, selected = true, onClick = onToggle)
            // 用时间说话，不暴露"段"：跨段跳转由统一时间轴处理，体感就是一条连续视频。
            Pill("−30 秒", Tone.Neutral, selected = true) { onSeek(combinedMs - 30_000L) }
            Pill("+30 秒", Tone.Neutral, selected = true) { onSeek(combinedMs + 30_000L) }
            // 事件目录多为"触发前 10 分钟预录"，撞击/保存那一刻在末尾：给个直达按钮。
            trigger?.let { Pill(it.first, Tone.Warn, selected = true, onClick = it.second) }
            if (multiCam) Pill("回到开头", Tone.Neutral, selected = true) { onSeek(0) }
        }
        Column {
            Text(
                "进度 ${formatMs(combinedMs)} / ${formatMs(totalMs)} · 画面时间 $wallText",
                style = MaterialTheme.typography.labelSmall,
                color = TL.textLow,
            )
            Slider(
                value = if (totalMs > 0) combinedMs.toFloat() else 0f,
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..(totalMs.coerceAtLeast(1).toFloat()),
                colors = SliderDefaults.colors(
                    thumbColor = TL.accent,
                    activeTrackColor = TL.accent,
                    inactiveTrackColor = TL.hairline,
                ),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun DashcamPlayer(event: DashcamEvent, speedPoints: List<com.teslalink.app.vm.DashboardViewModel.Point>) {    val context = androidx.compose.ui.platform.LocalContext.current
    val available = DashcamStore.CAMERA_ORDER.filter { event.clips[it]?.isNotEmpty() == true }
    // 机位名对不上 CAMERA_ORDER（老固件、奇怪目录）时这里会是空列表，下面 cams[0] 直接越界。
    if (available.isEmpty()) {
        Text(
            "这个事件里没有可识别的机位文件，换一个事件或重新扫描试试。",
            style = MaterialTheme.typography.bodyMedium,
            color = TL.textMid,
        )
        return
    }
    // 默认四路全开（他实测前四路能播）；解不动就关两路，或点下面的「只留前视重试」。
    var cams by remember(event) { mutableStateOf(available.take(4)) }
    var camNote by remember(event) { mutableStateOf<String?>(null) }
    var amapNote by remember(event) { mutableStateOf<Pair<Tone, String>?>(null) }
    var playState by remember(event) { mutableStateOf("准备中") }
    var playNote by remember(event) { mutableStateOf<Pair<Tone, String>?>(null) }
    var pendingSeekMs by remember(event) { mutableLongStateOf(-1L) }
    var segmentIndex by remember(event) { mutableIntStateOf(0) }
    var segPosMs by remember(event) { mutableLongStateOf(0L) }
    // 每段时长先用"文件名墙钟差"估出来：不然进度条要等播放器把整条 playlist 逐个准备完
    // 才知道总长，点开的前几秒就是一根没有刻度的条（他实测抱怨的正是这个）。真实时长
    // 等 onTimelineChanged 全窗口就绪后再替换。
    var durations by remember(event, cams) { mutableStateOf(segDurationsMs(event, cams[0])) }
    var fullscreen by remember(event) { mutableStateOf(false) }

    val players = remember(event, cams) {
        cams.map { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    }
    DisposableEffect(event, cams) { onDispose { players.forEach { it.release() } } }

    LaunchedEffect(event, cams) {
        cams.forEachIndexed { i, cam ->
            val clips = event.clips[cam]
            if (clips.isNullOrEmpty()) return@forEachIndexed
            players[i].setMediaItems(clips.map { MediaItem.fromUri(it.uri) })
            players[i].prepare()
        }
        // 总时长必须自己探一次：播放器对还没准备的段只给占位值（TIME_UNSET），
        // 不探的话进度条总长一直停在估算值，播到真末尾时条还差一截——就是"立刻已播完"的根因。
        val master = cams.firstOrNull() ?: return@LaunchedEffect
        val probed = withContext(Dispatchers.IO) {
            DashcamStore.segmentDurations(context, event.clips[master] ?: emptyList())
        }
        if (probed.isNotEmpty() && probed.size == durations.size) durations = probed
    }
    // 主视角时间轴长度（各分段时长），供拼接进度条使用。
    LaunchedEffect(players) {
        if (players.isEmpty()) return@LaunchedEffect
        players[0].addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                val w = androidx.media3.common.Timeline.Window()
                val ds = (0 until timeline.windowCount).map { timeline.getWindow(it, w).durationMs }
                // 逐格合并：拿到真值的换成真值，还没准备的保留探到的时长（占位是负数）。
                if (ds.isNotEmpty()) {
                    durations = durations.mapIndexed { i, est ->
                        ds.getOrNull(i)?.let { if (it > 0) it else est } ?: est
                    }
                }
            }
        })
    }
    // 播放状态与错误必须显出来：以前出错只是黑屏，谁也不知道是解码器不够、读不了文件还是编码不支持。
    DisposableEffect(players) {
        if (players.isEmpty()) return@DisposableEffect onDispose { }
        val listeners = players.mapIndexed { i, p ->
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (i == 0) {
                        playState = when (state) {
                            Player.STATE_IDLE -> "未就绪"
                            Player.STATE_BUFFERING -> "缓冲/读文件中…"
                            Player.STATE_READY -> if (p.playWhenReady) "播放中" else "已暂停"
                            Player.STATE_ENDED -> "已播完"
                            else -> playState
                        }
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (playNote == null) {
                        playNote = Tone.Bad to "第 ${i + 1} 路（${camLabel(cams[i])}）放不出来：" +
                            explainPlaybackError(error)
                    }
                }
            }
        }
        players.forEachIndexed { i, p -> p.addListener(listeners[i]) }
        onDispose { players.forEachIndexed { i, p -> runCatching { p.removeListener(listeners[i]) } } }
    }
    // 从机跟随主机：分段对齐 + 漂移 > 300ms 时校正。
    LaunchedEffect(players, cams) {
        if (players.isEmpty()) return@LaunchedEffect
        val master = players[0]
        while (true) {
            delay(150)
            segmentIndex = master.currentMediaItemIndex.coerceIn(0, (durations.size - 1).coerceAtLeast(0))
            segPosMs = master.currentPosition
            players.drop(1).forEach { p ->
                if (p.currentMediaItemIndex != master.currentMediaItemIndex) {
                    p.seekTo(master.currentMediaItemIndex, master.currentPosition)
                } else if (kotlin.math.abs(p.currentPosition - master.currentPosition) > 300) {
                    p.seekTo(master.currentPosition)
                }
                p.playWhenReady = master.playWhenReady
            }
        }
    }

    val totalMs = durations.sum()
    val combinedMs = durations.take(segmentIndex).sum() + segPosMs
    val masterClip = event.clips[cams[0]]?.getOrNull(segmentIndex)
    val wallMs = (masterClip?.startEpochMs ?: 0) + segPosMs

    fun togglePlay() {
        players[0].playWhenReady = !players[0].playWhenReady
    }

    /**
     * 落点相对真实窗口时长往回收 400ms：估出来的段长（墙钟差）每段比真实多约 0.5s，
     * 按估算时间轴算出的"跳到触发时刻"在真实时间轴上可能越过最后一段的结尾，
     * ExoPlayer 会把这次 seek 直接变成 STATE_ENDED——画面停在最后一帧，状态却写着"已播完"。
     * 有真实窗口时长（>0）就用它，还没有就退回估算值。
     */
    fun seekToWindow(i: Int, offMs: Long) {
        val tl = players[0].currentTimeline
        val real = if (i < tl.windowCount) {
            tl.getWindow(i, androidx.media3.common.Timeline.Window()).durationMs
        } else -1L
        val d = if (real > 0) real else (durations.getOrNull(i) ?: 0L)
        val capped = (offMs.coerceAtLeast(0L)).coerceAtMost((d - 400L).coerceAtLeast(0L))
        players.forEach { it.seekTo(i, capped) }
    }

    fun seekCombined(ms: Long) {
        // 夹在整条时间轴内：越界时老代码会跳回最后一段的开头，观感就是"拖一下倒回去了"。
        val target = ms.coerceIn(0L, (totalMs - 200L).coerceAtLeast(0L))
        var acc = 0L
        for (i in durations.indices) {
            val d = durations[i]
            if (target < acc + d) {
                seekToWindow(i, target - acc)
                return
            }
            acc += d
        }
        seekToWindow((durations.size - 1).coerceAtLeast(0), 0)
    }

    // 换机位会重建播放器：等新时间轴到位后回到原来的拼接时刻，避免每次切换都跳回开头。
    // 「跳到触发时刻」也走这条通道：时间轴还没就绪时先记下目标，就绪后再跳。
    LaunchedEffect(durations, pendingSeekMs, players) {
        val target = pendingSeekMs
        if (target >= 0 && durations.isNotEmpty()) {
            pendingSeekMs = -1L
            var acc = 0L
            for (i in durations.indices) {
                val d = durations[i]
                if (target < acc + d) {
                    seekToWindow(i, target - acc)
                    return@LaunchedEffect
                }
                acc += d
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // event.json 里车自己报的信息：滚动录像没有，哨兵/手动保存才有。
        if (event.city != null || event.lat != null || event.reason != null) {
            val tf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            Panel(tight = true) {
                KeyValue("城市", event.city ?: "未记录")
                event.triggerMs?.let { KeyValue("车记触发时刻", tf.format(Date(it)), Tone.Warn) }
                if (event.lat != null && event.lon != null) {
                    // 注意：车机没标坐标系；精度只到 3~4 位小数（约 10~100 m），够定位够报案，不够导航级。
                    KeyValue("车自报位置", "%.4f, %.4f".format(event.lat, event.lon))
                }
                KeyValue("触发原因", event.reasonText, if (event.isImpact) Tone.Bad else Tone.Neutral)
                event.cameraId?.let { KeyValue("event.json 机位编号", "#$it（车内部编号，与六路顺序不一定对应）") }
                if (event.lat != null && event.lon != null) {
                    ActionTile(
                        "在高德地图看这个位置",
                        onClick = {
                            amapNote = try {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(
                                            "androidamap://viewMap?sourceApplication=TeslaLink" +
                                                "&poiname=${Uri.encode(event.reasonText)}" +
                                                "&lat=${event.lat}&lon=${event.lon}&dev=0"
                                        )
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                Tone.Good to "已请求高德在标记点显示（车给的是 3~4 位小数的粗略位置）"
                            } catch (e: Exception) {
                                Tone.Bad to "没拉起来：${e.message}——手机没装高德或被系统拦了跳转"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        tone = Tone.Info,
                    )
                    amapNote?.let { HintBar(it.second, it.first) }
                }
            }
        }

        // 机位选择：盘里实际有 6 路，同屏最多放 4 路（再多解码撑不住）。
        if (available.size > 1) {
            Text(
                "机位（在播 ${cams.size}/${available.size}，上限 4）",
                style = MaterialTheme.typography.labelSmall,
                color = TL.textLow,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                available.forEach { cam ->
                    val on = cams.contains(cam)
                    Pill(
                        camLabel(cam),
                        if (on) Tone.Data else Tone.Neutral,
                        selected = on,
                    ) {
                        val next = if (on) cams.filterNot { it == cam }
                        else (cams + cam).sortedBy { available.indexOf(it) }
                        when {
                            next.isEmpty() -> camNote = "至少保留一路，否则没画面可看。"
                            next.size > 4 -> camNote = "最多同时 4 路：前视近 5.4MP，再多手机解码不动。先关掉一路再开。"
                            else -> {
                                camNote = null
                                playNote = null
                                pendingSeekMs = durations.take(segmentIndex).sum() + segPosMs
                                cams = next
                            }
                        }
                    }
                }
            }
            camNote?.let { HintBar(it, Tone.Warn) }
        }

        // 视频区：1~4 路宫格，主机始终是排在最前的一路（一般是前视）。
        // 全屏开着时不渲染这块：同一个 PlayerView 只能挂在一个窗口上，Dialog 全屏会接管渲染。
        if (!fullscreen) {
            cams.chunked(2).forEach { rowCams ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowCams.forEach { cam ->
                        val i = cams.indexOf(cam)
                        val clip = event.clips[cam]?.getOrNull(segmentIndex)
                        CameraTile(
                            player = players[i],
                            label = camLabel(cam),
                            timecode = clip?.let { timecodeOf(it.startEpochMs, segPosMs) },
                            speedKmh = if (cam == "front") speedAt(speedPoints, wallMs) else null,
                            speedSeries = if (cam == "front") speedSeries(speedPoints, wallMs) else null,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(4f / 3f),
                        )
                    }
                    if (rowCams.size == 1) Box(Modifier.weight(1f))
                }
            }
        }

        // 状态行 + 错误：黑屏时必须有话说（是还在读 U 盘、还是解码器不够 / 编码不支持）。
        // 文案不提"分段"：对用户来说这就是一段连续视频，分段是内部的实现细节。
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("播放器：$playState", if (playNote != null) Tone.Bad else Tone.Neutral)
            Pill("总长 ${formatMs(totalMs)}", if (durations.isEmpty()) Tone.Warn else Tone.Neutral)
            Pill("⛶ 全屏", if (fullscreen) Tone.Data else Tone.Neutral, selected = true) {
                fullscreen = !fullscreen
            }
        }
        playNote?.let {
            HintBar(it.second, it.first)
            ActionTile(
                "只留前视重试",
                onClick = {
                    playNote = null
                    pendingSeekMs = -1L
                    cams = available.take(1)
                },
                modifier = Modifier.fillMaxWidth(),
                tone = Tone.Info,
                stateLine = "多路同时解码失败时，先退到单路确认文件本身能放",
            )
        }

        PlaybackControls(
            playing = players[0].playWhenReady,
            onToggle = { togglePlay() },
            onSeek = { seekCombined(it) },
            combinedMs = combinedMs,
            totalMs = totalMs,
            wallText = masterClip?.let { timecodeOf(it.startEpochMs, segPosMs) } ?: "--",
            trigger = event.triggerIn(cams.firstOrNull() ?: "front")?.let { (seg, off) ->
                "⚑ 跳到触发时刻" to { pendingSeekMs = durations.take(seg).sum() + off }
            },
            multiCam = cams.size > 1,
            enabled = durations.isNotEmpty(),
        )
        HintBar(
            "机位在上方小丸子里自选，最多同时 4 路（前视近 5.4MP，再多手机解码不动）。车速叠加来自本 app " +
                "蓝牙采样——只有 app 在跑期间录下的画面才有数据；MP4 实测只有视频轨、没有隐藏数据轨，所以只能这么叠。",
            Tone.Neutral,
        )

        // 全屏：同一批 ExoPlayer 实例换一副骨架渲染，暂停/进度无缝衔接，不重新起播。
        if (fullscreen) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { fullscreen = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                fmtEventTitle(event),
                                fontSize = 14.sp,
                                color = TL.textHi,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(event.reasonText, fontSize = 11.sp, color = TL.textMid, maxLines = 1)
                        }
                        Pill("退出全屏", Tone.Neutral, selected = true) { fullscreen = false }
                    }
                    cams.chunked(2).forEach { rowCams ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowCams.forEach { cam ->
                                val i = cams.indexOf(cam)
                                val clip = event.clips[cam]?.getOrNull(segmentIndex)
                                CameraTile(
                                    player = players[i],
                                    label = camLabel(cam),
                                    timecode = clip?.let { timecodeOf(it.startEpochMs, segPosMs) },
                                    speedKmh = if (cam == "front") speedAt(speedPoints, wallMs) else null,
                                    speedSeries = if (cam == "front") speedSeries(speedPoints, wallMs) else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                            }
                            if (rowCams.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                    PlaybackControls(
                        playing = players[0].playWhenReady,
                        onToggle = { togglePlay() },
                        onSeek = { seekCombined(it) },
                        combinedMs = combinedMs,
                        totalMs = totalMs,
                        wallText = masterClip?.let { timecodeOf(it.startEpochMs, segPosMs) } ?: "--",
                        trigger = event.triggerIn(cams.firstOrNull() ?: "front")?.let { (seg, off) ->
                            "⚑ 跳到触发时刻" to { pendingSeekMs = durations.take(seg).sum() + off }
                        },
                        multiCam = cams.size > 1,
                        enabled = durations.isNotEmpty(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraTile(
    player: ExoPlayer,
    label: String,
    timecode: String?,
    speedKmh: Int?,
    speedSeries: List<Int>?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                // 从 XML 建：surface_type=texture_view（默认 surface_view 在 Compose 里会被
                // 父级背景挡住 → 黑屏），media3 1.4.1 没有公开的 setSurfaceType。
                android.view.LayoutInflater.from(ctx)
                    .inflate(
                        com.teslalink.app.R.layout.cam_player_view,
                        android.widget.FrameLayout(ctx),
                        false,
                    ) as PlayerView
            },
            update = { it.player = player },
        )
        Text(
            label,
            fontSize = 10.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .background(Color(0x66000000))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
        timecode?.let {
            Text(
                it,
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color(0x66000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        if (speedKmh != null) {
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color(0x66000000))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    "$speedKmh",
                    fontSize = 20.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = Color.White,
                )
                Text("km/h", fontSize = 8.sp, color = Color(0xB3FFFFFF))
                if (!speedSeries.isNullOrEmpty() && speedSeries.size >= 2) {
                    androidx.compose.foundation.Canvas(
                        Modifier
                            .padding(top = 2.dp)
                            .fillMaxWidth()
                            .height(22.dp)
                    ) {
                        val maxV = (speedSeries.max() + 10f).coerceAtLeast(50f)
                        val stepX = size.width / (speedSeries.size - 1)
                        fun y(v: Float) = size.height - (v / maxV) * size.height
                        for (i in 1 until speedSeries.size) {
                            drawLine(
                                Color(0xFF38BDF8),
                                Offset(stepX * (i - 1), y(speedSeries[i - 1].toFloat())),
                                Offset(stepX * i, y(speedSeries[i].toFloat())),
                                2f,
                            )
                        }
                        drawLine(
                            Color.White.copy(alpha = 0.4f),
                            Offset(0f, y(80f)),
                            Offset(size.width, y(80f)),
                            1f,
                        )
                    }
                }
            }
        }
    }
}

/** 播放时刻（墙钟）最近的一次采样车速。 */
private fun speedAt(points: List<com.teslalink.app.vm.DashboardViewModel.Point>, wallMs: Long): Int? {
    if (points.isEmpty()) return null
    var best: com.teslalink.app.vm.DashboardViewModel.Point? = null
    var bestDelta = Long.MAX_VALUE
    for (p in points) {
        val d = kotlin.math.abs(p.t - wallMs)
        if (d < bestDelta) {
            bestDelta = d
            best = p
        }
    }
    // 播放时刻离任何采样点超过 5 秒：没有可信数据。
    return best?.takeIf { bestDelta <= 5_000 }?.let { kotlin.math.ceil(it.v).toInt() }
}

/** 播放时刻前 60 秒的车速序列（迷你曲线）。 */
private fun speedSeries(
    points: List<com.teslalink.app.vm.DashboardViewModel.Point>,
    wallMs: Long,
): List<Int> =
    points.filter { it.t in (wallMs - 60_000)..wallMs }.map { kotlin.math.ceil(it.v).toInt() }

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}
