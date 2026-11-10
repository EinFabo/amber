cmake_minimum_required(VERSION 3.22.1)

# Always optimised, also in the debug APK: unoptimised ggml is many times slower, which would
# make every test dictation on a debug build misleadingly slow. Debugging happens in Kotlin.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(amethyst_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)

# Ninja otherwise runs one compiler per core. ggml's translation units need several hundred MB
# each, and twenty of them at once exhausted a build machine with 8 GB. The same holds for
# sherpa-onnx's Eigen and OpenFst units.
set_property(GLOBAL PROPERTY JOB_POOLS amethyst_compile=4 amethyst_link=1)
set(CMAKE_JOB_POOL_COMPILE amethyst_compile)
set(CMAKE_JOB_POOL_LINK amethyst_link)

# Everything is built from git submodules under third_party/ (passed in by app/build.gradle.kts):
# nothing is downloaded while configuring, so the build also runs without a network.
if(NOT DEFINED THIRD_PARTY_DIR OR THIRD_PARTY_DIR STREQUAL "")
    message(FATAL_ERROR "THIRD_PARTY_DIR is not set. Pass -DTHIRD_PARTY_DIR=<repo>/third_party")
endif()
# ONNX Runtime's headers and libonnxruntime.so, unpacked by app/build.gradle.kts from the Maven
# AAR that also puts the library into the APK.
if(NOT DEFINED ORT_DIR OR NOT EXISTS "${ORT_DIR}/headers/onnxruntime_cxx_api.h")
    message(FATAL_ERROR "ORT_DIR does not hold ONNX Runtime's headers. Build through Gradle (extractOnnxRuntime).")
endif()

# Used for every submodule: an empty directory means the clone was made without --recursive.
function(amber_require_submodule dir)
    if(NOT EXISTS "${THIRD_PARTY_DIR}/${dir}/CMakeLists.txt")
        message(FATAL_ERROR "third_party/${dir} is empty. Run: git submodule update --init --recursive")
    endif()
endfunction()

# amber_build_copy(<dir> <out_var> [PATCH <script> <file>])
#
# Copies third_party/<dir> into the build directory and returns the copy's path in <out_var>,
# for a submodule whose sources get changed while configuring: by our own patch (PATCH runs the
# CMake script with -DSRC=<copy>/<file>), or by sherpa-onnx itself. The submodule's working tree
# stays at its pinned commit.
#
# The copy is only refreshed when CMake configures, so every input is a configure dependency: a
# submodule bump or local edit, and the patch script. __FILE__ strings in the copy (asserts,
# logs) are mapped back to third_party/<dir>, the same in every checkout; clang applies the
# longest matching prefix, so this map wins over the ones for the build and repository roots.
function(amber_build_copy dir out_var)
    cmake_parse_arguments(PARSE_ARGV 2 arg "" "" "PATCH")
    amber_require_submodule(${dir})
    # The real path: file(COPY) copies a symlinked directory as the link itself, and the patch
    # would then land in the submodule.
    get_filename_component(src "${THIRD_PARTY_DIR}/${dir}" REALPATH)
    set(copy "${CMAKE_BINARY_DIR}/${dir}-src")
    file(GLOB_RECURSE inputs CONFIGURE_DEPENDS LIST_DIRECTORIES false "${src}/*")
    list(FILTER inputs EXCLUDE REGEX "/\\.git(/|$)")
    set(patch_script "")
    if(arg_PATCH)
        list(GET arg_PATCH 0 patch_script)
        list(GET arg_PATCH 1 patch_file)
    endif()
    set_property(DIRECTORY "${CMAKE_SOURCE_DIR}" APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${inputs} ${patch_script})
    file(REMOVE_RECURSE "${copy}")
    file(COPY "${src}/" DESTINATION "${copy}" PATTERN ".git" EXCLUDE)
    if(patch_script)
        execute_process(
            COMMAND ${CMAKE_COMMAND} -DSRC=${copy}/${patch_file} -P ${patch_script}
            RESULT_VARIABLE patch_result
        )
        if(NOT patch_result EQUAL 0)
            message(FATAL_ERROR "${patch_script} failed (${patch_result}) on ${copy}/${patch_file}")
        endif()
    endif()
    add_compile_options(-ffile-prefix-map=${copy}=third_party/${dir})
    set(${out_var} "${copy}" PARENT_SCOPE)
endfunction()

include(FetchContent)
set(FETCHCONTENT_FULLY_DISCONNECTED ON)

# ggml and sherpa-onnx log and assert with __FILE__; without these the libraries carry the
# absolute paths of the machine they were built on (app/build.gradle.kts maps the repository
# root the same way).
add_compile_options(
    -ffile-prefix-map=${CMAKE_BINARY_DIR}=build
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# --- whisper.cpp -----------------------------------------------------------------------------

# The patch for the persistent CPU thread pool goes into a copy in the build directory, and
# FetchContent is pointed at that copy.
amber_build_copy(whisper.cpp whisper_copy PATCH ${CMAKE_CURRENT_LIST_DIR}/patch-whisper.cmake src/whisper.cpp)
set(FETCHCONTENT_SOURCE_DIR_WHISPER "${whisper_copy}" CACHE PATH "" FORCE)
FetchContent_Declare(whisper SOURCE_DIR "${whisper_copy}")

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
# 16 KB segment alignment: loads on devices with 16 KB memory pages as well as 4 KB ones.
target_link_options(amethyst_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)

# --- sherpa-onnx -----------------------------------------------------------------------------
#
# libsherpa-onnx-jni.so for the Parakeet engine: sherpa-onnx v1.13.8 and every library it
# compiles in, statically, with text-to-speech off - that drops espeak-ng (GPL) and piper. It
# links dynamically against libonnxruntime.so from the Maven AAR, the same file the APK ships.
#
# sherpa-onnx fetches its dependencies with FetchContent; each one is pointed at its submodule,
# pinned to the tag or commit that sherpa-onnx v1.13.8 (or the dependency pulling it in) names:
#
#   FetchContent name     third_party/          pinned to           named in
#   kaldi_native_fbank    kaldi-native-fbank    v1.22.3             sherpa-onnx cmake/kaldi-native-fbank.cmake
#   kissfft               kissfft               febd4caeed32e33ad8b2e0bb5ea77542c40f18ec
#                                                                   kaldi-native-fbank cmake/kissfft.cmake
#   kaldi_decoder         kaldi-decoder         v0.3.0              sherpa-onnx cmake/kaldi-decoder.cmake
#   kaldifst              kaldifst              v1.8.0              kaldi-decoder cmake/kaldifst.cmake
#   openfst               openfst               v1.8.5-2026-07-09   sherpa-onnx cmake/openfst.cmake
#   eigen                 eigen                 5.0.1               sherpa-onnx cmake/eigen.cmake
#   simple-sentencepiece  simple-sentencepiece  v0.7                sherpa-onnx cmake/simple-sentencepiece.cmake
#   json                  json                  v3.12.0             sherpa-onnx cmake/json.cmake
#
# (kaldi-decoder and kaldifst carry their own eigen.cmake/openfst.cmake with older versions;
# sherpa-onnx's cmake/ comes first on CMAKE_MODULE_PATH, so its versions are the ones used.)
# A dependency missing here fails the configure step (FETCHCONTENT_FULLY_DISCONNECTED) instead
# of being downloaded.

amber_require_submodule(sherpa-onnx)

# sherpa-onnx's cmake/onnxruntime.cmake takes ONNX Runtime from these instead of downloading it.
set(ENV{SHERPA_ONNXRUNTIME_INCLUDE_DIR} "${ORT_DIR}/headers")
set(ENV{SHERPA_ONNXRUNTIME_LIB_DIR} "${ORT_DIR}/jni/${ANDROID_ABI}")

foreach(dep
        KALDI_NATIVE_FBANK=kaldi-native-fbank
        KISSFFT=kissfft
        KALDI_DECODER=kaldi-decoder
        KALDIFST=kaldifst
        OPENFST=openfst
        EIGEN=eigen
        JSON=json)
    string(REPLACE "=" ";" pair "${dep}")
    list(GET pair 0 name)
    list(GET pair 1 dir)
    amber_require_submodule(${dir})
    set(FETCHCONTENT_SOURCE_DIR_${name} "${THIRD_PARTY_DIR}/${dir}" CACHE PATH "" FORCE)
endforeach()

# On Android, sherpa-onnx's cmake/simple-sentencepiece.cmake rewrites three of its sources (it
# strips the thread pool), so it gets a copy.
amber_build_copy(simple-sentencepiece sentencepiece_copy)
set(FETCHCONTENT_SOURCE_DIR_SIMPLE-SENTENCEPIECE "${sentencepiece_copy}" CACHE PATH "" FORCE)

# Speech recognition through JNI only: no text-to-speech, speaker diarization, C API, tools,
# servers, Python or tests.
foreach(opt TTS SPEAKER_DIARIZATION C_API BINARY WEBSOCKET PORTAUDIO PYTHON TESTS CHECK)
    set(SHERPA_ONNX_ENABLE_${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(SHERPA_ONNX_ENABLE_JNI ON CACHE BOOL "" FORCE)

add_subdirectory("${THIRD_PARTY_DIR}/sherpa-onnx" sherpa-onnx)
