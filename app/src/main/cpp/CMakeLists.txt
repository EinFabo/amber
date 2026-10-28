cmake_minimum_required(VERSION 3.22.1)

# Always optimised, also in the debug APK: unoptimised ggml is many times slower, which would
# make every test dictation on a debug build misleadingly slow. Debugging happens in Kotlin.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(amethyst_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)

# Ninja otherwise runs one compiler per core. ggml's translation units need several hundred MB
# each, and twenty of them at once exhausted a build machine with 8 GB.
set_property(GLOBAL PROPERTY JOB_POOLS amethyst_compile=4 amethyst_link=1)
set(CMAKE_JOB_POOL_COMPILE amethyst_compile)
set(CMAKE_JOB_POOL_LINK amethyst_link)

# whisper.cpp is fetched at a pinned release and checked against its hash rather than vendored:
# the source tree is ~10 MB and would drown the repository. To build offline, pass
# -DFETCHCONTENT_SOURCE_DIR_WHISPER=/path/to/whisper.cpp-1.9.5 (then apply patch-whisper.cmake
# to that tree yourself: FetchContent skips the patch step for a provided source dir).
include(FetchContent)
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
    PATCH_COMMAND ${CMAKE_COMMAND} -DSRC=<SOURCE_DIR>/src/whisper.cpp -P ${CMAKE_CURRENT_LIST_DIR}/patch-whisper.cmake
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
# No OpenMP: ggml's own thread pool avoids shipping libomp.so, and dictation runs one
# inference at a time anyway.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Cross-compiling, so "native" would describe the build machine. armv8.2-a with fp16 and
# dotprod covers every arm64 phone since about 2018 and is what makes q8_0 models fast.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)

FetchContent_MakeAvailable(whisper)

add_library(amethyst_whisper SHARED whisper_jni.cpp)
target_link_libraries(amethyst_whisper PRIVATE whisper log)
target_compile_options(amethyst_whisper PRIVATE -O3 -fvisibility=hidden)
target_link_options(amethyst_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
