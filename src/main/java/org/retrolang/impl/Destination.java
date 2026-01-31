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
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import org.retrolang.code.FutureBlock;

/**
 * A Destination represents yet-to-be-generated code that may be branched to from multiple points,
 * and will be passed zero or more Values. Destinations are used for "function complete" (where the
 * values are the function results), ContinuationMethods (where the values are the arguments), and
 * branch targets (where the values are locals to be harmonized). The representation of the passed
 * values is determined by a ValueMemo or a list of Templates.
 *
 * <p>Destinations also keep track of the current escape link at each branch point; if all branches
 * have the same escape we can keep it when emitting code for the destination, otherwise we must
 * create a new one.
 *
 * <p>In the simplest case there is only a single branch to the Destination and the values it passes
 * are a subset of the supported representations; in that case no additional code or registers is
 * needed.
 *
 * <p>In the general case a Destination will allocate new registers to represent a value using
 * RValues based on the given templates, and then each branch to the Destination will first copy the
 * passed values into the new registers.
 *
 * <p>Destinations also implement {@link #createChild}, an operation that creates a new Destination
 * with a subset of the values of an existing Destination. The child destination must be emitted
 * first, and its emitted code should eventually call {@link #branchToParent} providing the values
 * that were not provided to the child.
 */
class Destination implements ResultsInfo {
  /** Links to this Destination; null after this Destination has been emitted. */
  private FutureBlock links = new FutureBlock();

  /** The combined escape state from branches to this Destination. */
  private CodeGen.EscapeState escape;

  /**
   * One of
   *
   * <ul>
   *   <li>{@link #TRIVIAL}, for Destinations with zero passed values;
   *   <li>a {@link FromValueMemo}, for Destinations that were created from a ValueMemo and have
   *       zero or one incoming links;
   *   <li>a {@link FromTemplates}, for Destinations that were created from a list of templates and
   *       have zero or one incoming links;
   *   <li>a {@link ForChild}, for Destinations that were created by {@link #createChild}; or
   *   <li>a {@link Full}, for non-child Destinations that have more than one incoming link.
   * </ul>
   */
  private State state;

  private Destination(State state) {
    this.state = state;
  }

  /** Returns a new Destination with no passed values. */
  static Destination newSimple() {
    return new Destination(TRIVIAL);
  }

  /** Returns a new Destination with one passed value for each element of the given ValueMemo. */
  static Destination fromValueMemo(ValueMemo vMemo) {
    return new Destination((vMemo.size() == 0) ? TRIVIAL : new FromValueMemo(vMemo));
  }

  /** Returns a new Destination with one passed value for each of the given templates. */
  static Destination fromTemplates(ImmutableList<Template> templates) {
    return new Destination(templates.isEmpty() ? TRIVIAL : new FromTemplates(templates));
  }

  /**
   * Returns a new child Destination that will be passed the first {@code prefixSize} values of this
   * Destination.
   */
  Destination createChild(int prefixSize) {
    assert prefixSize >= 0 && prefixSize <= size();
    return new Destination(new ForChild(this, prefixSize));
  }

  /** Returns the number of values that will be passed to this Destination. */
  int size() {
    return state.size();
  }

  /** Returns true if this destination has allocated new registers. */
  boolean isFull() {
    return root().state instanceof Full;
  }

  /** Ensures that new registers are allocated to store the values passed to this Destination. */
  void forceFull(CodeGen codeGen) {
    var unused = getFull(this, codeGen);
  }

  /**
   * Returns the index of the first register allocated to store the values passed to this
   * Destination; assumes that {@link #forceFull} was called previously.
   */
  int firstRegister() {
    return getFull(this, null).registerStart[0];
  }

  /**
   * Returns the index of the last register allocated to store the values passed to this
   * Destination; assumes that {@link #forceFull} was called previously.
   */
  int lastRegister() {
    return getFull(this, null).registerStart[state.size()] - 1;
  }

  /**
   * Creates a new Destination with the same value templates as this one. Should not be called on
   * Destinations returned from {@link #createChild}.
   */
  Destination duplicate(CodeGen codeGen) {
    State newState = this.state;
    assert !(newState instanceof ForChild);
    if (newState instanceof Full) {
      newState = new Full(codeGen, state);
    }
    return new Destination(newState);
  }

  /** The implementation of ResultInfo just delegates to the State. */
  @Override
  public <T> T result(int resultNum, TProperty<T> property) {
    return state.result(resultNum, property);
  }

  /** Adds a branch to this Destination. */
  void addBranch(CodeGen codeGen, Value[] values) {
    if (values == null || values.length == 0) {
      assert size() == 0;
    } else {
      assert values.length == size();
      state.saveValues(this, codeGen, values);
    }
    addBranch(codeGen);
  }

  /** Adds a branch to this Destination, assuming that the values have already been saved. */
  void addBranch(CodeGen codeGen) {
    assert links != null;
    if (codeGen.cb.nextIsReachable()) {
      // If all incoming links have the same non-null escape, emit() will restore it; otherwise
      // emit will setEscape() to null.
      CodeGen.EscapeState incomingEscape = codeGen.escapeState();
      if (!links.hasInLink()) {
        this.escape = incomingEscape;
      } else {
        this.escape = this.escape.combine(incomingEscape);
      }
      codeGen.cb.branchTo(links);
    }
  }

  /**
   * Returns an optimized CopyPlan from the given source Template to this Destination's registers.
   * Calling {@code createCopyPlan}, emitting the plan it returns, and then calling {@link
   * #addBranch(CodeGen)} is a more flexible (but less convenient and sometimes less efficient)
   * alternative to calling {@link #addBranch(CodeGen, Value[])}.
   */
  CopyPlan createCopyPlan(CodeGen codeGen, int i, Template src) {
    Full full = getFull(this, codeGen);
    Template dst = full.templates[i];
    CopyPlan plan = CopyPlan.create(src, dst);
    return CopyOptimizer.toRegisters(plan, full.registerStart[i], full.registerStart[i + 1], dst);
  }

  /**
   * Prepares the CodeBuilder to add the blocks at this Destination; returns the values that were
   * passed to it. No calls to {@link #addBranch} are allowed after this.
   *
   * <p>Returns null if the destination is unreachable or this destination was created by {@link
   * #createChild}.
   */
  @CanIgnoreReturnValue
  Value[] emit(CodeGen codeGen) {
    assert !codeGen.cb.nextIsReachable();
    if (!links.hasInLink()) {
      links = null;
      return null;
    }
    codeGen.cb.setNext(links);
    codeGen.restore(escape);
    Value[] result = state.values();
    links = null;
    return result;
  }

  /**
   * Only valid on Destinations that were created by {@link #createChild}; branches to the parent
   * Destination. If this child has fewer values than its parent, {@code extras} must contain the
   * additional Values.
   */
  void branchToParent(CodeGen codeGen, Value extras) {
    // This should be called after emit() on this Destination but before emit() on its parent.
    assert links == null && codeGen.cb.nextIsReachable();
    ((ForChild) state).branchToParent(codeGen, extras);
  }

  /**
   * If this Destination was not created by {@link #createChild}, returns it; otherwise follows
   * {@link ForChild#parent} links until it reaches a non-child Destination and returns that.
   */
  private Destination root() {
    Destination result = this;
    while (result.state instanceof ForChild fc) {
      result = fc.parent;
    }
    return result;
  }

  /**
   * Ensures that the root of the given destination uses a Full state, and returns it.
   *
   * <p>If {@code codeGen} is non-null and the root does not already use a Full state, it will be
   * converted; if {@code codeGen} is null, the root must already use a Full state.
   */
  private static Full getFull(Destination dest, CodeGen codeGen) {
    Destination root = dest.root();
    if (root.state instanceof Full full) {
      return full;
    }
    assert codeGen != null;
    Simple simple = (Simple) root.state;
    Full full = new Full(codeGen, simple);
    simple.copyFromSavedValues(root, codeGen, full);
    root.state = full;
    return full;
  }

  /**
   * Each Destination has an associated State that determines how the passed values are represented.
   */
  private abstract static class State implements ResultsInfo {
    /** Returns the number of values that must be passed to this Destination. */
    abstract int size();

    /** Called each time a new branch is added to this Destination. */
    abstract void saveValues(Destination dest, CodeGen codeGen, Value[] values);

    /** The values that will be returned from {@link #emit}. */
    abstract Value[] values();
  }

  private static final Value[] EMPTY_VALUES = new Value[0];

  /** The state for Destinations with no passed values. */
  private static final State TRIVIAL =
      new State() {
        @Override
        int size() {
          return 0;
        }

        @Override
        void saveValues(Destination dest, CodeGen codeGen, Value[] values) {
          throw new AssertionError();
        }

        @Override
        Value[] values() {
          return EMPTY_VALUES;
        }

        @Override
        public <T> T result(int resultNum, TProperty<T> property) {
          throw new AssertionError();
        }
      };

  /**
   * A simple State has not allocated any registers of its own; it is either
   *
   * <ul>
   *   <li>a FromValueMemo or FromTemplates with zero or one inlinks, or
   *   <li>a ForChild.
   * </ul>
   *
   * <p>A FromValueMemo or FromTemplates that gets multiple inlinks will be converted to a Full
   * state; a ForChild doesn't convert, but may cause its root to convert if it gets multiple
   * inlinks.
   */
  private abstract static class Simple extends State {
    /**
     * If this Destination has exactly one inlink (and the passed values are a subset of the
     * ValueMemo or templates), these will be the values that were passed. If it has zero inlinks or
     * is a ForChild with multiple inlinks this will be null.
     */
    Value[] savedValues;

    /**
     * If {@link #savedValues} is non-null, this is the corresponding value of {@code cb.nextSrc()}.
     */
    Object savedSrc;

    @Override
    final void saveValues(Destination dest, CodeGen codeGen, Value[] values) {
      assert dest.state == this;
      assert dest.links.hasInLink() || this.savedValues == null;
      // If this is the first inlink, and all of the passed values are representable by the
      // ValueMemo or templates, we can just save them; if there are no other inlinks they'll
      // be returned unmodified by values().
      if (!dest.links.hasInLink() && !saveNeedsCheck(values)) {
        this.savedValues = values;
        this.savedSrc = codeGen.cb.nextSrc();
        return;
      }
      // Otherwise we need to ensure that registers have been allocated, and then copy the passed
      // values into them.
      Full full = getFull(dest, codeGen);
      copyFromSavedValues(dest, codeGen, full);
      full.setValues(codeGen, 0, values);
    }

    /** Returns true if saving the given values might require a runtime check. */
    boolean saveNeedsCheck(Value[] values) {
      for (int i = 0; i < values.length; i++) {
        Value v = values[i];
        if (v instanceof ConditionalValue
            || !result(
                i,
                TProperty.isSupersetOf(
                    RValue.toTemplate(v), TemplateBuilder.TestOption.UPGRADE_SUB_INTS))) {
          return true;
        }
      }
      return false;
    }

    /**
     * If this state has {@link #savedValues} from a previous inlink, add blocks to copy them to the
     * registers allocated by {@code full}. This must be done before adding any more inlinks.
     */
    private void copyFromSavedValues(Destination dest, CodeGen codeGen, Full full) {
      if (savedValues != null) {
        // These blocks will be executed by the first inlink, but not by any that are added later.
        // Save the CodeBuilder's current next block and next src so that we can restore it when
        // we're done, the set them to the values they had when the values were saved (as if we
        // had emitted these blocks then).
        FutureBlock prev = codeGen.cb.swapNext(dest.links);
        dest.links = new FutureBlock();
        Object prevSrc = codeGen.cb.nextSrc();
        codeGen.cb.setNextSrc(savedSrc);
        full.setValues(codeGen, 0, savedValues);
        codeGen.cb.branchTo(dest.links);
        codeGen.cb.setNext(prev);
        codeGen.cb.setNextSrc(prevSrc);
        savedValues = null;
        savedSrc = null;
      }
    }

    void ensureNoSavedValues(Destination dest, CodeGen codeGen) {
      if (savedValues != null) {
        copyFromSavedValues(dest, codeGen, getFull(dest, codeGen));
      }
    }

    @Override
    final Value[] values() {
      return (this instanceof ForChild) ? null : savedValues;
    }
  }

  /** A Simple state for Destinations created by {@link #fromValueMemo}. */
  private static class FromValueMemo extends Simple {
    private final ValueMemo vMemo;

    FromValueMemo(ValueMemo vMemo) {
      this.vMemo = vMemo;
    }

    @Override
    int size() {
      return vMemo.size();
    }

    @Override
    public <T> T result(int resultNum, TProperty<T> property) {
      return vMemo.result(resultNum, property);
    }
  }

  /** A Simple state for Destinations created by {@link #fromTemplates}. */
  private static class FromTemplates extends Simple {
    private final ImmutableList<Template> templates;

    FromTemplates(ImmutableList<Template> templates) {
      this.templates = templates;
    }

    @Override
    int size() {
      return templates.size();
    }

    @Override
    public <T> T result(int resultNum, TProperty<T> property) {
      return property.fn.apply(templates.get(resultNum).toBuilder());
    }
  }

  /**
   * A state for Destinations that have allocated new registers for the passed values; each inlink
   * will get additional blocks to copy the passed values into the new registers.
   */
  private static class Full extends State {
    /** A template (with vars referring to registers) for each passed value. */
    private final Template[] templates;

    /**
     * The start (and end) register indices for each template, to simplify optimizing copies to
     * them.
     */
    private final int[] registerStart;

    Full(CodeGen codeGen, State source) {
      // Only FromValueMemo and FromTemplates should be converted to Full
      assert !(source instanceof ForChild);
      int size = source.size();
      templates = new Template[size];
      registerStart = new int[size + 1];
      TProperty<Template> build = TProperty.build(codeGen.newAllocator());
      for (int i = 0; i < size; i++) {
        registerStart[i] = codeGen.cb.numRegisters();
        templates[i] = source.result(i, build);
      }
      registerStart[size] = codeGen.cb.numRegisters();
    }

    /** Add blocks to store {@code values} into the templates starting at {@code offset}. */
    void setValues(CodeGen codeGen, int offset, Value[] values) {
      for (int i = 0; i < values.length; i++) {
        int target = i + offset;
        codeGen.emitStore(
            values[i], templates[target], registerStart[target], registerStart[target + 1]);
        if (!codeGen.cb.nextIsReachable()) {
          return;
        }
      }
    }

    @Override
    int size() {
      return templates.length;
    }

    @Override
    void saveValues(Destination dest, CodeGen codeGen, Value[] values) {
      setValues(codeGen, 0, values);
    }

    @Override
    Value[] values() {
      Value[] result = new Value[templates.length];
      Arrays.setAll(result, i -> RValue.fromTemplate(templates[i]));
      return result;
    }

    @Override
    public <T> T result(int resultNum, TProperty<T> property) {
      return property.fn.apply(templates[resultNum].toBuilder());
    }
  }

  /**
   * The state for Destinations created by {@link #createChild}. Like other Simple states, these
   * handle the simple case of one inlink by just saving the values, and more complex cases by
   * copying the values into new registers. Unlike other Simple states they never convert to Full
   * and allocate registers themselves; instead they ensure that their root has converted to Full
   * and use the root's registers (to avoid an additional copy when they {@link #branchToParent}).
   */
  private static class ForChild extends Simple {

    private final Destination parent;
    private final int prefixSize;

    ForChild(Destination parent, int prefixSize) {
      this.parent = parent;
      this.prefixSize = prefixSize;
    }

    void branchToParent(CodeGen codeGen, Value extras) {
      int parentSize = parent.size();
      if (prefixSize != parentSize) {
        // We should have been passed an appropriate number of extra values
        assert extras.baseType().size() == parentSize - prefixSize;
        if (savedValues != null) {
          // Just append them to the savedValues
          savedValues = getElements(extras, savedValues);
        } else {
          // Store them in the appropriate registers
          getFull(parent, codeGen).setValues(codeGen, prefixSize, getElements(extras, null));
        }
      } else {
        assert extras == null;
      }
      if (savedValues != null) {
        Object prevSrc = codeGen.cb.nextSrc();
        codeGen.cb.setNextSrc(savedSrc);
        parent.addBranch(codeGen, savedValues);
        codeGen.cb.setNextSrc(prevSrc);
        savedValues = null;
        savedSrc = null;
      } else {
        // Either prefixSize == 0 or we've already stored the values in the appropriate registers
        if (parent.state instanceof Simple simpleParent) {
          simpleParent.ensureNoSavedValues(parent, codeGen);
        }
        parent.addBranch(codeGen);
      }
    }

    /**
     * Copy the elements of {@code extras} into a {@code Value[]}. If {@code appendTo} is non-null,
     * precede them with the Values in {@code appendTo}.
     */
    private static Value[] getElements(Value extras, Value[] appendTo) {
      int size = extras.baseType().size();
      int start;
      Value[] result;
      if (appendTo == null) {
        start = 0;
        result = new Value[size];
      } else {
        start = appendTo.length;
        result = Arrays.copyOf(appendTo, start + size);
      }
      for (int i = 0; i < size; i++) {
        result[start + i] = extras.element(i);
      }
      return result;
    }

    @Override
    int size() {
      return prefixSize;
    }

    @Override
    public <T> T result(int resultNum, TProperty<T> property) {
      assert resultNum < prefixSize;
      return parent.result(resultNum, property);
    }
  }
}
