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
find_library(omp_lib omp REQUIRED)

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
        ${omp_lib}
)
