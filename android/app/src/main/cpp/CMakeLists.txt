cmake_minimum_required(VERSION 3.18.1)
project(ggwavejni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ggwave (MIT) — передача данных звуком; лежит в cpp/ggwave целиком, без загрузок из сети
add_library(ggwavejni SHARED
    ggwave_jni.cpp
    ggwave/src/ggwave.cpp)

target_include_directories(ggwavejni PRIVATE ggwave/include ggwave/src)
target_link_libraries(ggwavejni log)
