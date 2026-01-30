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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Each Scope has a MemoMerger which serves as the synchronization point for updates to MethodMemos
 * and CallMemos (ValueMemos are mostly self-synchronized, with one exception: the argsMemo of a
 * heavy or exlined MethodMemo).
 */
class MemoMerger {

  /** True if the current thread has locked the MemoMerger; only intended for assertions. */
  static boolean isLocked() {
    return Thread.holdsLock(TState.get().scope().memoMerger);
  }

  /**
   * Each MemoMerger has a PerMethod instance for each distinct VmMethod that it has created
   * MethodMemos for. The MethodMemos all link to the PerMethod instances, which keeps global
   * information about that method in this scope.
   *
   * <p>Methods with fixed (trivial) MethodMemos have a single associated PerMethod instance that is
   * not associated with any scope -- it has no useful information, but avoids needing special-case
   * handling for those methods.
   */
  static class PerMethod {
    /** The method that this PerMethod stores information for. */
    final VmMethod method;

    /**
     * A list of all heavy MethodMemos for this method in this scope.
     *
     * <p>Since this is usually empty or has a single element, we minimize memory use in those cases
     * by storing an ImmutableList; if a longer list is needed we switch to an ArrayList.
     */
    @VisibleForTesting List<MethodMemo> heavy = ImmutableList.of();

    /**
     * A list of all exlined MethodMemos for this method in this scope.
     *
     * <p>Uses the same ImmutableList/ArrayList strategy as {@link #heavy} in order to minimize
     * memory use.
     */
    @VisibleForTesting List<MethodMemo> exlined = ImmutableList.of();

    /**
     * If true, all calls to this method should use a single exlined MethodMemo. Used only for
     * testing.
     */
    private final boolean forceExlined;

    PerMethod(VmMethod method, boolean forceExlined) {
      this.method = method;
      this.forceExlined = forceExlined;
      if (forceExlined) {
        MethodMemo mm = method.memoFactory.newMemo(this);
        mm.setExlined(null);
        addExlined(mm);
      }
    }

    /**
     * Returns a list containing {@code item} and the elements of {@code list}. If {@code list} is
     * an ArrayList just adds {@code item} to it; otherwise returns a new ImmutableList (if {@code
     * list} was empty) or a new ArrayList (if it wasn't).
     *
     * <p>Used to update {@link #heavy} and {@link #exlined} in a way that minimizes memory use.
     */
    private static List<MethodMemo> addToList(MethodMemo item, List<MethodMemo> list) {
      assert !list.contains(item);
      if (!(list instanceof ArrayList)) {
        if (list.isEmpty()) {
          return ImmutableList.of(item);
        }
        List<MethodMemo> newList = new ArrayList<>();
        newList.addAll(list);
        list = newList;
      }
      list.add(item);
      return list;
    }

    /**
     * Returns a list containing the elements of {@code list} except for {@code item}. If {@code
     * list} is an ArrayList just calls {@link List#remove}; otherwise {@code list} should have
     * length 0 or 1, and this method returns either {@code list} (if it does not contain {@code
     * item}) or an empty ImmutableList (if it does).
     *
     * <p>Used to update {@link #heavy} and {@link #exlined} in a way that minimizes memory use.
     */
    private static List<MethodMemo> removeFromList(MethodMemo item, List<MethodMemo> list) {
      if (list instanceof ArrayList) {
        list.remove(item);
      } else {
        assert list.size() <= 1;
        if (!list.isEmpty() && list.get(0) == item) {
          return ImmutableList.of();
        }
      }
      return list;
    }

    /** Adds the given MethodMemo to {@link #heavy}. */
    void addHeavy(MethodMemo mm) {
      heavy = addToList(mm, heavy);
    }

    /** Removes the given MethodMemo from {@link #heavy}. */
    void removeHeavy(MethodMemo mm) {
      heavy = removeFromList(mm, heavy);
    }

    /** Adds the given MethodMemo to {@link #exlined}. */
    void addExlined(MethodMemo mm) {
      exlined = addToList(mm, exlined);
    }

    /** Removes the given MethodMemo from {@link #exlined}. */
    void removeExlined(MethodMemo mm) {
      exlined = removeFromList(mm, exlined);
    }

    /**
     * If a MethodMemo in {@link #exlined} is suitable for the given arguments, return it; otherwise
     * return null.
     */
    MethodMemo findExlined(Object[] args) {
      if (forceExlined) {
        assert exlined.size() == 1;
        return exlined.get(0);
      }
      for (MethodMemo mm : exlined) {
        if (mm.couldCastArgs(args)) {
          return mm;
        }
      }
      return null;
    }

    /** If a MethodMemo in {@link #exlined} is similar enough to {@code light}, merge them. */
    void checkForSimilarExlined(MethodMemo light, MemoMerger merger) {
      for (MethodMemo mm : exlined) {
        if (mm.isSimilarEnough(light)) {
          // The mergeInto() call below will leave a forwarding pointer, but if mm has a
          // parent we might as well go ahead and replace it now.
          merger.replaceInParent(light, mm);
          light.mergeInto(mm, merger);
          break;
        }
      }
    }

    /**
     * Returns true if {@link #exlined} contains the given MethodMemo. Intended only for assertions
     * (should be redundant with {@link MethodMemo#state} == EXLINED).
     */
    boolean exlinedContains(MethodMemo mm) {
      return exlined.contains(mm);
    }

    /**
     * Returns true if {@link #heavy} contains the given MethodMemo. Intended only for assertions
     * (should be redundant with {@link MethodMemo#state} == HEAVY).
     */
    boolean heavyContains(MethodMemo mm) {
      return heavy.contains(mm);
    }
  }

  final Scope scope;

  /** All PerMethod objects that have been allocated for this Scope. */
  private final Map<VmMethod, PerMethod> perMethods = new HashMap<>();

  /**
   * All exlined or heavy MethodMemos whose argTypes have changed and need to be re-compared against
   * other exlined/heavy MethodMemos to see if their argTypes now overlap, and all light MethodMemos
   * whose weight has increased enough that they should be made heavy.
   *
   * <p>In general I think that it would be better to check children before parents (since merging
   * children, and thereby making them exlined, may reduce the weight of their parents so that they
   * are no longer considered worth merging). Having this be a queue (rather than a stack) was
   * intended to make that more likely, but I'm not convinced enough that it matters to make this a
   * priority queue.
   */
  private final Queue<MethodMemo> needsCheck = new ArrayDeque<>();

  /**
   * All MethodMemos that have transitioned from LIGHT to HEAVY and had overlapping HEAVY
   * MethodMemos with the same callSite. We delay merging these until the end because there's a good
   * chance that their parents are making the same transition, and if so we would prefer to merge
   * them.
   *
   * <p>I think there's a clearer benefit here to merging parents before children, since merging
   * parents may make exlining the children unnecessary. Making this a stack (populated from
   * needsCheck(), which we try to run on children first) is intended to make that more likely, but
   * we also make an explicit check when pulling entries from it (see code in {@link
   * #finishChecks}).
   */
  private final Deque<MethodMemo> needsSelfCheck = new ArrayDeque<>();

  /**
   * If non-null, any method for which this predicate returns true will use a single exlined for all
   * calls. For testing only.
   */
  private Predicate<VmMethod> forceExlined;

  MemoMerger(Scope scope) {
    this.scope = scope;
  }

  synchronized void setForceExlined(Predicate<VmMethod> forceExlined) {
    this.forceExlined = forceExlined;
  }

  /**
   * Calls the given consumer with each method that was identified by a previous call to {@link
   * #setForceExlined}. (The MemoMerger is locked throughout these calls, so they should do a
   * minimal amount of work.)
   */
  void forEachForcedMethod(Consumer<MethodMemo> consumer) {
    synchronized (this) {
      assert forceExlined != null;
      for (PerMethod perMethod : perMethods.values()) {
        if (perMethod.forceExlined) {
          assert perMethod.exlined.size() == 1;
          consumer.accept(perMethod.exlined.get(0));
        }
      }
    }
  }

  /** Returns the PerMethod object for the given method. */
  PerMethod perMethod(VmMethod method) {
    // We shouldn't be creating per-scope state for a method with a fixedMemo.
    assert Thread.holdsLock(this) && method.fixedMemo == null;
    return perMethods.computeIfAbsent(method, this::newPerMethod);
  }

  private PerMethod newPerMethod(VmMethod method) {
    return new PerMethod(method, forceExlined != null && forceExlined.test(method));
  }

  /** For test assertions only. */
  Collection<PerMethod> allPerMethods() {
    return perMethods.values();
  }

  /** Queues a call to {@link #checkForOverlap}. */
  void needsCheck(MethodMemo memo) {
    // If the queue was likely to get long this could get inefficient, but in practice I expect it
    // to have very few elements.
    if (!needsCheck.contains(memo)) {
      needsCheck.add(memo);
    }
  }

  /** Queues a call to {@link #checkForSelfOverlap}. */
  private void needsSelfCheck(MethodMemo memo) {
    // If the queue was likely to get long this could get inefficient, but in practice I expect it
    // to have even fewer than the needsCheck queue.
    if (!needsSelfCheck.contains(memo)) {
      needsSelfCheck.push(memo);
    }
  }

  /**
   * Completes all asynchronous checks queued by {@link #needsCheck(MethodMemo)} and any resulting
   * merges.
   */
  void finishChecks() {
    assert Thread.holdsLock(this);
    for (; ; ) {
      // Complete all calls to checkForOverlap() before we consider checkForSelfOverlap()
      MethodMemo next = needsCheck.poll();
      if (next != null) {
        // If this MethodMemo has been merged since it was queued there's nothing we need to do.
        if (!next.isForwarded()) {
          checkForOverlap(next.perMethod, next);
        }
        continue;
      }
      // pollFirst() is corresponds to pop() when using a deque as a stack
      next = needsSelfCheck.pollFirst();
      if (next != null) {
        // If this MethodMemo is no longer heavy there's nothing we need to do.
        // (forwarded? exlined? lightweight again? doesn't matter.)
        if (!next.isHeavy()) {
          continue;
        }
        // Because I think it's better to do this check for parents before children, we pay the
        // cost of a potentially expensive check for whether any of next's ancestors are also on
        // stack, and pull them out if they are.  In practice I expect the stack to usually be
        // empty at this point the cost should be minimal, but if profiling shows I'm wrong we
        // should switch to a more scalable approach (e.g. we could record MethodMemo depth and
        // use a priority queue here?)
        MethodMemo best = next;
        if (!needsSelfCheck.isEmpty()) {
          for (MethodMemo parent = next.parent(); parent != null; parent = parent.parent()) {
            if (parent.isHeavy() && needsSelfCheck.contains(parent)) {
              best = parent;
            }
          }
          if (best != next) {
            needsSelfCheck.remove(best);
            needsSelfCheck.push(next);
          }
        }
        checkForSelfOverlap(best.perMethod, best);
        continue;
      }
      // If both needsCheck and needsSelfCheck are empty then we're done.
      break;
    }
    assert allSettled();
  }

  /**
   * The given MethodMemo has just become heavy, or it was already heavy and its argsMemo has
   * changed so that it might now overlap with another heavy or exlined memo. Compare it with all of
   * the other heavy and exlined memos. If it is similar enough to one of them, merge it; otherwise
   * mark it as heavy (if it wasn't already) and save it on our heavy list.
   */
  void checkForOverlap(PerMethod method, MethodMemo mm) {
    // This check is done asynchronously some time after MemoMerger.needsCheck() was called;
    // it's possible that the memo has been merged in the meantime, in which case there's
    // nothing for us to do.
    if (mm.isForwarded()) {
      return;
    }
    if (mm.isLight()) {
      if (!mm.weightOverThreshold()) {
        // This MethodMemo's weight has been reduced since it was queued, or maybe its child's
        // weight has increased?
        // All that we should do now is check to see that the final weight was propagated.
        mm.propagateWeightChange(this);
        return;
      }
      // Ensure other threads aren't changing our argsMemo while we do these comparisons.
      mm.argsMemo().setWriteRequiresExtraLock(true);
    }
    for (MethodMemo other : method.exlined) {
      if (other != mm && mm.isSimilarEnough(other)) {
        if (!mm.isExlined()) {
          // The mergeInto() call below will leave a forwarding pointer, but if mm has a
          // parent we might as well go ahead and replace it now.
          replaceInParent(mm, other);
        }
        mm.mergeInto(other, this);
        return;
      }
    }
    boolean needsSelfCheck = false;
    for (MethodMemo other : method.heavy) {
      if (other != mm) {
        // We postpone checks against MethodMemos from the same call site until we've done all
        // other merges; if our parents are going to be merged we'd rather do that first.
        // (We're trying to avoid ending up with an exlined method that's only called from one
        // place.)
        if (mm.sameCallSite(other)) {
          needsSelfCheck = true;
        } else if (mm.isSimilarEnough(other)) {
          mergeWithHeavy(method, mm, other);
          return;
        }
      }
    }
    // We didn't find any merge candidates, so record this one as heavy so that it's available for
    // comparison in the future.
    if (mm.isLight()) {
      mm.setHeavy();
      method.addHeavy(mm);
    }
    // We postponed propagating the weight increase that triggered this check until now, since
    // if we'd ended up merging it that would have reduced its weight.
    mm.propagateWeightChange(this);
    if (needsSelfCheck) {
      // We skipped a potential merge with at least one other heavy memo at the same site, so
      // come back and check those after all of the other checks are done.
      needsSelfCheck(mm);
    }
  }

  /**
   * Compare {@code mm} with other heavy memos from the same call site; if we find one that is
   * similar enough, merge them. These checks were delayed from {@link #checkForOverlap} since other
   * merges may make them unnecessary.
   */
  private void checkForSelfOverlap(PerMethod method, MethodMemo mm) {
    for (MethodMemo other : method.heavy) {
      if (other != mm && mm.sameCallSite(other) && mm.isSimilarEnough(other)) {
        mergeWithHeavy(method, mm, other);
        return;
      }
    }
  }

  /**
   * We've decided to merge {@code m1} and {@code m2}; {@code m2} is heavy, and {@code m1} may be
   * light, heavy, or exlined.
   */
  private void mergeWithHeavy(PerMethod method, MethodMemo m1, MethodMemo m2) {
    assert !m1.isForwarded() && m2.isHeavy() && m1.perMethod == method && m2.perMethod == method;
    // If m1 wasn't already exlined, we're going to make it exlined.
    if (!m1.isExlined()) {
      // setExlined() will null m1's parent, so save it first.
      MethodMemo parent = m1.parent();
      int prevWeight = m1.weight();
      if (m1.isHeavy()) {
        method.removeHeavy(m1);
      }
      m1.setExlined(this);
      method.addExlined(m1);
      if (parent != null) {
        parent.updateChildWeight(prevWeight, m1.weight());
        parent.propagateWeightChange(this);
      }
    }
    replaceInParent(m2, m1);
    m2.mergeInto(m1, this);
  }

  /** If {@code mm} has a parent, update its parent to point to {@code replacement} instead. */
  private void replaceInParent(MethodMemo mm, MethodMemo replacement) {
    MethodMemo parent = mm.parent();
    if (parent != null) {
      parent.replaceChild(mm, replacement);
      parent.propagateWeightChange(this);
    }
  }

  /**
   * This is a moderately expensive consistency check across all of the MemoMerger data structures,
   * intended for use only in assertions (it always returns true or throws an AssertionError).
   */
  private boolean allSettled() {
    for (PerMethod perMethod : perMethods.values()) {
      for (MethodMemo exlined : perMethod.exlined) {
        assert exlined.isExlined();
        exlined.checkConsistency(true);
      }
      for (MethodMemo heavy : perMethod.heavy) {
        assert heavy.isHeavy();
        heavy.checkConsistency(true);
      }
    }
    return true;
  }
}
