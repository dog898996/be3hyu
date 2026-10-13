cmake_minimum_required(VERSION 3.22.1)
project(hoyoung_ann LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_library(hoyoung_ann SHARED ann_jni.cpp)
target_compile_options(hoyoung_ann PRIVATE -O3 -Wall -Wextra)
# Android API 26 and both supported 64-bit ABIs. NDK r28 makes 16 KiB-safe ELF files.
target_include_directories(hoyoung_ann PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
