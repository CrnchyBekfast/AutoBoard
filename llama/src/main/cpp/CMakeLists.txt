cmake_minimum_required(VERSION 3.22.1)
project(llama_jni CXX)

# llama.cpp is a git submodule at the repository root.
# CMakeLists.txt lives at llama/src/main/cpp/ — four levels up reaches the repo root.
get_filename_component(LLAMA_CPP_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp"
    ABSOLUTE)

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp submodule not found at ${LLAMA_CPP_DIR}. "
        "Run: git submodule update --init --recursive")
endif()

# Disable features unavailable or undesirable on Android
set(GGML_METAL        OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
set(GGML_BLAS         OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# Android Bionic lacks both POSIX_MADV_* constants and posix_madvise() itself
# until API 26. Write a force-included shim header that:
#   1. Defines the POSIX_MADV_* numeric constants (identical to Linux MADV_*)
#   2. Provides posix_madvise() as an inline wrapper around madvise()
# This must appear before add_subdirectory so the flag propagates to llama-mmap.cpp.
if(ANDROID)
    set(_SHIM "${CMAKE_BINARY_DIR}/android_posix_shim.h")
    file(WRITE "${_SHIM}"
        "#pragma once\n"
        "#if defined(__ANDROID__)\n"
        "#include <sys/mman.h>\n"
        "#ifndef POSIX_MADV_NORMAL\n"
        "#define POSIX_MADV_NORMAL    0\n"
        "#define POSIX_MADV_RANDOM    1\n"
        "#define POSIX_MADV_SEQUENTIAL 2\n"
        "#define POSIX_MADV_WILLNEED  3\n"
        "#define POSIX_MADV_DONTNEED  4\n"
        "#endif\n"
        "#if __ANDROID_API__ < 26\n"
        "static inline int posix_madvise(void* addr, size_t len, int advice) {\n"
        "    return madvise(addr, len, advice);\n"
        "}\n"
        "#endif\n"
        "#endif\n"
    )
    add_compile_options(-include "${_SHIM}")
endif()

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_BINARY_DIR}/llama_build EXCLUDE_FROM_ALL)

add_library(llama_jni SHARED llama_jni.cpp)

target_include_directories(llama_jni PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

target_link_libraries(llama_jni PRIVATE
    llama
    android
    log
)
