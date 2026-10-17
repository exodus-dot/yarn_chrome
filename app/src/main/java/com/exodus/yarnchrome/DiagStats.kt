package com.exodus.yarnchrome

import android.os.SystemClock
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 汇总注入脚本每秒上报的性能指标：生成浮层文字、写 CSV、判断是否需要按延迟追帧。
 * 只处理数值指标，不记录网址、域名和页面内容。
 * logDir 为 null 时只做追帧判定，不写日志。
 */
class DiagStats(logDir: File?) {

    private class Sample(
        val top: Boolean,
        val visible: Boolean,
        val windowMs: Int,
        val ws: Int,
        val es: Int,
        val msgs: Double,
        val bytes: Long,
        val handlerMs: Double,
        val handlerMax: Double,
        val fps: Double,
        val frameGapMax: Double,
        val longTasks: Double,
        val longTaskMs: Double,
        val loopLagMax: Double,
        val tsLag: Double?,
        val heapMb: Double?,
    )

    private class Entry(val label: String) {
        var lastSeq = 0
        var lastSeen = 0L
        var minLag: Double? = null       // 本文档出现过的最小载荷延迟，作为"打开时"的基准，抵消两端时钟差
        var overStreak = 0
        var last: Sample? = null
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var frameCounter = 0
    private var navigatedAt = SystemClock.elapsedRealtime()
    private val log: BufferedWriter?
    val logPath: String?

    init {
        var writer: BufferedWriter? = null
        var path: String? = null
        if (logDir != null) {
            try {
                logDir.mkdirs()
                val name = "diag-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".csv"
                val file = File(logDir, name)
                val out = FileOutputStream(file)
                // 带 BOM，Excel 直接打开中文表头不乱码
                out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
                writer.write(HEADER)
                writer.newLine()
                writer.flush()
                path = file.absolutePath
            } catch (e: IOException) {
                writer = null
            }
        }
        log = writer
        logPath = path
    }

    /** 新导航开始：旧文档的统计作废，并在日志里记一笔，方便和刷新时间点对照。 */
    fun markNavigation(what: String) {
        entries.clear()
        frameCounter = 0
        navigatedAt = SystemClock.elapsedRealtime()
        writeLine(listOf(time(), "", what) + List(16) { "" })
    }

    /** 本文档开始后过了多久，用来判断"一直没识别到时间戳"。 */
    fun sinceNavigationMs(): Long = SystemClock.elapsedRealtime() - navigatedAt

    /** 当前文档是否在任何来源里识别到了载荷时间戳。 */
    fun hasTimestamp(): Boolean = entries.values.any { it.minLag != null }

    /** 当前文档是否有打开的 WebSocket / EventSource 连接。 */
    fun hasConnection(): Boolean = entries.values.any { e -> e.last.let { it != null && it.ws + it.es > 0 } }

    /**
     * 处理一条上报。catchUpMs > 0 时检查延迟：比基准多落后超过阈值、且连续 CATCH_UP_STREAK 次，返回 true 表示该追帧了。
     */
    fun accept(json: String?, catchUpMs: Long): Boolean {
        if (json.isNullOrEmpty() || !json.contains("\"yarnDiag\"")) return false
        val r = try {
            JSONObject(json)
        } catch (ex: JSONException) {
            return false
        }
        if (!r.has("yarnDiag")) return false
        val id = r.optString("id")
        val seq = r.optInt("seq")
        val s = parse(r) ?: return false
        return record(id, seq, s, catchUpMs)
    }

    private fun parse(r: JSONObject): Sample? =
        try {
            Sample(
                top = r.getBoolean("top"),
                visible = r.getBoolean("visible"),
                windowMs = r.getInt("windowMs"),
                ws = r.getInt("ws"),
                es = r.getInt("es"),
                msgs = r.getDouble("msgs"),
                bytes = r.getLong("bytes"),
                handlerMs = r.getDouble("handlerMs"),
                handlerMax = r.getDouble("handlerMax"),
                fps = r.getDouble("fps"),
                frameGapMax = r.getDouble("frameGapMax"),
                longTasks = r.getDouble("longTasks"),
                longTaskMs = r.getDouble("longTaskMs"),
                loopLagMax = r.getDouble("loopLagMax"),
                tsLag = optDouble(r, "tsLag"),
                heapMb = optDouble(r, "heapMb"),
            )
        } catch (ex: JSONException) {
            null
        }

    private fun record(id: String, seq: Int, s: Sample, catchUpMs: Long): Boolean {
        var e = entries[id]
        if (e == null) {
            e = Entry(if (s.top) "主页面" else "子框架${++frameCounter}")
            entries[id] = e
        } else if (seq <= e.lastSeq) {
            return false
        }
        e.lastSeq = seq
        e.lastSeen = SystemClock.elapsedRealtime()
        e.last = s

        var drift: Double? = null
        if (s.tsLag != null) {
            val min = e.minLag
            if (min == null || s.tsLag < min) e.minLag = s.tsLag
            drift = s.tsLag - e.minLag!!
        }
        writeLine(
            listOf(
                time(), e.label, "",
                s.windowMs.toString(), s.ws.toString(), s.es.toString(), f(s.msgs), s.bytes.toString(),
                f(s.handlerMs), f(s.handlerMax), f(s.fps), f(s.frameGapMax),
                f(s.longTasks), f(s.longTaskMs), f(s.loopLagMax),
                f(s.tsLag), f(drift), f(s.heapMb), if (s.visible) "1" else "0",
            ),
        )

        if (catchUpMs <= 0 || drift == null || !s.visible) {
            e.overStreak = 0
            return false
        }
        e.overStreak = if (drift > catchUpMs) e.overStreak + 1 else 0
        if (e.overStreak < CATCH_UP_STREAK) return false
        e.overStreak = 0
        return true
    }

    /** 浮层文字。只显示最近仍在上报、且是主页面或有连接、有消息的来源。 */
    fun render(): String {
        val now = SystemClock.elapsedRealtime()
        entries.entries.removeAll { now - it.value.lastSeen > STALE_MS }
        val sb = StringBuilder()
        for (e in entries.values) {
            val s = e.last ?: continue
            if (!s.top && s.ws + s.es == 0 && s.msgs == 0.0) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append('[').append(e.label).append(']')
            if (!s.visible) sb.append(" 页面不可见")
            // 上报定时器被推迟，说明主线程已经被占满
            if (s.windowMs > SATURATED_WINDOW_MS) sb.append(" 主线程饱和，上报周期 ").append(f(s.windowMs / 1000.0)).append(" s")
            sb.append('\n')
            sb.append("连接 WS ${s.ws} / SSE ${s.es}  消息 ${f(s.msgs)}/秒  ${bytes(s.bytes)}/秒\n")
            sb.append("回调 ${f(s.handlerMs)} ms/秒  单条最长 ${f(s.handlerMax)} ms\n")
            sb.append("帧率 ${f(s.fps)}  最长帧间隔 ${f(s.frameGapMax)} ms\n")
            sb.append("长任务 ${f(s.longTasks)} 个/秒 ${f(s.longTaskMs)} ms/秒  事件循环延迟 ${f(s.loopLagMax)} ms\n")
            val lag = s.tsLag
            val min = e.minLag
            if (lag != null && min != null) {
                sb.append(String.format(Locale.ROOT, "载荷延迟 %.2f s  较打开时 +%.2f s\n", lag / 1000, (lag - min) / 1000))
            } else {
                sb.append("载荷延迟 未识别到时间戳\n")
            }
            s.heapMb?.let { sb.append("JS 堆 ${it.toInt()} MB\n") }
        }
        return if (sb.isEmpty()) "诊断模式：等待页面数据" else sb.toString().trimEnd()
    }

    fun close() {
        try {
            log?.close()
        } catch (e: IOException) {
            // 关闭失败不影响浏览
        }
    }

    private fun writeLine(cols: List<String>) {
        val w = log ?: return
        try {
            w.write(cols.joinToString(","))
            w.newLine()
            w.flush()
        } catch (e: IOException) {
            // 存储满或被拔出时放弃这一行
        }
    }

    private fun time(): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())

    companion object {
        private const val STALE_MS = 5_000L
        private const val CATCH_UP_STREAK = 3
        private const val SATURATED_WINDOW_MS = 1_500
        private const val HEADER =
            "时间,来源,事件,上报周期ms,WS连接,SSE连接,消息数/秒,数据量/秒,回调耗时ms/秒,单条最长ms,帧率,最长帧间隔ms," +
                "长任务数/秒,长任务ms/秒,事件循环延迟ms,载荷延迟ms,较打开时ms,JS堆MB,页面可见"

        private fun optDouble(r: JSONObject, name: String): Double? =
            if (r.isNull(name)) null else r.optDouble(name).takeIf { !it.isNaN() }

        private fun f(v: Double?): String =
            if (v == null) "" else if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)

        private fun bytes(n: Long): String = when {
            n >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MB", n / 1_048_576.0)
            n >= 1024 -> String.format(Locale.ROOT, "%.1f KB", n / 1024.0)
            else -> "$n B"
        }
    }
}
