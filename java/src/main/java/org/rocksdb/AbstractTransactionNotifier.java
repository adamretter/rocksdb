// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

/**
 * Provides notification to the caller of SetSnapshotOnNextOperation when
 * the actual snapshot gets created
 */
public abstract class AbstractTransactionNotifier
    extends AbstractImmutableNativeReference {

  protected final long nativeHandle_;

  protected AbstractTransactionNotifier() {
    super(true);
    this.nativeHandle_ = createNewTransactionNotifier();
  }

  /**
   * Implement this method to receive notification when a snapshot is
   * requested via {@link Transaction#setSnapshotOnNextOperation()}.
   *
   * @param newSnapshot the snapshot that has been created.
   */
  public abstract void snapshotCreated(final Snapshot newSnapshot);

  /**
   * This is intentionally private as it is the callback hook
   * from JNI
   */
  private void snapshotCreated(final long snapshotHandle) {
    snapshotCreated(new Snapshot(snapshotHandle));
  }

  private native long createNewTransactionNotifier();

  /**
   * Deletes underlying C++ TransactionNotifier pointer.
   *
   * Note that this function should be called only after all
   * Transactions referencing the comparator are closed.
   * Otherwise an undefined behavior will occur.
   */
  @Override
  protected void disposeInternal() {
    disposeInternal(nativeHandle_);
  }
  protected final native void disposeInternal(final long handle);
}
