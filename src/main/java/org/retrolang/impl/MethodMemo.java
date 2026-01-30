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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.BiConsumer;
import org.retrolang.util.StringUtil;

/**
 * A MethodMemo saves information from prior executions of a VmMethod. Its primary state is
 *
 * <ul>
 *   <li>a ValueMemo for the arguments that have been passed to the method;
 *   <li>a CallMemo for each CallSite in the method (which in turn tracks which methods have been
 *       invoked from that CallSite, and a MethodMemo for each of them); and
 *   <li>ValueMemos for the results of calls: for methods defined by InstructionBlocks each CallSite
 *       has its own ValueMemo, while for built-in methods there is a ValueMemo for each
 *       Continuation.
 * </ul>
 *
 * <p>While most MethodMemos are mutable, for the simplest built-in methods (those that make no
 * nested calls and need no other state) we create an immutable MethodMemo that can be re-used at
 * each call site.
 */
public final class MethodMemo {
  /**
   * All of the state related to our method that has been collected in our scope. Null if this
   * MethodMemo was created by {@link InstructionBlock#memoForApply} (i.e. this memo is only being
   * used to execute an anonymous InstructionBlock).
   *
   * <p>Most of the PerMethod state is only for use by the MemoMerger, but {@code perMethod.method}
   * is the way to get the VmMethod that this memo is for. (We don't have a direct link in order to
   * save a field in these objects, of which there are many.)
   */
  final MemoMerger.PerMethod perMethod;

  /**
   * One of LIGHT, HEAVY, EXLINED, or FORWARDED. Would be an enum except that we're trying to
   * minimize the size of this object.
   */
  private byte state;

  /**
   * The (possibly clamped, possibly out-of-date) uint8 value of currentWeight that was used to
   * compute our parent's current weight.
   *
   * <p>Storing this separately from {@link #currentWeight} has two advantages:
   *
   * <ul>
   *   <li>Recomputing our parent's weight may happen asynchronously; keeping track of the last
   *       weight they've seen simplifies the update logic.
   *   <li>{@code reportedWeight} is clamped to MAX_WEIGHT, enabling us to skip propagating updates
   *       that could not have any effect (if a merge isn't triggered by MAX_WEIGHT it won't be
   *       triggered by any larger value).
   * </ul>
   */
  private byte reportedWeight;

  /**
   * The largest reportedWeight over all our child MethodMemos; a value greater than MAX_WEIGHT is
   * used as a flag that this field needs to be recomputed.
   */
  private byte maxChildWeight;

  /**
   * If state is EXLINED, this is the (constant) weight of a call (EXLINE_CALL_WEIGHT); otherwise
   * should match the result returned by {@link #computeWeight}.
   */
  private int currentWeight;

  /**
   * Records the arguments that have been passed to this method; null if this MethodMemo is
   * immutable.
   *
   * <p>If state is LIGHT, can be modified with just itself locked; if state is EXLINED or HEAVY,
   * can only be modified while holding the MemoMerger lock.
   */
  final ValueMemo argsMemo;

  /**
   * Records the results that have been returned from this method; null if this MethodMemo is
   * immutable.
   *
   * <p>If state is LIGHT, can be modified with just itself locked; if state is EXLINED or HEAVY,
   * can only be modified while holding the MemoMerger lock.
   */
  final ValueMemo resultsMemo;

  /**
   * A CallMemo for each of this method's call sites. Individual elements are null if that call site
   * has not yet been reached; the whole array is replaced with null if state is FORWARDED.
   */
  private CallMemo[] callMemos;

  /**
   * A ValueMemo for each of this method's call sites (user methods) or continuations (builtin
   * methods). Individual elements are null if that call site or continuation has not yet been
   * reached; the whole array is replaced with null if state is FORWARDED.
   */
  private ValueMemo[] valueMemos;

  /**
   * If state is LIGHT or HEAVY, the MethodMemo that contains this one (or null if this is the root
   * of the computation); if state is FORWARDED, the MethodMemo that should be used in place of this
   * one. Null if state is EXLINED.
   */
  private MethodMemo parent;

  /**
   * If state is LIGHT or HEAVY, extra is a CallSite identifying the CallMemo in {@link #parent}
   * that contains this MethodMemo. If state is EXLINED and perMethod.method.fixedMethod == null
   * (i.e. this is not a trivial, shared MethodMemo), extra is a CodeGenLink. Otherwise (state is
   * FORWARDED or this is a shared MethodMemo) extra is null.
   */
  private Object extra;

  // Possible values for state.
  private static final byte LIGHT = 1;
  private static final byte HEAVY = 2;
  private static final byte EXLINED = 3;
  private static final byte FORWARDED = 4;

  // The weight of a call to an exlined method doesn't depend on the weight of the method called.
  static final int EXLINE_CALL_WEIGHT = 3;

  // How high can currentWeight get before a MethodMemo is considered heavy?
  // It's a little more than a simple threshold (see weightOverThreshold()), but
  // it's somewhere between LOW_EXLINE_THRESHOLD and HIGH_EXLINE_THRESHOLD (inclusive).
  private static final int LOW_EXLINE_THRESHOLD = 32;
  private static final int MINIMUM_ADD_TO_CHILD = 5;
  private static final int HIGH_EXLINE_THRESHOLD = LOW_EXLINE_THRESHOLD + MINIMUM_ADD_TO_CHILD;

  // There's no point in updating our reported weight once it's above this level.
  private static final int MAX_WEIGHT = HIGH_EXLINE_THRESHOLD;

  static {
    // maxChildWeight must be able to store MAX_WEIGHT+1 without overflowing.
    assert MAX_WEIGHT < 255;
  }

  /** A VarHandle for accessing elements of a {@code ValueMemo[]}. */
  private static final VarHandle VALUE_MEMO_ARRAY_ELEMENT =
      MethodHandles.arrayElementVarHandle(ValueMemo[].class);

  /** A VarHandle for accessing elements of a {@code CallMemo[]}. */
  private static final VarHandle CALL_MEMO_ARRAY_ELEMENT =
      MethodHandles.arrayElementVarHandle(CallMemo[].class);

  private static final CallMemo[] NO_CALL_MEMOS = new CallMemo[0];
  private static final ValueMemo[] NO_VALUE_MEMOS = new ValueMemo[0];

  /**
   * If {@code factory} is null this MethodMemo will be immutable; this is used for simple builtins
   * with a fixed MethodMemo (since that MethodMemo will be shared across Scopes it must be
   * immutable).
   */
  MethodMemo(MemoMerger.PerMethod perMethod, Factory factory) {
    if (factory != null) {
      this.state = LIGHT;
      this.argsMemo = ValueMemo.withSize(factory.numArgs);
      this.resultsMemo = ValueMemo.withSize(factory.numResults);
      this.callMemos =
          (factory.numCallMemos == 0) ? NO_CALL_MEMOS : new CallMemo[factory.numCallMemos];
      this.valueMemos =
          (factory.numValueMemos == 0) ? NO_VALUE_MEMOS : new ValueMemo[factory.numValueMemos];
    } else {
      this.state = EXLINED;
      this.argsMemo = null;
      this.resultsMemo = null;
      this.callMemos = NO_CALL_MEMOS;
      this.valueMemos = NO_VALUE_MEMOS;
    }
    this.perMethod = perMethod;
    this.currentWeight = (perMethod == null) ? 1 : perMethod.method.baseWeight;
    assert currentWeight > 0 && currentWeight < LOW_EXLINE_THRESHOLD;
    this.reportedWeight = (byte) currentWeight;
  }

  static class Factory {
    final int numArgs;
    final int numResults;
    final int numCallMemos;
    final int numValueMemos;

    /** For use by subclasses only. */
    Factory(int numArgs, int numResults, int numCallMemos, int numValueMemos) {
      this.numArgs = numArgs;
      this.numResults = numResults;
      this.numCallMemos = numCallMemos;
      this.numValueMemos = numValueMemos;
    }

    static Factory create(int numArgs, int numResults, int numCallMemos, int numValueMemos) {
      return (numCallMemos == 0 && numValueMemos == 0)
          ? null
          : new Factory(numArgs, numResults, numCallMemos, numValueMemos);
    }

    MethodMemo newMemo(MemoMerger.PerMethod perMethod) {
      return new MethodMemo(perMethod, this);
    }
  }

  /** Returns true if this MethodMemo is immutable and shared by all callers of the method. */
  boolean isFixed() {
    return argsMemo == null;
  }

  /** Returns true if this MethodMemo has been merged into another. */
  boolean isForwarded() {
    return state == FORWARDED;
  }

  /**
   * If this MethodMemo has been merged into another, returns the MethodMemo to use in its place;
   * otherwise returns {@code this}.
   */
  MethodMemo resolve() {
    // To avoid races should only be called while the MemoMerger is locked
    assert Thread.holdsLock(TState.get().scope().memoMerger);
    MethodMemo result = this;
    if (state == FORWARDED) {
      result = parent;
      // It's possible (if unlikely) that we end up with a chain of forwarded MethodMemos
      while (result.state == FORWARDED) {
        result = result.parent;
        parent = result;
      }
    }
    return result;
  }

  /**
   * Returns true if this MethodMemo is unshared and below the weight threshold to be considered
   * shareable.
   */
  boolean isLight() {
    return state == LIGHT;
  }

  /**
   * Returns true if this MethodMemo is unshared but its weight is high enough to make it a
   * candidate for sharing.
   */
  boolean isHeavy() {
    return state == HEAVY;
  }

  /**
   * Returns true if this MethodMemo is exlined, allowing it to be referenced by multiple parents.
   */
  boolean isExlined() {
    return state == EXLINED;
  }

  /**
   * Returns true if both MethodMemos are unshared and called from the same CallSite in their
   * parents.
   */
  boolean sameCallSite(MethodMemo other) {
    return extra instanceof CallSite && extra == other.extra;
  }

  /** Changes this memo's state from LIGHT to HEAVY. */
  void setHeavy() {
    assert state == LIGHT && currentWeight >= LOW_EXLINE_THRESHOLD;
    state = HEAVY;
  }

  /**
   * Changes this memo's state from LIGHT or HEAVY to EXLINED. Called either before the memo is used
   * (for top-level memos) or when it is being merged.
   */
  void setExlined() {
    assert (state == LIGHT || state == HEAVY);
    if (parent != null) {
      assert currentWeight >= LOW_EXLINE_THRESHOLD && extra instanceof CallSite;
      parent = null;
      currentWeight = EXLINE_CALL_WEIGHT;
      reportedWeight = (byte) EXLINE_CALL_WEIGHT;
    } else {
      assert extra == null;
    }
    extra = new CodeGenLink(this, CodeGenLink.Kind.EXLINED);
    state = EXLINED;
  }

  /** This memo's weight, as seen by its parent. */
  int weight() {
    return reportedWeight & 0xff;
  }

  /** The maximum {@link #weight} of any of this memo's children. */
  int maxChildWeight() {
    return maxChildWeight & 0xff;
  }

  /** This memo's parent, or null if this memo is exlined. */
  MethodMemo parent() {
    // The parent field is also used to store the forwarding pointer when state is FORWARDED;
    // we shouldn't be getting that with this method.
    assert state != FORWARDED;
    return parent;
  }

  /** This memo's CallSite or CodeGenLink. */
  Object extra() {
    return extra;
  }

  /**
   * Moves a MethodMemo from one parent to another; called because the old parent is being merged
   * into {@code newParent}.
   */
  void updateParent(MethodMemo newParent) {
    assert state != FORWARDED;
    if (state != EXLINED) {
      this.parent = newParent;
    }
    // newParent just gained a child
    newParent.updateChildWeight(0, weight());
    // We don't need to update the old parent's weight, since our next step will be to forward it to
    // the new parent.
  }

  ValueMemo argsMemo() {
    assert state != FORWARDED;
    return argsMemo;
  }

  int currentWeight() {
    assert state != FORWARDED;
    return currentWeight;
  }

  /**
   * Recomputes this MethodMemo's weight. Should always match currentWeight; intended only for
   * assertions.
   */
  int computeWeight() {
    int sum = (perMethod == null) ? 1 : perMethod.method.baseWeight;
    for (CallMemo cm : callMemos) {
      if (cm != null) {
        // It's (theoretically) possible for this computation to overflow an int, but since child
        // weights are capped at MAX_WEIGHT you'd need more than 50M children to do so; for now I'm
        // choosing not to worry about that possibility.
        sum = Math.addExact(sum, cm.totalWeight());
      }
    }
    return sum;
  }

  /** Returns true if this MethodMemo's weight exceeds the given limit. */
  boolean weightExceeds(Scope scope, int limit) {
    // Once our weight reaches LOW_EXLINE_THRESHOLD we may stop tracking it accurately, so we can't
    // reliably answer this question for high limits.
    assert limit < LOW_EXLINE_THRESHOLD;
    MethodMemo memo = this;
    synchronized (scope.memoMerger) {
      while (memo.state == FORWARDED) {
        memo = memo.parent;
      }
      return (memo.state != LIGHT) || (memo.currentWeight > limit);
    }
  }

  /**
   * Recomputes this MethodMemo's maximum child weight. Should match maxChildWeight, unless
   * maxChildWeight > MAX_WEIGHT (the flag value indicating that it needs to be recomputed by this
   * method).
   */
  int computeMaxChildWeight() {
    int max = 0;
    for (CallMemo cm : callMemos) {
      if (cm != null) {
        max = Math.max(max, cm.maxWeight());
      }
    }
    return max;
  }

  /**
   * Returns the ValueMemo to use for the specified call site or continuation; if this is the first
   * request for that ValueMemo, constructs one of the specified size.
   */
  ValueMemo valueMemo(TState tstate, int index, int size) {
    // First try to find an existing memo without acquiring the scope lock.
    ValueMemo[] valueMemos = this.valueMemos;
    if (valueMemos != null) {
      ValueMemo result = valueMemos[index];
      if (result != null) {
        return result;
      }
    }
    // We need to create a new ValueMemo, or this memo has been forwarded, or maybe we raced against
    // someone creating the ValueMemo we need; lock the MemoMerger and try again.
    MemoMerger merger = tstate.scope().memoMerger;
    synchronized (merger) {
      return resolve().lockedValueMemo(index, size);
    }
  }

  private ValueMemo lockedValueMemo(int index, int size) {
    ValueMemo result = valueMemos[index];
    if (result == null) {
      result = ValueMemo.withSize(size);
      // valueMemos[index] = result
      // ... except don't make an incompletely-initialized ValueMemo visible to other threads
      VALUE_MEMO_ARRAY_ELEMENT.setRelease(valueMemos, index, result);
    }
    return result;
  }

  /**
   * Returns the CallMemo to use for the given call site; should only be called while holding the
   * MemoMerger lock.
   */
  CallMemo memoForCall(CallSite callSite) {
    return resolve().callMemos[callSite.cIndex];
  }

  /**
   * Returns the MethodMemo to use for the given method at the given call site; harmonizes {@code
   * args} with the result's {@link #argsMemo}.
   */
  MethodMemo memoForCall(TState tstate, CallSite callSite, VmMethod method, Object[] args) {
    // First try to find an existing memo without acquiring the scope lock.
    CallMemo[] callMemos = this.callMemos;
    if (callMemos != null) {
      CallMemo callMemo = callMemos[callSite.cIndex];
      if (callMemo != null) {
        MethodMemo result = callMemo.memoForMethod(method, false);
        if (result != null) {
          // If we get this far, harmonizing the args will usually work, but if (a) the MethodMemo
          // is exlined or heavy, and (b) the argsMemo needs to be updated to include these args,
          // then we'll need to back out, acquire the MemoMerger lock, and do it all again.
          ValueMemo.Outcome outcome = result.harmonizeArgs(tstate, args, false);
          if (outcome != ValueMemo.Outcome.CHANGE_REQUIRES_EXTRA_LOCK) {
            return result;
          }
        }
      }
    }
    // We need to create a new MethodMemo, or we need to update the argsMemo while holding the lock,
    // or maybe we just raced with someone who was updating those structures as we tried to read
    // them; lock the MemoMerger and try again.
    MemoMerger merger = tstate.scope().memoMerger;
    synchronized (merger) {
      return resolve().lockedMemoForCall(tstate, merger, callSite, method, args);
    }
  }

  private MethodMemo lockedMemoForCall(
      TState tstate, MemoMerger merger, CallSite callSite, VmMethod method, Object[] args) {
    // Sanity check: we've just acquired the MemoMerger lock, so all the invariants should be true
    assert weight() == Math.min(currentWeight, MAX_WEIGHT);
    CallMemo callMemo = callMemos[callSite.cIndex];
    if (callMemo != null) {
      MethodMemo result = callMemo.memoForMethod(method, true);
      if (result != null) {
        // Update argsMemo
        ValueMemo.Outcome outcome = result.harmonizeArgs(tstate, args, true);
        assert outcome != ValueMemo.Outcome.CHANGE_REQUIRES_EXTRA_LOCK;
        if (outcome == ValueMemo.Outcome.CHANGED && result.state != LIGHT) {
          // That harmonize() call changed the argsMemo, so re-check for overlaps.
          merger.needsCheck(result);
          merger.finishChecks();
          result = result.resolve();
        }
        return result;
      }
    }
    MethodMemo result;
    if (method.fixedMemo != null) {
      result = method.fixedMemo;
    } else {
      result = method.newMemo(merger, args);
      assert result.perMethod.method == method && result.parent == null;
      if (result.state == LIGHT) {
        assert result.extra == null;
        result.parent = this;
        result.extra = callSite;
      } else {
        // There was already a matching exlined method.
        assert result.state == EXLINED && result.extra instanceof CodeGenLink;
      }
      ValueMemo.Outcome outcome = result.harmonizeArgs(tstate, args, true);
      assert outcome != ValueMemo.Outcome.CHANGE_REQUIRES_EXTRA_LOCK;
    }
    if (callMemo == null) {
      // The first MethodMemo for this CallMemo.
      // If we haven't already created the corresponding ValueMemo, do that while we're holding the
      // lock so that we don't have to reacquire the lock later.
      if (callSite.vIndex >= 0) {
        var unused = lockedValueMemo(callSite.vIndex, callSite.vSize());
      }
    }
    callMemo = new CallMemo(result, callMemo);
    // callMemos[callSite.cIndex] = callMemo
    // ... except don't make an incompletely-initialized MethodMemo visible to other threads
    CALL_MEMO_ARRAY_ELEMENT.setRelease(callMemos, callSite.cIndex, callMemo);
    if (state != EXLINED) {
      updateChildWeight(0, result.weight());
      propagateWeightChange(merger);
      merger.finishChecks();
    }
    return result;
  }

  /**
   * Called when the weight of one of this memo's children has changed from {@code prev} to {@code
   * now}, or when a new child has been added ({@code prev == 0}).
   */
  void updateChildWeight(int prev, int now) {
    assert prev >= 0 && now > 0 && prev <= MAX_WEIGHT && now <= MAX_WEIGHT;
    if (state == EXLINED) {
      // Once a memo is exlined we no longer care about its weight.
      return;
    }
    // Our weight includes the sum of all our children's weights, so it can't be less than prev.
    assert prev <= currentWeight;
    currentWeight = Math.addExact(currentWeight, now - prev);
    // If the child's weight increased, it's easy to update maxChildWeight, but if it used to be
    // our biggest child and now has decreased we'll need to do a full scan of our children to
    // find the new max; fortunately this is expected to be rare.
    if (now >= prev) {
      // Note that if maxChildWeight is the "needs recomputation" value this will leave it unchanged
      maxChildWeight = (byte) Math.max(maxChildWeight(), now);
    } else if (prev == maxChildWeight()) {
      // Postpone the recomputation until we actually need to know.
      maxChildWeight = MAX_WEIGHT + 1;
    }
  }

  /**
   * Any code that changes {@link #currentWeight} should (sooner or later) call {@code
   * propagateWeightChange} to ensure that this memo's ancestors are properly updated.
   */
  void propagateWeightChange(MemoMerger merger) {
    for (MethodMemo mm = this; mm.checkWeight(merger); mm = mm.parent) {}
  }

  /** Returns true if a weight change was propagated to our parent. */
  boolean checkWeight(MemoMerger merger) {
    if (state == EXLINED || state == FORWARDED) {
      return false;
    }
    int newReported = Math.min(MAX_WEIGHT, currentWeight);
    int reported = weight();
    if (newReported == reported) {
      // Our weight hasn't changed, or was already high enough that our parent doesn't care about
      // further increases.
      return false;
    }
    if (newReported > reported && state == LIGHT && parent != null) {
      // If this MethodMemo is becoming heavy, postpone reporting the increased weight to our parent
      // until we've had a chance to check for possible exlining.
      if (weightOverThreshold()) {
        merger.needsCheck(this);
        return false;
      }
    }
    this.reportedWeight = (byte) newReported;
    if (newReported < reported && state == HEAVY && !weightOverThreshold()) {
      // We were heavy, but one or more of our children are now enough lighter (because they or
      // their children were exlined) that we're no longer a candidate for sharing.
      perMethod.removeHeavy(this);
      state = LIGHT;
    }
    if (parent == null || parent.state == EXLINED) {
      // We have no parent or our parent is exlined; there's no need to continue propagating.
      return false;
    }
    parent.updateChildWeight(reported, newReported);
    return true;
  }

  /** Returns true if this method should be considered heavy (and thus a candidate for sharing). */
  boolean weightOverThreshold() {
    assert state != EXLINED;
    if (currentWeight < LOW_EXLINE_THRESHOLD) {
      return false;
    } else if (currentWeight >= HIGH_EXLINE_THRESHOLD) {
      return true;
    }
    // If this memo's weight is over the minimum threshold, but almost all of it is due to a single
    // child (i.e. our weight is less than MINIMUM_ADD_TO_CHILD over our child's weight) we'll
    // deem it not worth merging -- we'd rather wait a little and see if the child gets big enough
    // to merge on its own.
    int maxChildWeight = maxChildWeight();
    if (maxChildWeight > MAX_WEIGHT) {
      maxChildWeight = (byte) computeMaxChildWeight();
      this.maxChildWeight = (byte) maxChildWeight;
    }
    return currentWeight >= maxChildWeight + MINIMUM_ADD_TO_CHILD;
  }

  /** Replaces the specified child of this memo with another, exlined MethodMemo. */
  void replaceChild(MethodMemo child, MethodMemo replacement) {
    assert child.parent == this
        && child.perMethod == replacement.perMethod
        && replacement.isExlined();
    CallSite callSite = (CallSite) child.extra;
    CallMemo callMemo = callMemos[callSite.cIndex];
    callMemo.replaceMemo(child, replacement);
    updateChildWeight(child.weight(), replacement.weight());
  }

  /** True if this (exlined) MethodMemo should be used for a new call with the given args. */
  boolean couldCastArgs(Object[] args) {
    return argsMemo == null || argsMemo.couldCast(args);
  }

  /** Harmonizes the given arguments for this method. */
  @CanIgnoreReturnValue
  ValueMemo.Outcome harmonizeArgs(TState tstate, Object[] values, boolean isLocked) {
    return argsMemo == null
        ? ValueMemo.Outcome.NO_CHANGE_REQUIRED
        : argsMemo.harmonizeAll(tstate, values, isLocked);
  }

  /** Harmonizes the results that this method is returning. */
  void harmonizeResults(TState tstate, Value[] results) {
    if (resultsMemo != null) {
      ValueMemo.Outcome outcome = resultsMemo.harmonizeAll(tstate, results, false);
      if (outcome == ValueMemo.Outcome.CHANGE_REQUIRES_EXTRA_LOCK) {
        synchronized (tstate.scope().memoMerger) {
          resultsMemo.harmonizeAll(tstate, results, true);
        }
      }
    }
  }

  /**
   * Returns true if the given MethodMemo (for the same VmMethod as this) is "similar enough" to
   * this to justify merging them.
   *
   * <p>The current test looks only for similarity in the args; a better (but more expensive) test
   * would be to see if they have similar children at corresponding call sites. Comparing result
   * memos would be another possibility, although it's not clear if they provide any additional
   * information.
   */
  boolean isSimilarEnough(MethodMemo other) {
    // Neither argsMemo can be null here (argsMemo is null only for a fixedMemo, which is never a
    // candidate for merging).
    return argsMemo.overlaps(other.argsMemo);
  }

  /** Leaves this memo forwarded to {@code other}. */
  void mergeInto(MethodMemo other, MemoMerger merger) {
    assert other != this
        && other.perMethod == this.perMethod
        && (other.extra instanceof CodeGenLink || other.extra == this.extra);
    if (state == EXLINED) {
      perMethod.removeExlined(this);
    } else if (state == HEAVY) {
      perMethod.removeHeavy(this);
    }
    argsMemo.mergeInto(other.argsMemo);
    resultsMemo.mergeInto(other.resultsMemo);
    if (other.state != LIGHT) {
      merger.needsCheck(other);
    }
    for (int i = 0; i < valueMemos.length; i++) {
      ValueMemo vm1 = valueMemos[i];
      if (vm1 != null) {
        ValueMemo vm2 = other.valueMemos[i];
        if (vm2 != null) {
          vm1.mergeInto(vm2);
        } else {
          other.valueMemos[i] = vm1;
        }
      }
    }
    for (int i = 0; i < callMemos.length; i++) {
      CallMemo cm1 = callMemos[i];
      CallMemo cm2 = other.callMemos[i];
      if (cm1 != null) {
        if (cm2 != null) {
          cm1.mergeInto(cm2, other, merger);
        } else {
          cm1.updateParents(other);
          other.callMemos[i] = cm1;
          cm2 = cm1;
        }
      }
      if (cm2 != null) {
        cm2.checkForExlinedLightWeights(merger);
      }
      // It's possible for the recursive merges to end up forwarding other
      other = other.resolve();
    }
    state = FORWARDED;
    valueMemos = null;
    callMemos = null;
    extra = null;
    parent = other;
  }

  /**
   * Calls {@code action} for each of this MethodMemo's children, with the child and the CallMemo
   * index. Used only for tests.
   */
  void forEachChild(BiConsumer<Integer, MethodMemo> action) {
    for (int i = 0; i < callMemos.length; ++i) {
      int finalI = i;
      callMemos[i].forEachChild((mm, count) -> action.accept(finalI, mm));
    }
  }

  /**
   * Called directly or indirectly from {@link MemoMerger#allSettled} to check that this memo is in
   * a consistent state.
   *
   * <p>If {@code root} is true this is a direct call and we always do the full suite of checks; if
   * {@code root} is false we're following a link (child or forwarding) to this memo from another
   * MethodMemo, and if this memo is exlined or heavy we'll verify only that it's on the appropriate
   * list and then skip the rest of the checks since we know allSettled() will call us directly.
   */
  void checkConsistency(boolean root) {
    if (state == FORWARDED) {
      assert (parent.state == FORWARDED || parent.state == EXLINED);
      parent.checkConsistency(false);
      return;
    }
    assert weight() == Math.min(currentWeight, MAX_WEIGHT);
    if (state == EXLINED && !root) {
      assert perMethod.method.fixedMemo == this || perMethod.exlinedContains(this);
    } else if (state == HEAVY && !root) {
      assert perMethod.heavyContains(this);
    } else {
      if (state != EXLINED) {
        assert currentWeight == computeWeight();
        assert maxChildWeight() == MAX_WEIGHT + 1 || maxChildWeight() == computeMaxChildWeight();
        // weightOverThreshold() has a potential side-effect (it recomputes the maxChildWeight if
        // it's out-of-date) so static checkers get cranky if we call it in an assert.
        boolean isHeavy = weightOverThreshold();
        assert state == (isHeavy ? HEAVY : LIGHT);
      }
      for (CallMemo callMemo : callMemos) {
        CallMemo.checkConsistency(callMemo);
      }
    }
  }

  @Override
  public String toString() {
    String suffix;
    if (state == FORWARDED) {
      suffix = "_f";
    } else if (state == EXLINED) {
      suffix = "_x";
    } else {
      suffix = ((state == HEAVY) ? "_h" : "_") + currentWeight;
    }
    return String.format("mMemo%s@%s", suffix, StringUtil.id(this));
  }
}
