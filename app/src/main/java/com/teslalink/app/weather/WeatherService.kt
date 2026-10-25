package com.teslalink.app.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import com.teslalink.app.debug.DebugLog
import com.teslalink.app.store.PrefsStore
import com.teslalink.app.tools.Coords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 仪表驻车版面显示的一条天气实况。 */
data class WeatherNow(
    val city: String,
    val text: String,
    val tempC: Int,
    val fetchedAtMs: Long,
)

/**
 * 高德天气：手机定位 → 逆地理拿城市 → 天气实况。
 *
 * 天气变化慢，调用方按需 30 分钟刷一次即可，这里不做任何自主轮询。
 * Key 复用设置里的「高德 Key（通用）」；没 Key、没定位权限、任何一步失败都只记日志并返回 null——
 * 仪表宁可不显示，也不放一个 "--" 占位。
 */
class WeatherService(private val context: Context, private val prefs: PrefsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun apiKey(): String? = prefs.getString("amap_key")?.trim()?.ifBlank { null }

    /** 上次成功的结果；超过 12 小时当作没有——跨天后的旧城市比不显示更容易误导。 */
    suspend fun cached(): WeatherNow? {
        val raw = prefs.getString(CACHE_KEY) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            WeatherNow(
                city = o.optString("city"),
                text = o.optString("text"),
                tempC = o.optInt("temp"),
                fetchedAtMs = o.optLong("ts"),
            ).takeIf {
                it.city.isNotBlank() && it.text.isNotBlank() &&
                    System.currentTimeMillis() - it.fetchedAtMs < CACHE_MAX_AGE_MS
            }
        }.getOrNull()
    }

    /** 取一次实况并落缓存；失败返回 null（原因进日志），节流由调用方负责。 */
    suspend fun fetch(): WeatherNow? = withContext(Dispatchers.IO) {
        val key = apiKey() ?: run {
            DebugLog.log(TAG, "未配置高德 Key，天气不显示")
            return@withContext null
        }
        val loc = lastKnown() ?: run {
            DebugLog.log(TAG, "没有可用的手机定位，天气不显示")
            return@withContext null
        }

        val (gcjLat, gcjLng) = Coords.wgs84ToGcj02(loc.latitude, loc.longitude)
        val regeoUrl = "https://restapi.amap.com/v3/geocode/regeo?key=$key&output=json" +
            "&location=${"%.6f".format(gcjLng)},${"%.6f".format(gcjLat)}&extensions=base"
        val regeo = getJson(regeoUrl) ?: return@withContext null
        if (regeo.optString("status") != "1") {
            DebugLog.log(TAG, "逆地理失败 ${regeo.optString("info")} (${regeo.optString("infocode")})")
            return@withContext null
        }
        val comp = regeo.optJSONObject("regeocode")?.optJSONObject("addressComponent")
        val adcode = cityAdcode(comp?.optString("adcode").orEmpty()) ?: run {
            DebugLog.log(TAG, "逆地理没有城市编码，天气不显示")
            return@withContext null
        }
        val fallbackCity = comp?.optString("city").orEmpty().ifBlank {
            comp?.optString("province").orEmpty()
        }

        val weatherUrl = "https://restapi.amap.com/v3/weather/weatherInfo?key=$key&output=json" +
            "&city=$adcode&extensions=base"
        // 天气接口多数情况下认区码；省直辖县级市（如 419001）没有地级市可退，
        // 直接截断会变成"省直辖县级行政区划"。先查原码，空结果再试市码。
        val live = fetchLive(weatherUrl) ?: cityAdcode(adcode)
            ?.takeIf { it != adcode }
            ?.let { city ->
                fetchLive(
                    "https://restapi.amap.com/v3/weather/weatherInfo?key=$key&output=json" +
                        "&city=$city&extensions=base",
                )
            } ?: run {
            DebugLog.log(TAG, "天气查询无结果 adcode=$adcode")
            return@withContext null
        }
        val text = live.optString("weather").ifBlank { null } ?: return@withContext null
        val temp = live.optString("temperature").toIntOrNull() ?: return@withContext null

        val now = WeatherNow(
            city = live.optString("city").ifBlank { fallbackCity },
            text = text,
            tempC = temp,
            fetchedAtMs = System.currentTimeMillis(),
        )
        prefs.putString(CACHE_KEY, JSONObject().apply {
            put("city", now.city)
            put("text", now.text)
            put("temp", now.tempC)
            put("ts", now.fetchedAtMs)
        }.toString())
        DebugLog.log(TAG, "天气 ${now.city} ${now.text} ${now.tempC}°（adcode=$adcode）")
        now
    }

    private fun fetchLive(url: String): JSONObject? {
        val json = getJson(url) ?: return null
        if (json.optString("status") != "1") {
            DebugLog.log(TAG, "天气查询失败 ${json.optString("info")} (${json.optString("infocode")})")
            return null
        }
        return json.optJSONArray("lives")?.optJSONObject(0)
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "T-Dash/0.0.2").build())
            .execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) {
                    DebugLog.log(TAG, "HTTP ${resp.code}：${body.take(120)}")
                    null
                } else {
                    JSONObject(body)
                }
            }
    }.onFailure {
        DebugLog.error(TAG, "天气请求失败：${it.javaClass.simpleName}: ${it.message}", it)
    }.getOrNull()

    /** GPS / 网络 / 被动定位里取最新的一帧；只有大致位置权限时网络定位就够用。 */
    private fun lastKnown(): Location? {
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!coarse) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .maxByOrNull { it.time }
    }

    /**
     * 区级 adcode → 城市级。高德天气认到市（直辖市归到 xx0000）；
     * 逆地理返回的是区码，直接拿去查经常是空 lives。
     */
    private fun cityAdcode(adcode: String): String? {
        if (adcode.length != 6 || adcode.any { !it.isDigit() }) return null
        return when (adcode.take(2)) {
            "11", "12", "31", "50" -> adcode.take(2) + "0000"
            else -> adcode.take(4) + "00"
        }
    }

    private companion object {
        const val TAG = "weather"
        const val CACHE_KEY = "weather_cache"
        const val CACHE_MAX_AGE_MS = 12 * 60 * 60_000L
    }
}
