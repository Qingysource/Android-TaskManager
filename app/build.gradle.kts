plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.android.taskmanager"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.android.taskmanager"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                // 注意：不要在这里设置 packageScope。
                // packageScope 会启用 R8 的 gradual shrinking（实验特性），
                // 只对列出的包做重命名/合并，跨包继承时会在运行时抛
                // IllegalAccessError（Class kotlin.collections.b extended by class yc is inaccessible）。
                // 不设置时默认作用域是 "**"，即标准 R8 全量优化。
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    /*
     * 没有这一段，Android Studio 的"一键构建"会直接失败：
     *
     *   Could not create task ':app:generateDebugComposePreviewRunfiles'.
     *
     * 这个 task 是 Compose Preview 的支撑任务，它在创建时就要拿"单元测试的 Android 资源"
     * （R 表 / 资源 jar）作为输入。默认 testOptions 不生成这些资源，于是 task 创建阶段
     * 就抛错 —— 而 Android Studio 点 Build 时会**实例化全部 task**（命令行只实例化实际要跑的），
     * 所以命令行构建正常、Studio 却 2~3 秒内 BUILD FAILED。
     *
     * 打开 isIncludeAndroidResources 后单元测试变体也会生成资源，task 能正常创建。
     */
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

/*
 * 关于 Android Studio"一键构建"在 2~3 秒内 BUILD FAILED 的两个根因（都已修，勿回退）：
 *
 * 1) generate<变体>ComposePreviewRunfiles —— AGP 只要见到 buildFeatures.compose = true，就会给
 *    **每一个变体**注册这个 Compose Preview 的支撑 task；而它的 CreationAction 在"配置阶段"
 *    就要求该变体存在单元测试组件（Preview 要用 unit test 资源 APK 喂 Layoutlib）。
 *    命令行构建只实例化真正要跑的那个变体，所以命令行一直正常、Studio 点 Build 必崩。
 *    → 修法：gradle.properties 里 android.onlyEnableUnitTestForTheTestedBuildType=false，
 *      让所有变体（含 release）都带单元测试组件；再配合下面的 isIncludeAndroidResources。
 *
 * 2) AAPT2 / CMake daemon 启动失败 —— 是 Gradle daemon 把提交内存吃满后子进程起不来，
 *    不是代码问题。构建完执行 gradlew.bat --stop 即可。
 */
dependencies {
    // Compose BOM：统一管理 Compose 组件版本
    implementation(platform(libs.androidx.compose.bom))

    // Activity Compose
    implementation(libs.androidx.activity.compose)

    // Compose UI
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Material 3
    implementation(libs.androidx.compose.material3)

    // Material Icons
    implementation(libs.androidx.compose.material.icons.extended)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Compose Preview
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // 单元测试 / 仪器测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}

// elf.so相关构建,这是服务 Linux 侧数据获取的方法
android {
    defaultConfig {
        ndk {
            // 真机（arm64）+ x86_64 模拟器都要能装，两种 ABI 的 native 库都会打进 APK
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                targets += "task_backend"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            // 使用提取式打包
            useLegacyPackaging = true

            // 避免 Gradle 对我们的 ELF 进行调试符号剥离
            keepDebugSymbols += "**/libtask_backend.so"
        }
    }
}

// Shizuku 支持
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // IShizukuService / IRemoteProcess 的 AIDL 桩：Shizuku 13.x 把 newProcess 收进了
    // 服务内部的 AIDL，公开的 Shizuku 类里已经没有起进程的入口，只能从这一层拿远程进程
    // （详见 ShizukuManager.newShellProcess 的注释）。
    implementation("dev.rikka.shizuku:aidl:13.1.5")
}

/*
 * 关于 ELF 的打包顺序 —— 改 C 代码时必读。
 *
 * 本项目的 ELF 由 CMake 直接输出到 src/main/jniLibs/<abi>/（见 cpp/CMakeLists.txt），
 * 而 AGP 的 merge*JniLibFolders 把 jniLibs 当"静态输入"，它在一次构建的早期就按
 * **上一次构建结束时**的内容做了快照。于是本次 CMake 刚写进 jniLibs 的新 ELF，
 * 要等到"再下一次构建"才会被拷进 APK。
 *
 * 现象（很容易误判成代码没生效）：只改 C 源码时，APK 里的 libtask_backend.so 仍是旧版，
 * 真机跑的还是旧逻辑；Kotlin 侧改动却都正常。
 *
 * 做法：改完 C 源码后**连续构建两次**（第二次 mergeDebugJniLibFolders 才会重跑）；
 *      或先删 app/build/intermediates/merged_native_libs、stripped_native_libs 再构建。
 *      验证办法：比对 APK 内 lib/<abi>/libtask_backend.so 与 jniLibs 下同名文件的哈希。
 */

/*
 * 关于命令行构建与 Android Studio 共存 —— 用脚本构建时必读。
 *
 * 症状：命令行 `gradlew assembleDebug` 成功之后，回到 Android Studio 点 Build，
 *      2~3 秒内直接 BUILD FAILED，报两处：
 *        :app:processDebugResources  AAPT2 ... Daemon startup failed
 *                                    IOException: Process unexpectedly exit
 *        :app:buildCMakeDebug[abi]   ProcessException: C++ build system [build] failed
 *      两者都没有任何子进程的 stderr —— 因为 aapt2.exe / ninja.exe 根本没起来。
 *
 * 原因不是代码，是 **Gradle daemon 残留吃掉了提交内存**：
 *      - org.gradle.jvmargs 是 -Xmx2048m（见根目录 gradle.properties），
 *        命令行构建会留下一个常驻 daemon（实测驻留约 0.9~1.1 GB），
 *        Studio 自己还有一个；两个 daemon 加起来 ≈ 2 GB；
 *      - 本机 16 GB 物理 + 13 GB pagefile，提交上限约 29.5 GB，
 *        其余进程（Studio 约 3 GB、系统与工具）把已提交推到 26 GB 以上；
 *      - Studio 再构建时要额外起 Kotlin daemon / worker / aapt2 daemon ×2 /
 *        ninja ×2，CreateProcess 在提交内存到顶时直接失败，
 *        于是两个 native 任务在同一轮里同时"启动即退出"。
 *
 * 做法（脚本 / 命令行构建收尾必须执行）：
 *      gradlew.bat --stop
 *      实测能释放约 2.4 GB 提交内存（26.5 GB -> 24.1 GB，物理空闲 0.8 GB -> 2.9 GB）。
 *      之后再从 Studio 构建即可恢复正常。
 *
 * 不要做的：靠加大 -Xmx 或开 org.gradle.parallel 来"解决"——那只会让峰值更高。
 */