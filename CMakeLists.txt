cmake_minimum_required(VERSION 3.10.2)
project(native-lib)

# Build the native shared library used for PTY operations.
add_library(native-lib SHARED native-lib.cpp)

# Align ELF load segments to 16KB pages, required for Android 15+ devices.
target_link_options(native-lib PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
find_library(m-lib m)

# Vulkan is only available on API 24+, but we don't want to fail the build on lower API levels.
set(HAVE_VULKAN 0)
if(DEFINED ANDROID_PLATFORM AND ANDROID_PLATFORM GREATER_EQUAL 24)
    find_library(vulkan-lib vulkan)
    if(vulkan-lib)
        set(HAVE_VULKAN 1)
        message(STATUS "Vulkan library found at: ${vulkan-lib}")
    endif()
endif()

# Link basic libs
set(LINK_LIBS ${log-lib} ${m-lib})
if(HAVE_VULKAN)
    list(APPEND LINK_LIBS ${vulkan-lib})
endif()

target_link_libraries(native-lib ${LINK_LIBS})
