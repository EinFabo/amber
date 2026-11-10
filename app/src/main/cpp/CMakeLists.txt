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

# Everything is built from git submodules under third_party/ (passed in by app/build.gradle.kts):
# nothing is downloaded while configuring, so the build also runs without a network.
if(NOT DEFINED THIRD_PARTY_DIR OR THIRD_PARTY_DIR STREQUAL "")
    message(FATAL_ERROR "THIRD_PARTY_DIR is not set. Pass -DTHIRD_PARTY_DIR=<repo>/third_party")
endif()

# Used for every submodule: an empty directory means the clone was made without --recursive.
function(amber_require_submodule dir)
    if(NOT EXISTS "${THIRD_PARTY_DIR}/${dir}/CMakeLists.txt")
        message(FATAL_ERROR "third_party/${dir} is empty. Run: git submodule update --init --recursive")
    endif()
endfunction()

include(FetchContent)
set(FETCHCONTENT_FULLY_DISCONNECTED ON)

amber_require_submodule(whisper.cpp)

# The submodule stays untouched: the patch for the persistent CPU thread pool is applied to a
# copy in the build directory, and FetchContent is pointed at that copy.
set(whisper_copy "${CMAKE_BINARY_DIR}/whisper-src")
file(REMOVE_RECURSE "${whisper_copy}")
file(COPY "${THIRD_PARTY_DIR}/whisper.cpp/" DESTINATION "${whisper_copy}" PATTERN ".git" EXCLUDE)
execute_process(
    COMMAND ${CMAKE_COMMAND} -DSRC=${whisper_copy}/src/whisper.cpp -P ${CMAKE_CURRENT_LIST_DIR}/patch-whisper.cmake
    RESULT_VARIABLE patch_result
)
if(NOT patch_result EQUAL 0)
    message(FATAL_ERROR "patch-whisper.cmake failed (${patch_result}) on ${whisper_copy}")
endif()
set(FETCHCONTENT_SOURCE_DIR_WHISPER "${whisper_copy}" CACHE PATH "" FORCE)
FetchContent_Declare(whisper SOURCE_DIR "${whisper_copy}")

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

# ggml logs and asserts with __FILE__; without this the library carries the absolute paths of
# the machine it was built on.
add_compile_options(
    -ffile-prefix-map=${CMAKE_BINARY_DIR}=build
    -ffile-prefix-map=${whisper_copy}=third_party/whisper.cpp
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
)

FetchContent_MakeAvailable(whisper)

add_library(amethyst_whisper SHARED whisper_jni.cpp)
target_link_libraries(amethyst_whisper PRIVATE whisper log)
target_compile_options(amethyst_whisper PRIVATE -O3 -fvisibility=hidden)
# 16 KB segment alignment: loads on devices with 16 KB memory pages as well as 4 KB ones.
target_link_options(amethyst_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
