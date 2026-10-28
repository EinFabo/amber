# Lets whisper.cpp run on one persistent CPU thread pool that the JNI bridge owns, instead of
# creating and destroying a fresh set of threads for every graph - which is several hundred
# times per dictation, once per decoded token.
#
# Why it matters on Android: a brand-new thread has no load history, so the scheduler starts
# it on a small core at low clock and it never stays long enough to be moved up. In the app's
# own window Android boosts everything in the foreground app and hides that; through the
# bubble the process is only a bound service, gets no boost, and recognition was several times
# slower. Persistent threads also have stable ids, which is what a performance hint session
# (ADPF) needs to boost them. See whisper_jni.cpp.
#
# Applied as text replacement rather than a .patch file so it needs neither git nor patch on
# the build machine, and so a whisper.cpp upgrade that moves the anchor fails loudly here
# instead of silently building without the pool.

file(READ "${SRC}" content)

string(FIND "${content}" "whisper_amber_cpu_threadpool" already)
if(NOT already EQUAL -1)
    return()
endif()

set(decl_anchor "static std::vector<ggml_backend_t> whisper_backend_init(")
set(init_anchor "    ggml_backend_t backend_cpu = ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, nullptr);
    if (backend_cpu == nullptr) {
        throw std::runtime_error(\"failed to initialize CPU backend\");
    }
")

foreach(anchor IN ITEMS "${decl_anchor}" "${init_anchor}")
    string(FIND "${content}" "${anchor}" found)
    if(found EQUAL -1)
        message(FATAL_ERROR "patch-whisper.cmake: anchor not found in ${SRC} - update the patch for this whisper.cpp version")
    endif()
endforeach()

string(REPLACE "${decl_anchor}" "#include \"ggml-cpu.h\"

// Amber: set by the JNI bridge before a model is loaded; null keeps upstream behaviour.
extern \"C\" { ggml_threadpool_t whisper_amber_cpu_threadpool = nullptr; }

${decl_anchor}" content "${content}")

string(REPLACE "${init_anchor}" "${init_anchor}    if (whisper_amber_cpu_threadpool != nullptr) {
        ggml_backend_cpu_set_threadpool(backend_cpu, whisper_amber_cpu_threadpool);
    }
" content "${content}")

file(WRITE "${SRC}" "${content}")
message(STATUS "patch-whisper.cmake: whisper.cpp patched for a persistent CPU thread pool")
