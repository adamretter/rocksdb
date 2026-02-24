// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
package org.rocksdb;


import java.io.File;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CheckPointTest {

  @RegisterExtension
  public static final RocksNativeLibraryResource ROCKS_NATIVE_LIBRARY_RESOURCE =
      new RocksNativeLibraryResource();

  @TempDir
  public File dbFolder;

  @TempDir
  public File checkpointFolder;

  @Test
  public void checkPoint() throws RocksDBException {
    try (final Options options = new Options().
        setCreateIfMissing(true)) {

      try (final RocksDB db = RocksDB.open(options,
          dbFolder.getAbsolutePath())) {
        db.put("key".getBytes(), "value".getBytes());
        try (final Checkpoint checkpoint = Checkpoint.create(db)) {
          checkpoint.createCheckpoint(checkpointFolder.getAbsolutePath() + "/snapshot1");
          db.put("key2".getBytes(), "value2".getBytes());
          checkpoint.createCheckpoint(checkpointFolder.getAbsolutePath() + "/snapshot2");
        }
      }

      try (final RocksDB db = RocksDB.open(options,
          checkpointFolder.getAbsolutePath() +
              "/snapshot1")) {
        assertThat(new String(db.get("key".getBytes()))).
            isEqualTo("value");
        assertThat(db.get("key2".getBytes())).isNull();
      }

      try (final RocksDB db = RocksDB.open(options,
          checkpointFolder.getAbsolutePath() +
              "/snapshot2")) {
        assertThat(new String(db.get("key".getBytes()))).
            isEqualTo("value");
        assertThat(new String(db.get("key2".getBytes()))).
            isEqualTo("value2");
      }
    }
  }

  @Test
  public void exportColumnFamily() throws RocksDBException {
    try (final Options options = new Options().setCreateIfMissing(true)) {
      try (final RocksDB db = RocksDB.open(options, dbFolder.getAbsolutePath())) {
        db.put("key".getBytes(), "value".getBytes());
        try (final Checkpoint checkpoint = Checkpoint.create(db)) {
          ExportImportFilesMetaData metadata1 =
              checkpoint.exportColumnFamily(db.getDefaultColumnFamily(),
                  checkpointFolder.getAbsolutePath() + "/export_column_family1");
          db.put("key2".getBytes(), "value2".getBytes());
          ExportImportFilesMetaData metadata2 =
              checkpoint.exportColumnFamily(db.getDefaultColumnFamily(),
                  checkpointFolder.getAbsolutePath() + "/export_column_family2");
        }
      }
    }
  }

  @Test
  public void failIfDbIsNull() {
    assertThrows(IllegalArgumentException.class, () -> {
        try (final Checkpoint ignored = Checkpoint.create(null)) {
        }
    });
  }

  @Test
  public void failIfDbNotInitialized() {
    assertThrows(IllegalStateException.class, () -> {
        try (final RocksDB db = RocksDB.open(
            dbFolder.getAbsolutePath())) {
          db.close();
          Checkpoint.create(db);
        }
    });
  }

  @Test
  public void failWithIllegalPath() {
    assertThrows(RocksDBException.class, () -> {
        try (final RocksDB db = RocksDB.open(dbFolder.getAbsolutePath());
             final Checkpoint checkpoint = Checkpoint.create(db)) {
          checkpoint.createCheckpoint("/Z:///:\\C:\\TZ/-");
        }
    });
  }
}
