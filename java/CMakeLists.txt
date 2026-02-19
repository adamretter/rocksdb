cmake_minimum_required(VERSION 3.11)

if(${CMAKE_VERSION} VERSION_LESS "3.11.4")
    message("Please consider switching to CMake 3.11.4 or newer")
endif()

set(ROCKSDB_JAVA_VERSION "$ENV{ROCKSDB_JAVA_VERSION}" CACHE STRING
    "Version string for RocksJava pom.xml and JAR artifacts (default: RocksDB project version)")
if(NOT ROCKSDB_JAVA_VERSION)
    set(ROCKSDB_JAVA_VERSION "${CMAKE_PROJECT_VERSION}")
endif()

# ---------------------------------------------------------------------------
# Generate pom.xml from pom.xml.template by substituting the project version.
# Equivalent to the ../Makefile 'rocksdbjavageneratepom' target.
# ---------------------------------------------------------------------------
message(STATUS "Generating java/pom.xml (version ${ROCKSDB_JAVA_VERSION})")
file(READ ${CMAKE_CURRENT_SOURCE_DIR}/pom.xml.template _POM_TEMPLATE)
string(REPLACE "0.0.0-SNAPSHOT" "${ROCKSDB_JAVA_VERSION}" _POM_CONTENT "${_POM_TEMPLATE}")
file(WRITE ${CMAKE_CURRENT_SOURCE_DIR}/pom.xml "${_POM_CONTENT}")

# ---------------------------------------------------------------------------
# Find Maven
# ---------------------------------------------------------------------------
find_program(MAVEN_EXECUTABLE mvn
    HINTS
        $ENV{M2_HOME}/bin
        /opt/apache-maven/bin
        /opt/maven/bin
        /usr/local/bin
        /usr/bin
    DOC "Apache Maven executable"
)
if(NOT MAVEN_EXECUTABLE)
    message(FATAL_ERROR "Maven (mvn) not found. Please install Maven and ensure it is on PATH or set M2_HOME.")
endif()
message(STATUS "Found Maven: ${MAVEN_EXECUTABLE}")

# ---------------------------------------------------------------------------
# JNI Native C++ sources - compiled by CMake into the native JNI library
# ---------------------------------------------------------------------------
set(JNI_NATIVE_SOURCES
        rocksjni/backup_engine_options.cc
        rocksjni/backupenginejni.cc
        rocksjni/cache.cc
        rocksjni/cassandra_compactionfilterjni.cc
        rocksjni/cassandra_value_operator.cc
        rocksjni/checkpoint.cc
        rocksjni/clock_cache.cc
        rocksjni/columnfamilyhandle.cc
        rocksjni/compact_range_options.cc
        rocksjni/compaction_filter.cc
        rocksjni/compaction_filter_factory.cc
        rocksjni/compaction_filter_factory_jnicallback.cc
        rocksjni/compaction_job_info.cc
        rocksjni/compaction_job_stats.cc
        rocksjni/compaction_options.cc
        rocksjni/compaction_options_fifo.cc
        rocksjni/compaction_options_universal.cc
        rocksjni/comparator.cc
        rocksjni/comparatorjnicallback.cc
        rocksjni/compression_options.cc
        rocksjni/concurrent_task_limiter.cc
        rocksjni/config_options.cc
        rocksjni/env.cc
        rocksjni/env_options.cc
        rocksjni/event_listener.cc
        rocksjni/event_listener_jnicallback.cc
        rocksjni/export_import_files_metadatajni.cc
        rocksjni/filter.cc
        rocksjni/hyper_clock_cache.cc
        rocksjni/import_column_family_options.cc
        rocksjni/ingest_external_file_options.cc
        rocksjni/iterator.cc
        rocksjni/jni_multiget_helpers.cc
        rocksjni/jni_perf_context.cc
        rocksjni/jnicallback.cc
        rocksjni/loggerjnicallback.cc
        rocksjni/lru_cache.cc
        rocksjni/memory_util.cc
        rocksjni/memtablejni.cc
        rocksjni/merge_operator.cc
        rocksjni/native_comparator_wrapper_test.cc
        rocksjni/optimistic_transaction_db.cc
        rocksjni/optimistic_transaction_options.cc
        rocksjni/options.cc
        rocksjni/options_util.cc
        rocksjni/persistent_cache.cc
        rocksjni/ratelimiterjni.cc
        rocksjni/remove_emptyvalue_compactionfilterjni.cc
        rocksjni/restorejni.cc
        rocksjni/rocks_callback_object.cc
        rocksjni/rocksdb_exception_test.cc
        rocksjni/rocksjni.cc
        rocksjni/slice.cc
        rocksjni/snapshot.cc
        rocksjni/sst_file_manager.cc
        rocksjni/sst_file_reader_iterator.cc
        rocksjni/sst_file_readerjni.cc
        rocksjni/sst_file_writerjni.cc
        rocksjni/sst_partitioner.cc
        rocksjni/statistics.cc
        rocksjni/statisticsjni.cc
        rocksjni/stderr_logger.cc
        rocksjni/table.cc
        rocksjni/table_filter.cc
        rocksjni/table_filter_jnicallback.cc
        rocksjni/table_properties_collector_factory.cc
        rocksjni/testable_event_listener.cc
        rocksjni/thread_status.cc
        rocksjni/trace_writer.cc
        rocksjni/trace_writer_jnicallback.cc
        rocksjni/transaction.cc
        rocksjni/transaction_db.cc
        rocksjni/transaction_db_options.cc
        rocksjni/transaction_notifier.cc
        rocksjni/transaction_notifier_jnicallback.cc
        rocksjni/transaction_options.cc
        rocksjni/ttl.cc
        rocksjni/wal_filter.cc
        rocksjni/wal_filter_jnicallback.cc
        rocksjni/wal_iterator.cc
        rocksjni/write_batch.cc
        rocksjni/write_batch_test.cc
        rocksjni/write_batch_with_index.cc
        rocksjni/write_buffer_manager.cc
        rocksjni/writebatchhandlerjnicallback.cc
)

# ---------------------------------------------------------------------------
# Include directories for native JNI compilation:
#   JNI_INCLUDE_DIRS                      - JDK headers: jni.h, jni_md.h
#   rocksjni/                             - internal headers: portal.h, etc.
#   target/generated-sources/jni/         - JNI headers (javac -h via Maven)
# ---------------------------------------------------------------------------
find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/rocksjni)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/target/generated-sources/jni)

# Native library staging directory.
# Matches pom.xml's 'native.library.staging.dir' property.
# Maven Surefire and the assembly plugin both read native libs from here.
set(JAVA_NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/target-native)

# ---------------------------------------------------------------------------
# Step 1: Maven compile
#   Compiles Java sources and generates JNI headers into:
#     java/target/generated-sources/jni/
#   Must complete before the native library is compiled.
# ---------------------------------------------------------------------------
add_custom_target(rocksdbjni_java_compile
    COMMAND ${MAVEN_EXECUTABLE} test-compile
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    COMMENT "Compiling Java sources (main + test) and generating JNI headers via Maven"
    VERBATIM
)

# ---------------------------------------------------------------------------
# Step 2: Build the native JNI library (CMake)
#   Depends on rocksdbjni_java_compile so JNI method headers exist first.
#   Links against the RocksDB library built by the parent CMake project.
# ---------------------------------------------------------------------------
if(NOT MSVC)
    set_property(TARGET ${ROCKSDB_STATIC_LIB} PROPERTY POSITION_INDEPENDENT_CODE ON)
endif()

set(ROCKSDBJNI_STATIC_LIB rocksdbjni${ARTIFACT_SUFFIX})
if(MINGW)
    # Build mingw only as staic library.
    add_library(${ROCKSDBJNI_STATIC_LIB} ${JNI_NATIVE_SOURCES})
else()
    add_library(${ROCKSDBJNI_STATIC_LIB} SHARED ${JNI_NATIVE_SOURCES})
endif()
add_dependencies(${ROCKSDBJNI_STATIC_LIB} rocksdbjni_java_compile)
target_link_libraries(${ROCKSDBJNI_STATIC_LIB} ${ROCKSDB_STATIC_LIB} ${ROCKSDB_LIB})

if(NOT MINGW)
    set(ROCKSDBJNI_SHARED_LIB rocksdbjni-shared${ARTIFACT_SUFFIX})
    add_library(${ROCKSDBJNI_SHARED_LIB} SHARED ${JNI_NATIVE_SOURCES})
    add_dependencies(${ROCKSDBJNI_SHARED_LIB} rocksdbjni_java_compile)
    target_link_libraries(${ROCKSDBJNI_SHARED_LIB} ${ROCKSDB_STATIC_LIB} ${ROCKSDB_LIB})

    set_target_properties(
        ${ROCKSDBJNI_SHARED_LIB}
        PROPERTIES
        COMPILE_PDB_OUTPUT_DIRECTORY ${CMAKE_CFG_INTDIR}
        COMPILE_PDB_NAME ${ROCKSDBJNI_STATIC_LIB}.pdb
    )
endif()

# Platform-specific native library output naming and Maven packaging profile.
# The Maven platform profile creates a classifier JAR (e.g. rocksdbjni-<ver>-osx.jar)
# in addition to the main JAR produced by the main-jar profile.
set(bitness 32)
if(CMAKE_SIZEOF_VOID_P EQUAL 8)
    set(bitness 64)
endif()

if(${CMAKE_SYSTEM_NAME} MATCHES "Windows")
    set_target_properties(
        ${ROCKSDBJNI_STATIC_LIB}
        PROPERTIES
        OUTPUT_NAME librocksdbjni-win${bitness}
    )
    set(MAVEN_PLATFORM_PROFILE "win64-jar")
elseif(${CMAKE_SYSTEM_NAME} MATCHES "Linux")
    set_target_properties(
        ${ROCKSDBJNI_STATIC_LIB}
        PROPERTIES
        OUTPUT_NAME "rocksdbjni-linux${bitness}"
    )
    if(bitness EQUAL 64)
        set(MAVEN_PLATFORM_PROFILE "linux64-jar")
    else()
        set(MAVEN_PLATFORM_PROFILE "linux32-jar")
    endif()
elseif(${CMAKE_SYSTEM_NAME} MATCHES "Darwin")
    set_target_properties(
        ${ROCKSDBJNI_STATIC_LIB}
        PROPERTIES
        OUTPUT_NAME rocksdbjni-osx-${CMAKE_SYSTEM_PROCESSOR}
        SUFFIX ".jnilib"
    )
    set(MAVEN_PLATFORM_PROFILE "osx-jar")
else()
    set(MAVEN_PLATFORM_PROFILE "")
endif()

# ---------------------------------------------------------------------------
# Step 3: Copy native library to Maven staging directory after build.
#   Maven Surefire (mvn test) and the assembly plugin (mvn package)
#   both expect native libs at ${JAVA_NATIVE_DIR}.
# ---------------------------------------------------------------------------
add_custom_command(TARGET ${ROCKSDBJNI_STATIC_LIB} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${JAVA_NATIVE_DIR}
    COMMAND ${CMAKE_COMMAND} -E copy
        $<TARGET_FILE:${ROCKSDBJNI_STATIC_LIB}>
        ${JAVA_NATIVE_DIR}/
    COMMENT "Copying native JNI library to Maven staging directory (target-native/)"
    VERBATIM
)

# ---------------------------------------------------------------------------
# Step 4: Package JAR via Maven profiles.
#   main-jar profile:              primary JAR is classes and native lib(s)
#                                  from target-native/
#   ${MAVEN_PLATFORM_PROFILE}:     platform classifier JAR, e.g.:
#                                    win64-jar -> rocksdbjni-<ver>-win64.jar
# ---------------------------------------------------------------------------
if(MAVEN_PLATFORM_PROFILE)
    set(MAVEN_PACKAGE_PROFILES "main-jar,${MAVEN_PLATFORM_PROFILE}")
else()
    set(MAVEN_PACKAGE_PROFILES "main-jar")
endif()

add_custom_target(rocksdbjava ALL
    COMMAND ${MAVEN_EXECUTABLE} package -DskipTests -P "${MAVEN_PACKAGE_PROFILES}"
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    DEPENDS ${ROCKSDBJNI_STATIC_LIB}
    COMMENT "Packaging JAR via Maven (profiles: ${MAVEN_PACKAGE_PROFILES})"
    VERBATIM
)

# ---------------------------------------------------------------------------
# Step 5: Testing via Maven Surefire.
#   By default all tests are run. To run a specific test set -DJAVA_TEST=<class>
#   at CMake configure time. Supports the full Surefire -Dtest= syntax:
#     -DJAVA_TEST=ReadOptionsTest               (single class)
#     -DJAVA_TEST="ReadOptions*"                (wildcard)
#     -DJAVA_TEST=ReadOptionsTest#myMethod      (specific method)
#     -DJAVA_TEST="ReadOptionsTest,RocksDBTest" (multiple classes)
#
# Two mechanisms are provided for running tests:
#
#   rocksdbjava_test (build target):
#     cmake --build . --target rocksdbjava_test
#     Invokes mvn test directly as a build step. Has an explicit DEPENDS on
#     the native library target, so it ensures the native lib is built and
#     staged to target-native/ before tests are run.
#
#   java_tests (CTest test):
#     ctest  -or-  cmake --build . --target test
#     Registers the same mvn test command with CTest for integration with
#     CI systems and IDEs that drive testing via ctest. Note: CTest does not
#     manage build dependencies, so the native library must have been built
#     first (e.g. via cmake --build . && ctest).
# ---------------------------------------------------------------------------
set(JAVA_TEST "" CACHE STRING
    "Java test class to run via mvn test (empty = all tests). \
Supports Surefire -Dtest= syntax: e.g. ReadOptionsTest, ReadOptions*, \
ReadOptionsTest#myMethod, or a comma-separated list."
)

set(MAVEN_TEST_FILTER)
if(JAVA_TEST)
    set(MAVEN_TEST_FILTER "-Dtest=${JAVA_TEST}")
    set(MAVEN_TEST_COMMENT "Running Java test '${JAVA_TEST}' via Maven")
else()
    set(MAVEN_TEST_COMMENT "Running all Java tests via Maven")
endif()

add_custom_target(rocksdbjava_test
    COMMAND ${MAVEN_EXECUTABLE} test ${MAVEN_TEST_FILTER}
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    DEPENDS ${ROCKSDBJNI_STATIC_LIB}
    COMMENT "${MAVEN_TEST_COMMENT}"
    VERBATIM
)

enable_testing()
add_test(
    NAME java_tests
    COMMAND ${MAVEN_EXECUTABLE} test ${MAVEN_TEST_FILTER}
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
)
