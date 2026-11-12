cmake_minimum_required(VERSION 3.22)
project(homeflix_native CXX)

# The Node.js runtime (nodejs-mobile), unpacked by scripts/fetch-native.sh into android/build/native.
set(NODE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../build/native/nodejs-mobile")

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES IMPORTED_LOCATION "${NODE_DIR}/bin/${ANDROID_ABI}/libnode.so")

add_library(homeflix_native SHARED native-lib.cpp)
target_include_directories(homeflix_native PRIVATE "${NODE_DIR}/include/node")
target_link_libraries(homeflix_native node log)
target_link_options(homeflix_native PRIVATE "-Wl,-z,max-page-size=16384")
