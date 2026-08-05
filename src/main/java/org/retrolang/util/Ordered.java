/*
 * Copyright 2025 The Retrospect Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.retrolang.util;

import java.util.List;
import java.util.function.IntToLongFunction;

/// A static-only class with methods for working with arrays and lists that have elements sorted in
/// ascending sortOrder.
public class Ordered {
  /// Searches the results of `keyFn` from 0 to `limit-1` for `key`, assuming that it returns values
  /// in strictly increasing order. If a match is found, returns its index; otherwise returns
  /// `-(i+1)` where `i` is the index at which `key` should be inserted to preserve ordering.
  public static int search(long key, IntToLongFunction keyFn, int limit) {
    // It's 2022 and I'm still writing binary search.
    int start = 0;
    // keys are distinct and non-negative, so if we're searching for a very small key we know it has
    // to be near the beginning of the list (1 and 2 are common values to search for).
    if (limit > key + 1) {
      limit = (int) (key + 1);
    }

    while (start < limit) {
      int mid = (start + limit) >>> 1;
      int cmp = Long.compare(keyFn.applyAsLong(mid), key);
      if (cmp < 0) {
        start = mid + 1;
      } else if (cmp > 0) {
        limit = mid;
      } else {
        return mid;
      }
    }
    return -(start + 1);
  }

  /// If `index` is non-negative, replaces the specified element of `list`; otherwise inserts a new
  /// element. The interpretation of `index` matches the result of [#search].
  public static <T> void setOrAdd(List<T> list, int index, T newElement) {
    if (index >= 0) {
      list.set(index, newElement);
    } else {
      list.add(-(index + 1), newElement);
    }
  }

  private Ordered() {}
}
