package com.teslalink.app.ui

import com.teslalink.app.ui.components.Tone
import com.teslalink.app.vm.StatusSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 类新读数的格式化与"报了才成行"判据。判据全在纯函数里，界面只负责画列表——
 * 这样空帧、残缺帧、单位换算都能在不上车的情况下验证。
 */
class LiveRowsTest {

    @Test
    fun `导航条文案：时间与里程各自可缺，都缺就整段不显示`() {
        assertEquals("12 分 · 8.4 km", navEtaText(12f, 8.4f))
        assertEquals("1 时 05 分 · 42 km", navEtaText(65f, 42.2f))
        assertEquals("8.4 km", navEtaText(null, 8.4f))
        assertEquals("12 分", navEtaText(12f, null))
        assertNull(navEtaText(null, null))
    }

    @Test
    fun `拥堵延时小于半分钟不算堵车`() {
        assertNull(navDelayText(0.4f))
        assertNull(navDelayText(null))
        assertEquals("堵 6 分", navDelayText(6.2f))
    }

    @Test
    fun `分钟数 负数是车机没在算 不当成 0 分`() {
        assertNull(minutesText(-1))
        assertNull(minutesText(null))
        assertEquals("0 分", minutesText(0))
        assertEquals("1 时 07 分", minutesText(67))
    }

    @Test
    fun `当日分钟数转时分 越界值不认`() {
        assertEquals("07:30", minuteOfDayText(450))
        assertEquals("00:00", minuteOfDayText(0))
        assertNull(minuteOfDayText(1_440))
        assertNull(minuteOfDayText(-5))
    }

    @Test
    fun `座椅档位说人话，超出量程原样报`() {
        assertEquals("关", seatLevelText(0))
        assertEquals("高", seatLevelText(3))
        assertEquals("7 档", seatLevelText(7))
        assertNull(seatLevelText(null))
    }

    @Test
    fun `空快照一行都不生成——界面因此不会画出半截真话夹一行 "--"`() {
        assertTrue(chargeLiveRows(null).isEmpty())
        assertTrue(chargeLiveRows(StatusSnapshot()).isEmpty())
        assertTrue(cabinLiveRows(StatusSnapshot()).isEmpty())
        assertTrue(closureLiveRows(StatusSnapshot()).isEmpty())
        assertTrue(savedPlaceRows(StatusSnapshot()).isEmpty())
    }

    @Test
    fun `桩在给电 电压电流相数按报出来的部分拼`() {
        val rows = chargeLiveRows(
            StatusSnapshot(
                rangeIdealKm = 320f,
                rangeRatedKm = 298f,
                chargerVoltage = 220f,
                chargerActualAmps = 16,
                chargerPilotAmps = 32,
                chargerPhases = 1,
                minutesToFullCharge = 67,
            )
        )
        assertEquals("320 km（额定 298）", rows.first { it.label == "续航" }.value)
        assertEquals("220V · 实际 16A · 引导 32A · 单相", rows.first { it.label == "桩在给电" }.value)
        assertEquals("1 时 07 分", rows.first { it.label == "充满还需" }.value)
        // 只有 ideal 时不带括号口径。
        val idealOnly = chargeLiveRows(StatusSnapshot(rangeIdealKm = 300f))
        assertEquals("300 km", idealOnly.first { it.label == "续航" }.value)
    }

    @Test
    fun `没插枪时桩电流全 0：不报"实际 0A"这种噪音`() {
        val rows = chargeLiveRows(StatusSnapshot(minutesToFullCharge = -1, energyAddedKwh = 0f))
        assertTrue(rows.none { it.label == "充满还需" })
        assertTrue(rows.none { it.label == "本次已充" })
    }

    @Test
    fun `电池预热两种状态分开说，没电不能预热是警告色`() {
        val heating = chargeLiveRows(StatusSnapshot(batteryHeater = true))
        assertEquals("进行中", heating.first { it.label == "电池预热" }.value)
        assertEquals(Tone.Good, heating.first { it.label == "电池预热" }.tone)
        val blocked = chargeLiveRows(StatusSnapshot(batteryHeaterNoPower = true))
        assertEquals(Tone.Warn, blocked.first { it.label == "电池预热" }.tone)
    }

    @Test
    fun `定时充电把模式与两个时刻并成一行`() {
        val rows = chargeLiveRows(
            StatusSnapshot(
                scheduledChargingMode = "定时充电：出发前",
                scheduledDepartAtMinute = 450,
                preconditionOnDeparture = true,
            )
        )
        assertEquals("定时充电：出发前 · 出发 07:30 · 预设座舱", rows.first { it.label == "车机定时" }.value)
        assertEquals(
            "未设定时",
            chargeLiveRows(StatusSnapshot(scheduledChargingMode = "未设定时")).first { it.label == "车机定时" }.value,
        )
    }

    @Test
    fun `V2L 整族不报时一行都不提，报了才显示`() {
        assertTrue(chargeLiveRows(StatusSnapshot()).none { it.label == "对外放电 V2L" })
        val rows = chargeLiveRows(
            StatusSnapshot(powershareStatus = "放电中", powershareLoadKw = 2.2f, outletSocLimit = 70)
        )
        assertEquals("放电中 · 负载 2.2 kW · 限到 70%", rows.first { it.label == "对外放电 V2L" }.value)
    }

    @Test
    fun `开闭件明细只报开着的；全关就空`() {
        val allShut = closureLiveRows(
            StatusSnapshot(
                doorDriverFront = false,
                doorDriverRear = false,
                windowDriverFront = false,
                sunRoofPercent = 0,
            )
        )
        assertTrue(allShut.isEmpty())
        val open = closureLiveRows(
            StatusSnapshot(
                doorDriverRear = true,
                windowPassengerFront = true,
                sunRoofPercent = 40,
                remoteStart = true,
            )
        )
        assertEquals("开 40%", open.first { it.label == "天窗" }.value)
        assertEquals("左后", open.first { it.label == "门开着" }.value)
        assertEquals("副驾", open.first { it.label == "窗开着" }.value)
        assertEquals("已启动，未踩刹车", open.first { it.label == "远程启动" }.value)
    }

    @Test
    fun `折叠摘要：没异常时退回原摘要，有异常时先说异常`() {
        val fallback = "车机 · 就绪"
        assertEquals(fallback, closureSummary(StatusSnapshot(), fallback))
        assertEquals(
            "开 40%",
            closureSummary(StatusSnapshot(sunRoofPercent = 40), fallback),
        )
    }

    @Test
    fun `座舱细项：预设与除霜各归各，MAX 除霜不再拿预设值当真值`() {
        val rows = cabinLiveRows(
            StatusSnapshot(
                preconditioningActive = true,
                frontDefrosterOn = true,
                rearDefrosterOn = false,
                seatHeatRearLeft = 2,
                seatHeatRearRight = 0,
                mirrorHeater = true,
                copAllowed = true,
                copState = "On",
                copCooling = true,
            )
        )
        assertEquals("按时刻表预设中", rows.first { it.label == "座舱预设" }.value)
        assertEquals("前", rows.first { it.label == "除霜" }.value)
        assertEquals("左 中 · 右 关", rows.first { it.label == "后排加热" }.value)
        assertEquals("On · 正在主动降温", rows.first { it.label == "离车高温保护" }.value)
        assertEquals("工作中", rows.first { it.label == "后视镜加热" }.value)
    }

    @Test
    fun `家与公司：两个坐标都齐才成行`() {
        assertTrue(savedPlaceRows(StatusSnapshot(homeLat = 30.25f)).isEmpty())
        val rows = savedPlaceRows(StatusSnapshot(homeLat = 30.25f, homeLon = 120.16f))
        assertEquals("30.25000, 120.16000", rows.single().value)
    }
}
