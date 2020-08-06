// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).
#pragma once

#ifndef ROCKSDB_LITE

#include <limits>
#include <string>
#include <vector>

#include "db/merge_context.h"
#include "memtable/skiplist.h"
#include "options/db_options.h"
#include "port/port.h"
#include "rocksdb/comparator.h"
#include "rocksdb/iterator.h"
#include "rocksdb/slice.h"
#include "rocksdb/status.h"
#include "rocksdb/utilities/write_batch_with_index.h"

namespace ROCKSDB_NAMESPACE {

class MergeContext;
struct Options;

// when direction == forward
// * current_at_base_ <=> base_iterator > delta_iterator
// when direction == backwards
// * current_at_base_ <=> base_iterator < delta_iterator
// always:
// * equal_keys_ <=> base_iterator == delta_iterator
class BaseDeltaIterator : public Iterator {
 public:
  BaseDeltaIterator(Iterator* base_iterator, WBWIIterator* delta_iterator,
                    const Comparator* comparator,
                    const ReadOptions* read_options = nullptr)
      : progress_(Progress::TO_BE_DETERMINED),
        current_at_base_(true),
        equal_keys_(false),
        status_(Status::OK()),
        base_iterator_(base_iterator),
        delta_iterator_(delta_iterator),
        comparator_(comparator),
        read_options_(read_options) {}

  ~BaseDeltaIterator() override {}

  bool Valid() const override {
    return status_.ok() ? (current_at_base_ ? BaseValid() : DeltaValid())
                        : false;
  }

  void SeekToFirst() override {
    progress_ = Progress::SEEK_TO_FIRST;
    base_iterator_->SeekToFirst();
    delta_iterator_->SeekToFirst();
    UpdateCurrent();
  }

  void SeekToLast() override {
    progress_ = Progress::SEEK_TO_LAST;

    // is there an upper bound constraint on base_iterator_?
    const Slice* base_upper_bound = base_iterator_upper_bound();
    if (base_upper_bound != nullptr) {
      // yes, and is base_iterator already constrained by an upper_bound?
      if (!base_iterator_->ChecksUpperBound()) {
        // no, so we have to seek it to before base_upper_bound
        base_iterator_->Seek(*(base_upper_bound));
        if (base_iterator_->Valid()) {
          base_iterator_->Prev();  // upper bound should be exclusive!
        } else {
          // the base_upper_bound is beyond the base_iterator, so just
          // SeekToLast()
          base_iterator_->SeekToLast();
        }
      } else {
        // yes, so the base_iterator will take care of base_upper_bound
        base_iterator_->SeekToLast();
      }
    } else {
      // no upper cound constraint, so just SeekToLast
      base_iterator_->SeekToLast();
    }

    // is there an upper bound constraint on delta_iterator_?
    if (read_options_ != nullptr &&
        read_options_->iterate_upper_bound != nullptr) {
      // delta iterator does not itself support iterate_upper_bound,
      // so we have to seek it to before iterate_upper_bound
      delta_iterator_->Seek(*(read_options_->iterate_upper_bound));
      if (delta_iterator_->Valid()) {
        delta_iterator_->Prev();  // upper bound should be exclusive!
      } else {
        // the upper_bound is beyond the delta_iterator, so just SeekToLast()
        delta_iterator_->SeekToLast();
      }

    } else {
      // no upper bound constraint, so just SeekToLast
      delta_iterator_->SeekToLast();
    }

    UpdateCurrent();
  }

  void Seek(const Slice& k) override {
    progress_ = Progress::SEEK;
    base_iterator_->Seek(k);
    delta_iterator_->Seek(k);
    UpdateCurrent();
  }

  void SeekForPrev(const Slice& k) override {
    progress_ = Progress::SEEK_FOR_PREV;
    base_iterator_->SeekForPrev(k);
    delta_iterator_->SeekForPrev(k);
    UpdateCurrent();
  }

  void Next() override {
    if (!Valid()) {
      status_ = Status::NotSupported("Next() on invalid iterator");
      return;
    }

    if (IsMovingBackward()) {
      // currently moving backward, so we need to change direction
      // if our direction was backward and we're not equal, we have two states:
      //     * both iterators are valid: we're already in a good state (current
      // shows to smaller)
      //     * only one iterator is valid: we need to advance that iterator

      equal_keys_ = false;
      if (!BaseValid()) {
        assert(DeltaValid());
        if (progress_ != Progress::SEEK_TO_LAST) {
          base_iterator_->SeekToFirst();
        }
      } else if (!DeltaValid()) {
        if (progress_ != Progress::SEEK_TO_LAST) {
          delta_iterator_->SeekToFirst();
        }
      } else {
        progress_ = Progress::FORWARD;
        if (current_at_base_) {
          // Change delta from larger than base to smaller
          AdvanceDelta();
        } else {
          // Change base from larger than delta to smaller
          AdvanceBase();
        }

        if (DeltaValid() && BaseValid()) {
          if (comparator_->Equal(delta_iterator_->Entry().key,
                                 base_iterator_->key())) {
            equal_keys_ = true;
          }
        }
      }
    }

    progress_ = Progress::FORWARD;

    Advance();
  }

  void Prev() override {
    if (!Valid()) {
      status_ = Status::NotSupported("Prev() on invalid iterator");
      return;
    }

    if (IsMovingForward()) {
      // currently moving forward, so we need to change direction
      // if our direction was forward and we're not equal, we have two states:
      //     * both iterators are valid: we're already in a good state (current
      // shows to smaller)
      //     * only one iterator is valid: we need to advance that iterator

      equal_keys_ = false;

      if (!BaseValid()) {
        assert(DeltaValid());
        if (progress_ != Progress::SEEK_TO_FIRST) {
          base_iterator_->SeekToLast();
        }
      } else if (!DeltaValid()) {
        if (progress_ != Progress::SEEK_TO_FIRST) {
          delta_iterator_->SeekToLast();
        }
      } else {
        progress_ = Progress::BACKWARD;

        if (current_at_base_) {
          // Change delta from less advanced than base to more advanced
          AdvanceDelta();
        } else {
          // Change base from less advanced than delta to more advanced
          AdvanceBase();
        }
      }

      if (DeltaValid() && BaseValid()) {
        if (comparator_->Equal(delta_iterator_->Entry().key,
                               base_iterator_->key())) {
          equal_keys_ = true;
        }
      }
    }

    progress_ = Progress::BACKWARD;

    Advance();
  }

  Slice key() const override {
    return current_at_base_ ? base_iterator_->key()
                            : delta_iterator_->Entry().key;
  }

  Slice value() const override {
    return current_at_base_ ? base_iterator_->value()
                            : delta_iterator_->Entry().value;
  }

  Status status() const override {
    if (!status_.ok()) {
      return status_;
    }
    if (!base_iterator_->status().ok()) {
      return base_iterator_->status();
    }
    return delta_iterator_->status();
  }

  bool ChecksLowerBound() const override { return false; }

  const Slice* lower_bound() const override {
    return base_iterator_lower_bound();
  }

  bool ChecksUpperBound() const override { return true; }

  const Slice* upper_bound() const override {
    return base_iterator_upper_bound();
  }

  void Invalidate(Status s) { status_ = s; }

 private:
  void AssertInvariants() {
#ifndef NDEBUG
    bool not_ok = false;
    if (!base_iterator_->status().ok()) {
      assert(!base_iterator_->Valid());
      not_ok = true;
    }
    if (!delta_iterator_->status().ok()) {
      assert(!delta_iterator_->Valid());
      not_ok = true;
    }
    if (not_ok) {
      assert(!Valid());
      assert(!status().ok());
      return;
    }

    if (!Valid()) {
      return;
    }
    if (!BaseValid()) {
      assert(!current_at_base_ && delta_iterator_->Valid());
      return;
    }
    if (!DeltaValid()) {
      assert(current_at_base_ && base_iterator_->Valid());
      return;
    }
    // we don't support those yet
    assert(delta_iterator_->Entry().type != kMergeRecord &&
           delta_iterator_->Entry().type != kLogDataRecord);
    int compare = comparator_->Compare(delta_iterator_->Entry().key,
                                       base_iterator_->key());
    if (IsMovingForward()) {
      // current_at_base -> compare < 0
      assert(!current_at_base_ || compare < 0);
      // !current_at_base -> compare <= 0
      assert(current_at_base_ && compare >= 0);
    } else {
      // current_at_base -> compare > 0
      assert(!current_at_base_ || compare > 0);
      // !current_at_base -> compare <= 0
      assert(current_at_base_ && compare <= 0);
    }
    // equal_keys_ <=> compare == 0
    assert((equal_keys_ || compare != 0) && (!equal_keys_ || compare == 0));
#endif
  }

  void Advance() {
    if (equal_keys_) {
      assert(BaseValid() && DeltaValid());
      AdvanceBase();
      AdvanceDelta();
    } else {
      if (current_at_base_) {
        assert(BaseValid());
        AdvanceBase();
      } else {
        assert(DeltaValid());
        AdvanceDelta();
      }
    }
    UpdateCurrent();
  }

  void AdvanceDelta() {
    if (IsMovingForward()) {
      delta_iterator_->Next();
    } else {
      delta_iterator_->Prev();
    }
  }
  void AdvanceBase() {
    if (IsMovingForward()) {
      base_iterator_->Next();
    } else {
      base_iterator_->Prev();
    }
  }
  bool BaseValid() const {
    // NOTE: we don't need the bounds check on
    // base_iterator if the base iterator has an
    // upper_bounds_check already
    return base_iterator_->Valid() &&
           (base_iterator_->ChecksUpperBound() ? true : BaseIsWithinBounds());
  }
  bool DeltaValid() const {
    return delta_iterator_->Valid() && DeltaIsWithinBounds();
  }
  void UpdateCurrent() {
// Suppress false positive clang analyzer warnings.
#ifndef __clang_analyzer__
    status_ = Status::OK();
    while (true) {
      WriteEntry delta_entry;
      if (DeltaValid()) {
        assert(delta_iterator_->status().ok());
        delta_entry = delta_iterator_->Entry();
      } else if (!delta_iterator_->status().ok()) {
        // Expose the error status and stop.
        current_at_base_ = false;
        return;
      }

      equal_keys_ = false;

      if (!BaseValid()) {
        if (!base_iterator_->status().ok()) {
          // Expose the error status and stop.
          current_at_base_ = true;
          return;
        }

        // Base and Delta have both finished.
        if (!DeltaValid()) {
          // Finished
          return;
        }

        if (read_options_ != nullptr &&
            read_options_->iterate_upper_bound != nullptr) {
          if (comparator_->Compare(delta_entry.key,
                                   *(read_options_->iterate_upper_bound)) >=
              0) {
            // out of upper bound -> finished.
            return;
          }
        }
        if (delta_entry.type == kDeleteRecord ||
            delta_entry.type == kSingleDeleteRecord) {
          AdvanceDelta();
        } else {
          current_at_base_ = false;
          return;
        }

      } else if (!DeltaValid()) {
        // Base is unfinished, but Delta has finished.
        current_at_base_ = true;
        return;

      } else {
        // Base and Delta are both unfinished.

        int compare =
            (IsMovingForward() ? 1 : -1) *
            comparator_->Compare(delta_entry.key, base_iterator_->key());
        if (compare <= 0) {  // delta bigger or equal
          if (compare == 0) {
            equal_keys_ = true;
          }
          if (delta_entry.type != kDeleteRecord &&
              delta_entry.type != kSingleDeleteRecord) {
            current_at_base_ = false;
            return;
          }

          // Delta is less advanced and is delete.
          AdvanceDelta();
          if (equal_keys_) {
            AdvanceBase();
          }

        } else {
          current_at_base_ = true;
          return;
        }
      }
    }

    AssertInvariants();
#endif  // __clang_analyzer__
  }

  /**
   * Returns the upper bound for the base iterator,
   * or nullptr if there is no upper bound.
   *
   * The base iterator may have its own upper bound,
   * if not not we use the upper bound from this
   * iterator's ReadOptions (if present).
   */
  inline const Slice* base_iterator_upper_bound() const {
    const Slice* upper = base_iterator_->upper_bound();
    if (upper == nullptr && read_options_ != nullptr) {
      return read_options_->iterate_upper_bound;
    }
    return upper;
  }

  /**
   * Returns the lower bound for the base iterator,
   * or nullptr if there is no lower bound.
   *
   * The base iterator may have its own lower bound,
   * if not not we use the lower bound from this
   * iterator's ReadOptions (if present).
   */
  inline const Slice* base_iterator_lower_bound() const {
    const Slice* lower = base_iterator_->lower_bound();
    if (lower == nullptr && read_options_ != nullptr) {
      return read_options_->iterate_lower_bound;
    }
    return lower;
  }

  bool BaseIsWithinBounds() const {
    if (IsMovingBackward()) {
      const Slice* lower = base_iterator_lower_bound();
      if (lower != nullptr) {
        return comparator_->Compare(base_iterator_->key(), *lower) >= 0;
      }
    }

    if (IsMovingForward()) {
      const Slice* upper = base_iterator_upper_bound();
      if (upper != nullptr) {
        return comparator_->Compare(base_iterator_->key(), *upper) < 0;
      }
    }

    return true;
  }

  bool DeltaIsWithinBounds() const {
    if (read_options_ != nullptr) {
      if (IsMovingBackward() && read_options_->iterate_lower_bound != nullptr) {
        return comparator_->Compare(delta_iterator_->Entry().key,
                                    *(read_options_->iterate_lower_bound)) >= 0;
      }

      if (IsMovingForward() && read_options_->iterate_upper_bound != nullptr) {
        return comparator_->Compare(delta_iterator_->Entry().key,
                                    *(read_options_->iterate_upper_bound)) < 0;
      }
    }
    return true;
  }

  inline bool IsMovingForward() const { return progress_ < Progress::BACKWARD; }

  inline bool IsMovingBackward() const { return progress_ > Progress::FORWARD; }

  /**
   * Indicates the progression of the BaseDeltaIterator.
   *
   * The numeric ordering of the enumerated values is
   * important as it allows us to easily calculate
   * whether a progression is considered to be generally
   * Forward or Backward. For the logic see
   * BaseDeltaIterator::IsMovingForward() and
   * BaseDeltaIterator::IsMovingBackward().
   */
  enum Progress {
    // initial state, also considered
    // to be a forward progression
    TO_BE_DETERMINED = 0,

    // forward progressions
    SEEK_TO_FIRST = 1,
    SEEK = 2,
    FORWARD = 3,

    // backward progressions
    BACKWARD = 4,
    SEEK_FOR_PREV = 5,
    SEEK_TO_LAST = 6
  };

  Progress progress_;
  bool current_at_base_;
  bool equal_keys_;
  Status status_;
  std::unique_ptr<Iterator> base_iterator_;
  std::unique_ptr<WBWIIterator> delta_iterator_;
  const Comparator* comparator_;  // not owned
  const ReadOptions* read_options_;  // not owned
};

// Key used by skip list, as the binary searchable index of WriteBatchWithIndex.
struct WriteBatchIndexEntry {
  WriteBatchIndexEntry(size_t o, uint32_t c, size_t ko, size_t ksz)
      : offset(o),
        column_family(c),
        key_offset(ko),
        key_size(ksz),
        search_key(nullptr) {}
  // Create a dummy entry as the search key. This index entry won't be backed
  // by an entry from the write batch, but a pointer to the search key. Or a
  // special flag of offset can indicate we are seek to first.
  // @_search_key: the search key
  // @_column_family: column family
  // @is_forward_direction: true for Seek(). False for SeekForPrev()
  // @is_seek_to_first: true if we seek to the beginning of the column family
  //                    _search_key should be null in this case.
  WriteBatchIndexEntry(const Slice* _search_key, uint32_t _column_family,
                       bool is_forward_direction, bool is_seek_to_first)
      // For SeekForPrev(), we need to make the dummy entry larger than any
      // entry who has the same search key. Otherwise, we'll miss those entries.
      : offset(is_forward_direction ? 0 : port::kMaxSizet),
        column_family(_column_family),
        key_offset(0),
        key_size(is_seek_to_first ? kFlagMinInCf : 0),
        search_key(_search_key) {
    assert(_search_key != nullptr || is_seek_to_first);
  }

  // If this flag appears in the key_size, it indicates a
  // key that is smaller than any other entry for the same column family.
  static const size_t kFlagMinInCf = port::kMaxSizet;

  bool is_min_in_cf() const {
    assert(key_size != kFlagMinInCf ||
           (key_offset == 0 && search_key == nullptr));
    return key_size == kFlagMinInCf;
  }

  // offset of an entry in write batch's string buffer. If this is a dummy
  // lookup key, in which case search_key != nullptr, offset is set to either
  // 0 or max, only for comparison purpose. Because when entries have the same
  // key, the entry with larger offset is larger, offset = 0 will make a seek
  // key small or equal than all the entries with the seek key, so that Seek()
  // will find all the entries of the same key. Similarly, offset = MAX will
  // make the entry just larger than all entries with the search key so
  // SeekForPrev() will see all the keys with the same key.
  size_t offset;
  uint32_t column_family;  // c1olumn family of the entry.
  size_t key_offset;       // offset of the key in write batch's string buffer.
  size_t key_size;         // size of the key. kFlagMinInCf indicates
                           // that this is a dummy look up entry for
                           // SeekToFirst() to the beginning of the column
                           // family. We use the flag here to save a boolean
                           // in the struct.

  const Slice* search_key;  // if not null, instead of reading keys from
                            // write batch, use it to compare. This is used
                            // for lookup key.
};

class ReadableWriteBatch : public WriteBatch {
 public:
  explicit ReadableWriteBatch(size_t reserved_bytes = 0, size_t max_bytes = 0)
      : WriteBatch(reserved_bytes, max_bytes) {}
  // Retrieve some information from a write entry in the write batch, given
  // the start offset of the write entry.
  Status GetEntryFromDataOffset(size_t data_offset, WriteType* type, Slice* Key,
                                Slice* value, Slice* blob, Slice* xid) const;
};

class WriteBatchEntryComparator {
 public:
  WriteBatchEntryComparator(const Comparator* _default_comparator,
                            const ReadableWriteBatch* write_batch)
      : default_comparator_(_default_comparator), write_batch_(write_batch) {}
  // Compare a and b. Return a negative value if a is less than b, 0 if they
  // are equal, and a positive value if a is greater than b
  int operator()(const WriteBatchIndexEntry* entry1,
                 const WriteBatchIndexEntry* entry2) const;

  int CompareKey(uint32_t column_family, const Slice& key1,
                 const Slice& key2) const;

  void SetComparatorForCF(uint32_t column_family_id,
                          const Comparator* comparator) {
    if (column_family_id >= cf_comparators_.size()) {
      cf_comparators_.resize(column_family_id + 1, nullptr);
    }
    cf_comparators_[column_family_id] = comparator;
  }

  const Comparator* default_comparator() { return default_comparator_; }

 private:
  const Comparator* default_comparator_;
  std::vector<const Comparator*> cf_comparators_;
  const ReadableWriteBatch* write_batch_;
};

typedef SkipList<WriteBatchIndexEntry*, const WriteBatchEntryComparator&>
    WriteBatchEntrySkipList;

class WBWIIteratorImpl : public WBWIIterator {
 public:
  WBWIIteratorImpl(uint32_t column_family_id,
                   WriteBatchEntrySkipList* skip_list,
                   const ReadableWriteBatch* write_batch,
                   WriteBatchEntryComparator* comparator)
      : column_family_id_(column_family_id),
        skip_list_iter_(skip_list),
        write_batch_(write_batch),
        comparator_(comparator) {}

  ~WBWIIteratorImpl() override {}

  bool Valid() const override {
    if (!skip_list_iter_.Valid()) {
      return false;
    }
    const WriteBatchIndexEntry* iter_entry = skip_list_iter_.key();
    return (iter_entry != nullptr &&
            iter_entry->column_family == column_family_id_);
  }

  void SeekToFirst() override {
    WriteBatchIndexEntry search_entry(
        nullptr /* search_key */, column_family_id_,
        true /* is_forward_direction */, true /* is_seek_to_first */);
    skip_list_iter_.Seek(&search_entry);
  }

  void SeekToLast() override {
    WriteBatchIndexEntry search_entry(
        nullptr /* search_key */, column_family_id_ + 1,
        true /* is_forward_direction */, true /* is_seek_to_first */);
    skip_list_iter_.Seek(&search_entry);
    if (!skip_list_iter_.Valid()) {
      skip_list_iter_.SeekToLast();
    } else {
      skip_list_iter_.Prev();
    }
  }

  void Seek(const Slice& key) override {
    WriteBatchIndexEntry search_entry(&key, column_family_id_,
                                      true /* is_forward_direction */,
                                      false /* is_seek_to_first */);
    skip_list_iter_.Seek(&search_entry);
  }

  void SeekForPrev(const Slice& key) override {
    WriteBatchIndexEntry search_entry(&key, column_family_id_,
                                      false /* is_forward_direction */,
                                      false /* is_seek_to_first */);
    skip_list_iter_.SeekForPrev(&search_entry);
  }

  void Next() override { skip_list_iter_.Next(); }

  void Prev() override { skip_list_iter_.Prev(); }

  WriteEntry Entry() const override;

  Status status() const override {
    // this is in-memory data structure, so the only way status can be non-ok is
    // through memory corruption
    return Status::OK();
  }

  const WriteBatchIndexEntry* GetRawEntry() const {
    return skip_list_iter_.key();
  }

  bool MatchesKey(uint32_t cf_id, const Slice& key);

 private:
  uint32_t column_family_id_;
  WriteBatchEntrySkipList::Iterator skip_list_iter_;
  const ReadableWriteBatch* write_batch_;
  WriteBatchEntryComparator* comparator_;
};

class WriteBatchWithIndexInternal {
 public:
  // For GetFromBatchAndDB or similar
  explicit WriteBatchWithIndexInternal(DB* db,
                                       ColumnFamilyHandle* column_family);
  // For GetFromBatch or similar
  explicit WriteBatchWithIndexInternal(const DBOptions* db_options,
                                       ColumnFamilyHandle* column_family);

  enum Result { kFound, kDeleted, kNotFound, kMergeInProgress, kError };

  // If batch contains a value for key, store it in *value and return kFound.
  // If batch contains a deletion for key, return Deleted.
  // If batch contains Merge operations as the most recent entry for a key,
  //   and the merge process does not stop (not reaching a value or delete),
  //   prepend the current merge operands to *operands,
  //   and return kMergeInProgress
  // If batch does not contain this key, return kNotFound
  // Else, return kError on error with error Status stored in *s.
  Result GetFromBatch(WriteBatchWithIndex* batch, const Slice& key,
                      std::string* value, bool overwrite_key, Status* s) {
    return GetFromBatch(batch, key, &merge_context_, value, overwrite_key, s);
  }
  Result GetFromBatch(WriteBatchWithIndex* batch, const Slice& key,
                      MergeContext* merge_context, std::string* value,
                      bool overwrite_key, Status* s);
  Status MergeKey(const Slice& key, const Slice* value, std::string* result,
                  Slice* result_operand = nullptr) {
    return MergeKey(key, value, merge_context_, result, result_operand);
  }
  Status MergeKey(const Slice& key, const Slice* value, MergeContext& context,
                  std::string* result, Slice* result_operand = nullptr);

 private:
  DB* db_;
  const DBOptions* db_options_;
  ColumnFamilyHandle* column_family_;
  MergeContext merge_context_;
};

}  // namespace ROCKSDB_NAMESPACE
#endif  // !ROCKSDB_LITE
