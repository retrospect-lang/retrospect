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

package org.retrolang.code;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.retrolang.code.CodeValue.Const;
import org.retrolang.util.Bits;

/// A ValueInfo represents information about the possible values of a register or other CodeValue.
/// There are a number of standard ValueInfo implementations, including
///
/// - ANY: the register can have any value consistent with its type
/// - CodeValue.Const: the register can only have this value
/// - Register: the register has the same value as another register
/// - IntRange: the register's value is an int within the specified range
/// - SmallIntSet: the register's value is one of the ints in a given Bits
///
/// ... but CodeBuilder clients can define additional ValueInfo implementations suitable for their
/// domain. All ValueInfo implementations must be immutable.
///
/// All ValueInfos other than Const are assumed to represent more than one possible value, so e.g.
/// `IntRange.of(4, 4)` returns a Const, not a single-element IntRange.
///
/// Operations that combine a ValueInfo with a value (such as [#containsValue] or [#unionConst]) are
/// defined as methods on this interface, but operations that combine two ValueInfos (such as
/// [BinaryOps#containsAll] or [BinaryOps#union]) are defined on the [BinaryOps] class; this makes
/// it easier to ensure that newly-added ValueInfo implementations combine in the expected ways with
/// standard implementations.
public interface ValueInfo {

  /// True if a register with this ValueInfo could have the specified value.
  boolean containsValue(Object value);

  /// True if a register with this ValueInfo could have the specified value.
  default boolean containsConst(Const constInfo) {
    return containsValue(constInfo.value);
  }

  /// Returns a ValueInfo that includes all the values allowed by this ValueInfo, as well as the
  /// given constant. May return ANY if no more precise ValueInfo is suitable.
  ValueInfo unionConst(Const constInfo);

  /// Returns a ValueInfo that includes all the values allowed by this ValueInfo except the given
  /// constant. May return this if no more precise ValueInfo is suitable.
  default ValueInfo removeConst(Const constInfo) {
    return this;
  }

  /// Should only be called on a ValueInfo that represents numeric values; returns the minimum value
  /// allowed, or null if there is no lower bound (other than that of the type).
  default Number minimum() {
    throw new AssertionError();
  }

  /// Should only be called on a ValueInfo that represents numeric values; returns the maximum value
  /// allowed, or null if there is no upper bound (other than that of the type).
  default Number maximum() {
    throw new AssertionError();
  }

  /// Returns true if any difference between `this` and `other` is too insignificant to merit
  /// re-analysis of the affected blocks.
  ///
  /// The default implementation returns `this.equals(other)`. Only ValueInfo implementations that
  /// have large numbers of almost-identical instances need worry about overriding this method. See
  /// docs/code.md#the-valueinfo-interface-and-codevalue-simplification for more details.
  default boolean isCloseEnoughTo(ValueInfo other) {
    return equals(other);
  }

  /// A ValueInfo indicating that nothing is known about this register beyond the restrictions of
  /// its type.
  ValueInfo ANY =
      new ValueInfo() {
        @Override
        public boolean containsValue(Object value) {
          return true;
        }

        @Override
        public ValueInfo unionConst(Const constInfo) {
          return this;
        }

        @Override
        public @Nullable Number minimum() {
          return null;
        }

        @Override
        public @Nullable Number maximum() {
          return null;
        }

        @Override
        public String toString() {
          return "any";
        }
      };

  /// A BinaryOps implements operations like union, intersection, mightIntersect, and containsAll
  /// that combine two ValueInfos. The base BinaryOps class implements those for the standard
  /// ValueInfo types, but if you define additional implementations of ValueInfo you'll need to
  /// define a BinaryOps subclass that handles combinations of your new types (and just calls super
  /// when given two of the standard types).
  ///
  /// None of these methods accept Registers as ValueInfos, because they cannot be interpreted in
  /// isolation; callers are expected to have first resolved copied registers to the ValueInfo for
  /// the copied Register.
  ///
  /// Note that for each operation there is a public final method that clients should call (e.g.
  /// [#union]) and a protected (non-final) method that subclasses should override; generally the
  /// public method handles a number of trivial cases, so overrides don't need to check for them. In
  /// the case of union the public method distinguishes three separate cases ([#unionConsts],
  /// [#unionImpl], and [ValueInfo#unionConst]) so that the overrides can be simpler.
  class BinaryOps {
    /// Returns a ValueInfo that is true whenever at least one of `x` or `y` is true. Neither
    /// ValueInfo is a Register.
    public final ValueInfo union(ValueInfo x, ValueInfo y) {
      assert !(x == null || x instanceof Register || y == null || y instanceof Register);
      if (x == y || x == ANY || y == CodeValue.NONE) {
        return x;
      } else if (x == CodeValue.NONE || y == ANY || x.equals(y)) {
        return y;
      } else if (x instanceof Const cx) {
        if (y instanceof Const cy) {
          return unionConsts(cx.value, cy.value);
        }
        return y.unionConst(cx);
      } else if (y instanceof Const cy) {
        return x.unionConst(cy);
      } else {
        return unionImpl(x, y);
      }
    }

    /// Returns a ValueInfo that is true whenever at least one of `x` or `y` is true. Neither
    /// ValueInfo is NONE, ANY, a Const, or a Register.
    protected ValueInfo unionImpl(ValueInfo x, ValueInfo y) {
      // We handle combinations of IntRange and/or SmallIntSet; everything else is ANY.
      if (x instanceof IntRange xRange) {
        if (y instanceof IntRange yRange) {
          return xRange.union(yRange);
        } else if (y instanceof SmallIntSet ySet) {
          return xRange.union(ySet);
        }
      } else if (x instanceof SmallIntSet xSet) {
        if (y instanceof IntRange yRange) {
          return yRange.union(xSet);
        } else if (y instanceof SmallIntSet ySet) {
          return xSet.applyOp(Bits.Op.UNION, ySet);
        }
      }
      return ANY;
    }

    /// Returns a ValueInfo that contains the two given (non-equal) values.
    protected ValueInfo unionConsts(Object x, Object y) {
      // If they're both ints we can return a SmallIntSet or IntRange; otherwise we fall back on
      // ANY.
      if (x instanceof Integer && y instanceof Integer) {
        int xi = (Integer) x;
        int yi = (Integer) y;
        if (SmallIntSet.inRange(xi) && SmallIntSet.inRange(yi)) {
          return new SmallIntSet(Bits.of(xi).set(yi));
        } else {
          return new IntRange(Math.min(xi, yi), Math.max(xi, yi));
        }
      }
      return ANY;
    }

    /// Returns a ValueInfo that is true whenever both `x` and `y` are true. Neither ValueInfo is a
    /// Register.
    public final ValueInfo intersection(ValueInfo x, ValueInfo y) {
      assert !(x == null || x instanceof Register || y == null || y instanceof Register);
      if (x == y || y == ANY || x == CodeValue.NONE) {
        return x;
      } else if (y == CodeValue.NONE || x == ANY || x.equals(y)) {
        return y;
      } else if (x instanceof Const cx) {
        return y.containsConst(cx) ? cx : CodeValue.NONE;
      } else if (y instanceof Const cy) {
        return x.containsConst(cy) ? cy : CodeValue.NONE;
      } else {
        return intersectionImpl(x, y);
      }
    }

    /// Returns a ValueInfo that is true whenever both `x` and `y` are true. Neither ValueInfo is
    /// NONE, ANY, a Const, or a Register.
    public ValueInfo intersectionImpl(ValueInfo x, ValueInfo y) {
      // We handle combinations of IntRange and/or SmallIntSet.
      if (x instanceof IntRange xRange) {
        if (y instanceof IntRange yRange) {
          return xRange.intersection(yRange);
        } else if (y instanceof SmallIntSet ySet) {
          return ySet.intersection(xRange);
        }
      } else if (x instanceof SmallIntSet xSet) {
        if (y instanceof IntRange yRange) {
          return xSet.intersection(yRange);
        } else if (y instanceof SmallIntSet ySet) {
          return xSet.applyOp(Bits.Op.INTERSECTION, ySet);
        }
      }
      // It would be correct to return either x or y, but I don't expect this case to happen
      // so for now I'd rather treat it as a bug.
      throw new AssertionError();
    }

    /// Returns false if there are no values compatible with both `x` and `y`. Neither ValueInfo is
    /// a Register.
    ///
    /// This method may return true if the correct answer is infeasible to determine, so while a
    /// return value of false means that the intersection is definitely empty, a return value of
    /// true does not guarantee a non-empty intersection.
    public final boolean mightIntersect(ValueInfo x, ValueInfo y) {
      assert !(x == null || x instanceof Register || y == null || y instanceof Register);
      if (x == CodeValue.NONE || y == CodeValue.NONE) {
        return false;
      } else if (x == ANY || y == ANY) {
        return true;
      } else if (x instanceof Const cx) {
        return y.containsConst(cx);
      } else if (y instanceof Const cy) {
        return x.containsConst(cy);
      } else {
        return mightIntersectImpl(x, y);
      }
    }

    /// Returns false if there are no values compatible with both `x` and `y`. Neither ValueInfo is
    /// NONE, ANY, a Const, or a Register.
    public boolean mightIntersectImpl(ValueInfo x, ValueInfo y) {
      // We handle combinations of IntRange and/or SmallIntSet.
      if (x instanceof IntRange xRange) {
        if (y instanceof IntRange yRange) {
          return xRange.intersects(yRange);
        } else if (y instanceof SmallIntSet ySet) {
          return ySet.intersects(xRange);
        }
      } else if (x instanceof SmallIntSet xSet) {
        if (y instanceof IntRange yRange) {
          return xSet.intersects(yRange);
        } else if (y instanceof SmallIntSet ySet) {
          return xSet.bits.testAny(ySet.bits);
        }
      }
      return true;
    }

    /// Returns true if every value compatible with `y` is also compatible with `x`. Neither
    /// ValueInfo is a Register.
    public final boolean containsAll(ValueInfo x, ValueInfo y) {
      assert !(x == null || x instanceof Register || y == null || y instanceof Register);
      if (x == y || x == ANY || y == CodeValue.NONE) {
        return true;
      } else if (x == CodeValue.NONE || y == ANY) {
        return false;
      } else if (y instanceof Const cy) {
        return x.containsConst(cy);
      } else if (x instanceof Const) {
        // Non-Const ValueInfos are expected to always include more than one element, e.g. we never
        // create a SmallIntSet or IntRange that only includes a single element.
        return false;
      } else {
        return x.equals(y) || containsAllImpl(x, y);
      }
    }

    /// Returns true if every value compatible with `y` is also compatible with `x`. Neither
    /// ValueInfo is NONE, ANY, a Const, or a Register.
    protected boolean containsAllImpl(ValueInfo x, ValueInfo y) {
      if (x instanceof IntRange xRange) {
        if (y instanceof IntRange yRange) {
          return xRange.containsAll(yRange);
        } else if (y instanceof SmallIntSet ySet) {
          return xRange.containsAll(ySet);
        }
      } else if ((x instanceof SmallIntSet xSet) && (y instanceof SmallIntSet ySet)) {
        return xSet.bits.testAll(ySet.bits);
      }
      // If an IntRange can be represented by a SmallIntSet we use a SmallIntSet
      // (e.g. IntRange.of(3, 5) returns a SmallIntSet), so it is not possible for
      // smallIntSet.containsAll(intRange) to be true.
      return false;
    }
  }

  /// A simple ValueInfo for integers in a range.
  class IntRange implements ValueInfo {
    /// A ValueInfo for integers in the range 0..255.
    public static final ValueInfo UINT8 = IntRange.of(0, 255);

    /// The (inclusive) lower bound.
    public final int min;

    /// The (inclusive) upper bound.
    public final int max;

    private IntRange(int min, int max) {
      // We don't use IntRange when a Const or SmallIntSet would do
      assert min < max && !(SmallIntSet.inRange(min) && SmallIntSet.inRange(max));
      this.min = min;
      this.max = max;
    }

    /// Returns a ValueInfo for integers between `min` and `max` inclusive. Chooses whichever
    /// representation is most appropriate, among NONE, Const, SmallIntSet, IntRange, or ANY.
    public static ValueInfo of(int min, int max) {
      if (min > max) {
        return CodeValue.NONE;
      } else if (min == max) {
        return CodeValue.of(min);
      } else if (min >= 0 && max <= SmallIntSet.LIMIT) {
        return new SmallIntSet(Bits.forRange(min, max));
      } else if (min == Integer.MIN_VALUE && max == Integer.MAX_VALUE) {
        return ANY;
      } else {
        return new IntRange(min, max);
      }
    }

    /// Returns a ValueInfo for integers less than (or less than or equal to, if `inclusive`) the
    /// given number.
    public static ValueInfo lessThan(Number max, boolean inclusive) {
      long bound = bound(max, true, inclusive);
      if (bound < Integer.MIN_VALUE) {
        return CodeValue.NONE;
      } else {
        return of(Integer.MIN_VALUE, (int) Math.min(bound, Integer.MAX_VALUE));
      }
    }

    /// Returns a ValueInfo for integers greater than (or greater than or equal to, if `inclusive`)
    /// the given number.
    public static ValueInfo greaterThan(Number min, boolean inclusive) {
      long bound = bound(min, false, inclusive);
      if (bound > Integer.MAX_VALUE) {
        return CodeValue.NONE;
      } else {
        return of((int) Math.max(bound, Integer.MIN_VALUE), Integer.MAX_VALUE);
      }
    }

    /// Interpret a Number as an integer bound. Handles cases where `value` is non-integral and/or
    /// beyond the bounds of an int.
    public static long bound(Number value, boolean upper, boolean inclusive) {
      long result;
      if (value instanceof Double || value instanceof Float) {
        double d = value.doubleValue();
        result = (long) (upper ? Math.floor(d) : Math.ceil(d));
        if (result != d) {
          // Even if this is an exclusive bound we don't need to adjust it further.
          return result;
        }
      } else {
        result = value.longValue();
      }
      if (!inclusive) {
        try {
          result = Math.addExact(result, (upper ? -1 : 1));
        } catch (ArithmeticException e) {
          // If that overflowed we're OK without the adjustment -- we're far outside the bounds of
          // an int so it isn't going to matter.
        }
      }
      return result;
    }

    private boolean contains(int i) {
      return (min <= i) && (max >= i);
    }

    @Override
    public boolean containsValue(Object value) {
      return (value instanceof Integer) && contains((int) value);
    }

    boolean containsAll(IntRange other) {
      return (other.min >= min) && (other.max <= max);
    }

    boolean containsAll(SmallIntSet other) {
      return (other.bits.min() >= min) && (other.bits.max() <= max);
    }

    private ValueInfo unionInt(int i) {
      if (i < min) {
        return of(i, max);
      } else if (i > max) {
        return of(min, i);
      } else {
        return this;
      }
    }

    @Override
    public ValueInfo unionConst(Const constInfo) {
      return (constInfo.value instanceof Integer i) ? unionInt(i) : ANY;
    }

    @Override
    public ValueInfo removeConst(Const constInfo) {
      // If you remove the upper or lower bound we can shrink the range -- otherwise we leave it
      // unchanged.
      if (constInfo.value instanceof Integer) {
        int i = (Integer) constInfo.value;
        if (i == min) {
          return of(min + 1, max);
        } else if (i == max) {
          return of(min, max - 1);
        }
      }
      return this;
    }

    @Override
    public @Nullable Number minimum() {
      return (min == Integer.MIN_VALUE) ? null : min;
    }

    @Override
    public @Nullable Number maximum() {
      return (max == Integer.MAX_VALUE) ? null : max;
    }

    /// Returns the number of ints in this range.
    private long longSize() {
      return max - (long) min + 1;
    }

    /// An arbitrarily-chosen threshold for [#isCloseEnoughTo].
    private static final int SIGNIFICANT_BOUND = 8;

    /// Returns the closest element of [-SIGNIFICANT\_BOUND, SIGNIFICANT\_BOUND].
    private static int clamp(int bound) {
      return Math.min(Math.max(bound, -SIGNIFICANT_BOUND), SIGNIFICANT_BOUND);
    }

    @Override
    public boolean isCloseEnoughTo(ValueInfo info) {
      if (equals(info)) {
        return true;
      } else if (!(info instanceof IntRange)) {
        return false;
      }
      IntRange other = (IntRange) info;
      // If either is smallish, the difference matters
      if (longSize() < SIGNIFICANT_BOUND || other.longSize() < SIGNIFICANT_BOUND) {
        return false;
      }
      // If they include different subsets of [-SIGNIFICANT_BOUND, SIGNIFICANT_BOUND] the difference
      // matters
      if (clamp(min) != clamp(other.min) || clamp(max) != clamp(other.max)) {
        return false;
      }
      // Otherwise it's probably unimportant
      return true;
    }

    boolean intersects(IntRange other) {
      return (other.min <= max) && (other.max >= min);
    }

    ValueInfo intersection(IntRange other) {
      int newMin = Math.max(min, other.min);
      int newMax = Math.min(max, other.max);
      if ((newMin == min) && (newMax == max)) {
        return this;
      } else if ((newMin == other.min) && (newMax == other.max)) {
        return other;
      } else {
        return of(newMin, newMax);
      }
    }

    ValueInfo union(IntRange other) {
      int newMin = Math.min(min, other.min);
      int newMax = Math.max(max, other.max);
      if ((newMin == min) && (newMax == max)) {
        return this;
      } else if ((newMin == other.min) && (newMax == other.max)) {
        return other;
      } else {
        return of(newMin, newMax);
      }
    }

    ValueInfo union(SmallIntSet info) {
      int newMin = Math.min(min, info.bits.min());
      int newMax = Math.max(max, info.bits.max());
      if ((newMin == min) && (newMax == max)) {
        return this;
      } else {
        return of(newMin, newMax);
      }
    }

    @Override
    public int hashCode() {
      return Objects.hash(min, max);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof IntRange)) {
        return false;
      }
      IntRange other = (IntRange) obj;
      return (min == other.min) && (max == other.max);
    }

    @Override
    public String toString() {
      return String.format(
          "∈%s..%s", (min == Integer.MIN_VALUE ? "" : min), (max == Integer.MAX_VALUE ? "" : max));
    }
  }

  /// A simple ValueInfo for integers in 0..31, represented as a [Bits].
  class SmallIntSet implements ValueInfo {
    static final int LIMIT = 31;

    /// The ints included in this ValueInfo.
    public final Bits bits;

    /// Returns true if the given int is in the range representable by a SmallIntSet.
    static boolean inRange(int i) {
      return i >= 0 && i <= LIMIT;
    }

    /// Creates a SmallIntSet with the given Bits, which must be within range and have at least two
    /// elements.
    public SmallIntSet(Bits bits) {
      assert bits.count() > 1 && bits.max() <= LIMIT;
      this.bits = bits;
    }

    /// Returns a ValueInfo for the given Bits, which must be in range for a SmallIntSet.
    private ValueInfo withNewBits(Bits newBits) {
      if (newBits.equals(bits)) {
        return this;
      } else if (newBits.count() > 1) {
        return new SmallIntSet(newBits);
      } else {
        return newBits.isEmpty() ? CodeValue.NONE : CodeValue.of(newBits.min());
      }
    }

    /// Combine another SmallIntSet with this one, using the specified [Bits.Op].
    private ValueInfo applyOp(Bits.Op op, SmallIntSet other) {
      Bits newBits = op.apply(bits, other.bits);
      return newBits.equals(bits) ? this : other.withNewBits(newBits);
    }

    boolean intersects(IntRange other) {
      // Find the smallest element of bits that's within other's lower bound
      int i = bits.nextSetBit(Math.max(other.min, 0));
      // It must exist, and be within the upper bound
      return (i >= 0) && (i <= other.max);
    }

    ValueInfo intersection(IntRange range) {
      Bits newBits = bits;
      if (range.max < LIMIT) {
        newBits = newBits.clearGreaterThanOrEq(Math.max(range.max + 1, 0));
      }
      if (range.min > 0) {
        newBits = newBits.clearLessThan(range.min);
      }
      return withNewBits(newBits);
    }

    @Override
    public ValueInfo unionConst(Const constInfo) {
      if (constInfo.value instanceof Integer) {
        int i = (Integer) constInfo.value;
        if (i < 0) {
          return new IntRange(i, bits.max());
        } else if (i > LIMIT) {
          return new IntRange(bits.min(), i);
        } else {
          return withNewBits(bits.set(i));
        }
      }
      return ANY;
    }

    @Override
    public ValueInfo removeConst(Const constInfo) {
      if (constInfo.value instanceof Integer) {
        int i = (Integer) constInfo.value;
        if (i >= 0) {
          return withNewBits(bits.clear(i));
        }
      }
      return this;
    }

    @Override
    public Number minimum() {
      return bits.min();
    }

    @Override
    public Number maximum() {
      return bits.max();
    }

    @Override
    public boolean containsValue(Object value) {
      return (value instanceof Integer i) && bits.test(i);
    }

    @Override
    public int hashCode() {
      return bits.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return (obj instanceof SmallIntSet other) && bits.equals(other.bits);
    }

    @Override
    public String toString() {
      return "∈" + bits;
    }
  }
}
