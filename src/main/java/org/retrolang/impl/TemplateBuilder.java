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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.retrolang.impl.Template.Compound;
import org.retrolang.impl.Template.Constant;
import org.retrolang.impl.Template.Empty;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.RefVar;
import org.retrolang.impl.Template.Union;
import org.retrolang.util.Ordered;

/**
 * A TemplateBuilder is used to build Templates. The basic flow is <nl>
 * <li>Start with Template.EMPTY or an existing Template cast to TemplateBuilder.
 * <li>One or more times: {@code builder = builder.add(value)} or {@code builder =
 *     builder.merge(builder2)} (note that unlike many builder classes, you must save the returned
 *     TemplateBuilder after each change, which may or may not be the one on which {@link #add} or
 *     {@link #merge} was called).
 * <li>Call {@link #build} with an appropriate VarAllocator. </nl>
 */
public abstract class TemplateBuilder {

  /**
   * The number of NumVars that would be allocated to build this TemplateBuilder (capped at
   * VAR_COUNT_LIMIT, although in practice we don't expect to ever get such complex templates).
   */
  private short nNumVars;

  /**
   * The number of RefVars that would be allocated to build this TemplateBuilder (capped at
   * VAR_COUNT_LIMIT, although in practice we don't expect to ever get such complex templates).
   */
  private short nRefVars;

  /**
   * The limit on {@link #nNumVars} and {@link #nRefVars}. This choice is arbitrary, but ensures
   * that we don't come near overflowing the shorts they're currently stored in.
   */
  static final int VAR_COUNT_LIMIT = 255;

  TemplateBuilder(short nNumVars, short nRefVars) {
    assert nNumVars >= 0 && nNumVars <= VAR_COUNT_LIMIT;
    assert nRefVars >= 0 && nRefVars <= VAR_COUNT_LIMIT;
    this.nNumVars = nNumVars;
    this.nRefVars = nRefVars;
  }

  /**
   * Returns the (estimated) total number of variables that would be allocated to build this
   * template.
   */
  final int totalWeight() {
    return nNumVars + (int) nRefVars;
  }

  /**
   * Tries to reduce the total weight of this TemplateBuilder by choosing a CompoundBuilder that has
   * totalWeight >= targetWeight and replacing it with a new FrameLayout (i.e. by boxing some part
   * of this template into a separate Frame).
   *
   * <p>Should only be called when {@code totalWeight() >= targetWeight}.
   */
  TemplateBuilder insertFrameLayout(int targetWeight) {
    // This method only does something interesting on CompoundBuilders and UnionBuilders; I don't
    // think it will ever be called on anything else, but if it is just declining to do anything
    // should be safe.
    return this;
  }

  /**
   * Returns a TemplateBuilder that includes all values included by this TemplateBuilder as well as
   * the given value. The result may or may not be identical to this.
   */
  final TemplateBuilder add(Value v) {
    if (this == Template.EMPTY
        || this instanceof UnionBase
        || v.baseType().sortOrder == baseType().sortOrder) {
      return addImpl(v);
    } else {
      return new UnionBuilder(this, newBuilder(v));
    }
  }

  /** Adds each element of the given array value to this TemplateBuilder. */
  final TemplateBuilder addElements(Value v) {
    return addElements(v, 0, v.numElements());
  }

  /** Adds the specified elements of the given value to this TemplateBuilder. */
  final TemplateBuilder addElements(Value v, int start, int end) {
    TemplateBuilder result = this;
    for (int i = start; i < end; i++) {
      result = result.add(v.peekElement(i));
    }
    return result;
  }

  /**
   * Returns a TemplateBuilder that includes all values included by this TemplateBuilder or the
   * given TemplateBuilder. The result may or may not be identical to this.
   */
  final TemplateBuilder merge(TemplateBuilder other) {
    if (this == Template.EMPTY) {
      return other;
    } else if (other == Template.EMPTY) {
      return this;
    } else if (this instanceof UnionBase unionThis) {
      if (other instanceof UnionBase unionOther) {
        return unionThis.mergeAll(unionOther);
      }
      return mergeImpl(other);
    } else if (other instanceof UnionBase) {
      return other.mergeImpl(this);
    } else if (baseType().sortOrder != other.baseType().sortOrder) {
      return new UnionBuilder(this, other);
    } else {
      return mergeOrAdd(other);
    }
  }

  /** Calls {@link #addImpl} if {@code other} is Constant, {@link #mergeImpl} otherwise. */
  final TemplateBuilder mergeOrAdd(TemplateBuilder other) {
    return (other instanceof Constant c) ? addImpl(c.value) : mergeImpl(other);
  }

  /**
   * Returns a Value equal to v and suitable for storing in storage described by this
   * TemplateBuilder (e.g. this is a RefVar with a FrameLayout, the result will be a Frame with that
   * layout). If the given value cannot be cast to this TemplateBuilder, returns null.
   *
   * <p>The given value may be have been returned by an {@link RC.Transient} method; this method
   * will call {@link Value#makeStorable} before returning it.
   */
  @RC.Out
  final @Nullable Value cast(TState tstate, Value v) {
    return castImpl(tstate, v);
  }

  /**
   * An option that determines some details of the behavior of {@link #couldCast} or {@link
   * #isSubsetOf}.
   */
  enum TestOption {
    /**
     * The default behavior for {@link #couldCast}; will return true even if some FrameLayout
     * evolution would be required to make the cast possible.
     *
     * <p>Not valid for calls to {@link #isSubsetOf}.
     */
    EVOLUTION_OK,

    /**
     * Will return true only if the relationship (cast or subset) holds without additional evolution
     * of FrameLayouts. There is no special treatment of ToBeSet or NumVars.
     */
    NO_EVOLUTION,

    /**
     * Will return true only if the relationship (cast or subset) holds without additional evolution
     * of FrameLayouts. ToBeSet values can be ignored. There is no special treatment of NumVars.
     */
    DROP_TO_BE_SET,

    /**
     * NumVars with encodings smaller than {@link NumEncoding#INT32} are treated as if they were
     * {@link NumEncoding#INT32}. Implies {@link #NO_EVOLUTION}.
     */
    UPGRADE_SUB_INTS;

    boolean evolutionAllowed() {
      return this == EVOLUTION_OK;
    }

    boolean dropToBeSetFromUnions() {
      return this == DROP_TO_BE_SET;
    }

    boolean upgradeSubInts() {
      return this == UPGRADE_SUB_INTS;
    }

    /** If this is {@link #EVOLUTION_OK}, returns {@link #NO_EVOLUTION}; otherwise returns this. */
    TestOption withNoEvolution() {
      return this == EVOLUTION_OK ? NO_EVOLUTION : this;
    }
  }

  /** Returns true if {@link #cast} would return a non-null Value. */
  final boolean couldCast(Value v) {
    return couldCast(v, TestOption.EVOLUTION_OK);
  }

  /** Returns true if {@link #cast} would return a non-null Value, subject to the given option. */
  final boolean couldCast(Value v, TestOption option) {
    return (v == Core.TO_BE_SET && option.dropToBeSetFromUnions()) || couldCastImpl(v, option);
  }

  /**
   * Returns true if every Value that can be stored in this TemplateBuilder can also be stored in
   * {@code other}.
   */
  final boolean isSubsetOf(TemplateBuilder other) {
    return isSubsetOf(other, TestOption.NO_EVOLUTION);
  }

  /**
   * Returns true if every Value that can be stored in this TemplateBuilder can also be stored in
   * {@code other}, subject to the given option.
   */
  final boolean isSubsetOf(TemplateBuilder other, TestOption option) {
    assert !option.evolutionAllowed();
    if (this == Template.EMPTY
        || this == other
        || (this == Core.TO_BE_SET.asTemplate && option.dropToBeSetFromUnions())) {
      return true;
    } else if (other == Template.EMPTY) {
      return false;
    }
    TemplateBuilder t = this;
    if (this instanceof UnionBase union) {
      if (option.dropToBeSetFromUnions()
          && union.numChoices() == 2
          && union.choiceBuilder(1) == Core.TO_BE_SET.asTemplate) {
        t = union.choiceBuilder(0);
      } else if (!(other instanceof UnionBase)) {
        return false;
      }
    }
    if (other instanceof UnionBase union && !(t instanceof UnionBase)) {
      int i = union.indexOf(t.baseType().sortOrder);
      if (i < 0) {
        return false;
      }
      other = union.choiceBuilder(i);
    }
    return t.isSubsetOfImpl(other, option);
  }

  /**
   * Used by {@link MethodMemo#isSimilarEnough} to check for similarities between the args of two
   * MethodMemos.
   *
   * <p>This is not a very principled predicate (and may end up getting further tweaked to get
   * better inlining decisions). The current intuition is roughly "does this TemplateBuilder include
   * values for which {@code t2.couldCast()} returns true, or vice versa"? ... except that any
   * numeric constant or variable is considered to overlap with any other numeric constant or
   * variable, and any string constant is considered to overlap with any other string constant.
   *
   * <p>This also has potentially surprising behavior with Template.EMPTY; because that is what's
   * returned by newBuilder(TO_BE_SET), and TO_BE_SET can be cast to anything, I've chosen to make
   * EMPTY overlap with anything (when you might reasonably have expected it to overlap nothing).
   */
  final boolean overlaps(TemplateBuilder t2) {
    if (this == t2 || this == Template.EMPTY || t2 == Template.EMPTY) {
      return true;
    }
    // If either is a union or refvar let their overlapsImpl() handle it (preferring the union, if
    // there is one).
    if (t2 instanceof UnionBase) {
      return t2.overlapsImpl(this);
    } else if (this instanceof UnionBase || this instanceof RefVar) {
      return overlapsImpl(t2);
    } else if (t2 instanceof RefVar) {
      return t2.overlapsImpl(this);
    }
    // At this point this and t2 must each be a constant, numvar, or compound.
    BaseType b1 = baseType();
    BaseType b2 = t2.baseType();
    if (b1 != b2) {
      return false;
    } else if (!b1.isCompositional()) {
      // e.g. two Numbers or two Strings
      return true;
    } else {
      return IntStream.range(0, b1.size())
          .allMatch(i -> elementBuilder(i).overlaps(t2.elementBuilder(i)));
    }
  }

  /**
   * Returns a Template that includes all values included by this TemplateBuilder, using the given
   * VarAllocator to assign indices to NumVars and RefVars.
   *
   * <p>If this is a Template and the VarAllocator assigns the same indices to all variables,
   * returns this.
   */
  abstract Template build(VarAllocator alloc);

  /**
   * For a TemplateBuilder that can only represent a single BaseType (Constant, NumVar, RefVar, and
   * CompoundBase) returns that BaseType. Should not be called on Empty or UnionBase.
   *
   * <p>Note that a RefVar whose FrameLayout has evolved will return the BaseType of the original
   * layout, which may not match the current FrameLayout (it might be a FixedArrayType when the
   * layout has evolved to a VArrayLayout), but it will still have the same sortOrder and that's all
   * that our callers depend on.
   *
   * <p>TODO: maybe replace with a sortOrder() method to avoid that jankiness?
   */
  abstract BaseType baseType();

  /** Returns true if this is a Constant template for a Singleton. */
  final boolean isSingleton() {
    return this instanceof Constant c && c.value instanceof Singleton;
  }

  /** Implements the {@link #cast} method. */
  @RC.Out
  abstract @Nullable Value castImpl(TState tstate, Value v);

  /** Implements the {@link #couldCast} method. */
  abstract boolean couldCastImpl(Value v, TestOption option);

  /**
   * Implements the {@link #isSubsetOf} method. Neither {@code this} nor {@code other} is empty. If
   * {@code this} is not a union, {@code other} is not a union.
   */
  abstract boolean isSubsetOfImpl(TemplateBuilder other, TestOption option);

  /**
   * Implements the {@link #overlaps} method; only defined for unions and refVars (other types are
   * handled directly by {@link #overlaps}).
   */
  boolean overlapsImpl(TemplateBuilder t2) {
    throw new AssertionError();
  }

  /**
   * Returns a TemplateBuilder that includes all values included by this TemplateBuilder as well as
   * the given value.
   *
   * <p>Unless this is a union or empty, the given value's base type must have the same sort order
   * as baseType(). Use {@link #add} instead to avoid that restriction.
   */
  abstract TemplateBuilder addImpl(Value v);

  /**
   * Returns a TemplateBuilder that includes any values included by this TemplateBuilder or the
   * given TemplateBuilder.
   *
   * <p>The given template may not a union or empty, and unless this is a union or empty, the given
   * template may not be Constant and must have a base type with the same sort order as baseType().
   * Use {@link #merge} instead to avoid those restrictions.
   */
  abstract TemplateBuilder mergeImpl(TemplateBuilder other);

  /**
   * Returns true if this is a FLOAT64 NumVar, a floating-point constant, or a union with either of
   * those as a choice.
   */
  boolean coercesToFloat() {
    assert this instanceof Empty || this instanceof RefVar || this instanceof CompoundBase;
    return false;
  }

  /**
   * If this TemplateBuilder includes values with the given compositional BaseType, returns a
   * TemplateBuilder that describes the specified element of those values; otherwise returns EMPTY.
   */
  TemplateBuilder builderForElement(BaseType compositionalType, int elementIndex) {
    assert this instanceof Empty
        || this instanceof NumVar
        || (this instanceof RefVar && !(this instanceof RefVar.ForFrame));
    return Template.EMPTY;
  }

  /**
   * Returns the FrameLayout to be used for values with the given sortOrder, or null if none has
   * been chosen.
   */
  @Nullable FrameLayout layout(long sortOrder) {
    assert this instanceof Empty
        || this instanceof NumVar
        || this instanceof CompoundBase
        || (this instanceof RefVar && !(this instanceof RefVar.ForFrame));
    return null;
  }

  /**
   * Should only be called on a CompoundBase or Constant when {@link #baseType} returns a BaseType
   * with size greater than {@code i}; returns a TemplateBuilder for the specified element.
   */
  TemplateBuilder elementBuilder(int i) {
    throw new AssertionError();
  }

  /**
   * Returns a String representation of this TemplateBuilder, using the given Printer to render any
   * NumVars or RefVars.
   */
  abstract String toString(Template.Printer printer);

  @Override
  public String toString() {
    return toString(Template.Printer.DEFAULT);
  }

  /**
   * A VarAllocator is used by {@link #build} to assign indices to the NumVars and RefVars in the
   * result Template.
   */
  interface VarAllocator {

    /**
     * Returns a NumVar with the same encoding as {@code forEncoding} and the next available index.
     */
    NumVar allocNumVar(NumVar forEncoding);

    /** Returns the next index for a RefVar. */
    int allocRefVar();

    /**
     * Returns true if new RefVars with a FrameLayout should update when the layout evolves. The
     * default implementation returns true.
     */
    default boolean refVarsTrackLayoutEvolution() {
      return true;
    }

    /** Returns true if Unions in the resulting template should drop any ToBeSet choice. */
    default boolean dropToBeSetFromUnions() {
      return false;
    }

    /**
     * Returns a new VarAllocator that will assign the same indices as this; used when building
     * unions.
     */
    VarAllocator duplicate();

    /**
     * Resets this VarAllocator's state so that it will assign the same indices as {@code other};
     * used when building unions. Will only be called on an allocator that was returned by {@link
     * #duplicate}.
     */
    void resetTo(VarAllocator other);

    /**
     * Advances (if necessary) the state of this so that it will avoid assigning any indices that
     * would conflict with those assigned by {@code other}; used when building unions. Will only be
     * called with an allocator that was returned by {@link #duplicate}.
     */
    void union(VarAllocator other);
  }

  /** Returns a new TemplateBuilder that includes the given value. */
  static TemplateBuilder newBuilder(Value v) {
    if (Template.isConstantValue(v)) {
      return Constant.of(v).toBuilder();
    }
    BaseType baseType = v.baseType();
    if (baseType == Core.NUMBER) {
      return NumVar.forNumber((NumValue) v);
    } else if (baseType instanceof BaseType.NonCompositional ncb && baseType != Core.VARRAY) {
      return ncb.asRefVar.addImpl(v);
    }
    FrameLayout layout = v.layout();
    if (layout != null) {
      return layout.asRefVar;
    }
    // We took care of non-compositional base types above, and singletons aren't refCounted,
    // so if we get here we must have a base type with size >= 1.
    assert baseType.size() >= 1;
    TemplateBuilder[] elements =
        IntStream.range(0, baseType.size())
            .mapToObj(i -> newBuilder(v.peekElement(i)))
            .toArray(TemplateBuilder[]::new);
    return new CompoundBuilder(baseType, elements);
  }

  /** Creates a new VArrayLayout with the given element layout, and returns a RefVar for it. */
  static Template.RefVar newVarray(TemplateBuilder element) {
    return VArrayLayout.newFromBuilder(TState.get().scope(), element).asRefVar;
  }

  /** A shared base class for (immutable) Compound and (mutable) CompoundBuilder. */
  abstract static class CompoundBase extends TemplateBuilder {
    final BaseType baseType;

    CompoundBase(BaseType baseType) {
      // Subclass constructors are responsible for calling computeWeightPair() after initializing
      super((short) 0, (short) 0);
      assert baseType.size() > 0;
      this.baseType = baseType;
    }

    /**
     * Returns a CompoundBase with the same BaseType and each element's TemplateBuilder provided by
     * the given function. If this is a CompoundBuilder, just modifies it in place.
     */
    abstract CompoundBase withElements(IntFunction<TemplateBuilder> updatedElements);

    /**
     * Returns a CompoundBase with the same BaseType and elements as this, except that {@code v} has
     * been added to the specified element.
     */
    CompoundBase addToElement(int index, Value v) {
      return withElements(
          i -> {
            TemplateBuilder e = elementBuilder(i);
            return (i == index) ? e.add(v) : e;
          });
    }

    @Override
    public BaseType baseType() {
      return baseType;
    }

    @Override
    @RC.Out
    @Nullable Value castImpl(TState tstate, Value v) {
      if (v.baseType() != baseType || v.layout() != null) {
        return null;
      } else {
        return ((CompoundValue) v).castImpl(tstate, this);
      }
    }

    @Override
    boolean couldCastImpl(Value v, TestOption option) {
      return v.baseType() == baseType
          && v.layout() == null
          && IntStream.range(0, baseType.size())
              .allMatch(i -> elementBuilder(i).couldCast(v.peekElement(i), option));
    }

    @Override
    boolean isSubsetOfImpl(TemplateBuilder other, TestOption option) {
      TemplateBuilder recursiveTemplate;
      TestOption recursiveOption;
      if (other instanceof RefVar.ForFrame rv) {
        FrameLayout layout = rv.frameLayout();
        recursiveTemplate = layout.template().toBuilder();
        // We're going to compare against the layout's template, not the original template,
        // so discard ToBeSet and revert to the default treatment of NumVars.
        recursiveOption = TestOption.DROP_TO_BE_SET;
        if (layout instanceof VArrayLayout) {
          // If other is a varray ref, each element of this compound must be a subset of the
          // element template.
          return baseType.isArray()
              && IntStream.range(0, baseType.size())
                  .allMatch(i -> elementBuilder(i).isSubsetOf(recursiveTemplate, recursiveOption));
        }
      } else {
        recursiveTemplate = other;
        recursiveOption = option;
      }
      assert recursiveTemplate instanceof CompoundBase
          || recursiveTemplate instanceof Constant
          || recursiveTemplate instanceof NumVar;
      return recursiveTemplate.baseType() == baseType
          && IntStream.range(0, baseType.size())
              .allMatch(
                  i ->
                      elementBuilder(i)
                          .isSubsetOf(recursiveTemplate.elementBuilder(i), recursiveOption));
    }

    @Override
    TemplateBuilder addImpl(Value v) {
      FrameLayout layout = v.layout();
      if (layout != null) {
        return layout.asRefVar.mergeImpl(this);
      }
      BaseType vBaseType = v.baseType();
      if (vBaseType == baseType) {
        return withElements(i -> elementBuilder(i).add(v.peekElement(i)));
      }
      return newVarray(mergeElementsInto(Template.EMPTY).addElements(v));
    }

    @Override
    TemplateBuilder mergeImpl(TemplateBuilder other) {
      if (other instanceof RefVar) {
        return other.mergeImpl(this);
      }
      CompoundBase otherCompound = (CompoundBase) other;
      if (otherCompound.baseType == baseType) {
        return withElements(i -> elementBuilder(i).merge(otherCompound.elementBuilder(i)));
      }
      return newVarray(otherCompound.mergeElementsInto(mergeElementsInto(Template.EMPTY)));
    }

    @Override
    TemplateBuilder builderForElement(BaseType compositionalType, int elementIndex) {
      if (baseType == compositionalType) {
        return elementBuilder(elementIndex);
      } else if (baseType.sortOrder != compositionalType.sortOrder) {
        return Template.EMPTY;
      } else {
        // If you return an array of a different length than this one, we'll end up creating a
        // vArray that can hold this array's elements.
        return mergeElementsInto(Template.EMPTY);
      }
    }

    /** Merges each element's builder into the given TemplateBuilder. */
    TemplateBuilder mergeElementsInto(TemplateBuilder builder) {
      int n = baseType.size();
      for (int i = 0; i < n; i++) {
        builder = builder.merge(elementBuilder(i));
      }
      return builder;
    }

    /** Recomputes this compound's weight by summing the weights of its elements. */
    void computeWeightPair() {
      int nNumVars = 0;
      int nRefVars = 0;
      for (int i = 0; i < baseType.size(); i++) {
        TemplateBuilder element = elementBuilder(i);
        nNumVars = Math.min(VAR_COUNT_LIMIT, nNumVars + element.nNumVars);
        nRefVars = Math.min(VAR_COUNT_LIMIT, nRefVars + element.nRefVars);
      }
      // Setting private fields in our superclass is a little awkward.
      ((TemplateBuilder) this).nNumVars = (short) nNumVars;
      ((TemplateBuilder) this).nRefVars = (short) nRefVars;
    }

    @Override
    public String toString(Template.Printer printer) {
      return baseType.toString(i -> elementBuilder(i).toString(printer));
    }
  }

  static class CompoundBuilder extends CompoundBase {
    private final TemplateBuilder[] elements;

    CompoundBuilder(BaseType baseType, TemplateBuilder... elements) {
      super(baseType);
      assert elements.length == baseType.size();
      this.elements = elements;
      computeWeightPair();
    }

    @Override
    TemplateBuilder elementBuilder(int i) {
      return elements[i];
    }

    @Override
    CompoundBuilder withElements(IntFunction<TemplateBuilder> updatedElements) {
      Arrays.setAll(elements, updatedElements);
      computeWeightPair();
      return this;
    }

    @Override
    Template build(VarAllocator allocator) {
      Template[] elementTemplates =
          Arrays.stream(elements).map(e -> e.build(allocator)).toArray(Template[]::new);
      return Compound.of(baseType, elementTemplates);
    }

    @Override
    TemplateBuilder insertFrameLayout(int targetWeight) {
      // We prefer to introduce a frame that is as close to the target weight as we can get, so
      // first see if one of our elements can do this.
      for (int i = 0; i < elements.length; i++) {
        TemplateBuilder eBefore = elements[i];
        if (eBefore.totalWeight() < targetWeight) {
          continue;
        }
        short nNumVarBefore = eBefore.nNumVars;
        short nRefVarBefore = eBefore.nRefVars;
        TemplateBuilder eAfter = eBefore.insertFrameLayout(targetWeight);
        elements[i] = eAfter;
        if (eAfter.nNumVars != nNumVarBefore || eAfter.nRefVars != nRefVarBefore) {
          // That element was able to do something, so declare success.
          computeWeightPair();
          return this;
        }
      }
      assert totalWeight() >= targetWeight;
      if (preferVarray()) {
        return newVarray(mergeElementsInto(Template.EMPTY));
      } else {
        return RecordLayout.newFromBuilder(TState.get().tracker().scope, this).asRefVar;
      }
    }

    /**
     * We're going to store values described by this template as frames; returns true if we should
     * use a VArrayLayout rather than a RecordLayout.
     */
    private boolean preferVarray() {
      if (!(baseType instanceof Core.FixedArrayType) || baseType.size() <= 2) {
        return false;
      } else if (baseType.size() > 7) {
        return true;
      }
      // Estimate the weight of the template that we would end up with after a call to
      // mergeElementsInto (unfortunately that's destructive, so we can't just call it
      // and see).
      // This is a list of choices with distinct sort orders.
      List<TemplateBuilder> choices = new ArrayList<>();
      // We won't count elements that are empty
      int numElements = elements.length;
      for (TemplateBuilder element : elements) {
        if (element == Template.EMPTY) {
          --numElements;
        } else if (element instanceof UnionBase union) {
          int n = union.numChoices();
          for (int i = 0; i < n; i++) {
            saveChoice(choices, union.choiceBuilder(i));
          }
        } else {
          saveChoice(choices, element);
        }
      }
      assert !choices.isEmpty();
      // Copied from UnionBase.computeWeightPair
      int maxNums = 0;
      int maxRefs = 0;
      for (TemplateBuilder choice : choices) {
        maxNums = Math.max(maxNums, choice.nNumVars);
        maxRefs = Math.max(maxRefs, choice.nRefVars);
      }
      // VArrays will generally require more memory to store but often less code to access.
      // This is an arbitrary heuristic chosen after a looking at a few cases.
      int memoryPenalty =
          numElements * (maxNums + maxRefs + (choices.size() > 1 ? 1 : 0)) - totalWeight();
      return memoryPenalty <= elements.length;
    }

    /**
     * A helper for {@link #preferVarray}; inserts {@code choice} into {@code choices}, keeping them
     * sorted by order. If there is already a choice with the same order, keep the one with the
     * larger {@link #totalWeight}.
     */
    private static void saveChoice(List<TemplateBuilder> choices, TemplateBuilder choice) {
      if (choices.isEmpty()) {
        choices.add(choice);
        return;
      }
      long order = choice.baseType().sortOrder;
      int pos = Ordered.search(order, i -> choices.get(i).baseType().sortOrder, choices.size());
      if (pos < 0) {
        choices.add(-(pos + 1), choice);
      } else {
        TemplateBuilder prev = choices.get(pos);
        if (prev.totalWeight() < choice.totalWeight()) {
          choices.set(pos, choice);
        }
      }
    }
  }

  /** A shared base class for (immutable) Union and (mutable) UnionBuilder. */
  abstract static class UnionBase extends TemplateBuilder {

    UnionBase() {
      // Subclass constructors are responsible for calling computeWeightPair() after initializing
      // *unless* this is an untagged union, in which case this is the correct weight.
      super((short) 0, (short) 1);
    }

    abstract int numChoices();

    abstract TemplateBuilder choiceBuilder(int i);

    /**
     * Returns a UnionBuilder with the given choice either replacing one of our choices ({@code
     * index >= 0}) or inserted among them ({@code index < 0}) (see {@link Ordered#setOrAdd} for the
     * interpretation of {@code index}). If this is a UnionBuilder, modifies it; otherwise creates a
     * new one.
     */
    abstract UnionBuilder withNewChoice(int index, TemplateBuilder choice);

    /** Returns true if this union has a {@link Core#TO_BE_SET} choice. */
    boolean hasToBeSet() {
      // We chose the sortIndex of TO_BE_SET so that it would (almost always) be at the end.
      // (If it's not at the end, it's followed by UNDEF, but the unions that we're testing
      // here should never contain UNDEF).
      TemplateBuilder lastChoice = choiceBuilder(numChoices() - 1);
      if (lastChoice == Core.TO_BE_SET.asTemplate) {
        return true;
      }
      assert lastChoice.baseType().sortOrder < BaseType.SORT_ORDER_TO_BE_SET;
      return false;
    }

    @Override
    public BaseType baseType() {
      throw new AssertionError();
    }

    @Override
    @RC.Out
    @Nullable Value castImpl(TState tstate, Value v) {
      int i = indexOf(v.baseType().sortOrder);
      return (i < 0) ? null : choiceBuilder(i).castImpl(tstate, v);
    }

    @Override
    boolean couldCastImpl(Value v, TestOption option) {
      int i = indexOf(v.baseType().sortOrder);
      return (i >= 0) && choiceBuilder(i).couldCastImpl(v, option);
    }

    @Override
    boolean isSubsetOfImpl(TemplateBuilder other, TestOption option) {
      return IntStream.range(0, numChoices())
          .allMatch(i -> choiceBuilder(i).isSubsetOf(other, option));
    }

    @Override
    boolean overlapsImpl(TemplateBuilder t2) {
      if (t2 instanceof UnionBase) {
        // True if any of our choices overlaps any of their choices.
        return IntStream.range(0, numChoices()).anyMatch(i -> t2.overlapsImpl(choiceBuilder(i)));
      } else {
        // Only one of our choices is a possible overlap.
        int i = indexOf(t2.baseType().sortOrder);
        return (i >= 0) && choiceBuilder(i).overlaps(t2);
      }
    }

    @Override
    UnionBase addImpl(Value v) {
      BaseType baseType = v.baseType();
      int index = indexOf(baseType.sortOrder);
      TemplateBuilder updated;
      if (index >= 0) {
        TemplateBuilder choice = choiceBuilder(index);
        short nNumVarBefore = choice.nNumVars;
        short nRefVarBefore = choice.nRefVars;
        updated = choice.addImpl(v);
        if (updated == choice) {
          if (updated.nNumVars != nNumVarBefore || updated.nRefVars != nRefVarBefore) {
            assert !(this instanceof Template);
            computeWeightPair();
          }
          return this;
        }
      } else {
        updated = newBuilder(v);
      }
      return withNewChoice(index, updated);
    }

    @Override
    UnionBase mergeImpl(TemplateBuilder other) {
      int index = indexOf(other.baseType().sortOrder);
      if (index >= 0) {
        TemplateBuilder choice = choiceBuilder(index);
        short nNumVarBefore = choice.nNumVars;
        short nRefVarBefore = choice.nRefVars;
        other = choice.mergeOrAdd(other);
        if (other == choice) {
          if (other.nNumVars != nNumVarBefore || other.nRefVars != nRefVarBefore) {
            assert !(this instanceof Template);
            computeWeightPair();
          }
          return this;
        }
      }
      return withNewChoice(index, other);
    }

    /** Calls {@link #mergeImpl} with each choice in {@code other}. */
    UnionBase mergeAll(UnionBase other) {
      UnionBase result = this;
      int n = other.numChoices();
      for (int i = 0; i < n; i++) {
        result = result.mergeImpl(other.choiceBuilder(i));
      }
      return result;
    }

    @Override
    boolean coercesToFloat() {
      int i = indexOf(BaseType.SORT_ORDER_NUM);
      return i >= 0 && choiceBuilder(i).coercesToFloat();
    }

    @Override
    TemplateBuilder builderForElement(BaseType compositionalType, int elementIndex) {
      int i = indexOf(compositionalType.sortOrder);
      return i >= 0
          ? choiceBuilder(i).builderForElement(compositionalType, elementIndex)
          : Template.EMPTY;
    }

    @Override
    @Nullable FrameLayout layout(long sortOrder) {
      int i = indexOf(sortOrder);
      return i >= 0 ? choiceBuilder(i).layout(sortOrder) : null;
    }

    /**
     * Returns the index of the choice with the given sortOrder, or the position at which a new
     * choice with that sortOrder should be inserted (see {@link Ordered#search} for the
     * interpretation of the result).
     */
    int indexOf(long order) {
      return Ordered.search(order, i -> choiceBuilder(i).baseType().sortOrder, numChoices());
    }

    /**
     * Returns true if this UnionBase's choices are valid and correctly ordered. Should always
     * return true; only used for assertions.
     */
    boolean isValidChoiceList() {
      if (numChoices() < 2) {
        // choices must have length >= 2
        return false;
      }
      for (int i = 0; i < numChoices(); i++) {
        TemplateBuilder choice = choiceBuilder(i);
        if (choice instanceof UnionBase || choice instanceof Template.Empty) {
          // choices may not include another union or EMPTY
          return false;
        } else if (i != 0
            && choice.baseType().sortOrder <= choiceBuilder(i - 1).baseType().sortOrder) {
          // choices must be in strictly increasing sortOrder
          return false;
        }
      }
      return true;
    }

    /**
     * Computes the weight pair for this union. The result is (maxRefVars, 1 + maxNumVars) where
     * maxRefVars is the largest nRefVars among the union's choices, and maxNumVars is the largest
     * nNumVars among its choices (the extra numVar is for the tag; this method is not called for
     * untagged Unions, but is called for all UnionBuilders).
     */
    void computeWeightPair() {
      int maxNums = 0;
      int maxRefs = 0;
      int numChoices = numChoices();
      for (int i = 0; i < numChoices; i++) {
        TemplateBuilder choice = choiceBuilder(i);
        maxNums = Math.max(maxNums, choice.nNumVars);
        maxRefs = Math.max(maxRefs, choice.nRefVars);
      }
      // If this is a TemplateBuilder, we assume that it will be a tagged union when built.
      ((TemplateBuilder) this).nNumVars = (short) Math.min(VAR_COUNT_LIMIT, 1 + maxNums);
      ((TemplateBuilder) this).nRefVars = (short) maxRefs;
    }

    /** Returns a string that {@link #toString} will prefix to the list of choices. */
    String toStringPrefix(Template.Printer printer) {
      return "";
    }

    /** If true, {@link #toString} will label each choice with its index. */
    boolean toStringNumbersChoices() {
      return false;
    }

    @Override
    public String toString(Template.Printer printer) {
      StringBuilder sb = new StringBuilder();
      sb.append(toStringPrefix(printer)).append("⸨");
      boolean numberChoices = toStringNumbersChoices();
      int n = numChoices();
      for (int i = 0; i < n; i++) {
        if (i != 0) {
          sb.append("; ");
        }
        if (numberChoices) {
          sb.append(i).append(":");
        }
        sb.append(choiceBuilder(i).toString(printer));
      }
      return sb.append("⸩").toString();
    }
  }

  static class UnionBuilder extends UnionBase {
    private final List<TemplateBuilder> choices;

    UnionBuilder(List<TemplateBuilder> choices) {
      this.choices = choices;
      assert isValidChoiceList();
      computeWeightPair();
    }

    /** Creates a UnionBuilder containing the given choices, which must have distinct sortOrders. */
    UnionBuilder(TemplateBuilder b1, TemplateBuilder b2) {
      this(asOrderedList(b1, b2));
    }

    /**
     * Returns a two-element sorted list containing the given TemplateBuilders, which must have
     * distinct sortOrders.
     */
    private static List<TemplateBuilder> asOrderedList(TemplateBuilder b1, TemplateBuilder b2) {
      long o1 = b1.baseType().sortOrder;
      long o2 = b2.baseType().sortOrder;
      assert o1 != o2;
      List<TemplateBuilder> result = new ArrayList<>();
      if (o1 < o2) {
        result.add(b1);
        result.add(b2);
      } else {
        result.add(b2);
        result.add(b1);
      }
      return result;
    }

    @Override
    int numChoices() {
      return choices.size();
    }

    @Override
    TemplateBuilder choiceBuilder(int i) {
      return choices.get(i);
    }

    @Override
    UnionBuilder withNewChoice(int index, TemplateBuilder choice) {
      Ordered.setOrAdd(choices, index, choice);
      assert isValidChoiceList();
      if (index < 0 && choice instanceof Constant) {
        // Small optimization: we can skip recomputing the weight in cases like adding a singleton
        assert choice.nNumVars == 0 && choice.nRefVars == 0;
      } else {
        computeWeightPair();
      }
      return this;
    }

    @Override
    Template build(VarAllocator allocator) {
      int nChoices = numChoices() - (allocator.dropToBeSetFromUnions() && hasToBeSet() ? 1 : 0);
      if (nChoices == 1) {
        return choiceBuilder(0).build(allocator);
      }
      // If we exit this loop with untaggedIndex >= 0 we can use an untagged union,
      // and untaggedIndex is the index of the first RefVar choice.
      int untaggedIndex = -1;
      for (int i = 0; i < nChoices; i++) {
        TemplateBuilder choice = choices.get(i);
        if (choice instanceof RefVar) {
          if (untaggedIndex < 0) {
            untaggedIndex = i;
          }
        } else if (!choice.isSingleton()) {
          untaggedIndex = -1;
          break;
        }
      }
      Template[] newChoices = new Template[nChoices];
      // If we're going to need a tag, allocate it first.
      NumVar tag =
          (untaggedIndex >= 0)
              ? null
              : ((newChoices.length <= 256) ? NumVar.UINT8 : NumVar.INT32).build(allocator);
      // It's not unusual for all our choices to be constant (e.g. when they're singletons), in
      // which case we can skip all the mucking around with duplicated VarAllocators.
      if (choices.stream().allMatch(c -> c instanceof Constant)) {
        Arrays.setAll(newChoices, i -> (Constant) choices.get(i));
      } else {
        // We need to reset the VarAllocator before building each choice,
        // and union the resulting VarAllocator states.
        VarAllocator initial = allocator.duplicate();
        VarAllocator scratch = allocator.duplicate();
        for (int i = 0; i < newChoices.length; i++) {
          TemplateBuilder choice = choices.get(i);
          if (choice instanceof Constant c) {
            newChoices[i] = c;
          } else {
            scratch.resetTo(initial);
            newChoices[i] = choice.build(scratch);
            allocator.union(scratch);
          }
        }
      }
      RefVar untagged = (untaggedIndex < 0) ? null : (RefVar) newChoices[untaggedIndex];
      return new Union(tag, untagged, newChoices);
    }

    @Override
    TemplateBuilder insertFrameLayout(int targetWeight) {
      // Find our heaviest choice that is a CompoundBuilder, and ask it to do the insertion.
      // It's possible that there isn't a suitable choice (although ValueMemo waits until the
      // total weight is more than double the target weight, which should ensure that there is
      // one), and if there is one it's possible that reducing its weight won't reduce our weight
      // (there may be more than one heavy choice).  Either way we should be OK -- ValueMemo
      // will just try again next time the TemplateBuilder changes.
      int best = -1;
      int bestWeight = 0;
      for (int i = 0; i < choices.size(); i++) {
        TemplateBuilder choice = choices.get(i);
        if (choice instanceof CompoundBuilder) {
          int w = choice.totalWeight();
          if (w > bestWeight) {
            best = i;
            bestWeight = w;
          }
        }
      }
      if (bestWeight >= targetWeight) {
        choices.set(best, choices.get(best).insertFrameLayout(targetWeight));
        computeWeightPair();
      }
      return this;
    }
  }
}
