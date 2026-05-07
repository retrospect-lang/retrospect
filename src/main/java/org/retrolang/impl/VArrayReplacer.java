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

import static org.retrolang.impl.CopyPlan.EMPTY;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import org.retrolang.impl.CopyOptimizer.Policy;
import org.retrolang.impl.CopyPlan.Basic;
import org.retrolang.impl.CopyPlan.Step;
import org.retrolang.impl.CopyPlan.StepType;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.RefVar;
import org.retrolang.util.ArrayUtil;
import org.retrolang.util.Bits;

/**
 * VArrayReplacer provides an optimized implementation of {@link Evolver.FrameReplacer} for copying
 * Frames from one varray layout to another.
 *
 * <p>The optimization comes in 3 stages:
 *
 * <ul>
 *   <li>First, construct a CopyPlan to initialize each row of the destination from the
 *       corresponding row of the source. Since we will often be copying varrays with hundreds or
 *       thousands of rows, constructing and using a CopyPlan is already likely to be much more
 *       efficient that the naive approach of {@link FrameLayout#simpleReplace}.
 *   <li>Second, use CopyOptimizer to move as many assignments as possible from inside Switches to
 *       the top level of the plan. Assignments at the top level of the plan can be implemented as a
 *       batch operation over the element array rather than by repeated calls to {@link
 *       CopyPlan.Step#execute}.
 *   <li>Third, in many cases a bulk copy operation can be further optimized by just sharing the
 *       element array between the source and destination varray. Since element arrays don't have
 *       their own reference counts we can't normally share them, but with a little care (to ensure
 *       that the shared array isn't deleted when we clear the original) this enables us to save
 *       both time (we don't need to allocate and initialize another element array) and memory.
 * </ul>
 *
 * <p>As a concrete example, consider a varray whose layout has evolved from {@code i0} to {@code
 * b0⸨0:i1; 1:None⸩} (i.e. from an array of integers to an array whose elements are either integers
 * or None). The initial CopyPlan (to be executed once per row) is {@code set(0, b0), copy(i0, i1)},
 * but we can optimize this to
 *
 * <ul>
 *   <li>Bulk set all elements of {@code b0} to 0.
 *   <li>Share {@code i0} in the source frame as {@code i1} in the new frame.
 * </ul>
 *
 * At this point the remaining CopyPlan is empty, so we don't need any per-row execution.
 *
 * <p>(In more complex cases we may not be able to eliminate the per-row execution, but may still
 * benefit if some of the operations can be done in bulk.)
 */
class VArrayReplacer implements Evolver.FrameReplacer {
  final VArrayLayout srcLayout;
  final VArrayLayout dstLayout;

  /**
   * The indices of the element arrays in {@link #srcLayout} that will be shared with the
   * destination frame.
   */
  final Bits sharedInSrc;

  /**
   * The indices of the element arrays in {@link #dstLayout} that will be shared with the source
   * frame.
   */
  final Bits sharedInDst;

  /** The per-row steps that could not be optimized into {@link #batchOps} or {@link #shares}. */
  final CopyPlan perRowPlan;

  /**
   * COPY_NUM, COPY_REF, SET_NUM, and SET_REF steps that can be done in batch. Does not include any
   * steps that were put in {@link #shares}.
   */
  final ImmutableList<Basic> batchOps;

  /** COPY_NUM and COPY_REF steps that can be implemented by sharing element arrays. */
  final ImmutableList<Basic> shares;

  /** Use {@link #create} to construct a VArrayReplacer. */
  private VArrayReplacer(
      VArrayLayout srcLayout,
      VArrayLayout dstLayout,
      Bits sharedInSrc,
      Bits sharedInDst,
      CopyPlan perRowPlan,
      ImmutableList<Basic> batchOps,
      ImmutableList<Basic> shares) {
    assert sharedInSrc.count() == sharedInDst.count();
    this.srcLayout = srcLayout;
    this.dstLayout = dstLayout;
    this.sharedInSrc = sharedInSrc;
    this.sharedInDst = sharedInDst.isEmpty() ? null : sharedInDst;
    this.perRowPlan = perRowPlan;
    this.batchOps = batchOps;
    this.shares = shares;
  }

  /**
   * Constructs a VArrayReplacer when no optimizations (beyond the construction of a CopyPlan) are
   * available.
   */
  private VArrayReplacer(VArrayLayout srcLayout, VArrayLayout dstLayout, CopyPlan perRowPlan) {
    this(
        srcLayout,
        dstLayout,
        Bits.EMPTY,
        Bits.EMPTY,
        perRowPlan,
        ImmutableList.of(),
        ImmutableList.of());
  }

  @Override
  public Frame copy(TState tstate, Frame src) {
    int numElements = srcLayout.numElements(src);
    if (numElements == 0) {
      // I think the only empty varrays are the non-refcounted ones (which don't come through here),
      // but it's possible that that will change at some point.
      return dstLayout.empty;
    }
    Frame dst = dstLayout.alloc(tstate, numElements, sharedInDst);
    for (Basic step : batchOps) {
      switch (step.type) {
        case COPY_NUM:
          {
            NumVar srcVar = (NumVar) step.src;
            NumVar dstVar = (NumVar) step.dst;
            byte[] srcArray = srcLayout.getElementArray(src, srcVar);
            byte[] dstArray = dstLayout.getElementArray(dst, dstVar);
            copyNums(srcArray, srcVar.encoding, dstArray, dstVar.encoding, numElements);
            continue;
          }
        case SET_NUM:
          {
            NumValue srcValue = (NumValue) step.src;
            NumVar dstVar = (NumVar) step.dst;
            byte[] dstArray = dstLayout.getElementArray(dst, dstVar);
            fillNums(srcValue, dstArray, dstVar.encoding, numElements);
            continue;
          }
        case SET_REF:
          {
            Value srcValue = (Value) step.src;
            assert !RefCounted.isRefCounted(srcValue);
            RefVar dstVar = (RefVar) step.dst;
            Object[] dstArray = dstLayout.getElementArray(dst, dstVar);
            Arrays.fill(dstArray, 0, numElements, srcValue);
            continue;
          }
        default:
          throw new AssertionError();
      }
    }
    for (Basic step : shares) {
      assert step.type == StepType.COPY_NUM || step.type == StepType.COPY_REF;
      int srcIndex = srcLayout.varIndex(step.src);
      int dstIndex = dstLayout.varIndex(step.dst);
      dstLayout.setElementArray(dst, dstIndex, srcLayout.getElementArray(src, srcIndex));
    }
    if (perRowPlan != EMPTY) {
      for (int i = 0; i < numElements; i++) {
        boolean ok =
            perRowPlan.execute(tstate, srcLayout.asVarSource(src, i), dstLayout.asVarSink(dst, i));
        assert ok;
      }
    }
    return dst;
  }

  /**
   * Copies {@code length} numbers from {@code src} (using {@code srcEncoding}) to {@code dst}
   * (using {@code dstEncoding}). {@code dstEncoding} must include all values of {@code srcEncoding}
   * (e.g. you can copy INT32 values to FLOAT64 but not vice versa).
   */
  private static void copyNums(
      byte[] src, NumEncoding srcEncoding, byte[] dst, NumEncoding dstEncoding, int length) {
    if (srcEncoding == dstEncoding) {
      // Currently always handled by sharing, not copying, but included for completeness.
      System.arraycopy(src, 0, dst, 0, length * srcEncoding.nBytes);
    } else if (srcEncoding == NumEncoding.INT32) {
      assert dstEncoding == NumEncoding.FLOAT64;
      for (int i = 0; i < length; i++) {
        ArrayUtil.bytesSetD(dst, i, ArrayUtil.bytesGetI(src, i));
      }
    } else {
      assert srcEncoding == NumEncoding.UINT8;
      if (dstEncoding == NumEncoding.INT32) {
        for (int i = 0; i < length; i++) {
          ArrayUtil.bytesSetI(dst, i, ArrayUtil.bytesGetB(src, i));
        }
      } else {
        assert dstEncoding == NumEncoding.FLOAT64;
        for (int i = 0; i < length; i++) {
          ArrayUtil.bytesSetD(dst, i, ArrayUtil.bytesGetB(src, i));
        }
      }
    }
  }

  /**
   * Stores {@code length} copies of {@code src} in {@code dst}, using {@code dstEncoding}. Caller
   * is responsible for ensuring that {@code src} can be represented in {@code dstEncoding}.
   */
  private static void fillNums(NumValue src, byte[] dst, NumEncoding dstEncoding, int length) {
    // If the non-byte cases here were a bottleneck they could be rewritten using
    // Unsafe.setMemory() but I don't expect that to be worth the effort.
    if (dstEncoding == NumEncoding.FLOAT64) {
      double srcDouble = NumValue.asDouble(src);
      for (int i = 0; i < length; i++) {
        ArrayUtil.bytesSetD(dst, i, srcDouble);
      }
    } else {
      int srcInt = NumValue.asInt(src);
      if (dstEncoding == NumEncoding.UINT8) {
        Arrays.fill(dst, 0, length, (byte) srcInt);
      } else {
        for (int i = 0; i < length; i++) {
          ArrayUtil.bytesSetI(dst, i, srcInt);
        }
      }
    }
  }

  @Override
  public String toString() {
    return String.format(
        "OptimizedReplacer{shares: %s, batchOps: %s, perRow: %s}", shares, batchOps, perRowPlan);
  }

  /** Returns a VArrayReplacer for the given source and destination layouts. */
  static VArrayReplacer create(VArrayLayout srcLayout, VArrayLayout dstLayout) {
    CopyPlan plan = CopyPlan.create(srcLayout.template, dstLayout.template, true);
    plan = CopyOptimizer.optimize(plan, dstLayout, Policy.VARRAY_REPLACE);
    // COPY_NUM or COPY_REF steps that we will handle by sharing
    ImmutableList.Builder<Basic> sharesBuilder = ImmutableList.builder();
    // COPY_NUM, SET_NUM, or SET_REF steps that we will handle by a bulk operation
    ImmutableList.Builder<Basic> batchOpsBuilder = ImmutableList.builder();
    // The steps in plan that we've put in sharesBuilder or batchOpsBuilder
    Bits.Builder removeFromPlan = new Bits.Builder();
    // The vars that appear as the src or dst of a sharing step
    Bits.Builder sharedInSrcBuilder = new Bits.Builder();
    Bits.Builder sharedInDstBuilder = new Bits.Builder();
    for (int i = 0; i < plan.steps.size(); i++) {
      if (!(plan.steps.get(i) instanceof Basic step)) {
        continue;
      }
      if (isShareableCopy(step)) {
        removeFromPlan.set(i);
        sharesBuilder.add(step);
        mustSet(sharedInSrcBuilder, srcLayout.varIndex(step.src));
        mustSet(sharedInDstBuilder, dstLayout.varIndex(step.dst));
      } else if (step.type == StepType.COPY_NUM
          || step.type == StepType.SET_NUM
          || step.type == StepType.SET_REF) {
        removeFromPlan.set(i);
        batchOpsBuilder.add(step);
      }
    }
    if (removeFromPlan.isEmpty()) {
      // Nothing shared, no batch ops -- the per-row plan is the best we can do
      return new VArrayReplacer(srcLayout, dstLayout, plan);
    }
    ImmutableList<Basic> batchOps = batchOpsBuilder.build();
    ImmutableList<Basic> shares = sharesBuilder.build();
    Bits sharedInSrc = sharedInSrcBuilder.build();
    Bits sharedInDst = sharedInDstBuilder.build();
    CopyPlan perRowPlan;
    if (batchOps.size() + shares.size() == plan.steps.size()) {
      perRowPlan = EMPTY;
    } else {
      ImmutableList.Builder<Step> perRowBuilder = ImmutableList.builder();
      for (int i = 0; i < plan.steps.size(); i++) {
        if (!removeFromPlan.test(i)) {
          perRowBuilder.add(plan.steps.get(i));
        }
      }
      perRowPlan = new CopyPlan(perRowBuilder.build());
    }
    return new VArrayReplacer(
        srcLayout, dstLayout, sharedInSrc, sharedInDst, perRowPlan, batchOps, shares);
  }

  /** Returns true if {@code step} is a COPY_REF, or a COPY_NUM with matching encodings. */
  private static boolean isShareableCopy(Basic step) {
    return (step.type == StepType.COPY_REF)
        || (step.type == StepType.COPY_NUM
            && ((NumVar) step.src).encoding == ((NumVar) step.dst).encoding);
  }

  /** Calls {@link Bits.Builder#set} and verifies that it returns true. */
  static void mustSet(Bits.Builder builder, int index) {
    // The reason I'm not using assert here: it's non-trivial to prove that this will never fail
    // (although I believe it's possible to do so), and if it *did* fail (because our sharing
    // steps weren't one-to-one) we'd likely end up with mysterious memory smashes far in the
    // future.
    if (!builder.set(index)) {
      throw new AssertionError();
    }
  }
}
