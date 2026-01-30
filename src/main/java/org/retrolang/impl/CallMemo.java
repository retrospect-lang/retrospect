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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.ObjIntConsumer;

/**
 * A CallMemo is a linked list of the MethodMemos that have been used at a single call site.
 *
 * <p>Having to search a sequential list in {@link #memoForMethod} might sound problematic, but in
 * practice
 *
 * <ul>
 *   <li>most call sites are expected to have a small number of methods that are actually invoked
 *       (usually one or two);
 *   <li>this structure is only traversed when interpreting instructions or when generating code,
 *       not when executing generated code; and
 *   <li>the generated method dispatch code will still need to do a type check for each method, i.e.
 *       it will be O(n) even if we were to do something more complex here.
 * </ul>
 */
class CallMemo {
  private static final VarHandle COUNT_VAR =
      Handle.forVar(MethodHandles.lookup(), CallMemo.class, "count", int.class);

  private MethodMemo memo;

  /**
   * A counter for the number of times we've chosen this method; will be used to order type checks
   * in generated code.
   */
  private volatile int count;

  private CallMemo next;

  CallMemo(MethodMemo memo, CallMemo next) {
    this.memo = memo;
    this.next = next;
  }

  /**
   * Stop incrementing count when it gets this big, to ensure that it doesn't overflow.
   *
   * <p>Due to races we may end up going over this value, but it's intentionally chosen to be low
   * enough that we still won't overflow.
   */
  private static final int MAX_COUNT = Integer.MAX_VALUE / 8;

  /**
   * Increments this CallMemo's {@link #count} field by {@code delta}, capping at {@link
   * #MAX_COUNT}.
   */
  private void incrementCount(int delta) {
    assert delta >= 0;
    delta = Math.min(delta, MAX_COUNT - count);
    if (delta > 0) {
      int c = (int) COUNT_VAR.getAndAdd(this, delta);
      c += delta;
      if (c > MAX_COUNT || c < 0) {
        // We must have raced against another update and overshot; clean up
        count = MAX_COUNT;
      }
    }
  }

  /** Returns the total weight of all MethodMemos at this call site. */
  int totalWeight() {
    assert MemoMerger.isLocked();
    int sum = 0;
    for (CallMemo cm = this; cm != null; cm = cm.next) {
      sum += cm.memo.weight();
    }
    return sum;
  }

  /** Returns the maximum weight of all MethodMemos at this call site. */
  int maxWeight() {
    assert MemoMerger.isLocked();
    int max = 0;
    for (CallMemo cm = this; cm != null; cm = cm.next) {
      max = Math.max(max, cm.memo.weight());
    }
    return max;
  }

  /**
   * Returns this CallMemo's MethodMemo for the given method, or null if there is none.
   *
   * <p>If {@code isLocked} is true and the MethodMemo we find has been forwarded (happens when
   * exlined methods are merged), we will fix the CallMemo to point to the replacement. If {@code
   * isLocked} is false we will leave any forwarded MethodMemo unchanged and just return null (which
   * will cause the caller to acquire the appropriate lock and try again).
   */
  MethodMemo memoForMethod(VmMethod method, boolean isLocked) {
    assert !isLocked || MemoMerger.isLocked();
    for (CallMemo cm = this; cm != null; cm = cm.next) {
      // We're paranoid about stepping on a null here because we might be reading objects created by
      // another thread without intervening synchronization.  We're OK with just skipping objects
      // that have nulls because a miss will cause us to synchronize & check again.
      MethodMemo mm = cm.memo;
      MemoMerger.PerMethod perMethod = (mm != null) ? mm.perMethod : null;
      if (perMethod != null && perMethod.method == method) {
        MethodMemo result = cm.memo;
        if (result.isForwarded()) {
          if (!isLocked) {
            return null;
          }
          result = result.resolve();
          assert result.isExlined() && result.weight() == cm.memo.weight();
          cm.memo = result;
        }
        cm.incrementCount(1);
        return result;
      }
    }
    return null;
  }

  /**
   * Replaces this CallMemo's link to {@code prev} with the given replacement.
   *
   * <p>Will error (with a NullPointerException) unless this CallMemo or one linked from it links to
   * {@code prev}.
   */
  void replaceMemo(MethodMemo prev, MethodMemo replacement) {
    assert MemoMerger.isLocked();
    for (CallMemo cm = this; ; cm = cm.next) {
      if (cm.memo == prev) {
        cm.memo = replacement;
        return;
      }
    }
  }

  /**
   * Called when a CallMemo is being moved from one MethodMemo to another (as part of a merge);
   * calls {@link MethodMemo#updateParent} on each MethodMemo.
   */
  void updateParents(MethodMemo newParent) {
    assert MemoMerger.isLocked();
    for (CallMemo cm = this; cm != null; cm = cm.next) {
      cm.memo.updateParent(newParent);
    }
  }

  /** Returns one of the MethodMemos. */
  MethodMemo firstMethodMemo() {
    return memo;
  }

  /**
   * Calls {@code action} with each of this CallMemo's MethodMemos, along with their corresponding
   * counts. Should only be called while holding the MemoMerger lock; {@code action} should not
   * modify this CallMemo.
   */
  void forEachChild(ObjIntConsumer<MethodMemo> action) {
    assert MemoMerger.isLocked();
    for (CallMemo cm = this; cm != null; cm = cm.next) {
      action.accept(cm.memo, cm.count);
    }
  }

  /**
   * {@link VmMethod#newMemo} checks for a matching exlined memo before allocating a new MethodMemo,
   * but after a memo is exlined there may still be existing lightweight memos that would now match
   * it (heavyweight ones can be checked for by the MemoMerger directly). This method checks each of
   * the CallMemo's lightweight memos for such a match, and if found does the merge.
   */
  void checkForExlinedLightWeights(MemoMerger merger) {
    for (CallMemo cm = this; cm != null; cm = cm.next) {
      MethodMemo mm = cm.memo;
      if (mm.isLight()) {
        mm.perMethod.checkForSimilarExlined(mm, merger);
      }
    }
  }

  /**
   * Moves or merges this CallMemo's MethodMemos into {@code other}. After calling {@code
   * mergeInto()} this CallMemo may still be read (and may return MethodMemos that have been merged,
   * or that are in {@code other}) but should not be modified.
   */
  void mergeInto(CallMemo other, MethodMemo otherParent, MemoMerger merger) {
    // Note that since we linear search other's MethodMemos for each of our MethodMemos this has
    // is O(product of the sizes).  I do not expect this to be a performance issue since almost
    // all CallMemos have a small number of MethodMemos, but if it does become a hotspot we could
    // replace this with something smarter.
    CallMemo cm = this;
    while (cm != null) {
      // addOrMerge may change next, so read it first!
      CallMemo next = cm.next;
      other.addOrMerge(cm, otherParent, merger);
      cm = next;
    }
    ;
  }

  /**
   * Moves or merges {@code cm2}'s first MethodMemo into this CallMemo. May reuse {@code cm2} by
   * linking it into {@code this}.
   */
  private void addOrMerge(CallMemo cm2, MethodMemo thisParent, MemoMerger merger) {
    MethodMemo called2 = cm2.memo.resolve();
    MemoMerger.PerMethod perMethod = called2.perMethod;
    // Search our MethodMemos to see if we've got a matching one
    CallMemo cm1 = this;
    for (; ; ) {
      MethodMemo called = cm1.memo.resolve();
      if (called == called2) {
        // both call the same exlined method
        return;
      } else if (called.perMethod == perMethod) {
        // We'll lose any increments to cm2.count after this point, but we can live with that
        cm1.incrementCount(cm2.count);
        if (called.isExlined()) {
          called2.mergeInto(called, merger);
        } else if (called2.isExlined()) {
          // cm2's MethodMemo is exlined and our MethodMemo isn't, so replace ours with cm2's and
          // then merge our previous MethodMemo into it.
          // Note that weight() on an exlined memo is constant, so won't be affected by a subsequent
          // merge.
          thisParent.updateChildWeight(called.weight(), called2.weight());
          cm1.memo = called2;
          called.mergeInto(called2, merger);
        } else {
          // Neither is exlined, so merge and then propagate any weight change back to our parent
          assert called.parent() == thisParent;
          called2.mergeInto(called, merger);
          var unused = called.checkWeight(merger);
        }
        return;
      } else if (cm1.next != null) {
        cm1 = cm1.next;
      } else {
        // We got to the end and found no match, so just splice in the whole CallMemo
        cm2.memo = called2;
        cm2.next = null;
        cm1.next = cm2;
        called2.updateParent(thisParent);
        return;
      }
    }
  }

  /** Calls {@link MethodMemo#checkConsistency} on each of our MethodMemos. */
  static void checkConsistency(CallMemo cm) {
    for (; cm != null; cm = cm.next) {
      cm.memo.checkConsistency(false);
    }
  }
}
