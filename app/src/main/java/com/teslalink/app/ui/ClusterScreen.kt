package com.teslalink.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslalink.app.ui.components.ArcGauge
import com.teslalink.app.ui.components.RingGauge
import com.teslalink.app.ui.theme.TL
import com.teslalink.app.vm.Trip
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.teslalink.app.vm.DashboardViewModel
import com.teslalink.app.vm.LinkViewModel
import com.teslalink.app.vm.SpeedBands
import com.teslalink.app.vm.SpeedPalette
import com.teslalink.app.vm.StatusViewModel

/**
 * 手机数字仪表盘：横屏三联（速度 / 功率 / 电量），竖屏堆叠。
 * 停车（车机明确报 P）切驻车版面：时钟 + 电量/车外/空调三卡，行车件平滑缩到角落；
 * 挂进 D/R 再平滑回到行车版面。版面只跟档位走，断联保持原版面等重连。
 * 屏幕常亮；BLE 断流超过 3 秒显示"信号中断"。
 */
@Composable
fun ClusterScreen(dashboard: DashboardViewModel, status: StatusViewModel, link: LinkViewModel) {
    val state by dashboard.cluster.collectAsState()
    val bodyState by status.snapshot.collectAsState()
    val mcuReady by dashboard.mcuReady.collectAsState()
    val linkState by link.state.collectAsState()
    val autoReconnecting by link.autoReconnecting.collectAsState()
    val accelRun by dashboard.accelRun.collectAsState()
    val trips by dashboard.trips.collectAsState()
    val tripNote by dashboard.tripNote.collectAsState()
    val phoneHeading by dashboard.phoneHeading.collectAsState()
    val speedBands by dashboard.speedBands.collectAsState()
    val weather by dashboard.weather.collectAsState()

    // 「接续上一段」：上一段行程结束在 1 小时内才出现（服务区休息场景）。
    // 30 秒心跳只为让窗口到点后自己消失；行程收尾（trips 变化）会立刻重算。
    var resumeTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            resumeTick = System.currentTimeMillis()
        }
    }
    var resumeAck by remember { mutableStateOf<String?>(null) }
    var resumeAckTone by remember { mutableStateOf(TL.good) }
    LaunchedEffect(resumeAck) {
        if (resumeAck != null) {
            delay(2_500)
            resumeAck = null
        }
    }
    val resumeCandidate = remember(trips, resumeTick) { dashboard.resumableTrip(resumeTick) }
    val resumeVisible = resumeAck != null || resumeCandidate != null
    // 没有进行中的行程就回退到上一次已收尾的：不然停车状态永远只看到一个空圈，
    // 他会以为这功能没做（他连着问了两次"轨迹图和朝向放哪儿了"）。
    val openTrip = trips.firstOrNull { it.endedAt == 0L }
    val badgeTrip = openTrip ?: trips.firstOrNull()
    val accelText = accelRun?.let {
        when {
            it.t0Ms == null -> "待命 · 起步即计时"
            it.t100Ms == null -> "%.2f s".format((System.currentTimeMillis() - it.t0Ms) / 1000f)
            else -> null
        }
    }

    // 屏幕常亮由 AppRoot 统一管理（仪表/调试页共享同一规则）。

    val stale = state.updatedAtMs == 0L || System.currentTimeMillis() - state.updatedAtMs > 3_000
    val notConnected = linkState != com.teslalink.app.vehicle.VehicleConnection.State.CONNECTED
    val connecting = notConnected && (
        linkState == com.teslalink.app.vehicle.VehicleConnection.State.CONNECTING || autoReconnecting
        )
    val retry: (() -> Unit)? = if (notConnected) ({ link.connect() }) else null
    // 驻车/行车版面只认车机明确报过的档位。断连时 cluster 被清空、shift 变 null，若直接用
    // shift=="P" 判定，断连瞬间版面会闪回行车版、重连又跳回来——他明确要求"意外断联时
    // 不要把模式切来切去，等待重连即可"。闩存最近一次真实档位：断联保持原版面。
    // 初始值 = 驻车：冷启动还没读到任何档位时按停车版面亮时钟和电量/温度卡（他实测点名），
    // 第一次读到档位后由档位接管；行车中断联也停在这套版面上等重连。
    var parked by remember { mutableStateOf(true) }
    LaunchedEffect(state.shift) { state.shift?.let { parked = it == "P" } }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    0f to Color(0xFF0B1016),
                    0.65f to Color(0xFF06090D),
                    1f to Color(0xFF020304),
                )
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val landscape = maxWidth > maxHeight
                if (landscape) {
                    // 三栏宽度跟着版面走：行车时速度环吃高度不吃宽度，中栏原来占 52% 的
                    // 宽度却只在中间用掉一个圆，两侧被挤出一大片留白（他截图点名的）。
                    // 行车 0.80/1.20/0.84、驻车 1.02/0.94/0.90，与速度环缩放的 420ms 同步。
                    // 字号有 150sp 上限，中栏收窄不会把数字压小；驻车时环已缩到槽内右下角。
                    val colSpec = tween<Float>(420, easing = FastOutSlowInEasing)
                    val leftW by animateFloatAsState(if (parked) 1.02f else 0.80f, colSpec, label = "leftColW")
                    val centerW by animateFloatAsState(if (parked) 0.94f else 1.20f, colSpec, label = "centerColW")
                    val rightW by animateFloatAsState(if (parked) 0.90f else 0.84f, colSpec, label = "rightColW")
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 横屏三联：功率左、速度+档位当家居中、本次行程右（原电量圈的位置）。
                        // 驻车版面：左槽换成时钟+三卡（功率淡出——驻车时功率本来就在 0 附近），
                        // 速度环缩到槽内右下角，行程卡原位不动。
                        Box(Modifier.weight(leftW).fillMaxHeight()) {
                            val powerAlpha by animateFloatAsState(
                                if (parked) 0f else 1f,
                                tween(420, easing = FastOutSlowInEasing),
                                label = "powerAlpha",
                            )
                            if (powerAlpha > 0.01f) {
                                PowerGauge(state, stale, Modifier.align(Alignment.Center).fillMaxWidth().alpha(powerAlpha))
                            }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ParkedInfo(
                                    state.soc, state.outsideTemp, state.driverTemp, state.insideTemp,
                                    state.climateOn, state.charging, state.chargePowerKw,
                                    weather = weather,
                                    vertical = true, visible = parked,
                                    modifier = Modifier.fillMaxWidth(0.92f),
                                )
                            }
                        }
                        Box(Modifier.weight(centerW).fillMaxHeight()) {
                            ClusterRing(
                                state, stale, connecting, retry, accelText, parked, speedBands,
                                portrait = false,
                            )
                        }
                        TripBadge(
                            badgeTrip, state.odometerKm,
                            horizontal = false, note = tripNote, live = openTrip != null,
                            phoneHeading,
                            modifier = Modifier.weight(rightW),
                        )
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 竖屏：速度+档位占主体，底部左右两小块——左下功率小圈、右下路线形状 + 六格读数。
                        // 底块自己包住内容（不撑高，撑出来的空档会卡在档位胶囊和卡片中间），
                        // 但余量分成上下两段：全给速度区的话底块就坐到指示条头上，整页重心掉到底下。
                        // 下面这条 0.22 的带子把底块往上顶起约 90dp，让它悬在"数字下方"而不是"屏幕底部"。
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            ClusterRing(
                                state, stale, connecting, retry, accelText, parked, speedBands,
                                portrait = true,
                            )
                            ParkedInfo(
                                state.soc, state.outsideTemp, state.driverTemp, state.insideTemp,
                                state.climateOn, state.charging, state.chargePowerKw,
                                weather = weather,
                                vertical = false, visible = parked,
                                modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.56f),
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(0.78f), contentAlignment = Alignment.Center) {
                                PowerGauge(state, stale, Modifier.fillMaxWidth(0.92f))
                            }
                            TripBadge(
                                badgeTrip, state.odometerKm,
                                horizontal = true, note = tripNote, live = openTrip != null,
                                phoneHeading,
                                modifier = Modifier.weight(1.22f),
                            )
                        }
                        Spacer(Modifier.weight(0.22f))
                    }
                }

                // 行车版面右上角：迷你时间+天气（驻车版面的时钟/天气让位给车速后，这里保住一眼可读）。
                // 天气没开/没配 Key/失败时只显示时间，不放占位符。横幅都居中、接续浮层在左上，右上角是空位。
                DriveMiniClock(
                    weather,
                    visible = !parked,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = if (landscape) 18.dp else 14.dp),
                )

                // 断开时不显示：那条连接提示已经占了这一行，而且断开后 updatedAtMs 清零、
                // stale 恒为真，两个提示会叠在同一位置。
                if (stale && !notConnected) {
                    Banner(
                        "BLE 无新数据 · 车辆可能已休眠",
                        TL.warn,
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = if (resumeVisible) 54.dp else 10.dp),
                    )
                }
            }

            // 真实状态指示条：只放协议里读得到的信号。vehicle-command 里没有任何灯状态字段
            // （只有「闪灯」命令），所以这里不画假灯，全部来自 VCSEC 与车机的真实响应。
            // 行驶中开闭件 VCSEC 一律报 UNKNOWN → 行驶中物理上不可能开着，推断为关。
            val moving = state.speedKmh > 2f
            TelltaleRow(
                locked = bodyState?.locked,
                charging = state.charging,
                chargePowerKw = state.chargePowerKw,
                frunkOpen = bodyState?.frunkShown(moving),
                trunkOpen = bodyState?.rearTrunkShown(moving),
                chargePortOpen = bodyState?.chargePortShown(moving),
                climateOn = bodyState?.climateOn,
                asleep = bodyState?.sleepStatus == "休眠",
                stale = stale,
                mcuReady = mcuReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
        }

        // 连接提示：与休眠横幅同款横条幅（他偏好这种样式），两者互斥不重叠；
        // 重连入口仍是触摸速度大数字（见 SpeedDisplay 的声呐脉冲）。
        // 文案必须与数字动效同一个真值：横幅说"自动重连中"，数字就得在动（SpeedDisplay 用的就是这个 connecting）。
        if (notConnected) {
            Banner(
                when {
                    linkState == com.teslalink.app.vehicle.VehicleConnection.State.CONNECTING -> "连接中…"
                    autoReconnecting -> "未连接 · 自动重连中…"
                    else -> "未连接 · 触摸数字重试"
                },
                if (linkState == com.teslalink.app.vehicle.VehicleConnection.State.CONNECTING) TL.accent else TL.warn,
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (resumeVisible) 54.dp else 10.dp),
            )
        }

        // 「接续上一段」浮层：服务区休息回来，把上一段接到本段前面（上一段结束在 1 小时内才出现）。
        if (resumeVisible) {
            ResumePill(
                text = resumeAck ?: resumeCandidate
                    ?.let { "接续上一段 · ${hhmm(it.endedAt)} 结束" }
                    .orEmpty(),
                tone = if (resumeAck != null) resumeAckTone else TL.good,
                active = resumeAck == null,
                onClick = {
                    val resumed = dashboard.continuePreviousTrip()
                    resumeTick = System.currentTimeMillis()
                    if (resumed != null) {
                        resumeAckTone = TL.good
                        resumeAck = "已接续 · 本段从 ${hhmm(resumed.startedAt)} 起"
                    } else {
                        resumeAckTone = TL.warn
                        resumeAck = "接续失败：上一段已超出 1 小时"
                    }
                },
                onDismiss = {
                    dashboard.dismissResumeOffer()
                    resumeTick = System.currentTimeMillis()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 10.dp),
            )
        }
    }
}

/**
 * 「接续上一段」浮层。active 时左右两块都能点：主区接续，✕ 只把这一次的提示收掉。
 * 接续完 / 失败后同一个位置显示结果（2.5 秒），不弹 Toast——仪表在车上，提示要就地可见。
 */
@Composable
private fun ResumePill(
    text: String,
    tone: Color,
    active: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = 0.14f))
            .border(1.dp, tone.copy(alpha = 0.45f), RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .then(if (active) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 13.dp, end = if (active) 6.dp else 13.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(if (active) "⟳" else "✓", color = tone, fontSize = 13.sp)
            Text(
                text,
                style = TextStyle(
                    fontSize = 11.5.sp,
                    letterSpacing = 0.6.sp,
                    fontWeight = FontWeight.Medium,
                    color = tone,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
        if (active) {
            Box(
                Modifier
                    .clickable(onClick = onDismiss)
                    .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = tone.copy(alpha = 0.75f), fontSize = 12.sp)
            }
        }
    }
}

private fun hhmm(t: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(t))

/** 顶部提示条：半透明底 + 同色描边 + 脉动点。 */
@Composable
private fun Banner(text: String, tone: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "banner")
    val dot by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dot",
    )
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = 0.12f))
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tone.copy(alpha = dot)))
        Text(
            text,
            style = TextStyle(
                fontSize = 11.5.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Medium,
                color = tone,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

/** 状态指示条：只放协议里真实可读的信号；没激活的灯不占位。 */
@Composable
private fun TelltaleRow(
    locked: Boolean?,
    charging: Boolean,
    chargePowerKw: Int?,
    frunkOpen: Boolean?,
    trunkOpen: Boolean?,
    chargePortOpen: Boolean?,
    climateOn: Boolean?,
    asleep: Boolean,
    stale: Boolean,
    mcuReady: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Telltale(
            when (locked) {
                true -> "🔒 落锁"
                false -> "🔓 解锁"
                null -> "门锁 --"
            },
            when (locked) {
                true -> TL.good
                false -> TL.warn
                null -> TL.textLow
            },
        )
        if (charging) {
            Telltale("⚡ 充电" + (chargePowerKw?.let { " ${it}kW" } ?: ""), TL.data)
        }
        if (frunkOpen == true) Telltale("前备箱 开", TL.warn)
        if (trunkOpen == true) Telltale("后备箱 开", TL.warn)
        if (chargePortOpen == true) Telltale("充电口 开", TL.data)
        if (climateOn == true) Telltale("❄ 空调", TL.data)
        if (asleep) Telltale("整车休眠", TL.textLow)
        Telltale(
            if (mcuReady) "车机在线" else "车机离线",
            if (mcuReady) TL.textMid else TL.textLow,
        )
        if (stale) Telltale("BLE 中断", TL.bad)
    }
}

@Composable
private fun Telltale(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 10.sp, letterSpacing = 0.5.sp, color = color, maxLines = 1)
    }
}

/**
 * 大数字车速：等宽字形防抖动，分档配色 + 阈值轨道 + 轻微辉光呼吸。
 * 未连接时整个数字可触摸发起重连：点击给一圈"声呐脉冲"扩散环 + 数字弹跳，
 * 连接尝试期间环持续往外荡，直到连上或回到静默。
 */
@Composable
private fun SpeedDisplay(
    state: DashboardViewModel.ClusterState,
    stale: Boolean,
    modifier: Modifier = Modifier,
    connecting: Boolean = false,
    onTap: (() -> Unit)? = null,
    accelText: String? = null,
    bands: SpeedBands = SpeedBands(),
) {
    val animated by animateFloatAsState(
        targetValue = state.speedKmh,
        // 450ms 的缓动会让数字永远追在真实车速后面——感知延迟比轮询本身还大。
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "speedDigits",
    )
    val band = speedBand(animated, bands)
    val hardAccel = state.powerKw > 120
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = if (band.pulse || hardAccel || connecting) 1.045f else 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val accent = when {
        stale -> TL.textLow
        hardAccel && !band.pulse -> Color(0xFFFF9800)
        else -> band.color
    }

    // 点击的一次性反馈：数字弹跳 + 双扩散环。
    var pingKey by remember { mutableIntStateOf(0) }
    val bounce = remember { androidx.compose.animation.core.Animatable(1f) }
    val ring = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(pingKey) {
        if (pingKey > 0) {
            ring.snapTo(0f)
            kotlinx.coroutines.coroutineScope {
                launch {
                    bounce.snapTo(0.94f)
                    bounce.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
                }
                launch { ring.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
            }
        }
    }
    val connectRing by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)),
        label = "connectRing",
    )
    // 重连期间大数字自己也要呼吸：光有外圈环，数字是死的，看着像页面卡住了。
    val connectBreath by pulseTransition.animateFloat(
        initialValue = 0.972f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "connectBreath",
    )

    val tapModifier = if (onTap != null) {
        Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null) { pingKey++; onTap() }
    } else Modifier

    BoxWithConstraints(modifier.then(tapModifier), contentAlignment = Alignment.Center) {
        val avail = maxWidth.value
        // 字号/行高原来只按宽度推导。横屏的槽位又宽又矮，数字直接顶穿高度上限，
        // 把下面的 KM/H 和阈值条挤出去，跟底部对齐的档位叠在一起。
        // 一叠内容（数字 1.06 + KM/H 0.18 + 阈值条 0.09 + 档位 0.24 + 留白）必须同时受宽和高约束。
        // 一叠内容（数字 1.06 + KM/H ~0.22 + 阈值条 0.07 + 档位胶囊 0.24 + 各自字体上下留白）
        // 实测系数要给到 1.95——上次 1.74 在竖屏上 KM/H 仍被档位胶囊压住。
        val unit = minOf(avail * 0.42f, (maxHeight.value - 30f) / 1.95f).coerceIn(48f, 150f)
        val glow = maxWidth * (0.34f + 0.42f * (animated / 160f).coerceIn(0f, 1f))
        Box(
            Modifier
                .size(glow)
                .scale(pulse)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = if (band.pulse) 0.30f else 0.16f), Color.Transparent))
                )
        )
        // 声呐扩散环：一次性（点击）+ 连接尝试期间的循环。
        // 半径按数字本身（unit）推，不按槽位短边——横屏槽位又宽又矮，按短边算的环会顶出
        // 上下边界，正好压在档位上（他截图里就是这个叠字）。
        Canvas(Modifier.fillMaxSize()) {
            val u = unit.dp.toPx()
            val base = u * 0.55f
            val reach = u * 0.45f
            if (ring.value < 1f) {
                listOf(0f, 0.3f).forEach { delay ->
                    val p = (ring.value - delay).coerceIn(0f, 1f)
                    if (p > 0f) {
                        drawCircle(
                            TL.accent.copy(alpha = (1f - p) * 0.55f),
                            radius = base + reach * p,
                            style = Stroke(width = 3.dp.toPx() * (1f - p) + 1f),
                        )
                    }
                }
            }
            if (connecting) {
                val p = connectRing
                // 双环错开，荡出去的样子更接近"在找人/在喊车辆"，比单环显眼。
                listOf(0f, 0.45f).forEach { off ->
                    val q = (p + off).let { if (it > 1f) it - 1f else it }
                    drawCircle(
                        TL.accent.copy(alpha = (1f - q) * 0.5f),
                        radius = base + reach * q,
                        style = Stroke(width = 3.dp.toPx() * (1f - q) + 1f),
                    )
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                val s = bounce.value * (if (connecting) connectBreath else 1f)
                scaleX = s
                scaleY = s
            },
        ) {
            Text(
                // 车机速度表向上取整，这里同口径。取的是最新测量值而不是动画中间值——
                // 加速时动画永远落后于真实值，对中间值取 ceil 就会偶发比车机少 1。
                text = kotlin.math.ceil(state.speedKmh).toInt().coerceAtLeast(0).toString(),
                style = TextStyle(
                    fontSize = unit.sp,
                    lineHeight = (unit * 1.06f).sp,
                    fontWeight = FontWeight.W800,
                    letterSpacing = (-3).sp,
                    fontFeatureSettings = "tnum",
                    brush = Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.72f))),
                ),
            )
            Text(
                "KM/H",
                style = TextStyle(
                    fontSize = (unit * 0.18f).sp,
                    letterSpacing = (unit * 0.03f).sp,
                    fontWeight = FontWeight.Medium,
                    color = TL.textMid.copy(alpha = 0.8f),
                ),
            )
            SpeedRail(animated, bands, Modifier.width((unit * 1.05f).dp).padding(top = (unit * 0.07f).dp))
            accelText?.let {
                Text(
                    it,
                    fontSize = (unit * 0.2f).coerceAtMost(26f).sp,
                    letterSpacing = (unit * 0.015f).sp,
                    fontWeight = FontWeight.Bold,
                    color = TL.accent,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // 档位跟着数字走（原来钉在槽位底部，横屏时和数字栈分得很开，环一荡就压上去）。
            ShiftIndicator(
                state.shift,
                Modifier.padding(top = (unit * 0.08f).dp),
                fontSize = (unit * 0.24f).sp,
            )
        }
    }
}

/**
 * 速度环槽位：行车时铺满槽位居中；驻车时平滑缩小、滑向槽内右下角，给时钟和卡片让位，
 * 挂回 D/R 再滑回来。对齐走 BiasAlignment 的两个偏推动画、尺寸走宽高比例动画——
 * 小环是按新尺寸真实重排的（清晰），不是 graphicsLayer 拉出来的糊影。
 */
@Composable
private fun ClusterRing(
    state: DashboardViewModel.ClusterState,
    stale: Boolean,
    connecting: Boolean,
    retry: (() -> Unit)?,
    accelText: String?,
    parked: Boolean,
    bands: SpeedBands,
    portrait: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        val spec = tween<Float>(420, easing = FastOutSlowInEasing)
        val fw by animateFloatAsState(if (parked) (if (portrait) 0.40f else 0.46f) else 1f, spec, label = "ringW")
        val fh by animateFloatAsState(if (parked) (if (portrait) 0.58f else 0.62f) else 1f, spec, label = "ringH")
        val hb by animateFloatAsState(if (parked) 1f else 0f, spec, label = "ringHb")
        val vb by animateFloatAsState(if (parked) 1f else 0f, spec, label = "ringVb")
        SpeedDisplay(
            state, stale,
            Modifier
                .align(BiasAlignment(hb, vb))
                .fillMaxWidth(fw)
                .fillMaxHeight(fh),
            connecting, retry, accelText, bands,
        )
    }
}

/**
 * 行车版面右上角的迷你「时间 + 天气」。驻车大时钟让位给车速后，这里保住"现在几点、
 * 外面什么天气"的一眼可读；天气没开、没配 Key 或失败时只显示时间（宁缺不占位）。
 * 与驻车版面共用同一个 weather StateFlow——取数在行车中照常进行：走手机网络，
 * 不发任何 BLE 请求，也不占车机链路，与"行驶中只问车一件事"不冲突。
 * visible=false 整块撤出组合，alpha 与驻车版面切换同步。
 */
@Composable
private fun DriveMiniClock(
    weather: com.teslalink.app.weather.WeatherNow?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "driveMini",
    )
    if (alpha <= 0.01f) return
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    Column(
        modifier.alpha(alpha),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)),
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 0.6.sp,
                fontFeatureSettings = "tnum",
                color = TL.textHi,
            ),
        )
        weather?.let { w ->
            Text(
                "${w.text} ${w.tempC}°",
                fontSize = 10.5.sp,
                letterSpacing = 0.4.sp,
                color = TL.textLow,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 驻车版面：时间为主（大时钟 + 日期），下面三张卡——电量 / 车外 / 空调。
 * visible=false 时整块撤出组合（时钟也不走表），alpha 淡入淡出与速度环的滑移动画同步。
 * 数据与慢车道同源：行驶中不发 CHARGE/CLIMATE，卡片行车中本来也不可见。
 * vertical=true（横屏左槽）时钟小一号、卡片竖排；false（竖屏）卡片一行三张。
 */
@Composable
private fun ParkedInfo(
    soc: Int?,
    outsideTemp: Float?,
    driverTemp: Float?,
    insideTemp: Float?,
    climateOn: Boolean?,
    charging: Boolean,
    chargePowerKw: Int?,
    weather: com.teslalink.app.weather.WeatherNow?,
    vertical: Boolean,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "parkedInfo",
    )
    if (alpha <= 0.01f) return
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val clock = "%02d:%02d".format(
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE),
    )
    val dateText = SimpleDateFormat("M月d日 E", Locale.CHINA).format(cal.time)

    val cards = listOf(
        Triple(
            "电量%",
            soc?.let { "$it" } ?: "--",
            if (charging) "充电中" + (chargePowerKw?.let { " · ${it}kW" } ?: "") else " ",
        ),
        Triple("车外°C", outsideTemp?.let { "%.0f".format(it) } ?: "--", " "),
        Triple(
            "空调°C",
            // is_climate_on 是可选字段，车不动空调时可能一直不报：不能因为它是 null 就把
            // 已经读到的设定温度打成 "--"。明确报关才显"关"，有设定温度显数值，
            // 只知道开着不知道温度显"开"，两者皆无才 "--"。
            // 15.0/31.0 是车机的设定下限/上限哨兵（面板上的"低/高"），照搬数字会让他对不上。
            when {
                climateOn == false -> "关"
                driverTemp != null -> when (driverTemp) {
                    15.0f -> "低"
                    31.0f -> "高"
                    else -> "%.1f".format(driverTemp)
                }
                climateOn == true -> "开"
                else -> "--"
            },
            insideTemp?.let { "车内 %.1f°".format(it) } ?: " ",
        ),
    )

    Column(
        modifier.alpha(alpha),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            clock,
            style = TextStyle(
                fontSize = (if (vertical) 38 else 54).sp,
                fontWeight = FontWeight.W800,
                letterSpacing = (-1.5).sp,
                fontFeatureSettings = "tnum",
                brush = Brush.verticalGradient(listOf(TL.textHi, TL.textHi.copy(alpha = 0.72f))),
            ),
        )
        Text(
            dateText,
            fontSize = if (vertical) 11.5.sp else 13.sp,
            letterSpacing = 1.sp,
            color = TL.textMid,
            modifier = Modifier.padding(top = 2.dp),
        )
        weather?.let { w ->
            Text(
                "${w.city} · ${w.text} ${w.tempC}°",
                fontSize = if (vertical) 10.5.sp else 12.sp,
                letterSpacing = 0.4.sp,
                color = TL.textLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (vertical) {
            Column(
                Modifier.padding(top = 12.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                cards.forEach { ParkedCard(it.first, it.second, it.third, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cards.forEach { ParkedCard(it.first, it.second, it.third, Modifier.weight(1f)) }
            }
        }
    }
}

/** 驻车信息卡：标签在上、大数值居中、小注脚垫底（空注脚用空格占位，三张卡等高）。 */
@Composable
private fun ParkedCard(label: String, value: String, sub: String?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TL.raised.copy(alpha = 0.72f))
            .border(1.dp, TL.hairlineSoft, RoundedCornerShape(12.dp))
            // 水平内边距 6dp：竖屏三张卡分到每张只有 70dp 上下，"车内 24.5°"这种注脚
            // 8dp 边距 + 9sp 时会被省略号吃掉一截（他实测抓到的）。
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            label,
            style = TL.eyebrow.copy(fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp),
            color = TL.textLow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = TL.eyebrow.copy(
                fontSize = 18.sp,
                lineHeight = 23.sp,
                letterSpacing = 0.sp,
                fontWeight = FontWeight.W700,
                fontFeatureSettings = "tnum",
            ),
            color = TL.textHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            sub ?: " ",
            style = TL.eyebrow.copy(fontSize = 8.5.sp, lineHeight = 12.sp, letterSpacing = 0.sp),
            color = TL.textLow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 阈值轨道：按用户设定的 4 个变化点分五段，每段宽度就是该速度区间本身。 */
@Composable
private fun SpeedRail(kmh: Float, bands: SpeedBands, modifier: Modifier = Modifier) {
    val b = bands.breaks
    val top = maxOf(160, b[3] + 28)
    val segments = listOf(
        (b[0]).toFloat(),
        (b[1] - b[0]).toFloat(),
        (b[2] - b[1]).toFloat(),
        (b[3] - b[2]).toFloat(),
        (top - b[3]).toFloat(),
    )
    val index = bands.bandIndex(kmh)
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        segments.forEachIndexed { i, weight ->
            val color = Color(SpeedPalette.argb(bands.colors[i]))
            Box(
                Modifier
                    .weight(weight.coerceAtLeast(1f))
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= index) color.copy(alpha = if (i == index) 1f else 0.45f) else TL.hairline)
            )
        }
    }
}

private data class SpeedBand(val color: Color, val pulse: Boolean)

/**
 * 本次行程块（原电量圈的位置）：里程与用时从 GPS 轨迹实时算，路线形状旋转到"行进方向朝上"。
 * 方向优先用轨迹点里的 GPS 航向（bearing，正北起顺时针）——他特意提醒过不能用手机朝向；
 * 但刚起步 GPS 还算不出运动航向时按他 2026-10-21 的要求用手机罗盘兜底（支架上手机朝前，
 * 方位角基本就是车头方向）。总里程也在这里。
 * 没起步落点时画一只罗盘针占位（有航向后整只表随航向转），一眼能认出这是方向/轨迹表。
 * horizontal=true（竖屏）画横条，false（横屏右槽）画竖排。
 */
@Composable
private fun TripBadge(
    trip: Trip?,
    odometerKm: Float?,
    horizontal: Boolean,
    note: String?,
    live: Boolean,
    phoneHeading: Int?,
    modifier: Modifier = Modifier,
) {
    // 用时每秒走一格；行车中速度流本来就在刷，停车收尾的 3 分钟里靠这个心跳走表。
    var nowMs by remember(trip?.startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(trip?.startedAt) {
        if (trip == null || !live) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Trip.points 是就地增长的 MutableList，引用永不变；而 RouteShape 的 Canvas 只在参数
    // 变了时才重画（size() 这类修饰符还按值判等）——直接把可变列表传下去，行车中新点只刷
    // 数字格、轨迹图永远停在首帧，要切一次统计页重组才恢复（他实测抓到的就是这个）。
    // 所以按点数做快照：每来一个新点给下游一个新 List，画布才会跟着重画。刺头点顺手滤掉。
    val rawPts = trip?.points
    val pts = remember(rawPts, rawPts?.size) {
        rawPts?.let { com.teslalink.app.vm.dropJumps(it) } ?: emptyList()
    }
    // 本次里程：里程计差值优先，GPS 累加兜底（口径见 Trip.distanceKm）。
    val distKm = trip?.distanceKm ?: 0.0
    // 朝向取"最近一个带 bearing 的点"而不是最后一个点：Android 只在定位点带速度时才给
    // bearing（静止定位 hasBearing()=false，网络定位基本没有），照抄最后一个点的话，
    // P 档/红灯一停这格就闪成 "--"，明明几分钟前刚有朝向。
    // 进行中的行程一旦整条都还没有 bearing（刚起步），用手机罗盘方位角兜底。
    val heading = pts.lastOrNull { it.heading != null }?.heading
        ?: (if (live) phoneHeading else null)
    // 没有进行中的行程时显示上一次已收尾的（标"上次"），停车状态也能看见形状和朝向。
    val title = if (live) "本次" else "上次"

    // 用时 = 行程自己的时间跨度，不是"距上次出发过了多久"。原来写 nowMs - startedAt，
    // 停车后 BLE 断流、这段行程迟迟没收尾，挂机 53 小时就把均速摊成 0km/h（他抓到的就是这个）。
    // 只有最后一个 GPS 点还在 1 分钟内（真的在录）才跟着挂钟走，否则一律收到最后一个点。
    val lastT = pts.lastOrNull()?.t ?: trip?.startedAt ?: 0L
    val endMs = when {
        trip == null -> 0L
        live && System.currentTimeMillis() - lastT < 60_000L -> nowMs
        else -> maxOf(lastT, trip.endedAt)
    }
    val durMs = (endMs - (trip?.startedAt ?: endMs)).coerceAtLeast(0L)
    val minutes = (durMs / 60_000L).toInt()
    // 距离还没攒出来（刚起步、GPS 漂移）时不报均速，报 0 是假数。
    val avgKmh = if (durMs > 60_000L && distKm > 0.05) distKm / (durMs / 3_600_000.0) else null
    // 用时超过一小时走 h:mm：横屏右槽只有百来 dp，"53时22分" 会被折成两行。
    val durText = when {
        trip == null -> "--"
        minutes < 60 -> "${minutes}分"
        else -> "${minutes / 60}:${"%02d".format(minutes % 60)}"
    }

    val panel: Modifier = Modifier
        .clip(RoundedCornerShape(12.dp))
        .background(TL.raised.copy(alpha = 0.72f))
        .border(1.dp, TL.hairlineSoft, RoundedCornerShape(12.dp))

    // 六格读数：单位一律写在标签上，数值只留数字——竖屏右下槽分给每格只有 40dp 上下，
    // "12.3km" 这种带单位的值会被省略号吃掉。
    // 电量/温度不在这里：驻车时它们是驻车版面的卡片（ParkedInfo），混进格子曾是 P 档闪退的现场。
    val cells = listOf(
        TripCell("${title}km", if (trip == null) "--" else "%.1f".format(distKm)),
        TripCell("用时", durText),
        TripCell("均速km/h", avgKmh?.let { "%.0f".format(it) } ?: "--"),
        TripCell("朝向", heading?.let { compass8(it) } ?: "--"),
        TripCell("最高km/h", trip?.takeIf { it.points.isNotEmpty() }
            ?.let { "%.0f".format(kotlin.math.ceil(it.maxKmh)) } ?: "--"),
        TripCell("总里程km", odometerKm?.let { "%.0f".format(it) } ?: "--"),
    )

    if (horizontal) {
        Row(
            modifier
                .then(panel)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RouteArea(pts, Modifier.size(56.dp))
            TripGrid(cells, note, 2, Modifier.weight(1f))
        }
    } else {
        Column(
            modifier
                .then(panel)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 等比之后路线不会再填满盒子（装不下的方向留白），所以这里给足高度：
            // 一条东西向 4km × 南北向 2km 的路线，在 180dp 宽里只吃 52dp 高，太扁就看不清形状了。
            RouteArea(pts, Modifier.fillMaxWidth().height(76.dp))
            TripGrid(cells, note, 3, Modifier.fillMaxWidth())
        }
    }
}

private data class TripCell(val label: String, val value: String)

/** 读数表：标签在上（小字淡色）、数值在下（等宽数字）。横屏右槽窄，用 3 列；竖屏卡片宽，用 2 列。 */
@Composable
private fun TripGrid(cells: List<TripCell>, note: String?, cols: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        cells.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach {
                    Column(Modifier.weight(1f)) {
                        Text(
                            it.label,
                            style = TL.eyebrow.copy(fontSize = 8.5.sp, lineHeight = 11.sp, letterSpacing = 0.sp),
                            color = TL.textLow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            it.value,
                            style = TL.eyebrow.copy(
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                                letterSpacing = 0.sp,
                                fontFeatureSettings = "tnum",
                            ),
                            color = TL.textHi,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 不满行也要占位，否则最后一格会被拉宽、列对不齐。
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (note != null) {
            Text(
                note,
                style = TL.eyebrow.copy(fontSize = 8.5.sp, lineHeight = 11.sp, letterSpacing = 0.sp),
                color = TL.warn,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 路线区三种状态，都不留空白也不谎报：
 * 0 点 = "无轨迹"；1 点 = 当前位置圆点（"我在这里"，起步十几秒就是这个状态）；≥2 点 = 连线形状。
 */
@Composable
private fun RouteArea(pts: List<com.teslalink.app.vm.TripPoint>, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            pts.isEmpty() -> Text("无轨迹", style = TL.eyebrow, color = TL.textLow)
            pts.size == 1 -> Box(
                Modifier
                    .size(10.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(TL.accent),
            )
            else -> RouteShape(pts, Modifier.matchParentSize())
        }
    }
}

/**
 * 本次路线形状：正北朝上、**两轴同一比例尺**（不旋转、不带罗盘针——朝向交给旁边的八方位文字，
 * 一个没有指北参照的旋转图反而读不懂）。起点灰点、当前蓝点。
 *
 * 先换算成米再等比缩放：原来两轴各自把"同一个度数跨度"铺满自己的边长，横屏那个扁盒子
 * 就等于把南北方向压掉三四倍，路线看着被压瘪；而且一度经线的实际长度随纬度收缩，
 * 杭州大约是纬线的 0.86 倍，不按 cos(lat) 修正连形状都是错的。
 */
@Composable
private fun RouteShape(pts: List<com.teslalink.app.vm.TripPoint>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val step = maxOf(1, pts.size / 160)
        val picked = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.size - 1 }
        val minLat = picked.minOf { it.lat }
        val maxLat = picked.maxOf { it.lat }
        val minLng = picked.minOf { it.lng }
        val maxLng = picked.maxOf { it.lng }
        val midLat = (minLat + maxLat) / 2
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(midLat))
        val spanE = ((maxLng - minLng) * mPerDegLng).coerceAtLeast(1.0)
        val spanN = ((maxLat - minLat) * mPerDegLat).coerceAtLeast(1.0)
        val pad = 6.dp.toPx()
        // 一个比例尺（像素/米）同时套在两轴上：装不下的那个方向留白，形不能被拉。
        val k = minOf((size.width - 2 * pad) / spanE, (size.height - 2 * pad) / spanN).toFloat()
        val cxMid = size.width / 2
        val cyMid = size.height / 2
        val midLng = (minLng + maxLng) / 2
        fun cx(lng: Double) = cxMid + ((lng - midLng) * mPerDegLng).toFloat() * k
        fun cy(lat: Double) = cyMid - ((lat - midLat) * mPerDegLat).toFloat() * k

        val path = Path()
        picked.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(cx(p.lng), cy(p.lat)) else path.lineTo(cx(p.lng), cy(p.lat))
        }
        // 辉光垫底 + 主线，路线小图也保持和统计页地图同一套视觉语言。
        drawPath(
            path,
            TL.accent.copy(alpha = 0.22f),
            style = Stroke(width = 7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawPath(
            path,
            TL.accent,
            style = Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawCircle(TL.textMid.copy(alpha = 0.85f), radius = 2.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx(picked.first().lng), cy(picked.first().lat)))
        drawCircle(TL.accent, radius = 3.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx(picked.last().lng), cy(picked.last().lat)))
    }
}

/** 八方位罗盘名。 */
private fun compass8(bearing: Int): String {
    val names = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    val norm = ((bearing % 360) + 360) % 360
    return names[((norm / 45.0) + 0.5).toInt() % 8]
}

/**
 * 速度分档：4 个变化点（默认 66/88/110/132，对应 60/80/100/120 限速 + 10%）从设置页来。
 * 只变色、不打"接近限速/超速"之类的文字标签——数字本身已经足够信息。
 * 最上面两段沿用脉冲效果，其余段静止。
 */
private fun speedBand(kmh: Float, bands: SpeedBands): SpeedBand {
    val index = bands.bandIndex(kmh)
    return SpeedBand(Color(SpeedPalette.argb(bands.colors[index])), index >= 3)
}

@Composable
private fun PowerGauge(state: DashboardViewModel.ClusterState, stale: Boolean, modifier: Modifier = Modifier) {
    val powerColor = when {
        stale -> TL.textLow
        state.powerKw < -5 -> Color(0xFF4CAF50)
        state.powerKw > 150 -> Color(0xFFFF5252)
        else -> TL.textHi
    }
    ArcGauge(
        label = "功率",
        value = state.powerKw.toFloat(),
        min = -80f,
        max = 250f,
        unit = if (state.powerKw < -5) "kW · 回收" else "kW",
        digits = 0,
        color = powerColor,
        zeroBased = true,
        modifier = modifier,
    )
}

@Composable
private fun ShiftIndicator(
    shift: String?,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(TL.raised.copy(alpha = 0.55f))
            .border(1.dp, TL.hairlineSoft, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("P", "R", "N", "D").forEach { gear ->
            val active = shift == gear
            val color = when {
                !active -> TL.textLow.copy(alpha = 0.6f)
                gear == "P" -> Color(0xFF4CAF50)
                gear == "R" -> Color(0xFFFF9800)
                gear == "N" -> Color(0xFFFFEB3B)
                else -> Color(0xFF38BDF8)
            }
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (active) color.copy(alpha = 0.16f) else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    gear,
                    fontSize = if (active) fontSize * 1.15f else fontSize,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = color,
                )
            }
        }
    }
}
