cmake_minimum_required(VERSION 3.22)
project(llama_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")

add_library(llamabridge SHARED
        ${CMAKE_SOURCE_DIR}/llamabridge.cpp
)

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so"
)

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so"
)

find_library(log_lib log REQUIRED)
find_library(cpp_shared c++_shared REQUIRED)

get_filename_component(LLVM_BIN_DIR "${CMAKE_C_COMPILER}" DIRECTORY)
get_filename_component(LLVM_TOOLCHAIN_ROOT "${LLVM_BIN_DIR}" DIRECTORY)

if(NOT LLVM_TOOLCHAIN_ROOT)
    message(FATAL_ERROR "Unable to resolve LLVM toolchain root from compiler path '${CMAKE_C_COMPILER}'")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(OMP_ARCH_SUBDIR "aarch64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(OMP_ARCH_SUBDIR "arm")
elseif(ANDROID_ABI STREQUAL "x86")
    set(OMP_ARCH_SUBDIR "i686")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(OMP_ARCH_SUBDIR "x86_64")
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI '${ANDROID_ABI}' for locating libomp.so")
endif()

set(OMP_LIB_PATH "${LLVM_TOOLCHAIN_ROOT}/lib64/clang/${CMAKE_CXX_COMPILER_VERSION}/lib/linux/${OMP_ARCH_SUBDIR}/libomp.so")

if(NOT EXISTS "${OMP_LIB_PATH}")
    message(FATAL_ERROR "Could not locate libomp.so at ${OMP_LIB_PATH}")
endif()

add_library(omp SHARED IMPORTED)
set_target_properties(omp PROPERTIES
        IMPORTED_LOCATION "${OMP_LIB_PATH}"
        IMPORTED_NO_SONAME TRUE
)

target_include_directories(llamabridge PRIVATE
        ${CMAKE_SOURCE_DIR}/third_party/llama
        ${CMAKE_SOURCE_DIR}/third_party/llama/ggml/include
)

target_link_libraries(llamabridge
        PRIVATE
        llama
        ggml
        ${cpp_shared}
        ${log_lib}
        omp
)
