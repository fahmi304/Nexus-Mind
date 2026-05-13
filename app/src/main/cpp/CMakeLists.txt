cmake_minimum_required(VERSION 3.22.1)
project("claudebridge")

set(LIBNODE "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so")

if(NOT EXISTS "${LIBNODE}")
    message(FATAL_ERROR
        "libnode.so not found at ${LIBNODE}\n"
        "Run: bash scripts/download-libnode.sh")
endif()

# ── Prebuilt libnode.so from nodejs-mobile release ─────────────────────────────
add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES IMPORTED_LOCATION "${LIBNODE}")

# ── JNI bridge: Kotlin calls NodeEngine.nativeStart(String[]) ─────────────────
add_library(nodebridge SHARED node_bridge.cpp)
target_link_libraries(nodebridge node android log)

# ── Standalone Node.js launcher ────────────────────────────────────────────────
# Named libnode-launcher.so so AGP packages it in lib/<abi>/ and Android
# extracts it to nativeLibraryDir where it can be executed (API 29+).
add_executable(node_launcher node_launcher.cpp)
target_link_libraries(node_launcher node)
set_target_properties(node_launcher PROPERTIES
    OUTPUT_NAME              "libnode-launcher"
    SUFFIX                   ".so"
    PREFIX                   ""
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")

# ── PTY helper ─────────────────────────────────────────────────────────────────
# Allocates a POSIX PTY and relays stdin/stdout so bridge.js can run
# interactive programs (python3 -i, ruby, vim, etc.) with a real TTY.
# Same lib*.so naming trick so Android packages and extracts it as an executable.
add_executable(pty_helper pty_helper.c)
set_target_properties(pty_helper PROPERTIES
    OUTPUT_NAME              "libpty-helper"
    SUFFIX                   ".so"
    PREFIX                   ""
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
