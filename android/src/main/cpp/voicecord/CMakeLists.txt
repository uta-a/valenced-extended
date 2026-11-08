cmake_minimum_required(VERSION 3.22)
project(voicecord LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ShadowHook は prefab（com.bytedance.android:shadowhook）から取り込む。
find_package(shadowhook REQUIRED CONFIG)

add_library(voicecord SHARED hook.cpp)
target_link_libraries(voicecord shadowhook::shadowhook log)
target_compile_options(voicecord PRIVATE -Wall -O2 -fvisibility=hidden)
