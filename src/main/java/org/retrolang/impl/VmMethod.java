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

/** A VmMethod represents a method, together with its predicate and "default" flag. */
public final class VmMethod {

  /** The function this method implements. */
  final VmFunction function;

  /** The MethodPredicate associated with this method. Will not be null. */
  final MethodPredicate predicate;

  /**
   * If true, this method will only be considered when there are no matching preferred methods (i.e.
   * methods with isDefault=false).
   */
  final boolean isDefault;

  /** The method's implementation. */
  final MethodImpl impl;

  /**
   * A measure of the complexity of this method, not counting the weight of any functions it calls.
   */
  final int baseWeight;

  /** A factory for creating new MethodMemos, or null if all call sites can share a fixed memo. */
  final MethodMemo.Factory memoFactory;

  /**
   * Simple builtins with no nested calls may not need any information to be saved in their
   * MethodMemos; for such methods we create a single, immutable MethodMemo that can be shared among
   * all uses (even between Scopes).
   */
  final MethodMemo fixedMemo;

  VmMethod(
      VmFunction function,
      MethodPredicate predicate,
      boolean isDefault,
      MethodImpl impl,
      int baseWeight,
      MethodMemo.Factory memoFactory) {
    this.function = function;
    this.predicate = (predicate == null) ? MethodPredicate.TRUE : predicate;
    this.isDefault = isDefault;
    this.impl = impl;
    this.baseWeight = baseWeight;
    if (memoFactory.createsFixedMemos()) {
      // The fixedMemo needs a PerMemo since that's how its VmMethod can be found, but it won't
      // be associated with any Scope.
      this.fixedMemo = memoFactory.newMemo(new MemoMerger.PerMethod(this, false));
      this.memoFactory = null;
    } else {
      this.fixedMemo = null;
      this.memoFactory = memoFactory;
    }
  }

  /**
   * Returns a MethodMemo for this method; called the first time the method is invoked from a
   * particular MethodMemo / CallSite combination (the MethodMemo returned will be saved and reused
   * for subsequent calls of the same method from the same site).
   */
  MethodMemo newMemo(MemoMerger merger, Object[] args) {
    // Find the scope's PerMethod entry, and see if there are already exlined MethodMemos that match
    // these arguments.
    MemoMerger.PerMethod perMethod = merger.perMethod(this);
    MethodMemo exlined = perMethod.findExlined(args);
    return (exlined != null) ? exlined : memoFactory.newMemo(perMethod);
  }

  @Override
  public String toString() {
    return impl.toString();
  }
}
