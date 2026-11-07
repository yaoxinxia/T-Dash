package com.teslalink.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.teslalink.app.debug.DebugLog
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 玩具：模拟发动机声浪。手机本来就没有排气管，这里用 AudioTrack 现场合成 PCM——
 * **不带任何音频素材**（不下载、不解码、不占包体），纯代码可审计。
 *
 * 输入只有一个：车机报回来的**实时输出功率**（仪表车速道每 250–500ms 就有一个新值）。
 * 功率越大 → 基频越高、音量越大、仪表角标抖得越凶；松开油门（功率回落到 0）就淡出静音。
 * 电车没有"怠速"，所以 0 kW 就是 0 声音，不做假底噪。
 */
class EngineSound {

    @Volatile private var targetLevel = 0f
    @Volatile private var running = false
    private var worker: Thread? = null
    private var track: AudioTrack? = null

    /** 目标强度 0..1（[engineLevelFor] 算出来的值）。线程安全，随时可调。 */
    fun setLevel(level: Float) {
        targetLevel = if (!level.isFinite()) 0f else level.coerceIn(0f, 1f)
    }

    fun start() {
        if (running) return
        running = true
        val t = Thread({ loop() }, "engine-sound")
        t.priority = Thread.NORM_PRIORITY + 1
        worker = t
        t.start()
    }

    fun stop() {
        running = false
        targetLevel = 0f
        val t = worker
        worker = null
        // 写线程可能正阻塞在 AudioTrack.write 上：先停轨让它脱困，再等它退出。
        runCatching { track?.pause() }
        runCatching { track?.stop() }
        runCatching { t?.join(250) }
        runCatching { track?.release() }
        track = null
    }

    private fun loop() {
        val rate = SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            DebugLog.error("engine-sound", "系统不给音频缓冲（minBuf=$minBuf），声浪不可用")
            running = false
            return
        }
        val audio = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            (minBuf.coerceAtLeast(BLOCK_FRAMES * 2) / 2) * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track = audio
        val block = ShortArray(BLOCK_FRAMES)
        var phase = 0.0
        var amp = 0f
        var idle = true
        try {
            while (running) {
                val want = targetLevel
                // 快起慢落：踩下去 60ms 内就有反应，松油门 260ms 淡出，才不会"咔"一下断掉。
                val k = if (want > amp) ATTACK_PER_BLOCK else RELEASE_PER_BLOCK
                amp += (want - amp) * k
                if (amp < 0.004f) {
                    amp = 0f
                    if (!idle) {
                        runCatching { audio.pause() }
                        runCatching { audio.flush() }
                        idle = true
                    }
                    Thread.sleep(8)
                    continue
                }
                if (idle) {
                    runCatching { audio.play() }
                    idle = false
                }
                // 频率也按同一包络平滑，避免换挡式突跳（zipper noise）。
                val freq = engineFreqFor(amp)
                val inc = freq / rate
                for (i in 0 until BLOCK_FRAMES) {
                    block[i] = (sampleAt(phase) * amp * MAX_AMPLITUDE).toInt().toShort()
                    phase += inc
                    if (phase >= 1.0) phase -= 1.0
                }
                if (audio.write(block, 0, BLOCK_FRAMES) < 0) {
                    DebugLog.error("engine-sound", "AudioTrack 写入失败，声浪停止")
                    running = false
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            DebugLog.error("engine-sound", "声浪线程异常退出：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { audio.stop() }
            runCatching { audio.release() }
            track = null
            running = false
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val BLOCK_FRAMES = 512
        /** 输出天花板：留足余量，别把手机喇叭推到削顶。 */
        private const val MAX_AMPLITUDE = 12_000f
        private const val ATTACK_PER_BLOCK = 0.55f
        private const val RELEASE_PER_BLOCK = 0.16f

        /**
         * 单个采样：基频 + 整数倍泛音（近锯齿，才有"机器味"而不是哨声）。
         * **所有分量必须是 2π 的整数倍周期**——否则 phase 回绕处波形不闭合，
         * 每个周期都会"哒"一下（单测 waveformIsBoundedAndPeriodic 钉住了这一点）。
         * 粗糙感改由相位畸变提供：`p + 0.35·sin p` 仍是 2π 周期，但把正弦推成点火脉冲形状。
         */
        fun sampleAt(phase: Double): Double {
            val p = phase * 2.0 * PI
            val w = p + 0.35 * sin(p)
            val saw = 0.52 * sin(w) + 0.26 * sin(2 * w) + 0.15 * sin(3 * w) + 0.08 * sin(5 * w)
            val grit = 0.06 * sin(7 * w) + 0.045 * sin(9 * w)
            return (saw + grit) / 1.115
        }
    }
}

/**
 * 车机功率 → 声浪强度 0..1。
 * 0 kW 就是 0（电车没有怠速，不做假底噪）；到 [FULL_POWER_KW] 满格。
 * 开平方是为了让"轻踩"就有存在感——线性映射下 30 kW 才 0.25，听感上等于没响。
 * 非有限值（车机偶尔回 NaN/Inf）一律当 0，别让它流进动画和音频。
 */
fun engineLevelFor(powerKw: Float): Float =
    if (!powerKw.isFinite() || powerKw <= 0f) 0f
    else sqrt((powerKw / FULL_POWER_KW).coerceIn(0f, 1f))

/** 强度 → 基频 Hz：从低转的喉音一路抬到中高转，落在引擎声常见的区间里。 */
fun engineFreqFor(level: Float): Float =
    IDLE_FREQ + (PEAK_FREQ - IDLE_FREQ) * finiteLevel(level)

/** 仪表角标的抖动量：平移 dp 与旋转角度都随强度线性放大，0 功率时完全静止。 */
data class EngineShake(val shiftDp: Float, val rotateDeg: Float)

fun engineShakeFor(level: Float): EngineShake {
    val l = finiteLevel(level)
    return EngineShake(shiftDp = 4.0f * l, rotateDeg = 3.0f * l)
}

private fun finiteLevel(level: Float): Float =
    if (!level.isFinite()) 0f else level.coerceIn(0f, 1f)

/** 满格功率：Model 3 全油门能远超这个数，但声浪到这里就该顶格了。 */
const val FULL_POWER_KW = 120f

/** 基频区间（Hz）。 */
const val IDLE_FREQ = 46f
const val PEAK_FREQ = 168f
