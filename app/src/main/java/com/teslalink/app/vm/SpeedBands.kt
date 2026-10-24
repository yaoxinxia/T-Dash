package com.teslalink.app.vm

/**
 * 仪表盘速度分档：4 个变化点（km/h）把速度切成 5 段，每段一个调色板槽位。
 * 颜色存调色板下标而不是 ARGB，配置短、换色板也不会把老配置读成随机颜色。
 * 默认 66/88/110/132 = 60/80/100/120 限速 + 10%，与车机速度表向上取整同口径。
 */
data class SpeedBands(
    val breaks: List<Int> = DEFAULT_BREAKS,
    val colors: List<Int> = DEFAULT_COLORS,
) {
    /** 0..4：当前车速落在哪一段（最后一段没有上限）。 */
    fun bandIndex(kmh: Float): Int =
        breaks.indexOfFirst { kmh < it }.let { if (it < 0) 4 else it }

    /** 每一段的显示区间文本，如 "0–66" / "132+"，设置页预览和轨道都用同一口径。 */
    fun rangeLabel(index: Int): String {
        val lo = if (index == 0) 0 else breaks[index - 1]
        val hi = breaks.getOrNull(index)
        return if (hi == null) "$lo+" else "$lo–$hi"
    }

    /** 收进合法区间：相邻档至少差 1 km/h、不超过 240 km/h，调色板下标不能越界。 */
    fun normalized(): SpeedBands {
        val src = (breaks + DEFAULT_BREAKS).take(4)
        val out = ArrayList<Int>(4)
        var prev = 9
        for (i in 0..3) {
            val v = src[i].coerceIn(prev + 1, 240 - (3 - i))
            out.add(v)
            prev = v
        }
        val cols = (colors + DEFAULT_COLORS).take(5)
            .map { it.coerceIn(0, SpeedPalette.entries.lastIndex) }
        return SpeedBands(out, cols)
    }

    fun encode(): String = breaks.joinToString(",") + "|" + colors.joinToString(",")

    companion object {
        val DEFAULT_BREAKS = listOf(66, 88, 110, 132)
        val DEFAULT_COLORS = listOf(0, 1, 2, 3, 4)

        fun decode(raw: String?): SpeedBands {
            if (raw.isNullOrBlank()) return SpeedBands()
            val parts = raw.split("|")
            val b = parts.getOrNull(0)
                ?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
            val c = parts.getOrNull(1)
                ?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
            return SpeedBands(b, c).normalized()
        }
    }
}

/**
 * 可选颜色。只给一组固定色板：仪表是暗底、强反光环境，随手一调的低对比色在车上根本看不清，
 * 让他在"验证过对比度的颜色"里选，比给一个 HSV 色轮更不容易坑到自己。
 */
object SpeedPalette {
    data class Entry(val argb: Long, val label: String)

    val entries = listOf(
        Entry(0xFF38BDF8, "冰蓝"),
        Entry(0xFFE8EAED, "银白"),
        Entry(0xFFFFB300, "琥珀"),
        Entry(0xFFFF7043, "橙"),
        Entry(0xFFFF5252, "红"),
        Entry(0xFF4CAF50, "绿"),
        Entry(0xFFB388FF, "紫"),
        Entry(0xFF00E5FF, "青"),
    )

    fun argb(index: Int): Long = entries[index.coerceIn(0, entries.lastIndex)].argb
}
