cmake_minimum_required(VERSION 3.22.1)
project(homeflix_node CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(native-lib SHARED native-lib.cpp)
include_directories(nodejs-mobile-include/node)

add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so)

find_library(log-lib log)
target_link_libraries(native-lib libnode ${log-lib})
