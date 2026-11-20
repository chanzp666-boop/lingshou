cmake_minimum_required(VERSION 3.22.1)
project("lsguard" LANGUAGES CXX)

# 双进程守护 native 层
# 编译为 liblsguard.so，由 com.byd.ls.guard.DaemonGuard 通过 JNI 调用
add_library(lsguard SHARED
        lsguard.cpp)

target_compile_options(lsguard PRIVATE
        -Wall
        -Wno-unused-parameter
        -fno-exceptions
        -fno-rtti)

target_link_libraries(lsguard
        android
        log)
