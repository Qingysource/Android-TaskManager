package com.example.taskmanager.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.taskmanager.data.PerfInfo
import com.example.taskmanager.data.ShizukuManager
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun PerformancePage() {
    var perfData by remember { mutableStateOf<PerfInfo?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val usageHistory = remember { mutableStateListOf<Float>() }
    val shizukuState by ShizukuManager.state.collectAsState()

    // 用于计算 CPU 差值
    var lastTotalCpu by remember { mutableStateOf(0L) }
    var lastIdleCpu by remember { mutableStateOf(0L) }

    LaunchedEffect(shizukuState) {
        if (shizukuState != ShizukuManager.ShizukuState.Ready) {
            errorMsg = "等待 Shizuku 授权..."
            return@LaunchedEffect
        }

        while (true) {
            try {
                val raw = ShizukuManager.getPerformanceData()
                if (raw.isBlank()) {
                    errorMsg = "获取数据失败"
                } else {
                    // 解析 Shizuku 组合命令的返回结果
                    val lines = raw.lines()
                    var cpuLine = ""
                    var curFreq = "未知"
                    var maxFreq = "未知"
                    var procs = "0"
                    var threads = "0"
                    var uptimeStr = "未知"

                    for (line in lines) {
                        when {
                            line.startsWith("cpu:") -> cpuLine = line.substringAfter("cpu:").trim()
                            line.startsWith("freq:") -> {
                                val f = line.substringAfter("freq:").trim().toLongOrNull() ?: 0L
                                if (f > 0) curFreq = String.format(Locale.US, "%.2f GHz", f / 1000000.0)
                            }
                            line.startsWith("max_freq:") -> {
                                val f = line.substringAfter("max_freq:").trim().toLongOrNull() ?: 0L
                                if (f > 0) maxFreq = String.format(Locale.US, "%.2f GHz", f / 1000000.0)
                            }
                            line.startsWith("procs:") -> procs = line.substringAfter("procs:").trim()
                            line.startsWith("threads:") -> threads = line.substringAfter("threads:").trim()
                            line.startsWith("uptime:") -> {
                                val sec = line.substringAfter("uptime:").trim().toDoubleOrNull()?.toLong() ?: 0
                                val days = sec / 86400
                                val hours = (sec % 86400) / 3600
                                uptimeStr = if (days > 0) "${days}天 ${hours}小时" else "${hours}小时"
                            }
                        }
                    }

                    // 计算 CPU 利用率
                    var cpuUsage = 0f
                    if (cpuLine.isNotEmpty()) {
                        val parts = cpuLine.split(Regex("\\s+"))
                        if (parts.size >= 8) {
                            // 格式: cpu user nice system idle iowait irq softirq
                            val user = parts[1].toLongOrNull() ?: 0
                            val nice = parts[2].toLongOrNull() ?: 0
                            val system = parts[3].toLongOrNull() ?: 0
                            val idle = parts[4].toLongOrNull() ?: 0
                            val iowait = parts[5].toLongOrNull() ?: 0
                            val irq = parts[6].toLongOrNull() ?: 0
                            val softirq = parts[7].toLongOrNull() ?: 0

                            val idleTime = idle + iowait
                            val totalTime = user + nice + system + idleTime + irq + softirq

                            if (lastTotalCpu != 0L) {
                                val totalDiff = totalTime - lastTotalCpu
                                val idleDiff = idleTime - lastIdleCpu
                                if (totalDiff > 0) {
                                    cpuUsage = (100 * (totalDiff - idleDiff).toFloat() / totalDiff).coerceIn(0f, 100f)
                                }
                            }
                            lastTotalCpu = totalTime
                            lastIdleCpu = idleTime
                        }
                    }

                    perfData = PerfInfo(
                        cpuUsage = cpuUsage,
                        curFreq = curFreq,
                        maxFreq = maxFreq,
                        processes = procs,
                        threads = threads,
                        fds = "需 root 权限", // 句柄数通常普通 ADB 权限受限
                        logical = "8", // 默认写死，若要精确需用 lscpu
                        l1 = "通常 32KB-64KB",
                        l2 = "通常 512KB-1MB",
                        l3 = "视芯片而定",
                        uptime = uptimeStr
                    )
                    errorMsg = null
                    usageHistory.add(cpuUsage)
                    if (usageHistory.size > 60) usageHistory.removeAt(0)
                }
            } catch (e: Exception) {
                errorMsg = "异常：${e.message}"
            }
            delay(1000)
        }
    }

    if (errorMsg != null) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    if (perfData == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("性能监控", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("CPU 利用率: ${String.format(Locale.US, "%.1f", perfData!!.cpuUsage)}%", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    UsageChart(history = usageHistory, modifier = Modifier.fillMaxWidth().height(200.dp))
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                InfoCard("运行速度", perfData!!.curFreq, Modifier.weight(1f))
                InfoCard("基准速度", perfData!!.maxFreq, Modifier.weight(1f))
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("系统对象统计", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        InfoColumn("进程总数", perfData!!.processes)
                        InfoColumn("线程总数", perfData!!.threads)
                        InfoColumn("句柄总数", perfData!!.fds)
                    }
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("硬件信息", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    InfoRow("逻辑处理器数量", perfData!!.logical)
                    InfoRow("L1 缓存", perfData!!.l1)
                    InfoRow("L2 缓存", perfData!!.l2)
                    InfoRow("L3 缓存", perfData!!.l3)
                    InfoRow("正常运行时间", perfData!!.uptime)
                }
            }
        }
    }
}

@Composable
private fun UsageChart(history: List<Float>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Canvas(modifier = modifier) {
        val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        for (i in 1..4) {
            val y = size.height - (size.height * i / 4f)
            drawLine(color = gridColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = dashPathEffect)
        }

        if (history.size > 1) {
            val path = Path()
            val stepX = size.width / 59f
            val startIndex = if (history.size < 60) 60 - history.size else 0

            history.forEachIndexed { index, value ->
                val x = (startIndex + index) * stepX
                val y = size.height - (value / 100f * size.height)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun InfoCard(title: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun InfoColumn(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}