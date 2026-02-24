// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

public class BackupEngineOptionsTest {
  private static final String ARBITRARY_PATH = System.getProperty("java.io.tmpdir");

  @RegisterExtension
  public static final RocksNativeLibraryResource ROCKS_NATIVE_LIBRARY_RESOURCE =
      new RocksNativeLibraryResource();

  public static final Random rand = PlatformRandomHelper.
      getPlatformSpecificRandomFactory();

  @Test
  public void backupDir() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      assertThat(backupEngineOptions.backupDir()).isEqualTo(ARBITRARY_PATH);
    }
  }

  @Test
  public void env() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      assertThat(backupEngineOptions.backupEnv()).isNull();

      try(final Env env = new RocksMemEnv(Env.getDefault())) {
        backupEngineOptions.setBackupEnv(env);
        assertThat(backupEngineOptions.backupEnv()).isEqualTo(env);
      }
    }
  }

  @Test
  public void shareTableFiles() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      final boolean value = rand.nextBoolean();
      backupEngineOptions.setShareTableFiles(value);
      assertThat(backupEngineOptions.shareTableFiles()).isEqualTo(value);
    }
  }

  @Test
  public void infoLog() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      assertThat(backupEngineOptions.infoLog()).isNull();

      try (final Options options = new Options(); final Logger logger = new Logger(options) {
        @Override
        protected void log(final InfoLogLevel infoLogLevel, final String logMsg) {}
      }) {
        backupEngineOptions.setInfoLog(logger);
        assertThat(backupEngineOptions.infoLog()).isEqualTo(logger);
      }
    }
  }

  @Test
  public void sync() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      final boolean value = rand.nextBoolean();
      backupEngineOptions.setSync(value);
      assertThat(backupEngineOptions.sync()).isEqualTo(value);
    }
  }

  @Test
  public void destroyOldData() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      final boolean value = rand.nextBoolean();
      backupEngineOptions.setDestroyOldData(value);
      assertThat(backupEngineOptions.destroyOldData()).isEqualTo(value);
    }
  }

  @Test
  public void backupLogFiles() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      final boolean value = rand.nextBoolean();
      backupEngineOptions.setBackupLogFiles(value);
      assertThat(backupEngineOptions.backupLogFiles()).isEqualTo(value);
    }
  }

  @Test
  public void backupRateLimit() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      final long value = Math.abs(rand.nextLong());
      backupEngineOptions.setBackupRateLimit(value);
      assertThat(backupEngineOptions.backupRateLimit()).isEqualTo(value);
      // negative will be mapped to 0
      backupEngineOptions.setBackupRateLimit(-1);
      assertThat(backupEngineOptions.backupRateLimit()).isEqualTo(0);
    }
  }

  @Test
  public void backupRateLimiter() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      assertThat(backupEngineOptions.backupEnv()).isNull();

      try(final RateLimiter backupRateLimiter =
              new RateLimiter(999)) {
        backupEngineOptions.setBackupRateLimiter(backupRateLimiter);
        assertThat(backupEngineOptions.backupRateLimiter()).isEqualTo(backupRateLimiter);
      }
    }
  }

  @Test
  public void restoreRateLimit() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      final long value = Math.abs(rand.nextLong());
      backupEngineOptions.setRestoreRateLimit(value);
      assertThat(backupEngineOptions.restoreRateLimit()).isEqualTo(value);
      // negative will be mapped to 0
      backupEngineOptions.setRestoreRateLimit(-1);
      assertThat(backupEngineOptions.restoreRateLimit()).isEqualTo(0);
    }
  }

  @Test
  public void restoreRateLimiter() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      assertThat(backupEngineOptions.backupEnv()).isNull();

      try(final RateLimiter restoreRateLimiter =
              new RateLimiter(911)) {
        backupEngineOptions.setRestoreRateLimiter(restoreRateLimiter);
        assertThat(backupEngineOptions.restoreRateLimiter()).isEqualTo(restoreRateLimiter);
      }
    }
  }

  @Test
  public void shareFilesWithChecksum() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      final boolean value = rand.nextBoolean();
      backupEngineOptions.setShareFilesWithChecksum(value);
      assertThat(backupEngineOptions.shareFilesWithChecksum()).isEqualTo(value);
    }
  }

  @Test
  public void maxBackgroundOperations() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      final int value = rand.nextInt();
      backupEngineOptions.setMaxBackgroundOperations(value);
      assertThat(backupEngineOptions.maxBackgroundOperations()).isEqualTo(value);
    }
  }

  @Test
  public void callbackTriggerIntervalSize() {
    try (final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH)) {
      final long value = rand.nextLong();
      backupEngineOptions.setCallbackTriggerIntervalSize(value);
      assertThat(backupEngineOptions.callbackTriggerIntervalSize()).isEqualTo(value);
    }
  }

  @Test
  public void failBackupDirIsNull() {
    assertThrows(IllegalArgumentException.class, () -> {
      try (final BackupEngineOptions ignored = new BackupEngineOptions(null)) {
        //no-op
      }
    });
  }

  @Test
  public void failBackupDirIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.backupDir();
      }
    });
  }

  @Test
  public void failSetShareTableFilesIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.setShareTableFiles(true);
      }
    });
  }

  @Test
  public void failShareTableFilesIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.shareTableFiles();
      }
    });
  }

  @Test
  public void failSetSyncIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.setSync(true);
      }
    });
  }

  @Test
  public void failSyncIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.sync();
      }
    });
  }

  @Test
  public void failSetDestroyOldDataIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.setDestroyOldData(true);
      }
    });
  }

  @Test
  public void failDestroyOldDataIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.destroyOldData();
      }
    });
  }

  @Test
  public void failSetBackupLogFilesIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.setBackupLogFiles(true);
      }
    });
  }

  @Test
  public void failBackupLogFilesIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.backupLogFiles();
      }
    });
  }

  @Test
  public void failSetBackupRateLimitIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.setBackupRateLimit(1);
      }
    });
  }

  @Test
  public void failBackupRateLimitIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.backupRateLimit();
      }
    });
  }

  @Test
  public void failSetRestoreRateLimitIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.setRestoreRateLimit(1);
      }
    });
  }

  @Test
  public void failRestoreRateLimitIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.restoreRateLimit();
      }
    });
  }

  @Test
  public void failSetShareFilesWithChecksumIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.setShareFilesWithChecksum(true);
      }
    });
  }

  @Test
  public void failShareFilesWithChecksumIfDisposed() {
    assertThrows(AssertionError.class, () -> {
      try (final BackupEngineOptions options = setupUninitializedBackupEngineOptions()) {
        options.shareFilesWithChecksum();
      }
    });
  }

  private BackupEngineOptions setupUninitializedBackupEngineOptions() {
    final BackupEngineOptions backupEngineOptions = new BackupEngineOptions(ARBITRARY_PATH);
    backupEngineOptions.close();
    return backupEngineOptions;
  }
}
