cmake_minimum_required(VERSION 3.22.1)
project(pyrunner_native)

# python-android/ 는 저장소 루트에 두는 CPython 안드로이드 배포판 폴더
# (python.org에서 받은 prefix/include, prefix/lib 중 필요한 것만 복사해둔 것)
set(PYTHON_ANDROID_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../python-android)

include_directories(${PYTHON_ANDROID_DIR}/include/python3.14)
link_directories(${PYTHON_ANDROID_DIR}/lib)

add_library(pyrunner_native SHARED pyrunner_native.c)

find_library(log-lib log)

target_link_libraries(pyrunner_native
    python3.14
    ${log-lib}
)