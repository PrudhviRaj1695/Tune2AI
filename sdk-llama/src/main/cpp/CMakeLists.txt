cmake_minimum_required(VERSION 3.22.1)
project(tune2ai_llama C CXX)

# Build only the llama library from the pinned submodule (see .gitmodules).
set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE) # cross-compiling: never tune for the build machine
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)
# ponytail: generic CPU baseline per ABI; add arm64 dotprod/i8mm variants when tuning real-device speed.

add_subdirectory(llama.cpp)

add_library(tune2ai_llama SHARED llama_jni.cpp)
target_link_libraries(tune2ai_llama PRIVATE llama log)
