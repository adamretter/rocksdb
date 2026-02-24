// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import java.io.File;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

public class SstPartitionerTest {
  @RegisterExtension
  public static final RocksNativeLibraryResource ROCKS_NATIVE_LIBRARY_RESOURCE =
      new RocksNativeLibraryResource();

  @TempDir public File dbFolder;

  @Test
  public void sstFixedPrefix() throws RocksDBException {
    try (final SstPartitionerFixedPrefixFactory factory = new SstPartitionerFixedPrefixFactory(4);
         final Options opt =
             new Options().setCreateIfMissing(true).setSstPartitionerFactory(factory);
         final RocksDB db = RocksDB.open(opt, dbFolder.getAbsolutePath())) {
      // writing (long)100 under key
      db.put("aaaa1".getBytes(), "A".getBytes());
      db.put("bbbb1".getBytes(), "B".getBytes());
      db.flush(new FlushOptions());

      db.put("aaaa0".getBytes(), "A2".getBytes());
      db.put("aaaa2".getBytes(), "A2".getBytes());
      db.flush(new FlushOptions());

      db.compactRange();

      final List<LiveFileMetaData> metadata = db.getLiveFilesMetaData();
      assertThat(metadata.size()).isEqualTo(2);
    }
  }

  @Test
  public void sstFixedPrefixFamily() throws RocksDBException {
    final byte[] cfName = "new_cf".getBytes(UTF_8);
    final ColumnFamilyDescriptor cfDescriptor = new ColumnFamilyDescriptor(cfName,
        new ColumnFamilyOptions().setSstPartitionerFactory(
            new SstPartitionerFixedPrefixFactory(4)));

    try (final Options opt = new Options().setCreateIfMissing(true);
         final RocksDB db = RocksDB.open(opt, dbFolder.getAbsolutePath())) {
      final ColumnFamilyHandle columnFamilyHandle = db.createColumnFamily(cfDescriptor);

      // writing (long)100 under key
      db.put(columnFamilyHandle, "aaaa1".getBytes(), "A".getBytes());
      db.put(columnFamilyHandle, "bbbb1".getBytes(), "B".getBytes());
      db.flush(new FlushOptions(), columnFamilyHandle);

      db.put(columnFamilyHandle, "aaaa0".getBytes(), "A2".getBytes());
      db.put(columnFamilyHandle, "aaaa2".getBytes(), "A2".getBytes());
      db.flush(new FlushOptions(), columnFamilyHandle);

      db.compactRange(columnFamilyHandle);

      final List<LiveFileMetaData> metadata = db.getLiveFilesMetaData();
      assertThat(metadata.size()).isEqualTo(2);
    }
  }
}
