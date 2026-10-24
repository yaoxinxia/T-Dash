package com.teslalink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ElectricCar
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslalink.app.AppContainer
import com.teslalink.app.ui.theme.TL
import com.teslalink.app.vm.DashboardViewModel
import com.teslalink.app.vm.LinkViewModel
import com.teslalink.app.vm.StatusViewModel

@Composable
fun AppRoot(container: AppContainer) {
    // 首页是仪表；5 页签时它排在正中（控制/工具/仪表/统计/设置）。
    val tab = remember { mutableIntStateOf(2) }
    val link = remember { LinkViewModel(container) }
    val status = remember { StatusViewModel(container) }
    val dashboard = remember { DashboardViewModel(container) }
    val devMode by link.devMode.collectAsState()
    val everConnected by link.everConnected.collectAsState()
    val keyPairedEver by link.keyPairedEver.collectAsState()
    val prefsLoaded by link.prefsLoaded.collectAsState()

    // 底栏页签：仪表是首页；「调试」只在开发者模式打开时出现。
    val tabs = buildList {
        add("控制" to Icons.Filled.Tune)
        add("工具" to Icons.Filled.Build)
        add("仪表" to Icons.Filled.Dashboard)
        add("统计" to Icons.Filled.Insights)
        if (devMode) add("调试" to Icons.Filled.BugReport)
        add("设置" to Icons.Filled.Settings)
    }
    val current = tabs[tab.intValue.coerceIn(0, tabs.lastIndex)].first

    // 从 P 挂进 R/D 就切到仪表页：手机就是仪表盘，出发那一刻不该还要手动切页签。
    // 只认"P 之后出现 R/D"这一次真实换挡——冷启动第一帧读到什么都不跳，
    // 否则刚打开 app 就被拽离他正在看的页面（N→D 这类中途换挡同样不打扰）。
    val cluster by dashboard.cluster.collectAsState()
    var lastShift by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cluster.shift) {
        val prev = lastShift
        lastShift = cluster.shift
        if (prev == "P" && (cluster.shift == "R" || cluster.shift == "D")) {
            tab.intValue = tabs.indexOfFirst { it.first == "仪表" }.coerceAtLeast(0)
        }
    }

    // 横屏：底栏换成左侧竖排导航栏；竖屏维持底栏。
    val landscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // 从没配对成功过才跳设置看引导；已配对的车钥匙冷启动直接进仪表（即便还没连上/人在车外）。
    // 必须等 prefs 读完再决定，否则默认 false 会先把用户按在设置页。
    androidx.compose.runtime.LaunchedEffect(prefsLoaded, keyPairedEver, everConnected) {
        if (prefsLoaded && !keyPairedEver && !everConnected) tab.intValue = tabs.lastIndex
    }

    // 常亮由这里统一持有：仪表（开车当仪表盘）和调试（一分钟自检不能黑屏）都需要它。
    // 各页自己加减会因为"新页 effect 先于旧页 dispose"而被误清除。
    val keepScreenOn = current == "仪表" || current == "调试" ||
        link.selfTestRunning.collectAsState().value
    val window = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(keepScreenOn) {
        if (keepScreenOn) {
            window?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { }
    }

    Scaffold(
        containerColor = TL.ink,
        contentColor = TL.textHi,
        bottomBar = {
            // 横屏用左侧 SideRail 代替底栏，下边不留条。
            if (!landscape) {
                TabBar(tabs, tab.intValue.coerceIn(0, tabs.lastIndex)) { tab.intValue = it }
            }
        },
    ) { padding ->
        val content: @Composable () -> Unit = {
            when (current) {
                "仪表" -> DashboardScreen(dashboard, status, link)
                "控制" -> ControlScreen(link, status)
                "工具" -> ToolsScreen(container, dashboard)
                "统计" -> StatsScreen(dashboard)
                "调试" -> DebugScreen(link)
                else -> SettingsScreen(container, link, dashboard)
            }
        }
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        if (landscape) {
            Row(modifier) {
                SideRail(tabs, tab.intValue.coerceIn(0, tabs.lastIndex)) { tab.intValue = it }
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Box(modifier) { content() }
        }
    }
}

/**
 * 横屏侧边导航：与底部 TabBar 同一设计语言（选中项上方的强调条 + 图标 + 文字），
 * 竖排铺满高度；发丝线画在右缘。insets 自己吃——三键导航横置在侧面时同样不能被盖。
 */
@Composable
private fun SideRail(
    tabs: List<Pair<String, ImageVector>>,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    val hairline = TL.hairline
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxHeight()
            .background(TL.base)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    hairline,
                    Offset(size.width - stroke / 2f, 0f),
                    Offset(size.width - stroke / 2f, size.height),
                    stroke,
                )
            }
            // 不能再吃 windowInsetsPadding：这一栏已经在 Scaffold 的 innerPadding 之内，左右安全区
            // 早扣过一遍；再吃一次会把"右侧导航栏"的宽度也从 60dp 里减掉，栏里只剩三十几 dp，
            // 「控制」被折成两行（他截图里就是这个）。键盘挡住底下一个页签才是这里真正要防的。
            .padding(bottom = with(density) { WindowInsets.ime.getBottom(density).toDp() })
            .width(60.dp)
    ) {
        tabs.forEachIndexed { index, (label, icon) ->
            val selected = index == current
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .selectable(selected = selected, onClick = { onSelect(index) }),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(width = 16.dp, height = 2.dp)
                        .background(if (selected) TL.accent else Color.Transparent)
                )
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (selected) TL.accent else TL.textLow,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp).size(20.dp),
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) TL.textHi else TL.textLow,
                )
            }
        }
    }
}

/** 底部导航：细发丝分隔线 + 选中项上方一道强调条，不用 Material3 的胶囊指示器。 */
@Composable
private fun TabBar(
    tabs: List<Pair<String, ImageVector>>,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    val hairline = TL.hairline
    Column(
        Modifier
            .fillMaxWidth()
            .background(TL.base)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(hairline, Offset(0f, stroke / 2f), Offset(size.width, stroke / 2f), stroke)
            }
            // targetSdk 35 起系统强制 edge-to-edge：自定义底栏必须自己吃掉导航栏 inset，
            // 否则三键导航会盖在页签上；键盘弹出时也跟着抬高。
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            tabs.forEachIndexed { index, (label, icon) ->
                val selected = index == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(selected = selected, onClick = { onSelect(index) }),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(width = 16.dp, height = 2.dp)
                            .background(if (selected) TL.accent else Color.Transparent)
                    )
                    Icon(
                        icon,
                        contentDescription = label,
                        tint = if (selected) TL.accent else TL.textLow,
                        modifier = Modifier.padding(top = 5.dp, bottom = 3.dp).size(20.dp),
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        letterSpacing = 0.6.sp,
                        color = if (selected) TL.textHi else TL.textLow,
                    )
                }
            }
        }
    }
}
