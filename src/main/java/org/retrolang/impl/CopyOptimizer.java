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

import com.google.common.collect.ImmutableList;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.retrolang.impl.CopyPlan.Basic;
import org.retrolang.impl.CopyPlan.Step;
import org.retrolang.impl.CopyPlan.StepType;
import org.retrolang.impl.CopyPlan.Switch;
import org.retrolang.impl.Template.Compound;
import org.retrolang.impl.Template.Constant;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.RefVar;
import org.retrolang.util.Bits;
import org.retrolang.util.Bits.Op;
import org.retrolang.util.SmallIntMap;

/**
 * CopyOptimizer identifies cases where a CopyPlan can be optimized by promoting one or more
 * assignments (COPY_NUM, SET_NUM, COPY_REF, or SET_REF) from inside switch branches to the top
 * level. "Optimized" here is judged primarily by reducing the number of assignments in the
 * resulting code; in some cases that might actually lead to more assignments being executed (if an
 * assignment is done at the top level and then overwritten by another assignment to the same
 * variable in the chosen switch branch), but we assume that on the whole reducing code size is an
 * improvement (and without statistics on how often each branch is taken we're unable to optimize
 * execution time directly).
 *
 * <p>CopyOptimizer objects are not created directly; the static {@link #optimize(CopyPlan, int,
 * int, int, Policy)} method (and a handful of convenience methods such as {@link #toRegisters} for
 * common cases) is the only entry point (the call to {@link #optimize} will create a CopyOptimizer
 * instance to hold temporary state if the plan to be optimized contains at least one Switch step).
 *
 * <p>Exactly when it is valid to promote an assignment, and when a promotion would be an
 * optimization, depends on how the plan will be used, so {@link #optimize} takes a {@link Policy}
 * argument.
 *
 * <p>When the policy is INITIALIZATION_REQUIRED, {@link #optimize} also ensures that all paths in
 * the returned plan assign values to all of the variables in {@code dst}; this is necessary when
 * the variables are Java locals, since those cannot be left uninitialized even if we do not care
 * about their values. This is an "optimization" in the sense that without it we would have to
 * blindly initialize all destination locals before executing the plan.
 *
 * <p>Note that CopyOptimizer currently only considers promoting assignments to the top level (i.e.
 * outside any switches); when there are nested switches there might be cases where a partial
 * promotion (from within a nested switch to within the containing switch) would be beneficial, but
 * for now we are not considering that possibility.
 */
class CopyOptimizer {

  // See the javadoc of optimize(CopyPlan, int, int, int, Policy) for an explanation of these
  // parameters.
  final int dstNumVarIndexBase;
  final int dstRefVarIndexBase;

  final Policy policy;

  /**
   * Indexed by the result of {@link #index(NumVar)} or {@link #index(RefVar)}; if non-null, a step
   * whose destination is the corresponding NumVar or RefVar.
   *
   * <p>We currently only consider promoting the first assignment we see to each destination NumVar
   * or RefVar. In some cases we could do better by choosing a later assignment (if it occurs more
   * often), but I'll wait for some evidence to justify the additional complexity of implementing
   * that.
   */
  private final Basic[] promotable;

  /** Set if the corresponding step in {@link #promotable} has been seen more than once. */
  private final Bits.Builder foundMultiple = new Bits.Builder();

  /**
   * Set if the corresponding destination variable is also set by steps other than the one in {@link
   * #promotable}.
   */
  private final Bits.Builder conflicted = new Bits.Builder();

  /**
   * Set if the corresponding destination variable has been set outside of any switch.
   *
   * <p>Used only when policy is INITIALIZATION_REQUIRED; otherwise null.
   */
  private final Bits.Builder topLevelInitialized;

  /**
   * Set if the corresponding destination variable has been set on the current switch branch.
   *
   * <p>Used only when policy is VARRAY_REPLACE, INITIALIZATION_REQUIRED, or
   * UNANIMOUS_PROMOTION_ONLY; otherwise null.
   */
  private final Bits.Builder initialized;

  /**
   * Set if the corresponding destination variable has been set on some branches of a switch and not
   * on others.
   *
   * <p>Used only when policy is VARRAY_REPLACE, INITIALIZATION_REQUIRED, or
   * UNANIMOUS_PROMOTION_ONLY; otherwise null.
   */
  private final Bits.Builder inconsistentInitialization;

  /**
   * Tracks whether a RefVar in the source template has been consistently assigned to a single
   * destination RefVar. It is only safe to promote a RefVar copy if it is always assigned to the
   * same RefVar in the destination and it is never read without copying it (since that would imply
   * that sometimes the source is non-null when the destination should be null). The value in the
   * map is either the index of the dst RefVar we can copy this src RefVar into, or -1 if this src
   * RefVar cannot be safely copied into any dst RefVar.
   *
   * <p>The read-without-copying steps that can cause us to put -1 in this map are
   *
   * <ul>
   *   <li>VERIFY_REF, VERIFY_REF_TYPE, or FRAME_TO_COMPOUND;
   *   <li>COMPOUND_TO_FRAME with the RefVar anywhere in the source template; or
   *   <li>a switch on the RefVar that has at least one non-FAIL branch corresponding to a singleton
   *       in the source Union.
   * </ul>
   *
   * <p>For example, the CopyPlan from an untagged union {@code x0⸨None, String⸩} to a tagged union
   * {@code b0⸨i1, None, x0:String⸩} is {@code x0⸨None:set(1, b0); String:set(2, b0),set(x0, x0)⸩}.
   * Although dst {@code x0} is only ever set to src {@code x0}, in cases where src {@code x0} is
   * None, dst {@code x0} should be null, so the copy cannot be promoted.
   *
   * <p>Since (unlike the other fields of CopyOptimizer) {@code srcRefVarDstIndices} is indexed by
   * src RefVars, we cannot rely on {@link #dstRefVarIndexBase} to shift them into a known range, so
   * we just rely on SmallIntMap's efficient handling of sparse maps.
   */
  private final SmallIntMap.Builder<Integer> srcRefVarDstIndices = new SmallIntMap.Builder<>();

  private CopyOptimizer(
      int dstNumVarIndexBase, int dstRefVarIndexBase, int numDstVars, Policy policy) {
    this.dstNumVarIndexBase = dstNumVarIndexBase;
    this.dstRefVarIndexBase = dstRefVarIndexBase;
    this.policy = policy;
    this.promotable = new Basic[numDstVars];
    this.topLevelInitialized =
        (policy == Policy.INITIALIZATION_REQUIRED) ? new Bits.Builder() : null;
    if (policy.checkInitialization) {
      this.initialized = new Bits.Builder();
      this.inconsistentInitialization = new Bits.Builder();
    } else {
      this.initialized = null;
      this.inconsistentInitialization = null;
    }
  }

  enum Policy {
    /**
     * A simple promotion policy: assignments are promotable if the same assignment appears in more
     * than one branch of a switch.
     */
    BASE(false),
    /** Assignments are promotable if there are no conflicting assignments. */
    NO_CONFLICTS(false),
    /**
     * Assignments are promotable if there are no conflicting assignments and either the variable is
     * assigned on every non-FAIL branch or the assignment is a copy of a NumVar with the same
     * encoding.
     */
    VARRAY_REPLACE(true),
    /**
     * Assignments are promotable if the same assignment appears in more than one branch of a
     * switch, or if there are switch branches that don't assign to that destination at all. The
     * resulting plan will assign a value to every destination variable (at least once) on every
     * non-FAIL branch; if it is unable to promote one of the assignments (which can happen for
     * RefVars, not NumVars) it will start the plan by assigning null to the RefVar.
     */
    INITIALIZATION_REQUIRED(true),
    /** Assignments are only promotable if the same value is assigned on every non-FAIL branch. */
    UNANIMOUS_PROMOTION_ONLY(true),
    /**
     * The indices of destination NumVars are assumed to be byte offsets, so e.g. an assignment to
     * i4 conflicts with an assignment to b5. Only promote assignments that don't conflict with any
     * other assignments.
     */
    NUM_VARS_ARE_BYTE_OFFSETS(false);

    final boolean checkInitialization;

    Policy(boolean checkInitialization) {
      this.checkInitialization = checkInitialization;
    }
  }

  /**
   * A convenience method for optimizing plans whose destination template is an {@link RValue}, i.e.
   * NumVar and RefVar indices refer to registers.
   *
   * @param registerStart the value of CodeBuilder.numRegisters() before the destination template
   *     was built
   * @param registerEnd the value of CodeBuilder.numRegisters() after the destination template was
   *     built
   * @param dst the destination template; ensures that all registers in this template will be
   *     initialized
   */
  static CopyPlan toRegisters(CopyPlan plan, int registerStart, int registerEnd, Template dst) {
    if (plan.isFail() || registerStart == registerEnd) {
      return plan;
    }
    CopyOptimizer optimizer =
        new CopyOptimizer(
            registerStart,
            registerStart,
            registerEnd - registerStart,
            Policy.INITIALIZATION_REQUIRED);
    for (Step step : plan.steps) {
      if (step instanceof Basic basic) {
        optimizer.addTopLevel(basic);
      } else {
        optimizer.analyze((Switch) step);
      }
    }
    return optimizer.finish(plan, dst);
  }

  /**
   * A convenience method for optimizing plans whose destination is the TState's function results.
   */
  static CopyPlan toFnResult(CopyPlan plan, int objSize, int byteSize) {
    return optimize(plan, 0, -byteSize, byteSize + objSize, Policy.NUM_VARS_ARE_BYTE_OFFSETS);
  }

  /**
   * A convenience method for optimizing plans whose destination is an instance of the given
   * RecordLayout.
   */
  static CopyPlan toRecord(CopyPlan plan, RecordLayout layout) {
    return optimize(plan, layout, Policy.NUM_VARS_ARE_BYTE_OFFSETS);
  }

  /**
   * A convenience method for optimizing plans whose destination is one element of an instance of
   * the given VArrayLayout.
   */
  static CopyPlan toVArray(CopyPlan plan, VArrayLayout layout) {
    return optimize(plan, layout, Policy.BASE);
  }

  /**
   * Assumes (like {@link #toRegisters}) that the destination template is an {@link RValue}, but
   * instead of copying a value the resulting plan will be used to compare the source value with the
   * destination value.
   *
   * @param registerStart the value of CodeBuilder.numRegisters() before the destination template
   *     was built
   * @param registerEnd the value of CodeBuilder.numRegisters() after the destination template was
   *     built
   */
  static CopyPlan equalsRegisters(CopyPlan plan, int registerStart, int registerEnd) {
    return optimize(
        plan,
        registerStart,
        registerStart,
        registerEnd - registerStart,
        Policy.UNANIMOUS_PROMOTION_ONLY);
  }

  /**
   * Returns a plan suitable for comparing the source value with a RecordLayout instance or one
   * element of a VArrayLayout instance.
   */
  static CopyPlan equalsLayout(CopyPlan plan, FrameLayout layout) {
    return optimize(plan, layout, Policy.UNANIMOUS_PROMOTION_ONLY);
  }

  static CopyPlan optimize(CopyPlan plan, FrameLayout dstLayout, Policy policy) {
    int numNv = dstLayout.numVarIndexLimit();
    return optimize(plan, 0, -numNv, dstLayout.refVarIndexLimit() + numNv, policy);
  }

  /**
   * Returns an equivalent plan, optimized according to the given policy.
   *
   * <p>Most of CopyOptimizer's state has an entry for each NumVar and RefVar in the destination
   * template. To make that efficient, we require that the caller provide a mapping from NumVar
   * indices and RefVar indices to small integers. The requirements are that
   *
   * <ul>
   *   <li>For all NumVars {@code nv} in the destination template, {@code
   *       nv.index-dstNumVarIndexBase} is in {@code [0 .. numDstVars-1]}.
   *   <li>For all RefVars {@code rv} in the destination template, {@code
   *       rv.index-dstRefVarIndexBase} is in {@code [0 .. numDstVars-1]}.
   *   <li>For any NumVar {@code nv} and RefVar {@code rv} in the destination template, {@code
   *       nv.index-dstNumVarIndexBase != rv.index-dstRefVarIndexBase}.
   * </ul>
   */
  static CopyPlan optimize(
      CopyPlan plan,
      int dstNumVarIndexBase,
      int dstRefVarIndexBase,
      int numDstVars,
      Policy policy) {
    // This method is not intended for use with INITIALIZATION_REQUIRED; toRegisters() has its own
    // version of this loop.
    assert policy != Policy.INITIALIZATION_REQUIRED;
    if (plan.isFail() || numDstVars == 0) {
      return plan;
    }
    // We only create a CopyOptimizer instance if we encounter a Switch
    CopyOptimizer optimizer = null;
    for (Step step : plan.steps) {
      if (step instanceof Switch sw) {
        if (optimizer == null) {
          optimizer = new CopyOptimizer(dstNumVarIndexBase, dstRefVarIndexBase, numDstVars, policy);
        }
        optimizer.analyze(sw);
      }
    }
    return (optimizer == null) ? plan : optimizer.finish(plan, null);
  }

  /**
   * Called after all of {@code plan} has been analyzed, to construct the optimized plan.
   *
   * <p>If {@link #policy} is INITIALIZATION_REQUIRED, the destination template must be supplied so
   * that the full set of destination registers can be determined.
   */
  private CopyPlan finish(CopyPlan plan, Template dst) {
    assert (policy != Policy.INITIALIZATION_REQUIRED) || (dst != null);
    ImmutableList.Builder<Step> newSteps = ImmutableList.builder();
    boolean promotedSome = false;
    // Start by adding any steps we're promoting to the start of the new plan
    for (int i = 0; i < promotable.length; i++) {
      Basic step = promotable[i];
      if (step != null) {
        if (isPromotable(step, i)) {
          newSteps.add(step);
          promotedSome = true;
          if (topLevelInitialized != null) {
            topLevelInitialized.set(i);
          }
        } else {
          promotable[i] = null;
          if (topLevelInitialized != null && !inconsistentInitialization.test(i)) {
            // Even if it's not assigned at the top level, if it's consistently initialized in every
            // branch we can skip the initialization.
            topLevelInitialized.set(i);
          }
        }
      }
    }
    boolean initializedSome = false;
    if (policy == Policy.INITIALIZATION_REQUIRED) {
      // Anything that still isn't set in topLevelInitialized needs initialization.
      int sizeBefore = topLevelInitialized.countGreaterThanOrEq(0);
      Template.visitVars(
          dst,
          new Template.VarVisitor() {
            @Override
            public void visitNumVar(NumVar v) {
              if (topLevelInitialized.set(index(v))) {
                newSteps.add(new Basic(StepType.SET_NUM, NumValue.ZERO, v));
              }
            }

            @Override
            public void visitRefVar(RefVar v) {
              if (topLevelInitialized.set(index(v))) {
                newSteps.add(new Basic(StepType.SET_REF, null, v));
              }
            }
          });
      // We just want to know if newSteps is non-empty, but ImmutableList.Builder won't
      // let us ask that so we check this instead.
      int sizeAfter = topLevelInitialized.countGreaterThanOrEq(0);
      initializedSome = (sizeAfter != sizeBefore);
    }
    if (promotedSome) {
      // Now remove any steps we promoted from the original plan, and append it
      UnaryOperator<Basic> dropPromoted = step -> (isPromoted(step) ? null : step);
      for (Step step : plan.steps) {
        if (step instanceof Basic) {
          newSteps.add(step);
        } else {
          Switch transformed = CopyPlan.transformSwitch((Switch) step, dropPromoted);
          if (transformed != null) {
            newSteps.add(transformed);
          }
        }
      }
    } else if (initializedSome) {
      // We didn't do any promotion but we added one or more initialization-to-zero steps, so
      // just copy over the original plan
      newSteps.addAll(plan.steps);
    } else {
      // We aren't making any changes to this plan after all
      return plan;
    }
    return new CopyPlan(newSteps.build());
  }

  /**
   * Returns true if the src and dst NumVars of the given step (which must be a COPY_NUM) have the
   * same encoding.
   */
  private static boolean sameEncoding(Basic step) {
    return ((NumVar) step.src).encoding == ((NumVar) step.dst).encoding;
  }

  /**
   * After completing the analysis pass, CopyOptimizer calls isPromotable once with each candidate
   * assignment to decide whether it should be promoted.
   */
  private boolean isPromotable(Basic step, int index) {
    if (step.type == StepType.COMPOUND_TO_FRAME || step.type == StepType.FRAME_TO_COMPOUND) {
      // These steps are never promotable (but we put them in `promotable` in case we're running
      // with INITIALIZATION_REQUIRED).
      return false;
    } else if (step.src instanceof RefVar rvSrc) {
      // COPY_REF is only promotable if the srcRefVarDstIndices check passes
      assert step.type == StepType.COPY_REF;
      int validDstIndex = srcRefVarDstIndices.get(rvSrc.index);
      if (validDstIndex < 0 || validDstIndex != ((RefVar) step.dst).index) {
        return false;
      }
    }
    if (policy == Policy.UNANIMOUS_PROMOTION_ONLY
        || (step.type == StepType.COPY_NUM && !sameEncoding(step))) {
      // Only promote if every non-fail branch set to the same rhs
      return !(conflicted.test(index)
          || (inconsistentInitialization != null && inconsistentInitialization.test(index)));
    } else if (policy == Policy.NO_CONFLICTS || step.dst instanceof RefVar) {
      // Promote if no conflicts
      return !conflicted.test(index);
    }
    // Policies for numvars are more varied
    return switch (policy) {
      // Promote if more than one branch set to the same rhs
      case BASE -> foundMultiple.test(index);
      // Promote if more than one branch set to the same rhs
      case VARRAY_REPLACE -> {
        if (conflicted.test(index)) {
          yield false;
        } else if (!inconsistentInitialization.test(index)) {
          yield true;
        } else {
          // See VArrayReplacer.isShareableCopy()
          yield step.type == StepType.COPY_NUM && sameEncoding(step);
        }
      }
      // Promote if more than one branch set to the same rhs *or* there were branches that didn't
      // set it at all.
      case INITIALIZATION_REQUIRED ->
          foundMultiple.test(index) || inconsistentInitialization.test(index);
      case NUM_VARS_ARE_BYTE_OFFSETS -> {
        // Promote if there were no conflicts, including to numvars that partially overlap this one
        int length = ((NumVar) step.dst).encoding.nBytes;
        if (IntStream.range(index + 1, index + length).allMatch(i -> promotable[i] == null)) {
          yield !conflicted.test(index);
        }
        // This step conflicts with one or more steps that cover a subset of its destination
        // bytes; mark them all as conflicted and don't promote this one.
        Op.UNION.rangeInto(conflicted, index + 1, index + length - 1);
        yield false;
      }
      default -> throw new AssertionError();
    };
  }

  /**
   * Returns the index in {@link #promotable} (and other CopyOptimizer state) to use for the given
   * NumVar from the destination template.
   */
  private int index(NumVar dst) {
    return dst.index - dstNumVarIndexBase;
  }

  /**
   * Returns the index in {@link #promotable} (and other CopyOptimizer state) to use for the given
   * RefVar from the destination template.
   */
  private int index(RefVar dst) {
    return dst.index - dstRefVarIndexBase;
  }

  /**
   * Returns the index in {@link #promotable} (and other CopyOptimizer state) to use for the
   * destination of the given step, or -1 if the destination is neither a NumVar nor a RefVar.
   */
  private int dstIndex(Basic step) {
    return switch (step.type) {
      case COPY_NUM, SET_NUM -> index((NumVar) step.dst);
      case COPY_REF, SET_REF, COMPOUND_TO_FRAME -> index((RefVar) step.dst);
      case VERIFY_NUM, VERIFY_REF, VERIFY_REF_TYPE, FRAME_TO_COMPOUND -> -1;
    };
  }

  /** Returns true if this is a step we've previously decided to promote. */
  private boolean isPromoted(Basic step) {
    int index = dstIndex(step);
    if (index < 0) {
      return false;
    }
    Basic fromPromotable = promotable[index];
    return (fromPromotable != null) && fromPromotable.equals(step);
  }

  /** Called with each step whose src is a RefVar, to update {@link #srcRefVarDstIndices}. */
  private void updateSrcRefVarDstIndices(Basic step) {
    int srcIndex = ((RefVar) step.src).index;
    if (step.dst instanceof RefVar rvDst) {
      srcRefVarDstIndices.put(srcIndex, rvDst.index, (x, y) -> x.equals(y) ? x : -1);
    } else {
      srcRefVarDstIndices.put(srcIndex, -1);
    }
  }

  /** Sets topLevelInitialized for any NumVar or RefVar in the destination of this step. */
  void addTopLevel(Basic step) {
    int index = dstIndex(step);
    if (index >= 0) {
      topLevelInitialized.set(index);
    } else if (step.type == StepType.FRAME_TO_COMPOUND) {
      Template.visitVars(
          (Compound) step.dst,
          new Template.VarVisitor() {
            @Override
            public void visitNumVar(NumVar v) {
              topLevelInitialized.set(index(v));
            }

            @Override
            public void visitRefVar(RefVar v) {
              topLevelInitialized.set(index(v));
            }
          });
    }
  }

  /**
   * Called once with each Basic step in the plan being optimized, to update the optimizer's state.
   */
  private void analyze(Basic step) {
    if (step.src instanceof RefVar) {
      updateSrcRefVarDstIndices(step);
    } else if (step.src instanceof Template.Compound cSrc) {
      invalidateAllSrcRefVars(cSrc);
    }
    int index = dstIndex(step);
    if (index < 0) {
      if (step.type == StepType.FRAME_TO_COMPOUND) {
        allVarsConflict((Compound) step.dst, step);
      }
      return;
    }
    if (initialized != null) {
      initialized.set(index);
    }
    Basic prev = promotable[index];
    if (prev == null) {
      promotable[index] = step;
    } else if (prev.equals(step)) {
      foundMultiple.set(index);
    } else {
      conflicted.set(index);
      if (policy == Policy.NUM_VARS_ARE_BYTE_OFFSETS
          && step.dst instanceof NumVar nvDst
          && nvDst.encoding.nBytes > ((NumVar) prev.dst).encoding.nBytes) {
        // We won't promote this, but we want to make sure that we also don't promote any other
        // steps that partially overlap its destination.
        promotable[index] = step;
      }
    }
  }

  /** Called once with each Switch in the plan being optimized. */
  private void analyze(Switch sw) {
    int nChoices = sw.union.numChoices();
    // If we're checking initialization we need to save the current initialization state (which
    // may be non-empty if this is a nested switch) and restore it after analyzing each branch
    // of the switch.
    Bits prevInitialized = (initialized == null) ? null : initialized.build();
    Bits firstInitialized = null;
    for (int i = 0; i < nChoices; i++) {
      CopyPlan choice = sw.choice(i);
      if (choice.isFail()) {
        continue;
      }
      if (sw.union.untagged != null && sw.union.choice(i) instanceof Constant) {
        // An untagged union with a (non-fail) Singleton branch means we can't copy from that
        // src RefVar
        srcRefVarDstIndices.put(sw.union.untagged.index, -1);
      }
      if (firstInitialized != null) {
        initialized.setAll(prevInitialized);
      }
      for (Step step : choice.steps) {
        if (step instanceof Basic basic) {
          analyze(basic);
        } else {
          analyze((Switch) step);
        }
      }
      if (initialized != null) {
        if (firstInitialized == null) {
          firstInitialized = initialized.build();
        } else {
          // Any variable initialized by this branch and not by the first one, or vice versa, should
          // be added to inconsistentInitialization.
          Op.SYMM_DIFF.into(initialized, firstInitialized);
          Op.UNION.into(inconsistentInitialization, initialized);
        }
      }
    }
    if (firstInitialized != null) {
      // Leave with the initialization state we got after the first branch (it doesn't matter
      // how any inconsistently initialized variables are set)
      initialized.setAll(firstInitialized);
    }
  }

  /**
   * Set {@link #conflicted} for each NumVar or RefVar in the given destination template; called
   * when we encounter a FRAME_TO_COMPOUND step.
   */
  private void allVarsConflict(Template t, Basic step) {
    assert step.type == StepType.FRAME_TO_COMPOUND;
    Template.visitVars(
        t,
        new Template.VarVisitor() {
          @Override
          public void visitNumVar(NumVar v) {
            // Putting a FRAME_TO_COMPOUND in promotable would probably confuse the code we
            // use to check for overlapping numVars, but we shouldn't ever encounter a
            // FRAME_TO_COMPOUND when using this policy.
            assert policy != Policy.NUM_VARS_ARE_BYTE_OFFSETS;
            visitVar(index(v));
          }

          @Override
          public void visitRefVar(RefVar v) {
            visitVar(index(v));
          }

          private void visitVar(int index) {
            promotable[index] = step;
            conflicted.set(index);
            if (initialized != null) {
              initialized.set(index);
            }
          }
        });
  }

  /**
   * Set {@link #srcRefVarDstIndices} to -1 for each RefVar in the given source template; called
   * when we encounter a COMPOUND_TO_FRAME step.
   */
  private void invalidateAllSrcRefVars(Template t) {
    Template.visitVars(t, refVar -> srcRefVarDstIndices.put(refVar.index, -1));
  }
}
