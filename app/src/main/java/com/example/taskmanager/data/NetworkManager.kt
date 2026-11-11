package com.example.taskmanager.data

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.Locale

fun fetchFromSocket(request: String): String {
    return try {
        val socket = Socket("127.0.0.1", 12345)
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
        writer.write("$request\n")
        writer.flush()

        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val result = StringBuilder()
        reader.useLines { lines -> lines.forEach { result.append(it).append("\n") } }
        socket.close()
        result.toString()
    } catch (_: Exception) { "" }
}

fun parsePerfData(output: String): PerfInfo? {
    if (output.isBlank()) return null
    val map = output.lines().filter { it.contains(":") }.associate {
        it.substringBefore(":").trim() to it.substringAfter(":").trim()
    }
    return PerfInfo(
        cpuUsage = map["cpu_usage"]?.toFloatOrNull() ?: 0f,
        curFreq = map["cur_freq"] ?: "未知",
        maxFreq = map["max_freq"] ?: "未知",
        processes = map["processes"] ?: "0",
        threads = map["threads"] ?: "0",
        fds = map["fds"] ?: "0",
        logical = map["logical"] ?: "0",
        l1 = map["l1"] ?: "未知",
        l2 = map["l2"] ?: "未知",
        l3 = map["l3"] ?: "未知",
        uptime = map["uptime"] ?: "未知"
    )
}

fun parseProcOutput(output: String): List<ProcessInfo> {
    val list = mutableListOf<ProcessInfo>()
    output.lines().forEach { line ->
        if (line.isBlank()) return@forEach
        val parts = line.split("|")
        if (parts.size >= 9) {
            val memoryMb = try { String.format(Locale.US, "%.1f MB", parts[8].toLong() / 1024.0) } catch (e: Exception) { "0.0 MB" }
            list.add(ProcessInfo(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6], parts[7], memoryMb))
        }
    }
    return list.sortedByDescending { it.memory.replace(" MB", "").toDoubleOrNull() ?: 0.0 }
}