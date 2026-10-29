package com.teslalink.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.teslalink.app.vehicle.VehicleConnection
import com.teslalink.app.vm.LinkViewModel
import com.teslalink.app.vm.StatusSnapshot
import com.teslalink.app.vm.StatusViewModel

/**
 * 控制页：按钮可用性由连接与安全闸门驱动。
 *
 * 状态分两层——门锁/舱盖/休眠来自安全控制器（免鉴权、不唤醒车机，3 秒轮询）；
 * 空调/充电/车窗来自车机（需要 Infotainment 会话）。
 * 本页刻意不轮询车机（轮询会一直把车机叫醒），所以车机类读数经常是上一次读到的旧值：
 * 旧值照常显示、照常参与"已经开着/已经落锁"这类互斥提示，但**状态未知不再锁死按钮**——
 * 命令发出去由车辆自己裁决，失败会在日志里说清原因（他 2026-10-21 实测点名）。
 */
@Composable
fun ControlScreen(vm: LinkViewModel, status: StatusViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val log by vm.log.collectAsState()
    val snap by status.snapshot.collectAsState()
    val place by status.place.collectAsState()
    val linkState by vm.state.collectAsState()
    val keyPaired by vm.keyPaired.collectAsState()
    val mcuReady by vm.mcuReady.collectAsState()
    val busy by vm.busy.collectAsState()

    var temp by remember { mutableFloatStateOf(21f) }
    var chargeLimit by remember { mutableIntStateOf(80) }
    var limitTouched by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        status.startBodyPolling()
        // One vehicle-data read on entry (and after each command) so the climate/charging gates
        // have real values. Deliberately not a loop: polling the MCU keeps waking the head unit.
        status.refresh(force = true)
        onDispose { status.stopBodyPolling() }
    }

    // 读到车辆真实充电上限时同步滑块，除非用户已经动过它。
    val seenLimit = snap?.chargeLimit
    LaunchedEffect(seenLimit) {
        if (!limitTouched && seenLimit != null && seenLimit > 0) chargeLimit = seenLimit
    }

    // 读到新坐标就顺手翻一次地址（ViewModel 里按位置缓存，不会反复打网络）。
    // 光等用户展开才解析不够：展开时 LOCATION 往往还没读回来。
    LaunchedEffect(snap?.latitude, snap?.longitude) {
        if (snap?.latitude != null && snap?.longitude != null) status.resolvePlace()
    }

    // 命令返回后立刻回读状态，让按钮尽快反映结果。车机类命令本来就把车叫醒了，顺便把
    // 空调/充电状态读回来（否则这些闸门会因为读不到车机数据而长期停留在"未知"）。
    val lastCommandAt = log.firstOrNull()?.time
    val lastCommandOk = log.firstOrNull()?.ok
    LaunchedEffect(lastCommandAt) {
        if (lastCommandAt != null) {
            status.refreshBody(force = true)
            if (lastCommandOk == true) {
                status.refresh(force = true)
                // 整轮回读的 DRIVE 若不完整会连坐跳过 CLOSURES（哨兵），且车辆对哨兵等
                // 开关的状态迁移是异步的——延后 2 秒再单独补一条 CLOSURES 原子读。
                status.refreshClosuresDelayed()
            }
        }
    }

    val linkOk = linkState == VehicleConnection.State.CONNECTED && keyPaired
    // 车机读数只用于"显示"和已知状态下的互斥提示，不再按新鲜度清零。清零正是
    // "状态获取中 + 按钮全灰"的来源：本页不轮询车机，6 秒后必然清零，永远等不到新值。
    val mcuAgeMs = (snap?.mcuUpdatedAtMs ?: 0L).let { if (it > 0) System.currentTimeMillis() - it else Long.MAX_VALUE }
    val mcuStateOld = mcuAgeMs > MCU_STALE_HINT_MS
    // 行驶判断用更宽的 60s 窗口：宁可多灰一会儿，也不能在行驶中误放开闭件。
    val notParked = snap?.recentlyMoving() == true
    val locked = snap?.locked
    val rearOpen = snap?.rearTrunkShown(notParked)
    val frunkOpen = snap?.frunkShown(notParked)
    val portOpen = snap?.chargePortShown(notParked)
    val climateOn = snap?.climateOn
    val charging = snap?.chargingState
    val sentryOn = snap?.sentryOn
    val sentryFresh = snap?.sentryIsFresh() == true
    val hvacAuto = snap?.hvacAuto
    val fanLevel = snap?.fanLevel
    val notPlugged = charging == "未连接" || charging == "无电源"
    val isCharging = charging == "充电中" || charging == "启动中"

    val blocked = when {
        linkState != VehicleConnection.State.CONNECTED -> "未连接车辆"
        !keyPaired -> "钥匙尚未配对"
        busy -> "命令进行中"
        else -> null
    }

    fun gate(needMcu: Boolean = false, needPark: Boolean = false, stateReason: String? = null): String? {
        blocked?.let { return it }
        if (needMcu && !mcuReady) return "车机未就绪"
        if (needPark && notParked) return "非 P 档不可用"
        return stateReason
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageHeader(
            "控制",
            "按钮可用性跟随连接与安全闸门；车机状态显示最后一次读到的值",
            trailing = {
                Pill(
                    if (mcuReady) "车机在线" else "车机未就绪",
                    if (mcuReady) Tone.Good else Tone.Warn,
                )
            },
        )

        StatePills(snap, linkOk, notParked)

        when {
            !linkOk -> HintBar(
                if (linkState != VehicleConnection.State.CONNECTED) {
                    "未连接：正在自动重连；也可到「设置」手动连接"
                } else {
                    "已连接但未配对：到「设置」完成钥匙配对"
                },
                Tone.Bad,
            )
            !mcuReady -> HintBar(
                "车机（Infotainment）会话未建立，标注「车机」的动作会失败。" +
                    "到「调试」页跑一次链路自检可拿到车辆返回的确切故障码；锁、舱盖、唤醒不受影响。",
                Tone.Warn,
            )
            notParked -> HintBar("车辆在行驶（非 P 档）：开闭件/车窗/充电口/哨兵已按车辆规则禁用", Tone.Warn)
            notPlugged -> HintBar("当前未插充电枪：充电启停与上限设置不可用", Tone.Info)
            // 本页不轮询车机（会一直叫醒车），状态旧是常态，说清楚"旧值只用于显示、不锁按钮"。
            linkOk && mcuStateOld -> HintBar(
                "车机读数已超过 6 秒没刷新，显示的是最后一次读到的值；按钮不受它限制，" +
                    "发送失败会在这里的最近命令里给出车辆返回的原因。",
                Tone.Info,
            )
        }

        CollapsibleSection(
            "车锁与舱盖",
            lockSummary(locked, rearOpen, frunkOpen),
            initiallyExpanded = true,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(
                "解锁",
                gate(stateReason = when (locked) {
                    false -> "已经解锁"
                    else -> null
                }),
                vm::unlock,
                Modifier.weight(1f),
            )
            Tile(
                "落锁",
                gate(stateReason = when (locked) {
                    true -> "已经落锁"
                    else -> null
                }),
                vm::lock,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(
                "开后备箱",
                gate(
                    needPark = true,
                    stateReason = when (rearOpen) {
                        true -> "后备箱已开"
                        else -> null
                    },
                ),
                vm::trunk,
                Modifier.weight(1f),
            )
            Tile(
                "关后备箱",
                gate(
                    needPark = true,
                    stateReason = when (rearOpen) {
                        false -> "后备箱已关"
                        else -> null
                    },
                ),
                vm::trunkClose,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(
                "开前备箱",
                gate(
                    needPark = true,
                    stateReason = when (frunkOpen) {
                        true -> "前备箱已开"
                        else -> null
                    },
                ),
                vm::frunk,
                Modifier.weight(1f),
            )
            Tile("唤醒整车", gate(), vm::wake, Modifier.weight(1f))
        }
        }

        CollapsibleSection("灯光与车窗", "车机 · " + if (mcuReady) "就绪" else "未就绪") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("闪灯", gate(needMcu = true), vm::flash, Modifier.weight(1f), badge = "车机")
                Tile("鸣笛", gate(needMcu = true), vm::honk, Modifier.weight(1f), badge = "车机")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(
                    "车窗通风",
                    gate(needMcu = true, needPark = true),
                    vm::ventWindows,
                    Modifier.weight(1f),
                    badge = "车机",
                )
                Tile(
                    "车窗关闭",
                    gate(needMcu = true, needPark = true),
                    vm::closeWindows,
                    Modifier.weight(1f),
                    badge = "车机",
                )
            }
        }

        CollapsibleSection("空调", climateSummary(climateOn, snap?.driverTemp, snap?.insideTemp, hvacAuto, fanLevel)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(
                    "开空调",
                    gate(needMcu = true, stateReason = when (climateOn) {
                        true -> "空调已运行"
                        else -> null
                    }),
                    // 开空调要把他当前的 AUTO/手动 模式原样回发：HvacAutoAction 的
                    // manual_override 不带 optional，漏发等于每次开空调都把人顶回 AUTO。
                    { vm.climateOn(manual = hvacAuto == false) },
                    Modifier.weight(1f),
                    badge = "车机",
                )
                Tile(
                    "关空调",
                    gate(needMcu = true, stateReason = when (climateOn) {
                        false -> "空调已关闭"
                        else -> null
                    }),
                    vm::climateOff,
                    Modifier.weight(1f),
                    badge = "车机",
                )
            }

            // 手档 = 协议里的 hvac_auto_request=Override：风量由车机固定，不再按目标温度自动调。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(
                    "AUTO",
                    gate(needMcu = true, stateReason = when (hvacAuto) {
                        true -> "已是 AUTO"
                        else -> null
                    }),
                    { vm.setHvacMode(manual = false) },
                    Modifier.weight(1f),
                    badge = "车机",
                )
                Tile(
                    "手动风量",
                    gate(
                        needMcu = true,
                        stateReason = when (hvacAuto) {
                            false -> "已是手动"
                            else -> if (climateOn == false) "先开空调" else null
                        },
                    ),
                    { vm.setHvacMode(manual = true) },
                    Modifier.weight(1f),
                    badge = "车机",
                )
            }
            Text(
                when (hvacAuto) {
                    false -> "当前风量 ${fanLevel ?: "?"} 档 · 高/中/低本身要在车机上按（协议里没有设风量的指令）"
                    true -> "AUTO：风量由车机按目标温度自己决定"
                    null -> "模式未知 · 手动档的高/中/低本身要在车机上按（协议里没有设风量的指令）"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TL.textLow,
            )
            Panel(title = "目标温度", subtitle = "车内 ${snap?.insideTemp?.let { String.format("%.1f", it) } ?: "--"}°C · 设定 ${snap?.driverTemp?.let { String.format("%.1f", it) } ?: "--"}°C") {
                Text(
                    "${"%.1f".format(temp)} °C",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TL.textHi,
                )
                Slider(
                    value = temp,
                    onValueChange = { temp = it },
                    valueRange = 15f..28f,
                    steps = 25,
                    colors = sliderColors(),
                )
                Tile("应用温度", gate(needMcu = true), { vm.setClimateTemp(temp) }, Modifier.fillMaxWidth(), badge = "车机")
            }

            ClimateExtras(vm, snap, mcuReady, blocked)
        }

        CollapsibleSection("充电", chargeSummary(charging, snap?.chargeLimit)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(
                    "开始充电",
                    gate(needMcu = true, stateReason = when {
                        notPlugged -> "未插充电枪"
                        isCharging -> "已在充电"
                        else -> null
                    }),
                    vm::chargeStart,
                    Modifier.weight(1f),
                    badge = "车机",
                )
                Tile(
                    "停止充电",
                    gate(needMcu = true, stateReason = when {
                        notPlugged -> "未插充电枪"
                        charging == null -> null
                        isCharging -> null
                        else -> "当前没在充电"
                    }),
                    vm::chargeStop,
                    Modifier.weight(1f),
                    badge = "车机",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(
                    "充电口开",
                    gate(
                        needMcu = true,
                        needPark = true,
                        stateReason = when (portOpen) {
                            true -> "充电口已开"
                            else -> null
                        },
                    ),
                    vm::chargePortOpen,
                    Modifier.weight(1f),
                    badge = "车机",
                )
                Tile(
                    "充电口关",
                    gate(
                        needMcu = true,
                        needPark = true,
                        stateReason = when (portOpen) {
                            false -> "充电口已关"
                            else -> null
                        },
                    ),
                    vm::chargePortClose,
                    Modifier.weight(1f),
                    badge = "车机",
                )
            }
            Panel(
                title = "充电上限",
                subtitle = "车辆当前上限 ${snap?.chargeLimit?.let { "$it%" } ?: "--"}",
            ) {
                Text(
                    "$chargeLimit %",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TL.textHi,
                )
                Slider(
                    value = chargeLimit.toFloat(),
                    onValueChange = {
                        limitTouched = true
                        chargeLimit = it.toInt()
                    },
                    valueRange = 50f..100f,
                    steps = 9,
                    colors = sliderColors(),
                )
                Tile(
                    "设置充电上限",
                    gate(needMcu = true, stateReason = if (notPlugged) "未插充电枪" else null),
                    { vm.setChargeLimit(chargeLimit) },
                    Modifier.fillMaxWidth(),
                    badge = "车机",
                )
            }
        }

        CollapsibleSection(
            "驻车安全",
            sentrySummary(sentryOn, sentryFresh),
            // 哨兵状态来自车机（要会话才答），而且不是每轮都轮到。null 或已过期都补读：
            // 旧值曾经会被别的类的成功读"续命"，显示得像新鲜值。
            onExpand = { if (sentryOn == null || !sentryFresh) status.refresh(force = true) },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(
                    "哨兵开",
                    gate(needPark = true, stateReason = when {
                        sentryOn == true && sentryFresh -> "哨兵已开启"
                        else -> null
                    }),
                    { vm.sentry(true) },
                    Modifier.weight(1f),
                    badge = "车机",
                )
                Tile(
                    "哨兵关",
                    gate(needPark = true, stateReason = when {
                        sentryOn == false && sentryFresh -> "哨兵已关闭"
                        else -> null
                    }),
                    { vm.sentry(false) },
                    Modifier.weight(1f),
                    badge = "车机",
                )
            }
        }

        // 原状态页只活在这里的两项：经纬度/朝向。其余状态都由上方胶囊与按钮状态同步。
        val addr = place?.address
        CollapsibleSection(
            "车辆详情",
            when {
                addr != null -> addr
                snap?.latitude != null && snap?.longitude != null ->
                    "已定位，地址未解析" + if (snap?.sleepStatus == "休眠") " · 整车休眠" else ""
                else -> "位置未读取（需要车机响应）"
            },
            onExpand = {
                // 展开就是"我要看车在哪"：位置没读到就补一轮车机读取，读到了就翻一次地址。
                if (snap?.latitude == null || snap?.longitude == null) status.refresh(force = true)
                status.resolvePlace()
            },
        ) {
            Panel(tight = true) {
                val lat = snap?.latitude
                val lon = snap?.longitude
                KeyValue(
                    "经纬度",
                    if (lat != null && lon != null) "${"%.5f".format(lat)}, ${"%.5f".format(lon)}" else "未知",
                )
                if (lat != null && lon != null) {
                    val p = place
                    val pAddr = p?.address
                    KeyValue(
                        "位置",
                        when {
                            p == null -> "解析中…"
                            pAddr != null -> pAddr
                            else -> "解析失败"
                        },
                        tone = when {
                            pAddr != null -> Tone.Good
                            p == null -> Tone.Neutral
                            else -> Tone.Warn
                        },
                    )
                    // 车机给的是 WGS-84，高德要 GCJ-02，转换在解析内部做了；这事必须让他看得见。
                    Text(
                        listOfNotNull(
                            "坐标来源：车机 WGS-84 → ${p?.source ?: "解析中"}",
                            p?.note?.let { "试过：$it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (p?.note != null) TL.warn else TL.textLow,
                    )
                    if (p != null && p.address == null) {
                        HintBar(
                            "地址解析不出来多半是没配高德 Key：「设置 → 高德 Key（通用）」填一把「Web服务」平台的 Key 再点重新解析。" +
                                "OSM 在国内一般连不上，不能指望。",
                            Tone.Warn,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("重新解析地址", Tone.Neutral, selected = true) { status.resolvePlace(force = true) }
                        Pill(
                            "复制坐标",
                            Tone.Neutral,
                            selected = true,
                        ) {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as
                                android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    "car-location",
                                    (p?.address?.let { "$it " } ?: "") +
                                        String.format(java.util.Locale.US, "GPS %.5f, %.5f", lat, lon)
                                )
                            )
                        }
                    }
                }
                KeyValue("朝向", snap?.heading?.toString() ?: "未知")
                KeyValue("整车", snap?.sleepStatus ?: "未知")
                KeyValue(
                    "更新",
                    snap?.updatedAtMs?.takeIf { it > 0 }?.let {
                        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))
                    } ?: "从未",
                )
            }
        }

        if (log.isNotEmpty()) {
            SectionLabel("最近命令", "实测延迟")
            Panel(tight = true) {
                log.take(8).forEach { entry ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${if (entry.ok) "✓" else "✗"} ${entry.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (entry.ok) TL.good else TL.bad,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${entry.latencyMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = TL.textLow,
                            )
                        }
                        // 车辆返回的失败原因必须就地可读——页顶提示承诺过"失败原因会出现在这里"，
                        // 旧实现只渲染 ✓/✗ 和延迟，message 被丢掉，排障只能去调试页翻日志。
                        if (!entry.ok && !entry.message.isNullOrBlank()) {
                            Text(
                                entry.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = TL.warn,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 空调细项：座椅加热/通风、方向盘加热、MAX 除霜、驻车模式、生物防御。
 * 车机没报告对应设备（如没装座椅加热）时整行隐藏；档位控件点击在 关→低→中→高 间循环。
 */
@Composable
private fun ClimateExtras(
    vm: LinkViewModel,
    snap: StatusSnapshot?,
    mcuReady: Boolean,
    blocked: String?,
) {
    // 车机读数按"最后一次读到的值"显示；本页不轮询车机，按新鲜度清零只会让整块细项消失。
    val heatL = snap?.seatHeatLeft
    val heatR = snap?.seatHeatRight
    val coolL = snap?.seatCoolLeft
    val coolR = snap?.seatCoolRight
    val sw = snap?.swHeater
    val maxD = snap?.maxDefrost
    val keeper = snap?.keeper
    val bio = snap?.bioweaponOn

    if (heatL == null && heatR == null && coolL == null && coolR == null &&
        sw == null && maxD == null && keeper == null && bio == null
    ) {
        return
    }

    SectionLabel("空调 · 更多", "车机 · 车辆不支持的项目自动隐藏")
    val mcuGate = when {
        blocked != null -> blocked
        !mcuReady -> "车机未就绪"
        else -> null
    }
    if (heatL != null || heatR != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (heatL != null) {
                LevelTile("座椅加热 左", heatL, mcuGate, Modifier.weight(1f)) { vm.seatHeat((heatL + 1) % 4, true) }
            }
            if (heatR != null) {
                LevelTile("座椅加热 右", heatR, mcuGate, Modifier.weight(1f)) { vm.seatHeat((heatR + 1) % 4, false) }
            }
        }
    }
    if (coolL != null || coolR != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (coolL != null) {
                LevelTile("座椅通风 左", coolL, mcuGate, Modifier.weight(1f)) { vm.seatCool((coolL + 1) % 4, true) }
            }
            if (coolR != null) {
                LevelTile("座椅通风 右", coolR, mcuGate, Modifier.weight(1f)) { vm.seatCool((coolR + 1) % 4, false) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sw != null) {
            ToggleTile("方向盘加热", sw, mcuGate, Modifier.weight(1f)) { vm.steeringWheelHeater(!sw) }
        }
        if (maxD != null) {
            ToggleTile("MAX 除霜/预热", maxD, mcuGate, Modifier.weight(1f)) { vm.maxDefrost(!maxD) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (keeper != null) {
            val cycle = listOf("关闭" to "Off", "保持模式" to "On", "爱犬模式" to "Dog", "露营模式" to "Camp")
            val idx = cycle.indexOfFirst { it.first == keeper }.coerceAtLeast(0)
            val next = cycle[(idx + 1) % cycle.size]
            ToggleTile("驻车模式", keeper != "关闭", mcuGate, Modifier.weight(1f), stateText = keeper) {
                vm.climateKeeper(next.second)
            }
        }
        if (bio != null) {
            ToggleTile("生物防御", bio, mcuGate, Modifier.weight(1f), badge = "HEPA") { vm.bioweaponMode(!bio) }
        }
    }
}

/** 档位循环控件：显示当前档位，点击切到下一档。 */
@Composable
private fun LevelTile(
    label: String,
    level: Int,
    reason: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val names = listOf("关", "低", "中", "高")
    ActionTile(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = reason == null,
        stateLine = if (reason != null) reason else "当前 ${names[level.coerceIn(0, 3)]} · 点击切换",
        tone = if (reason == null) Tone.Info else Tone.Warn,
    )
}

/** 双态控件：显示开/关，点击切换。 */
@Composable
private fun ToggleTile(
    label: String,
    on: Boolean,
    reason: String?,
    modifier: Modifier = Modifier,
    stateText: String? = null,
    badge: String? = null,
    onClick: () -> Unit,
) {
    ActionTile(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = reason == null,
        stateLine = if (reason != null) reason else (stateText ?: if (on) "开 · 点击关闭" else "关 · 点击开启"),
        tone = if (reason == null) Tone.Info else Tone.Warn,
        badge = badge,
    )
}

private fun lockSummary(locked: Boolean?, rear: Boolean?, frunk: Boolean?): String {
    fun part(v: Boolean?, on: String, off: String, unknown: String) = when (v) {
        true -> on
        false -> off
        null -> unknown
    }
    return listOf(
        part(locked, "已落锁", "未落锁", "门锁未知"),
        part(rear, "后备箱开", "后备箱关", "后备箱未知"),
        part(frunk, "前备箱开", "前备箱关", "前备箱未知"),
    ).joinToString(" · ")
}

private fun climateSummary(on: Boolean?, driver: Float?, inside: Float?, auto: Boolean?, fan: Int?): String {
    val set = driver?.let { "%.1f°C".format(it) } ?: "--"
    val mode = when (auto) {
        true -> "AUTO"
        false -> "手动${fan?.let { " $it 档" } ?: ""}"
        null -> null
    }
    return when (on) {
        null -> "状态未知 · 设定 $set"
        true -> "运行中 · $set · 车内 ${inside?.let { "%.1f°C".format(it) } ?: "--"}" + (mode?.let { " · $it" } ?: "")
        false -> "已关闭 · 设定 $set"
    }
}

private fun chargeSummary(state: String?, limit: Int?): String =
    (state ?: "状态未知") + (limit?.let { " · 上限 $it%" } ?: "")

private fun sentrySummary(on: Boolean?, fresh: Boolean): String = when {
    on == null -> "哨兵状态未知"
    // 旧值如实标注读取时间：哨兵不是每轮都轮到，没有这个标注，几小时前的旧值看着像新鲜值。
    !fresh -> "哨兵${if (on) "开启" else "关闭"}（读数较旧，展开重读）"
    else -> "哨兵${if (on) "开启" else "关闭"}"
}

/** 顶部状态胶囊：一眼看到车辆现在是什么样，按钮为什么可用或不可用。 */
@Composable
private fun StatePills(snap: StatusSnapshot?, linkOk: Boolean, moving: Boolean) {
    fun onOff(value: Boolean?, on: String, off: String) = when (value) {
        null -> "未知"
        true -> on
        false -> off
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Pill("门锁 ${onOff(snap?.locked, "已落锁", "未落锁")}", if (snap?.locked == true) Tone.Good else Tone.Neutral)
        Pill("档位 ${snap?.shiftState ?: "未知"}", if (moving) Tone.Warn else Tone.Neutral)
        Pill("后备箱 ${onOff(snap?.rearTrunkShown(moving), "开", "关")}", if (snap?.rearTrunkShown(moving) == true) Tone.Warn else Tone.Neutral)
        Pill("前备箱 ${onOff(snap?.frunkShown(moving), "开", "关")}", if (snap?.frunkShown(moving) == true) Tone.Warn else Tone.Neutral)
        Pill("充电口 ${onOff(snap?.chargePortShown(moving), "开", "关")}", if (snap?.chargePortShown(moving) == true) Tone.Warn else Tone.Neutral)
        Pill("空调 ${onOff(snap?.climateOn, "运行", "关闭")}", if (snap?.climateOn == true) Tone.Data else Tone.Neutral)
        Pill(
            "车内 " + when (snap?.userPresence) {
                true -> "有人"
                false -> "无人"
                null -> "未知"
            },
            if (snap?.userPresence == true) Tone.Data else Tone.Neutral,
        )
        Pill("充电 ${snap?.chargingState ?: "未知"}", if (snap?.chargingState == "充电中") Tone.Good else Tone.Neutral)
        if (linkOk) {
            snap?.updatedAtMs?.takeIf { it > 0 }?.let {
                Pill(
                    "更新 " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)),
                    Tone.Neutral,
                )
            }
        }
    }
}

@Composable
private fun Tile(
    label: String,
    reason: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    ActionTile(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = reason == null,
        stateLine = reason,
        tone = if (reason == null) Tone.Info else Tone.Warn,
        badge = badge,
    )
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = TL.accent,
    activeTrackColor = TL.accent,
    inactiveTrackColor = TL.hairline,
)

/** 车机读数超过这个时长就提示"显示的是最后一次读到的值"（本页不轮询车机）。 */
private const val MCU_STALE_HINT_MS = 6_000L
