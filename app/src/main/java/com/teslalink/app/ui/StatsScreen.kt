package com.teslalink.app.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.teslalink.app.ui.components.CollapsibleSection
import com.teslalink.app.ui.components.HintBar
import com.teslalink.app.ui.components.PageHeader
import com.teslalink.app.ui.components.Panel
import com.teslalink.app.ui.components.Pill
import com.teslalink.app.ui.components.SectionLabel
import com.teslalink.app.ui.components.Tone
import com.teslalink.app.ui.theme.TL
import com.teslalink.app.ui.components.LineChart
import com.teslalink.app.vm.DashboardViewModel
import com.teslalink.app.vm.Trip
import com.teslalink.app.vm.TripPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.views.CustomZoomButtonsController

/** 统计页：BLE 实时采样的行驶曲线（车速/功率）+ 叠加在地图上的行驶轨迹。
 *  电量/充电功率曲线已删：行驶中慢车道不发 CHARGE，这两条只有停车时才有数据点，
 *  停车的电量是一条平线、充电功率在仪表指示条和控制页都有，留在"行驶统计"里全是死重。 */
@Composable
fun StatsScreen(dashboard: DashboardViewModel) {
    val speed by dashboard.speed.collectAsState()
    val power by dashboard.power.collectAsState()
    val trips by dashboard.trips.collectAsState()

    DisposableEffect(Unit) {
        dashboard.startBlePolling()
        onDispose { dashboard.stopBlePolling() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            "统计",
            "停在「仪表」或本页时持续采样，本次启动内全量保留",
            trailing = {
                Pill(
                    "${speed.size}/${power.size} 点",
                    if (speed.isEmpty()) Tone.Neutral else Tone.Data,
                )
            },
        )

        SectionLabel("行驶曲线", "折叠态看数字，点开看全程趋势")
        // 摘要要对全表 map+average：数据按 1Hz 发布后这是每秒一次的 O(N)，用 remember 挂在数据上，
        // 其它原因触发的重组（折叠展开、滚动）不再重算。
        MetricSection("车速", speed, TL.accent, " km/h", remember(speed) { speedSummary(speed) }, "大数字在「仪表」页")
        MetricSection("功率", power, Color(0xFFFF9800), " kW", remember(power) { powerSummary(power) }, "正为输出，负为回收")

        SectionLabel(
            "行驶轨迹",
            "行驶中每 10 秒一个点 · 存本机，磁盘保留最近 ${com.teslalink.app.vm.TripRecorder.KEEP_TRIPS} 条",
        )
        var selected by remember { mutableStateOf<Trip?>(null) }
        var pendingDelete by remember { mutableStateOf<Trip?>(null) }
        var tripNote by remember { mutableStateOf<Pair<Tone, String>?>(null) }
        var overlayAll by remember { mutableStateOf(false) }
        val trip = selected?.takeIf { trips.contains(it) } ?: trips.firstOrNull()
        if (trip == null) {
            Panel {
                Text(
                    "还没有轨迹：开车（车速 > 2 km/h）并停留在「仪表」或本页时自动记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TL.textLow,
                )
            }
        } else {
            if (trips.size > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    trips.forEach { t ->
                        Pill(
                            "${fmt.format(java.util.Date(t.startedAt))} · ${"%.1f".format(t.distanceKm)} km",
                            if (t == trip) Tone.Data else Tone.Neutral,
                            selected = t == trip,
                            onClick = { selected = t },
                        )
                    }
                }
            }
            Panel {
                TripStats(trip, trips.size, trailing = {
                    Pill(
                        if (overlayAll) "叠加中" else "叠加全部",
                        if (overlayAll) Tone.Data else Tone.Neutral,
                        selected = overlayAll,
                        onClick = { overlayAll = !overlayAll },
                    )
                })
                // 折线重建的节流键：行程每来一个 GPS 点 / 叠加开关与行程集合变化才重建，
                // 速度曲线的 250ms 刷新不碰地图。
                val mapKey = remember(trip.points.size, overlayAll, trips.size) { Any() }
                TripMap(
                    trip,
                    if (overlayAll) trips.take(60) else emptyList(),
                    mapKey,
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }

        if (trips.isNotEmpty()) {
            CollapsibleSection(
                "全部轨迹",
                "${trips.size} 条 · 累计 ${"%.0f".format(trips.sumOf { it.distanceKm })} km · 点击加载到地图 · ✕ 删除单条",
            ) {
                val monthFmt = java.text.SimpleDateFormat("yyyy年M月", java.util.Locale.getDefault())
                val timeFmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                trips.groupBy { monthFmt.format(java.util.Date(it.startedAt)) }.forEach { (month, list) ->
                    Text(
                        month,
                        style = TL.eyebrow,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    list.forEach { t ->
                        val isSel = t == trip
                        val isOpen = t.endedAt == 0L
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) TL.accent.copy(alpha = 0.12f) else Color.Transparent),
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = t }
                                    .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        timeFmt.format(java.util.Date(t.startedAt)) +
                                            if (isOpen) " · 进行中" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSel) TL.accent else TL.textHi,
                                    )
                                    Text(
                                        "${t.points.size} 点 · 最高 ${kotlin.math.ceil(t.maxKmh).toInt()} km/h",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TL.textLow,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "%.1f km".format(t.distanceKm),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TL.textHi,
                                    )
                                    val minutes = t.durationMs / 60_000
                                    Text(
                                        durationCn(minutes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TL.textLow,
                                    )
                                }
                                Pill("✕", if (isOpen) Tone.Warn else Tone.Neutral, selected = true) {
                                    pendingDelete = t
                                }
                            }
                            // 删除是动真数据（连本地 JSON 一起没），一律二次确认。
                            if (pendingDelete == t) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (isOpen) TL.warn.copy(alpha = 0.12f) else TL.bad.copy(alpha = 0.10f))
                                        .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        when {
                                            isOpen -> "这段还在录。删掉后下一个采样点会另起一条新轨迹。"
                                            else -> "删掉这 ${t.points.size} 个点？本地文件一起删，不可恢复。"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TL.textMid,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Pill("确认删除", Tone.Bad, selected = true) {
                                        val ok = dashboard.deleteTrip(t)
                                        tripNote = if (ok) {
                                            Tone.Good to "已删除 ${timeFmt.format(java.util.Date(t.startedAt))} 那条" +
                                                if (isOpen) "（当前这段重新开始记录）" else ""
                                        } else {
                                            Tone.Bad to "没删掉：这条已经不在列表里了（可能刚被收尾或清理）"
                                        }
                                        pendingDelete = null
                                    }
                                    Pill("取消", Tone.Neutral, selected = true) { pendingDelete = null }
                                }
                            }
                        }
                    }
                }
                tripNote?.let { HintBar(it.second, it.first) }
            }
        }

        Text(
            "采样间隔：行驶 250 ms / 停车 600 ms（车速与档位），电量与温度 2.5 s。车辆数据全部来自蓝牙直连，联网只用于地图底图和可选的高德天气。",
            style = MaterialTheme.typography.labelSmall,
            color = TL.textLow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
        Pill("清空曲线", Tone.Warn, selected = true, onClick = dashboard::clear)
    }
}

@Composable
private fun MetricSection(
    title: String,
    points: List<DashboardViewModel.Point>,
    color: Color,
    unit: String,
    summary: String,
    hint: String? = null,
) {
    CollapsibleSection(title, summary) {
        Panel(subtitle = hint) {
            LineChart(title, points, color, unit, Modifier.fillMaxWidth())
        }
    }
}

private fun speedSummary(points: List<DashboardViewModel.Point>): String {
    if (points.isEmpty()) return "等待采样…"
    val v = points.map { it.v }
    return "均值 ${"%.0f".format(v.average().toFloat())} · 峰值 ${"%.0f".format(v.max())} · 当前 ${kotlin.math.ceil(v.last()).toInt()} km/h"
}

private fun powerSummary(points: List<DashboardViewModel.Point>): String {
    if (points.isEmpty()) return "等待采样…"
    val v = points.map { it.v }
    return "均值 ${"%.1f".format(v.average().toFloat())} · 峰值 ${"%.1f".format(v.max())} · 当前 ${"%.1f".format(v.last())} kW"
}

/** 用时统一按"行程自己的跨度"显示：满一小时走 h:mm（与仪表卡同一口径），不满就写分。 */
private fun durationCn(minutes: Long): String =
    if (minutes >= 60) "${minutes / 60}:${"%02d".format(minutes % 60)}" else "${minutes}分"

@Composable
private fun TripStats(trip: Trip, total: Int, trailing: @Composable () -> Unit = {}) {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    val minutes = trip.durationMs / 60_000
    // 四格等宽：原来四格按内容 intrinsic 排 + 18dp 间距，超出可用宽度后最后一格
    // 「轨迹点 309」被压成一列竖字（他截图里就是这个）。
    @Composable fun kv(k: String, v: String, m: Modifier) {
        Column(m) {
            Text(k, style = MaterialTheme.typography.labelSmall, color = TL.textLow, maxLines = 1)
            Text(v, style = MaterialTheme.typography.titleMedium, color = TL.textHi, maxLines = 1)
        }
    }
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${fmt.format(java.util.Date(trip.startedAt))} · 共 $total 条记录",
                style = MaterialTheme.typography.labelSmall,
                color = TL.textLow,
            )
            trailing()
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val w = Modifier.weight(1f)
            kv(if (trip.distanceFromOdometer) "距离·里程计" else "距离·GPS", "%.1f km".format(trip.distanceKm), w)
            kv("时长", durationCn(minutes), w)
            kv("最高", "${kotlin.math.ceil(trip.maxKmh).toInt()} km/h", w)
            kv("轨迹点", "${trip.points.size}", w)
        }
    }
}

/**
 * 轨迹叠加地图：CARTO 深色瓦片（与 app 主题一致，磁盘缓存），折线按速度着色，
 * 绿点起点、红点终点。无网络时瓦片是灰底，但轨迹折线照常绘制。
 */
@Composable
private fun TripMap(trip: Trip, overlayTrips: List<Trip>, rebuildKey: Any?, modifier: Modifier = Modifier) {
    val added = remember { mutableListOf<Overlay>() }
    var builtFor by remember { mutableStateOf<Any?>(null) }
    var fittedFor by remember { mutableStateOf<Any?>(null) }
    val res = LocalContext.current.resources
    val context = LocalContext.current
    // MapView 提到 remember 里：Attached 生命周期自己管（osmdroid 的 onResume/onPause/onDetach
    // 负责瓦片线程与刷新调度，旧实现从不挂钩，离开页面后这些线程照跑）。
    val map = remember(context) {
        MapView(context).apply {
            setTileSource(amapTiles)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.5)
            // 高德只有浅色栅格：整体调暗到 ~30% 亮度，贴合全 app 深色体系（不用反色，
            // 反色会把路名和配色翻成怪色相）。
            overlayManager.tilesOverlay.setColorFilter(
                ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            0.30f, 0f, 0f, 0f, 0f,
                            0f, 0.30f, 0f, 0f, 0f,
                            0f, 0f, 0.30f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f,
                        )
                    )
                )
            )
        }
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(map, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> map.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }
    Box(modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(10.dp)),
            factory = { map },
            update = { m ->
                // 旧实现在每次重组（行驶中 ~4Hz，速度列表驱动）里全量 dropJumps + 重建全部
                // 折线。折线只依赖行程内容与叠加开关，用 rebuildKey 门控——行程每来一个
                // GPS 点才重建一次；速度/功率列表的刷新不再触碰地图。
                if (builtFor != rebuildKey) {
                    builtFor = rebuildKey
                    added.forEach { m.overlays.remove(it) }
                    added.clear()
                    // 叠加模式：其他行程半透明单色垫底（每条抽稀到 ≤200 点，最多 60 条），当前行程压顶层。
                    // 刺头点先滤掉再抽稀：老行程里落盘的漂移点不该跟着上镜。
                    overlayTrips.filter { it != trip }.forEach { t ->
                        val simplified = decimate(com.teslalink.app.vm.dropJumps(t.points), 200)
                        if (simplified.size >= 2) {
                            val line = Polyline(m).apply {
                                setPoints(simplified.map { wgs84ToGcj02(it.lat, it.lng) })
                                outlinePaint.color = Color(0xFF38BDF8).copy(alpha = 0.28f).toArgb()
                                outlinePaint.strokeWidth = 6f
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                            }
                            m.overlays.add(line)
                            added.add(line)
                        }
                    }
                    val pts = com.teslalink.app.vm.dropJumps(trip.points)
                    if (pts.isNotEmpty()) {
                        // 相邻同色段合并成一条折线，减少 overlay 数量。
                        val runs = ArrayList<Pair<Color, MutableList<GeoPoint>>>()
                        var curColor: Color? = null
                        var cur = ArrayList<GeoPoint>()
                        for (i in pts.indices) {
                            val c = tripColor(pts[i].kmh)
                            val g = wgs84ToGcj02(pts[i].lat, pts[i].lng)
                            if (c != curColor) {
                                if (cur.size > 1) runs.add(curColor!! to cur)
                                val seed = ArrayList<GeoPoint>()
                                if (i > 0) seed.add(wgs84ToGcj02(pts[i - 1].lat, pts[i - 1].lng))
                                seed.add(g)
                                curColor = c
                                cur = seed
                            } else {
                                cur.add(g)
                            }
                        }
                        if (cur.size > 1) runs.add(curColor!! to cur)
                        runs.forEach { (color, geo) ->
                            // 辉光双层：宽软光晕 + 清晰主线，霓虹感来自这两层的叠加。
                            val glowLine = Polyline(m).apply {
                                setPoints(geo)
                                outlinePaint.color = color.copy(alpha = 0.16f).toArgb()
                                outlinePaint.strokeWidth = 26f
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                            }
                            val coreLine = Polyline(m).apply {
                                setPoints(geo)
                                outlinePaint.color = color.toArgb()
                                outlinePaint.strokeWidth = 8f
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                            }
                            m.overlays.add(glowLine)
                            added.add(glowLine)
                            m.overlays.add(coreLine)
                            added.add(coreLine)
                        }
                        val startMark = Marker(m).apply {
                            position = wgs84ToGcj02(pts.first().lat, pts.first().lng)
                            setAnchor(0.5f, 0.5f)
                            icon = tripMarkerIcon(res, Color(0xFF4CAF50).toArgb())
                            title = "起点"
                        }
                        val endMark = Marker(m).apply {
                            position = wgs84ToGcj02(pts.last().lat, pts.last().lng)
                            setAnchor(0.5f, 0.5f)
                            icon = tripMarkerIcon(res, Color(0xFFFF5252).toArgb())
                            title = "终点"
                        }
                        m.overlays.add(startMark)
                        added.add(startMark)
                        m.overlays.add(endMark)
                        added.add(endMark)
                    }
                }
                val fitKey = trip to overlayTrips.isEmpty()
                if (fittedFor != fitKey && trip.points.size >= 2) {
                    fittedFor = fitKey
                    // 叠加模式下缩放到全部轨迹的外包框；单条模式只看当前行程。
                    // 外包框同样按滤过刺头的点算：一个漂移点就能把缩放拉到几公里外。
                    val geo = (listOf(trip) + overlayTrips)
                        .flatMap { decimate(com.teslalink.app.vm.dropJumps(it.points), 200) }
                        .map { wgs84ToGcj02(it.lat, it.lng) }
                    val bb = BoundingBox.fromGeoPoints(geo)
                    // 布局完成后再缩放，否则按 0 尺寸计算会得到错误 zoom。
                    m.post { m.zoomToBoundingBox(bb.increaseByScale(1.3f), false) }
                }
                m.invalidate()
            },
        )
        Text(
            "底图 © 高德地图",
            style = TL.mono.copy(fontSize = 9.sp, color = TL.textMid),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
        )
    }
}

/** 均匀抽稀（含首尾）：叠加几十条轨迹时控制点数，画起来不卡、形状无差。 */
private fun decimate(pts: List<TripPoint>, max: Int): List<TripPoint> {
    // max=1 会让 step 变成 Infinity，(i*step).toInt() 溢出成 Int.MAX_VALUE 再 pts[idx] 越界。
    require(max >= 2) { "抽稀目标点数至少 2，收到 $max" }
    if (pts.size <= max) return pts
    val step = (pts.size - 1).toDouble() / (max - 1)
    val out = ArrayList<TripPoint>(max + 1)
    var lastIdx = -1
    for (i in 0 until max) {
        val idx = (i * step).toInt()
        if (idx != lastIdx) {
            out.add(pts[idx])
            lastIdx = idx
        }
    }
    if (out.last() != pts.last()) out.add(pts.last())
    return out
}

/** 轨迹端点图标：外圈光晕 + 亮色环 + 实心核 + 白心，替代系统默认大头针。 */
private fun tripMarkerIcon(res: android.content.res.Resources, core: Int): BitmapDrawable {
    val bmp = Bitmap.createBitmap(44, 44, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = core
    p.alpha = 45
    c.drawCircle(22f, 22f, 20f, p)
    p.alpha = 210
    p.style = Paint.Style.STROKE
    p.strokeWidth = 4f
    c.drawCircle(22f, 22f, 13f, p)
    p.style = Paint.Style.FILL
    p.alpha = 255
    c.drawCircle(22f, 22f, 8f, p)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(22f, 22f, 3f, p)
    return BitmapDrawable(res, bmp)
}

/** 轨迹按速度着色（与仪表速度分档同口径：0/66/88/110/132）。 */
private fun tripColor(kmh: Float): Color = when {
    kmh < 66f -> Color(0xFF38BDF8)
    kmh < 88f -> Color(0xFFE8EAED)
    kmh < 110f -> Color(0xFFFFB300)
    kmh < 132f -> Color(0xFFFF7043)
    else -> Color(0xFFFF5252)
}

/**
 * 高德栅格瓦片（公开端点，无需 API key）。注意：高德瓦片是 GCJ-02 坐标系，
 * 打点前必须把 WGS-84 轨迹转成 GCJ-02，否则整体偏移 100–700 m。
 *
 * 授权边界：这个端点不是高德开放平台接口，仅限个人非商业使用；需要商用或大流量
 * 请换成自己申请了授权的底图源（改这一个 tile source 即可）。高德调整策略时同样换源。
 * 出处与第三方服务声明见根目录 NOTICE。
 */
private val amapTiles = object : OnlineTileSourceBase(
    "AMap", 3, 19, 256, ".png", arrayOf("https://wprd01.is.autonavi.com/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        getBaseUrl() + "appmaptile?x=" + MapTileIndex.getX(pMapTileIndex) +
            "&y=" + MapTileIndex.getY(pMapTileIndex) +
            "&z=" + MapTileIndex.getZoom(pMapTileIndex) +
            "&lang=zh_cn&size=1&scl=1&style=7"
}

private fun wgs84ToGcj02(lat: Double, lng: Double): GeoPoint {
    val (a, b) = com.teslalink.app.tools.Coords.wgs84ToGcj02(lat, lng)
    return GeoPoint(a, b)
}
