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

import static org.retrolang.impl.Value.addRef;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.retrolang.impl.TemplateBuilder.CompoundBase;
import org.retrolang.impl.TemplateBuilder.UnionBase;
import org.retrolang.util.Ordered;

/**
 * There are exactly six classes that implement Template, all defined in this file: Empty, Constant,
 * NumVar, RefVar, Compound, and Union. See templates.md for details.
 */
public sealed interface Template {
  /**
   * For a Template that can only represent a single BaseType (Constant, NumVar, RefVar, and
   * Compound) returns that BaseType. Should not be called on Empty or Union.
   *
   * <p>Note that a RefVar whose FrameLayout has evolved will return the BaseType of the original
   * layout, which may not match the current FrameLayout (it might be a FixedArrayType when the
   * layout has evolved to a VArrayLayout), but it will still have the same sortOrder.
   */
  BaseType baseType();

  /** Returns the value of this template, given values for its variables. */
  @RC.Out
  Value getValue(TState tstate, VarSource vars);

  /** Returns the value of this template, given values for its variables. */
  Value peekValue(VarSource vars);

  /**
   * Equivalent to calling {@link NumValue#asInt} or {@link NumValue#asIntOrMinusOne} on the result
   * of {@link #peekValue}.
   */
  default int asInt(VarSource vars, boolean orMinusOne) {
    Value v;
    if (this instanceof Constant) {
      // Might be an int
      v = peekValue(vars);
    } else {
      // This method is overridden by NumVar and Union, so this must be a RefVar or Compound,
      // neither of which could possibly be a number.
      assert !(peekValue(vars) instanceof NumValue);
      v = null;
    }
    return orMinusOne ? NumValue.asIntOrMinusOne(v) : NumValue.asInt(v);
  }

  /**
   * Sets the value of this template's variables so that it equals the given Value and returns true,
   * or returns false if this template cannot equal the given value. Should not be called on Empty.
   *
   * <p>Note that {@code setValue} may call some of the {@code set*} methods on {@code vars} before
   * returning false.
   */
  boolean setValue(TState tstate, Value v, VarSink vars);

  /**
   * If this is a Compound or a Constant with compositional base type, returns a Template for each
   * element; should not be called on any other type of Template.
   */
  default Template element(int i) {
    throw new AssertionError();
  }

  /**
   * All Template implementations are subclasses of TemplateBuilder, so this method can just cast.
   */
  default TemplateBuilder toBuilder() {
    return (TemplateBuilder) this;
  }

  /** We only use Constant templates for values that are not reference counted and not transient. */
  static boolean isConstantValue(Value v) {
    // If we're being asked to do anything with a ConditionalValue things have gone terribly wrong.
    assert !(v instanceof ConditionalValue);
    return !RefCounted.isRefCountedOrTransient(v);
  }

  /**
   * Returns true if the given Template contains no NumVars or RefVars, i.e. it is a Constant or
   * Empty.
   */
  static boolean isConstant(Template t) {
    return t == EMPTY || t instanceof Constant;
  }

  /**
   * Should only be called when {@link #isConstant} returns true. If the given Template is a
   * Constant, returns its value; if it is EMPTY, returns TO_BE_SET.
   */
  static Value toValue(Template t) {
    return t == EMPTY ? Core.TO_BE_SET : ((Constant) t).value;
  }

  /**
   * Calls {@code visitor} with each NumVar or RefVar in the given Template.
   *
   * <p>Note {@link VarVisitor#visitRefVar} will only be called once for an untagged union, so
   * should not depend on the BaseType of the RefVar it is passed.
   */
  static void visitVars(Template t, VarVisitor visitor) {
    if (isConstant(t)) {
      // Nothing to do
    } else if (t instanceof NumVar nv) {
      visitor.visitNumVar(nv);
    } else if (t instanceof RefVar rv) {
      visitor.visitRefVar(rv);
    } else if (t instanceof Compound compound) {
      int n = compound.baseType.size();
      for (int i = 0; i < n; i++) {
        visitVars(compound.element(i), visitor);
      }
    } else {
      Union union = (Union) t;
      if (union.untagged != null) {
        visitor.visitRefVar(union.untagged);
      } else {
        visitor.visitNumVar(union.tag);
        int n = union.numChoices();
        for (int i = 0; i < n; i++) {
          visitVars(union.choice(i), visitor);
        }
      }
    }
  }

  /**
   * A VarSource is used by {@link #getValue} or {@link #peekValue} to get the values of NumVars and
   * RefVars.
   */
  interface VarSource {

    /** Returns the value of a UINT8 NumVar with the given index. */
    int getB(int index);

    /** Returns the value of an INT32 NumVar with the given index. */
    int getI(int index);

    /** Returns the value of a FLOAT64 NumVar with the given index. */
    double getD(int index);

    /**
     * Returns the value of a RefVar with the given index.
     *
     * <p>May return null, which should be treated as {@link Core#TO_BE_SET}.
     */
    Value getValue(int index);
  }

  /** A VarSink is used by {@link #setValue} to set the values of NumVars and RefVars. */
  interface VarSink {

    /** Sets the value of a UINT8 NumVar with the given index. */
    void setB(int index, int value);

    /** Sets the value of an INT32 NumVar with the given index. */
    void setI(int index, int value);

    /** Sets the value of a FLOAT64 NumVar with the given index. */
    void setD(int index, double value);

    /** Sets the value of a RefVar with the given index. */
    void setValue(int index, @RC.In Value value);
  }

  /**
   * A VarVisitor will be called by {@link #visitVars} for each NumVar or RefVar in the given
   * Template.
   */
  interface VarVisitor {
    default void visitNumVar(NumVar v) {}

    void visitRefVar(RefVar v);
  }

  /**
   * A simple VarVisitor for finding the minimum and maximum indices of the NumVars and RefVars in a
   * given template.
   */
  static class IndexBounds implements VarVisitor {
    /**
     * The minimum index of any NumVar or RefVar in the given template, or Integer.MAX_VALUE if the
     * template has no NumVar or RefVar.
     */
    int minIndex = Integer.MAX_VALUE;

    /**
     * The maximum index of any NumVar or RefVar in the given template, or Integer.MIN_VALUE if the
     * template has no NumVar or RefVar.
     */
    int maxIndex = Integer.MIN_VALUE;

    /** Creates an IndexBounds with its minIndex and maxIndex initialized for the given template. */
    IndexBounds(Template t) {
      Template.visitVars(t, this);
    }

    private void update(int i) {
      minIndex = Math.min(minIndex, i);
      maxIndex = Math.max(maxIndex, i);
    }

    @Override
    public void visitNumVar(NumVar v) {
      update(v.index);
    }

    @Override
    public void visitRefVar(RefVar v) {
      update(v.index);
    }
  }

  /**
   * A Printer is used to modify the output of the {@code toString()} methods of Templates and
   * TemplateBuilders. It only controls the representations chosen for NumVars and RefVars.
   */
  class Printer {
    public static final Printer DEFAULT = new Printer();

    /**
     * Returns the toString() representation of the given NumVar. The default implementation just
     * calls {@link NumVar#toString()}.
     */
    public String toString(NumVar nv) {
      return nv.toString();
    }

    /**
     * Returns the toString() representation of the given RefVar. The default implementation just
     * calls {@link RefVar#toString()}.
     */
    public String toString(RefVar rv) {
      return rv.toString();
    }
  }

  /**
   * A Template that doesn't actually represent any values. Most useful as a TemplateBuilder.
   *
   * <p>Note that Constant.of(TO_BE_SET) returns EMPTY, so methods like {@link #getValue} and {@link
   * #peekValue} return TO_BE_SET.
   */
  static final class Empty extends TemplateBuilder implements Template {
    // Singleton class
    private Empty() {
      super((short) 0, (short) 0);
    }

    @Override
    @RC.Out
    public Value getValue(TState tstate, VarSource vars) {
      return Core.TO_BE_SET;
    }

    @Override
    public Value peekValue(VarSource vars) {
      return Core.TO_BE_SET;
    }

    @Override
    public boolean setValue(TState tstate, Value v, VarSink vars) {
      return false;
    }

    @Override
    public BaseType baseType() {
      return Core.TO_BE_SET.baseType();
    }

    @Override
    @RC.Out
    Value castImpl(TState tstate, Value v) {
      return null;
    }

    @Override
    boolean couldCastImpl(Value v, TestOption option) {
      return false;
    }

    @Override
    boolean isSubsetOfImpl(TemplateBuilder other, TestOption option) {
      return true;
    }

    @Override
    TemplateBuilder addImpl(Value v) {
      return newBuilder(v);
    }

    @Override
    TemplateBuilder mergeImpl(TemplateBuilder other) {
      return other;
    }

    @Override
    Template build(VarAllocator alloc) {
      return this;
    }

    @Override
    String toString(Printer printer) {
      return "(empty)";
    }
  }

  /** The empty Template. */
  static final Empty EMPTY = new Empty();

  /** A Constant template represents a single value. */
  static final class Constant extends TemplateBuilder implements Template {
    final Value value;

    private Constant(Value value) {
      super((short) 0, (short) 0);
      // Templates are not refcounted, so can't contain values that are.
      // We also explicitly disallow values with layouts, although in practice that's redundant
      // since they're always counted.
      assert value.layout() == null && isConstantValue(value);
      this.value = value;
    }

    /**
     * Returns a Constant with the given value, which must not be refcounted.
     *
     * <p>Note that {@code Constant.of(TO_BE_SET)} returns EMPTY.
     */
    public static Template of(Value value) {
      if (value instanceof Singleton v) {
        return v.asTemplate;
      } else if (value instanceof Frame) {
        // The only Frames that aren't refcounted are the empty VArrays.
        assert Core.EMPTY_ARRAY.equals(value);
        return Core.EMPTY_ARRAY.asTemplate;
      } else {
        return new Constant(value);
      }
    }

    /** This entry point is only intended for use by the Singleton constructor. */
    static Constant forSingletonConstructor(Singleton singleton) {
      return new Constant(singleton);
    }

    @Override
    @RC.Out
    public Value getValue(TState tstate, VarSource vars) {
      return value;
    }

    @Override
    public Value peekValue(VarSource vars) {
      return value;
    }

    @Override
    public boolean setValue(TState tstate, Value v, VarSink vars) {
      return value.equals(v);
    }

    @Override
    public BaseType baseType() {
      return value.baseType();
    }

    @Override
    public Template element(int i) {
      return of(value.peekElement(i));
    }

    @Override
    TemplateBuilder elementBuilder(int i) {
      return element(i).toBuilder();
    }

    @Override
    @RC.Out
    @Nullable Value castImpl(TState tstate, Value v) {
      return value.equals(v) ? v.makeStorable(tstate) : null;
    }

    @Override
    boolean couldCastImpl(Value v, TestOption option) {
      return value.equals(v);
    }

    @Override
    boolean isSubsetOfImpl(TemplateBuilder other, TestOption option) {
      // If we would have to evolve the other template to include our value, then we are not
      // currently a subset of it.
      return other.couldCast(value, option.withNoEvolution());
    }

    @Override
    TemplateBuilder addImpl(Value v) {
      if (isConstantValue(v) && value.equals(v)) {
        return this;
      }
      BaseType baseType = value.baseType();
      if (baseType == Core.NUMBER) {
        return NumVar.forNumber((NumValue) value).addImpl(v);
      } else if (!baseType.usesFrames()) {
        assert v.baseType() == baseType;
        return ((BaseType.NonCompositional) baseType).asRefVar;
      }
      FrameLayout vLayout = v.layout();
      if (vLayout != null) {
        return vLayout.asRefVar.addImpl(value);
      }
      if (baseType == v.baseType()) {
        assert baseType.size() >= 1;
        TemplateBuilder[] elements =
            IntStream.range(0, baseType.size())
                .mapToObj(i -> of(value.element(i)).toBuilder().add(v.peekElement(i)))
                .toArray(TemplateBuilder[]::new);
        return new CompoundBuilder(baseType, elements);
      }
      // We've got two fixed-length arrays of different lengths; make a new VArrayLayout that can
      // hold any of their elements
      return TemplateBuilder.newVarray(EMPTY.addElements(value).addElements(v));
    }

    @Override
    TemplateBuilder mergeImpl(TemplateBuilder other) {
      return other.addImpl(value);
    }

    @Override
    Template build(VarAllocator allocator) {
      return this;
    }

    @Override
    boolean coercesToFloat() {
      return value instanceof NumValue.D && !NumValue.isInt(value);
    }

    @Override
    TemplateBuilder builderForElement(BaseType compositionalType, int elementIndex) {
      assert compositionalType.isCompositional();
      if (value.baseType() == compositionalType) {
        return elementBuilder(elementIndex);
      } else if (value.baseType().sortOrder != compositionalType.sortOrder) {
        return EMPTY;
      } else {
        // If you return an array of a different length than this one, we'll end up creating a
        // vArray that can hold this array's elements.
        return Template.EMPTY.addElements(value);
      }
    }

    @Override
    @Nullable FrameLayout layout(long sortOrder) {
      // Currently this will always be null, since frames are always refcounted and hence can't be
      // Constant templates.
      FrameLayout result = value.layout();
      return (result != null && result.baseType().sortOrder == sortOrder) ? result : null;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else {
        return (obj instanceof Constant c) && value.equals(c.value);
      }
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public String toString(Printer printer) {
      return value.toString();
    }
  }

  /** The number of NumEncodings; useful for arrays indexed by encoding. */
  static final int NUM_ENCODINGS = NumEncoding.values().length;

  /** A NumVar represents a number provided as one of the template's inputs. */
  static final class NumVar extends TemplateBuilder implements Template {
    // When we need a new numeric TemplateBuilder we'll use one of these;
    // the proper index will be filled in when it's built.
    static final NumVar UINT8 = new NumVar(NumEncoding.UINT8, 0);
    static final NumVar INT32 = new NumVar(NumEncoding.INT32, 0);
    static final NumVar FLOAT64 = new NumVar(NumEncoding.FLOAT64, 0);

    public final NumEncoding encoding;
    public final int index;

    NumVar(NumEncoding encoding, int index) {
      super((short) 1, (short) 0);
      assert encoding != null && index >= 0;
      this.encoding = encoding;
      this.index = index;
    }

    /** Returns a NumVar with the most compact NumEncoding that can store the given number. */
    static NumVar forNumber(NumValue v) {
      if (NumValue.isUint8(v)) {
        return UINT8;
      } else if (NumValue.isInt(v)) {
        return INT32;
      } else {
        return FLOAT64;
      }
    }

    /** Returns a NumVar with the same encoding as this and the given index. */
    NumVar withIndex(int i) {
      return (index == i) ? this : new NumVar(encoding, i);
    }

    /** Returns the value of this NumVar as an int; should not be called if encoding is FLOAT64. */
    int toInt(VarSource vars) {
      if (encoding == NumEncoding.UINT8) {
        return vars.getB(index);
      } else {
        assert encoding == NumEncoding.INT32;
        return vars.getI(index);
      }
    }

    @Override
    @RC.Out
    public Value getValue(TState tstate, VarSource vars) {
      if (encoding == NumEncoding.FLOAT64) {
        return NumValue.of(vars.getD(index), tstate);
      } else {
        return NumValue.of(toInt(vars), tstate);
      }
    }

    @Override
    public Value peekValue(VarSource vars) {
      if (encoding == NumEncoding.FLOAT64) {
        return NumValue.of(vars.getD(index), Allocator.TRANSIENT);
      } else {
        return NumValue.of(toInt(vars), Allocator.TRANSIENT);
      }
    }

    @Override
    public int asInt(VarSource vars, boolean orMinusOne) {
      if (encoding != NumEncoding.FLOAT64) {
        return toInt(vars);
      } else {
        double d = vars.getD(index);
        int i = (int) d;
        if (d == i) {
          return i;
        }
        assert orMinusOne;
        return -1;
      }
    }

    /** Sets the value of this NumVar; should not be called if encoding is FLOAT64. */
    void setToInt(int i, VarSink vars) {
      if (encoding == NumEncoding.UINT8) {
        vars.setB(index, i);
      } else {
        assert encoding == NumEncoding.INT32;
        vars.setI(index, i);
      }
    }

    @Override
    public boolean setValue(TState tstate, Value v, VarSink vars) {
      switch (encoding) {
        case FLOAT64:
          if (v instanceof NumValue) {
            vars.setD(index, NumValue.asDouble(v));
            return true;
          }
          break;
        case INT32:
          if (NumValue.isInt(v)) {
            vars.setI(index, NumValue.asInt(v));
            return true;
          }
          break;
        case UINT8:
          if (NumValue.isUint8(v)) {
            vars.setB(index, NumValue.asInt(v));
            return true;
          }
          break;
      }
      return false;
    }

    @Override
    public BaseType baseType() {
      return Core.NUMBER;
    }

    @Override
    @RC.Out
    @Nullable Value castImpl(TState tstate, Value v) {
      return couldCast(v) ? v.makeStorable(tstate) : null;
    }

    @Override
    boolean couldCastImpl(Value v, TestOption option) {
      if (!(v instanceof NumValue)) {
        return false;
      } else if (encoding == NumEncoding.FLOAT64) {
        return true;
      } else if (encoding == NumEncoding.INT32 || option.upgradeSubInts) {
        return NumValue.isInt(v);
      } else {
        return NumValue.isUint8(v);
      }
    }

    @Override
    boolean isSubsetOfImpl(TemplateBuilder other, TestOption option) {
      if (!(other instanceof NumVar nv)) {
        return false;
      } else if (!option.upgradeSubInts) {
        return nv.encoding.compareTo(encoding) >= 0;
      } else {
        // All ints are equivalent for this test, so the only way it fails is if this is a double
        // and other is some kind of int.
        return !(encoding == NumEncoding.FLOAT64 && nv.encoding != NumEncoding.FLOAT64);
      }
    }

    @Override
    TemplateBuilder addImpl(Value v) {
      return mergeImpl(forNumber((NumValue) v));
    }

    @Override
    TemplateBuilder mergeImpl(TemplateBuilder other) {
      return ((NumVar) other).encoding.compareTo(encoding) > 0 ? other : this;
    }

    @Override
    NumVar build(VarAllocator allocator) {
      return allocator.allocNumVar(this);
    }

    @Override
    boolean coercesToFloat() {
      return encoding == NumEncoding.FLOAT64;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else if (obj instanceof NumVar nv) {
        return nv.encoding == encoding && nv.index == index;
      }
      return false;
    }

    @SuppressWarnings("EnumOrdinal")
    @Override
    public int hashCode() {
      // The offset is arbitrary.
      return 1685084401 + index * NUM_ENCODINGS + encoding.ordinal();
    }

    @Override
    public String toString(Printer printer) {
      return printer.toString(this);
    }

    @Override
    public String toString() {
      return encoding.prefix + index;
    }
  }

  /**
   * A RefVar represents a Value with a specified layout or non-compositional BaseType, provided as
   * one of the template's inputs.
   */
  static final class RefVar extends TemplateBuilder implements Template {

    /**
     * If true and this RefVar has a FrameLayout, {@link #frameLayout()} will change if the layout
     * evolves; otherwise {@link #frameLayout()} will always return the original layout.
     *
     * <p>We use RefVars with {@code tracksLayoutEvolution == false} in RValues, where we want a
     * consistent view of the RValue's layout.
     */
    final boolean tracksLayoutEvolution;

    final int index;
    final BaseType baseType;
    private FrameLayout frameLayout;

    static final VarHandle FRAME_LAYOUT =
        Handle.forVar(MethodHandles.lookup(), RefVar.class, "frameLayout", FrameLayout.class);

    RefVar(int index, BaseType baseType, FrameLayout frameLayout, boolean tracksLayoutEvolution) {
      super((short) 0, (short) 1);
      assert !baseType.isSingleton();
      assert (frameLayout != null) == baseType.usesFrames();
      assert !(frameLayout == null && tracksLayoutEvolution);
      this.index = index;
      this.tracksLayoutEvolution = tracksLayoutEvolution;
      this.baseType = baseType;
      this.frameLayout = frameLayout;
    }

    /**
     * The BaseType of a RefVar that uses frames may change (from a FixedArrayType to VARRAY), but
     * its sortOrder won't.
     */
    long sortOrder() {
      return baseType.sortOrder;
    }

    @Override
    @RC.Out
    public Value getValue(TState tstate, VarSource vars) {
      return addRef(peekValue(vars));
    }

    @Override
    public Value peekValue(VarSource vars) {
      Value v = vars.getValue(index);
      return (v == null) ? Core.TO_BE_SET : v;
    }

    @Override
    public boolean setValue(TState tstate, Value v, VarSink vars) {
      v = castImpl(tstate, v);
      if (v == null) {
        return false;
      } else {
        vars.setValue(index, v);
        return true;
      }
    }

    @Override
    public BaseType baseType() {
      // Note that if frameLayout has evolved this may be out-of-date, but it will still have the
      // same sortOrder which is good enough; see comment on TemplateBuilder.baseType()
      return baseType;
    }

    @Override
    @RC.Out
    @Nullable Value castImpl(TState tstate, Value v) {
      if (v.baseType().sortOrder != sortOrder()) {
        return null;
      } else if (frameLayout == null) {
        return v.makeStorable(tstate);
      }
      FrameLayout vLayout = v.layout();
      if (vLayout == frameLayout) {
        return v.makeStorable(tstate);
      }
      return frameLayout().cast(tstate, v);
    }

    @Override
    boolean couldCastImpl(Value v, TestOption option) {
      if (v.baseType().sortOrder != sortOrder()) {
        return false;
      } else if (frameLayout == null || option.evolutionAllowed) {
        // As long as the sortOrder matches, cast() will expand the FrameLayout to contain v
        return true;
      }
      FrameLayout layout = frameLayout();
      FrameLayout vLayout = v.layout();
      if (vLayout != null) {
        // If we made it to here then option.evolutionAllowed is false, so the layouts are required
        // to be identical.
        return vLayout == layout;
      }
      int n = v.baseType().size();
      Template template = layout.template();
      for (int i = 0; i < n; i++) {
        Template element = (layout instanceof VArrayLayout) ? template : template.element(i);
        if (!element.toBuilder().couldCast(v.element(i), TestOption.NO_EVOLUTION)) {
          return false;
        }
      }
      return true;
    }

    @Override
    boolean isSubsetOfImpl(TemplateBuilder other, TestOption option) {
      if (other instanceof RefVar rv) {
        if (frameLayout == null) {
          return rv.baseType == baseType;
        } else if (rv.frameLayout != null) {
          // Even if one of the refvars isn't tracking evolution, we want to compare the most recent
          // versions of the layouts.
          return latestLayout() == rv.latestLayout();
        }
      }
      return false;
    }

    @Override
    boolean overlapsImpl(TemplateBuilder t2) {
      return t2.baseType().sortOrder == sortOrder();
    }

    /**
     * Returns the latest version of this RefVar's layout, whether or not we are tracking layout
     * evolution.
     */
    private FrameLayout latestLayout() {
      return tracksLayoutEvolution ? frameLayout() : frameLayout.latest();
    }

    public FrameLayout frameLayout() {
      if (!tracksLayoutEvolution) {
        assert frameLayout != null;
        return frameLayout;
      }
      // Use getAcquire() because another thread may asynchronously update the frameLayout field.
      FrameLayout layout = (FrameLayout) FRAME_LAYOUT.getAcquire(this);
      return updateFrameLayout(layout, layout.latest());
    }

    /**
     * If {@code latest} is different from {@code current} (the value we read from our {@link
     * #frameLayout} field), update the field.
     */
    @CanIgnoreReturnValue
    private FrameLayout updateFrameLayout(FrameLayout current, FrameLayout latest) {
      assert tracksLayoutEvolution;
      if (latest != current) {
        assert current.scope.evolver.evolvedTo(current, latest);
        // We don't bother checking the return value because if it isn't what we expect that would
        // just mean that there was a race and someone else updated it before us, which is fine.
        FRAME_LAYOUT.compareAndExchangeRelease(this, current, latest);
      }
      return latest;
    }

    @Override
    TemplateBuilder addImpl(Value v) {
      if (frameLayout == null) {
        assert v.baseType() == baseType;
        return this;
      }
      assert tracksLayoutEvolution;
      FrameLayout layout = frameLayout();
      updateFrameLayout(layout, layout.addValue(v));
      return this;
    }

    @Override
    TemplateBuilder mergeImpl(TemplateBuilder other) {
      if (other == this) {
        return this;
      } else if (frameLayout == null) {
        assert other instanceof RefVar && other.baseType() == baseType;
        return this;
      }
      assert tracksLayoutEvolution;
      FrameLayout layout = frameLayout();
      FrameLayout newLayout;
      if (other instanceof RefVar rv) {
        FrameLayout otherLayout = rv.frameLayout();
        if (otherLayout == layout) {
          return this;
        }
        newLayout = layout.scope.evolver.merge(layout, otherLayout);
      } else {
        newLayout = layout.merge((CompoundBase) other);
      }
      updateFrameLayout(layout, newLayout);
      return this;
    }

    /** Returns a RefVar with the same layout as this and the given index. */
    RefVar withIndex(int newIndex) {
      return withIndex(newIndex, tracksLayoutEvolution);
    }

    /** Returns a RefVar with the same layout as this and the given index. */
    RefVar withIndex(int newIndex, boolean newTracksLayoutEvolution) {
      if (frameLayout == null) {
        return (index == newIndex) ? this : new RefVar(newIndex, baseType, null, false);
      }
      // Get the current version of frameLayout and the corresponding baseType
      FrameLayout newLayout = latestLayout();
      BaseType newBaseType = newLayout.baseType();
      if (index == newIndex
          && this.baseType == newBaseType
          && this.tracksLayoutEvolution == newTracksLayoutEvolution) {
        if (newTracksLayoutEvolution || this.frameLayout == newLayout) {
          return this;
        }
      }
      return new RefVar(newIndex, newBaseType, newLayout, newTracksLayoutEvolution);
    }

    @Override
    Template build(VarAllocator allocator) {
      return withIndex(allocator.allocRefVar(), allocator.refVarsTrackLayoutEvolution());
    }

    @Override
    TemplateBuilder builderForElement(BaseType compositionalType, int elementIndex) {
      FrameLayout frameLayout = this.frameLayout;
      if (frameLayout == null) {
        return EMPTY;
      } else if (frameLayout instanceof RecordLayout layout) {
        Template.Compound record = layout.template;
        if (record.baseType == baseType) {
          return record.elementBuilder(elementIndex);
        } else if (record.baseType.sortOrder != BaseType.SORT_ORDER_ARRAY) {
          return EMPTY;
        }
      }
      if (compositionalType.sortOrder != BaseType.SORT_ORDER_ARRAY) {
        return EMPTY;
      } else if (frameLayout instanceof VArrayLayout layout) {
        return layout.template.toBuilder();
      } else {
        // If you return an array of a different length than this one, we'll end up creating a
        // vArray that can hold this array's elements.
        return ((RecordLayout) frameLayout).template.mergeElementsInto(EMPTY);
      }
    }

    @Override
    @Nullable FrameLayout layout(long sortOrder) {
      return frameLayout != null && frameLayout.baseType().sortOrder == sortOrder
          ? frameLayout()
          : null;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else if (obj instanceof RefVar rv) {
        if (rv.index != index) {
          return false;
        } else if (frameLayout == null) {
          return rv.baseType == baseType;
        } else {
          return rv.frameLayout != null
              && tracksLayoutEvolution == rv.tracksLayoutEvolution
              && frameLayout() == rv.frameLayout();
        }
      }
      return false;
    }

    /** Returns true if obj is a RefVar with the same index as this one. */
    boolean equalsIgnoringType(Object obj) {
      return obj instanceof RefVar o && o.index == index;
    }

    @Override
    public int hashCode() {
      // We don't want to include the layout, since it might evolve and we don't want the
      // hashCode to change.  The offset is arbitrary, but chosen to avoid collisions with
      // NumVars.
      return 1685084400 - index;
    }

    @Override
    public String toString(Printer printer) {
      return printer.toString(this);
    }

    @Override
    public String toString() {
      String info =
          (frameLayout == null) ? baseType.toString() : frameLayout().toStringNoTemplate();
      return String.format("x%d:%s", index, info);
    }
  }

  /**
   * A Compound template represents values with a specified compositional BaseType; each element of
   * the value is represented by a template.
   */
  static final class Compound extends CompoundBase implements Template {
    private final Template[] elements;

    private Compound(BaseType baseType, Template... elements) {
      super(baseType);
      this.elements = elements;
      computeWeightPair();
    }

    /**
     * If {@link #isConstant} is true for all elements, returns a constant template for the
     * compound; otherwise returns a compound template.
     */
    public static Template of(BaseType baseType, Template... elements) {
      assert elements.length == baseType.size();
      if (Arrays.stream(elements).allMatch(Template::isConstant)) {
        return new Constant(
            CompoundValue.of(Allocator.UNCOUNTED, baseType, i -> toValue(elements[i])));
      } else {
        return new Compound(baseType, elements);
      }
    }

    @Override
    @RC.Out
    public Value getValue(TState tstate, VarSource vars) {
      return CompoundValue.of(tstate, baseType, i -> elements[i].getValue(tstate, vars));
    }

    @Override
    public Value peekValue(VarSource vars) {
      return new Value.NotStorable() {
        @Override
        public BaseType baseType() {
          return baseType;
        }

        @Override
        @RC.Out
        public Value element(int index) {
          return elements[index].getValue(TState.get(), vars);
        }

        @Override
        public Value peekElement(int index) {
          return elements[index].peekValue(vars);
        }

        @Override
        public int elementAsInt(int index) {
          return elements[index].asInt(vars, false);
        }

        @Override
        public int elementAsIntOrMinusOne(int index) {
          return elements[index].asInt(vars, true);
        }

        @Override
        public Value makeStorable(TState tstate) {
          return getValue(tstate, vars);
        }

        @Override
        public boolean equals(Object other) {
          return other == this || (other instanceof Value v && baseType.equalValues(this, v));
        }

        @Override
        public int hashCode() {
          throw new AssertionError(VALUES_ARENT_HASHABLE);
        }

        @Override
        public String toString() {
          return baseType.compositionalToString(this);
        }
      };
    }

    @Override
    public boolean setValue(TState tstate, Value v, VarSink vars) {
      if (v.baseType() != baseType) {
        return false;
      }
      for (int i = 0; i < baseType.size(); i++) {
        Value vi = v.peekElement(i);
        if (vi != Core.TO_BE_SET && !elements[i].setValue(tstate, vi, vars)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public Template element(int i) {
      return elements[i];
    }

    @Override
    TemplateBuilder elementBuilder(int i) {
      return elements[i].toBuilder();
    }

    @Override
    Template build(VarAllocator allocator) {
      // If we're lucky, each of our elements will be unchanged by building and we can return
      // ourself as the result; otherwise we'll have to allocate a new Template[] and a new
      // Compound.
      Template[] newElements = null;
      for (int i = 0; i < elements.length; i++) {
        Template t = elements[i];
        Template t2 = t.toBuilder().build(allocator);
        if (t2 != t) {
          if (newElements == null) {
            newElements = Arrays.copyOf(elements, elements.length);
          }
          newElements[i] = t2;
        }
      }
      return (newElements == null) ? this : new Compound(baseType, newElements);
    }

    @Override
    CompoundBase withElements(IntFunction<TemplateBuilder> updatedElements) {
      TemplateBuilder[] newElements = null;
      for (int i = 0; i < elements.length; i++) {
        TemplateBuilder updated = updatedElements.apply(i);
        if (updated != elements[i]) {
          if (newElements == null) {
            newElements = Arrays.copyOf(elements, elements.length, TemplateBuilder[].class);
          }
          newElements[i] = updated;
        }
      }
      return (newElements == null) ? this : new CompoundBuilder(baseType, newElements);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else if (obj instanceof Compound c) {
        return c.baseType == baseType && Arrays.equals(c.elements, elements);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(elements) * 31 + baseType.hashCode();
    }
  }

  /**
   * A Union template represents all the values that can be represented by two or more other
   * templates.
   *
   * <p>A tagged union can have Constants, NumVars, RefVars, and Compounds as choices; an additional
   * NumVar (the tag) is used to determine which choice is active.
   *
   * <p>An untagged union can only have RefVars and Singleton Constants as choices; there must be at
   * least one RefVar, and all the RefVars must have the same index. The type of the input is used
   * to determine which of the choices is active.
   */
  static final class Union extends UnionBase implements Template {
    // Exactly one of tag or untagged will be non-null.
    // If untagged is non-null all non-singleton choices are RefVars with the same index.
    final NumVar tag;
    final RefVar untagged;
    private final Template[] choices;

    Union(NumVar tag, RefVar untagged, Template... choices) {
      assert (tag == null) != (untagged == null);
      if (untagged == null) {
        // tag must have an appropriate encoding
        assert tag.encoding == NumEncoding.INT32
            || (choices.length <= 256 && tag.encoding == NumEncoding.UINT8);
      } else {
        // Each choice must be a singleton or a RefVar with the same index as untagged
        assert Arrays.stream(choices)
            .allMatch(
                choice ->
                    choice.toBuilder().isSingleton() || ((RefVar) choice).index == untagged.index);
        // At least one choice must be a RefVar
        assert Arrays.stream(choices).anyMatch(choice -> choice instanceof RefVar);
      }
      this.tag = tag;
      this.untagged = untagged;
      this.choices = choices;
      assert isValidChoiceList();
      if (tag != null) {
        computeWeightPair();
      }
    }

    /** Returns the Template that represents this Union's value. */
    private Template chosen(VarSource vars) {
      if (untagged != null) {
        return untagged;
      } else {
        int index = tag.toInt(vars);
        // If index is out of range there's a bug somewhere, and an ArrayIndexOutOfBoundsException
        // seems as good a way to signal that as anything else.
        return choices[index];
      }
    }

    @Override
    @RC.Out
    public Value getValue(TState tstate, VarSource vars) {
      return chosen(vars).getValue(tstate, vars);
    }

    @Override
    public Value peekValue(VarSource vars) {
      return chosen(vars).peekValue(vars);
    }

    @Override
    public int asInt(VarSource vars, boolean orMinusOne) {
      return chosen(vars).asInt(vars, orMinusOne);
    }

    @Override
    public boolean setValue(TState tstate, Value v, VarSink vars) {
      int i = indexOf(v.baseType().sortOrder);
      if (i < 0) {
        return false;
      }
      Template choice = choices[i];
      boolean success = choice.setValue(tstate, v, vars);
      if (success) {
        if (tag != null) {
          tag.setToInt(i, vars);
        } else if (!(choice instanceof RefVar)) {
          assert choice instanceof Template.Constant && v instanceof Singleton;
          vars.setValue(untagged.index, v);
        }
      }
      return success;
    }

    @Override
    int numChoices() {
      return choices.length;
    }

    Template choice(int i) {
      return choices[i];
    }

    @Override
    TemplateBuilder choiceBuilder(int i) {
      return choices[i].toBuilder();
    }

    @Override
    UnionBuilder withNewChoice(int index, TemplateBuilder choice) {
      // Ensure that there's a little extra room for additional choices.
      List<TemplateBuilder> choiceList = new ArrayList<>(choices.length + 2);
      for (Template c : choices) {
        choiceList.add(c.toBuilder());
      }
      Ordered.setOrAdd(choiceList, index, choice);
      return new UnionBuilder(choiceList);
    }

    @Override
    Template build(VarAllocator allocator) {
      NumVar newTag = (tag == null) ? null : tag.build(allocator);
      // If we're lucky, each of our choices will be unchanged by building and we can reuse
      // our choices array.
      Template[] newChoices = null;
      RefVar newUntagged = null;
      // It's not unusual for all our choices to be constant (e.g. when they're singletons), in
      // which case we can skip all the mucking around with duplicated VarAllocators.
      if (!Arrays.stream(choices).allMatch(c -> c instanceof Constant)) {
        // We need to reset the VarAllocator before building each choice,
        // and union the resulting VarAllocator states.
        VarAllocator initial = allocator.duplicate();
        VarAllocator scratch = allocator.duplicate();
        for (int i = 0; i < choices.length; i++) {
          Template c = choices[i];
          if (!(c instanceof Constant)) {
            scratch.resetTo(initial);
            Template c2 = c.toBuilder().build(scratch);
            if (c2 != c) {
              if (newChoices == null) {
                newChoices = Arrays.copyOf(choices, choices.length);
              }
              newChoices[i] = c2;
            }
            if (newTag == null && newUntagged == null) {
              newUntagged = (RefVar) c2;
            }
            // allocator should be left as the union of the allocators after each choice
            allocator.union(scratch);
          }
        }
      }
      if (newTag == tag && newUntagged == untagged && newChoices == null) {
        return this;
      } else {
        return new Union(newTag, newUntagged, newChoices == null ? choices : newChoices);
      }
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else if (obj instanceof Union u) {
        return Objects.equals(u.tag, tag)
            && Objects.equals(u.untagged, untagged)
            && Arrays.equals(u.choices, choices);
      }
      return false;
    }

    /** Returns the hashCode of the NumVar or RefVaf this Union switches on. */
    int tagHash() {
      return (tag != null) ? tag.hashCode() : untagged.hashCode();
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(choices) * 31 + tagHash();
    }

    @Override
    String toStringPrefix(Template.Printer printer) {
      return (tag != null) ? printer.toString(tag) : "";
    }

    @Override
    boolean toStringNumbersChoices() {
      return tag != null;
    }
  }
}
