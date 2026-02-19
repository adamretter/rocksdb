ROCKSDB_MAJOR = $(shell grep -E "ROCKSDB_MAJOR.[0-9]" ../include/rocksdb/version.h | cut -d ' ' -f 3)
ROCKSDB_MINOR = $(shell grep -E "ROCKSDB_MINOR.[0-9]" ../include/rocksdb/version.h | cut -d ' ' -f 3)
ROCKSDB_PATCH = $(shell grep -E "ROCKSDB_PATCH.[0-9]" ../include/rocksdb/version.h | cut -d ' ' -f 3)
ROCKSDB_JAVA_VERSION = $(ROCKSDB_MAJOR).$(ROCKSDB_MINOR).$(ROCKSDB_PATCH)

NATIVE_INCLUDE = ./include
ARCH := $(shell getconf LONG_BIT)
SHA256_CMD ?= sha256sum

# Set the path of the java commands
ifeq ($(JAVA_CMD),)
ifneq ($(JAVA_HOME),)
JAVA_CMD := $(JAVA_HOME)/bin/java
else
JAVA_CMD := java
endif
endif

ifeq ($(JAVAC_CMD),)
ifneq ($(JAVA_HOME),)
JAVAC_CMD := $(JAVA_HOME)/bin/javac
else
JAVAC_CMD := javac
endif
endif

MAVEN_CMD ?= mvn
JAVA_NATIVE_DIR ?= target-native

# Look for the Java version (1.6->6, 1.7->7, 1.8->8, 11.0->11, 13.0->13, 15.0->15 etc..)
JAVAC_VERSION := $(shell $(JAVAC_CMD) -version 2>&1)
JAVAC_MAJOR_VERSION := $(word 2,$(subst ., ,$(JAVAC_VERSION)))
ifeq ($(JAVAC_MAJOR_VERSION),1)
JAVAC_MAJOR_VERSION := $(word 3,$(subst ., ,$(JAVAC_VERSION)))
endif

# Test whether the version we see meets our minimum
MIN_JAVAC_MAJOR_VERSION := 8
JAVAC_VERSION_GE_MIN := $(shell [ $(JAVAC_MAJOR_VERSION) -ge $(MIN_JAVAC_MAJOR_VERSION) ] > /dev/null 2>&1 && echo true)

# Set the default JAVA_ARGS to "" for DEBUG_LEVEL=0
JAVA_ARGS ?=

JAVAC_ARGS ?=

# When debugging add -Xcheck:jni to the java args
ifneq ($(DEBUG_LEVEL),0)
	JAVA_ARGS += -ea -Xcheck:jni
	JAVAC_ARGS += -Xlint:deprecation -Xlint:unchecked
endif

java-version:
ifneq ($(JAVAC_VERSION_GE_MIN),true)
	echo 'Java version is $(JAVAC_VERSION), minimum required version is $(MIN_JAVAC_MAJOR_VERSION)'
	exit 1
endif

clean:
	$(MAVEN_CMD) clean
	cd benchmark; $(MAVEN_CMD) clean
	cd samples; $(MAVEN_CMD) clean

javadocs:
	$(MAVEN_CMD) -P javadoc-jar javadoc:javadoc

javalib: java java_test javadocs

java: java-version
	$(MAVEN_CMD) compile

build_samples:
	cd benchmark; $(MAVEN_CMD) package

sample: java
	cd benchmark; $(MAVEN_CMD) package
	$(AM_V_at)@rm -rf /tmp/rocksdbjni
	$(JAVA_CMD) $(JAVA_ARGS) -Djava.library.path=${JAVA_NATIVE_DIR} -cp target/rocksdbjni-$(ROCKSDB_JAVA_VERSION).jar:samples/target/rocksdbjni-samples-0.0.0-SNAPSHOT.jar RocksDBSample /tmp/rocksdbjni
	$(AM_V_at)@rm -rf /tmp/rocksdbjni
	$(AM_V_at)@rm -rf /tmp/rocksdbjni_not_found

column_family_sample: java
	cd benchmark; $(MAVEN_CMD) package
	$(AM_V_at)@rm -rf /tmp/rocksdbjni
	$(JAVA_CMD) $(JAVA_ARGS) -Djava.library.path=${JAVA_NATIVE_DIR} -cp target/rocksdbjni-$(ROCKSDB_JAVA_VERSION).jar:samples/target/rocksdbjni-samples-0.0.0-SNAPSHOT.jar RocksDBColumnFamilySample /tmp/rocksdbjni
	$(AM_V_at)@rm -rf /tmp/rocksdbjni

transaction_sample: java
	cd benchmark; $(MAVEN_CMD) package
	$(AM_V_at)@rm -rf /tmp/rocksdbjni
	$(JAVA_CMD) $(JAVA_ARGS) -Djava.library.path=${JAVA_NATIVE_DIR} -cp target/rocksdbjni-$(ROCKSDB_JAVA_VERSION).jar:samples/target/rocksdbjni-samples-0.0.0-SNAPSHOT.jar TransactionSample /tmp/rocksdbjni
	$(AM_V_at)@rm -rf /tmp/rocksdbjni

optimistic_transaction_sample: java
	cd benchmark; $(MAVEN_CMD) package
	$(AM_V_at)@rm -rf /tmp/rocksdbjni
	$(JAVA_CMD) $(JAVA_ARGS) -Djava.library.path=${JAVA_NATIVE_DIR} -cp target/rocksdbjni-$(ROCKSDB_JAVA_VERSION).jar:samples/target/rocksdbjni-samples-0.0.0-SNAPSHOT.jar OptimisticTransactionSample /tmp/rocksdbjni
	$(AM_V_at)@rm -rf /tmp/rocksdbjni

java_test:
	$(MAVEN_CMD) test-compile

test:
	$(MAKE) run_test

run_test:
	$(MAVEN_CMD) test

db_bench: java
	cd benchmark; $(MAVEN_CMD) package
	$(JAVA_CMD) $(JAVA_ARGS) -Djava.library.path=${JAVA_NATIVE_DIR} -cp target/rocksdbjni-$(ROCKSDB_JAVA_VERSION).jar:benchmark/target/rocksdbjni-benchmark-0.0.0-SNAPSHOT.jar org.rocksdb.benchmark.DbBenchmark

pmd:
	$(MAVEN_CMD) pmd:pmd pmd:cpd pmd:check
