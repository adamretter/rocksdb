// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Extension to load the RocksDB JNI library.
 */
public class RocksNativeLibraryResource implements BeforeAllCallback {
  @Override
  public void beforeAll(ExtensionContext context) {
    RocksDB.loadLibrary();
  }
}
