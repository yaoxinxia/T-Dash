package com.teslalink.app.tools

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class AccidentLocation(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val address: String?,
    /** 高德 / OSM / 无 */
    val geocodeSource: String,
    val fixTimeMs: Long,
    /** true = 本次没能取到新定位，用的是缓存（界面必须说明，免得用户拿旧位置报警）。 */
    val fromCache: Boolean,
    /** 逆地理失败时告诉用户试过什么、还缺什么。 */
    val geocodeNote: String?,
)

/** 一段坐标翻译出来的位置：地址可能为空（没 Key 又连不上 OSM），失败说明必须一并带回。 */
data class PlaceResult(
    val address: String?,
    /** 高德 / OSM / 无 */
    val source: String,
    val note: String?,
)

/** 报警/救援/保险快拨号（ACTION_DIAL 预填号码，用户按一下才拨出，无需 CALL_PHONE 权限）。 */
val EMERGENCY_NUMBERS = listOf(
    "122 · 交通事故报警" to "122",
    "110 · 公安报警" to "110",
    "120 · 急救" to "120",
    "12122 · 高速公路救援" to "12122",
)

val INSURANCE_HOTLINES = listOf(
    "人保财险 PICC" to "95518",
    "平安产险" to "95511",
    "太平洋保险" to "95500",
    "国寿财险" to "95519",
    "大地保险" to "95590",
    "阳光财险" to "95510",
    "中华联合财险" to "95585",
    "Tesla 道路救援" to "4009100707",
)

/** 现场取证清单：每项一个待办，勾选状态由界面维护。 */
val PHOTO_TASKS = listOf(
    "全景 · 车头方向" to "站得远一点，把两车、车牌、车道线和周围路牌建筑一起拍进去",
    "全景 · 车尾方向" to "从对面 45° 拍两车的相对位置和停止状态",
    "碰撞部位特写" to "贴近拍凹陷、划痕、掉漆，两张不同角度",
    "地面痕迹" to "刹车印、散落物、碎片位置（拍完再挪车）",
    "对方车牌与证件" to "车牌、行驶证、驾驶证、保险单（或电子保单页）",
    "本方损失与记录仪" to "自己车的损伤；确认记录仪已保存该时段（SavedClips）",
)

object AccidentHelper {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private const val FIX_TIMEOUT_MS = 25_000L

    /**
     * 车内首次定位可能十几到几十秒，所以：先立刻给缓存位置（标注 fromCache），
     * 同时最多等 25 秒新定位；两个都不行才失败，并给出可诊断的原因。
     */
    suspend fun locate(context: Context, amapKey: String?): Result<AccidentLocation> =
        withContext(Dispatchers.IO) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = activeProviders(lm)
            if (providers.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("手机的定位服务（GPS/网络定位）都是关闭的，请先到系统设置打开")
                )
            }
            val cached = bestLastKnown(lm, providers)
            // 取消要往上抛：页面离开取消定位时，吃掉 CancellationException 会让后面
            // 十几秒的逆地理继续在 IO 线程白跑（结果最终被丢弃）。
            val fresh = try {
                requestFix(lm, providers)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val fix = fresh ?: cached
                ?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000 }
            if (fix == null) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "${FIX_TIMEOUT_MS / 1000} 秒内没拿到定位（已启用：${providers.joinToString("、")}）。" +
                            "车内首次冷启动常要 30–60 秒，请到窗边或下车后重试"
                    )
                )
            }
            val usingCache = fresh == null
            val (source, address, note) = reverseGeocode(fix.latitude, fix.longitude, amapKey)
            Result.success(
                AccidentLocation(
                    lat = fix.latitude,
                    lng = fix.longitude,
                    accuracyM = fix.accuracy.takeIf { !it.isNaN() },
                    address = address.ifBlank { null },
                    geocodeSource = source,
                    fixTimeMs = fix.time,
                    fromCache = usingCache,
                    geocodeNote = if (address.isBlank()) note else null,
                )
            )
        }

    private fun activeProviders(lm: LocationManager): List<String> =
        buildList {
            if (Build.VERSION.SDK_INT >= 31 && runCatching { lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) }
                    .getOrDefault(false)
            ) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

    private fun bestLastKnown(lm: LocationManager, providers: List<String>): Location? =
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    /** 同时向所有可用 provider 请求连续更新，拿第一帧就全部取消。 */
    private suspend fun requestFix(lm: LocationManager, providers: List<String>): Location? =
        withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!cont.isActive) return
                        cont.resume(location)
                        providers.forEach { p -> runCatching { lm.removeUpdates(this) } }
                    }
                }
                providers.forEach { p ->
                    runCatching {
                        lm.requestLocationUpdates(p, 1_000L, 0f, listener, android.os.Looper.getMainLooper())
                    }
                }
                cont.invokeOnCancellation {
                    providers.forEach { p -> runCatching { lm.removeUpdates(listener) } }
                }
            }
        }


    /** 高德错误码翻译成人话——最常见的是拿错平台的 Key。 */
    private fun glossAmap(info: String, code: String): String = when (code) {
        "10009" -> "$code 平台不匹配：这把 Key 绑定的是「Web端/JS API」或客户端 SDK，" +
            "请在高德控制台重新添加 Key 并把服务平台选成「Web服务」"
        "10001" -> "$code Key 无效：检查是否复制完整、是否被删除"
        "10003" -> "$code 每日配额用尽"
        "10004" -> "$code 该 Key 已禁用"
        "10005" -> "$code 请求超出 QPS 限制"
        "10008" -> "$code 请求参数不合法（常见于未开启数字签名却带了 sig）"
        "10015" -> "$code 服务未开通或需实名认证"
        else -> if (info.isBlank()) "高德无返回" else "高德返回 $info ($code)"
    }

    /** 给"位置翻译"用的对外入口：WGS-84 坐标 → 地址（内部已做高德需要的 GCJ-02 转换）。 */
    fun placeFromWgs(wgsLat: Double, wgsLng: Double, amapKey: String?): PlaceResult {
        val (source, addr, note) = reverseGeocode(wgsLat, wgsLng, amapKey)
        return PlaceResult(addr.ifBlank { null }, source, note)
    }

    /** (来源, 地址, 失败说明)。高德优先（需 key），失败退 OSM Nominatim（国内可能连不上）。 */
    private fun reverseGeocode(
        wgsLat: Double,
        wgsLng: Double,
        amapKey: String?,
    ): Triple<String, String, String?> {
        val notes = mutableListOf<String>()
        val gcj = Coords.wgs84ToGcj02(wgsLat, wgsLng)
        if (!amapKey.isNullOrBlank()) {
            runCatching {
                val url = "https://restapi.amap.com/v3/geocode/regeo?key=$amapKey&output=json" +
                    "&location=${"%.6f".format(gcj.second)},${"%.6f".format(gcj.first)}&extensions=base"
                val json = JSONObject(http.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: "")
                if (json.optString("status") == "1") {
                    val addr = json.getJSONObject("regeocode").optString("formatted_address")
                    if (addr.isNotBlank()) return Triple("高德", addr, null)
                }
                notes += glossAmap(json.optString("info"), json.optString("infocode"))
            }.onFailure { notes += "高德请求失败 ${it.message}" }
        } else {
            notes += "未配置高德 Key"
        }
        runCatching {
            val url = "https://nominatim.openstreetmap.org/reverse" +
                "?lat=$wgsLat&lon=$wgsLng&format=jsonv2&accept-language=zh&zoom=18"
            val req = Request.Builder().url(url)
                .header("User-Agent", "TeslaLink/0.3 (personal car companion)")
                .build()
            val json = JSONObject(http.newCall(req).execute().body?.string() ?: "")
            val addr = json.optString("display_name")
            if (addr.isNotBlank()) return Triple("OSM", addr, null)
            notes += "OSM 无结果"
        }.onFailure { notes += "OSM 请求失败 ${it.message}" }
        // 国内基本只有高德这条路能通，所以把该怎么做写在脸上。
        return Triple(
            "无",
            "",
            notes.joinToString("；") + "——坐标已可用；中文地址需要一把服务平台为「Web服务」的高德 Key",
        )
    }
}
