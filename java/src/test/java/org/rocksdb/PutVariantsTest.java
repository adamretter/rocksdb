// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.rocksdb.MergeTest.longFromByteArray;
import static org.rocksdb.MergeTest.longToByteArray;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
public class PutVariantsTest {
  @FunctionalInterface
  interface FunctionPut<PDatabase, PLeft, PRight> {
    public void apply(PDatabase db, PLeft two, PRight three) throws RocksDBException;
  }

    static Stream<PutVariantsTest.FunctionPut<RocksDB, byte[], byte[]>> data() {
    return Stream.of(RocksDB::put,
        (db, left, right)
            -> db.put(new WriteOptions(), left, right),
        (db, left, right)
            -> {
          final byte[] left0 =
              ("1234567" + new String(left, StandardCharsets.UTF_8) + "890").getBytes();
          final byte[] right0 =
              ("1234" + new String(right, StandardCharsets.UTF_8) + "567890ab").getBytes();
          db.put(left0, 7, left.length, right0, 4, right.length);
        },
        (db, left, right)
            -> {
          final byte[] left0 =
              ("1234567" + new String(left, StandardCharsets.UTF_8) + "890").getBytes();
          final byte[] right0 =
              ("1234" + new String(right, StandardCharsets.UTF_8) + "567890ab").getBytes();
          db.put(new WriteOptions(), left0, 7, left.length, right0, 4, right.length);
        },
        (db, left, right)
            -> {
          final ByteBuffer bbLeft = ByteBuffer.allocateDirect(100);
          final ByteBuffer bbRight = ByteBuffer.allocateDirect(100);
          bbLeft.put(left).flip();
          bbRight.put(right).flip();
          db.put(new WriteOptions(), bbLeft, bbRight);
        },
        (db, left, right) -> {
          final ByteBuffer bbLeft = ByteBuffer.allocate(100);
          final ByteBuffer bbRight = ByteBuffer.allocate(100);
          bbLeft.put(left).flip();
          bbRight.put(right).flip();
          db.put(new WriteOptions(), bbLeft, bbRight);
        });
  }

  
  @RegisterExtension
  public static final RocksNativeLibraryResource ROCKS_NATIVE_LIBRARY_RESOURCE =
      new RocksNativeLibraryResource();

  @TempDir
  File dbFolder;

  @ParameterizedTest
  @MethodSource("data")
  public void writeAndRead(final PutVariantsTest.FunctionPut<RocksDB, byte[], byte[]> putFunction) throws InterruptedException, RocksDBException {
    try (final UInt64AddOperator uint64AddOperator = new UInt64AddOperator();
         final Options opt =
             new Options().setCreateIfMissing(true).setMergeOperator(uint64AddOperator);
         final RocksDB db = RocksDB.open(opt, dbFolder.getAbsolutePath())) {
      // Writing (long)100 under key
      putFunction.apply(db, "key".getBytes(), longToByteArray(100));

      final byte[] value = db.get("key".getBytes());
      final long longValue = longFromByteArray(value);

      assertThat(longValue).isEqualTo(100);
    }
  }
}
