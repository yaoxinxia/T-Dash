package com.teslalink.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.teslalink.app.store.AccidentProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一键导出取证清单 PDF（框架 PdfDocument，不引第三方库）。
 *
 * A4 竖版，文字用 Paint 手绘、中文按字宽折行。照片一律**缩放到格子里**画，
 * 页脚带页码、续页带小页眉；某张读不出来要画占位并说明，不能悄悄少一张。
 */
object EvidencePdf {

    private const val W = 595f          // A4 pt
    private const val H = 842f
    private const val M = 42f           // 页边距
    private const val CW = W - 2 * M    // 版心宽
    private const val FOOT = 26f        // 页脚占位

    class Result(val file: File, val pages: Int, val photos: Int, val failed: Int)

    fun build(
        context: Context,
        profile: AccidentProfile,
        timeText: String,
        placeText: String,
        checklist: List<Pair<String, Boolean>>,
        photos: List<Pair<String, List<Uri>>>,
        idPhotos: List<Pair<String, Uri>>,
    ): Result {
        val doc = PdfDocument()
        var pageNo = 1
        var pageInfo = PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNo).create()
        var page = doc.startPage(pageInfo)
        var cv: Canvas = page.canvas
        var y = M
        var okPhotos = 0
        var badPhotos = 0

        val titleP = paint(20f, Color.rgb(10, 14, 20), bold = true)
        val h2P = paint(12.5f, Color.rgb(10, 14, 20), bold = true)
        val bodyP = paint(10.5f, Color.rgb(26, 30, 38))
        val labelP = paint(9.5f, Color.rgb(118, 126, 138))
        val footP = paint(8.5f, Color.rgb(140, 148, 160))
        val okP = paint(10.5f, Color.rgb(16, 122, 68), bold = true)
        val noP = paint(10.5f, Color.rgb(150, 156, 166))
        val fillP = paint(10f, Color.rgb(246, 247, 249)).apply { style = Paint.Style.FILL }
        val lineP = paint(10f, Color.rgb(206, 212, 220)).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f }
        val imgP = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

        fun footer() {
            cv.drawLine(M, H - M - 4f, W - M, H - M - 4f, lineP)
            cv.drawText("交通事故取证清单 · 生成于 $stamp", M, H - M + 10f, footP)
            val right = "第 $pageNo 页"
            cv.drawText(right, W - M - footP.measureText(right), H - M + 10f, footP)
        }

        fun next() {
            footer()
            doc.finishPage(page)
            pageNo++
            pageInfo = PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNo).create()
            page = doc.startPage(pageInfo)
            cv = page.canvas
            cv.drawText("交通事故取证清单（续）", M, M + 4f, h2P)
            cv.drawLine(M, M + 12f, W - M, M + 12f, lineP)
            y = M + 30f
        }

        /** 画之前先量够不够，不够先换页——绝不出界。 */
        fun room(h: Float) {
            if (y + h > H - M - FOOT) next()
        }

        /** 中文没有空格可断，按实测字宽逐字折行。 */
        fun wrap(p: Paint, s: String, x: Float, maxW: Float, lh: Float) {
            var line = StringBuilder()
            fun flush() {
                if (line.isEmpty()) return
                room(lh)
                cv.drawText(line.toString(), x, y, p)
                y += lh
                line = StringBuilder()
            }
            s.forEach { ch ->
                if (ch == '\n') {
                    flush()
                    return@forEach
                }
                if (p.measureText("$line$ch") > maxW && line.isNotEmpty()) flush()
                line = StringBuilder("$line$ch")
            }
            flush()
        }

        fun row(label: String, value: String) {
            val lw = 64f
            room(15f)
            cv.drawText(label, M, y, labelP)
            wrap(bodyP, value, M + lw, CW - lw, 15f)
        }

        /** 把照片等比缩放进格子里；返回 false 表示这张读不出来。 */
        fun cell(uri: Uri?, x: Float, top: Float, w: Float, h: Float, caption: String): Boolean {
            cv.drawRect(x, top, x + w, top + h, fillP)
            val bmp = uri?.let { decode(context, it) }
            if (bmp == null) {
                cv.drawText("这张读不出来", x + 8f, top + h / 2f, labelP)
                cv.drawRect(x, top, x + w, top + h, lineP)
                cv.drawText(caption, x, top + h + 11f, labelP)
                return false
            }
            val scale = minOf((w - 6f) / bmp.width, (h - 6f) / bmp.height)
            val bw = bmp.width * scale
            val bh = bmp.height * scale
            cv.drawBitmap(
                bmp, null,
                RectF(x + (w - bw) / 2f, top + (h - bh) / 2f, x + (w + bw) / 2f, top + (h + bh) / 2f),
                imgP,
            )
            bmp.recycle()
            cv.drawRect(x, top, x + w, top + h, lineP)
            cv.drawText(caption, x, top + h + 11f, labelP)
            return true
        }

        // ---- 标题 + 基本信息 ----
        wrap(titleP, "交通事故取证清单", M, CW, 26f)
        y += 2f
        cv.drawLine(M, y, W - M, y, lineP)
        y += 16f
        row("生成时间", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        row("事故时间", timeText)
        row("事故位置", placeText)
        row("报案人", profile.name.ifBlank { "（未填）" })
        row("手机 / 车牌", listOf(profile.phone.ifBlank { "未填" }, profile.plate.ifBlank { "未填" }).joinToString(" · "))
        if (profile.vin.isNotBlank()) row("车架号", profile.vin)
        if (profile.insurer.isNotBlank() || profile.policyNo.isNotBlank()) {
            row("保险", "${profile.insurer.ifBlank { "未填" }} · 保单号 ${profile.policyNo.ifBlank { "未填" }}")
        }
        y += 8f

        // ---- 取证清单（两列）----
        room(40f)
        cv.drawText("取证清单", M, y, h2P)
        val done = checklist.count { it.second }
        val sum = "已拍 $done / ${checklist.size} 项"
        cv.drawText(sum, W - M - labelP.measureText(sum), y, labelP)
        y += 16f
        checklist.chunked(2).forEach { pair ->
            room(15f)
            pair.forEachIndexed { i, (label, ok) ->
                val x = M + i * (CW / 2f)
                cv.drawText(if (ok) "✓" else "○", x, y, if (ok) okP else noP)
                cv.drawText(label, x + 15f, y, bodyP)
            }
            y += 15f
        }
        y += 10f

        // ---- 证件照片 ----
        val ids = idPhotos.take(2)
        if (ids.isNotEmpty()) {
            val idH = 120f
            room(idH + 34f)
            cv.drawText("证件照片", M, y, h2P)
            y += 14f
            val cellW = (CW - 10f) / 2f
            ids.forEachIndexed { i, (label, uri) ->
                if (cell(uri, M + i * (cellW + 10f), y, cellW, idH, label)) okPhotos++ else badPhotos++
            }
            y += idH + 22f
            room(20f)
        }

        // ---- 现场照片（每组一个小标题，两列网格）----
        room(30f)
        cv.drawText("现场照片", M, y, h2P)
        y += 16f
        val cellW = (CW - 10f) / 2f
        val cellH = cellW * 0.68f
        val rowH = cellH + 20f
        photos.forEach { (label, uris) ->
            if (uris.isEmpty()) return@forEach
            room(rowH + 18f)
            wrap(labelP, "$label（${uris.size} 张）", M, CW, 13f)
            y += 3f
            uris.chunked(2).forEachIndexed { r, rowUris ->
                room(rowH)
                rowUris.forEachIndexed { i, uri ->
                    val n = uris.indexOf(uri) + 1
                    if (cell(uri, M + i * (cellW + 10f), y, cellW, cellH, "第 $n 张")) okPhotos++ else badPhotos++
                }
                y += rowH
            }
            y += 6f
        }

        footer()
        doc.finishPage(page)
        val out = File(context.cacheDir, "exports").apply { mkdirs() }
        val sfx = SimpleDateFormat("MMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(out, "取证清单_$sfx.pdf")
        runCatching { file.outputStream().use { doc.writeTo(it) } }.onFailure {
            doc.close()
            throw it
        }
        doc.close()
        return Result(file, pageNo, okPhotos, badPhotos)
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint().apply {
        textSize = size
        this.color = color
        isFakeBoldText = bold
        isAntiAlias = true
    }

    /**
     * 解码到够画就行：格子里最大 250pt 宽，560px 已接近 160dpi。
     * 原图整尺寸塞进去，一份 7 张的清单能到 28MB，微信发不动。
     */
    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 560) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /** 另存一份到系统「下载」目录（现场发完还能在文件管理里找到）。失败返回 null，不影响分享。 */
    fun saveToDownloads(context: Context, file: File): Uri? = runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        uri
    }.getOrNull()

    /** 弹系统分享面板（微信/邮件/网盘都能选）。FileProvider 给一次性读授权。 */
    fun share(context: Context, file: File, authority: String): Boolean = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            android.content.Intent.createChooser(intent, "发送取证清单").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
