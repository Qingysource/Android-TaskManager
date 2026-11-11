package com.example.taskmanager.data

import android.content.pm.PackageManager
import android.os.RemoteException
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuManager {

    sealed class ShizukuState {
        object NotInstalled : ShizukuState()
        object ServiceNotRunning : ShizukuState()
        object PermissionDenied : ShizukuState()
        object Ready : ShizukuState()
    }

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.ServiceNotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private const val REQUEST_CODE = 1001

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { updateState() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _state.value = ShizukuState.ServiceNotRunning
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) updateState()
            else _state.value = ShizukuState.PermissionDenied
        }
    }

    fun init() {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        updateState()
    }

    fun destroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    fun updateState() {
        _state.value = when {
            !Shizuku.pingBinder() -> ShizukuState.ServiceNotRunning
            Shizuku.isPreV11() -> ShizukuState.Ready
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.Ready
            else -> ShizukuState.PermissionDenied
        }
    }

    fun requestPermission() {
        if (!Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    // ★ 核心：使用反射执行 Shell 命令
    @Throws(Exception::class)
    fun execShellCommand(command: Array<String>): String {
        if (_state.value != ShizukuState.Ready) throw SecurityException("Shizuku not ready")

        val shizukuClass = Shizuku::class.java
        val newProcessMethod = shizukuClass.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        newProcessMethod.isAccessible = true

        val process = newProcessMethod.invoke(null, command, null, null) as? ShizukuRemoteProcess
            ?: throw RuntimeException("newProcess returned null")

        val output = StringBuilder()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        reader.useLines { lines -> lines.forEach { output.append(it).append("\n") } }

        val error = process.errorStream.bufferedReader().use { it.readText() }
        process.waitFor()

        if (error.isNotBlank()) throw RuntimeException("Command error: $error")
        return output.toString()
    }

    // 直接通过 Shizuku 获取 ps -A 信息
    fun getProcessList(): String {
        return try {
            execShellCommand(arrayOf("sh", "-c", "ps -A"))
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    // 直接通过 Shizuku 获取性能数据
    fun getPerformanceData(): String {
        return try {
            // 一次命令读取多个底层文件
            val cmd = "echo 'cpu:' \$(cat /proc/stat | head -n 1); " +
                    "echo 'freq:' \$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null || echo '0'); " +
                    "echo 'max_freq:' \$(cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>/dev/null || echo '0'); " +
                    "echo 'procs:' \$(ls /proc | grep -E '^[0-9]+\$' | wc -l); " +
                    "echo 'threads:' \$(ls -d /proc/*/task/* 2>/dev/null | wc -l); " +
                    "echo 'uptime:' \$(cat /proc/uptime | awk '{print \$1}')"
            execShellCommand(arrayOf("sh", "-c", cmd))
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}