cmake_minimum_required(VERSION 3.22)
project(dnta C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(PINE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/pine")

include_directories(
        "${PINE_DIR}"
        "${PINE_DIR}/external/xz-embedded/linux/include/linux"
        "${PINE_DIR}/external/xz-embedded/userspace"
        "${CMAKE_CURRENT_SOURCE_DIR}/src"
        "${CMAKE_CURRENT_SOURCE_DIR}/zygisk")

set(PINE_SOURCES
        "${PINE_DIR}/pine.cpp"
        "${PINE_DIR}/ruler.cpp"
        "${PINE_DIR}/android.cpp"
        "${PINE_DIR}/jni_bridge.cpp"
        "${PINE_DIR}/art/art_method.cpp"
        "${PINE_DIR}/art/thread.cpp"
        "${PINE_DIR}/art/jit.cpp"
        "${PINE_DIR}/trampoline/trampoline_installer.cpp"
        "${PINE_DIR}/utils/memory.cpp"
        "${PINE_DIR}/utils/scoped_memory_access_protection.cpp"
        "${PINE_DIR}/utils/elf_image.cpp"
        "${PINE_DIR}/utils/well_known_classes.cpp"
        "${PINE_DIR}/external/xz-embedded/linux/lib/xz/xz_crc32.c"
        "${PINE_DIR}/external/xz-embedded/linux/lib/xz/xz_dec_lzma2.c"
        "${PINE_DIR}/external/xz-embedded/linux/lib/xz/xz_dec_stream.c")

if (ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND PINE_SOURCES
            "${PINE_DIR}/trampoline/arch/arm64.cpp"
            "${PINE_DIR}/trampoline/arch/arm64.S")
    add_compile_options(-ffixed-x18)
    add_link_options(-ffixed-x18)
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    list(APPEND PINE_SOURCES
            "${PINE_DIR}/trampoline/arch/thumb2.cpp"
            "${PINE_DIR}/trampoline/arch/thumb2.S")
else()
    message(FATAL_ERROR "Unsupported ABI ${ANDROID_ABI}")
endif ()

add_library(dnta SHARED
        ${PINE_SOURCES}
        "${CMAKE_CURRENT_SOURCE_DIR}/src/module.cpp"
        "${CMAKE_CURRENT_SOURCE_DIR}/src/dex_data.cpp")

target_compile_options(dnta PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        -Wno-unused-variable
        -Wno-format
        -fno-exceptions
        -fno-rtti
        $<$<COMPILE_LANGUAGE:CXX>:-include cstddef>)

find_library(log-lib log)
target_link_libraries(dnta ${log-lib})
target_link_options(dnta PRIVATE -Wl,--hash-style=both)

# Keep the symbols required by JNI / Zygisk visible.
set_target_properties(dnta PROPERTIES CXX_VISIBILITY_PRESET default VISIBILITY_INLINES_HIDDEN ON)
