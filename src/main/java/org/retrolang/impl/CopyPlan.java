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
import com.google.errorprone.annotations.Keep;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.retrolang.impl.Template.Compound;
import org.retrolang.impl.Template.Constant;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.RefVar;
import org.retrolang.impl.Template.Union;
import org.retrolang.impl.Template.VarSink;
import org.retrolang.impl.Template.VarSource;

/**
 * A CopyPlan is used to optimize repeated calls of the form
 *
 * <pre>
 * dstTemplate.setValue(tstate, srcTemplate.peekValue(varSource), varSink);
 * </pre>
 *
 * with the same {@code srcTemplate} and {@code dstTemplate}. For example, to copy values between
 * vArrays with different layouts we could make such a call for each element (with the varSource and
 * varSink adjusted to identify the source index and destination index), but it would usually be
 * more efficient to use a CopyPlan:
 *
 * <pre>
 * CopyPlan plan = CopyPlan.create(srcTemplate, dstTemplate);
 * // for each (varSource, varSink):
 * plan.execute(tstate, varSource, varSink);
 * </pre>
 *
 * (like {@link Template#setValue}, {@link #execute} returns a boolean indicating whether the
 * destination template is able to represent the source value).
 *
 * <p>The CopyPlan is just a sequence of steps ("copy input var v1 to output var v2", "set output
 * var v to constant", etc.); in some situations it may be possible to examine the steps and
 * optimize them further rather than simply executing them.
 */
class CopyPlan {

  /** A CopyPlan that does nothing but return {@code false} when executed. */
  static final CopyPlan FAIL = new CopyPlan(null);

  /** A CopyPlan that does nothing but return {@code true} when executed. */
  static final CopyPlan EMPTY = new CopyPlan(ImmutableList.of());

  /** The steps that will be executed by this plan; null for {@link #FAIL}. */
  final ImmutableList<Step> steps;

  CopyPlan(ImmutableList<Step> steps) {
    this.steps = steps;
  }

  /**
   * Executes this plan with the given {@code src} and {@code dst}. If
   *
   * <pre>
   * dstTemplate.setValue(tstate, srcTemplate.peekValue(src), dst)
   * </pre>
   *
   * would return true (where {@code srcTemplate} and {@code dstTemplate} are the templates used to
   * construct this plan) then {@code execute()} will also return true and will make the same calls
   * to set values in {@code dst}. If that call would return false then {@code execute()} will also
   * return false (although it may set different values in {@code dst} before doing so).
   */
  boolean execute(TState tstate, VarSource src, VarSink dst) {
    if (isFail()) {
      return false;
    } else {
      return steps.stream().allMatch(step -> step.execute(tstate, src, dst));
    }
  }

  /** Returns true if this plan will always fail. */
  public boolean isFail() {
    return steps == null;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(steps);
  }

  @Override
  public boolean equals(Object obj) {
    return (obj instanceof CopyPlan cp) && Objects.equals(steps, cp.steps);
  }

  @Override
  public String toString() {
    if (isFail()) {
      return "FAIL";
    } else if (steps.isEmpty()) {
      return "EMPTY";
    } else {
      return steps.stream().map(Object::toString).collect(Collectors.joining(", "));
    }
  }

  abstract static class Step {
    abstract boolean execute(TState tstate, VarSource src, VarSink dst);
  }

  /**
   * All Steps except {@link Switch}es are instances of {@code Basic} (although two step types,
   * FRAME_TO_COMPOUND and COMPOUND_TO_FRAME, use the {@link FrameVsCompound} subclass).
   *
   * <p>There are nine different types of Basic steps (see {@link StepType}); while it would be
   * possible to define a separate subclass of Step for each of them, doing it this way required a
   * lot less boilerplate.
   */
  static class Basic extends Step {

    /** The type of this Step. */
    final StepType type;

    /**
     * For most step types this is a NumVar, RefVar, or Compound from the source template; for
     * SET_NUM and SET_REF it is a Value.
     */
    final Object src;

    /**
     * For most step types this is a NumVar, RefVar, or Compound from the destination template; for
     * VERIFY_NUM and VERIFY_REF it is a Value, and for VERIFY_REF_TYPE it is a BaseType.
     */
    final Object dst;

    Basic(StepType type, Object src, Object dst) {
      assert validArgs(type, src, dst);
      this.type = type;
      this.src = src;
      this.dst = dst;
    }

    @Override
    boolean execute(TState tstate, VarSource varSource, VarSink varSink) {
      return switch (type) {
        case COPY_NUM -> {
          NumVar srcVar = (NumVar) src;
          NumVar dstVar = (NumVar) dst;
          if (srcVar.encoding == NumEncoding.FLOAT64) {
            yield setD(varSource.getD(srcVar.index), dstVar, varSink);
          } else {
            yield setI(srcVar.toInt(varSource), dstVar, varSink);
          }
        }
        case COPY_REF -> {
          RefVar srcVar = (RefVar) src;
          Value v = varSource.getValue(srcVar.index);
          if (v != null) {
            RefCounted.addRef(v);
            RefVar dstVar = (RefVar) dst;
            varSink.setValue(dstVar.index, v);
          }
          yield true;
        }
        case SET_NUM -> {
          NumValue v = (NumValue) src;
          NumVar dstVar = (NumVar) dst;
          yield dstVar.setValue(null, v, varSink);
        }
        case SET_REF -> {
          Value v = (Value) src;
          assert !RefCounted.isRefCounted(v);
          RefVar dstVar = (RefVar) dst;
          varSink.setValue(dstVar.index, v);
          yield true;
        }
        case VERIFY_NUM -> {
          NumVar srcVar = (NumVar) src;
          if (srcVar.encoding == NumEncoding.FLOAT64) {
            yield NumValue.equals(dst, varSource.getD(srcVar.index));
          } else {
            yield NumValue.equals(dst, srcVar.toInt(varSource));
          }
        }
        case VERIFY_REF -> {
          RefVar srcVar = (RefVar) src;
          yield dst.equals(varSource.getValue(srcVar.index));
        }
        case VERIFY_REF_TYPE -> {
          RefVar srcVar = (RefVar) src;
          long order = ((BaseType) dst).sortOrder;
          Value v = varSource.getValue(srcVar.index);
          yield v != null && v.baseType().sortOrder == order;
        }
        default -> throw new AssertionError();
      };
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, toHashable(src), toHashable(dst));
    }

    /**
     * Many Values are not hashable, so if this is a Value (other than NumValue) we settle for
     * hashing its baseType (unless it's an array, in which case we hash VARRAY since frame
     * replacement can change the baseType of an array value).
     */
    private static Object toHashable(Object x) {
      if (x instanceof Value v && !(v instanceof NumValue)) {
        BaseType baseType = v.baseType();
        return baseType.isArray() ? Core.VARRAY : baseType;
      }
      return x;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else if (obj instanceof Basic b) {
        return (b.type == type) && equalsEnough(b.src, src) && equalsEnough(b.dst, dst);
      }
      return false;
    }

    /**
     * We don't depend on the type information of a RefVar, so don't test it (note that
     * RefVar.hashCode() doesn't depend on it either, so this is consistent with hashCode()).
     */
    private static boolean equalsEnough(Object x, Object y) {
      return (x instanceof RefVar rv) ? rv.equalsIgnoringType(y) : x.equals(y);
    }

    @Override
    public String toString() {
      return String.format("%s(%s, %s)", type.label, cleanRefVars(src), cleanRefVars(dst));
    }
  }

  /**
   * If {@code obj} is anything other than a RefVar, returns {@code obj.toString()}. For RefVars
   * just returns {@code "x"} followed by the RefVar index, without the type information that {@code
   * toString()} would append (step execution ignores the types of RefVars, and they clutter the
   * output).
   */
  private static String cleanRefVars(Object obj) {
    return (obj instanceof RefVar rv) ? "x" + rv.index : String.valueOf(obj);
  }

  /**
   * If {@code d} can be stored in {@code dstVar}, do so and return true; otherwise return false.
   */
  private static boolean setD(double d, NumVar dstVar, VarSink sink) {
    if (dstVar.encoding == NumEncoding.FLOAT64) {
      sink.setD(dstVar.index, d);
    } else {
      int i = (int) d;
      if (i != d) {
        return false;
      } else if (dstVar.encoding == NumEncoding.INT32) {
        sink.setI(dstVar.index, i);
      } else if (i != (i & 0xff)) {
        return false;
      } else {
        sink.setB(dstVar.index, i);
      }
    }
    return true;
  }

  /**
   * If {@code i} can be stored in {@code dstVar}, do so and return true; otherwise return false.
   */
  private static boolean setI(int i, NumVar dstVar, VarSink sink) {
    switch (dstVar.encoding) {
      case UINT8:
        if (i != (i & 0xff)) {
          return false;
        }
        sink.setB(dstVar.index, i);
        break;
      case INT32:
        sink.setI(dstVar.index, i);
        break;
      case FLOAT64:
        sink.setD(dstVar.index, i);
        break;
    }
    return true;
  }

  enum StepType {
    /** Copies the value from a NumVar in the source to a NumVar in the destination. */
    COPY_NUM("copy"),

    /** Copies the value from a RefVar in the source to a RefVar in the destination. */
    COPY_REF("copy"),

    /** Sets a NumVar in the destination to a constant value. */
    SET_NUM("set"),

    /** Sets a RefVar in the destination to a constant value. */
    SET_REF("set"),

    /** Fails unless a NumVar in the source has the specified value. */
    VERIFY_NUM("verify"),

    /** Fails unless a RefVar in the source has the specified value. */
    VERIFY_REF("verify"),

    /**
     * Fails unless a RefVar in the source has the specified BaseType (or a BaseType with the same
     * sortOrder).
     */
    VERIFY_REF_TYPE("verifyType"),

    /**
     * Copies the contents of a Frame from a RefVar in the source into a Compound from the
     * destination.
     *
     * <p>{@code FRAME_TO_COMPOUND} steps are always allocated using the FrameVsCompound subclass of
     * Basic.
     */
    FRAME_TO_COMPOUND("fromFrame"),

    /**
     * Sets a RefVar in the destination to a new Frame initialized from a Compound in the source.
     *
     * <p>{@code COMPOUND_TO_FRAME} steps are always allocated using the FrameVsCompound subclass of
     * Basic.
     */
    COMPOUND_TO_FRAME("toFrame");

    final String label;

    StepType(String label) {
      this.label = label;
    }
  }

  /**
   * Returns true if {@code src} and {@code dst} are valid arguments for a step of the given type.
   */
  private static boolean validArgs(StepType type, Object src, Object dst) {
    return switch (type) {
      case COPY_NUM -> src instanceof NumVar && dst instanceof NumVar;
      case COPY_REF ->
          src instanceof RefVar rvSrc
              && dst instanceof RefVar rvDst
              && rvSrc.sortOrder() == rvDst.sortOrder();
      case SET_NUM ->
          src instanceof NumValue nvSrc
              && dst instanceof NumVar nvDst
              && !RefCounted.isRefCounted(nvSrc)
              && nvDst.couldCast(nvSrc);
      // Any RefVar can be set to null.
      // We also allow any RefVar to be set to any Singleton, since SET_REF is used for setting
      // the RefVar of an untagged union.
      // Otherwise the src must be an uncounted Value of the appropriate type.
      case SET_REF ->
          dst instanceof RefVar rvDst
              && (src == null
                  || src instanceof Singleton
                  || (src instanceof Value vSrc
                      && !RefCounted.isRefCounted(vSrc)
                      && rvDst.couldCast(vSrc)));
      case VERIFY_NUM ->
          src instanceof NumVar nvSrc
              && dst instanceof NumValue nvDst
              && !RefCounted.isRefCounted(nvDst)
              && nvSrc.couldCast(nvDst);
      // As with SET_REF we must allow singletons to be compared with any RefVar type in order to
      // support untagged unions.
      case VERIFY_REF ->
          src instanceof RefVar rvSrc
              && (dst == null
                  || dst instanceof Singleton
                  || (dst instanceof Value vDst
                      && !RefCounted.isRefCounted(vDst)
                      && rvSrc.couldCast(vDst)));
      case VERIFY_REF_TYPE -> src instanceof RefVar && dst instanceof BaseType;
      case FRAME_TO_COMPOUND ->
          src instanceof RefVar rvSrc
              && dst instanceof Compound cDst
              && rvSrc.sortOrder() == cDst.baseType.sortOrder;
      case COMPOUND_TO_FRAME ->
          (src instanceof Compound || src instanceof Constant)
              && dst instanceof RefVar rvDst
              && ((Template) src).baseType().sortOrder == rvDst.sortOrder();
    };
  }

  /**
   * A subclass of Basic used for FRAME_TO_COMPOUND and COMPOUND_TO_FRAME steps. Adds a cache whose
   * contents are computed from the current layout of the RefVar.
   */
  static class FrameVsCompound extends Basic {
    @Keep private Cached cached;

    private static final VarHandle CACHED =
        Handle.forVar(MethodHandles.lookup(), FrameVsCompound.class, "cached", Cached.class);

    FrameVsCompound(StepType type, Object src, Object dst) {
      super(type, src, dst);
    }

    /**
     * Returns the current layout of this step's RefVar and a value computed from it. If this is the
     * first call to {@code getCached()} on this FrameVsCompound or the RefVar's layout has changed,
     * {@code producer.apply()} will be called to compute the result; otherwise the
     * previously-computed value will be returned.
     *
     * <p>Note that all callers to {@code getCached()} on a single FrameVsCompound should pass the
     * same producer; callers that need different results will need a separate copy of the plan.
     */
    Cached getCached(BiFunction<Template, FrameLayout, Object> producer) {
      // For FRAME_TO_COMPOUND, src is a RefVar; for COMPOUND_TO_FRAME, dst is a RefVar
      RefVar refVar = (RefVar) (src instanceof RefVar ? src : dst);
      FrameLayout layout = refVar.frameLayout();
      Cached cached = (Cached) CACHED.getAcquire(this);
      if (cached != null && cached.layout == layout) {
        return cached;
      }
      // For FRAME_TO_COMPOUND, dst is a Compound; for COMPOUND_TO_FRAME, src is a Compound or
      // Constant
      Template compound = (Template) (src instanceof RefVar ? dst : src);
      Cached newCached = null;
      // If other threads are evolving the layout and calling getCached() we might need to do this
      // more than once.
      for (; ; ) {
        if (newCached == null) {
          newCached = new Cached(layout, producer.apply(compound, layout));
        }
        Cached prevCached = cached;
        cached = (Cached) CACHED.compareAndExchangeRelease(this, cached, newCached);
        if (cached == prevCached) {
          // We were able to store newCached without a race, so we're done.
          return newCached;
        }
        FrameLayout prevLayout = layout;
        layout = layout.latest();
        if (cached.layout == layout) {
          // Someone else stored a new Cached, and it's up-to-date, so use it.
          return cached;
        } else if (layout != prevLayout) {
          // We raced against someone else's store, *and* in the meantime the layout has evolved so
          // that both their Cached and ours are out-of-date.  Just start over.
          newCached = null;
        } else {
          // We raced against someone else's store; their Cached is already out-of-date, but ours
          // is good; go back and just try the store again.
        }
      }
    }

    @Override
    boolean execute(TState tstate, VarSource varSource, VarSink varSink) {
      return switch (type) {
        case FRAME_TO_COMPOUND -> {
          Frame f = (Frame) varSource.getValue(((RefVar) src).index);
          if (f == null) {
            yield true;
          }
          Cached prev = null;
          // We may need to retry if we race against someone evolving the layout
          for (; ; ) {
            Cached cached = getCached(FrameToCompound::create);
            assert cached != prev;
            Boolean ok = ((FrameToCompound) cached.result).fromFrame(tstate, f, varSink);
            if (ok != null) {
              yield ok;
            }
            // We raced, try again
            prev = cached;
          }
        }
        case COMPOUND_TO_FRAME -> {
          Cached prev = null;
          // We may need to retry if we race against someone evolving the layout
          for (; ; ) {
            Cached cached = getCached(CompoundToFrame::create);
            assert cached != prev;
            Frame newFrame = ((CompoundToFrame) cached.result).newFrame(tstate, varSource);
            if (newFrame != null) {
              varSink.setValue(((RefVar) dst).index, newFrame);
              yield true;
            }
            // We raced, try again
            prev = cached;
          }
        }
        default -> throw new AssertionError();
      };
    }
  }

  /**
   * The most-recently-seen FrameLayout of a FrameVsCompound's RefVar, and the cached value that was
   * computed based on it.
   */
  static class Cached {
    final FrameLayout layout;
    final Object result;

    Cached(FrameLayout layout, Object result) {
      this.layout = layout;
      this.result = result;
    }
  }

  /**
   * A {@code Switch} step corresponds to a Union in the source template, and has a CopyPlan
   * corresponding to each of the Union's choices.
   */
  static class Switch extends Step {
    /** A Union from the source template; may be tagged or untagged. */
    final Union union;

    private final CopyPlan[] choices;

    Switch(Union union, CopyPlan[] choices) {
      assert choices.length == union.numChoices();
      this.union = union;
      this.choices = choices;
    }

    /**
     * Returns the CopyPlan that should be used when the source uses the specified choice of the
     * Union.
     */
    CopyPlan choice(int i) {
      return choices[i];
    }

    @Override
    boolean execute(TState tstate, VarSource src, VarSink dst) {
      int choiceIndex;
      // How we choose which branch to take depends on whether this is a tagged or untagged union
      NumVar tag = union.tag;
      if (tag != null) {
        choiceIndex = tag.toInt(src);
      } else {
        Value v = union.untagged.peekValue(src);
        choiceIndex = union.indexOf(v.baseType().sortOrder);
        if (choiceIndex < 0) {
          return false;
        }
      }
      return choices[choiceIndex].execute(tstate, src, dst);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(choices) * 31 + union.tagHash();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof Switch sw) {
        return sw.union.equals(union) && Arrays.equals(sw.choices, choices);
      }
      return false;
    }

    @Override
    public String toString() {
      StringBuilder result = new StringBuilder();
      result.append(union.tag != null ? union.tag : cleanRefVars(union.untagged));
      result.append("⸨");
      for (int i = 0; i < union.numChoices(); i++) {
        if (i != 0) {
          result.append("; ");
        }
        if (union.tag != null) {
          result.append(i);
        } else {
          BaseType baseType = union.choice(i).baseType();
          result.append(baseType.isArray() ? "Array" : baseType);
        }
        result.append(":").append(choices[i]);
      }
      return result.append("⸩").toString();
    }
  }

  /**
   * Returns a CopyPlan to transfer values described by {@code src} to values described by {@code
   * dst}.
   *
   * <p>If {@code skipToBeSet} is true, instances of ToBeSet in {@code src} will not be copied; the
   * destination may left unchanged or set to arbitrary values; this is only used when the
   * destination is a frame (where the layout templates exclude ToBeSet values).
   */
  static CopyPlan create(Template src, Template dst, boolean skipToBeSet) {
    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    if (!create(src, dst, skipToBeSet, builder)) {
      return FAIL;
    }
    ImmutableList<Step> steps = builder.build();
    return steps.isEmpty() ? EMPTY : new CopyPlan(steps);
  }

  /**
   * Determines the steps necessary to copy {@code src} to {@code dst}, appends them to {@code
   * steps}, and returns true. If such a copy would always fail, returns false ({@code steps} will
   * be ignored if this method returns false).
   */
  private static boolean create(
      Template src, Template dst, boolean skipToBeSet, ImmutableList.Builder<Step> steps) {
    // If skipToBeSet is true, dst should never be a union with a ToBeSet choice
    assert !(skipToBeSet && dst instanceof Union uDst && uDst.hasToBeSet());
    if (src == Template.EMPTY || (skipToBeSet && src == Core.TO_BE_SET.asTemplate)) {
      return true;
    } else if (dst == Template.EMPTY) {
      // An empty template has never had anything stored in it *except* possibly a ToBeSet
      if (skipToBeSet) {
        dst = Core.TO_BE_SET.asTemplate;
      } else {
        return false;
      }
    }
    if (src instanceof Union uSrc) {
      // Does uSrc have a ToBeSet option that we should drop?
      boolean dropToBeSet = skipToBeSet && uSrc.hasToBeSet();
      int numSrcChoices = uSrc.numChoices() - (dropToBeSet ? 1 : 0);
      if (numSrcChoices == 1) {
        // Since we can ignore the ToBeSet branch, this isn't really a union.
        src = uSrc.choice(0);
      } else if (dst instanceof Union uDst) {
        // If both unions are tagged and all of the src choices are constants and the
        // corresponding-length prefix of dst's choices are identical to src's, we can just copy the
        // tag (this is common with unions of singletons, such as Boolean).
        if (uSrc.tag != null
            && uDst.tag != null
            && hasMatchingConstants(uSrc, uDst, numSrcChoices)) {
          steps.add(new Basic(StepType.COPY_NUM, uSrc.tag, uDst.tag));
          return true;
        }
        // If both unions are untagged and dst has cases to match all of src's, we can just copy the
        // refvar.
        if (uSrc.tag == null && uDst.tag == null && hasAllOrders(uSrc, uDst, numSrcChoices)) {
          steps.add(new Basic(StepType.COPY_REF, uSrc.untagged, uDst.untagged));
          return true;
        }
        // We're gonna need a Switch
        return createSwitch(uSrc, uDst, skipToBeSet, steps);
      } else {
        // Union to non-union; at most one of the src choices can possibly succeed (other than
        // perhaps a ToBeSet).
        int i;
        if (dropToBeSet && dst == Core.TO_BE_SET.asTemplate) {
          // We're already discarding the ToBeSet choice.
          i = -1;
        } else {
          i = uSrc.indexOf(dst.baseType().sortOrder);
        }
        if (i >= 0 && dropToBeSet) {
          // Both look possible, so we probably need a Switch.
          CopyPlan plan = create(uSrc.choice(i), dst, skipToBeSet);
          if (plan.isFail()) {
            // Never mind
            i = -1;
          } else {
            CopyPlan[] plans = new CopyPlan[uSrc.numChoices()];
            Arrays.fill(plans, FAIL);
            plans[i] = plan;
            plans[plans.length - 1] = EMPTY;
            steps.add(new Switch(uSrc, plans));
            return true;
          }
        }
        if (i >= 0) {
          steps.add(requireChoice(uSrc, i));
          src = uSrc.choice(i);
        } else if (dropToBeSet) {
          steps.add(requireChoice(uSrc, uSrc.numChoices() - 1));
          return true;
        } else {
          return false;
        }
      }
    }
    if (dst instanceof Union uDst) {
      // Non-union to union
      int i = uDst.indexOf(src.baseType().sortOrder);
      if (i < 0) {
        // None of the destination branches match the source
        return false;
      }
      Template choice = uDst.choice(i);
      if (uDst.tag != null) {
        // Tagged union: set the tag value and continue
        steps.add(new Basic(StepType.SET_NUM, NumValue.of(i, Allocator.UNCOUNTED), uDst.tag));
      } else if (choice instanceof Constant c) {
        // Singleton case of an untagged union: set the union RefVar to the Singleton
        assert src == c;
        steps.add(new Basic(StepType.SET_REF, c.value, uDst.untagged));
        return true;
      } else {
        // Anything else into an untagged union: we just need to set the RefVar
        assert choice instanceof RefVar;
      }
      dst = choice;
    }
    // Now neither src nor dst is a Union
    if (src instanceof Constant cSrc) {
      Value v = cSrc.value;
      return switch (dst) {
        case Constant cDst -> cDst.value.equals(v);
        case NumVar nvDst -> {
          if (!nvDst.couldCast(v)) {
            // e.g. set(3.1, b0) would always fail
            yield false;
          }
          steps.add(new Basic(StepType.SET_NUM, v, nvDst));
          yield true;
        }
        case RefVar rvDst -> {
          if (!rvDst.couldCast(v)) {
            yield false;
          } else if (v instanceof CompoundValue) {
            steps.add(new FrameVsCompound(StepType.COMPOUND_TO_FRAME, cSrc, rvDst));
          } else {
            steps.add(new Basic(StepType.SET_REF, v, dst));
          }
          yield true;
        }
        default -> {
          // Copying Constant to Compound
          BaseType baseType = ((Compound) dst).baseType;
          yield (baseType == v.baseType())
              && copyElements(baseType.size(), src, dst, skipToBeSet, steps);
        }
      };
    } else if (dst instanceof Constant cDst) {
      Value v = cDst.value;
      if (src instanceof NumVar nvSrc) {
        if (!nvSrc.couldCast(v)) {
          return false;
        }
        steps.add(new Basic(StepType.VERIFY_NUM, nvSrc, v));
        return true;
      } else if (src instanceof RefVar rvSrc) {
        if (!rvSrc.couldCast(v)) {
          return false;
        }
        steps.add(new Basic(StepType.VERIFY_REF, rvSrc, v));
        return true;
      } else {
        // Copying Compound to Constant
        BaseType baseType = ((Compound) src).baseType;
        return (baseType == v.baseType())
            && copyElements(baseType.size(), src, dst, skipToBeSet, steps);
      }
    } else if (src instanceof NumVar) {
      if (!(dst instanceof NumVar)) {
        return false;
      }
      steps.add(new Basic(StepType.COPY_NUM, src, dst));
      return true;
    } else if (src instanceof RefVar rvSrc) {
      if (rvSrc.sortOrder() != dst.baseType().sortOrder) {
        return false;
      } else if (dst instanceof RefVar) {
        steps.add(new Basic(StepType.COPY_REF, rvSrc, dst));
      } else {
        assert dst instanceof Compound;
        steps.add(new FrameVsCompound(StepType.FRAME_TO_COMPOUND, rvSrc, dst));
      }
      return true;
    } else {
      BaseType baseType = ((Compound) src).baseType;
      if (dst instanceof Compound cDst) {
        return cDst.baseType == baseType
            && copyElements(baseType.size(), src, cDst, skipToBeSet, steps);
      } else if (dst instanceof RefVar rvDst && rvDst.sortOrder() == baseType.sortOrder) {
        steps.add(new FrameVsCompound(StepType.COMPOUND_TO_FRAME, src, rvDst));
        return true;
      }
      return false;
    }
  }

  /**
   * {@code src} and {@code dst} are each a Constant or Compound, and they have the same baseType
   * with the given size, so we can just copy element-by-element.
   */
  private static boolean copyElements(
      int size,
      Template src,
      Template dst,
      boolean skipToBeSet,
      ImmutableList.Builder<Step> steps) {
    return IntStream.range(0, size)
        .allMatch(i -> create(src.element(i), dst.element(i), skipToBeSet, steps));
  }

  /** Returns a step that verifies the specified branch of the source Union applies. */
  private static Step requireChoice(Union src, int choice) {
    if (src.tag != null) {
      return new Basic(StepType.VERIFY_NUM, src.tag, NumValue.of(choice, Allocator.UNCOUNTED));
    } else {
      BaseType baseType = src.choice(choice).baseType();
      return new Basic(StepType.VERIFY_REF_TYPE, src.untagged, baseType);
    }
  }

  /** Implements {@link #create} when both {@code src} and {@code dst} are Unions. */
  private static boolean createSwitch(
      Union src, Union dst, boolean skipToBeSet, ImmutableList.Builder<Step> steps) {
    CopyPlan[] choices = new CopyPlan[src.numChoices()];
    int numFail = 0;
    int lastNonFail = -1;
    // Even if we need a Switch we may still be able to copy the tag, but we won't know for sure
    // until we've checked all the branches.
    boolean canCopyTag = (src.tag != null) && (dst.tag != null);
    for (int i = 0; i < choices.length; i++) {
      CopyPlan plan = create(src.choice(i), dst, skipToBeSet);
      choices[i] = plan;
      if (plan.isFail()) {
        ++numFail;
      } else {
        lastNonFail = i;
        if (canCopyTag && !plan.steps.isEmpty()) {
          // We can only get here if dst is tagged; if it's tagged, and the create() didn't fail,
          // the first step of the plan will be to set the tag value.
          Basic setTag = (Basic) plan.steps.get(0);
          assert setTag.type == StepType.SET_NUM && setTag.dst == dst.tag;
          if (!NumValue.equals(setTag.src, i)) {
            canCopyTag = false;
          }
        }
      }
    }
    if (numFail == choices.length) {
      // All branches failed, so we don't need a Switch after all.
      return false;
    }
    if (canCopyTag) {
      // If we can just copy the source tag to the destination tag we prefer to do that over setting
      // it in each branch of the Switch.
      steps.add(new Basic(StepType.COPY_NUM, src.tag, dst.tag));
      // Drop the steps we previously generated to set the destination tag.
      for (int i = 0; i < choices.length; i++) {
        CopyPlan plan = choices[i];
        if (plan != FAIL && !plan.steps.isEmpty()) {
          choices[i] =
              (plan.steps.size() == 1)
                  ? EMPTY
                  : new CopyPlan(plan.steps.subList(1, plan.steps.size()));
        }
      }
    }
    if (numFail == choices.length - 1) {
      // If only one branch can succeed, we prefer to use a verify step rather than a Switch
      steps.add(requireChoice(src, lastNonFail));
      steps.addAll(choices[lastNonFail].steps);
    } else {
      steps.add(new Switch(src, choices));
    }
    return true;
  }

  /** True if all of src's choices are Constants and match the corresponding choices in dst. */
  private static boolean hasMatchingConstants(Union src, Union dst, int numSrcChoices) {
    if (numSrcChoices > dst.numChoices()) {
      return false;
    }
    for (int i = 0; i < numSrcChoices; i++) {
      Template choice = src.choice(i);
      if (!(choice instanceof Constant && choice.equals(dst.choice(i)))) {
        return false;
      }
    }
    return true;
  }

  /**
   * True if each of src's choices has a corresponding choice in dst (i.e. one with the same
   * sortOrder).
   */
  private static boolean hasAllOrders(Union src, Union dst, int numSrcChoices) {
    int numExtra = dst.numChoices() - numSrcChoices;
    if (numExtra < 0) {
      return false;
    }
    // The choice of dst that will be compared with the next choice of src.
    int nextDst = 0;
    // Since both union's choices are sorted this is O(sum of lengths).
    for (int i = 0; i < numSrcChoices; i++) {
      long srcOrder = src.choice(i).baseType().sortOrder;
      for (; ; ) {
        long dstOrder = dst.choice(nextDst++).baseType().sortOrder;
        if (dstOrder == srcOrder) {
          // Go on to the next src choice
          break;
        } else if (--numExtra < 0) {
          // There aren't enough dst choices left, so we can stop now.
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Creates a new CopyPlan by applying the given transformation to each Basic step of this
   * CopyPlan. If the transformation returns null, the step is dropped.
   */
  CopyPlan transformSteps(UnaryOperator<Basic> transformStep) {
    if (isFail() || steps.isEmpty()) {
      return this;
    }
    ImmutableList.Builder<Step> newSteps = null;
    // If newSteps is null, either all of the steps so far have been dropped (allDropped == true)
    // or all of them have been left unchanged (allDropped == false).
    boolean allDropped = true;
    for (int i = 0; i < steps.size(); i++) {
      Step step = steps.get(i);
      Step newStep =
          (step instanceof Basic basic)
              ? transformStep.apply(basic)
              : transformSwitch((Switch) step, transformStep);
      if (newSteps == null) {
        if (newStep == null) {
          if (allDropped) {
            continue;
          }
        } else if (newStep == step) {
          if (i == 0 || !allDropped) {
            allDropped = false;
            continue;
          }
        }
        newSteps = ImmutableList.builder();
        if (!allDropped) {
          newSteps.addAll(steps.subList(0, i));
        }
      }
      if (newStep != null) {
        newSteps.add(newStep);
      }
    }
    if (newSteps != null) {
      return new CopyPlan(newSteps.build());
    } else {
      return allDropped ? CopyPlan.EMPTY : this;
    }
  }

  static Switch transformSwitch(Switch sw, UnaryOperator<Basic> transformStep) {
    CopyPlan[] newChoices = null;
    boolean allEq = true;
    boolean allEmpty = true;
    int nChoices = sw.union.numChoices();
    for (int i = 0; i < nChoices; i++) {
      CopyPlan choice = sw.choice(i);
      CopyPlan newChoice = choice.transformSteps(transformStep);
      if (newChoices == null) {
        boolean wasAllEq = allEq;
        if (newChoice != choice) {
          allEq = false;
        }
        if (newChoice != EMPTY) {
          allEmpty = false;
        }
        if (allEq || allEmpty) {
          continue;
        }
        newChoices = Arrays.copyOf(sw.choices, nChoices);
        if (!wasAllEq) {
          Arrays.fill(newChoices, 0, i, EMPTY);
        }
      }
      newChoices[i] = newChoice;
    }
    if (newChoices != null) {
      return new Switch(sw.union, newChoices);
    } else {
      return allEmpty ? null : sw;
    }
  }

  /** Instances of CompoundToFrame are cached by {@link #execute} for COMPOUND_TO_FRAME steps. */
  private abstract static class CompoundToFrame {

    final Template compound;
    final FrameLayout layout;

    private CompoundToFrame(Template compound, FrameLayout layout) {
      this.compound = compound;
      this.layout = layout;
    }

    /** Initializes the newly-allocated frame {@code dst} from {@code src}. */
    abstract boolean initialize(TState tstate, VarSource src, Frame dst);

    /**
     * Allocates a new Frame (with a previously-determined layout) and initializes it from {@code
     * src}. If the current layout cannot store the source value, evolves the layout appropriately
     * and returns null.
     */
    @RC.Out
    Frame newFrame(TState tstate, VarSource src) {
      Frame result = layout.alloc(tstate, compound.baseType().size());
      if (initialize(tstate, src, result)) {
        return result;
      }
      // It's not possible to represent the source value with this layout.  Evolve the layout
      // so that it can represent the source value.
      tstate.dropReference(result);
      FrameLayout newLayout = layout.addValue(compound.peekValue(src));
      assert newLayout != layout;
      // Returning null will cause execute() to reload from the cache, which will see that the
      // layout has evolved and construct a new CompoundToFrame.
      return null;
    }

    /**
     * Returns a {@link CompoundToFrame} that allocates a new Frame with the given layout and
     * initializes it using the given template.
     */
    static CompoundToFrame create(Template compound, FrameLayout layout) {
      if (layout instanceof RecordLayout rLayout) {
        CopyPlan plan = CopyPlan.create(compound, rLayout.template, true);
        return new CompoundToFrame(compound, layout) {
          @Override
          boolean initialize(TState tstate, VarSource src, Frame dst) {
            return plan.execute(tstate, src, rLayout.asVarSink(dst));
          }
        };
      } else {
        // Just like the RecordLayout case, except that we create a separate plan to store each of
        // the compound's elements in the VArray.
        assert compound.baseType().isArray();
        VArrayLayout vArrayLayout = (VArrayLayout) layout;
        CopyPlan[] plans = new CopyPlan[compound.baseType().size()];
        Arrays.setAll(
            plans, i -> CopyPlan.create(compound.element(i), vArrayLayout.template, true));
        return new CompoundToFrame(compound, layout) {
          @Override
          boolean initialize(TState tstate, VarSource src, Frame dst) {
            return IntStream.range(0, plans.length)
                .allMatch(i -> plans[i].execute(tstate, src, vArrayLayout.asVarSink(dst, i)));
          }
        };
      }
    }
  }

  /** Instances of FrameToCompound are cached by {@link #execute} for FRAME_TO_COMPOUND steps. */
  private abstract static class FrameToCompound {

    final FrameLayout layout;

    private FrameToCompound(FrameLayout layout) {
      this.layout = layout;
    }

    /** Extracts the values of {@code src} to {@code dst}. */
    abstract boolean extract(TState tstate, Frame src, VarSink dst);

    /**
     * Sets variables in {@code dst} from {@code src}. Returns {@link Boolean#TRUE} if it succeeded,
     * {@link Boolean#FALSE} if the value of {@code src} cannot be represented by the destination
     * template, and {@code null} if {@code src} does not have the expected layout.
     */
    Boolean fromFrame(TState tstate, Frame src, VarSink dst) {
      if (src.layoutOrReplacement != layout) {
        // Like src = Frame.latest(src), but without the RC.In/Rc.Out
        Frame.Replacement replacement = Frame.getReplacement(src);
        if (replacement != null) {
          src = replacement.newFrame;
        }
        if (src.layoutOrReplacement != layout) {
          assert layout.latest() != layout;
          // Returning null will cause execute() to reload from the cache, which will see that the
          // layout has evolved and construct a new CompoundToFrame.
          return null;
        }
      }
      return extract(tstate, src, dst);
    }

    /**
     * Returns a {@link FrameToCompound} that sets the variables in {@code compound} from a Frame
     * with the given layout.
     */
    static FrameToCompound create(Template compound, FrameLayout layout) {
      if (layout instanceof RecordLayout rLayout) {
        CopyPlan plan = CopyPlan.create(rLayout.template, compound, false);
        return new FrameToCompound(layout) {
          @Override
          boolean extract(TState tstate, Frame src, VarSink dst) {
            return plan.execute(tstate, rLayout.asVarSource(src), dst);
          }
        };
      } else {
        VArrayLayout vArrayLayout = (VArrayLayout) layout;
        CopyPlan[] plans = new CopyPlan[compound.baseType().size()];
        Arrays.setAll(
            plans, i -> CopyPlan.create(vArrayLayout.template, compound.element(i), false));
        return new FrameToCompound(layout) {
          @Override
          boolean extract(TState tstate, Frame src, VarSink dst) {
            return IntStream.range(0, plans.length)
                .allMatch(i -> plans[i].execute(tstate, vArrayLayout.asVarSource(src, i), dst));
          }
        };
      }
    }
  }
}
