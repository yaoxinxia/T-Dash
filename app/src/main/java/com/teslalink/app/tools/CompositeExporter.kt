package com.teslalink.app.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 多视角合成导出：N 路同分钟的记录仪分段 → 解码 → GL 按布局合成进同一帧
 * （1 路=全幅；2/3/4 路=2×2，左下角烙墙钟时间码）→ 高码率 H.264 重编码。
 *
 * 画质承诺：码率按画素数给足（≈0.15 bit/px/frame，2×2 约 24 Mbps），前视保持原生分辨率
 * 作主格；单路=全幅原生。remux 那条"零重编码"路线改不了任何像素（所以已随 Mp4Stitcher
 * 删除），这条管线的唯一损失是重编码一代。同步以主机位（views[0]）的帧钟为准，
 * 从机各自"追到不晚于主机时刻的最新帧"。
 */
object CompositeExporter {

    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val OVERLAY_W = 780
    private const val OVERLAY_H = 88
    private const val LABEL_W = 460
    private const val LABEL_H = 64

    fun stitch(
        context: Context,
        views: List<String>,
        clipsByCam: Map<String, List<DashcamClip>>,
        displayName: String,
        onProgress: (Int, Int) -> Unit,
        /** 用户取消入口：编码是阻塞循环，靠这个回调在段/帧边界主动停（finally 会清半成品）。 */
        isCancelled: () -> Boolean = { false },
    ): Uri {
        require(views.isNotEmpty()) { "没有选中任何机位" }
        val master = views.first()
        val masterClips = clipsByCam[master] ?: error("主机位（${DashcamStore.camLabel(master)}）没有分段")
        val segCount = masterClips.size
        require(segCount > 0) { "没有可导出的分段" }
        fun ensureActive() { if (isCancelled()) throw kotlinx.coroutines.CancellationException("导出已取消") }

        // 画布尺寸：主机位第一段的原始分辨率，宽高取偶（编码器要求）。
        val probe = openTrack(context, masterClips[0])
        val fmt0 = probe.format
        val W = fmt0.getInteger(MediaFormat.KEY_WIDTH).let { it - (it % 2) }
        val H = fmt0.getInteger(MediaFormat.KEY_HEIGHT).let { it - (it % 2) }
        probe.release()
        require(W > 0 && H > 0) { "读不到视频尺寸" }
        check(W.toLong() * H <= 8_400_000L) { "${W}x${H} 超出编码器常见上限，导不了这么大" }
        val bitrate = (W.toLong() * H * 30 * 0.15).toLong().coerceIn(16_000_000L, 40_000_000L).toInt()

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TeslaLink")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("相册拒绝了写入请求")
        val pfd = context.contentResolver.openFileDescriptor(outUri, "rw")
            ?: error("拿不到输出文件描述符")

        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var enc: MediaCodec? = null
        var gl: EglRender? = null
        val encoderInfo = MediaCodec.BufferInfo()

        // 帧到达信号专用 Looper：回调里只做计数，绝不碰 GL。
        val frameThread = HandlerThread("dashcam-frames").apply { start() }
        val frameHandler = Handler(frameThread.looper)

        try {
            // 顺序必须是：配编码器 → 拿输入 Surface → 建 EGL → start。
            val encoder = createEncoder(bitrate, W, H)
            enc = encoder
            val inputSurface = encoder.createInputSurface()
            gl = EglRender(inputSurface)
            gl.makeCurrent()
            encoder.start()

            var segBaseUs = 0L
            for (seg in 0 until segCount) {
                ensureActive()
                onProgress(seg, segCount)
                fun clipOf(cam: String): DashcamClip? =
                    clipsByCam[cam]?.getOrNull(seg)?.takeIf { seg < (clipsByCam[cam]?.size ?: 0) }

                val masterClip = requireNotNull(clipOf(master)) { "主机位第 ${seg + 1} 段缺失" }
                val rows = views.map { cam -> cam to clipOf(cam) }

                // 逐路建解码器。某一路失败（文件损坏/拔盘）时，前面几路已经建好的
                // codec/SurfaceTexture/afd 必须就地释放——外层 finally 够不到它们，
                // 漏一次就是一组系统资源，反复失败会累积成 fd 泄漏。
                val decs = ArrayList<SegDecoder?>(rows.size)
                try {
                    rows.forEach { (cam, clip) ->
                        decs += clip?.let { openDecoder(context, it, gl.textureFor(cam), frameHandler) }
                    }
                } catch (e: Throwable) {
                    decs.forEach { d -> runCatching { d?.release() } }
                    decs.clear()
                    throw e
                }
                val masterDec = decs[0] ?: error("主机位解码器没建起来")
                var segDurationUs = masterDec.extractor
                    .getTrackFormat(masterDec.trackIdx)
                    .getLong(MediaFormat.KEY_DURATION, 0L)

                // 预热：等每路都吐出第一帧再开始合成。不等的话开头几帧只有主机有画面
                // ——从机的 SurfaceTexture 还是空的，那几格纯黑（他截图里的"只有前视有画面"）。
                val warmDeadline = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < warmDeadline) {
                    decs.forEach { d -> d?.pumpRenderOne() }
                    if (decs.filterNotNull().all { it.pending.get() > 0 }) break
                    Thread.sleep(2)
                }
                decs.forEach { d -> d?.consumeLatest() }

                try {
                    while (!masterDec.eos) {
                        ensureActive()
                        // 主机一次只推进一帧，推进了就必须合成出去——丢帧的根因就是这里
                        // 以前一次抽干了解码器所有就绪帧却只画一帧。
                        if (!masterDec.pumpRenderOne()) {
                            Thread.sleep(2)
                            continue
                        }
                        val masterPts = masterDec.lastPtsUs
                        // 从机：最多再推几帧，追到"不晚于主机时刻"的那一帧为止。
                        decs.drop(1).forEach { d ->
                            if (d == null) return@forEach
                            var guard = 0
                            while (guard++ < 4 && d.lastPtsUs < masterPts && d.pumpRenderOne()) { }
                        }
                        val ptsUs = segBaseUs + masterPts

                        decs.forEach { d -> d?.consumeLatest() }
                        gl.clear()
                        rows.forEachIndexed { idx, (cam, _) ->
                            val d = decs[idx] ?: return@forEachIndexed
                            val q = quadrantRect(idx, views.size, W, H)
                            gl.drawOes(d.texId, d.stMatrix, fitRect(q, d.srcW, d.srcH))
                            // 每格左上角标机位名：成片里得能认出哪格是哪个视角。
                            gl.drawTextured(
                                gl.labelTexture(DashcamStore.camLabel(cam)),
                                q[0] + 10, q[1] + q[3] - LABEL_H - 10, LABEL_W, LABEL_H,
                            )
                        }
                        val wallMs = masterClip.startEpochMs + masterPts / 1000
                        val stampTex = gl.maybeDraw(
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(wallMs)),
                        )
                        gl.drawTextured(stampTex, 12, 12, OVERLAY_W, OVERLAY_H)
                        gl.swap(ptsUs)

                        drainEncoder(encoder, muxer, { muxerStarted = it == 1 }, encoderInfo, endOfStream = false)
                    }
                    if (seg == segCount - 1) {
                        drainEncoder(encoder, muxer, { muxerStarted = it == 1 }, encoderInfo, endOfStream = true)
                    }
                } finally {
                    decs.forEach { d -> runCatching { d?.release() } }
                }
                if (segDurationUs <= 0) segDurationUs = DashcamEvent.SEGMENT_MS * 1000
                segBaseUs += segDurationUs
            }
            try {
                muxer.stop()
            } catch (e: Exception) {
                runCatching { context.contentResolver.delete(outUri, null, null) }
                throw IllegalStateException("合成收尾失败（可能没有有效帧）", e)
            }
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(outUri, null, null) }
            throw e
        } finally {
            runCatching { enc?.release() }
            runCatching { gl?.release() }
            runCatching { frameThread.quitSafely() }
            runCatching { muxer.release() }
            runCatching { pfd.close() }
        }

        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        context.contentResolver.update(outUri, done, null, null)
        return outUri
    }

    /** 2×2 的格子区域（GL 坐标，y 向上）：左上/右上/左下/右下；单路=全幅。 */
    private fun quadrantRect(idx: Int, count: Int, W: Int, H: Int): IntArray =
        if (count == 1) intArrayOf(0, 0, W, H)
        else {
            val cw = W / 2
            val ch = H / 2
            intArrayOf(if (idx % 2 == 0) 0 else cw, if (idx < 2) H - ch else 0, cw, ch)
        }

    /** 格子里按源宽高比等比缩放、居中；装不下的方向留黑，绝不拉伸。 */
    private fun fitRect(q: IntArray, srcW: Int, srcH: Int): IntArray {
        val scale = minOf(q[2].toFloat() / srcW, q[3].toFloat() / srcH)
        val dw = ((srcW * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val dh = ((srcH * scale).toInt() / 2 * 2).coerceAtLeast(2)
        return intArrayOf(q[0] + (q[2] - dw) / 2, q[1] + (q[3] - dh) / 2, dw, dh)
    }

    /** 编码器：先按目标码率配，编码器撑不住就逐级降码率重试（很少走到）。 */
    private fun createEncoder(bitrateIn: Int, W: Int, H: Int): MediaCodec {
        var br = bitrateIn
        var last: Exception? = null
        repeat(3) {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, br)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            try {
                return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            } catch (e: Exception) {
                last = e
                br = br * 2 / 3
            }
        }
        throw IllegalStateException("编码器开不出来（${W}x${H}）：${last?.message}", last)
    }

    /** 单视频轨。 */
    private class Track(
        val index: Int,
        val format: MediaFormat,
        val extractor: MediaExtractor,
        val afd: android.content.res.AssetFileDescriptor,
    ) {
        fun release() {
            runCatching { extractor.release() }
            runCatching { afd.close() }
        }
    }

    private fun openTrack(context: Context, clip: DashcamClip): Track {
        val afd = context.contentResolver.openAssetFileDescriptor(clip.uri, "r")
            ?: error("打不开分段：${DashcamStore.camLabel(clip.camera)} @${clip.startEpochMs}")
        val ex = MediaExtractor()
        try {
            if (afd.getDeclaredLength() < 0) ex.setDataSource(afd.fileDescriptor)
            else ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.getDeclaredLength())
        } catch (e: Exception) {
            runCatching { ex.release() }
            runCatching { afd.close() }
            throw IllegalStateException("系统读不出这个分段：${DashcamStore.camLabel(clip.camera)} @${clip.startEpochMs}", e)
        }
        var trackIdx = -1
        var format: MediaFormat? = null
        for (t in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(t)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                trackIdx = t
                format = f
                break
            }
        }
        if (trackIdx < 0 || format == null) {
            ex.release()
            afd.close()
            throw IllegalStateException("分段里没有视频轨：${DashcamStore.camLabel(clip.camera)} @${clip.startEpochMs}")
        }
        ex.selectTrack(trackIdx)
        return Track(trackIdx, format!!, ex, afd)
    }

    private fun drainEncoder(
        enc: MediaCodec,
        muxer: MediaMuxer,
        onStarted: (Int) -> Unit,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
    ) {
        if (endOfStream) enc.signalEndOfInputStream()
        while (true) {
            val i = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxer.addTrack(enc.outputFormat)
                    muxer.start()
                    onStarted(1)
                }
                i >= 0 -> {
                    val buf = enc.getOutputBuffer(i) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(0, buf, info)
                    }
                    enc.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** 单段单机位解码器：同步模式，解码帧直接渲染进 SurfaceTexture。 */
    private class SegDecoder(
        val codec: MediaCodec,
        val extractor: MediaExtractor,
        val afd: android.content.res.AssetFileDescriptor,
        val surface: android.view.Surface,
        val st: SurfaceTexture,
        val texId: Int,
        val trackIdx: Int,
        val srcW: Int,
        val srcH: Int,
        val pending: AtomicLong,
    ) {
        var eos = false
        var inputDone = false
        var lastPtsUs = 0L
        val stMatrix = FloatArray(16)
        private val info = MediaCodec.BufferInfo()

        /**
         * 渲染**恰好一帧**就返回（true = 这一拍渲染了新帧）。
         * 原来是一次把解码器里所有就绪的帧全渲染进纹理，外层却只合成一帧——
         * 中间那些帧被抽干丢掉，单路导出因此明显掉帧（他看出的那个问题）。
         */
        fun pumpRenderOne(): Boolean {
            if (eos) return false
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(0L)
                    if (inIdx >= 0) {
                        val bb = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(bb, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 0L)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIdx >= 0 -> {
                        val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0) {
                            codec.releaseOutputBuffer(outIdx, true)
                            lastPtsUs = info.presentationTimeUs
                            if (isEos) eos = true
                            return true
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (isEos) {
                            eos = true
                            return false
                        }
                    }
                }
            }
        }

        /** 只在 GL 线程调用：消费掉所有已到帧，纹理停留在最新一帧并取它的变换矩阵。 */
        fun consumeLatest() {
            var n = pending.getAndSet(0)
            while (n-- > 0) st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        }

        fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            runCatching { st.release() }
            runCatching { extractor.release() }
            runCatching { afd.close() }
        }
    }

    private fun openDecoder(
        context: Context,
        clip: DashcamClip,
        texId: Int,
        frameHandler: Handler,
    ): SegDecoder {
        val track = openTrack(context, clip)
        val format = track.format
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        val pending = AtomicLong(0)
        val st = SurfaceTexture(texId).apply {
            setOnFrameAvailableListener({ pending.incrementAndGet() }, frameHandler)
        }
        val surface = android.view.Surface(st)
        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, surface, null, 0)
                start()
            }
        } catch (e: Exception) {
            runCatching { surface.release() }
            runCatching { st.release() }
            track.release()
            throw IllegalStateException(
                "解码器开不出来（${DashcamStore.camLabel(clip.camera)} ${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}）：" +
                    "同时合成多路时这台手机可能撑不住，少选一两路再试",
                e,
            )
        }
        return SegDecoder(
            codec = codec,
            extractor = track.extractor,
            afd = track.afd,
            surface = surface,
            st = st,
            texId = texId,
            trackIdx = track.index,
            srcW = format.getInteger(MediaFormat.KEY_WIDTH),
            srcH = format.getInteger(MediaFormat.KEY_HEIGHT),
            pending = pending,
        )
    }

    // ---------- GL：外置 OES 纹理合成 + 位图时间码 ----------

    private class EglRender(outSurface: android.view.Surface) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY).also {
            check(EGL14.eglInitialize(it, IntArray(1), 0, IntArray(1), 0)) { "EGL 初始化失败" }
        }
        private val config: EGLConfig
        private val context: EGLContext
        private val eglSurface: EGLSurface
        private var programOes = 0
        private var program2d = 0
        private val quad: FloatBuffer
        private val quadFlipped: FloatBuffer
        private val overlayTex: Int
        private val overlayBmp = Bitmap.createBitmap(OVERLAY_W, OVERLAY_H, Bitmap.Config.ARGB_8888)
        private val overlayCanvas = Canvas(overlayBmp)
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 56f
            isAntiAlias = true
            setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
        }
        private val bgPaint = Paint().apply { color = 0x66000000 }
        private val labelPaint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            isAntiAlias = true
            setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
        }
        private val labelTexes = HashMap<String, Int>()
        private val identityMat = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        private var lastSecond = ""
        private val camTextures = HashMap<String, Int>()

        init {
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
                "EGL 找不到可录制配置"
            }
            config = configs[0]!!
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context !== EGL14.EGL_NO_CONTEXT) { "EGL 上下文创建失败" }
            eglSurface = EGL14.eglCreateWindowSurface(display, config, outSurface, intArrayOf(EGL14.EGL_NONE), 0)
            makeCurrent()
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            programOes = buildProgram(VERT_SRC, FRAG_OES)
            program2d = buildProgram(VERT_SRC, FRAG_2D)
            // SurfaceTexture 的标准姿势：位置四边形 + ST 矩阵管方向。
            quad = floatBufferOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            )
            // GLUtils 上传的位图行序上下颠倒：2D 纹理要用 v 翻转的坐标画才正立。
            quadFlipped = floatBufferOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f,
            )
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            overlayTex = tex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun textureFor(cam: String): Int {
            camTextures[cam]?.let { return it }
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            camTextures[cam] = tex[0]
            return tex[0]
        }

        fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "EGL makeCurrent 失败" }
        }

        fun clear() {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        fun drawOes(texId: Int, stMatrix: FloatArray, rect: IntArray) {
            GLES20.glViewport(rect[0], rect[1], rect[2], rect[3])
            GLES20.glUseProgram(programOes)
            bindQuad(programOes, quad)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programOes, "uTex"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programOes, "uSTMatrix"), 1, false, stMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        /** 时间码贴片：每秒才重绘一次位图。返回纹理 id 给 drawTextured 用。 */
        fun maybeDraw(text: String): Int {
            if (text != lastSecond) {
                lastSecond = text
                overlayCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                overlayCanvas.drawRoundRect(0f, 0f, OVERLAY_W.toFloat(), OVERLAY_H.toFloat(), 12f, 12f, bgPaint)
                overlayCanvas.drawText(text, 20f, 62f, textPaint)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBmp, 0)
            }
            return overlayTex
        }

        /** 机位名标签：文字固定，做个一次位图就复用。 */
        fun labelTexture(text: String): Int {
            labelTexes[text]?.let { return it }
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val bmp = Bitmap.createBitmap(LABEL_W, LABEL_H, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.drawRoundRect(0f, 0f, LABEL_W.toFloat(), LABEL_H.toFloat(), 10f, 10f, bgPaint)
            cv.drawText(text, 16f, 45f, labelPaint)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            labelTexes[text] = tex[0]
            return tex[0]
        }

        /**
         * 画一块 2D 位图贴片（时间码 / 机位名）。
         * 顶点着色器是两个程序共用的，uSTMatrix 不设就是全零矩阵 → 整块 quad 只采样 (0,0)
         * 那个透明像素，时间码"画了但看不见"（第一版导出没有画面时间就是这个）。
         */
        fun drawTextured(texId: Int, x: Int, y: Int, w: Int, h: Int) {
            GLES20.glViewport(x, y, w, h)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glUseProgram(program2d)
            bindQuad(program2d, quadFlipped)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uTex"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program2d, "uSTMatrix"), 1, false, identityMat, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        fun swap(ptsUs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsUs * 1000)
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers 失败" }
        }

        fun release() {
            runCatching { EGL14.eglDestroySurface(display, eglSurface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglReleaseThread() }
            runCatching { EGL14.eglTerminate(display) }
        }

        private fun bindQuad(program: Int, buf: FloatBuffer) {
            buf.position(0)
            val posLoc = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
            GLES20.glEnableVertexAttribArray(posLoc)
            buf.position(2)
            val texLoc = GLES20.glGetAttribLocation(program, "aTex")
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
            GLES20.glEnableVertexAttribArray(texLoc)
        }

        private fun floatBufferOf(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v); position(0)
            }

        private fun buildProgram(vert: String, frag: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vert))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, frag))
            GLES20.glLinkProgram(p)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { "GL 链接失败：${GLES20.glGetProgramInfoLog(p)}" }
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "GL 编译失败：${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
    }

    private const val VERT_SRC = """
        attribute vec2 aPos;
        attribute vec2 aTex;
        uniform mat4 uSTMatrix;
        varying vec2 vTex;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            vTex = (uSTMatrix * vec4(aTex, 0.0, 1.0)).xy;
        }
    """

    private const val FRAG_OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTex;
        uniform samplerExternalOES uTex;
        void main() { gl_FragColor = texture2D(uTex, vTex); }
    """

    private const val FRAG_2D = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        void main() { gl_FragColor = texture2D(uTex, vTex); }
    """
}
