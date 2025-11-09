cmake_minimum_required(VERSION 3.22)
project(llama_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")

# ✅ Thêm đúng file vào target (nếu sai tên/đường dẫn, IDE sẽ báo "not part of project")
add_library(llamabridge SHARED
        ${CMAKE_SOURCE_DIR}/llamabridge.cpp
)

# ✅ Import libllama.so theo ABI đang build
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so"
)

# IMPORT ggml (giống cách bạn import llama)
add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so"
)

# Link JNI shim -> llama + ggml + log
target_link_libraries(llamabridge PRIVATE llama ggml ${log-lib})


# ✅ Include path tới headers bạn đã copy
include_directories(
        ${CMAKE_SOURCE_DIR}/third_party/llama
        ${CMAKE_SOURCE_DIR}/third_party/llama/ggml/include
)

# Android log
find_library(log-lib log)

find_library(cpp_shared c++_shared REQUIRED)

# Link JNI shim -> llama + log
target_link_libraries(llamabridge
        PRIVATE
        llama
        ggml
        c++_shared      # <-- quan trọng
        ${log-lib}
)