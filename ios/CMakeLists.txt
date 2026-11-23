cmake_minimum_required(VERSION 3.25)
project(NECMobileApple LANGUAGES C CXX OBJCXX Swift)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_OSX_DEPLOYMENT_TARGET 15.0)
set(CMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED NO)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(REPO ${CMAKE_CURRENT_LIST_DIR}/..)
if(CMAKE_OSX_SYSROOT MATCHES "iphonesimulator")
    set(NEC_SDK iphonesimulator)
else()
    set(NEC_SDK iphoneos)
endif()
set(NP2_FRAMEWORK ${CMAKE_CURRENT_BINARY_DIR}/Native/np2kai.framework)
add_custom_target(np2Apple ALL
    COMMAND ${Python3_EXECUTABLE} ${REPO}/scripts/build_np2kai_ios.py --sdk ${NEC_SDK}
        --arch ${CMAKE_OSX_ARCHITECTURES} --output ${NP2_FRAMEWORK}
    BYPRODUCTS ${NP2_FRAMEWORK}/np2kai)
add_subdirectory(${REPO}/native/pc88 ${CMAKE_CURRENT_BINARY_DIR}/pc88)
set_target_properties(pc88 PROPERTIES FRAMEWORK TRUE MACOSX_FRAMEWORK_IDENTIFIER org.necmobile.pc88)
add_library(pc98 SHARED ${REPO}/native/pc98/adapter.cpp)
set_target_properties(pc98 PROPERTIES FRAMEWORK TRUE MACOSX_FRAMEWORK_IDENTIFIER org.necmobile.pc98
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)
target_compile_features(pc98 PRIVATE cxx_std_17)
file(GLOB SWIFT_SOURCES CONFIGURE_DEPENDS ${CMAKE_CURRENT_LIST_DIR}/Sources/*.swift)
file(GLOB BRIDGE_SOURCES CONFIGURE_DEPENDS ${CMAKE_CURRENT_LIST_DIR}/Native/*.mm)
add_executable(NECMobile MACOSX_BUNDLE ${SWIFT_SOURCES} ${BRIDGE_SOURCES}
    ${REPO}/native/host.cpp ${REPO}/native/midi_synth.cpp ${REPO}/native/dos_disk.cpp
    ${REPO}/third_party/fatfs/ff.c)
target_include_directories(NECMobile PRIVATE Native ${REPO}/native ${REPO}/third_party/fatfs)
target_compile_options(NECMobile PRIVATE "$<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>")
target_link_libraries(NECMobile PRIVATE "-framework UIKit" "-framework SwiftUI" "-framework Metal"
    "-framework QuartzCore" "-framework AVFoundation" "-framework AudioToolbox" z)
add_dependencies(NECMobile pc88 pc98 np2Apple)
set_target_properties(NECMobile PROPERTIES
    MACOSX_BUNDLE_INFO_PLIST ${CMAKE_CURRENT_LIST_DIR}/Info.plist
    MACOSX_BUNDLE_GUI_IDENTIFIER org.necmobile.ios
    XCODE_ATTRIBUTE_PRODUCT_BUNDLE_IDENTIFIER org.necmobile.ios
    XCODE_ATTRIBUTE_SWIFT_VERSION 5.0
    XCODE_ATTRIBUTE_CLANG_ENABLE_OBJC_ARC YES
    XCODE_ATTRIBUTE_SWIFT_OBJC_BRIDGING_HEADER ${CMAKE_CURRENT_LIST_DIR}/Native/Bridging.h
    XCODE_ATTRIBUTE_TARGETED_DEVICE_FAMILY "1,2"
    XCODE_ATTRIBUTE_LD_RUNPATH_SEARCH_PATHS "@executable_path/Frameworks"
    XCODE_EMBED_FRAMEWORKS "pc88;pc98;${NP2_FRAMEWORK}"
    XCODE_EMBED_FRAMEWORKS_CODE_SIGN_ON_COPY FALSE)
add_custom_command(TARGET NECMobile POST_BUILD
    COMMAND ${Python3_EXECUTABLE} ${REPO}/scripts/prepare_ios_assets.py --app "$<TARGET_BUNDLE_DIR:NECMobile>")
if(NEC_SDK STREQUAL "iphonesimulator")
    find_package(XCTest REQUIRED)
    xctest_add_bundle(NECMobileImportUITests NECMobile Tests/ImportUITests.swift)
    set_target_properties(NECMobileImportUITests PROPERTIES
        XCODE_PRODUCT_TYPE com.apple.product-type.bundle.ui-testing
        XCODE_ATTRIBUTE_TEST_HOST ""
        XCODE_ATTRIBUTE_BUNDLE_LOADER ""
        XCODE_ATTRIBUTE_TEST_TARGET_NAME NECMobile
        XCODE_ATTRIBUTE_PRODUCT_BUNDLE_IDENTIFIER org.necmobile.ios.import-tests
        XCODE_ATTRIBUTE_GENERATE_INFOPLIST_FILE YES
        XCODE_ATTRIBUTE_SWIFT_VERSION 5.0)
    set_target_properties(NECMobile PROPERTIES XCODE_GENERATE_SCHEME TRUE
        XCODE_SCHEME_TEST_CONFIGURATION Release)
endif()
