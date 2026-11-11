package com.example.taskmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.taskmanager.data.ShizukuManager
import com.example.taskmanager.data.ShizukuManager.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsPage() {
    val shizukuState by ShizukuManager.state.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("管理应用权限与高级功能", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ShizukuStatusCard(
            state = shizukuState,
            onRequestPermission = { ShizukuManager.requestPermission() },
            onRefresh = { ShizukuManager.updateState() }
        )

        if (shizukuState == ShizukuState.Ready) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("高级功能 (通过 Shizuku 执行)", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val output = ShizukuManager.execShellCommand(arrayOf("sh", "-c", "ps -A | head -n 5"))
                                    withContext(Dispatchers.Main) { testResult = output }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { testResult = "执行失败：${e.message}" }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("测试执行底层 'ps -A'")
                    }
                    if (testResult.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = testResult,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShizukuStatusCard(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    val (icon, iconColor, title, description, actionText, onAction) = when (state) {
        ShizukuState.NotInstalled -> StatusInfo(
            Icons.Default.Warning, Color(0xFFF44336), "Shizuku 未安装",
            "请从 Google Play 或 GitHub 安装 Shizuku 应用。",
            "刷新状态", onRefresh
        )
        ShizukuState.ServiceNotRunning -> StatusInfo(
            Icons.Default.Warning, Color(0xFFFF9800), "Shizuku 服务未运行",
            "请在 Shizuku 应用中启动服务（通常通过无线调试或 Root）。",
            "刷新状态", onRefresh
        )
        ShizukuState.PermissionDenied -> StatusInfo(
            Icons.Default.Info, Color(0xFF2196F3), "等待授权",
            "Shizuku 服务已运行，但本应用尚未获得权限。",
            "请求权限", onRequestPermission
        )
        ShizukuState.Ready -> StatusInfo(
            Icons.Default.CheckCircle, Color(0xFF4CAF50), "Shizuku 已就绪",
            "应用已获得 ADB 级别权限，高级功能可用。",
            "刷新状态", onRefresh
        )
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconColor)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                Text(actionText)
            }
        }
    }
}

private data class StatusInfo(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconColor: Color,
    val title: String,
    val description: String,
    val actionText: String,
    val onAction: () -> Unit
)