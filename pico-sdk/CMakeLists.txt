cmake_minimum_required(VERSION 3.13)

include(pico_sdk_import.cmake)

project(c86ec0_pico C CXX ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

pico_sdk_init()

add_executable(c86ec0_pico
    src/main.c
    src/usb_descriptors.c
)

target_include_directories(c86ec0_pico PRIVATE
    src
)

target_link_libraries(c86ec0_pico PRIVATE
    pico_stdlib
    tinyusb_device
    tinyusb_board
)

pico_add_extra_outputs(c86ec0_pico)

