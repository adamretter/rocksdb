// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).
package org.rocksdb;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ReadOnlyTest {

  @RegisterExtension
  public static final RocksNativeLibraryResource ROCKS_NATIVE_LIBRARY_RESOURCE =
      new RocksNativeLibraryResource();

  @TempDir
  public File dbFolder;

  @Test
  public void readOnlyOpen() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true);
         final RocksDB db = RocksDB.open(options,
             dbFolder.getAbsolutePath())) {
      db.put("key".getBytes(), "value".getBytes());
    }
    try (final RocksDB db = RocksDB.openReadOnly(dbFolder.getAbsolutePath())) {
      assertThat("value").isEqualTo(new String(db.get("key".getBytes())));
    }

    try (final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()) {
      final List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
      cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));
      final List<ColumnFamilyHandle> columnFamilyHandleList = new ArrayList<>();
      try (final RocksDB db = RocksDB.open(
               dbFolder.getAbsolutePath(), cfDescriptors, columnFamilyHandleList)) {
        columnFamilyHandleList.add(
            db.createColumnFamily(new ColumnFamilyDescriptor("new_cf".getBytes(), cfOpts)));
        columnFamilyHandleList.add(
            db.createColumnFamily(new ColumnFamilyDescriptor("new_cf2".getBytes(), cfOpts)));
        db.put(columnFamilyHandleList.get(2), "key2".getBytes(), "value2".getBytes());
      }

      columnFamilyHandleList.clear();
      try (final RocksDB db = RocksDB.openReadOnly(
               dbFolder.getAbsolutePath(), cfDescriptors, columnFamilyHandleList)) {
        assertThat(db.get("key2".getBytes())).isNull();
        assertThat(db.get(columnFamilyHandleList.get(0), "key2".getBytes())).isNull();
      }

      cfDescriptors.clear();
      cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));
      cfDescriptors.add(new ColumnFamilyDescriptor("new_cf2".getBytes(), cfOpts));
      columnFamilyHandleList.clear();
      try (final RocksDB db = RocksDB.openReadOnly(
               dbFolder.getAbsolutePath(), cfDescriptors, columnFamilyHandleList)) {
        assertThat(new String(db.get(columnFamilyHandleList.get(1), "key2".getBytes())))
            .isEqualTo("value2");
      }
    }
  }

  @Test
  public void failToWriteInReadOnly() throws RocksDBException {
    assertThrows(RocksDBException.class, () -> {
        try (final Options options = new Options().setCreateIfMissing(true)) {
          try (final RocksDB ignored = RocksDB.open(options, dbFolder.getAbsolutePath())) {
            // no-op
          }
        }

        try (final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()) {
          final List<ColumnFamilyDescriptor> cfDescriptors = Collections.singletonList(
              new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));

          final List<ColumnFamilyHandle> readOnlyColumnFamilyHandleList = new ArrayList<>();
          try (final RocksDB rDb = RocksDB.openReadOnly(dbFolder.getAbsolutePath(),
                   cfDescriptors, readOnlyColumnFamilyHandleList)) {
            // test that put fails in readonly mode
            rDb.put("key".getBytes(), "value".getBytes());
          }
        }
    });
  }

  @Test
  public void failToCFWriteInReadOnly() throws RocksDBException {
    assertThrows(RocksDBException.class, () -> {
        try (final Options options = new Options().setCreateIfMissing(true);
             final RocksDB ignored = RocksDB.open(options, dbFolder.getAbsolutePath())) {
          //no-op
        }

        try (final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()) {
          final List<ColumnFamilyDescriptor> cfDescriptors = Collections.singletonList(
              new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));
          final List<ColumnFamilyHandle> readOnlyColumnFamilyHandleList =
              new ArrayList<>();
          try (final RocksDB rDb = RocksDB.openReadOnly(
              dbFolder.getAbsolutePath(), cfDescriptors,
              readOnlyColumnFamilyHandleList)) {
            rDb.put(readOnlyColumnFamilyHandleList.get(0), "key".getBytes(), "value".getBytes());
          }
        }
    });
  }

  @Test
  public void failToRemoveInReadOnly() throws RocksDBException {
    assertThrows(RocksDBException.class, () -> {
        try (final Options options = new Options().setCreateIfMissing(true);
             final RocksDB ignored = RocksDB.open(options, dbFolder.getAbsolutePath())) {
          //no-op
        }

        try (final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()) {
          final List<ColumnFamilyDescriptor> cfDescriptors = Collections.singletonList(
              new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));

          final List<ColumnFamilyHandle> readOnlyColumnFamilyHandleList =
              new ArrayList<>();

          try (final RocksDB rDb = RocksDB.openReadOnly(
              dbFolder.getAbsolutePath(), cfDescriptors,
              readOnlyColumnFamilyHandleList)) {
            rDb.delete("key".getBytes());
          }
        }
    });
  }

  @Test
  public void failToCFRemoveInReadOnly() throws RocksDBException {
    assertThrows(RocksDBException.class, () -> {
        try (final Options options = new Options().setCreateIfMissing(true);
             final RocksDB ignored = RocksDB.open(options, dbFolder.getAbsolutePath())) {
          //no-op
        }

        try (final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()) {
          final List<ColumnFamilyDescriptor> cfDescriptors = Collections.singletonList(
              new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));

          final List<ColumnFamilyHandle> readOnlyColumnFamilyHandleList =
              new ArrayList<>();
          try (final RocksDB rDb = RocksDB.openReadOnly(
              dbFolder.getAbsolutePath(), cfDescriptors,
              readOnlyColumnFamilyHandleList)) {
            rDb.delete(readOnlyColumnFamilyHandleList.get(0), "key".getBytes());
          }
        }
    });
  }

  @Test
  public void failToWriteBatchReadOnly() throws RocksDBException {
    assertThrows(RocksDBException.class, () -> {
        try (final Options options = new Options().setCreateIfMissing(true);
             final RocksDB ignored = RocksDB.open(options, dbFolder.getAbsolutePath())) {
          //no-op
        }

        try (final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()) {
          final List<ColumnFamilyDescriptor> cfDescriptors = Collections.singletonList(
              new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));

          final List<ColumnFamilyHandle> readOnlyColumnFamilyHandleList =
              new ArrayList<>();
          try (final RocksDB rDb = RocksDB.openReadOnly(
              dbFolder.getAbsolutePath(), cfDescriptors,
              readOnlyColumnFamilyHandleList);
               final WriteBatch wb = new WriteBatch();
               final WriteOptions wOpts = new WriteOptions()) {
            wb.put("key".getBytes(), "value".getBytes());
            rDb.write(wOpts, wb);
          }
        }
    });
  }

  @Test
  public void failToCFWriteBatchReadOnly() throws RocksDBException {
    assertThrows(RocksDBException.class, () -> {
        try (final Options options = new Options().setCreateIfMissing(true);
             final RocksDB ignored = RocksDB.open(options, dbFolder.getAbsolutePath())) {
          //no-op
        }

        try (final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()) {
          final List<ColumnFamilyDescriptor> cfDescriptors = Collections.singletonList(
              new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));

          final List<ColumnFamilyHandle> readOnlyColumnFamilyHandleList =
              new ArrayList<>();
          try (final RocksDB rDb = RocksDB.openReadOnly(
              dbFolder.getAbsolutePath(), cfDescriptors,
              readOnlyColumnFamilyHandleList);
               final WriteBatch wb = new WriteBatch();
               final WriteOptions wOpts = new WriteOptions()) {
            wb.put(readOnlyColumnFamilyHandleList.get(0), "key".getBytes(), "value".getBytes());
            rDb.write(wOpts, wb);
          }
        }
    });
  }

  @Test
  public void errorIfWalFileExists() throws RocksDBException, IOException {
    assertThrows(RocksDBException.class, () -> {
        try (final Options options = new Options().setCreateIfMissing(true);
             final RocksDB ignored = RocksDB.open(options, dbFolder.getAbsolutePath())) {
          // no-op
        }
        Files.write(dbFolder.getAbsoluteFile().toPath().resolve("999999.log"), new byte[] {1});

        try (final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()) {
          final List<ColumnFamilyDescriptor> cfDescriptors = Collections.singletonList(
              new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));

          final List<ColumnFamilyHandle> readOnlyColumnFamilyHandleList = new ArrayList<>();
          try (final DBOptions options = new DBOptions();
               final RocksDB ignored =
                   RocksDB.openReadOnly(options, dbFolder.getAbsolutePath(), cfDescriptors,
                       readOnlyColumnFamilyHandleList, true)) {
            // no-op... should have raised an error as errorIfWalFileExists=true
          }
        }
    });
  }
}
