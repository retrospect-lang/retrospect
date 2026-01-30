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
import java.util.stream.IntStream;
import org.retrolang.code.Op;
import org.retrolang.util.ArrayUtil;

/**
 * All VM objects that represent Retrospect-language values implement Value. Other objects may also
 * implement Value for convenience, e.g. the objects that represent stack entries.
 *
 * <p>Equality of Values is generally only defined within Retrospect. Some implementations of Value
 * define {@code equals()} to make testing easier, but non-test code should not be calling it; nor
 * should Values be used as keys in a hashtable.
 */
public interface Value {
  /**
   * Every Value has a BaseType. If this is a Retrospect-language value it will be a BaseType.Named.
   */
  BaseType baseType();

  /**
   * The number of elements in this Value; only defined if this is an array Value or has a
   * compositional BaseType.
   */
  default int numElements() {
    // Overridden by Frame, so if we're here this must have a compositional basetype.
    return baseType().size();
  }

  /**
   * Returns the specified component or array element. The behavior of this method is only defined
   * if {@code index} is non-negative and less than {@link #numElements}.
   * </ul>
   */
  @RC.Out
  default Value element(int index) {
    throw new AssertionError();
  }

  /**
   * Returns the specified component or array element, <i>not</i> reference-counted and possibly
   * using a short-term-only representation.
   *
   * <p>See {@link #element} for preconditions and {@link RC.Transient} for restrictions on the use
   * of this method's result.
   */
  @RC.Transient
  default Value peekElement(int index) {
    throw new AssertionError();
  }

  /** Returns the specified element as an int; {@code NumValue#isInt} must be true of it. */
  default int elementAsInt(int index) {
    return NumValue.asInt(peekElement(index));
  }

  /** Returns the specified element as an int if {@code NumValue#isInt} is true, otherwise -1. */
  default int elementAsIntOrMinusOne(int index) {
    return NumValue.asIntOrMinusOne(peekElement(index));
  }

  /**
   * Returns a copy of this Value with the specified element replaced. The behavior of this method
   * is only defined if {@code element(index)} is defined.
   */
  @RC.Out
  @RC.In
  default Value replaceElement(TState tstate, int index, @RC.In Value newElement) {
    throw new AssertionError();
  }

  /** True if this Value is contained in the given type. */
  default Condition isa(VmType type) {
    return Condition.of(type.contains(baseType()));
  }

  /** True if this Value has the given base type. */
  default Condition isa(BaseType type) {
    return Condition.of(baseType() == type);
  }

  /** True if this Value is the given singleton. */
  default Condition is(Singleton singleton) {
    assert !(this instanceof RValue);
    return Condition.of(this == singleton);
  }

  /** True if this Value is an array with the given length. */
  default Condition isArrayOfLength(int length) {
    return Condition.of(baseType().isArray() && numElements() == length);
  }

  @CanIgnoreReturnValue
  public static Value addRef(Value v) {
    RefCounted.addRef(v);
    return v;
  }

  /**
   * Given a Value returned by an {@link RC.Transient} method, returns an equal Value without any
   * restrictions; {@code peekElement(i).makeStorable()} is equivalent to {@code element(i)}.
   */
  @RC.Out
  default Value makeStorable(TState tstate) {
    // Actually transient values will override this default.
    return addRef(this);
  }

  /** If this Value is represented by a Frame, returns its layout; otherwise returns null. */
  default FrameLayout layout() {
    return null;
  }

  /**
   * If {@code v} is a Frame that has been replaced, returns its replacement; otherwise returns
   * {@code v}.
   */
  @RC.Out
  static Value latest(@RC.In Value v) {
    return (v instanceof Frame f) ? Frame.latest(f) : v;
  }

  Op FROM_ARRAY_OP = RcOp.forRcMethod(Value.class, "fromArray", Object[].class, int.class).build();

  /** Returns the specified array element as a Value. */
  static Value fromArray(Object[] array, int index) {
    Value v = (Value) array[index];
    Frame.Replacement replacement = (v instanceof Frame f) ? Frame.getReplacement(f) : null;
    if (replacement == null) {
      return v;
    }
    Frame result = replacement.newFrame;
    if (replacement.isComplete()) {
      // This frame's replacement is visible to everyone, so we can update
      // the source to point to the replacement.
      TState tstate = TState.get();
      if (tstate == null || tstate.tracker() == null) {
        // This thread isn't bound to a ResourceTracker (probably it's just trying to toString()
        // a Value it encountered somewhere), so it's not allowed to modify anything.
        return result;
      }
      Object prev =
          ArrayUtil.OBJECT_ARRAY_ELEMENT.compareAndExchangeRelease(array, index, v, result);
      if (prev == v) {
        result.addRef();
        tstate.dropReference((Frame) v);
      }
      // If the compareAndExchange fails, we raced with someone else (who was probably making
      // the same update); it's safe to just assume that they did the right thing (the worst
      // case is that they replaced it with an older version than the one we have, but if
      // that happened it will get sorted out next time).
    }
    return result;
  }

  /**
   * Returns true if the first {@code size} elements of {@code values} are instances of Value, and
   * any remaining elements are null. Used only for assertions.
   */
  static boolean containsValues(Object[] values, int size) {
    return IntStream.range(0, size).allMatch(i -> values[i] instanceof Value)
        && IntStream.range(size, values.length).allMatch(i -> values[i] == null);
  }

  /**
   * Returns true if the first {@code numElements} elements of {@code v1} and {@code v2} are equals.
   */
  static boolean equalElements(Value v1, Value v2, int numElements) {
    for (int i = 0; i < numElements; i++) {
      if (!v1.peekElement(i).equals(v2.peekElement(i))) {
        return false;
      }
    }
    return true;
  }

  /** Returns this Value as a transient Integer or throws the specified Err. */
  @RC.Out
  default Value verifyInt(Err err) throws Err.BuiltinException {
    return NumValue.verifyInt(this, err);
  }

  /**
   * Values aren't generally required to be hashable, but Templates are, and constant Templates
   * contain uncounted values, so any uncounted value must be hashable. In other cases throwing an
   * AssertionError with this message is appropriate.
   */
  static final String VALUES_ARENT_HASHABLE = "Values aren't hashable.";

  /**
   * A base class for implementations of Value returned from {@link RC.Transient} methods that
   * should never be stored.
   */
  abstract class NotStorable implements Value {}
}
