package com.teslalink.app

import android.app.Application
import com.teslalink.app.store.KeyManager
import com.teslalink.app.store.PrefsStore
import com.teslalink.app.store.SessionCacheStore
import com.teslalink.app.vehicle.VehicleConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container. BLE-only: no cloud endpoints. */
class AppContainer(private val app: Application) {
    /** 轨迹车道要在它上面注册 GPS 监听（行程采样改用手机定位，不再问车机）。 */
    val appContext: android.content.Context get() = app

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val prefs = PrefsStore(app)
    val keyManager = KeyManager(app)
    val sessionCache = SessionCacheStore(app)

    /** 由 MainActivity 维护；自动重连与 keep-alive 的"前台"条件之一。 */
    val appInForeground = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * 行驶闩：最近一次**新鲜**车机数据表明 D/R（或车速>2）的时刻 + 90s 宽限。
     * D/R 时息屏/后台要继续保持监听（手机就是仪表、轨迹不能断），P/N 落地即停。
     * 由 DashboardViewModel 的车速道维护；90s 宽限覆盖"链接刚断、还该试着恢复"的窗口。
     */
    val drivingUntilMs = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** keep-alive 门控：前台或行驶闩有效。 */
    fun keepAliveAllowed(): Boolean =
        appInForeground.value || drivingUntilMs.value > System.currentTimeMillis()

    val connection = VehicleConnection(
        app, appScope, keyManager, sessionCache,
        keepAliveAllowed = { keepAliveAllowed() },
    )
    val trips = com.teslalink.app.vm.TripRecorder(app.filesDir)

    /** 事故取证用的个人信息与证件照（本机存储；导出清单 PDF 时直接带上）。 */
    val profile = com.teslalink.app.store.ProfileStore(app, appScope)

    /** 由仪表/统计页的车速轮询实时维护；其他页用它决定行驶中要不要停掉自己的轮询。 */
    val vehicleMoving = kotlinx.coroutines.flow.MutableStateFlow(false)
}

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 第一件事：装崩溃钩子。容器初始化本身如果炸了也要留下现场。
        com.teslalink.app.debug.CrashLog.install(this)
        container = AppContainer(this)
        // osmdroid：UA 用于瓦片服务方的用量统计（OSM 使用政策要求可识别的 UA）；
        // 缓存放应用私有目录，避免任何存储权限。
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = java.io.File(filesDir, "osmdroid")
            osmdroidTileCache = java.io.File(filesDir, "osmdroid/tiles")
        }
    }
}
