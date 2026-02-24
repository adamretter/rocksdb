//  Copyright (c) Meta Platforms, Inc. and affiliates.
//
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;

public class PutMultiplePartsTest {

  static IntStream parameters() {
    return IntStream.of(2, 3, 250, 20000);
  }

  @TempDir
  File dbFolder;

  @ParameterizedTest
  @MethodSource("parameters")
  public void putUntracked(final int numParts) throws RocksDBException {
    try (final Options options = new Options().setCreateIfMissing(true);
         final TransactionDBOptions txnDbOptions = new TransactionDBOptions();
         final TransactionDB txnDB =
             TransactionDB.open(options, txnDbOptions, dbFolder.getAbsolutePath())) {
      try (final Transaction transaction = txnDB.beginTransaction(new WriteOptions())) {
        final byte[][] keys = generateItems("key", ":", numParts);
        final byte[][] values = generateItems("value", "", numParts);
        transaction.putUntracked(keys, values);
        transaction.commit();
      }
      txnDB.syncWal();
    }

    validateResults(numParts);
  }

  @ParameterizedTest
  @MethodSource("parameters")
  public void put(final int numParts) throws RocksDBException {
    try (final Options options = new Options().setCreateIfMissing(true);
         final TransactionDBOptions txnDbOptions = new TransactionDBOptions();
         final TransactionDB txnDB =
             TransactionDB.open(options, txnDbOptions, dbFolder.getAbsolutePath())) {
      try (final Transaction transaction = txnDB.beginTransaction(new WriteOptions())) {
        final byte[][] keys = generateItems("key", ":", numParts);
        final byte[][] values = generateItems("value", "", numParts);
        transaction.put(keys, values);
        transaction.commit();
      }
      txnDB.syncWal();
    }

    validateResults(numParts);
  }

  @ParameterizedTest
  @MethodSource("parameters")
  public void putUntrackedCF(final int numParts) throws RocksDBException {
    try (final Options options = new Options().setCreateIfMissing(true);
         final TransactionDBOptions txnDbOptions = new TransactionDBOptions();
         final TransactionDB txnDB =
             TransactionDB.open(options, txnDbOptions, dbFolder.getAbsolutePath());
         final ColumnFamilyHandle columnFamilyHandle =
             txnDB.createColumnFamily(new ColumnFamilyDescriptor("cfTest".getBytes()))) {
      try (final Transaction transaction = txnDB.beginTransaction(new WriteOptions())) {
        final byte[][] keys = generateItems("key", ":", numParts);
        final byte[][] values = generateItems("value", "", numParts);
        transaction.putUntracked(columnFamilyHandle, keys, values);
        transaction.commit();
      }
      txnDB.syncWal();
    }

    validateResultsCF(numParts);
  }

  @ParameterizedTest
  @MethodSource("parameters")
  public void putCF(final int numParts) throws RocksDBException {
    try (final Options options = new Options().setCreateIfMissing(true);
         final TransactionDBOptions txnDbOptions = new TransactionDBOptions();
         final TransactionDB txnDB =
             TransactionDB.open(options, txnDbOptions, dbFolder.getAbsolutePath());
         final ColumnFamilyHandle columnFamilyHandle =
             txnDB.createColumnFamily(new ColumnFamilyDescriptor("cfTest".getBytes()))) {
      try (final Transaction transaction = txnDB.beginTransaction(new WriteOptions())) {
        final byte[][] keys = generateItems("key", ":", numParts);
        final byte[][] values = generateItems("value", "", numParts);
        transaction.put(columnFamilyHandle, keys, values);
        transaction.commit();
      }
      txnDB.syncWal();
    }

    validateResultsCF(numParts);
  }

  private void validateResults(final int numParts) throws RocksDBException {
    try (final RocksDB db = RocksDB.open(new Options(), dbFolder.getAbsolutePath())) {
      final List<byte[]> keys = generateItemsAsList("key", ":", numParts);
      final byte[][] values = generateItems("value", "", numParts);

      final StringBuilder singleKey = new StringBuilder();
      for (int i = 0; i < numParts; i++) {
        singleKey.append(new String(keys.get(i), StandardCharsets.UTF_8));
      }
      final byte[] result = db.get(singleKey.toString().getBytes());
      final StringBuilder singleValue = new StringBuilder();
      for (int i = 0; i < numParts; i++) {
        singleValue.append(new String(values[i], StandardCharsets.UTF_8));
      }
      assertThat(result).isEqualTo(singleValue.toString().getBytes());
    }
  }

  private void validateResultsCF(final int numParts) throws RocksDBException {
    final List<ColumnFamilyDescriptor> columnFamilyDescriptors = new ArrayList<>();
    columnFamilyDescriptors.add(new ColumnFamilyDescriptor("cfTest".getBytes()));
    columnFamilyDescriptors.add(new ColumnFamilyDescriptor("default".getBytes()));
    final List<ColumnFamilyHandle> columnFamilyHandles = new ArrayList<>();
    try (final RocksDB db = RocksDB.open(new DBOptions(), dbFolder.getAbsolutePath(),
             columnFamilyDescriptors, columnFamilyHandles)) {
      final List<byte[]> keys = generateItemsAsList("key", ":", numParts);
      final byte[][] values = generateItems("value", "", numParts);

      final StringBuilder singleKey = new StringBuilder();
      for (int i = 0; i < numParts; i++) {
        singleKey.append(new String(keys.get(i), StandardCharsets.UTF_8));
      }
      final byte[] result = db.get(columnFamilyHandles.get(0), singleKey.toString().getBytes());
      final StringBuilder singleValue = new StringBuilder();
      for (int i = 0; i < numParts; i++) {
        singleValue.append(new String(values[i], StandardCharsets.UTF_8));
      }
      assertThat(result).isEqualTo(singleValue.toString().getBytes());
    }
  }

  private byte[][] generateItems(final String prefix, final String suffix, final int numItems) {
    return generateItemsAsList(prefix, suffix, numItems).toArray(new byte[0][0]);
  }

  private List<byte[]> generateItemsAsList(
      final String prefix, final String suffix, final int numItems) {
    final List<byte[]> items = new ArrayList<>();
    for (int i = 0; i < numItems; i++) {
      items.add((prefix + i + suffix).getBytes());
    }
    return items;
  }
}
