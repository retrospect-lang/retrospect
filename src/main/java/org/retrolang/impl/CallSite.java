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

package org.retrolang.impl;

import java.util.function.IntPredicate;

/** Represents a call site within a method. */
class CallSite {
  /** Each CallSite in a method must have a distinct index. */
  final int cIndex;

  /**
   * The index of the ValueMemo in {@link MethodMemo#valueMemos} that will harmonize the non-ignored
   * results from this call. CallSites within a built-in method that share a Continuation should
   * have the same vIndex.
   */
  final int vIndex;

  /** The number of results from the called function that will be kept. */
  private int numResultsKept;

  /** The size of the ValueMemo identified by {@link #vIndex}. */
  private int vSize;

  /**
   * If one or more results are ignored at this CallSite, an array with one entry for each of the
   * called function's results: -1 if that result is being ignored, and otherwise the index of the
   * corresponding entry in the ValueMemo. Null if all results are kept.
   */
  private int[] resultsMapping;

  /**
   * If non-null, this is the stack entry type the caller will push for an unwind during the call.
   * Currently only used to improve debuggability.
   */
  BaseType.StackEntryType duringCallEntryType;

  /**
   * Creates a new CallSite that keeps all the returned results and stores them in a ValueMemo of
   * size {@code numResults}.
   */
  CallSite(int cIndex, int vIndex, int numResults) {
    this.cIndex = cIndex;
    this.vIndex = vIndex;
    this.numResultsKept = numResults;
    this.vSize = numResults;
  }

  /**
   * Increases the size of the ValueMemo used to store this CallSite's results (used by built-in
   * methods to reserve space for a Continuation's saved arguments). Must be called before the
   * CallSite is used, and {@code vSize} must be greater than or equal to the number of results that
   * will be kept. If {@link #setIgnoredResults} is also used on this CallSite, {@link
   * #setIgnoredResults} must be called first.
   */
  void setValueMemoSize(int vSize) {
    // if vIndex < 0, there is no corresponding vMemo (i.e. this is a tail call)
    assert vSize >= this.numResultsKept || vIndex < 0;
    this.vSize = vSize;
  }

  /** Marks one or more results as ignored; must be called before this CallSite is used. */
  void setIgnoredResults(IntPredicate ignored) {
    assert this.resultsMapping == null && vSize == numResultsKept;
    int[] resultsMapping = new int[numResultsKept];
    int kept = 0;
    for (int i = 0; i < resultsMapping.length; i++) {
      resultsMapping[i] = ignored.test(i) ? -1 : kept++;
    }
    // If kept == numResultsKept, ignored was false everywhere.  We handle that as a no-op (by
    // leaving the CallSite unchanged), but since we don't currently expect to ever make such a call
    // we're also going to assert that it shouldn't happen.
    assert kept < numResultsKept;
    if (kept < numResultsKept) {
      this.resultsMapping = resultsMapping;
      vSize -= numResultsKept - kept;
      numResultsKept = kept;
    }
  }

  /** Returns the number of function results that will be kept. */
  int numResultsKept() {
    return numResultsKept;
  }

  /** Returns the size of the ValueMemo for storing the function call's results. */
  int vSize() {
    return vSize;
  }

  /** Returns the ValueMemo that harmonizes the results of calls from this CallSite. */
  ValueMemo valueMemo(TState tstate, MethodMemo mMemo) {
    return mMemo.valueMemo(tstate, vIndex, vSize);
  }

  /**
   * Given the full set of function results, returns an array containing only those results that are
   * kept at this CallSite.
   */
  Value[] kept(Value[] returned) {
    if (resultsMapping == null) {
      assert returned.length == numResultsKept;
      return returned;
    }
    assert returned.length == resultsMapping.length;
    Value[] kept = new Value[numResultsKept];
    for (int i = 0; i < resultsMapping.length; i++) {
      int pos = resultsMapping[i];
      if (pos >= 0) {
        kept[pos] = returned[i];
      }
    }
    return kept;
  }

  /**
   * Given a ResultsInfo for the results that will be kept at this CallSite, returns a ResultsInfo
   * suitable for the called function. For results that are kept it returns the corresponding info
   * from {@code forKeptResults}; for results that are discarded it returns
   *
   * <ul>
   *   <li>True if the TProperty is {@link TProperty#IS_DISCARDED}; or
   *   <li>the TProperty's result when applied to {@link Template#EMPTY} otherwise.
   * </ul>
   */
  ResultsInfo adjustResultsInfo(ResultsInfo forKeptResults) {
    if (resultsMapping == null) {
      // The usual case; no translation needed.
      return forKeptResults;
    } else {
      return new ResultsInfo() {
        @Override
        public <T> T result(int resultNum, TProperty<T> property) {
          int mapped = resultsMapping[resultNum];
          if (mapped >= 0) {
            return forKeptResults.result(mapped, property);
          } else if (property == TProperty.IS_DISCARDED) {
            @SuppressWarnings("unchecked")
            T result = (T) Boolean.TRUE;
            return result;
          } else {
            return property.fn.apply(Template.EMPTY);
          }
        }
      };
    }
  }
}
