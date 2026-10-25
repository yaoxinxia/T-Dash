package com.teslalink.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslalink.app.AppContainer
import com.teslalink.app.store.AccidentProfile
import com.teslalink.app.store.ProfileStore
import com.teslalink.app.ui.components.ActionTile
import com.teslalink.app.ui.components.CollapsibleSection
import com.teslalink.app.ui.components.HintBar
import com.teslalink.app.ui.components.KeyValue
import com.teslalink.app.ui.components.PageHeader
import com.teslalink.app.ui.components.Panel
import com.teslalink.app.ui.components.Pill
import com.teslalink.app.ui.components.SectionLabel
import com.teslalink.app.ui.components.Tone
import com.teslalink.app.ui.theme.TL
import com.teslalink.app.vehicle.VehicleConnection
import com.teslalink.app.vm.LinkViewModel
import com.teslalink.app.vm.SpeedBands
import com.teslalink.app.vm.SpeedPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置：连接（VIN/扫描/配对）、兜底参数、本机钥匙、会话缓存、开发者模式。
 * 没有独立的「车辆」页——连接是打开 app 自动做的，这里负责配置与补救。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    link: LinkViewModel,
    dashboard: com.teslalink.app.vm.DashboardViewModel,
) {
    val scope = rememberCoroutineScope()
    val publicKey by link.publicKeyHex.collectAsState()
    val bands by dashboard.speedBands.collectAsState()
    // 反馈一律就地（他自己定的规矩）：每个操作的结果显示在操作旁边，不塞进页顶——
    // 设置页很长，页顶提示在保存 MAC/Key/个人信息时根本看不见。
    var customName by remember { mutableStateOf("") }
    var macInput by remember { mutableStateOf("") }
    var amapKey by remember { mutableStateOf("") }
    var weatherOn by remember { mutableStateOf(false) }
    var resetNote by remember { mutableStateOf<Pair<Tone, String>?>(null) }
    var macNote by remember { mutableStateOf<Pair<Tone, String>?>(null) }
    var nameNote by remember { mutableStateOf<Pair<Tone, String>?>(null) }
    var amapNote by remember { mutableStateOf<Pair<Tone, String>?>(null) }
    var rotateNote by remember { mutableStateOf<Pair<Tone, String>?>(null) }
    var cacheNote by remember { mutableStateOf<Pair<Tone, String>?>(null) }

    val vin by link.vin.collectAsState()
    val state by link.state.collectAsState()
    val keyPaired by link.keyPaired.collectAsState()
    val mcuReady by link.mcuReady.collectAsState()
    val autoReconnecting by link.autoReconnecting.collectAsState()
    val pairing by link.pairingPhase.collectAsState()
    val ack by link.pairingAck.collectAsState()
    val busy by link.busy.collectAsState()
    val everConnected by link.everConnected.collectAsState()
    val keyPairedEver by link.keyPairedEver.collectAsState()
    val devMode by link.devMode.collectAsState()

    val connected = state == VehicleConnection.State.CONNECTED

    // 设置页的三种模式由"识别过的状态数据"驱动：首次 / 待配对 / 日常。
    val stage = when {
        keyPairedEver -> SetupStage.PAIRED
        everConnected -> SetupStage.CONNECTED_NOT_PAIRED
        else -> SetupStage.NEVER_CONNECTED
    }

    LaunchedEffect(Unit) {
        customName = container.prefs.getString("ble_name_override") ?: ""
        macInput = container.prefs.getString("ble_direct_addr") ?: ""
        amapKey = container.prefs.getString("amap_key") ?: ""
        weatherOn = container.prefs.getString("weather_on") == "1"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            "设置",
            "连接、钥匙与本机数据；全部留在本机",
            trailing = {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = TL.accent,
                    )
                }
            },
        )

        when (stage) {
            SetupStage.NEVER_CONNECTED -> FirstRunGuide()
            SetupStage.CONNECTED_NOT_PAIRED -> HintBar(
                "链路已经通了，但这把公钥还没被车辆认下：完成下方「钥匙配对」（刷 NFC 卡 + 车机确认）后，" +
                    "以后打开 app 就会直接进仪表页。",
                Tone.Warn,
            )
            SetupStage.PAIRED -> Unit
        }

        SectionLabel(
            "连接车辆",
            "纯蓝牙直连 · " + when (stage) {
                SetupStage.NEVER_CONNECTED -> "首次配置"
                SetupStage.CONNECTED_NOT_PAIRED -> "还差钥匙配对"
                SetupStage.PAIRED -> "日常（钥匙已入库）"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(
                when {
                    state == VehicleConnection.State.DISCONNECTED && autoReconnecting ->
                        "链路 自动重连中…"
                    else ->
                        when (state) {
                            VehicleConnection.State.CONNECTED -> "链路 已连接"
                            VehicleConnection.State.CONNECTING -> "链路 连接中"
                            VehicleConnection.State.DISCONNECTED -> "链路 未连接"
                        }
                },
                when (state) {
                    VehicleConnection.State.CONNECTED -> Tone.Good
                    VehicleConnection.State.DISCONNECTED -> Tone.Bad
                    else -> Tone.Warn
                },
            )
            Pill(
                "钥匙 ${if (keyPaired) "已配对" else "未配对"}",
                if (keyPaired) Tone.Good else Tone.Warn,
            )
            Pill("车机 ${if (mcuReady) "在线" else "未就绪"}", if (mcuReady) Tone.Good else Tone.Neutral)
        }

        when (stage) {
            // 日常模式：钥匙已入库，把整套连接/配对操作收进折叠，页面第一眼只剩状态。
            SetupStage.PAIRED -> CollapsibleSection(
                "连接车辆 · 日常",
                "钥匙已入库 · 打开 app 自动连接，平时不用动这里",
            ) {
                ConnectionControls(link)
                ActionTile(
                    "重看首次引导（清掉配对记录）",
                    onClick = {
                        link.resetOnboarding()
                        resetNote = Tone.Good to
                            "已重置界面引导状态：本机钥匙没动，也没通知车辆；首次引导和配对卡已恢复显示"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    tone = Tone.Warn,
                    stateLine = "只重置界面引导状态：不动本机钥匙，也不会通知车辆",
                )
                resetNote?.let { HintBar(it.second, it.first) }
            }
            else -> ConnectionControls(link)
        }

        CollapsibleSection(
            "连接兜底",
            when {
                macInput.isNotBlank() -> "已记住 MAC …" + macInput.takeLast(5)
                else -> "默认扫描发现 · 无需配置"
            },
        ) {
        Panel(
            title = "车辆 BLE 地址（MAC）",
            subtitle = "用 nRF Connect 在车旁看到车辆（名称 S…C）而本 app 扫不到时填写；保存后连接将跳过扫描直接拨号。",
        ) {
            OutlinedTextField(
                value = macInput,
                onValueChange = { macInput = it },
                singleLine = true,
                placeholder = { Text("A0:B1:C2:D3:E4:F5", color = TL.textLow) },
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    fontFeatureSettings = "tnum",
                    color = TL.textHi,
                ),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(
                    "保存并使用",
                    onClick = {
                        val mac = macInput.trim().uppercase()
                        if (Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(mac)) {
                            link.rememberAddress(mac)
                            macNote = Tone.Good to "已保存车辆地址 $mac，下次连接直接拨号"
                        } else {
                            macNote = Tone.Bad to "MAC 格式不正确（应为 XX:XX:XX:XX:XX:XX）"
                        }
                    },
                    modifier = Modifier.weight(1f),
                    emphasize = true,
                    tone = Tone.Info,
                )
                ActionTile(
                    "清除",
                    onClick = {
                        link.forgetAddress()
                        macInput = ""
                        macNote = Tone.Good to "已清除车辆地址，下次连接恢复扫描"
                    },
                    modifier = Modifier.width(110.dp),
                )
            }
            macNote?.let { HintBar(it.second, it.first) }
        }

        Panel(
            title = "自定义 BLE 广播名",
            subtitle = "车辆广播名默认按 VIN 计算（S + SHA1 前 8 字节 + C），与你在 Tesla app 里改的昵称无关。",
        ) {
            OutlinedTextField(
                value = customName,
                onValueChange = { customName = it },
                singleLine = true,
                placeholder = { Text("例如 Tesla Model 3（留空则不启用）", color = TL.textLow) },
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            ActionTile(
                "保存广播名",
                onClick = {
                    link.setCustomBleName(customName)
                    nameNote = Tone.Good to "已保存自定义广播名"
                },
                modifier = Modifier.fillMaxWidth(),
            )
            nameNote?.let { HintBar(it.second, it.first) }
        }
        }

        CollapsibleSection(
            "高德 Key（通用）",
            (if (amapKey.isBlank()) "未配置 · 地址与天气都不可用"
            else "已配置 · 尾号 …" + amapKey.takeLast(4) + if (weatherOn) " · 天气开" else ""),
        ) {
            Panel(
                title = "高德开放平台 Key",
                subtitle = "全 app 通用的一项配置，影响两处：「工具 → 事故处理 → 我在哪」的逆地理编码" +
                    "（把 GPS 坐标翻译成中文地址），以及可选的仪表天气。" +
                    "统计页轨迹的高德底图走公开栅瓦，不需要 Key；车辆数据全程蓝牙直连，与这个 Key 无关。",
            ) {
                OutlinedTextField(
                    value = amapKey,
                    onValueChange = { amapKey = it },
                    singleLine = true,
                    placeholder = { Text("粘贴高德 Web 服务 Key", color = TL.textLow) },
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "只要「Key」那一串，不需要「安全密钥」（那是 JS API 用的）。" +
                        "高德控制台「我的应用」里这把 Key 的绑定服务必须显示 Web服务；" +
                        "显示 Web端 / Android平台 的会报 10009 平台不匹配；不要勾选数字签名。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TL.textLow,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(
                        "保存 Key",
                        onClick = {
                            val key = amapKey.trim()
                            amapKey = key
                            scope.launch { container.prefs.putString("amap_key", key) }
                            amapNote = if (key.isBlank()) {
                                Tone.Good to "已清除 Key：地址翻译退回 OSM 兜底（国内常常连不上），仪表天气也不再显示"
                            } else {
                                Tone.Good to "已保存 Key，下次进入事故助手 / 仪表天气即生效"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        emphasize = true,
                        tone = Tone.Info,
                    )
                    ActionTile(
                        "清除",
                        onClick = {
                            amapKey = ""
                            scope.launch { container.prefs.remove("amap_key") }
                            amapNote = Tone.Good to "已清除 Key：地址翻译退回 OSM 兜底（国内常常连不上），仪表天气也不再显示"
                        },
                        modifier = Modifier.width(110.dp),
                    )
                }
                amapNote?.let { HintBar(it.second, it.first) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "仪表驻车版面显示天气",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TL.textHi,
                        )
                        Text(
                            "用手机最近一次定位查城市，再取高德天气；30 分钟一刷，只出现在停车时钟下方。" +
                                "没有 Key、没给位置权限或请求失败时整块不显示，不会放占位符。",
                            style = MaterialTheme.typography.labelSmall,
                            color = TL.textLow,
                        )
                    }
                    Switch(
                        checked = weatherOn,
                        onCheckedChange = { on ->
                            weatherOn = on
                            scope.launch {
                                container.prefs.putString("weather_on", if (on) "1" else null)
                            }
                            if (on && amapKey.isBlank()) {
                                amapNote = Tone.Warn to "天气已开启，但还没保存 Key：保存后进入仪表才会请求"
                            }
                        },
                    )
                }
            }
        }

        CollapsibleSection(
            "仪表速度分档",
            bands.breaks.joinToString(" / ") + " km/h · 五段配色",
        ) {
            Panel(
                title = "速度分级与颜色",
                subtitle = "车速跨过这些变化点就换颜色，作用在仪表盘的大数字、光晕和下面的阈值条上。" +
                    "按 km/h 设置，相邻两档至少差 1 km/h；改完立即生效，不用重连。",
            ) {
                bands.breaks.forEachIndexed { i, value ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "第 ${i + 1} 档上限",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TL.textMid,
                            modifier = Modifier.weight(1f),
                        )
                        StepChip("−") {
                            val list = bands.breaks.toMutableList()
                            // 相邻档至少差 1 km/h：到边界就停住，不连带把上一档推走。
                            list[i] = (value - 1).coerceAtLeast(
                                (bands.breaks.getOrNull(i - 1) ?: 9) + 1
                            )
                            dashboard.setSpeedBands(bands.copy(breaks = list))
                        }
                        Text(
                            "$value",
                            style = MaterialTheme.typography.titleMedium,
                            color = TL.textHi,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(40.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        StepChip("+") {
                            val list = bands.breaks.toMutableList()
                            list[i] = (value + 1).coerceAtMost(
                                (bands.breaks.getOrNull(i + 1) ?: 241) - 1
                            )
                            dashboard.setSpeedBands(bands.copy(breaks = list))
                        }
                    }
                }

                Text(
                    "每次 ±1 km/h；相邻两档至少差 1，到边界按钮停住。" +
                        "仪表上的大数字、光晕、阈值条会一起换到对应颜色。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TL.textLow,
                )

                SectionLabel("每段颜色", "暗底高对比色板")
                for (i in 0..4) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(SpeedPalette.argb(bands.colors[i])))
                        )
                        Text(
                            bands.rangeLabel(i) + " km/h",
                            style = MaterialTheme.typography.labelSmall,
                            color = TL.textMid,
                            modifier = Modifier.width(70.dp),
                        )
                        Row(
                            Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            SpeedPalette.entries.forEachIndexed { idx, entry ->
                                val selected = bands.colors[i] == idx
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color(entry.argb))
                                        .border(
                                            if (selected) 2.dp else 1.dp,
                                            if (selected) TL.textHi else TL.hairline,
                                            CircleShape,
                                        )
                                        .clickable {
                                            val list = bands.colors.toMutableList()
                                            list[i] = idx
                                            dashboard.setSpeedBands(bands.copy(colors = list))
                                        }
                                )
                            }
                        }
                    }
                }

                ActionTile(
                    "恢复默认分级（66 / 88 / 110 / 132 · 冰蓝→红）",
                    onClick = { dashboard.setSpeedBands(SpeedBands()) },
                    modifier = Modifier.fillMaxWidth(),
                    tone = Tone.Warn,
                )
            }
        }

        PersonalInfoSection(container)

        CollapsibleSection(
            "本机钥匙与缓存",
            "指纹尾 …" + publicKey.takeLast(4) + " · P-256",
        ) {
        Panel(
            title = "车钥匙公钥",
            subtitle = "私钥由 AndroidKeyStore 加密后仅存本机，不上传任何服务器",
            trailing = {
                Pill("P-256", Tone.Neutral)
            },
        ) {
            Text(publicKey, style = TL.mono, color = TL.textMid)
            HintBar(
                "只有当「调试」页出现“本机钥匙无法解密”时才需要重新生成：这会替换本机钥匙，之后必须重新" +
                    "刷卡配对，并在车机「控制→钥匙」删除旧条目。日常更新 app / 重启不会换钥匙。",
                Tone.Neutral,
            )
            ActionTile(
                "重新生成本机钥匙",
                onClick = {
                    scope.launch {
                        try {
                            link.rotateKey()
                            rotateNote = Tone.Good to
                                "已生成新钥匙：请在本页上方「钥匙配对」重新配对，并在车机钥匙列表删除旧条目"
                        } catch (e: Exception) {
                            rotateNote = Tone.Bad to (e.message ?: "重新生成失败")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                tone = Tone.Bad,
            )
            rotateNote?.let { HintBar(it.second, it.first) }
        }

        Panel(
            title = "协议会话缓存",
            subtitle = "缓存握手结果以加速首条命令。持续报签名/计数器错误时清除重建。",
        ) {
            KeyValue("清除后", "下次连接重新握手")
            ActionTile(
                "清除会话缓存",
                onClick = {
                    scope.launch {
                        container.sessionCache.clear()
                        cacheNote = Tone.Good to "已清除，下次连接将重新握手"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                tone = Tone.Warn,
            )
            cacheNote?.let { HintBar(it.second, it.first) }
        }
        }

        CollapsibleSection(
            "关于与开发者",
            "v0.3.0 · 开发者模式" + if (devMode) "开" else "关",
        ) {
        Panel(
            title = "开发者模式",
            subtitle = "打开后底栏显示「调试」页（链路自检、分级日志）。日常使用无需打开。",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (devMode) "已开启：底栏显示调试页" else "已关闭",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TL.textMid,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = devMode, onCheckedChange = { link.setDevMode(it) })
            }
        }

        SectionLabel("关于")
        Panel(tight = true) {
            KeyValue("版本", "T-Dash v0.3.0")
            KeyValue("通道", "蓝牙直连（官方 Vehicle Command 协议的 Kotlin 实现）")
            KeyValue("云端", "无：无账号登录、无遥测服务器；联网只用于轨迹地图底图，以及可选的高德天气")
            KeyValue("远程", "人不在车旁时需要 Fleet API，当前版本未启用")
        }
        }
    }
}

/** 首次使用引导：accent 高亮框，完成首次连接后自动消失。 */
@Composable
private fun FirstRunGuide() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TL.accent.copy(alpha = 0.08f))
            .border(1.dp, TL.accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("连接你的车辆", style = MaterialTheme.typography.titleMedium, color = TL.textHi)
        Text(
            "第一次使用，按下面三步走（都在本页完成）：",
            style = MaterialTheme.typography.labelSmall,
            color = TL.textMid,
        )
        Step("01", "在下方输入 17 位 VIN")
        Step("02", "点「扫描并连接」，保持手机在车旁")
        Step("03", "按提示刷 NFC 钥匙卡并在车机屏幕确认")
    }
}

@Composable
private fun Step(index: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            index,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = TL.accent,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TL.textMid)
    }
}

@Composable
/**
 * 加减键：点一下走一步；按住 400ms 后连发（90ms/步，按住超过 1.6 秒加速到 45ms/步）。
 * 连发期间每步都会改速度分档的内存值，落盘由 DashboardViewModel 合并成"停手后写一次"。
 */
private fun StepChip(text: String, onClick: () -> Unit) {
    val action by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    var repeatJob by remember { mutableStateOf<Job?>(null) }
    Box(
        Modifier
            .size(width = 38.dp, height = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) TL.accent.copy(alpha = 0.20f) else TL.raised)
            .border(1.dp, if (pressed) TL.accent else TL.hairline, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        action()
                        repeatJob = scope.launch {
                            delay(holdDelayMs)
                            var interval = repeatStartMs
                            var held = 0L
                            while (isActive) {
                                action()
                                delay(interval)
                                held += interval
                                if (held >= accelerateAfterMs) interval = repeatFastMs
                            }
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            repeatJob?.cancel()
                            repeatJob = null
                            pressed = false
                        }
                    }
                )
            }
            .semantics {
                role = Role.Button
                semanticsOnClick(label = text) {
                    action()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = TL.textHi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

private const val holdDelayMs = 400L
private const val repeatStartMs = 90L
private const val repeatFastMs = 45L
private const val accelerateAfterMs = 1_200L

@Composable
private fun fieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = TL.accent,
    unfocusedBorderColor = TL.hairline,
    focusedContainerColor = TL.raised,
    unfocusedContainerColor = TL.raised,
    cursorColor = TL.accent,
    focusedTextColor = TL.textHi,
    unfocusedTextColor = TL.textHi,
    focusedLabelColor = TL.accent,
    unfocusedLabelColor = TL.textLow,
)

/**
 * 「连接车辆」操作区：链路排查提示 + VIN/扫描连接 + 钥匙配对。
 * 首次与待配对阶段直接展开；日常模式由调用方收进折叠区。
 */
@Composable
private fun ConnectionControls(link: com.teslalink.app.vm.LinkViewModel) {
    val vin by link.vin.collectAsState()
    val state by link.state.collectAsState()
    val keyPaired by link.keyPaired.collectAsState()
    val pairing by link.pairingPhase.collectAsState()
    val ack by link.pairingAck.collectAsState()
    val busy by link.busy.collectAsState()
    val connected = state == VehicleConnection.State.CONNECTED

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var note by remember { mutableStateOf<Pair<Tone, String>?>(null) }
        if (!connected) {
            HintBar(
                "打开 app 后会自动保持连接，无需手动操作。扫不到车辆的两个常见原因：" +
                    "① 官方 App 在后台占用了车辆的 BLE 连接（上限 3 个），请完全退出它；" +
                    "② 车辆唤醒/行驶中 VCSEC 可能不再广播，可锁车等 2-3 分钟再试。",
                Tone.Neutral,
            )
        }

        Panel(title = "VIN", subtitle = "17 位，见车辆铭牌或行驶证") {
            OutlinedTextField(
                value = vin,
                onValueChange = link::setVin,
                singleLine = true,
                placeholder = { Text("5YJ…", color = TL.textLow) },
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    fontFeatureSettings = "tnum",
                    color = TL.textHi,
                ),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(
                    if (state == VehicleConnection.State.CONNECTING) "正在连接…" else "扫描并连接",
                    onClick = link::connect,
                    modifier = Modifier.weight(1f),
                    enabled = !busy && vin.length == 17,
                    tone = Tone.Info,
                    emphasize = true,
                    stateLine = if (vin.length != 17) "需要 17 位 VIN" else null,
                )
                ActionTile(
                    "断开",
                    onClick = {
                        link.disconnect()
                        note = Tone.Good to "已断开：自动重连不会再自行拉起，点「扫描并连接」恢复"
                    },
                    modifier = Modifier.width(96.dp),
                    enabled = state != VehicleConnection.State.DISCONNECTED && !busy,
                )
            }
        }

        if (!keyPaired) {
            Panel(
                title = "把本机公钥加入车辆钥匙链",
                subtitle = "请求发出后车辆会要求刷卡并在屏幕确认",
            ) {
                Step("01", "点「发送配对请求」")
                Step("02", "把 NFC 钥匙卡贴在中控读取区（杯架前方的卡槽）")
                Step("03", "在车机屏幕点「确认」，选择车主权限")
                when (pairing) {
                    LinkViewModel.PairingPhase.IDLE -> ActionTile(
                        "发送配对请求",
                        onClick = link::sendPairingRequest,
                        enabled = connected,
                        emphasize = true,
                        tone = Tone.Info,
                        stateLine = if (!connected) "需要先连接车辆" else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LinkViewModel.PairingPhase.SENDING -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = TL.accent)
                        Text("正在发送…", style = MaterialTheme.typography.bodySmall, color = TL.textMid)
                    }
                    LinkViewModel.PairingPhase.WAITING_CONFIRM -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = TL.warn)
                        Text(
                            "等待刷卡与车机确认（最长 2 分钟，自动检测）",
                            style = MaterialTheme.typography.bodySmall,
                            color = TL.warn,
                        )
                        ack?.let { HintBar("车辆回执：$it", Tone.Info) }
                    }
                    LinkViewModel.PairingPhase.PAIRED ->
                        Text("配对成功。", style = MaterialTheme.typography.titleMedium, color = TL.good)
                    LinkViewModel.PairingPhase.FAILED -> {
                        Text("配对未完成。", style = MaterialTheme.typography.titleMedium, color = TL.bad)
                        ActionTile("重试", onClick = link::resetPairing, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        note?.let { HintBar(it.second, it.first) }

    }
}

/** 设置页的三种模式，由持久化的识别状态推导。 */
private enum class SetupStage { NEVER_CONNECTED, CONNECTED_NOT_PAIRED, PAIRED }

/**
 * 个人信息：报案/保险要反复填的那几项 + 驾驶证行驶证照片。
 * 唯一读取方是「工具 → 事故处理 → 导出取证清单 PDF」，文字进 DataStore、
 * 照片复制进应用私有目录，都不出本机。
 */
@Composable
private fun PersonalInfoSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val stored by container.profile.profile.collectAsState()
    val licenseUri by container.profile.license.collectAsState()
    val vehicleUri by container.profile.vehicle.collectAsState()
    var draft by remember { mutableStateOf<AccidentProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var manualInsurer by remember { mutableStateOf(false) }
    // 就地反馈：文字面板和照片面板各一条，不往页顶塞。
    var textNote by remember { mutableStateOf<Pair<Tone, String>?>(null) }
    var photoNote by remember { mutableStateOf<Pair<Tone, String>?>(null) }
    val form = draft ?: stored
    val photoCount = listOfNotNull(licenseUri, vehicleUri).size

    // 两类照片各用一个启动器：如果把「选的是哪一类」存在状态里，相册进程把 app 回收
    // 再回来时状态会退回默认值，行驶证就存成驾驶证了。
    fun import(kind: String, label: String, uri: Uri?) {
        if (uri == null) {
            photoNote = Tone.Warn to "已取消选择${label}照片"
            return
        }
        busy = true
        scope.launch {
            // 复制而不是记 Uri：选择器给的是临时读授权，进程一重启那条引用就废了。
            val ok = withContext(Dispatchers.IO) { container.profile.importPhoto(kind, uri) }
            busy = false
            photoNote = (
                if (ok) Tone.Good to "已保存${label}照片，导出 PDF 自动带上"
                else Tone.Bad to "${label}照片读不出来，换一张再试"
                )
        }
    }

    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    val pickLicense = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        import(ProfileStore.KIND_LICENSE, "驾驶证", it)
    }
    val pickVehicle = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        import(ProfileStore.KIND_VEHICLE, "行驶证", it)
    }

    CollapsibleSection(
        "个人信息（事故取证用）",
        (if (stored.summary.isBlank()) "未填写" else stored.summary) + " · 证件照 $photoCount/2",
    ) {
        Panel(
            title = "报案信息",
            subtitle = "只被「工具 → 事故处理 → 导出取证清单 PDF」读取，也用来核对现场要念给交警/保险的号码。" +
                "全部存本机（应用私有存储），不联网、不上传。",
        ) {
            ProfileField("姓名", form.name, "张三") { draft = form.copy(name = it) }
            ProfileField("手机号", form.phone, "138…", KeyboardType.Phone) { draft = form.copy(phone = it) }
            ProfileField("车牌号", form.plate, "沪A·12345") { draft = form.copy(plate = it) }
            ProfileField("车架号 VIN", form.vin, "17 位，见行驶证", KeyboardType.Ascii) { draft = form.copy(vin = it) }
            if (manualInsurer) {
                ProfileField("保险公司", form.insurer, "保单上的公司名") { draft = form.copy(insurer = it) }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Pill("改回下拉选择", onClick = { manualInsurer = false })
                }
            } else {
                InsurerPicker(
                    value = form.insurer,
                    onPick = { draft = form.copy(insurer = it) },
                    onManual = { manualInsurer = true },
                )
                if (form.insurer.isNotBlank() && form.insurer !in INSURER_NAMES) {
                    Text(
                        "「${form.insurer}」不在常用列表里：PDF 照写，但报保险那一步认不出你这家，会列全部号码。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TL.warn,
                    )
                }
            }
            ProfileField("保单号", form.policyNo, "保险单右上角") { draft = form.copy(policyNo = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(
                    "保存",
                    onClick = {
                        val snapshot = form
                        scope.launch {
                            container.profile.save(snapshot)
                            draft = null
                            textNote = Tone.Good to (
                                "已保存到本机" +
                                    if (snapshot.name.isBlank() || snapshot.phone.isBlank() || snapshot.plate.isBlank())
                                        "（姓名/手机/车牌还有空项，PDF 里会缺）"
                                    else "，事故助手导出 PDF 即生效"
                                )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    emphasize = true,
                    tone = Tone.Info,
                    enabled = draft != null && !busy,
                    stateLine = when {
                        draft == null -> "没有改动"
                        busy -> "正在存照片，稍等一下"
                        else -> null
                    },
                )
                ActionTile(
                    "清空文字",
                    onClick = {
                        draft = null
                        scope.launch {
                            container.profile.save(AccidentProfile())
                            textNote = Tone.Good to "已清空文字项（证件照没动）"
                        }
                    },
                    modifier = Modifier.width(112.dp),
                    enabled = draft != null || stored.summary.isNotBlank(),
                )
            }
            textNote?.let { HintBar(it.second, it.first) }
        }

        Panel(
            title = "证件照片",
            subtitle = "驾驶证、行驶证各一张，导出 PDF 时排在「证件照片」栏，现场不用再翻相册。",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IdPhotoSlot(
                    "驾驶证",
                    licenseUri,
                    busy,
                    modifier = Modifier.weight(1f),
                    onPick = { pickLicense.launch(request) },
                    onClear = {
                        scope.launch {
                            container.profile.clearPhoto(ProfileStore.KIND_LICENSE)
                            photoNote = Tone.Good to "已删除驾驶证照片（只删本机私有副本，不碰相册）"
                        }
                    },
                )
                IdPhotoSlot(
                    "行驶证",
                    vehicleUri,
                    busy,
                    modifier = Modifier.weight(1f),
                    onPick = { pickVehicle.launch(request) },
                    onClear = {
                        scope.launch {
                            container.profile.clearPhoto(ProfileStore.KIND_VEHICLE)
                            photoNote = Tone.Good to "已删除行驶证照片（只删本机私有副本，不碰相册）"
                        }
                    },
                )
            }
            Text(
                "选完会复制一份到本机私有目录，之后删掉相册里的原图也不影响导出。" +
                    "照片只在导出 PDF 时读取，同样不出本机。",
                style = MaterialTheme.typography.labelSmall,
                color = TL.textLow,
            )
            photoNote?.let { HintBar(it.second, it.first) }
        }
    }
}

/** 常用保险公司名单：与「工具 → 事故处理 → 报保险」同源；道路救援不是保险公司，不进下拉。 */
private val INSURER_NAMES =
    com.teslalink.app.tools.INSURANCE_HOTLINES.filter { !it.first.startsWith("Tesla") }.map { it.first }

/** 保险公司下拉：列表里没有的走「其它（手动填写）」，不把他逼进一个填不了的框。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun InsurerPicker(value: String, onPick: (String) -> Unit, onManual: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text("保险公司") },
            placeholder = { Text("下拉选 · 报保险那一步只显示你这家", color = TL.textLow) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = fieldColors(),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            INSURER_NAMES.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, color = TL.textHi) },
                    onClick = {
                        expanded = false
                        onPick(name)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("其它（手动填写）", color = TL.accent) },
                onClick = {
                    expanded = false
                    onManual()
                },
            )
        }
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        placeholder = { Text(placeholder, color = TL.textLow) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 证件照位：有图就地显示并可点更换，没图显示加号；清除只在有图时出现，不留空操作按钮。 */
@Composable
private fun IdPhotoSlot(
    label: String,
    uri: Uri?,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(86.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TL.raised)
                .border(
                    1.dp,
                    if (uri == null) TL.hairline else TL.accent.copy(alpha = 0.45f),
                    RoundedCornerShape(8.dp),
                )
                .clickable(enabled = !busy, onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (uri != null) {
                PhotoThumb(uri, Modifier.fillMaxSize())
            } else {
                Text("＋ $label", style = MaterialTheme.typography.labelMedium, color = TL.textMid)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(if (uri == null) "选择" else "更换", onClick = onPick)
            if (uri != null) Pill("清除", tone = Tone.Bad, onClick = onClear)
        }
    }
}
