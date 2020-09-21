//  Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).

#ifndef ROCKSDB_LITE

#include "utilities/write_batch_with_index/write_batch_with_index_internal.h"

#include "db/column_family.h"
#include "db/db_impl/db_impl.h"
#include "db/merge_context.h"
#include "db/merge_helper.h"
#include "rocksdb/comparator.h"
#include "rocksdb/db.h"
#include "rocksdb/utilities/write_batch_with_index.h"
#include "util/cast_util.h"
#include "util/coding.h"
#include "util/string_util.h"

namespace ROCKSDB_NAMESPACE {
BaseDeltaIterator::BaseDeltaIterator(Iterator* base_iterator,
                                     WBWIIterator* delta_iterator,
                                     const Comparator* comparator,
                                     const ReadOptions* read_options)
    : progress_(Progress::TO_BE_DETERMINED),
      current_at_base_(true),
      equal_keys_(false),
      status_(Status::OK()),
      base_iterator_(base_iterator),
      delta_iterator_(delta_iterator),
      comparator_(comparator),
      read_options_(read_options) {}

bool BaseDeltaIterator::Valid() const {
  return status_.ok() ? (current_at_base_ ? BaseValid() : DeltaValid()) : false;
}

void BaseDeltaIterator::SeekToFirst() {
  progress_ = Progress::SEEK_TO_FIRST;
  base_iterator_->SeekToFirst();
  delta_iterator_->SeekToFirst();
  UpdateCurrent();
}

void BaseDeltaIterator::SeekToLast() {
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
        // the base_upper_bound is beyond the base_iterator, so just SeekToLast()
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
  if (read_options_ != nullptr
      && read_options_->iterate_upper_bound != nullptr) {

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

void BaseDeltaIterator::Seek(const Slice& k) {
  progress_ = Progress::SEEK;
  base_iterator_->Seek(k);
  delta_iterator_->Seek(k);
  UpdateCurrent();
}

void BaseDeltaIterator::SeekForPrev(const Slice& k) {
  progress_ = Progress::SEEK_FOR_PREV;
  base_iterator_->SeekForPrev(k);
  delta_iterator_->SeekForPrev(k);
  UpdateCurrent();
}

void BaseDeltaIterator::Next() {
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

void BaseDeltaIterator::Prev() {
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

Slice BaseDeltaIterator::key() const {
  return current_at_base_ ? base_iterator_->key()
                          : delta_iterator_->Entry().key;
}

Slice BaseDeltaIterator::value() const {
  return current_at_base_ ? base_iterator_->value()
                          : delta_iterator_->Entry().value;
}

Status BaseDeltaIterator::status() const {
  if (!status_.ok()) {
    return status_;
  }
  if (!base_iterator_->status().ok()) {
    return base_iterator_->status();
  }
  return delta_iterator_->status();
}

bool BaseDeltaIterator::ChecksLowerBound() const { return false; }

const Slice* BaseDeltaIterator::lower_bound() const {
  return base_iterator_lower_bound();
}

bool BaseDeltaIterator::ChecksUpperBound() const { return true; }

const Slice* BaseDeltaIterator::upper_bound() const {
  return base_iterator_upper_bound();
}

void BaseDeltaIterator::Invalidate(Status s) { status_ = s; }

void BaseDeltaIterator::AssertInvariants() {
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
  int compare =
      comparator_->Compare(delta_iterator_->Entry().key, base_iterator_->key());
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

void BaseDeltaIterator::Advance() {
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

void BaseDeltaIterator::AdvanceDelta() {
  if (IsMovingForward()) {
    delta_iterator_->Next();
  } else {
    delta_iterator_->Prev();
  }
}

void BaseDeltaIterator::AdvanceBase() {
  if (IsMovingForward()) {
    base_iterator_->Next();
  } else {
    base_iterator_->Prev();
  }
}

bool BaseDeltaIterator::BaseValid() const {
  // NOTE: we don't need the bounds check on
  // base_iterator if the base iterator has an
  // upper_bounds_check already
  return base_iterator_->Valid() &&
      (base_iterator_->ChecksUpperBound() ? true : BaseIsWithinBounds());
}

bool BaseDeltaIterator::DeltaValid() const {
  return delta_iterator_->Valid() &&
      DeltaIsWithinBounds();
}

void BaseDeltaIterator::UpdateCurrent() {
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

      if (read_options_ != nullptr && read_options_->iterate_upper_bound != nullptr) {
        if (comparator_->Compare(delta_entry.key, *(read_options_->iterate_upper_bound)) >= 0) {
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

      // Base and Delta are both unfinished

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

inline const Slice* BaseDeltaIterator::base_iterator_upper_bound() const {
  const Slice* upper_bound = base_iterator_->upper_bound();
  if (upper_bound == nullptr && read_options_ != nullptr) {
    return read_options_->iterate_upper_bound;
  }
  return upper_bound;
}

inline const Slice* BaseDeltaIterator::base_iterator_lower_bound() const {
  const Slice* lower_bound = base_iterator_->lower_bound();
  if (lower_bound == nullptr && read_options_ != nullptr) {
    return read_options_->iterate_lower_bound;
  }
  return lower_bound;
}

bool BaseDeltaIterator::BaseIsWithinBounds() const {
  if (IsMovingBackward()) {
    const Slice* lower_bound = base_iterator_lower_bound();
    if (lower_bound != nullptr) {
        return comparator_->Compare(base_iterator_->key(), *lower_bound) >= 0;
    }
  }

  if (IsMovingForward()) {
    const Slice* upper_bound = base_iterator_upper_bound();
    if (upper_bound != nullptr) {
      return comparator_->Compare(base_iterator_->key(), *upper_bound) < 0;
    }
  }

  return true;
}

bool BaseDeltaIterator::DeltaIsWithinBounds() const {
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

class Env;
class Logger;
class Statistics;

Status ReadableWriteBatch::GetEntryFromDataOffset(size_t data_offset,
                                                  WriteType* type, Slice* Key,
                                                  Slice* value, Slice* blob,
                                                  Slice* xid) const {
  if (type == nullptr || Key == nullptr || value == nullptr ||
      blob == nullptr || xid == nullptr) {
    return Status::InvalidArgument("Output parameters cannot be null");
  }

  if (data_offset == GetDataSize()) {
    // reached end of batch.
    return Status::NotFound();
  }

  if (data_offset > GetDataSize()) {
    return Status::InvalidArgument("data offset exceed write batch size");
  }
  Slice input = Slice(rep_.data() + data_offset, rep_.size() - data_offset);
  char tag;
  uint32_t column_family;
  Status s = ReadRecordFromWriteBatch(&input, &tag, &column_family, Key, value,
                                      blob, xid);
  if (!s.ok()) {
    return s;
  }

  switch (tag) {
    case kTypeColumnFamilyValue:
    case kTypeValue:
      *type = kPutRecord;
      break;
    case kTypeColumnFamilyDeletion:
    case kTypeDeletion:
      *type = kDeleteRecord;
      break;
    case kTypeColumnFamilySingleDeletion:
    case kTypeSingleDeletion:
      *type = kSingleDeleteRecord;
      break;
    case kTypeColumnFamilyRangeDeletion:
    case kTypeRangeDeletion:
      *type = kDeleteRangeRecord;
      break;
    case kTypeColumnFamilyMerge:
    case kTypeMerge:
      *type = kMergeRecord;
      break;
    case kTypeLogData:
      *type = kLogDataRecord;
      break;
    case kTypeNoop:
    case kTypeBeginPrepareXID:
    case kTypeBeginPersistedPrepareXID:
    case kTypeBeginUnprepareXID:
    case kTypeEndPrepareXID:
    case kTypeCommitXID:
    case kTypeRollbackXID:
      *type = kXIDRecord;
      break;
    default:
      return Status::Corruption("unknown WriteBatch tag ",
                                ToString(static_cast<unsigned int>(tag)));
  }
  return Status::OK();
}

// If both of `entry1` and `entry2` point to real entry in write batch, we
// compare the entries as following:
// 1. first compare the column family, the one with larger CF will be larger;
// 2. Inside the same CF, we first decode the entry to find the key of the entry
//    and the entry with larger key will be larger;
// 3. If two entries are of the same CF and offset, the one with larger offset
//    will be larger.
// Some times either `entry1` or `entry2` is dummy entry, which is actually
// a search key. In this case, in step 2, we don't go ahead and decode the
// entry but use the value in WriteBatchIndexEntry::search_key.
// One special case is WriteBatchIndexEntry::key_size is kFlagMinInCf.
// This indicate that we are going to seek to the first of the column family.
// Once we see this, this entry will be smaller than all the real entries of
// the column family.
int WriteBatchEntryComparator::operator()(
    const WriteBatchIndexEntry* entry1,
    const WriteBatchIndexEntry* entry2) const {
  if (entry1->column_family > entry2->column_family) {
    return 1;
  } else if (entry1->column_family < entry2->column_family) {
    return -1;
  }

  // Deal with special case of seeking to the beginning of a column family
  if (entry1->is_min_in_cf()) {
    return -1;
  } else if (entry2->is_min_in_cf()) {
    return 1;
  }

  Slice key1, key2;
  if (entry1->search_key == nullptr) {
    key1 = Slice(write_batch_->Data().data() + entry1->key_offset,
                 entry1->key_size);
  } else {
    key1 = *(entry1->search_key);
  }
  if (entry2->search_key == nullptr) {
    key2 = Slice(write_batch_->Data().data() + entry2->key_offset,
                 entry2->key_size);
  } else {
    key2 = *(entry2->search_key);
  }

  int cmp = CompareKey(entry1->column_family, key1, key2);
  if (cmp != 0) {
    return cmp;
  } else if (entry1->offset > entry2->offset) {
    return 1;
  } else if (entry1->offset < entry2->offset) {
    return -1;
  }
  return 0;
}

int WriteBatchEntryComparator::CompareKey(uint32_t column_family,
                                          const Slice& key1,
                                          const Slice& key2) const {
  if (column_family < cf_comparators_.size() &&
      cf_comparators_[column_family] != nullptr) {
    return cf_comparators_[column_family]->Compare(key1, key2);
  } else {
    return default_comparator_->Compare(key1, key2);
  }
}

WriteEntry WBWIIteratorImpl::Entry() const {
  WriteEntry ret;
  Slice blob, xid;
  const WriteBatchIndexEntry* iter_entry = skip_list_iter_.key();
  // this is guaranteed with Valid()
  assert(iter_entry != nullptr &&
         iter_entry->column_family == column_family_id_);
  auto s = write_batch_->GetEntryFromDataOffset(
      iter_entry->offset, &ret.type, &ret.key, &ret.value, &blob, &xid);
  assert(s.ok());
  assert(ret.type == kPutRecord || ret.type == kDeleteRecord ||
         ret.type == kSingleDeleteRecord || ret.type == kDeleteRangeRecord ||
         ret.type == kMergeRecord);
  return ret;
}

bool WBWIIteratorImpl::MatchesKey(uint32_t cf_id, const Slice& key) {
  if (Valid()) {
    return comparator_->CompareKey(cf_id, key, Entry().key) == 0;
  } else {
    return false;
  }
}

WriteBatchWithIndexInternal::WriteBatchWithIndexInternal(
    DB* db, ColumnFamilyHandle* column_family)
    : db_(db), db_options_(nullptr), column_family_(column_family) {
  if (db_ != nullptr && column_family_ == nullptr) {
    column_family_ = db_->DefaultColumnFamily();
  }
}

WriteBatchWithIndexInternal::WriteBatchWithIndexInternal(
    const DBOptions* db_options, ColumnFamilyHandle* column_family)
    : db_(nullptr), db_options_(db_options), column_family_(column_family) {}

Status WriteBatchWithIndexInternal::MergeKey(const Slice& key,
                                             const Slice* value,
                                             MergeContext& merge_context,
                                             std::string* result,
                                             Slice* result_operand) {
  if (column_family_ != nullptr) {
    auto cfh = static_cast_with_check<ColumnFamilyHandleImpl>(column_family_);
    const auto merge_operator = cfh->cfd()->ioptions()->merge_operator.get();
    if (merge_operator == nullptr) {
      return Status::InvalidArgument(
          "Merge_operator must be set for column_family");
    } else if (db_ != nullptr) {
      const ImmutableDBOptions& immutable_db_options =
          static_cast_with_check<DBImpl>(db_->GetRootDB())
              ->immutable_db_options();
      Statistics* statistics = immutable_db_options.statistics.get();
      Logger* logger = immutable_db_options.info_log.get();
      SystemClock* clock = immutable_db_options.clock;
      return MergeHelper::TimedFullMerge(
          merge_operator, key, value, merge_context.GetOperands(), result,
          logger, statistics, clock, result_operand);
    } else if (db_options_ != nullptr) {
      Statistics* statistics = db_options_->statistics.get();
      Env* env = db_options_->env;
      Logger* logger = db_options_->info_log.get();
      SystemClock* clock = env->GetSystemClock().get();
      return MergeHelper::TimedFullMerge(
          merge_operator, key, value, merge_context.GetOperands(), result,
          logger, statistics, clock, result_operand);
    } else {
      return MergeHelper::TimedFullMerge(
          merge_operator, key, value, merge_context.GetOperands(), result,
          nullptr, nullptr, SystemClock::Default().get(), result_operand);
    }
  } else {
    return Status::InvalidArgument("Must provide a column_family");
  }
}

WriteBatchWithIndexInternal::Result WriteBatchWithIndexInternal::GetFromBatch(
    WriteBatchWithIndex* batch, const Slice& key, MergeContext* merge_context,
    std::string* value, bool overwrite_key, Status* s) {
  uint32_t cf_id = GetColumnFamilyID(column_family_);
  *s = Status::OK();
  Result result = kNotFound;

  std::unique_ptr<WBWIIteratorImpl> iter(
      static_cast_with_check<WBWIIteratorImpl>(
          batch->NewIterator(column_family_)));

  // We want to iterate in the reverse order that the writes were added to the
  // batch.  Since we don't have a reverse iterator, we must seek past the end.
  // TODO(agiardullo): consider adding support for reverse iteration
  iter->Seek(key);
  while (iter->Valid() && iter->MatchesKey(cf_id, key)) {
    iter->Next();
  }

  if (!(*s).ok()) {
    return WriteBatchWithIndexInternal::kError;
  }

  if (!iter->Valid()) {
    // Read past end of results.  Reposition on last result.
    iter->SeekToLast();
  } else {
    iter->Prev();
  }

  Slice entry_value;
  while (iter->Valid()) {
    if (!iter->MatchesKey(cf_id, key)) {
      // Unexpected error or we've reached a different next key
      break;
    }

    const WriteEntry entry = iter->Entry();
    switch (entry.type) {
      case kPutRecord: {
        result = WriteBatchWithIndexInternal::Result::kFound;
        entry_value = entry.value;
        break;
      }
      case kMergeRecord: {
        result = WriteBatchWithIndexInternal::Result::kMergeInProgress;
        merge_context->PushOperand(entry.value);
        break;
      }
      case kDeleteRecord:
      case kSingleDeleteRecord: {
        result = WriteBatchWithIndexInternal::Result::kDeleted;
        break;
      }
      case kLogDataRecord:
      case kXIDRecord: {
        // ignore
        break;
      }
      default: {
        result = WriteBatchWithIndexInternal::Result::kError;
        (*s) = Status::Corruption("Unexpected entry in WriteBatchWithIndex:",
                                  ToString(entry.type));
        break;
      }
    }
    if (result == WriteBatchWithIndexInternal::Result::kFound ||
        result == WriteBatchWithIndexInternal::Result::kDeleted ||
        result == WriteBatchWithIndexInternal::Result::kError) {
      // We can stop iterating once we find a PUT or DELETE
      break;
    }
    if (result == WriteBatchWithIndexInternal::Result::kMergeInProgress &&
        overwrite_key == true) {
      // Since we've overwritten keys, we do not know what other operations are
      // in this batch for this key, so we cannot do a Merge to compute the
      // result.  Instead, we will simply return MergeInProgress.
      break;
    }

    iter->Prev();
  }

  if ((*s).ok()) {
    if (result == WriteBatchWithIndexInternal::Result::kFound ||
        result == WriteBatchWithIndexInternal::Result::kDeleted) {
      // Found a Put or Delete.  Merge if necessary.
      if (merge_context->GetNumOperands() > 0) {
        if (result == WriteBatchWithIndexInternal::Result::kFound) {
          *s = MergeKey(key, &entry_value, *merge_context, value);
        } else {
          *s = MergeKey(key, nullptr, *merge_context, value);
        }
        if ((*s).ok()) {
          result = WriteBatchWithIndexInternal::Result::kFound;
        } else {
          result = WriteBatchWithIndexInternal::Result::kError;
        }
      } else {  // nothing to merge
        if (result == WriteBatchWithIndexInternal::Result::kFound) {  // PUT
          value->assign(entry_value.data(), entry_value.size());
        }
      }
    }
  }

  return result;
}

}  // namespace ROCKSDB_NAMESPACE

#endif  // !ROCKSDB_LITE
