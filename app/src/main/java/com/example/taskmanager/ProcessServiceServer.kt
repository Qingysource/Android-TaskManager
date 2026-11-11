package com.example.taskmanager

import androidx.annotation.Keep
import java.io.File
import java.util.Locale

@Keep
class ProcessServiceServer {
    // 缓存上次的 CPU 数据用于计算利用率
    private var lastTotalCpu = 0L
    private var lastIdleCpu = 0L

    fun getProcessList(): String {
        val result = StringBuilder()
        val procDir = File("/proc")
        val dirs = procDir.listFiles() ?: return ""

        for (dir in dirs) {
            if (!dir.isDirectory || !dir.name.matches(Regex("\\d+"))) continue
            val pid = dir.name
            try {
                val comm = File(dir, "comm").takeIf { it.exists() }?.readText()?.trim() ?: ""
                if (comm.isEmpty()) continue

                val cmdline = File(dir, "cmdline").takeIf { it.exists() }?.readText()?.replace('\u0000', ' ')?.trim() ?: ""
                var state = ""; var uid = ""; var nssid = ""; var memory = "0"

                val statusFile = File(dir, "status")
                if (statusFile.exists()) {
                    statusFile.forEachLine { line ->
                        when {
                            line.startsWith("State:") -> state = line.substringAfter("State:").trim().split(Regex("\\s+"))[0]
                            line.startsWith("Uid:") -> uid = line.substringAfter("Uid:").trim().split(Regex("\\s+"))[0]
                            line.startsWith("NSsid:") -> nssid = line.substringAfter("NSsid:").trim()
                            line.startsWith("VmRSS:") -> memory = line.substringAfter("VmRSS:").replace("kB", "").trim()
                        }
                    }
                }
                val cgroup = File(dir, "cgroup").takeIf { it.exists() }?.readText()?.replace("\n", ";")?.trim() ?: ""
                val exe = try { File(dir, "exe").canonicalPath } catch (e: Exception) { "" }

                result.append("$pid|$comm|${cmdline.replace("|", " ")}|$state|$uid|$nssid|${cgroup.replace("|", " ")}|$exe|$memory\n")
            } catch (e: Exception) {}
        }
        return result.toString()
    }

    // ================= 性能数据采集 =================
    fun getPerformanceData(): String {
        val usage = readCpuUsage()
        val (curFreq, maxFreq) = readFreq()
        val (pCount, tCount, fCount) = readCounts()
        val logical = File("/sys/devices/system/cpu").listFiles()?.count { it.name.matches(Regex("cpu\\d+")) } ?: 1
        val (l1, l2, l3) = readCaches()
        val uptime = readUptime()

        return """
            cpu_usage:$usage
            cur_freq:$curFreq
            max_freq:$maxFreq
            processes:$pCount
            threads:$tCount
            fds:$fCount
            logical:$logical
            l1:$l1
            l2:$l2
            l3:$l3
            uptime:$uptime
        """.trimIndent()
    }

    private fun readCpuUsage(): Float {
        return try {
            val stat = File("/proc/stat").readText()
            val cpuLine = stat.lineSequence().first { it.startsWith("cpu ") }
            val parts = cpuLine.split(Regex("\\s+"))
            val user = parts[1].toLong(); val nice = parts[2].toLong(); val system = parts[3].toLong()
            val idle = parts[4].toLong(); val iowait = parts[5].toLong(); val irq = parts[6].toLong(); val softirq = parts[7].toLong()

            val idleTime = idle + iowait
            val totalTime = user + nice + system + idleTime + irq + softirq

            if (lastTotalCpu == 0L) {
                lastTotalCpu = totalTime; lastIdleCpu = idleTime
                return 0f
            }
            val totalDiff = totalTime - lastTotalCpu
            val idleDiff = idleTime - lastIdleCpu
            lastTotalCpu = totalTime; lastIdleCpu = idleTime

            if (totalDiff == 0L) 0f
            else (100 * (totalDiff - idleDiff).toFloat() / totalDiff).coerceIn(0f, 100f)
        } catch (e: Exception) { 0f }
    }

    private fun readFreq(): Pair<String, String> {
        var curMax = 0L; var infoMax = 0L
        File("/sys/devices/system/cpu").listFiles()?.forEach { cpuDir ->
            if (cpuDir.name.startsWith("cpu") && cpuDir.name.length > 3) {
                try {
                    val curFile = File(cpuDir, "cpufreq/scaling_cur_freq")
                    if (curFile.exists()) {
                        val f = curFile.readText().trim().toLongOrNull() ?: 0L
                        if (f > curMax) curMax = f
                    }
                    val maxFile = File(cpuDir, "cpufreq/cpuinfo_max_freq")
                    if (maxFile.exists()) {
                        val f = maxFile.readText().trim().toLongOrNull() ?: 0L
                        if (f > infoMax) infoMax = f
                    }
                } catch (_: Exception) {}
            }
        }
        val curStr = if (curMax > 0) String.format(Locale.US, "%.2f GHz", curMax / 1000000.0) else "未知"
        val maxStr = if (infoMax > 0) String.format(Locale.US, "%.2f GHz", infoMax / 1000000.0) else "未知"
        return Pair(curStr, maxStr)
    }

    private fun readCounts(): Triple<Int, Int, Int> {
        var p = 0; var t = 0; var f = 0
        File("/proc").listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.matches(Regex("\\d+"))) {
                p++
                t += File(file, "task").listFiles()?.size ?: 0
                f += File(file, "fd").listFiles()?.size ?: 0
            }
        }
        return Triple(p, t, f)
    }

    private fun readCaches(): Triple<String, String, String> {
        var l1 = "未知"; var l2 = "未知"; var l3 = "未知"
        File("/sys/devices/system/cpu/cpu0/cache").listFiles()?.forEach { dir ->
            if (dir.name.startsWith("index")) {
                try {
                    val level = File(dir, "level").readText().trim()
                    val size = File(dir, "size").readText().trim()
                    when(level) {
                        "1" -> l1 = size; "2" -> l2 = size; "3" -> l3 = size
                    }
                } catch (_: Exception) {}
            }
        }
        return Triple(l1, l2, l3)
    }

    private fun readUptime(): String {
        return try {
            val uptimeStr = File("/proc/uptime").readText().split(" ")[0]
            val uptimeSec = uptimeStr.toDoubleOrNull()?.toLong() ?: 0
            val days = uptimeSec / (24 * 3600)
            val hours = (uptimeSec % (24 * 3600)) / 3600
            val mins = (uptimeSec % 3600) / 60
            if (days > 0) "${days}天 ${hours}小时" else "${hours}小时 ${mins}分钟"
        } catch (_: Exception) { "未知" }
    }
}