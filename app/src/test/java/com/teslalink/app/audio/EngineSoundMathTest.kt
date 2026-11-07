package com.teslalink.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 声浪的映射曲线与波形：纯数学，不碰 Android 音频栈，所以能全部钉死。 */
class EngineSoundMathTest {

    @Test
    fun zeroAndNegativePowerStaySilent() {
        assertEquals(0f, engineLevelFor(0f), 0f)
        assertEquals(0f, engineLevelFor(-40f), 0f)
        // 电车没有怠速：松油门就是彻底安静，不做假底噪。
        assertEquals(0f, engineShakeFor(0f).shiftDp, 0f)
        assertEquals(0f, engineShakeFor(0f).rotateDeg, 0f)
        assertEquals(IDLE_FREQ, engineFreqFor(0f), 0.001f)
    }

    @Test
    fun levelRisesMonotonicallyAndSaturatesAtFullPower() {
        var previous = -1f
        var kw = 0f
        while (kw <= FULL_POWER_KW * 2f) {
            val level = engineLevelFor(kw)
            assertTrue("功率 $kw kW 时强度回退了", level >= previous)
            assertTrue("强度越界：$level", level in 0f..1f)
            previous = level
            kw += 5f
        }
        assertEquals(1f, engineLevelFor(FULL_POWER_KW), 0.001f)
        assertEquals(1f, engineLevelFor(400f), 0.001f) // 全油门远超满格，也只能顶格
        // 开平方：30 kW（满格的 1/4）就该有一半强度，轻踩才听得出反应。
        assertEquals(0.5f, engineLevelFor(FULL_POWER_KW / 4f), 0.001f)
    }

    @Test
    fun frequencyAndShakeTrackLevelAndNeverGoOutOfRange() {
        assertEquals(PEAK_FREQ, engineFreqFor(1f), 0.001f)
        assertTrue(engineFreqFor(0.5f) > engineFreqFor(0.2f))
        val shake = engineShakeFor(1f)
        assertTrue(shake.shiftDp > 0f && shake.rotateDeg > 0f)
        // 非法强度不能把 NaN 漏给动画：频率退到基频下限，抖动退到完全静止。
        assertEquals(IDLE_FREQ, engineFreqFor(Float.NaN), 0.001f)
        assertEquals(0f, engineShakeFor(Float.NaN).shiftDp, 0f)
        assertEquals(4.0f, engineShakeFor(9f).shiftDp, 0.001f)
    }

    @Test
    fun garbagePowerFromVehicleNeverBecomesNaN() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val level = engineLevelFor(bad)
            assertFalse("非有限功率必须归 0，得到 $level", level.isNaN())
            assertEquals(0f, level, 0f)
        }
    }

    @Test
    fun waveformIsBoundedAndPeriodicSoItCannotClip() {
        var maxAbs = 0.0
        var sum = 0.0
        val steps = 4096
        for (i in 0 until steps) {
            val s = EngineSound.sampleAt(i.toDouble() / steps)
            assertTrue("采样出现非有限值：$s", s.isFinite())
            maxAbs = maxOf(maxAbs, kotlin.math.abs(s))
            sum += s * s
        }
        // 波形归一化过：满强度（amp=1）乘上输出天花板后仍留有余量，不会削顶。
        assertTrue("波形峰值 $maxAbs 超过 1.0，会削顶", maxAbs <= 1.0)
        // 但也不能几乎是直流（那听起来就是"嗡"而不是机器）。
        assertTrue("波形太弱：rms=${sum / steps}", sum / steps > 0.05)
        assertEquals(EngineSound.sampleAt(0.0), EngineSound.sampleAt(1.0), 1e-9)
        // 相位回绕必须闭合：回绕点前后的采样差不能比正常步长大一个量级，否则每周期"哒"一声。
        val wrapStep = kotlin.math.abs(EngineSound.sampleAt(0.99975) - EngineSound.sampleAt(0.0))
        var maxInternalStep = 0.0
        for (i in 1 until steps) {
            maxInternalStep = maxOf(
                maxInternalStep,
                kotlin.math.abs(EngineSound.sampleAt(i.toDouble() / steps) - EngineSound.sampleAt((i - 1).toDouble() / steps)),
            )
        }
        assertTrue(
            "回绕不闭合：wrap=$wrapStep，内部最大步长=$maxInternalStep",
            wrapStep <= maxOf(0.05, maxInternalStep * 2.0),
        )
    }
}
