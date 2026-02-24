//  Copyright (c) Meta Platforms, Inc. and affiliates.
//
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).

package org.rocksdb.util;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.rocksdb.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;

public class JNIComparatorTest {

  static Stream<Arguments> parameters() {
    return Stream.of(
        Arguments.of("bytewise_non-direct", BuiltinComparator.BYTEWISE_COMPARATOR, false),
        Arguments.of("bytewise_direct", BuiltinComparator.BYTEWISE_COMPARATOR, true),
        Arguments.of("reverse-bytewise_non-direct", BuiltinComparator.REVERSE_BYTEWISE_COMPARATOR, false),
        Arguments.of("reverse-bytewise_direct", BuiltinComparator.REVERSE_BYTEWISE_COMPARATOR, true)
    );
  }

  @RegisterExtension
  public static final RocksNativeLibraryResource ROCKS_NATIVE_LIBRARY_RESOURCE =
      new RocksNativeLibraryResource();

  @TempDir
  File dbFolder;

  private static final int MIN = Short.MIN_VALUE - 1;
  private static final int MAX = Short.MAX_VALUE + 1;

  @ParameterizedTest
  @MethodSource("parameters")
  public void java_comparator_equals_cpp_comparator(final String name, final BuiltinComparator builtinComparator, final boolean useDirectBuffer) throws RocksDBException, IOException {
    final int[] javaKeys;
    try (final ComparatorOptions comparatorOptions = new ComparatorOptions();
         final AbstractComparator comparator = builtinComparator == BuiltinComparator.BYTEWISE_COMPARATOR
             ? new BytewiseComparator(comparatorOptions)
             : new ReverseBytewiseComparator(comparatorOptions)) {
      final Path javaDbDir =
          FileSystems.getDefault().getPath(Files.createTempDirectory(dbFolder.toPath(), "java_comparator_equals_cpp_comparator-java").toAbsolutePath().toString());
      storeWithJavaComparator(javaDbDir, comparator);
      javaKeys = readAllWithJavaComparator(javaDbDir, comparator);
    }

    final Path cppDbDir =
        FileSystems.getDefault().getPath(Files.createTempDirectory(dbFolder.toPath(), "java_comparator_equals_cpp_comparator-cpp").toAbsolutePath().toString());
    storeWithCppComparator(cppDbDir, builtinComparator);
    final int[] cppKeys =
        readAllWithCppComparator(cppDbDir, builtinComparator);

    assertThat(javaKeys).isEqualTo(cppKeys);
  }

  private void storeWithJavaComparator(final Path dir,
      final AbstractComparator comparator) throws RocksDBException {
    final ByteBuffer buf = ByteBuffer.allocate(4);
    try (final Options options = new Options()
             .setCreateIfMissing(true)
             .setComparator(comparator);
         final RocksDB db =
             RocksDB.open(options, dir.toAbsolutePath().toString())) {
      for (int i = MIN; i < MAX; i++) {
        buf.putInt(i);
        buf.flip();

        db.put(buf.array(), buf.array());

        buf.clear();
      }
    }
  }

  private void storeWithCppComparator(final Path dir,
      final BuiltinComparator builtinComparator) throws RocksDBException {
    try (final Options options = new Options()
             .setCreateIfMissing(true)
             .setComparator(builtinComparator);
         final RocksDB db =
             RocksDB.open(options, dir.toAbsolutePath().toString())) {

      final ByteBuffer buf = ByteBuffer.allocate(4);
      for (int i = MIN; i < MAX; i++) {
        buf.putInt(i);
        buf.flip();

        db.put(buf.array(), buf.array());

        buf.clear();
      }
    }
  }

  private int[] readAllWithJavaComparator(final Path dir,
      final AbstractComparator comparator) throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setComparator(comparator);
         final RocksDB db =
             RocksDB.open(options, dir.toAbsolutePath().toString())) {

      try (final RocksIterator it = db.newIterator()) {
        it.seekToFirst();

        final ByteBuffer buf = ByteBuffer.allocate(4);
        final int[] keys = new int[MAX - MIN];
        int idx = 0;
        while (it.isValid()) {
          buf.put(it.key());
          buf.flip();

          final int thisKey = buf.getInt();
          keys[idx++] = thisKey;

          buf.clear();

          it.next();
        }

        return keys;
      }
    }
  }

  private int[] readAllWithCppComparator(final Path dir,
      final BuiltinComparator comparator) throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setComparator(comparator);
         final RocksDB db =
             RocksDB.open(options, dir.toAbsolutePath().toString())) {

      try (final RocksIterator it = db.newIterator()) {
        it.seekToFirst();

        final ByteBuffer buf = ByteBuffer.allocate(4);
        final int[] keys = new int[MAX - MIN];
        int idx = 0;
        while (it.isValid()) {
          buf.put(it.key());
          buf.flip();

          final int thisKey = buf.getInt();
          keys[idx++] = thisKey;

          buf.clear();

          it.next();
        }

        return keys;
      }
    }
  }
}
