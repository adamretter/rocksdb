//  Copyright (c) Meta Platforms, Inc. and affiliates.
//
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

public class ConcurrentTaskLimiterTest {
  @RegisterExtension
  public static final RocksNativeLibraryResource ROCKS_NATIVE_LIBRARY_RESOURCE =
      new RocksNativeLibraryResource();

  private static final String NAME = "name";

  private ConcurrentTaskLimiter concurrentTaskLimiter;

  @BeforeEach
  public void beforeTest() {
    concurrentTaskLimiter = new ConcurrentTaskLimiterImpl(NAME, 3);
  }

  @Test
  public void name() {
    assertEquals(NAME, concurrentTaskLimiter.name());
  }

  @Test
  public void outstandingTask() {
    assertEquals(0, concurrentTaskLimiter.outstandingTask());
  }

  @Test
  public void setMaxOutstandingTask() {
    assertEquals(concurrentTaskLimiter, concurrentTaskLimiter.setMaxOutstandingTask(4));
    assertEquals(0, concurrentTaskLimiter.outstandingTask());
  }

  @Test
  public void resetMaxOutstandingTask() {
    assertEquals(concurrentTaskLimiter, concurrentTaskLimiter.resetMaxOutstandingTask());
    assertEquals(0, concurrentTaskLimiter.outstandingTask());
  }

  @AfterEach
  public void afterTest() {
    concurrentTaskLimiter.close();
  }
}
