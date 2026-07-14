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

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import org.retrolang.Vm;
import org.retrolang.code.CodeValue;
import org.retrolang.code.ValueInfo;
import org.retrolang.util.StringUtil;

/**
 * Every Value has a BaseType; whether the value is included in some VmType can be answered just by
 * looking at its BaseType.
 *
 * <p>(The only exception is the Integer type, which includes some but not all Values with BaseType
 * Number. Integer is a special case in many ways, and probably shouldn't be a VmType at all.)
 *
 * <p>Non-singleton BaseTypes may also be used as the ValueInfo for a pointer-valued variable,
 * indicating that it points to a Value with this BaseType (if {@link #usesFrames} is false), or
 * that it points to a Frame whose BaseType has the same sortOrder (if {@link #usesFrames} is true).
 */
public abstract class BaseType implements PtrInfo {

  /**
   * The size of a BaseType is
   *
   * <ul>
   *   <li>0 for singletons,
   *   <li>positive (the size of the compound) for compounds, and
   *   <li>negative for non-compositional baseTypes (NUMBER and reference-only baseTypes).
   * </ul>
   */
  private final int size;

  /** For BaseTypes with size 0, the corresponding Singleton value; null for other BaseTypes. */
  private final Singleton asSingleton;

  /**
   * Each BaseType has a corresponding positive long, which may be used when sorting or searching
   * lists of objects with associated BaseTypes. Most BaseTypes are assigned distinct, arbitrary
   * sortOrders, but a few are given special ones.
   */
  final long sortOrder;

  /** The sortOrder of Core.NUMBER. */
  static final long SORT_ORDER_NUM = 1;

  /**
   * The sortOrder of all array BaseTypes. This is currently the only case where multiple BaseTypes
   * share a sortOrder.
   */
  static final long SORT_ORDER_ARRAY = 2;

  /**
   * The sortOrder of Core.UNDEF. I think that always putting this last in a union will allow us to
   * generate simpler code.
   */
  static final long SORT_ORDER_UNDEF = Long.MAX_VALUE - 1; // Ordered.search() adds 1

  /**
   * The sortOrder of Core.TO_BE_SET. Some unions (e.g. in RValues) can include TO_BE_SET, but
   * others (e.g. in a VArrayLayout) exclude them; I think that putting them at the end of the
   * unions that do include them will allow us to generate simpler code.
   */
  static final long SORT_ORDER_TO_BE_SET = SORT_ORDER_UNDEF - 1;

  static final long LAST_UNRESERVED_SORT_ORDER = SORT_ORDER_TO_BE_SET - 1;

  /** All other BaseTypes will be assigned arbitrary sequential sortOrders starting here. */
  static final long FIRST_UNRESERVED_SORT_ORDER = 3;

  private static final AtomicLong nextSortOrder = new AtomicLong(FIRST_UNRESERVED_SORT_ORDER);

  /** Flag value for sortOrder, to indicate that an arbitrary unique sortOrder should be used. */
  static final long ALLOC_NEW_SORT_ORDER = -1;

  BaseType(int size) {
    this(size, ALLOC_NEW_SORT_ORDER);
  }

  BaseType(int size, long sortOrder) {
    this.size = size;
    asSingleton = (size == 0) ? new Singleton(this) : null;
    if (sortOrder == ALLOC_NEW_SORT_ORDER) {
      this.sortOrder = nextSortOrder.getAndIncrement();
    } else {
      assert sortOrder > 0
          && (sortOrder < FIRST_UNRESERVED_SORT_ORDER || sortOrder > LAST_UNRESERVED_SORT_ORDER);
      this.sortOrder = sortOrder;
    }
  }

  /** True if this is a singleton type. */
  public boolean isSingleton() {
    return size == 0;
  }

  /**
   * If true, two Values of this BaseType are equivalent if all of their corresponding elements are
   * equivalent.
   */
  public boolean isCompositional() {
    return size >= 0;
  }

  /**
   * Returns the size of a compound BaseType, or 0 if this is a singleton. Should only be called if
   * {@link #isCompositional} returns true.
   */
  public int size() {
    assert isCompositional();
    return size;
  }

  /** Returns true if values of this BaseType are arrays. */
  public boolean isArray() {
    // Could also be written as Core.ARRAY.contains(this), but I expect this to be a little faster.
    return sortOrder == SORT_ORDER_ARRAY;
  }

  /** Returns true if values of this BaseType may be stored as Frames. */
  public boolean usesFrames() {
    // Frames are used for arrays and for any BaseType that is compositional and not a singleton.
    return isArray() || size > 0;
  }

  /**
   * Returns the Singleton corresponding to this BaseType. Should only be called on BaseTypes with
   * size 0.
   */
  public Singleton asValue() {
    assert asSingleton != null;
    return asSingleton;
  }

  /**
   * Returns a string representation of a value of this type, given an IntFunction that returns its
   * elements.
   *
   * <p>Should only be called when {@link #size} is positive.
   */
  public String toString(IntFunction<Object> elements) {
    return StringUtil.joinElements(this + "(", ")", size, elements);
  }

  /**
   * Returns a string representation of a value of this type.
   *
   * <p>Should only be called when {@link #size} is positive.
   */
  String compositionalToString(Value v) {
    assert v.baseType() == this;
    return toString(v::peekElement);
  }

  /**
   * Returns true if
   *
   * <ul>
   *   <li>{@code v2} has baseType {@code this}, or {@code v1} is a fixed length array and {@code
   *       v2} is a varray of the same length; and
   *   <li>the corresponding elements of {@code v1} and {@code v2} are {@code equals()}.
   * </ul>
   *
   * <p>Should only be called when {@code v1} has baseType {@code this} and {@link #isCompositional}
   * is true.
   */
  boolean equalValues(Value v1, Value v2) {
    assert isCompositional() && v1.baseType() == this;
    if (v2.baseType() == this
        || (isArray() && v2.baseType().isArray() && v2.numElements() == size)) {
      return Value.equalElements(v1, v2, size);
    }
    return false;
  }

  /**
   * Returns the most specific VmType for values with this BaseType. Should only be called on
   * BaseTypes that correspond to Retrospect-language values.
   */
  public abstract VmType vmType();

  /**
   * Creates a new uncounted Value with the given elements, all of which must be uncounted.
   *
   * <p>Should only be called when {@link #isCompositional} is true.
   */
  public Value uncountedOf(Value... elements) {
    assert isCompositional();
    return (elements.length == 0)
        ? asValue()
        : new CompoundValue(Allocator.UNCOUNTED, this, elements);
  }

  /**
   * Creates a new uncounted Value with the given elements, all of which must be uncounted.
   *
   * <p>Should only be called when {@link #isCompositional} is true.
   */
  public Value uncountedOf(RC.RCIntFunction<Value> elements) {
    assert isCompositional();
    return CompoundValue.of(Allocator.UNCOUNTED, this, elements);
  }

  // PtrInfo methods
  @Override
  public BaseType baseType() {
    return this;
  }

  @Override
  public boolean containsValue(Object value) {
    assert !isSingleton();
    return value == null || ((Value) value).baseType().sortOrder == sortOrder;
  }

  @Override
  public ValueInfo unionConst(CodeValue.Const constInfo) {
    assert !isSingleton();
    return PtrInfo.union(this, constInfo);
  }

  @Override
  public ValueInfo removeConst(CodeValue.Const constInfo) {
    assert !isSingleton();
    return this;
  }

  /** A subclass for BaseTypes that correspond to Retrospect-language types. */
  public static class Named extends BaseType {
    final AsType asType;

    /** Create a BaseType.Named with the given super types. */
    Named(VmModule module, String name, int size, long sortOrder, Vm.Type... superTypes) {
      super(size, sortOrder);
      asType = new AsType(module, name, this, superTypes);
    }

    Named(VmModule module, String name, int size, Vm.Type... superTypes) {
      this(module, name, size, ALLOC_NEW_SORT_ORDER, superTypes);
    }

    @Override
    public VmType vmType() {
      return asType;
    }

    @Override
    public String toString() {
      return asType.name;
    }
  }

  /**
   * A subclass of VmType for types that correspond to a single BaseType, including singletons and
   * compounds.
   */
  static class AsType extends VmType {
    final Named baseType;

    private AsType(VmModule module, String name, BaseType.Named baseType, Vm.Type[] superTypes) {
      super(module, name, superTypes);
      this.baseType = baseType;
    }

    @Override
    boolean contains(BaseType type) {
      return type == baseType;
    }
  }

  /** A subclass for non-compositional BaseTypes that provide their own Value implementation. */
  public static class NonCompositional extends Named {

    /**
     * A RefVar template with this base type.
     *
     * <p>Null for {@link Core#VARRAY}, since RefVars with that base type must also specify a
     * FrameLayout.
     */
    final Template.RefVar asRefVar;

    final Class<?> javaType;

    NonCompositional(
        VmModule module, String name, Class<?> javaType, long sortOrder, Vm.Type... superTypes) {
      super(module, name, -1, sortOrder, superTypes);
      assert (sortOrder == SORT_ORDER_ARRAY)
          ? javaType == null
          : Value.class.isAssignableFrom(javaType);
      this.javaType = javaType;
      asRefVar = (sortOrder == SORT_ORDER_ARRAY) ? null : new Template.RefVar(0, this);
    }

    public NonCompositional(
        VmModule module, String name, Class<?> javaType, Vm.Type... superTypes) {
      this(module, name, javaType, ALLOC_NEW_SORT_ORDER, superTypes);
    }
  }

  /**
   * A subclass of BaseType used for stack entries. These are not visible as values in the language,
   * but representing them as Values with distinct BaseTypes enables us to reuse the mechanisms for
   * encoding (and later serializing) Values.
   */
  public abstract static class StackEntryType extends BaseType {
    /** Create a StackEntryType that saves the values of {@code numLocals} local variables. */
    StackEntryType(int numLocals) {
      super(numLocals);
      // All StackEntryTypes are compositional.
      assert numLocals >= 0;
    }

    /** Returns the name of a captured local variable. */
    abstract String localName(int i);

    /** If this stack entry represents an in-progress call, the function called; otherwise null. */
    VmFunction called() {
      return null;
    }

    /**
     * Should only be called if {@link #called} is non-null, with an {@code entry} whose baseType is
     * {@code this}. Resumes the in-progress computation using the return values in {@code tstate}.
     * Returns with either results in tstate or an unwind in progress.
     *
     * <p>{@code results} and {@code mMemo} are the same arguments that were passed to the
     * interrupted {@link MethodImpl#execute} call.
     */
    void resume(TState tstate, @RC.In Value entry, ResultsInfo results, MethodMemo mMemo) {
      throw new AssertionError();
    }

    @Override
    public VmType vmType() {
      // Stack entries aren't Retrospect-language values, so this should never be called.
      throw new AssertionError();
    }

    @Override
    public String toString(IntFunction<Object> elements) {
      if (isSingleton()) {
        return "⟦" + this + "⟧";
      }
      return StringUtil.joinElements(
          "⟦" + this + " ∥ ", "⟧", size(), i -> localName(i) + "=" + elements.apply(i));
    }
  }

  /**
   * A subclass used for statically-created StackEntryTypes (i.e. those for built-in methods and
   * errors, not for user-defined methods).
   */
  public static class SimpleStackEntryType extends StackEntryType {
    private final String msg;
    private final String[] localNames;

    SimpleStackEntryType(String msg, String... localNames) {
      super(localNames.length);
      this.msg = msg;
      this.localNames = localNames;
    }

    @Override
    String localName(int i) {
      return localNames[i];
    }

    @Override
    public String toString() {
      return msg;
    }

    /**
     * Convenience method that pushes an instance of this entry on the unwind stack. Only for use
     * with entries that have no locals.
     */
    void pushUnwind(TState tstate) {
      tstate.pushUnwind(asValue());
    }

    /**
     * Convenience method that pushes an instance of this entry on the unwind stack, given a Value
     * for each of its locals.
     */
    void pushUnwind(TState tstate, @RC.In Value... args) {
      assert args.length == size();
      tstate.pushUnwind(CompoundValue.of(tstate, this, i -> args[i]));
    }

    /**
     * Convenience method that pushes an instance of this entry on the unwind stack, given a Java
     * string for each of its locals.
     */
    void pushUnwind(TState tstate, String... args) {
      assert args.length == size();
      tstate.pushUnwind(CompoundValue.of(tstate, this, i -> new StringValue(tstate, args[i])));
    }
  }

  /**
   * A subclass of StackEntryType used only at the top of a stack that has been suspended to wait
   * for some asynchronous event.
   */
  abstract static class BlockingEntryType extends SimpleStackEntryType {
    BlockingEntryType(String msg, String... localNames) {
      super(msg, localNames);
    }

    @Override
    abstract VmFunction called();

    /**
     * Called after a thread has called {@link TState#startBlock} passing a blockingEntry with this
     * baseType. This method is responsible for arranging that {@link RThread#resumeAsync} is called
     * when {@code thread} can be resumed.
     */
    abstract void suspended(TState tstate, Value entry, RThread thread);

    /**
     * Called when the asynchronous event has completed, with any return values in {@code tstate}.
     * The baseType of {@code entry} is {@code this}.
     *
     * <p>{@code results} and {@code mMemo} are the same arguments that were passed to the {@link
     * TState#startBlock} call.
     */
    @Override
    void resume(TState tstate, @RC.In Value entry, ResultsInfo results, MethodMemo mMemo) {
      assert entry.baseType() == this;
      tstate.dropValue(entry);
      // Just return the resume value(s)
    }
  }
}
