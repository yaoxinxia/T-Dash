package com.teslalink.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream

// 官方与社区口径一致：正方形 PNG，边长 512~1024，单张 ≤1 MB，最多 10 张。
private const val MIN_PX = 512
private const val MAX_PX = 1024
private const val MAX_BYTES = 1_000_000L

/**
 * 车机皮肤（喷漆车间 / Wraps）U 盘管理。
 *
 * 实测 + 官方口径一致：U 盘**根目录**建 `Wraps`，里面放**正方形 PNG**，边长 512~1024，
 * 单张 ≤1 MB，最多 10 张；车机 玩具箱 → 喷漆车间 → Wraps 页签里选。
 * 他盘里现有的三张都是 1024x1024 RGBA PNG（187~800 KB），文件名是下载时带的编号，车机不改名。
 */
/** 车机能用 = null；否则是不合规原因（界面直接显示，别让用户猜）。 */
data class WrapImage(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val modifiedMs: Long,
) {
    val problem: String?
        get() = when {
            width <= 0 || height <= 0 -> "读不出尺寸"
            width != height -> "不是正方形（${width}×$height）"
            width < MIN_PX -> "边长 $width 低于 $MIN_PX"
            width > MAX_PX -> "边长 $width 超过 $MAX_PX"
            sizeBytes > MAX_BYTES -> "体积 ${"%.1f".format(sizeBytes / 1024f / 1024f)} MB 超过 1 MB"
            else -> null
        }
}

/** 导入结果：写没写进去、最后落到哪张图、为什么不行，全部要说出来。 */
data class WrapImport(val name: String, val ok: Boolean, val detail: String)

object WrapStore {

    const val DIR = "Wraps"
    const val MAX_COUNT = 10

    /** 选中的树 → Wraps 目录（没有则返回 null，界面给出"创建"入口）。 */
    fun wrapsDir(context: Context, treeUri: Uri): DocumentFile? {
        val picked = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (picked.name.equals(DIR, ignoreCase = true)) return picked
        return picked.listFiles().firstOrNull { it.isDirectory && it.name.equals(DIR, ignoreCase = true) }
    }

    fun createWrapsDir(context: Context, treeUri: Uri): DocumentFile? {
        val picked = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (picked.name.equals(DIR, ignoreCase = true)) return picked
        wrapsDir(context, treeUri)?.let { return it }
        return runCatching { picked.createDirectory(DIR) }.getOrNull()
    }

    fun list(context: Context, dir: DocumentFile): List<WrapImage> =
        dir.listFiles()
            .filter { it.isFile && it.name?.endsWith(".png", ignoreCase = true) == true }
            .map { f ->
                val (w, h) = readBounds(context, f.uri)
                WrapImage(f.name ?: "?", f.uri, runCatching { f.length() }.getOrDefault(0), w, h,
                    runCatching { f.lastModified() }.getOrDefault(0))
            }
            .sortedByDescending { it.modifiedMs }

    /** 只解头，不解像素，列 10 张也不卡。 */
    private fun readBounds(context: Context, uri: Uri): Pair<Int, Int> = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        opts.outWidth to opts.outHeight
    } catch (_: Exception) {
        0 to 0
    }

    /**
     * 删除失败必须区分"为什么"：授权失效（重新授权）和盘没了（插好盘）是两种处置，
     * 一律说"这个盘不让写"会把他往错的方向带。返回 null = 删除成功，否则=给界面的原因。
     */
    fun deleteImage(context: Context, img: WrapImage): String? = try {
        if (DocumentFile.fromSingleUri(context, img.uri)?.delete() == true) null
        else "SAF 报告删除没生效（文件可能已不在）"
    } catch (e: SecurityException) {
        com.teslalink.app.debug.DebugLog.error("wraps", "删除被拒（授权失效）：${e.message}")
        describeWriteFailure(e)
    } catch (e: java.io.FileNotFoundException) {
        com.teslalink.app.debug.DebugLog.error("wraps", "删除目标不存在（盘被拔？）：${img.name}")
        null   // 文件已经不在了，视为删除成功
    } catch (e: Exception) {
        describeWriteFailure(e)
    }

    /** 把异常翻译成能自助的处置提示（授权失效 / 盘没了 / 其它）。 */
    fun describeWriteFailure(e: Throwable): String = when (e) {
        is SecurityException ->
            "写授权失效了——点「换 U 盘 / 重新授权」重新选一次 U 盘根目录"
        is java.io.FileNotFoundException ->
            "U 盘读不到了（可能被拔出）——插好再试"
        else ->
            "${e.javaClass.simpleName} ${e.message ?: ""}——这个文件系统可能不让写（exFAT 一般可以），重试或换 U 盘"
    }

    /**
     * 手机里的任意图 → 合规皮肤：居中裁正方形，按 1024/896/768/640/512 逐档压 PNG，
     * 取第一个 ≤1 MB 的档位写进 Wraps（车机最低只收 512，所以不放大、不越界）。
     */
    fun import(context: Context, dir: DocumentFile, src: Uri): WrapImport {
        val name = displayNameOf(context, src)
        val square = loadCenterSquare(context, src)
            ?: return WrapImport(name, false, "解不开这张图（格式不支持或文件已删）")
        val side = square.width
        if (side < MIN_PX) {
            square.recycle()
            return WrapImport(name, false, "原图最短边只有 $side px，低于车机要求的 $MIN_PX，放大也没意义")
        }
        var payload: ByteArray? = null
        var usedPx = 0
        var lastSize = 0
        for (px in intArrayOf(MAX_PX, 896, 768, 640, MIN_PX)) {
            if (px > side) continue
            val bmp = if (side == px) square else Bitmap.createScaledBitmap(square, px, px, true)
            val bytes = pngOf(bmp)
            lastSize = bytes.size
            if (bytes.size <= MAX_BYTES) {
                payload = bytes
                usedPx = px
                if (bmp !== square) bmp.recycle()
                break
            }
            if (bmp !== square) bmp.recycle()
        }
        square.recycle()
        val data = payload
            ?: return WrapImport(
                name, false,
                "压到 $MIN_PX×$MIN_PX 还有 ${"%.1f".format(lastSize / 1024f / 1024f)} MB——这图颜色太杂（照片级细节），" +
                    "换一张扁平一点的或先手动缩到 512 再来"
            )
        val target = uniqueName(dir, name)
        val doc: DocumentFile = try {
            dir.createFile("image/png", target)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return WrapImport(name, false, describeWriteFailure(e))
        } ?: return WrapImport(
            name, false,
            "U 盘没让创建文件（SAF 返回空）——" + describeWriteFailure(java.io.IOException("createFile=null")),
        )
        return try {
            context.contentResolver.openOutputStream(doc.uri)?.use { it.write(data) }
                ?: error("打不开输出流")
            WrapImport(target, true, "已写入 ${usedPx}×${usedPx} · ${"%.0f".format(data.size / 1024f)} KB")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { doc.delete() }   // 不留半成品
            WrapImport(target, false, "写入失败：" + describeWriteFailure(e))
        }
    }

    private fun pngOf(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** 居中裁成正方形（先粗略降采样，避免 4000px 原图直接进内存）。 */
    private fun loadCenterSquare(context: Context, src: Uri): Bitmap? = try {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, probe) }
        val w = probe.outWidth
        val h = probe.outHeight
        val bmp = if (w > 0 && h > 0) {
            var sample = 1
            while (minOf(w, h) / (sample * 2) >= MAX_PX) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } else null
        bmp?.let {
            val s = minOf(it.width, it.height)
            val sq = Bitmap.createBitmap(it, (it.width - s) / 2, (it.height - s) / 2, s, s)
            if (sq !== it) it.recycle()
            sq
        }
    } catch (_: Exception) {
        null
    }

    private fun displayNameOf(context: Context, uri: Uri): String {
        val raw = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull()
        val stem = (raw ?: "skin").substringBeforeLast('.')
            // 保留中文等任意文字的字母，只把路径分隔符/空格/标点换成短横线：名字是他认图的唯一线索。
            .replace(Regex("[^\\p{L}\\p{N}_-]"), "-").replace(Regex("-{2,}"), "-").trim('-')
        return (if (stem.isBlank()) "skin" else stem.take(28)) + ".png"
    }

    private fun uniqueName(dir: DocumentFile, name: String): String {
        // 列不出目录（拔盘/授权过期会抛）时不必失败：按原名走，后面 createFile 会给出真原因。
        val taken = runCatching { dir.listFiles().mapNotNull { it.name?.lowercase() }.toHashSet() }
            .getOrDefault(hashSetOf())
        if (name.lowercase() !in taken) return name
        val stem = name.substringBeforeLast('.')
        for (i in 2..99) {
            val cand = "$stem-$i.png"
            if (cand.lowercase() !in taken) return cand
        }
        return "$stem-${System.currentTimeMillis()}.png"
    }
}
