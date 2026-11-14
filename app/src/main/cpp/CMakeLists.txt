cmake_minimum_required(VERSION 3.22)

# 只编 C：后端是纯 C（无 STL / 无 libc++），所以项目语言只声明 C，
# 一旦有人误把 C++ 源文件塞进 add_executable，配置阶段就会直接报错。
project(TaskManagerBackend LANGUAGES C)

# APK Native Library 打包目录
set(BACKEND_OUTPUT_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
)

file(MAKE_DIRECTORY "${BACKEND_OUTPUT_DIR}")

# 创建独立 ELF 可执行文件（纯 C 源文件，见 Backend/main.c）
add_executable(
    task_backend
    Backend/main.c
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
target_compile_options(task_backend PRIVATE -Os -g0 -Wall -Wextra)

# 链接期丢掉没用到的段，进一步压缩 ELF
target_link_options(task_backend PRIVATE -Wl,--gc-sections)

# 预留：新增后端源文件时，在此扩展 add_executable 的源文件列表与链接库。
