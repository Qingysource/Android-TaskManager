package com.example.taskmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.taskmanager.data.ProcessInfo
import com.example.taskmanager.data.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ProcessPage() {
    var processList by remember { mutableStateOf<List<ProcessInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableIntStateOf(0) }
    var selectedProcess by remember { mutableStateOf<ProcessInfo?>(null) }

    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val shizukuState by ShizukuManager.state.collectAsState()

    fun refreshProcesses() {
        if (isLoading || shizukuState != ShizukuManager.ShizukuState.Ready) return
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val rawData = ShizukuManager.getProcessList() // 直接由 Shizuku 执行 ps -A
            val parsedList = parsePsOutput(rawData)
            withContext(Dispatchers.Main) {
                processList = parsedList
                isLoading = false
            }
        }
    }

    // ★ 自动加载：一旦 Shizuku 授权成功，立刻拉取数据
    LaunchedEffect(shizukuState) {
        if (shizukuState == ShizukuManager.ShizukuState.Ready) {
            refreshProcesses()
        }
    }

    val filteredList = processList.filter { process ->
        val matchesSearch = process.name.contains(searchQuery, ignoreCase = true) || process.pid.contains(searchQuery)
        val matchesFilter = when (selectedFilter) {
            1 -> process.name.startsWith("[") || process.name == "init"
            2 -> !process.name.startsWith("[") && process.name != "init"
            else -> true
        }
        matchesSearch && matchesFilter
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("进程", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { refreshProcesses() }, enabled = !isLoading && shizukuState == ShizukuManager.ShizukuState.Ready) {
                Icon(Icons.Default.Refresh, "刷新", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isLoading) "刷新中" else "刷新")
            }
        }

        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            placeholder = { Text("搜索进程或 PID...") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            shape = RoundedCornerShape(28.dp), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            FilterChip(selected = selectedFilter == 0, onClick = { selectedFilter = 0 }, label = { Text("全部") })
            FilterChip(selected = selectedFilter == 1, onClick = { selectedFilter = 1 }, label = { Text("系统") })
            FilterChip(selected = selectedFilter == 2, onClick = { selectedFilter = 2 }, label = { Text("用户") })
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(16.dp)) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (shizukuState != ShizukuManager.ShizukuState.Ready) {
                    Text("请先在设置页授权 Shizuku", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
                } else if (isLoading && processList.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (processList.isEmpty()) {
                    Text("未找到匹配的进程", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(contentPadding = PaddingValues(8.dp)) {
                        item {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("进程名称 / UID", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                Text("PID", modifier = Modifier.width(70.dp), style = MaterialTheme.typography.labelMedium)
                                Text("内存(RSS)", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.labelMedium)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        items(filteredList) { process ->
                            ProcessItem(process, onClick = { selectedProcess = process })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }
    }

    if (selectedProcess != null) {
        ProcessDetailDialog(process = selectedProcess!!, onDismiss = { selectedProcess = null })
    }
}

@Composable
private fun ProcessItem(process: ProcessInfo, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(
            color = when(process.state) { "R" -> Color(0xFF4CAF50); "S" -> Color(0xFF2196F3); "D" -> Color(0xFFFF9800); "Z" -> Color(0xFFF44336); else -> Color.Gray },
            shape = CircleShape
        ))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(process.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("UID: ${process.uid}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("PID: ${process.pid}", modifier = Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall)
        Text(process.memory, modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ProcessDetailDialog(process: ProcessInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(process.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("PID", process.pid)
                DetailRow("状态 (State)", process.state)
                DetailRow("UID", process.uid)
                DetailRow("会话 ID (NSsid)", process.nssid)
                DetailRow("路径 (exe)", process.exePath)
                DetailRow("命令行 (cmdline)", process.cmdline)
                DetailRow("作业对象 (cgroup)", process.cgroup)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value.ifEmpty { "无 / N/A" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

// ★ 直接解析 ps -A 的原始输出
private fun parsePsOutput(output: String): List<ProcessInfo> {
    val list = mutableListOf<ProcessInfo>()
    val lines = output.lines()
    if (lines.size <= 1) return list

    for (i in 1 until lines.size) {
        val line = lines[i].trim()
        if (line.isEmpty()) continue
        val parts = line.split(Regex("\\s+"))
        // ps -A 输出格式: USER PID PPID VSZ RSS WCHAN ADDR S NAME
        if (parts.size >= 9) {
            val pid = parts[1]
            val rss = parts[4] // 内存 (KB)
            val state = parts[7]
            val name = parts.subList(8, parts.size).joinToString(" ")

            val memoryMb = try {
                val kb = rss.toLong()
                String.format(Locale.US, "%.1f MB", kb / 1024.0)
            } catch (e: Exception) { "0.0 MB" }

            // ps 命令拿不到 cgroup 等详细信息，这里先给默认值，如果有需要可以再发命令去读取 /proc
            list.add(ProcessInfo(pid, name, "N/A", state, "N/A", "N/A", "N/A", "N/A", memoryMb))
        }
    }
    return list.sortedByDescending { it.memory.replace(" MB", "").toDoubleOrNull() ?: 0.0 }
}