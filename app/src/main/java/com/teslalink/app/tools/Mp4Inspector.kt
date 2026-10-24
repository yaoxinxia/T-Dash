package com.teslalink.app.tools

import java.io.FileDescriptor
import java.io.EOFException
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * MP4 结构体检：只读遍历 atom/box 树，列出全部轨道（handler + 编码 + 分辨率）与
 * 异常容器（udta/meta 等——若特斯拉在录像里藏了遥测轨，会在这里现形）。
 * 只读文件头附近的 moov，不解码媒体数据，瞬间完成。
 */
object Mp4Inspector {

    data class TrackInfo(
        val index: Int,
        val handler: String,
        var codec: String? = null,
        var width: Int? = null,
        var height: Int? = null,
    )

    data class Result(
        val fileSize: Long,
        val topBoxes: List<String>,
        val tracks: List<TrackInfo>,
        val foundUdta: Boolean,
        val metaBoxes: List<String>,
    ) {
        /** 出现 vide/soun 之外的轨道 = 可能藏了遥测数据。 */
        val hasTelemetryTrack: Boolean
            get() = tracks.any { it.handler !in setOf("vide", "soun") }
    }

    fun inspect(fd: FileDescriptor): Result? = try {
        inspectChannel(FileInputStream(fd).channel)
    } catch (_: Exception) {
        null
    }

    fun inspectChannel(ch: FileChannel): Result? = try {
        val fileSize = ch.size()
        ch.position(0)
        val acc = Acc()
        walk(ch, 0L, fileSize, emptyList(), 0, acc)
        Result(fileSize, acc.topBoxes.toList(), acc.tracks, acc.foundUdta, acc.metaBoxes.toList())
    } catch (_: Exception) {
        null
    }

    private class Acc {
        val topBoxes = mutableListOf<String>()
        val tracks = mutableListOf<TrackInfo>()
        var foundUdta = false
        val metaBoxes = mutableListOf<String>()
    }

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "moof", "traf")

    private fun walk(ch: FileChannel, start: Long, end: Long, path: List<String>, depth: Int, acc: Acc) {
        if (depth > 6 || end - start < 8) return
        var pos = start
        while (pos + 8 <= end) {
            ch.position(pos)
            val head = ByteBuffer.allocate(8)
            readFully(ch, head)
            head.flip()
            var size = head.int.toLong() and 0xFFFFFFFFL
            val type = typeName(head)
            if (size == 1L) {
                val big = ByteBuffer.allocate(8)
                readFully(ch, big)
                big.flip()
                size = big.long
                if (size < 16) return
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < 8 || pos + size > end) return

            if (path.isEmpty()) acc.topBoxes += type

            val bodyStart = pos + 8
            val bodyEnd = pos + size
            val childPath = path + type

            when {
                type == "meta" && path.isNotEmpty() -> {
                    // meta 是 full box：版本/标志占 4 字节
                    acc.metaBoxes += childPath.joinToString("/")
                    walk(ch, bodyStart + 4, bodyEnd, childPath, depth + 1, acc)
                }
                type in CONTAINERS -> {
                    if (type == "udta") acc.foundUdta = true
                    walk(ch, bodyStart, bodyEnd, childPath, depth + 1, acc)
                }
                type == "hdlr" && path.lastOrNull() == "mdia" -> {
                    // payload: version/flags(4) + pre_defined(4) + handler_type(4)
                    ch.position(bodyStart + 8)
                    val buf = ByteBuffer.allocate(4)
                    readFully(ch, buf)
                    buf.flip()
                    acc.tracks += TrackInfo(acc.tracks.size, typeName(buf))
                }
                type == "tkhd" -> {
                    val buf = ByteBuffer.allocate(96)
                    ch.position(bodyStart)
                    buf.limit(minOf(96L, bodyEnd - bodyStart).toInt())
                    readFully(ch, buf)
                    buf.flip()
                    val version = buf.get(0).toInt()
                    // 宽高是 16.16 定点：v0 在 payload+76，v1 在 payload+88
                    val offset = if (version == 1) 88 else 76
                    if (buf.limit() >= offset + 8) {
                        buf.position(offset)
                        val width = buf.int ushr 16
                        val height = buf.int ushr 16
                        acc.tracks.lastOrNull()?.let {
                            acc.tracks[acc.tracks.indexOf(it)] =
                                it.copy(width = width, height = height)
                        }
                    }
                }
                type == "stsd" -> {
                    // payload: version/flags(4) + entry_count(4) + entry_size(4) + format(4)
                    val buf = ByteBuffer.allocate(24)
                    ch.position(bodyStart)
                    buf.limit(minOf(24L, bodyEnd - bodyStart).toInt())
                    readFully(ch, buf)
                    buf.flip()
                    if (buf.limit() >= 16) {
                        val codec = typeName(ByteBuffer.wrap(buf.array(), 12, 4))
                        acc.tracks.lastOrNull()?.let {
                            acc.tracks[acc.tracks.indexOf(it)] = it.copy(codec = codec)
                        }
                    }
                }
            }
            pos += size
        }
    }

    private fun readFully(ch: FileChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw EOFException()
        }
    }

    private fun typeName(buf: ByteBuffer): String {
        val b = ByteArray(4)
        buf.get(b)
        return String(b, Charsets.ISO_8859_1).trim()
    }
}
