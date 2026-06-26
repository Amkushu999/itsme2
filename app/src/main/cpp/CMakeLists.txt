cmake_minimum_required(VERSION 3.22.1)
project("facegate" CXX)

# ── FFmpeg + libyuv prebuilt root directories ─────────────────────────────────
#
# Set in local.properties (recommended):
#   ffmpeg.dir=/path/to/ffmpeg-android
#   libyuv.dir=/path/to/libyuv-android
#
# Or pass directly via CMake args:
#   cmake -DFFMPEG_ROOT=... -DLIBYUV_ROOT=...
#
# Each root must contain:
#   ${ROOT}/android/${ANDROID_ABI}/include/
#   ${ROOT}/android/${ANDROID_ABI}/lib/
# ─────────────────────────────────────────────────────────────────────────────

if(NOT DEFINED FFMPEG_ROOT)
    if(DEFINED ENV{FFMPEG_ROOT})
        set(FFMPEG_ROOT $ENV{FFMPEG_ROOT})
    else()
        set(FFMPEG_ROOT "${CMAKE_SOURCE_DIR}/../ffmpeg")
        message(WARNING "FFMPEG_ROOT not set — defaulting to ${FFMPEG_ROOT}")
    endif()
endif()

if(NOT DEFINED LIBYUV_ROOT)
    if(DEFINED ENV{LIBYUV_ROOT})
        set(LIBYUV_ROOT $ENV{LIBYUV_ROOT})
    else()
        set(LIBYUV_ROOT "${CMAKE_SOURCE_DIR}/../libyuv")
        message(WARNING "LIBYUV_ROOT not set — defaulting to ${LIBYUV_ROOT}")
    endif()
endif()

set(FFMPEG_ABI_DIR "${FFMPEG_ROOT}/android/${ANDROID_ABI}")
set(LIBYUV_ABI_DIR "${LIBYUV_ROOT}/android/${ANDROID_ABI}")

# ── FFmpeg prebuilt static libs ───────────────────────────────────────────────

foreach(_lib avformat avcodec avutil swresample swscale)
    add_library(${_lib} STATIC IMPORTED)
    set_target_properties(${_lib} PROPERTIES
        IMPORTED_LOCATION "${FFMPEG_ABI_DIR}/lib/lib${_lib}.a"
    )
endforeach()

# ── libyuv prebuilt static lib ────────────────────────────────────────────────

add_library(yuv STATIC IMPORTED)
set_target_properties(yuv PROPERTIES
    IMPORTED_LOCATION "${LIBYUV_ABI_DIR}/lib/libyuv.a"
)

# ── System libraries ──────────────────────────────────────────────────────────

find_library(log-lib    log)
find_library(android-lib android)
find_library(z-lib      z)

# ── ffmpeg_decoder.so ─────────────────────────────────────────────────────────

add_library(ffmpeg_decoder SHARED ffmpeg_decoder.cpp)

target_include_directories(ffmpeg_decoder PRIVATE
    "${FFMPEG_ABI_DIR}/include"
)

target_compile_options(ffmpeg_decoder PRIVATE
    -O3 -fPIC -ffast-math -frtti -fexceptions -DNDEBUG
)

target_link_libraries(ffmpeg_decoder
    avformat avcodec avutil swresample swscale
    ${z-lib} ${log-lib} ${android-lib}
)

target_compile_features(ffmpeg_decoder PRIVATE cxx_std_14)

# ── libyuv_wrapper.so ─────────────────────────────────────────────────────────

add_library(libyuv_wrapper SHARED libyuv_wrapper.cpp)

target_include_directories(libyuv_wrapper PRIVATE
    "${LIBYUV_ABI_DIR}/include"
)

target_compile_options(libyuv_wrapper PRIVATE
    -O3 -fPIC -ffast-math -frtti -fexceptions -DNDEBUG
)

target_link_libraries(libyuv_wrapper
    yuv ${log-lib}
)

target_compile_features(libyuv_wrapper PRIVATE cxx_std_14)
