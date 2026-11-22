cmake_minimum_required(VERSION 3.22.1)
project(tune2ai_llama C CXX)

# Build only the llama library from the pinned submodule (see .gitmodules).
# Backends (CPU, Vulkan, OpenCL, Hexagon) are separate .so files that llama_jni.cpp loads at start-up
# (GGML_BACKEND_DL). A backend whose driver library is missing on a phone simply does not load, so the
# app never fails to start because of a GPU. This needs shared libraries and the shared C++ runtime.
set(BUILD_SHARED_LIBS   ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL     ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE) # cross-compiling: never tune for the build machine
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)
# ponytail: generic CPU baseline per ABI; add arm64 dotprod/i8mm variants when tuning real-device speed.

# GPU: OpenCL is llama.cpp's tuned path for Qualcomm Adreno; Vulkan covers other GPUs (Mali, PowerVR,
# emulator). At run time each is tried with a warm-up decode and the JNI layer falls back to CPU.
# NPU: Qualcomm Hexagon needs the proprietary Hexagon SDK, so it is opt-in (-PTUNE2AI_HEXAGON_SDK=<path>).
option(TUNE2AI_VULKAN "Build the Vulkan GPU backend" ON)
if (TUNE2AI_VULKAN)
    set(_vk_headers ${CMAKE_CURRENT_SOURCE_DIR}/third_party/Vulkan-Headers/include)
    set(_spirv_headers ${CMAKE_CURRENT_SOURCE_DIR}/third_party/SPIRV-Headers/include)
    # The NDK has the Vulkan loader and glslc but not vulkan.hpp, so the headers come from a pinned submodule.
    file(GLOB _glslc "${CMAKE_ANDROID_NDK}/shader-tools/*/glslc")
    if (NOT _glslc OR NOT EXISTS ${_vk_headers}/vulkan/vulkan.hpp)
        message(FATAL_ERROR "Vulkan build needs the NDK glslc and the Vulkan-Headers submodule "
                            "(git submodule update --init --recursive), or build with -PTUNE2AI_VULKAN=false")
    endif()
    list(GET _glslc 0 _glslc)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE ${_glslc} CACHE FILEPATH "" FORCE)
    set(Vulkan_INCLUDE_DIR ${_vk_headers} CACHE PATH "" FORCE)
    # Vulkan 1.1 entry points (vkGetPhysicalDeviceFeatures2) are only in the NDK's libvulkan stub from API 28.
    # The app still installs on API 26, where the Kotlin side never starts the GPU path.
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        set(_vk_triple aarch64-linux-android)
    else()
        set(_vk_triple x86_64-linux-android)
    endif()
    set(Vulkan_LIBRARY ${CMAKE_SYSROOT}/usr/lib/${_vk_triple}/28/libvulkan.so CACHE FILEPATH "" FORCE)
    # ggml wants find_package(SPIRV-Headers CONFIG); give it a stub config pointing at the submodule.
    set(_spirv_cfg ${CMAKE_BINARY_DIR}/spirv-headers-config)
    file(WRITE ${_spirv_cfg}/SPIRV-HeadersConfig.cmake
"add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES \"${_spirv_headers}\")
")
    set(SPIRV-Headers_DIR ${_spirv_cfg} CACHE PATH "" FORCE)
    # vulkan-shaders-gen runs on the build machine, not the phone. Its nested CMake reuses Gradle's ninja,
    # which is not on PATH, so hand it a host toolchain file that names ninja and the host compilers.
    # The PATH here holds the NDK clang (an Android cross compiler), so look in the system dirs first.
    find_program(tune2ai_host_cc NAMES cc gcc clang PATHS /usr/bin /usr/local/bin NO_DEFAULT_PATH REQUIRED)
    find_program(tune2ai_host_cxx NAMES c++ g++ clang++ PATHS /usr/bin /usr/local/bin NO_DEFAULT_PATH REQUIRED)
    set(_host_tc ${CMAKE_BINARY_DIR}/tune2ai-host-toolchain.cmake)
    file(WRITE ${_host_tc}
"set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_FLAGS -O2)
set(CMAKE_CXX_FLAGS -O2)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)
set(CMAKE_C_COMPILER \"${tune2ai_host_cc}\")
set(CMAKE_CXX_COMPILER \"${tune2ai_host_cxx}\")
set(CMAKE_MAKE_PROGRAM \"${CMAKE_MAKE_PROGRAM}\" CACHE FILEPATH \"\" FORCE)
")
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN ${_host_tc} CACHE FILEPATH "" FORCE)
endif()

option(TUNE2AI_OPENCL "Build the OpenCL GPU backend (Adreno)" ON)
if (TUNE2AI_OPENCL AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(_cl_headers ${CMAKE_CURRENT_SOURCE_DIR}/third_party/OpenCL-Headers)
    if (NOT EXISTS ${_cl_headers}/CL/cl.h)
        message(FATAL_ERROR "OpenCL build needs the OpenCL-Headers submodule "
                            "(git submodule update --init --recursive), or build with -PTUNE2AI_OPENCL=false")
    endif()
    # The Khronos ICD loader is built only to link against. It is excluded from the APK (sdk-llama/build.gradle),
    # so at run time libggml-opencl.so binds to the phone's own /vendor libOpenCL.so.
    set(OPENCL_ICD_LOADER_HEADERS_DIR ${_cl_headers} CACHE PATH "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(ENABLE_OPENCL_LAYERS OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    add_subdirectory(third_party/OpenCL-ICD-Loader EXCLUDE_FROM_ALL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(OpenCL_INCLUDE_DIR ${_cl_headers} CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE) # the loader target above
endif()

if (TUNE2AI_HEXAGON_SDK)
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    set(HEXAGON_SDK_ROOT ${TUNE2AI_HEXAGON_SDK} CACHE PATH "" FORCE)
endif()

add_subdirectory(llama.cpp)

add_library(tune2ai_llama SHARED llama_jni.cpp)
if (TUNE2AI_HEXAGON_SDK)
    target_compile_definitions(tune2ai_llama PRIVATE TUNE2AI_HAS_HEXAGON=1)
endif()
target_link_libraries(tune2ai_llama PRIVATE llama log)
