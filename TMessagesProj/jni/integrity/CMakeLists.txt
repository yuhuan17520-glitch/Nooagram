# Bind each native build to its own APK certificate, never to an upstream key.
set(NOOAGRAM_EXPECTED_SIGNER_SHA1 "" CACHE STRING "APK signer certificate SHA1")
string(LENGTH "${NOOAGRAM_EXPECTED_SIGNER_SHA1}" NOOAGRAM_SIGNER_LENGTH)
if(NOT NOOAGRAM_SIGNER_LENGTH EQUAL 40 OR NOOAGRAM_EXPECTED_SIGNER_SHA1 MATCHES "[^0-9A-Fa-f]")
    message(FATAL_ERROR "NOOAGRAM_EXPECTED_SIGNER_SHA1 must be a 40-character certificate SHA1")
endif()
string(TOUPPER "${NOOAGRAM_EXPECTED_SIGNER_SHA1}" NOOAGRAM_EXPECTED_SIGNER_SHA1)
add_library(integrity STATIC
        integrity/integrity.cpp
        integrity/meth.cpp
        integrity/openat.c
        integrity/read_cert.cpp
        integrity/SHA1.cpp)
target_compile_definitions(integrity PRIVATE NOOAGRAM_EXPECTED_SIGNER_SHA1="${NOOAGRAM_EXPECTED_SIGNER_SHA1}")
target_compile_options(integrity PUBLIC
        -Oz -Wall -fvisibility=hidden)
target_include_directories(integrity PUBLIC
        integrity)
