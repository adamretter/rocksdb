// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

public class DefaultEnvTest {

  @RegisterExtension
  public static final RocksNativeLibraryResource ROCKS_NATIVE_LIBRARY_RESOURCE =
      new RocksNativeLibraryResource();

  @TempDir
  public File dbFolder;

  @Test
  public void backgroundThreads() {
    try (final Env defaultEnv = RocksEnv.getDefault()) {
      defaultEnv.setBackgroundThreads(5, Priority.BOTTOM);
      assertThat(defaultEnv.getBackgroundThreads(Priority.BOTTOM)).isEqualTo(5);

      defaultEnv.setBackgroundThreads(5);
      assertThat(defaultEnv.getBackgroundThreads(Priority.LOW)).isEqualTo(5);

      defaultEnv.setBackgroundThreads(5, Priority.LOW);
      assertThat(defaultEnv.getBackgroundThreads(Priority.LOW)).isEqualTo(5);

      defaultEnv.setBackgroundThreads(5, Priority.HIGH);
      assertThat(defaultEnv.getBackgroundThreads(Priority.HIGH)).isEqualTo(5);
    }
  }

  @Test
  public void threadPoolQueueLen() {
    try (final Env defaultEnv = RocksEnv.getDefault()) {
      assertThat(defaultEnv.getThreadPoolQueueLen(Priority.BOTTOM)).isEqualTo(0);
      assertThat(defaultEnv.getThreadPoolQueueLen(Priority.LOW)).isEqualTo(0);
      assertThat(defaultEnv.getThreadPoolQueueLen(Priority.HIGH)).isEqualTo(0);
    }
  }

  @Test
  public void incBackgroundThreadsIfNeeded() {
    try (final Env defaultEnv = RocksEnv.getDefault()) {
      defaultEnv.incBackgroundThreadsIfNeeded(20, Priority.BOTTOM);
      assertThat(defaultEnv.getBackgroundThreads(Priority.BOTTOM)).isGreaterThanOrEqualTo(20);

      defaultEnv.incBackgroundThreadsIfNeeded(20, Priority.LOW);
      assertThat(defaultEnv.getBackgroundThreads(Priority.LOW)).isGreaterThanOrEqualTo(20);

      defaultEnv.incBackgroundThreadsIfNeeded(20, Priority.HIGH);
      assertThat(defaultEnv.getBackgroundThreads(Priority.HIGH)).isGreaterThanOrEqualTo(20);
    }
  }

  @Test
  public void lowerThreadPoolIOPriority() {
    try (final Env defaultEnv = RocksEnv.getDefault()) {
      defaultEnv.lowerThreadPoolIOPriority(Priority.BOTTOM);

      defaultEnv.lowerThreadPoolIOPriority(Priority.LOW);

      defaultEnv.lowerThreadPoolIOPriority(Priority.HIGH);
    }
  }

  @Test
  public void lowerThreadPoolCPUPriority() {
    try (final Env defaultEnv = RocksEnv.getDefault()) {
      defaultEnv.lowerThreadPoolCPUPriority(Priority.BOTTOM);

      defaultEnv.lowerThreadPoolCPUPriority(Priority.LOW);

      defaultEnv.lowerThreadPoolCPUPriority(Priority.HIGH);
    }
  }

  @Test
  public void threadList() throws RocksDBException {
    // We need to open DB first to get at least one thread in thread list.
    try (final RocksDB db = RocksDB.open(dbFolder.getAbsolutePath())) {
      db.put("test-key".getBytes(StandardCharsets.UTF_8),
          "test-value".getBytes(StandardCharsets.UTF_8));
      try (final Env defaultEnv = RocksEnv.getDefault()) {
        final Collection<ThreadStatus> threadList = defaultEnv.getThreadList();
        assertThat(threadList.size()).isGreaterThan(0);
      }
    }
  }

  @Test
  public void threadList_integration() throws RocksDBException {
    try (final Env env = RocksEnv.getDefault();
        final Options opt = new Options()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true)
            .setEnv(env)) {
      // open database
      try (final RocksDB db = RocksDB.open(opt,
          dbFolder.getAbsolutePath())) {

        final List<ThreadStatus> threadList = env.getThreadList();
        assertThat(threadList.size()).isGreaterThan(0);
      }
    }
  }
}
