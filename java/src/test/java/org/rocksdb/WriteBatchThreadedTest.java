// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).
package org.rocksdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

public class WriteBatchThreadedTest {

  static Stream<Integer> threadCounts() {
    return Stream.of(1, 10, 50, 100);
  }

  @TempDir
  public File dbFolder;

  RocksDB db;

  @BeforeEach
  public void setUp() throws Exception {
    RocksDB.loadLibrary();
    final Options options = new Options()
        .setCreateIfMissing(true)
        .setIncreaseParallelism(32);
    db = RocksDB.open(options, dbFolder.getAbsolutePath());
    assert (db != null);
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (db != null) {
      db.close();
    }
  }

  @ParameterizedTest(name = "threadedWrites(threadCount={0})")
  @MethodSource("threadCounts")
  public void threadedWrites(int threadCount) throws InterruptedException, ExecutionException {
    final List<Callable<Void>> callables = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      final int offset = i * 100;
      callables.add(() -> {
        try (final WriteBatch wb = new WriteBatch();
             final WriteOptions w_opt = new WriteOptions()) {
          for (int i1 = offset; i1 < offset + 100; i1++) {
            wb.put(ByteBuffer.allocate(4).putInt(i1).array(), "parallel rocks test".getBytes());
          }
          db.write(w_opt, wb);
        }
        return null;
      });
    }

    //submit the callables
    final ExecutorService executorService =
        Executors.newFixedThreadPool(threadCount);
    try {
      final ExecutorCompletionService<Void> completionService =
          new ExecutorCompletionService<>(executorService);
      final Set<Future<Void>> futures = new HashSet<>();
      for (final Callable<Void> callable : callables) {
        futures.add(completionService.submit(callable));
      }

      while (futures.size() > 0) {
        final Future<Void> future = completionService.take();
        futures.remove(future);

        try {
          future.get();
        } catch (final ExecutionException e) {
          for (final Future<Void> f : futures) {
            f.cancel(true);
          }

          throw e;
        }
      }
    } finally {
      executorService.shutdown();
      executorService.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}
