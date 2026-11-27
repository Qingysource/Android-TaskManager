cmake_minimum_required(VERSION 3.22)

# 只编 C：后端是纯 C（无 STL / 无 libc++），所以项目语言只声明 C，
# 一旦有人误把 C++ 源文件塞进 add_executable，配置阶段就会直接报错。
project(TaskManagerBackend LANGUAGES C)

# APK Native Library 打包目录
set(BACKEND_OUTPUT_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
)

file(MAKE_DIRECTORY "${BACKEND_OUTPUT_DIR}")

# 后端源文件清单。组织方式见 Backend/Header.h：
#   main.c          服务实例入口 / 消息循环 / 命令分发
#   Protocol.c      协议层（状态映射、字段输出、应答包）
#   Common.c        公共文本与数字工具
#   ProcessSnapshot.c  进程快照采集与增量结算
#   DeviceSnapshot.c   设备快照装配（注册表 + 各类设备组装）
#   DeviceExtendedSnapshot.c  设备扩展快照装配（typeCode=6，按集群 / 逐核）
#   CPUDevice.c 等  各具体设备模块
#   CPUExtended.c   CPU 扩展采集（集群分组与逐核频率 / 利用率）
#
# 文件名一律英文，中文只出现在注释里：这些路径要经过 CMake、AGP 与 Gradle
# 三层脚本转发，中文名在不同平台的编码上容易出岔子。
add_executable(
    task_backend
    Backend/main.c
    Backend/Protocol.c
    Backend/Common.c
    Backend/ProcessSnapshot.c
    Backend/ProcessControl.c
    Backend/DeviceSnapshot.c
    Backend/DeviceExtendedSnapshot.c
    Backend/CPUDevice.c
    Backend/CPUExtended.c
    Backend/MemoryDevice.c
    Backend/Disk.c
    Backend/GPUDevice.c
    Backend/NetworkDevice.c
    Backend/SystemDevice.c
)

# 输出为 libtask_backend.so
# 关键技巧：把可执行文件命名成 libXXX.so，AGP 才会把它当作依赖库原样打包；
# 运行时由 LinuxService 用 linker64 直接执行，不会按 .so 动态加载。
set_target_properties(task_backend PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${BACKEND_OUTPUT_DIR}"
    OUTPUT_NAME "libtask_backend"
    SUFFIX ".so"
)

# C11 即可（用到的只有 stdio / string / unistd 这些最基础的东西）
target_compile_features(task_backend PRIVATE c_std_11)

# 体积优先：这个后端只做"读一行、分发、写一句"，不需要调试符号。
# 想用 gdb / addr2line 看调用栈时，把 -g0 去掉即可（体积会回到 MB 级）。
# 模块拆分后不再加 -flto：实测（同一套 flag、arm64）单文件 97,496 字节、
# 拆分后 106,600 字节，LTO 只省 40 字节，不值得让链接期多一层复杂度。
target_compile_options(task_backend PRIVATE -Os -g0 -Wall -Wextra)

# 链接期丢掉没用到的段，进一步压缩 ELF
target_link_options(task_backend PRIVATE -Wl,--gc-sections)

# 显卡那一节用 dlopen 查 Vulkan 版本，Android 上 dlopen 在 libdl 里。
target_link_libraries(task_backend dl)
