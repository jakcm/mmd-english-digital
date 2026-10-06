cmake_minimum_required(VERSION 3.20)
project(ten_vad_jni C)

# 预编译的 libten_vad.so（arm64-v8a，532KB）已放在 app/src/main/jniLibs/ 下，
# 由 Android 打包并安装到 lib/ 目录；这里只编译 JNI 桥并链接它。
add_library(ten_vad_jni SHARED ten_vad_jni.c)
target_include_directories(ten_vad_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# 直接按 ABI 目录指定预编译库（CMAKE_CURRENT_SOURCE_DIR = app/src/main/cpp）
set(TEN_VAD_PREBUILT "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libten_vad.so")
if(EXISTS "${TEN_VAD_PREBUILT}")
    message(STATUS "TEN VAD prebuilt: ${TEN_VAD_PREBUILT}")
    target_link_libraries(ten_vad_jni "${TEN_VAD_PREBUILT}" log)
else()
    message(FATAL_ERROR "TEN VAD prebuilt not found: ${TEN_VAD_PREBUILT}")
endif()
