cmake_minimum_required(VERSION 3.18.1)
project(nekoflash_usbfs LANGUAGES CXX)

add_library(nekoflash_usbfs SHARED native_usbfs.cpp)

target_compile_features(nekoflash_usbfs PRIVATE cxx_std_17)
target_compile_options(nekoflash_usbfs PRIVATE -Wall -Wextra -Werror)

target_link_libraries(nekoflash_usbfs android log)
