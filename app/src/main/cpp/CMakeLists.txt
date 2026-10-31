cmake_minimum_required(VERSION 3.22.1)

# Whisper runs far too slowly unoptimised, so always build it as Release, even in the debug APK.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(clipwhisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# whisper and ggml are built as static libraries and linked into one shared library.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

# Plain CPU build that runs on every phone: no OpenMP runtime to package, no CPU-specific tuning.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

if (DEFINED WHISPER_SOURCE_DIR)
    # Used for local test builds against an existing checkout.
    FetchContent_Declare(whisper SOURCE_DIR ${WHISPER_SOURCE_DIR})
else ()
    FetchContent_Declare(whisper
        URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz)
endif ()
FetchContent_MakeAvailable(whisper)

add_library(clipwhisper SHARED whisper_jni.c)
target_link_libraries(clipwhisper PRIVATE whisper)

if (ANDROID)
    # 16 KB page alignment for newer Android devices.
    target_link_options(clipwhisper PRIVATE "-Wl,-z,max-page-size=16384")
endif ()
