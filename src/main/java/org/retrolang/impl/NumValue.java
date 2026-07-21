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

import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import org.retrolang.code.Op;
import org.retrolang.util.SizeOf;

/** Implementations of Value for ints and doubles. */
public abstract class NumValue extends RefCounted implements Value {

  public static final NumValue.I ZERO;
  public static final NumValue.I ONE;
  public static final NumValue.I NEGATIVE_ONE;

  public static final NumValue.D POSITIVE_INFINITY =
      new NumValue.D(Allocator.UNCOUNTED, Double.POSITIVE_INFINITY);
  public static final NumValue.D NEGATIVE_INFINITY =
      new NumValue.D(Allocator.UNCOUNTED, Double.NEGATIVE_INFINITY);

  private static final int MIN_CACHED_INT = -1;
  private static final int MAX_CACHED_INT = 255;

  /** We preallocate uncounted NumValues for integers between -1 and 255 inclusive. */
  private static final NumValue.I[] intCache;

  static {
    intCache = new NumValue.I[MAX_CACHED_INT - MIN_CACHED_INT + 1];
    Arrays.setAll(intCache, i -> new NumValue.I(Allocator.UNCOUNTED, i + MIN_CACHED_INT));
    ZERO = fromCache(0);
    ONE = fromCache(1);
    NEGATIVE_ONE = fromCache(-1);
  }

  static final MethodHandle AS_DOUBLE = Handle.forMethod(NumValue.class, "asDouble", Value.class);

  static final Op OF_INT_OP =
      RcOp.forRcMethod(NumValue.class, "of", int.class, Allocator.class).build();
  static final Op OF_DOUBLE_OP =
      RcOp.forRcMethod(NumValue.class, "of", double.class, Allocator.class).build();
  static final Op AS_DOUBLE_OP = RcOp.forRcMethod(NumValue.class, "asDouble", Value.class).build();
  static final Op IS_INT_OP = RcOp.forRcMethod(NumValue.class, "isInt", Value.class).build();
  static final Op IS_UINT8_OP = RcOp.forRcMethod(NumValue.class, "isUint8", Value.class).build();
  static final Op AS_INT_OP = RcOp.forRcMethod(NumValue.class, "asInt", Value.class).build();

  /**
   * Returns a NumValue.I for the given int, which must be between MIN_CACHED_INT and MAX_CACHED_INT
   * inclusive.
   */
  private static NumValue.I fromCache(int i) {
    return intCache[i - MIN_CACHED_INT];
  }

  /** Returns a NumValue.I for the given int. */
  @RC.Out
  public static NumValue.I of(int i, Allocator allocator) {
    if (i >= MIN_CACHED_INT && i <= MAX_CACHED_INT) {
      return fromCache(i);
    } else {
      return new NumValue.I(allocator, i);
    }
  }

  /** Returns a NumValue.D for the given double, which must not be NaN. */
  @RC.Out
  public static NumValue.D of(double d, Allocator allocator) {
    assert !Double.isNaN(d);
    return new NumValue.D(allocator, d);
  }

  /** Returns a NumValue for the given Number, which must not be NaN. */
  @RC.Out
  public static NumValue of(Number number, Allocator allocator) {
    if (number instanceof Double || number instanceof Float) {
      return of(number.doubleValue(), allocator);
    } else {
      return of(number.intValue(), allocator);
    }
  }

  /** Returns a NumValue.D for the given double, or None if the double is NaN. */
  @RC.Out
  public static Value orNan(double d, Allocator allocator) {
    return Double.isNaN(d) ? Core.NONE : new NumValue.D(allocator, d);
  }

  /** Returns true if the given value is a NumValue for an int. */
  public static boolean isInt(Value v) {
    assert !(v instanceof RValue);
    return (v instanceof I) || (v instanceof D nd && nd.isInt());
  }

  /** Returns true if the given value is a NumValue for an int >= 0. */
  public static boolean isNonNegativeInt(Value v) {
    if (v instanceof I ni) {
      return ni.value >= 0;
    } else if (v instanceof D nd) {
      double d = nd.value;
      int i = (int) d;
      return i == d && i >= 0;
    }
    assert !(v instanceof RValue);
    return false;
  }

  /** Returns true if the given value is a NumValue for an int in 0..255. */
  public static boolean isUint8(Value v) {
    return isBoundedInt(v, 0, 255);
  }

  /** Returns true if the given value is a NumValue for an int in min..max. */
  public static boolean isBoundedInt(Value v, int min, int max) {
    int i;
    if (v instanceof I ni) {
      i = ni.value;
    } else if (v instanceof D nd) {
      double d = nd.value;
      i = (int) d;
      if (i != d) {
        return false;
      }
    } else {
      assert !(v instanceof RValue);
      return false;
    }
    return i >= min && i <= max;
  }

  /**
   * Returns the given Value as an int. Should only be called with Values for which {@link #isInt}
   * returns true.
   */
  public static int asInt(Value v) {
    assert isInt(v);
    return toInt(v);
  }

  /**
   * Returns the given Value as NumValue.I. Should only be called with Values for which {@link
   * #isInt} returns true.
   */
  @RC.Out
  public static NumValue.I asInt(Value v, Allocator allocator) {
    assert isInt(v);
    if (v instanceof I ni) {
      if (ni.isRefCountedOrTransient()) {
        return (I) ni.makeStorable((TState) allocator);
      }
      return ni;
    } else {
      return of((int) ((D) v).value, allocator);
    }
  }

  /** Returns the given Value as a transient NumValue.I or throws the specified Err. */
  public static NumValue.I verifyInt(Value v, Err err) throws Err.BuiltinException {
    if (v instanceof I ni) {
      return ni;
    } else if (v instanceof D nd) {
      int i = (int) nd.value;
      if (i == nd.value) {
        return of(i, Allocator.TRANSIENT);
      }
    }
    throw err.asException();
  }

  /**
   * Returns the given Value as a transient NumValue.I or throws an INVALID_ARGUMENT
   * BuiltinException.
   */
  public static NumValue.I verifyBoundedInt(Value v, int min, int max) throws Err.BuiltinException {
    if (v instanceof I ni && ni.value >= min && ni.value <= max) {
      return ni;
    } else if (v instanceof D nd) {
      int i = (int) nd.value;
      if (i == nd.value && i >= min && i <= max) {
        return of(i, Allocator.TRANSIENT);
      }
    }
    throw Err.INVALID_ARGUMENT.asException();
  }

  /**
   * If the given value is a NumValue for an int, returns its value as an int; otherwise returns -1.
   */
  public static int asIntOrMinusOne(Value v) {
    if (v instanceof I ni) {
      return ni.value;
    } else if (v instanceof D nd) {
      int i = (int) nd.value;
      return (i == nd.value) ? i : -1;
    } else {
      assert !(v instanceof RValue);
      return -1;
    }
  }

  /** Given a NumValue, coerces it to an int. */
  public static int toInt(Value v) {
    assert v instanceof NumValue;
    if (v instanceof I ni) {
      return ni.value;
    } else {
      return (int) ((D) v).value;
    }
  }

  /** Returns the given NumValue as a double. */
  public static double asDouble(Value v) {
    assert v instanceof NumValue;
    if (v instanceof I ni) {
      return ni.value;
    } else {
      return ((D) v).value;
    }
  }

  /** Returns the given NumValue as an Integer or Double. */
  public static Number asNumber(Value v) {
    if (v instanceof I ni) {
      return ni.value;
    } else {
      return ((D) v).value;
    }
  }

  /**
   * Returns true if the given Object is a NumValue with the given int value.
   *
   * <p>Equivalent to NumValue.of(i, Allocator.UNBOUNDED).equals(x), but more efficient.
   */
  public static boolean equals(Object x, int i) {
    if (x instanceof I ni) {
      return i == ni.value;
    } else {
      assert !(x instanceof RValue);
      return (x instanceof D nd) && i == nd.value;
    }
  }

  /**
   * Returns true if the given Object is a NumValue with the given double value.
   *
   * <p>Equivalent to NumValue.of(d, Allocator.UNBOUNDED).equals(x), but more efficient.
   */
  public static boolean equals(Object x, double d) {
    if (x instanceof I ni) {
      return d == ni.value;
    } else {
      assert !(x instanceof RValue);
      return (x instanceof D nd) && d == nd.value;
    }
  }

  @Override
  public BaseType baseType() {
    return Core.NUMBER;
  }

  @Override
  @RC.Out
  public NumValue makeStorable(TState tstate) {
    if (unsetTransient()) {
      tstate.recordAlloc(this, this instanceof I ? I.OBJ_SIZE : D.OBJ_SIZE);
    } else {
      addRef();
    }
    return this;
  }

  @Override
  protected long visitRefs(RefVisitor visitor) {
    return (this instanceof I) ? I.OBJ_SIZE : D.OBJ_SIZE;
  }

  NumValue asTransient() {
    if (this instanceof I ni) {
      return new DebugTransientI(ni);
    } else {
      return new DebugTransientD((D) this);
    }
  }

  /** Subclass for ints. */
  public static class I extends NumValue {
    private static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + SizeOf.INT);

    public final int value;

    @RC.Out
    private I(Allocator allocator, int value) {
      this.value = value;
      allocator.recordAlloc(this, OBJ_SIZE);
    }

    /** Only used for tests. */
    @Override
    public boolean equals(Object other) {
      return equals(other, value);
    }

    @Override
    public int hashCode() {
      // Using the same hash values as the corresponding Integer objects should be OK, since we
      // don't expect to mix NumValues and Numbers in the same hashtable.
      return Integer.hashCode(value);
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }
  }

  /** Subclass for doubles. */
  public static class D extends NumValue {
    private static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + SizeOf.DOUBLE);

    public final double value;

    @RC.Out
    private D(Allocator allocator, double value) {
      // Negative zero makes int and double arithmetic irreconcilable, so we don't allow it.
      this.value = isNegativeZero(value) ? 0 : value;
      allocator.recordAlloc(this, OBJ_SIZE);
    }

    boolean isInt() {
      return value == (int) value;
    }

    /** Only used for tests. */
    @Override
    public boolean equals(Object other) {
      return equals(other, value);
    }

    @Override
    public int hashCode() {
      // Using the same hash values as the corresponding Double objects should be OK, since we
      // don't expect to mix NumValues and Numbers in the same hashtable.
      return Double.hashCode(value);
    }

    @Override
    public String toString() {
      int i = (int) value;
      return value == i ? String.valueOf(i) : String.valueOf(value);
    }

    private static final long NEGATIVE_ZERO_BITS = Double.doubleToRawLongBits(-0d);

    private static boolean isNegativeZero(double d) {
      return Double.doubleToRawLongBits(d) == NEGATIVE_ZERO_BITS;
    }
  }

  /**
   * A subclass of NumValue.I used only when VERIFY_TRANSIENT_USE is set in CompoundValue (i.e.
   * while running tests). This subclass retains an (uncounted) pointer to the original NumValue and
   * returns it from {@link #makeStorable}, which ensures that VERIFY_TRANSIENT_USE doesn't cause us
   * to create extra copies of NumValues (making the allocated numbers misleading).
   */
  static class DebugTransientI extends I {
    final I original;

    @RC.Out
    private DebugTransientI(I original) {
      super(Allocator.TRANSIENT, original.value);
      this.original = original;
    }

    @Override
    @RC.Out
    public NumValue makeStorable(TState tstate) {
      original.addRef();
      return original;
    }
  }

  /**
   * A subclass of NumValue.D used only when VERIFY_TRANSIENT_USE is set in CompoundValue; see the
   * comment on {@link DebugTransientI} for details.
   */
  static class DebugTransientD extends D {
    final D original;

    @RC.Out
    private DebugTransientD(D original) {
      super(Allocator.TRANSIENT, original.value);
      this.original = original;
    }

    @Override
    @RC.Out
    public NumValue makeStorable(TState tstate) {
      original.addRef();
      return original;
    }
  }
}
