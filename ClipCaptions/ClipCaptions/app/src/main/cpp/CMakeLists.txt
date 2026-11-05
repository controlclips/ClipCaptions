cmake_minimum_required(VERSION 3.22.1)

# Whisper runs far too slowly unoptimised, so always build it as Release, even in the debug APK.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(clipwhisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# whisper and ggml are built as static libraries and linked into one shared library.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Two builds share this file:
# - clipwhisper:      runs on every phone (the app module).
# - clipwhisper_fast: uses the dot-product and half-precision instructions that 64-bit phones
#                     from about 2018 onwards have (the whisperfast module). Roughly twice as fast;
#                     the app only loads it after checking the phone's processor supports it.
option(CLIP_FAST_ARM "Build the faster variant for ARMv8.2 phones" OFF)
if (CLIP_FAST_ARM)
    set(CLIP_LIBRARY clipwhisper_fast)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
else ()
    set(CLIP_LIBRARY clipwhisper)
endif ()

include(FetchContent)

# Plain CPU build: no OpenMP runtime to package, no tuning for the build machine.
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

add_library(${CLIP_LIBRARY} SHARED ${CMAKE_CURRENT_LIST_DIR}/whisper_jni.c)
target_link_libraries(${CLIP_LIBRARY} PRIVATE whisper)

if (ANDROID)
    # 16 KB page alignment for newer Android devices.
    target_link_options(${CLIP_LIBRARY} PRIVATE "-Wl,-z,max-page-size=16384")
endif ()
