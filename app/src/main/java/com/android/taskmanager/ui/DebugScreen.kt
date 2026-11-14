/**
 * ============================================================
 * 调试页
 * ============================================================
 *
 * 功能：验证 APK 内置的 Linux ELF 后端（libtask_backend.so）能否被拉起、能否收发命令。
 *
 * 职责边界（本次改造的重点）：
 * 本页**不持有进程、不读 stdout**——起停与收发都在 LinuxService，
 * 对外能力由 UIService 的 IUIServiceApi 转发（startLinuxBackend / pingLinuxSvc /
 * linuxBackendOutput）。页面只负责：点按钮、把接口返回的文本显示出来。
 *
 * 流程（v2 请求包，协议见 data/LinuxService.kt ① 段）：
 *   点"启动 Linux Backend 测试" -> UIService.startLinuxBackend()
 *        -> LinuxService 用 /system/bin/linker64 拉起 ELF（常驻循环，见 cpp/Backend/main.c）
 *        -> 后端先打 READY，LinuxService 收到后才允许发正式请求
 *        -> 页面每 400ms 轮询 linuxBackendOutput()，把最近输出贴到结果区
 *   点"心跳" -> UIService.pingLinuxSvc() -> `REQ <seq> 4 0x0` -> 后端回 PKG(seq) + I pong=1
 *   点"停止" -> UIService.stopLinuxBackend() -> LinuxService 先发 SHUTDOWN 包再 destroy
 *
 * 两个输出框：
 *   ① 测试结果：本页按钮驱动的交互结果（最近的后端往来）；
 *   ② 上游服务输出：AndroidService / LinuxService 自己的运行记录
 *      （身份自检、READY/OK/DATA/ERR 应答），通过 UIService.upstreamOutput() 一次取两条。
 *
 * 预留实现接口：
 * - 增加更多自检项：/proc 可读性、Shizuku 状态、Root 状态、设备信息；
 * - 后端支持子命令后（procs / meminfo / threads），这里加按钮即可，不用动进程管理。
 *
 * ============================================================
 * [DEBUG-EXEMPT] 调试页豁免说明（刻意保留，不要照搬到业务页）
 * ============================================================
 *
 * 本页是排查问题的入口，因此**允许**越过下面这些一般约束：
 * 1. 可以直接调用任何一个对外接口（IUIServiceApi / IUserCredentialApi / IShizukuApi 等），
 *    不必像业务页那样"只读 UIService 快照、只走页面数据接口"；
 * 2. 可以直接触发提权 / 重启 / 停止这类有副作用的动作（重启 Android 服务、拉起 Linux 后端等），
 *    目的就是方便观察"切换身份之后上游到底发生了什么"；
 * 3. 可以显示服务自报的原始输出（含 uid、READY / OK / DATA / ERR 应答），
 *    但这些输出里**只应出现 uid**，不该出现"权限是从 Shizuku 还是 su 借来的"这类来源说明 ——
 *    权限来源模型只归 UIService 维护，服务侧只需要知道自己跑成了什么 uid。
 *
 * 除本页之外，任何业务页面都必须遵守：数据从 UIService 拿，不直接调上游、不自行判断提权能力。
 * 需要新调试能力时加在这里，而不是放宽业务页的约束。
 */
package com.android.taskmanager

import android.os.Handler
import android.os.Looper
import android.widget.Toast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import com.android.taskmanager.service.UIService

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.File
import com.android.taskmanager.data.LinuxControlCode
import com.android.taskmanager.data.LinuxField
import com.android.taskmanager.data.LinuxProtocol
import com.android.taskmanager.data.LinuxRequestType

// ============================================================
// Debug Screen
// ============================================================

/** "完整快照"按钮最多列出多少个进程（其余汇总成一行，避免一次贴几百行）。 */
private const val FULL_SNAPSHOT_LINES = 20

/** 页面实现状态：调试页已实现（启动 / 收发 / 停止 Linux 后端都能跑）。 */
val DebugPageState: PageState = PageState.IMPLEMENTED

@Composable
fun DebugScreen() {

    // 只用来取 ELF 的安装路径做展示（不在这里启动它）
    val context = LocalContext.current

    // 后端输出的镜像：由上方的轮询写入，页面只读
    var output by remember {
        mutableStateOf("等待 Linux Backend 启动...")
    }

    // 后端是否在跑：决定按钮文案与可用性
    var isRunning by remember {
        mutableStateOf(false)
    }

    /**
     * 页面自己的协程作用域：起停后端、发命令都放到 IO 线程。
     *
     * 为什么必须离开主线程：root 通路是 `su -c`，第一次会弹 Magisk 的授权框，
     * 主线程在这里同步等 = 界面卡死甚至 ANR（用户看到的就是"点一下崩了"）。
     * Shizuku 通路虽然不弹框，但也是一次 IPC，同样不该占着主线程。
     */
    val scope = rememberCoroutineScope()

    // 上游服务（AndroidService / LinuxService）的最近输出：调试页"第二项"
    var upstream by remember {
        mutableStateOf(UpstreamOutput())
    }

    // 后端当前身份（shell / 应用 UID），用于在页面上直接说明"是谁在跑"
    var backendIdentity by remember {
        mutableStateOf("未知")
    }

    // UIService 是否就绪：没起来时按钮点了也没意义，提前说清楚
    var serviceReady by remember {
        mutableStateOf(false)
    }

    /**
     * v2 请求包的验证输出（请求包 / 应答包）。
     *
     * 单独一个状态，不跟 [output] 混：那个框由 400ms 轮询覆盖写，
     * 这里的结果要留到用户看清为止，所以只在点按钮时更新。
     */
    var packetOutput by remember {
        mutableStateOf("尚未发起 v2 请求（协议见 data/LinuxService.kt ① 段）")
    }

    // 主线程 Handler：轮询线程不能直接改 Compose 状态，统一 post 回主线程
    val mainHandler = remember {
        Handler(Looper.getMainLooper())
    }

    // 轮询：每 400ms 从 UIService 取一次快照。
    // 为什么用轮询而不是回调：后端输出是"推"的，但页面拿的是"拉"的接口，
    // 最简实现下轮询最省事；等 UIService 提供 StateFlow 后换成 collect 即可。
    LaunchedEffect(Unit) {
        while (true) {
            val service = UIService.instanceOrNull()

            mainHandler.post {
                serviceReady = service != null

                output = service?.linuxBackendOutput() ?: "UIService 尚未启动"
                isRunning = service?.isLinuxBackendRunning == true

                // 第二项：两个上游服务各自的输出，与上面的"测试结果"分开取
                upstream = service?.upstreamOutput() ?: UpstreamOutput()

                backendIdentity = when {
                    !isRunning -> "未运行"
                    else -> service?.linuxBackendIdentity() ?: "未知"
                }
            }

            kotlinx.coroutines.delay(400L)
        }
    }

    // ========================================================
    // UI
    // ========================================================

    Column(
        // 整页可滚动：横屏时高度被顶部的页名行吃掉一大截，
        // 内容（标题 + 说明 + 按钮 + 结果区）会超出屏幕，
        // 这里不给 verticalScroll 就既看不全也拖不动。
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),

        verticalArrangement = Arrangement.Top,

        horizontalAlignment = Alignment.Start
    ) {

        // 页面标题
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(
                modifier = Modifier.width(12.dp)
            )

            Text(
                text = "调试与测试",

                style = MaterialTheme.typography.headlineSmall,

                fontWeight = FontWeight.Bold
            )
        }

        Spacer(
            modifier = Modifier.height(24.dp)
        )

        // ====================================================
        // Linux Backend Test
        // ====================================================

        Text(
            text = "Linux Backend",

            style = MaterialTheme.typography.titleMedium,

            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Text(
            text = "启动 APK 内置的 Linux ELF 后端，并读取它与本应用之间的命令往来。",

            style = MaterialTheme.typography.bodyMedium,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        // ELF 安装位置：只做展示，方便确认打包是否生效（起停由 LinuxService 负责）
        Text(
            text = "ELF: " + File(
                context.applicationInfo.nativeLibraryDir,
                "libtask_backend.so"
            ).absolutePath,

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        // 启动入口：只发一个"请启动"的请求，具体 Process 在 LinuxService 里
        Button(
            onClick = {
                // 起进程可能要走 su（会弹授权框），必须离开主线程
                scope.launch(Dispatchers.IO) {

                    val service = UIService.instanceOrNull()

                    if (service == null) {
                        withContext(Dispatchers.Main) {
                            output = "UIService 尚未就绪，请稍后重试"
                        }

                        return@launch
                    }

                    val started = service.startLinuxBackend()

                    if (started) {
                        // 起来之后先发一次心跳：验证"READY 之后通道真的能收发"，
                        // 结果区立刻有话可看
                        service.pingLinuxSvc()
                    }

                    val text = if (started) {
                        service.linuxBackendOutput()
                    } else {
                        // 起不来时把服务侧记下的原因原样给他（如"找不到 ELF / su 被拒 / Shizuku 失败"）
                        service.linuxBackendOutput()
                    }

                    withContext(Dispatchers.Main) {
                        output = text
                    }
                }
            },

            enabled = serviceReady && !isRunning
        ) {

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null
            )

            Spacer(
                modifier = Modifier.width(8.dp)
            )

            Text(
                if (isRunning) "Linux Backend 运行中" else "启动 Linux Backend 测试"
            )
        }

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        // 心跳自检：证明"启动一次之后还能继续收发"，而不是每次重新 fork
        OutlinedButton(
            onClick = {
                scope.launch(Dispatchers.IO) {

                    val service = UIService.instanceOrNull()

                    val text = when {
                        service == null -> "UIService 尚未就绪，请稍后重试"

                        service.pingLinuxSvc() -> "心跳成功：后端回了 PKG + pong=1（见上方输出）"

                        else -> "心跳失败：后端没在运行，或尚未就绪 / 超时"
                    }

                    withContext(Dispatchers.Main) {
                        output = text
                    }
                }
            },

            enabled = serviceReady && isRunning
        ) {

            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null
            )

            Spacer(
                modifier = Modifier.width(8.dp)
            )

            Text("发送心跳")
        }

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        // 停止入口：验证"生命周期确实由 UIService 管"——停掉后进程消失、按钮回可点
        TextButton(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    UIService.instanceOrNull()?.stopLinuxBackend()
                }
            },

            enabled = serviceReady && isRunning
        ) {
            Text("停止 Linux Backend")
        }

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        // ====================================================
        // v2 协议自检：请求包 / 应答包
        //
        // 为什么放在调试页：这里是唯一可以直接观察"协议有没有真的跑通"的地方。
        // 三个按钮分别覆盖 查询进程 / 查询能力 / 控制 三种包。
        // 页面侧正常不应这样直连（它们只登记需求，由 UIService 合并后统一取）。
        // ====================================================

        Text(
            text = "v2 请求包自检（协议见 data/LinuxService.kt ① 段）",

            style = MaterialTheme.typography.titleSmall,

            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // 查询进程快照：掩码 43 位全开，服务端按"点到的字段"逐行返回
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {

                        val service = UIService.instanceOrNull()

                        val text = if (service == null) {
                            "UIService 尚未就绪，请稍后重试"
                        } else {

                            val packet = service.GetDataFromLinuxSvc(LinuxField.ALL)

                            if (packet == null) {
                                "请求失败：后端未运行 / 发送失败 / 等待应答超时"
                            } else buildString {

                                appendLine(
                                    "请求：REQ #${packet.seq} " +
                                        "${LinuxRequestType.nameOf(packet.typeCode)} " +
                                        "mask=${LinuxField.describe(packet.mask)}"
                                )

                                appendLine("应答：${packet.summary()}")

                                val first = packet.processes.firstOrNull()

                                if (first == null) {
                                    appendLine("（包里没有进程行）")
                                } else {
                                    appendLine("首行 pid=${first.pid}，字段 ${first.fields.size} 个：")

                                    appendLine(
                                        first.fields.entries.joinToString("  ") {
                                            "${it.key}=${it.value}"
                                        }
                                    )

                                    // 字段级状态：这些是"没有数值"的格子（不是 0）
                                    if (first.statuses.isNotEmpty()) {
                                        appendLine(
                                            "无值字段：" +
                                                first.statuses.entries.joinToString("  ") {
                                                    "${it.key}=!${it.value}"
                                                }
                                        )
                                    }
                                }

                                append("原始行 ${packet.raw.size} 行")
                            }
                        }

                        withContext(Dispatchers.Main) {
                            packetOutput = text
                        }
                    }
                },

                enabled = serviceReady && isRunning
            ) {
                Text("查询进程快照")
            }

            // 完整快照：一次要全部 43 列，把每个进程的每一格都打出来。
            // 只列前 FULL_SNAPSHOT_LINES 个进程（其余汇总成一行），
            // 再给一份"无值字段汇总"——哪一格没有值、各出现多少次，一眼能看出是权限还是没实现。
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {

                        val service = UIService.instanceOrNull()

                        val text = if (service == null) {
                            "UIService 尚未就绪，请稍后重试"
                        } else {

                            val packet = service.GetDataFromLinuxSvc(LinuxField.ALL)

                            if (packet == null) {
                                "请求失败：后端未运行 / 发送失败 / 等待应答超时"
                            } else buildString {

                                appendLine("应答：${packet.summary()}")
                                appendLine("原始行：${packet.raw.size} 行（包头 1 + 进程 ${packet.processes.size}）")
                                appendLine()

                                val shown = packet.processes.take(FULL_SNAPSHOT_LINES)

                                shown.forEach { row ->
                                    appendLine("pid=${row.pid} start_ticks=${row.startTicks ?: "?"}")

                                    appendLine(
                                        "  " + LinuxField.KEYS.joinToString("  ") { key ->
                                            val status = row.statusOf(key)

                                            if (status != null) {
                                                "$key=!$status"
                                            } else {
                                                "$key=${row.fieldOf(key) ?: "-"}"
                                            }
                                        }
                                    )
                                }

                                if (packet.processes.size > shown.size) {
                                    appendLine(
                                        "…… 其余 ${packet.processes.size - shown.size} 个进程未列出"
                                    )
                                }

                                val statusCount = mutableMapOf<String, Int>()

                                packet.processes.forEach { row ->
                                    row.statuses.forEach { (key, status) ->
                                        val label = "$key=!$status"
                                        statusCount[label] = (statusCount[label] ?: 0) + 1
                                    }
                                }

                                if (statusCount.isNotEmpty()) {
                                    appendLine()
                                    appendLine("无值字段汇总：")

                                    statusCount.entries
                                        .sortedByDescending { it.value }
                                        .forEach { appendLine("  ${it.key} ×${it.value}") }
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            packetOutput = text
                        }
                    }
                },

                enabled = serviceReady && isRunning
            ) {
                Text("完整快照")
            }

            // 查询能力：proto / fields / fieldMask —— 两侧字段表是否同步，看这一行就知道
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {

                        val service = UIService.instanceOrNull()

                        val text = if (service == null) {
                            "UIService 尚未就绪，请稍后重试"
                        } else {

                            val packet = service.linuxCapabilities()

                            if (packet == null) {
                                "能力查询失败：后端未运行 / 超时"
                            } else buildString {

                                appendLine("应答：${packet.summary()}")

                                packet.info.entries.forEach { (key, value) ->
                                    appendLine("$key = $value")
                                }

                                append(
                                    "本地字段表：${LinuxField.KEYS.size} 项，ALL=0x" +
                                        java.lang.Long.toHexString(LinuxField.ALL)
                                )
                            }
                        }

                        withContext(Dispatchers.Main) {
                            packetOutput = text
                        }
                    }
                },

                enabled = serviceReady && isRunning
            ) {
                Text("查询能力")
            }
        }

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // 控制类：让服务重建采集上下文（当前服务侧只回执，不做真实动作）
            TextButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {

                        val service = UIService.instanceOrNull()

                        val text = when {
                            service == null -> "UIService 尚未就绪，请稍后重试"

                            service.ControlLinuxSvc(LinuxControlCode.REINIT, "") ->
                                "控制回执：REINIT 成功（服务侧已重建采集上下文）"

                            else -> "控制失败：后端未运行 / 超时 / 服务侧拒绝"
                        }

                        withContext(Dispatchers.Main) {
                            packetOutput = text
                        }
                    }
                },

                enabled = serviceReady && isRunning
            ) {
                Text("控制：重探上下文")
            }

            TextButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {

                        val service = UIService.instanceOrNull()

                        val text = when {
                            service == null -> "UIService 尚未就绪，请稍后重试"

                            service.ControlLinuxSvc(LinuxControlCode.DUMP_STATE, "") ->
                                "控制回执：DUMP_STATE 成功（服务内部状态已吐出，见上方输出）"

                            else -> "控制失败：后端未运行 / 超时 / 服务侧拒绝"
                        }

                        withContext(Dispatchers.Main) {
                            packetOutput = text
                        }
                    }
                },

                enabled = serviceReady && isRunning
            ) {
                Text("控制：导出状态")
            }
        }

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),

            color = MaterialTheme.colorScheme.surfaceVariant,

            shape = MaterialTheme.shapes.medium
        ) {

            Text(
                text = packetOutput,

                modifier = Modifier.padding(12.dp),

                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        HorizontalDivider()

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        // ====================================================
        // AndroidService 重启
        //
        // 为什么需要这个入口：切换身份（普通 / Shell / root）之后，
        // AndroidService 里记的 privilegedMode 与"上次自检结果"还是旧的；
        // 想观察"换身份后它到底以什么身份在跑"，就得把这条通道重启一次。
        // 只重启 AndroidService，不动 LinuxService，这样两条通道的日志能分开对照。
        // ====================================================

        Text(
            text = "AndroidService",

            style = MaterialTheme.typography.titleMedium,

            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(4.dp)
        )

        Text(
            text = "重启 Android 服务：按当前身份重新拉起，并在输出里重新打印一次身份自检。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        OutlinedButton(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    UIService.instanceOrNull()?.restartAndroidService()
                }
            },

            enabled = serviceReady
        ) {

            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null
            )

            Spacer(
                modifier = Modifier.width(8.dp)
            )

            Text("重启 Android 服务")
        }

        Spacer(
            modifier = Modifier.height(24.dp)
        )

        HorizontalDivider()

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        // ====================================================
        // 详细信息页 · 测试数据
        //
        // 为什么放在调试页：详细信息页的表不该自己造假数据；
        // "表里有几行"要能反映真实进程；要压测表格（43 列 × 多行）就从这个按钮手动加。
        // 每次点击加入 Test0 ~ Test10 共 11 个进程：名称 TestN，其余统计值都填 N。
        // ====================================================

        Text(
            text = "详细信息页测试数据",

            style = MaterialTheme.typography.titleMedium,

            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(4.dp)
        )

        Text(
            text = "加入 Test0 ~ Test10 共 11 个进程（名称 TestN，其余统计值都填 N），用来观察表格的排序、选中与横向滚动开销。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        OutlinedButton(
            onClick = {

                scope.launch(Dispatchers.IO) {

                    val added = UIService.instanceOrNull()?.appendDetailTestRows() ?: 0

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "已加入 $added 行（Test0 ~ Test10，重复点击只刷新）",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },

            enabled = serviceReady
        ) {

            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null
            )

            Spacer(
                modifier = Modifier.width(8.dp)
            )

            Text("添加 Test0 ~ Test10")
        }

        Spacer(
            modifier = Modifier.height(24.dp)
        )

        HorizontalDivider()

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        // ====================================================
        // Test Result
        // ====================================================

        Text(
            // 结果展示区：显示后端最近若干行输出（命令回显 + 应答），
            // 或启动失败 / 服务未就绪的提示
            text = "测试结果",

            style = MaterialTheme.typography.titleMedium,

            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),

            color = MaterialTheme.colorScheme.surfaceVariant,

            shape = MaterialTheme.shapes.medium
        ) {

            Text(
                text = output,

                modifier = Modifier.padding(16.dp),

                style = MaterialTheme.typography.bodyLarge,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(
            modifier = Modifier.height(24.dp)
        )

        HorizontalDivider()

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        // ====================================================
        // 第二项：上游服务的输出
        //
        // 与上面的"测试结果"分开的原因：
        // - 上面那个是"本页按钮驱动"的交互结果（启动 / 发 ping / 停止）；
        // - 这一项是"服务自己产生的"运行记录：AndroidService 的启动参数与身份自检、
        //   LinuxService 的后端身份与 READY/OK/DATA/ERR 协议应答。
        // 两条通道挨着显示，便于对照"同一时刻谁在跑、谁没响应"。
        // ====================================================

        Text(
            text = "上游服务输出",

            style = MaterialTheme.typography.titleMedium,

            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(4.dp)
        )

        Text(
            text = "AndroidService 与 LinuxService 最近的运行记录（后端身份：$backendIdentity）",

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),

            color = MaterialTheme.colorScheme.surfaceVariant,

            shape = MaterialTheme.shapes.medium
        ) {

            Column(
                modifier = Modifier.padding(16.dp),

                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                Text(
                    text = upstream.android,

                    style = MaterialTheme.typography.bodyMedium,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                Text(
                    text = upstream.linux,

                    style = MaterialTheme.typography.bodyMedium,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
