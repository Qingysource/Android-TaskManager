# 📱 Android TaskManager (Hardcore Edition)

[English](#english-version) | [简体中文](#简体中文版)

---

## 简体中文版

一个基于 **Jetpack Compose (Material Design 3)** 和 **Shizuku API** 打造的硬核 Android 任务管理器。无需 Root，即可突破 Android 沙盒限制，读取系统底层的真实进程与性能数据。

### ✨ 核心特性

- **🔓 突破沙盒限制**：借助 Shizuku 获取 ADB (Shell) 权限，读取全量系统进程。
- **📊 极客性能监控**：实时解析 `/proc/stat` 绘制 CPU 利用率曲线，读取底层硬件运行频率、缓存与运行时间。
- **🗂️ 深度进程剖析**：不仅展示进程名和 PID，还深入解析 UID、状态、会话 ID (NSsid)、`cgroup` 路径及 `cmdline`。
- **🎨 现代化 UI**：基于 Material Design 3 构建，支持响应式布局（手机/平板双端适配），支持动态取色 (Dynamic Color)。

### 🛠️ 技术栈

- **语言**：Kotlin
- **UI 框架**：Jetpack Compose (Material 3)
- **高权限通信**：Shizuku API (Reflection for `newProcess`)
- **底层数据采集**：直接解析 Linux `/proc` 虚拟文件系统

### 📱 前置要求与使用

1. 你的设备需运行 Android 7.0 及以上版本。
2. 下载并安装 [Shizuku](https://shizuku.rikka.app/) 应用。
3. 通过 **无线调试** 或 **Root** 启动 Shizuku 服务。
4. 安装本项目编译的 APK，打开 App 并在**“设置”**页面授予 Shizuku 权限。
5. 前往“进程”或“性能”页面，即可查看实时数据。

### 🚀 构建指南

1. 克隆此仓库：`git clone https://github.com/Qingsource/Android-TaskManager.git`
2. 使用 Android Studio 打开项目。
3. 等待 Gradle 同步完成，直接点击 **Run** 即可。

---

## English Version

A hardcore Android Task Manager built with **Jetpack Compose (Material Design 3)** and the **Shizuku API**. Break through Android sandbox restrictions and access deep system processes and performance data—without requiring Root.

### ✨ Features

- **🔓 Sandbox Bypass**: Utilizes Shizuku to obtain ADB (Shell) privileges, allowing full access to system processes.
- **📊 Geek-Level Monitoring**: Real-time CPU utilization curve generated from `/proc/stat`, including CPU frequencies, cache sizes, and system uptime.
- **🗂️ In-depth Process Info**: Displays not just PID and name, but also UID, State, Session ID (NSsid), `cgroup` path, and `cmdline`.
- **🎨 Modern UI**: Built with Material Design 3, featuring responsive layouts (Phone/Tablet) and Dynamic Color support.

### 🛠️ Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material 3)
- **Privileged Communication**: Shizuku API (Reflection for `newProcess`)
- **Data Collection**: Direct parsing of Linux `/proc` virtual filesystem

### 📱 Prerequisites & Usage

1. Device running Android 7.0 or higher.
2. Download and install the [Shizuku](https://shizuku.rikka.app/) app.
3. Start the Shizuku service via **Wireless Debugging** or **Root**.
4. Install the APK built from this project. Open the app and grant Shizuku permission on the **"Settings"** page.
5. Navigate to the "Processes" or "Performance" page to view real-time data.

### 🚀 Build Guide

1. Clone this repository: `git clone https://github.com/Qingsource/Android-TaskManager.git`
2. Open the project in Android Studio.
3. Wait for Gradle sync to finish, then hit **Run**.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## ⭐ Support

If you find this project helpful, please give it a star! 
