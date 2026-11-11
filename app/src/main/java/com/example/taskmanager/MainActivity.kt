package com.example.taskmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
// ★ 补全了下面这三个 import
import com.example.taskmanager.data.ShizukuManager
import com.example.taskmanager.data.navItems
import com.example.taskmanager.ui.screens.PerformancePage
import com.example.taskmanager.ui.screens.ProcessPage
import com.example.taskmanager.ui.screens.SettingsPage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TaskManagerScreen()
            }
        }
    }
}

@Composable
fun TaskManagerScreen() {
    // 默认进入设置页（索引 7）
    var selectedTabIndex by remember { mutableIntStateOf(7) }

    // 初始化并监听 Shizuku 状态
    DisposableEffect(Unit) {
        ShizukuManager.init()
        onDispose { ShizukuManager.destroy() }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val isWide = maxWidth >= 600.dp
        if (isWide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    header = {
                        Text("TM", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 16.dp))
                    }
                ) {
                    // 修复横屏显示不下的 Bug：加入滚动
                    Column(
                        modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        navItems.forEachIndexed { index, item ->
                            NavigationRailItem(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                icon = { Icon(item.icon, null) },
                                label = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { PageContent(selectedTabIndex) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    navItems.forEachIndexed { index, item ->
                        Tab(selected = selectedTabIndex == index, onClick = { selectedTabIndex = index }, text = { Text(item.title) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { PageContent(selectedTabIndex) }
            }
        }
    }
}

// ================= 页面路由分发 =================
@Composable
fun PageContent(selectedIndex: Int) {
    when (selectedIndex) {
        0 -> ProcessPage()
        1 -> PerformancePage()
        7 -> SettingsPage() // 接入设置页
        else -> PlaceholderPage(navItems[selectedIndex].title)
    }
}

@Composable
fun PlaceholderPage(title: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Build, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text("$title 页面正在建设中", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}