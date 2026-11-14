/**
 * ============================================================
 * 用户凭据的界面状态（UIService <-> 设置页 的实时通道）
 * ============================================================
 *
 * 为什么要一个独立的状态对象，而不是让设置页自己 remember：
 * - 授权状态是**服务侧的事**：设置页拿不到 Shizuku / Root 的真实状态，也不该去查；
 * - 将来 Shizuku / Root 授权成功，是 UIService 先知道，它要能"推"给界面；
 * - 主界面和浮窗里的设置页是两处 Composition，状态放这里两边看到的是同一份。
 *
 * 写入方只有 UIService：它每次调 credentialSnapshot() 时用 [apply] 把最新状态推下来。
 * 设置页只读（顺带在"UISvc 判定允许"后由服务回推新身份），不自己改这里的值。
 *
 * 回退方式：删掉本文件，并把设置页"用户凭据"分组一起删（那个分组只依赖本状态）。
 */
package com.android.taskmanager

import androidx.compose.runtime.mutableStateOf

/**
 * 当前生效的凭据状态。
 *
 * 三个字段都是 Compose 可变状态：读它们的界面会在服务推新值时自动重组，
 * 不需要页面轮询——这也是"按钮能自己变成已授权"的实现方式。
 */
internal object UserCredentialState {

    /** 当前生效的用户身份（[SettingsCatalog.CREDENTIAL_IDENTITIES] 之一）。 */
    val identity = mutableStateOf(SettingsCatalog.CREDENTIAL_NORMAL)

    /** Shizuku 是否已授权（决定 Shizuku 一栏的按钮是"请求权限"还是灰掉的"已授权"）。 */
    val shizukuGranted = mutableStateOf(false)

    /** 是否具备 Root 能力（同上，决定 Root 一栏的按钮）。 */
    val rootGranted = mutableStateOf(false)

    /** Shizuku 服务端是否以 root 运行（决定 Shizuku 一行怎么描述自己的能力）。 */
    val shizukuRoot = mutableStateOf(false)

    /** Shell(adb) 身份是否真的可用（Shizuku 已授权且服务端是 uid 2000）。 */
    val shellAvailable = mutableStateOf(false)

    /** Shizuku 管理器装没装（未安装时按钮改成"请求权限"并在失败框里提示去装）。 */
    val shizukuInstalled = mutableStateOf(false)

    /** Shizuku 服务在不在跑（装了但没启动时按钮改成"打开 Shizuku"）。 */
    val shizukuRunning = mutableStateOf(false)

    /** 把 UIService 给的最新快照整份盖到界面上。 */
    fun apply(snapshot: UserCredentialSnapshot) {
        identity.value = snapshot.identity

        shizukuGranted.value = snapshot.shizukuGranted

        rootGranted.value = snapshot.rootGranted

        shizukuRoot.value = snapshot.shizukuRoot

        shellAvailable.value = snapshot.shellAvailable

        shizukuInstalled.value = snapshot.shizukuInstalled

        shizukuRunning.value = snapshot.shizukuRunning
    }
}
