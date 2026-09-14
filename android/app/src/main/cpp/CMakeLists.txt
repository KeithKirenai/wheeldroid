cmake_minimum_required(VERSION 3.22.1)
project(mkw_android LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_definitions(NDEBUG)
add_compile_options(-O3 -pipe)

get_filename_component(MKW_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(MKW_RUNTIME_DIR "${MKW_ROOT}/runtime")
set(MKW_AURORA_DIR "${MKW_ROOT}/aurora-main")

set(MKW_GENERATED_DIR "${MKW_ROOT}/generated")

set(DAWN_ENABLE_D3D11 OFF CACHE BOOL "" FORCE)
set(DAWN_ENABLE_D3D12 OFF CACHE BOOL "" FORCE)
set(DAWN_ENABLE_VULKAN ON CACHE BOOL "" FORCE)
set(DAWN_ENABLE_OPENGLES ON CACHE BOOL "" FORCE)
set(DAWN_ENABLE_METAL OFF CACHE BOOL "" FORCE)
set(TINT_BUILD_HLSL_WRITER OFF CACHE BOOL "" FORCE)
set(DAWN_BUILD_SAMPLES OFF CACHE BOOL "" FORCE)
set(DAWN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(AURORA_DAWN_PROVIDER "package" CACHE STRING "" FORCE)
set(AURORA_DAWN_LINKAGE "static" CACHE STRING "" FORCE)
set(AURORA_SDL3_PROVIDER "vendor" CACHE STRING "" FORCE)
set(SDL_SHARED OFF CACHE BOOL "" FORCE)
set(SDL_STATIC ON CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)

add_subdirectory("${MKW_AURORA_DIR}" "${CMAKE_BINARY_DIR}/aurora-build" EXCLUDE_FROM_ALL)

include_directories(
    "${MKW_RUNTIME_DIR}/include"
    "${MKW_RUNTIME_DIR}/src"
    "${MKW_ROOT}"
    "${MKW_AURORA_DIR}/include"
    "${MKW_RUNTIME_DIR}/third_party"
    "${MKW_RUNTIME_DIR}/third_party/pugixml"
    "${MKW_RUNTIME_DIR}/third_party/toml11"
    "${MKW_RUNTIME_DIR}/third_party/cryptopp"
)

# 1. Crypto++ (for Wii ES cryptography)
set(MKW_CRYPTOPP_INTERFACE_DEFINITIONS CRYPTOPP_DISABLE_ASM CRYPTOPP_NO_GLOBAL_BYTE)
file(GLOB MKW_CRYPTOPP_SOURCES CONFIGURE_DEPENDS
    "${MKW_RUNTIME_DIR}/third_party/cryptopp/*.cpp")
list(FILTER MKW_CRYPTOPP_SOURCES EXCLUDE REGEX
    "/(adhoc|bench[123]|datatest|dlltest|fipsalgt|fipstest|pch|regtest[1234]|simple|test|validat[0-9]+)\\.cpp$")
list(FILTER MKW_CRYPTOPP_SOURCES EXCLUDE REGEX
    "(_avx|_ppc|_simd|_sse)\\.cpp$")
add_library(mkw_cryptopp STATIC ${MKW_CRYPTOPP_SOURCES})
target_include_directories(mkw_cryptopp SYSTEM PUBLIC "${MKW_RUNTIME_DIR}/third_party")
target_compile_features(mkw_cryptopp PUBLIC cxx_std_17)
target_compile_definitions(mkw_cryptopp PUBLIC ${MKW_CRYPTOPP_INTERFACE_DEFINITIONS})
target_compile_options(mkw_cryptopp PRIVATE -w)
set_target_properties(mkw_cryptopp PROPERTIES UNITY_BUILD OFF)

# 2. Translated Shards (72 shards for Mario Kart Wii - decoupled prebuilt)
include("${MKW_GENERATED_DIR}/build_shards/shards.cmake")
set(MKW_PREBUILT_SHARDS "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmkw_base_shared.a")
if(NOT EXISTS "${MKW_PREBUILT_SHARDS}")
    message(FATAL_ERROR
        "\n[mkw] Missing prebuilt shard archive: ${MKW_PREBUILT_SHARDS}\n"
        "[mkw] Shard compilation is decoupled from the APK build.\n"
        "[mkw] Please run 'build-shards.bat' (or use 'build-app.bat' which calls it automatically) to compile shards first.\n"
    )
endif()

message(STATUS "[mkw] Using decoupled shard archive: ${MKW_PREBUILT_SHARDS}")
add_library(mkw_base_shared STATIC IMPORTED GLOBAL)
set_target_properties(mkw_base_shared PROPERTIES
    IMPORTED_LOCATION "${MKW_PREBUILT_SHARDS}"
)

# 3. Runtime & Platform Sources
file(GLOB_RECURSE RUNTIME_SOURCES CONFIGURE_DEPENDS "${MKW_RUNTIME_DIR}/src/*.cpp")
list(REMOVE_ITEM RUNTIME_SOURCES
    "${MKW_RUNTIME_DIR}/src/guest_flat_memory_macos.cpp"
    "${MKW_RUNTIME_DIR}/src/product/retro_rewind_product.cpp"
    "${MKW_RUNTIME_DIR}/src/host_cpu_baseline.cpp"
)

set(ANDROID_APP_SOURCES
    android_main.cpp
    "${MKW_RUNTIME_DIR}/src/platform/android/co_switch_android.S"
    "${MKW_RUNTIME_DIR}/third_party/pugixml/pugixml.cpp"
    "${MKW_GENERATED_DIR}/data_sections_init.cpp"
    "${MKW_GENERATED_DIR}/data_sections_init_blobs.S"
    "${MKW_GENERATED_DIR}/guest_symbol_table.cpp"
    ${MKW_BASE_REGISTRATION_SOURCES}
    ${RUNTIME_SOURCES}
)
list(REMOVE_DUPLICATES ANDROID_APP_SOURCES)

add_library(mkw_android SHARED
    ${ANDROID_APP_SOURCES}
)

target_compile_definitions(mkw_android PRIVATE
    ANDROID
    NOMINMAX
    TARGET_PC
    SDL_MAIN_HANDLED
    _DISABLE_STRING_ANNOTATION
    _DISABLE_VECTOR_ANNOTATION
    NDEBUG
)
target_compile_options(mkw_android PRIVATE -O3 -pipe)

# PPC semantic helpers must not use fast-math
set_source_files_properties(
    "${MKW_RUNTIME_DIR}/src/ppc_helpers.cpp"
    "${MKW_RUNTIME_DIR}/src/fpu_helpers.cpp"
    PROPERTIES COMPILE_OPTIONS "-fno-fast-math;-ffp-contract=off"
)

target_link_libraries(mkw_android PRIVATE
    android
    log
    vulkan
    GLESv3
    EGL
    aurora::gx
    aurora::pad
    aurora::si
    aurora::vi
    aurora::mtx
    -Wl,--whole-archive mkw_base_shared -Wl,--no-whole-archive
    mkw_cryptopp
)

# Bind all internal function references to their definitions inside this .so
# instead of routing them through the PLT/GOT. The prebuilt shard archive was
# compiled with default symbol visibility, so every generated-to-generated guest
# call, every generated -> HLE call and every HLE -> generated call currently
# pays a PLT+GOT indirection (root-caused from the in-race simpleperf profile).
target_link_options(mkw_android PRIVATE "LINKER:-Bsymbolic-functions")