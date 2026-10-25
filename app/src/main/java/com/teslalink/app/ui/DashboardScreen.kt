package com.teslalink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.teslalink.app.vm.DashboardViewModel
import com.teslalink.app.vm.LinkViewModel
import com.teslalink.app.vm.StatusViewModel

/** 仪表盘页（首页）：只放数字仪表 + 真实状态指示条。历史曲线在独立的「统计」页。数据全部来自 BLE。 */
@Composable
fun DashboardScreen(dashboard: DashboardViewModel, status: StatusViewModel, link: LinkViewModel) {
    DisposableEffect(Unit) {
        dashboard.startBlePolling()
        dashboard.startWeather()
        // 门锁指示条来自 VCSEC（免鉴权、不唤醒车机），这里与控制页共享同一个引用计数的轮询。
        status.startBodyPolling()
        onDispose {
            dashboard.stopBlePolling()
            dashboard.stopWeather()
            status.stopBodyPolling()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050607))
    ) { ClusterScreen(dashboard, status, link) }
}
