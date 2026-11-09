cmake_minimum_required(VERSION 3.22.1)
project("llama-android")

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build common library" FORCE)

# Ensure Snapdragon 685 (ARMv8.0-A Cortex-A73) compatibility:
# No ARMv8.2-A dotprod or SVE; use standard ARMv8-A NEON
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "Disable llamafile" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/llama.cpp build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
    llama-android.cpp
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    PRIVATE
    llama
    common
    android
    log
)
