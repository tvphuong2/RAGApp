cmake_minimum_required(VERSION 3.22)
project(llama_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")

add_library(llamabridge SHARED
        ${CMAKE_SOURCE_DIR}/llamabridge.cpp
)

# prebuilt libs trong jniLibs/${ANDROID_ABI}
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so")

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so")

include_directories(
        ${CMAKE_SOURCE_DIR}/third_party/llama
        ${CMAKE_SOURCE_DIR}/third_party/llama/ggml/include
)

find_library(log-lib log)

# --- STL động: chọn target khả dụng theo NDK ---
set(STL_TGT "")
if (TARGET NDK::c++_shared)
    set(STL_TGT NDK::c++_shared)
elseif (TARGET c++_shared)
    set(STL_TGT c++_shared)
else()
    find_library(CPP_SHARED_LIB NAMES c++_shared REQUIRED)
    set(STL_TGT ${CPP_SHARED_LIB})
endif()

# (Tùy chọn, gợi ý toolchain dùng STL động)
set(CMAKE_ANDROID_STL_TYPE c++_shared)

# Link JNI shim -> llama + ggml + STL + log
target_link_libraries(llamabridge PRIVATE llama ggml ${STL_TGT} ${log-lib})

# Debug CMake: in ra STL_TGT đã chọn
message(STATUS "Using STL target: ${STL_TGT}")
