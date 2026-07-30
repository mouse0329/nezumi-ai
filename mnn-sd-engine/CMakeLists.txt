cmake_minimum_required(VERSION 3.22.1)
project(mnn_sd_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(MNN_SD_BUILD_SHARED "Build shared library" ON)
option(MNN_SD_BUILD_PROBE_CLI "Build mnn_sd_probe host CLI" ON)
option(MNN_SD_BUILD_HTTP_SERVER "Build optional HTTP adapter" OFF)

set(MNN_SD_PUBLIC_HEADERS
    include/mnn_sd/export.h
    include/mnn_sd/types.h
    include/mnn_sd/engine.h
)

set(MNN_SD_SOURCES
    src/c_api.cpp
    src/mnn_session.cpp
    src/model_config.cpp
)

# --- MNN dependency ---
if(NOT DEFINED MNN_ROOT)
    set(MNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/MNN")
endif()

set(MNN_SD_MNN_LIBS "")
set(MNN_SD_MNN_INCLUDES "")

if(EXISTS "${MNN_ROOT}/include/MNN/Interpreter.hpp")
    set(MNN_SD_MNN_INCLUDES "${MNN_ROOT}/include")
    message(STATUS "mnn-sd-engine: MNN_ROOT=${MNN_ROOT}")

    if(ANDROID)
        if(NOT DEFINED MNN_ANDROID_LIB)
            set(_MNN_LIB_CANDIDATES
                "${MNN_ROOT}/build-android-arm64/libMNN.so"
                "${MNN_ROOT}/project/android/build_64/libMNN.so"
                "${MNN_ROOT}/project/android/build/libMNN.so"
            )
            foreach(_candidate IN LISTS _MNN_LIB_CANDIDATES)
                if(EXISTS "${_candidate}")
                    set(MNN_ANDROID_LIB "${_candidate}")
                    break()
                endif()
            endforeach()
        endif()
        if(MNN_ANDROID_LIB AND EXISTS "${MNN_ANDROID_LIB}")
            set(MNN_SD_MNN_LIBS "${MNN_ANDROID_LIB}")
            message(STATUS "mnn-sd-engine: MNN_ANDROID_LIB=${MNN_ANDROID_LIB}")
        else()
            message(WARNING "libMNN.so not found — probe will stub. Run scripts/build_mnn_android.ps1")
        endif()
    else()
        find_library(MNN_LIB MNN PATHS "${MNN_ROOT}/build" NO_DEFAULT_PATH)
        if(MNN_LIB)
            set(MNN_SD_MNN_LIBS "${MNN_LIB}")
        else()
            message(WARNING "MNN library not found under ${MNN_ROOT}/build — probe will stub")
        endif()
    endif()
else()
    message(STATUS "mnn-sd-engine: MNN headers not found at ${MNN_ROOT}")
endif()

if(MNN_SD_MNN_LIBS AND MNN_SD_MNN_INCLUDES)
    add_compile_definitions(MNN_SD_HAS_MNN=1)
endif()

if(MNN_SD_BUILD_SHARED)
    add_library(mnn_sd_engine SHARED ${MNN_SD_SOURCES})
    target_compile_definitions(mnn_sd_engine PRIVATE MNN_SD_EXPORTS MNN_SD_BUILD_SHARED)
else()
    add_library(mnn_sd_engine STATIC ${MNN_SD_SOURCES})
endif()

target_include_directories(mnn_sd_engine
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
)

if(MNN_SD_MNN_INCLUDES)
    target_include_directories(mnn_sd_engine PRIVATE "${MNN_SD_MNN_INCLUDES}")
endif()

if(ANDROID)
    find_library(ANDROID_LOG_LIB log)
    target_link_libraries(mnn_sd_engine PRIVATE ${ANDROID_LOG_LIB})
endif()

if(MNN_SD_MNN_LIBS)
    target_link_libraries(mnn_sd_engine PRIVATE ${MNN_SD_MNN_LIBS})
endif()

# --- Host probe CLI (shape discovery) ---
if(MNN_SD_BUILD_PROBE_CLI AND NOT ANDROID)
    add_executable(mnn_sd_probe tools/mnn_sd_probe.cpp)
    target_link_libraries(mnn_sd_probe PRIVATE mnn_sd_engine)

    add_executable(mnn_sd_load_test tools/mnn_sd_load_test.cpp)
    target_link_libraries(mnn_sd_load_test PRIVATE mnn_sd_engine)
    
    # 実際に txt2img まで通す host 用CLI。Android実機への都度デプロイなしに
    # 生成結果 (.ppm / stb_image_write.h があれば .png) を確認できる。
    add_executable(mnn_sd_generate_cli tools/mnn_sd_generate_cli.cpp)
    target_link_libraries(mnn_sd_generate_cli PRIVATE mnn_sd_engine)
    if(MSVC)
        target_compile_options(mnn_sd_generate_cli PRIVATE /utf-8)
    endif()
endif()

# --- Android JNI ---
if(ANDROID)
    add_library(mnn_sd_jni SHARED android/jni/mnn_sd_jni.cpp)
    target_link_libraries(mnn_sd_jni PRIVATE mnn_sd_engine ${ANDROID_LOG_LIB})
    target_include_directories(mnn_sd_jni PRIVATE 
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/src   # ← ここを追加！
    )
    # 16KB page size support (Android 15+)
    target_link_options(mnn_sd_jni PRIVATE "-Wl,-z,max-page-size=16384")
endif()

# --- Optional HTTP server (Phase 4) ---
if(MNN_SD_BUILD_HTTP_SERVER)
    add_subdirectory(optional/http_server)
endif()

install(TARGETS mnn_sd_engine
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
    RUNTIME DESTINATION bin
)
install(FILES ${MNN_SD_PUBLIC_HEADERS} DESTINATION include/mnn_sd)
