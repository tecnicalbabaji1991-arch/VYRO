add_library(vyro_engine_core STATIC
    project/src/project_state.cpp
    timeline/src/timeline_engine.cpp
)

target_include_directories(vyro_engine_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/core/include
        ${CMAKE_CURRENT_SOURCE_DIR}/contracts/include
        ${CMAKE_CURRENT_SOURCE_DIR}/project/include
        ${CMAKE_CURRENT_SOURCE_DIR}/timeline/include
)

target_compile_features(vyro_engine_core PUBLIC cxx_std_17)

if(ANDROID)
    add_library(vyro_engine SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/../bridge/jni/src/main/cpp/vyro_jni.cpp
    )
    target_link_options(vyro_engine PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_libraries(vyro_engine PRIVATE vyro_engine_core log android)
else()
    enable_testing()
    add_executable(vyro_engine_smoke_test
        tests/engine_smoke_test.cpp
    )
    target_link_libraries(vyro_engine_smoke_test PRIVATE vyro_engine_core)
    add_test(NAME vyro_engine_smoke_test COMMAND vyro_engine_smoke_test)
endif()
