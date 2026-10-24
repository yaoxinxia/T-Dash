package com.teslalink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslalink.app.debug.DebugLog
import com.teslalink.app.ui.components.ActionTile
import com.teslalink.app.ui.components.PageHeader
import com.teslalink.app.ui.components.Panel
import com.teslalink.app.ui.components.Pill
import com.teslalink.app.ui.components.Tone
import com.teslalink.app.ui.theme.TL
import com.teslalink.app.vehicle.VehicleConnection
import com.teslalink.app.vm.LinkViewModel

/**
 * 调试页：链路自检 + 分级日志。
 * 蓝牙广播发现已验证通过，扫描工具与广播日志已移除，这里只保留当前要排障的内容。
 */
@Composable
fun DebugScreen(link: LinkViewModel) {
    val lines by DebugLog.lines.collectAsState()
    val verbose by DebugLog.verboseState.collectAsState()
    val clipboard = LocalClipboardManager.current
    val selfTesting by link.selfTestRunning.collectAsState()
    val state by link.state.collectAsState()
    val keyPaired by link.keyPaired.collectAsState()
    val mcuReady by link.mcuReady.collectAsState()
    var tagFilter by remember { mutableStateOf<String?>(null) }
    val crash by com.teslalink.app.debug.CrashLog.pending.collectAsState()

    // 屏幕常亮由 AppRoot 管理（调试页停用时保持常亮，方便跑完一分钟自检）。

    val shown = if (tagFilter == null) lines else lines.filter { it.tag == tagFilter }
    val tags = lines.map { it.tag }.distinct()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            "调试",
            "一次跑完 9 步，把车辆返回的故障码全部留在日志里",
            trailing = {
                Pill(
                    when (state) {
                        VehicleConnection.State.CONNECTED -> "链路 已连接"
                        VehicleConnection.State.CONNECTING -> "链路 连接中"
                        VehicleConnection.State.DISCONNECTED -> "链路 未连接"
                    },
                    if (state == VehicleConnection.State.CONNECTED) Tone.Good else Tone.Bad,
                )
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("钥匙 ${if (keyPaired) "已配对" else "未配对"}", if (keyPaired) Tone.Good else Tone.Warn)
            Pill("车机 ${if (mcuReady) "在线" else "未就绪"}", if (mcuReady) Tone.Good else Tone.Warn)
            Pill("日志 ${shown.size}/${lines.size}", Tone.Neutral)
        }

        // 上次闪退的现场。崩溃那一刻内存里的日志环全丢，唯一能留下的就是这份落盘的东西，
        // 所以它必须出现在一进页就能看到的地方，并且能一键复制走。
        crash?.let { stack ->
            Panel(
                title = "上次异常退出 · 现场已恢复",
                subtitle = "复制下面这段发给我就能定位是哪一行炸了；点「知道了」清掉，不会每次开 app 重复报。",
            ) {
                Text(
                    stack,
                    style = TL.mono,
                    color = TL.bad,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(
                        "复制现场",
                        onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(stack)) },
                        modifier = Modifier.weight(1f),
                        tone = Tone.Info,
                        emphasize = true,
                    )
                    ActionTile(
                        "知道了",
                        onClick = { com.teslalink.app.debug.CrashLog.dismiss() },
                        modifier = Modifier.width(110.dp),
                    )
                }
            }
        }

        Panel(
            title = "链路自检",
            subtitle = "①VCSEC 会话 ②钥匙链 ③本机公钥在册 ④车机握手 ⑤唤醒 ⑥车机重试 ⑦Ping ⑧单类遥测 ⑨全类遥测",
        ) {
            ActionTile(
                if (selfTesting) "自检运行中…" else "开始自检（约 1 分钟）",
                onClick = link::runSelfTest,
                enabled = !selfTesting && state == VehicleConnection.State.CONNECTED,
                emphasize = true,
                tone = Tone.Info,
                stateLine = if (state != VehicleConnection.State.CONNECTED) "需要先连接：打开 app 会自动连，也可到「设置」手动连接" else null,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(
                    if (verbose) "协议收发：开" else "协议收发：关",
                    if (verbose) Tone.Data else Tone.Neutral,
                    selected = verbose,
                    onClick = { DebugLog.setVerbose(!verbose) },
                )
                Pill("复制全部", Tone.Info, onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            lines.joinToString("\n") {
                                "${TIME_FORMAT.format(java.util.Date(it.timeMs))} [${it.tag}] ${it.message}"
                            }
                        )
                    )
                })
                Pill("清空", Tone.Neutral, onClick = {
                    DebugLog.clear()
                    tagFilter = null
                })
            }
            Text(
                "「协议收发」打开后记录每条 BLE/协议报文与帧 hex，轮询时会刷屏，只在排障时开。",
                style = TL.mono.copy(fontSize = 10.sp, color = TL.textLow),
            )
        }

        Panel(title = "日志", tight = true) {
            // 过滤行必须一直可见：否则被筛成空列表时看不出是"没日志"还是"筛掉了"，也点不回"全部"。
            if (tags.isNotEmpty() || tagFilter != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill("全部", Tone.Neutral, selected = tagFilter == null) { tagFilter = null }
                    tags.forEach { tag ->
                        Pill(
                            "$tag ${lines.count { it.tag == tag }}",
                            toneForTag(tag),
                            selected = tagFilter == tag,
                        ) { tagFilter = if (tagFilter == tag) null else tag }
                    }
                }
            }
            val logState = rememberLazyListState()
            // 环形缓冲写满后 size 不再变化，所以用最新一条的时间戳驱动自动滚动。
            LaunchedEffect(shown.lastOrNull()?.timeMs) {
                if (shown.isNotEmpty()) logState.animateScrollToItem((shown.size - 1).coerceAtLeast(0))
            }
            LazyColumn(
                state = logState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .background(TL.sunken),
            ) {
                itemsIndexed(shown.takeLast(400)) { _, line ->
                    Text(
                        buildString {
                            append(TIME_FORMAT.format(java.util.Date(line.timeMs)))
                            append("  ")
                            append(line.tag.padEnd(10))
                            append("  ")
                            append(line.message)
                        },
                        style = TL.mono.copy(color = lineColor(line.tag, line.message)),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

private fun toneForTag(tag: String): Tone = when (tag) {
    "selftest" -> Tone.Warn
    "dispatcher" -> Tone.Data
    "vehicle" -> Tone.Info
    "ble-gatt", "ble-tx", "ble-rx", "ble-framing" -> Tone.Neutral
    else -> Tone.Neutral
}

private fun lineColor(tag: String, message: String): Color = when {
    message.contains("失败") || message.contains("错误") || message.contains("✗") -> Color(0xFFFF8A80)
    tag == "selftest" -> Color(0xFFFFD54F)
    tag == "dispatcher" || tag.startsWith("ble") -> Color(0xFF9FB3C8)
    else -> Color(0xFFD6E2EA)
}

private val TIME_FORMAT = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
