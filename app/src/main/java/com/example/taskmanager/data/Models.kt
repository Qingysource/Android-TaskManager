package com.example.taskmanager.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

data class ProcessInfo(
    val pid: String, val name: String, val cmdline: String, val state: String,
    val uid: String, val nssid: String, val cgroup: String, val exePath: String, val memory: String
)

data class PerfInfo(
    val cpuUsage: Float, val curFreq: String, val maxFreq: String,
    val processes: String, val threads: String, val fds: String,
    val logical: String, val l1: String, val l2: String, val l3: String, val uptime: String
)

data class NavItem(val title: String, val icon: ImageVector)

val navItems = listOf(
    NavItem("进程", Icons.Default.List),
    NavItem("性能", Icons.Default.Info),
    NavItem("应用历史记录", Icons.Default.DateRange),
    NavItem("启动应用", Icons.Default.PlayArrow),
    NavItem("用户", Icons.Default.Person),
    NavItem("详细信息", Icons.Default.Settings),
    NavItem("服务", Icons.Default.Build),
    NavItem("设置", Icons.Default.Home)
)