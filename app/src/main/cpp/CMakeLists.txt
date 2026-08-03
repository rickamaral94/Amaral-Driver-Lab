cmake_minimum_required(VERSION 3.22.1)
project(amaral_driver_lab LANGUAGES C CXX)

include(FetchContent)

# Pinned: https://github.com/bylaws/libadrenotools/commit/8fae8ce254dfc1344527e05301e43f37dea2df80
FetchContent_Declare(
        adrenotools_source
        GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
        GIT_TAG 8fae8ce254dfc1344527e05301e43f37dea2df80
        GIT_SHALLOW FALSE
        GIT_SUBMODULES_RECURSE TRUE)
FetchContent_MakeAvailable(adrenotools_source)

add_library(driverlab SHARED benchmark.cpp)
target_compile_features(driverlab PRIVATE cxx_std_20)
target_compile_definitions(driverlab PRIVATE VK_NO_PROTOTYPES)

find_library(android-lib android REQUIRED)
find_library(dl-lib dl REQUIRED)
find_library(log-lib log REQUIRED)

target_link_libraries(
        driverlab
        PRIVATE
        adrenotools
        ${android-lib}
        ${dl-lib}
        ${log-lib})
