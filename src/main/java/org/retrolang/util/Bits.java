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

package org.retrolang.util;

import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/// A Bits represents an immutable, finite set of non-negative integers.
///
/// They are designed to use minimal memory: as cheap as Integers when the maximum element is less
/// than 32, and always less than a BitSet with the same contents.
public abstract class Bits implements IntPredicate, Iterable<Integer> {

  /// We define two subclasses (Small and Large), and that's it.
  private Bits() {}

  /// A Bits value containing no integers.
  public static final Bits EMPTY;

  /// Returns true if this Bits contains no integers.
  @SuppressWarnings("ReferenceEquality")
  public boolean isEmpty() {
    // We canonicalize small Bits, so this is trivial.
    // If we didn't do that we'd want
    //     (this instanceof Small) && ((Small) this).bits == 0
    return this == EMPTY;
  }

  /// Returns a Bits containing only the given integer.
  ///
  /// `element` must be non-negative.
  public static Bits of(int element) {
    Preconditions.checkArgument(element >= 0);
    if (element < Integer.SIZE) {
      return Small.make(1 << element);
    } else {
      return forRange(element, element);
    }
  }

  /// Returns a Bits containing all integers greater than or equal to min and less than or equal to
  /// max.
  ///
  /// `min` must be non-negative.
  public static Bits forRange(int min, int max) {
    Preconditions.checkArgument(min >= 0);
    if (min > max) {
      return EMPTY;
    } else if (max < Integer.SIZE) {
      int bits = intBitsGreaterOrEq(min) & ~intBitsGreaterOrEq(max + 1);
      return Small.make(bits);
    } else {
      return new Large(setLongsToRange(min, max, null));
    }
  }

  /// Returns a Bits containing the bits that are set in the given int, i.e. the result contains `i`
  /// if `i < 32 && (bits & (1 << i)) != 0`.
  public static Bits fromInt(int bits) {
    return Small.make(bits);
  }

  /// Returns a Bits containing 0 if `contains0` is true, otherwise EMPTY.
  public static Bits fromBooleans(boolean contains0) {
    return Small.make(contains0 ? 1 : 0);
  }

  /// Returns a Bits containing 0 if `contains0` is true and 1 if `contains1` is true.
  public static Bits fromBooleans(boolean contains0, boolean contains1) {
    return Small.make((contains0 ? 1 : 0) + (contains1 ? 2 : 0));
  }

  /// Returns a Bits containing `i` if `contains[i]` is true.
  public static Bits fromBooleans(boolean... contains) {
    return fromPredicate(contains.length - 1, i -> contains[i]);
  }

  /// Returns a Bits containing the values less than or equal to `max` for which the given
  /// IntPredicate returns true.
  public static Bits fromPredicate(int max, IntPredicate include) {
    if (include instanceof Bits bits && max >= 0) {
      return bits.clearGreaterThanOrEq(max + 1);
    }
    // Reduce max until we find the highest set bit.
    for (; ; max--) {
      if (max < 0) {
        return EMPTY;
      } else if (include.test(max)) {
        break;
      }
    }
    if (max < Integer.SIZE) {
      int bits = 1;
      for (int i = max - 1; i >= 0; i--) {
        bits = (bits << 1) | (include.test(i) ? 1 : 0);
      }
      return Small.make(bits);
    } else {
      long[] bits = new long[1 + max / Long.SIZE];
      setBit(max, bits);
      for (int i = max - 1; i >= 0; i--) {
        if (include.test(i)) {
          setBit(i, bits);
        }
      }
      return new Large(bits);
    }
  }

  /// Returns an IntStream of the values in this Bits, in ascending order.
  public IntStream stream() {
    return StreamSupport.intStream(iterator(), false);
  }

  /// Returns an int[] of the values in this Bits, in ascending order.
  public int[] toArray() {
    return stream().toArray();
  }

  /// Returns an iterator for the values in this Bits, in ascending order.
  @Override
  public BitsIterator iterator() {
    return new BitsIterator() {
      /// The next element to be returned, or -1 if this iterator is exhausted.
      private int next = nextSetBit(0);

      @Override
      public int nextInt() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        int result = next;
        next = nextSetBit(result + 1);
        return result;
      }

      @Override
      public boolean hasNext() {
        return next >= 0;
      }

      @Override
      public boolean tryAdvance(IntConsumer action) {
        if (!hasNext()) {
          return false;
        }
        action.accept(nextInt());
        return true;
      }

      @Override
      public long estimateSize() {
        return (next < 0) ? 0 : countGreaterThanOrEq(next);
      }
    };
  }

  @Override
  public String toString() {
    return StringHelper.toString(iterator());
  }

  /// A helper class to construct a compact string representation from a BitsIterator. When more
  /// than two consecutive integers are returned by the iterator, they will be represented using
  /// min..max notation.
  private static class StringHelper implements IntConsumer {
    private final StringBuilder sb = new StringBuilder("{");

    // If runEnd >= 0, then the values in runStart..runEnd have been returned
    // by the iterator but not yet appended to the result.
    private int runStart = 0;
    private int runEnd = -1;

    static String toString(BitsIterator it) {
      StringHelper helper = new StringHelper();
      it.forEachRemaining(helper);
      return helper.build();
    }

    @Override
    public void accept(int i) {
      if (i != runEnd + 1) {
        if (runEnd >= 0) {
          appendRun();
          sb.append(", ");
        }
        runStart = i;
      }
      runEnd = i;
    }

    private void appendRun() {
      sb.append(runStart);
      if (runEnd != runStart) {
        sb.append((runEnd == runStart + 1) ? ", " : "..").append(runEnd);
      }
    }

    private String build() {
      if (runEnd < 0) {
        return "{}";
      }
      appendRun();
      return sb.append("}").toString();
    }
  }

  /// An Op is an elementwise binary operation on Bits.
  public enum Op implements BinaryOperator<Bits> {
    // Note that some of the logic in subsequent methods assumes that there are only these
    // four Ops; if you add another one, plan to look carefully at each method that takes an Op.

    /// The UNION of two Bits contains the elements that are in either of the arguments (or,
    /// equivalently, bitwise OR).
    UNION {
      @Override
      int apply(int x, int y) {
        return x | y;
      }

      @Override
      long apply(long x, long y) {
        return x | y;
      }

      @Override
      Bits apply(Large x, Large y) {
        if (x.testAll(y)) {
          return x;
        } else if (y.testAll(x)) {
          return y;
        } else {
          return super.apply(x, y);
        }
      }

      @Override
      public void into(Builder dst, Bits src) {
        Preconditions.checkArgument(!dst.isInitializedForIntersection());
        if (src.isEmpty()) {
          return;
        } else if (dst.built != null) {
          if (dst.built.testAll(src)) {
            return;
          } else if (src.testAll(dst.built)) {
            dst.setAll(src);
            return;
          }
        }
        dst.apply(this, src);
      }
    },

    /// The INTERSECTION of two Bits contains the elements that are in both of the arguments (or,
    /// equivalently, bitwise AND).
    INTERSECTION {
      @Override
      int apply(int x, int y) {
        return x & y;
      }

      @Override
      long apply(long x, long y) {
        return x & y;
      }

      @Override
      Bits apply(Large x, Large y) {
        if (x.testAll(y)) {
          return y;
        } else if (y.testAll(x)) {
          return x;
        } else {
          return super.apply(x, y);
        }
      }

      @Override
      public void into(Builder dst, Bits src) {
        if (src.isEmpty()) {
          dst.clearAll();
          return;
        } else if (dst.isInitializedForIntersection()) {
          dst.setAll(src);
        } else if (dst.built != null) {
          if (src.testAll(dst.built)) {
            return;
          } else if (dst.built.testAll(src)) {
            dst.setAll(src);
            return;
          }
        }
        dst.apply(this, src);
      }
    },

    /// The DIFFERENCE of two Bits contains the elements that are in the first argument but not the
    /// second (or, equivalently, bitwise AND NOT).
    DIFFERENCE {
      @Override
      int apply(int x, int y) {
        return x & ~y;
      }

      @Override
      long apply(long x, long y) {
        return x & ~y;
      }

      @Override
      Bits apply(Large x, Large y) {
        if (!x.testAny(y)) {
          return x;
        } else {
          return super.apply(x, y);
        }
      }

      @Override
      public void into(Builder dst, Bits src) {
        Preconditions.checkArgument(!dst.isInitializedForIntersection());
        if (src.isEmpty() || (dst.built != null && !dst.built.testAny(src))) {
          return;
        }
        dst.apply(this, src);
      }
    },

    /// The SYMM\_DIFF (symmetric difference) of two Bits contains the elements that are in one
    /// argument but not in both (or, equivalently, bitwise XOR).
    SYMM_DIFF {
      @Override
      int apply(int x, int y) {
        return x ^ y;
      }

      @Override
      long apply(long x, long y) {
        return x ^ y;
      }

      @Override
      public void into(Builder dst, Bits src) {
        Preconditions.checkArgument(!dst.isInitializedForIntersection());
        if (src.isEmpty()) {
          return;
        } else if (dst.isEmpty()) {
          dst.setAll(src);
          return;
        }
        dst.apply(this, src);
      }
    };

    /// Applies this Op to the given Bits and returns the result.
    @Override
    public final Bits apply(Bits x, Bits y) {
      if (x instanceof Small xSmall) {
        if (y instanceof Small ySmall) {
          int newBits = apply(xSmall.bits, ySmall.bits);
          if (newBits == xSmall.bits) {
            return x;
          } else {
            return ySmall.makeIfChanged(newBits);
          }
        } else {
          Large yLarge = (Large) y;
          if (this == DIFFERENCE) {
            // Only the low 32 bits of y are relevant.
            return xSmall.makeIfChanged(xSmall.bits & ~((int) yLarge.bits[0]));
          } else {
            // All the other operations are symmetric
            return apply(yLarge, xSmall);
          }
        }
      } else {
        if (y instanceof Small ySmall) {
          return apply((Large) x, ySmall);
        } else {
          return apply((Large) x, (Large) y);
        }
      }
    }

    abstract int apply(int x, int y);

    abstract long apply(long x, long y);

    final Bits apply(Large x, Small y) {
      long word0 = x.bits[0];
      if (this == INTERSECTION) {
        // Only the low 32 bits of x are relevant.
        return y.makeIfChanged(((int) word0) & y.bits);
      }
      long newWord0 = apply(word0, y.bitsAsLong());
      if (newWord0 == word0) {
        return x;
      }
      long[] newBits = x.bits.clone();
      newBits[0] = newWord0;
      return new Large(newBits);
    }

    Bits apply(Large x, Large y) {
      // Used as-is for SYMM_DIFF; other Ops override, test for special cases and then call super.
      long[] xBits = x.bits;
      long[] yBits = y.bits;
      int word = Math.min(xBits.length, yBits.length) - 1;
      // The length of the final result (after dropping high-order zeros) depends on the Op:
      // * For UNION: the length of the longer argument
      // * For SYM_DIFF: the length of the longer argument, if they are unequal; if they are equal
      //   it may be (arbitrarily) shorter
      // * For DIFFERENCE: if x is longer than y, the length of x; otherwise it may be arbitrarily
      //   short
      // * For INTERSECTION: no longer than the shorter argument, but may be arbitrarily short

      // Establish the loop invariant:
      // - if result is non-null, it has the correct final length and the values in
      //   result[word+1..] are correct
      // - if result is null, the final length could be anything <= word+1
      long[] result;
      if (this == UNION || (this == SYMM_DIFF && xBits.length != yBits.length)) {
        // result will be as long as the longer of x or y
        result = (xBits.length >= yBits.length ? xBits : yBits).clone();
      } else if (this == DIFFERENCE && xBits.length > yBits.length) {
        // result will be as long as x
        result = xBits.clone();
      } else {
        // result could be arbitrarily short
        result = null;
      }
      for (; word >= 0; word--) {
        long b = this.apply(xBits[word], yBits[word]);
        if (result == null) {
          if (word == 0 && fitsInSmall(b)) {
            return Small.make((int) b);
          } else if (b == 0) {
            continue;
          }
          result = new long[word + 1];
        }
        result[word] = b;
      }
      return new Large(result);
    }

    /// Applies this Op to the current contents of the given Builder and a Bits, and stores the
    /// result back in the Builder.
    public abstract void into(Builder dst, Bits src);

    /// Applies this Op to the current contents of two Builders and stores the result in the first
    /// Builder.
    public final void into(Builder dst, Builder src) {
      Preconditions.checkArgument(!src.isInitializedForIntersection());
      if (dst.isInitializedForIntersection()) {
        Preconditions.checkArgument(this == INTERSECTION);
        dst.setAll(src);
      } else if (src.built != null) {
        into(dst, src.built);
      } else {
        dst.apply(this, src.bits);
      }
    }

    /// Equivalent to into(dst, forRange(min, max)), but more efficient.
    public final void rangeInto(Builder dst, int min, int max) {
      Preconditions.checkArgument(min >= 0);
      if (dst.isInitializedForIntersection()) {
        Preconditions.checkArgument(this == INTERSECTION);
        dst.setToRange(min, max);
      } else if (min <= max) {
        dst.applyRange(this, min, max);
      } else if (this == INTERSECTION) {
        dst.clearAll();
      }
    }
  }

  /// Returns the number of elements (set bits) in this Bits.
  public abstract int count();

  /// Returns the number of elements (set bits) in this Bits that are greater than or equal to `i`.
  ///
  /// `i` must be non-negative.
  public abstract int countGreaterThanOrEq(int i);

  /// Returns the smallest element of this Bits.
  ///
  /// `isEmpty()` must be false.
  public abstract int min();

  /// Returns the largest element of this Bits.
  ///
  /// `isEmpty()` must be false.
  public abstract int max();

  /// Returns true if this Bits contains `i`. Returns false if `i` is negative.
  @Override
  public boolean test(int i) {
    return i >= 0 && contains(i);
  }

  /// Returns true if every element of the given Bits is also in this Bits.
  public abstract boolean testAll(Bits other);

  /// Returns true if the given Bits contains at least one element in common with this Bits.
  public abstract boolean testAny(Bits other);

  /// Returns the smallest element of this Bits that is greater than or equal to `i`.
  ///
  /// `i` must be non-negative.
  ///
  /// Equivalent to `countGreaterThanOrEq(i) == 0 ? -1 : clearLessThan(i).min()`.
  public abstract int nextSetBit(int i);

  /// Returns a Bits that contains the same elements as this, and `i`.
  ///
  /// `i` must be non-negative.
  public abstract Bits set(int i);

  /// Returns a Bits that contains the same elements as this, except for `i`.
  ///
  /// `i` must be non-negative.
  public abstract Bits clear(int i);

  /// Returns a Bits that contains the elements of this that are greater than or equal to `i`.
  ///
  /// `i` must be non-negative.
  public abstract Bits clearLessThan(int i);

  /// Returns a Bits that contains the elements of this that are less than `i`.
  ///
  /// `i` must be non-negative.
  public abstract Bits clearGreaterThanOrEq(int i);

  /// Returns the subset of this Bits for which the given IntPredicate returns true.
  public abstract Bits filter(IntPredicate predicate);

  /// Returns true if this Bits contains the given int.
  ///
  /// `i` must be non-negative.
  abstract boolean contains(int i);

  /// Allocates a new long[] and copies this Bits into it. The result will have length `minLength`
  /// or the number of longs necessary to hold all the elements of this Bits, whichever is greater.
  ///
  /// `i` must be greater than zero.
  abstract long[] copyBits(int minLength);

  @Override
  public abstract boolean equals(Object other);

  @Override
  public abstract int hashCode();

  /// A Builder is a mutable object whose current state is a Bits. A newly-created Builder is empty.
  ///
  /// Calling [#build] returns the current value, but leaves the Builder state otherwise unchanged
  /// so additional mutations can be made to it.
  ///
  /// Calling [#build] multiple times without intervening mutations is guaranteed to return the same
  /// Bits without additional allocations.
  ///
  /// Calling [#initializeForIntersection] puts the Builder in a special state so that a subsequent
  /// call to `Op.INTERSECTION.apply(builder, x)` is equivalent to calling `builder.setAll(x)`; this
  /// makes it simpler to write code that intersects a variable number of `Bits` (or `Builder`)
  /// objects. Note that if no sets are intersected into the Builder a call to [#build] will fail
  /// (since there is no Bits that contains all possible ints). You can detect whether a Builder is
  /// in this state with [#isInitializedForIntersection], and clear it by calling [#clearAll] or
  /// [#setAll].
  public static class Builder implements IntPredicate, Iterable<Integer> {

    /// True if [#initializeForIntersection] was the last operation to modify the state of this
    /// Builder.
    private boolean isInitializedForIntersection;

    /// The current state of a Builder may be represented as a long[] (`bits`), a Bits (`built`), or
    /// both. At least one will be non-null; if both are are non-null they represent the same set.
    ///
    /// If `bits` is non-null its length is non-zero.
    private long[] bits;

    /// Null if [#isInitializedForIntersection] is true.
    private Bits built = EMPTY;

    /// Sets this Builder to a special state equivalent to containing all possible bits; the
    /// intended use is to follow this with one or more calls to intersect other Builders or Bits
    /// into this Builder.
    ///
    /// Many operations on a Builder in this state will error (including [#stream], [#iterator],
    /// [#clear], [#countGreaterThanOrEq], [#nextSetBit], and [#build]), as will attempting to
    /// [Op#UNION], [Op#DIFFERENCE], or [Op#SYMM_DIFF] into it.
    public void initializeForIntersection() {
      isInitializedForIntersection = true;
      built = null;
    }

    /// Returns true if [#initializeForIntersection] was the last operation to modify the state of
    /// this Builder.
    public boolean isInitializedForIntersection() {
      return isInitializedForIntersection;
    }

    /// Returns true if this Builder's state contains `i`.
    @Override
    public boolean test(int i) {
      if (i < 0) {
        return false;
      } else if (isInitializedForIntersection) {
        // Implementing test() (and testAll() and testAny()) when isInitializedForIntersection()
        // is true may not be useful, but it's as easy as throwing an exception.
        return true;
      } else if (bits != null) {
        return getBit(i, bits);
      } else {
        return built.contains(i);
      }
    }

    /// Returns true if this Builder's state is empty.
    public boolean isEmpty() {
      if (built != null) {
        return built.isEmpty();
      } else if (isInitializedForIntersection) {
        return false;
      }
      for (long b : bits) {
        if (b != 0) {
          return false;
        }
      }
      return true;
    }

    /// Returns an IntStream of the values in this Builder, in ascending order.
    public IntStream stream() {
      return StreamSupport.intStream(iterator(), false);
    }

    /// Returns false if this Builder's state already contains `i`. Otherwise adds `i` to this
    /// Builder's state and returns true.
    ///
    /// `i` must be non-negative.
    @CanIgnoreReturnValue
    public boolean set(int i) {
      Preconditions.checkArgument(i >= 0 && !isInitializedForIntersection);
      if (built != null && built.contains(i)) {
        return false;
      }
      int word = i / Long.SIZE;
      ensureBits(word + 1);
      long b = bits[word];
      long bNew = b | (1L << (i % Long.SIZE));
      bits[word] = bNew;
      return b != bNew;
    }

    /// Returns false if this Builder's state does not contain `i`. Otherwise removes `i` from this
    /// Builder's state and returns true.
    ///
    /// `i` must be non-negative.
    @CanIgnoreReturnValue
    public boolean clear(int i) {
      Preconditions.checkArgument(i >= 0 && !isInitializedForIntersection);
      if (built != null && !built.contains(i)) {
        return false;
      }
      int word = i / Long.SIZE;
      ensureBits(1);
      if (word >= bits.length) {
        return false;
      }
      long b = bits[word];
      long bNew = b & ~(1L << (i % Long.SIZE));
      bits[word] = bNew;
      return b != bNew;
    }

    /// Sets this Builder's state to the given Bits.
    public void setAll(Bits built) {
      this.built = built;
      isInitializedForIntersection = false;
      if (bits != null) {
        // We could just discard `bits`, but keeping it might save an allocation later.
        if (built instanceof Large largeBuilt) {
          int builtLength = largeBuilt.bits.length;
          if (bits.length >= builtLength) {
            System.arraycopy(largeBuilt.bits, 0, bits, 0, builtLength);
            Arrays.fill(bits, builtLength, bits.length, 0);
          } else {
            bits = null;
          }
        } else {
          bits[0] = ((Small) built).bitsAsLong();
          Arrays.fill(bits, 1, bits.length, 0);
        }
      }
    }

    /// Sets this Builder's state to be the same as the given Builder.
    public void setAll(Builder other) {
      if (other.built != null) {
        setAll(other.built);
      } else if (other.isInitializedForIntersection) {
        initializeForIntersection();
      } else {
        this.built = null;
        if (bits == null || bits.length < other.bits.length) {
          bits = Arrays.copyOf(other.bits, other.bits.length);
        } else {
          System.arraycopy(other.bits, 0, bits, 0, other.bits.length);
          Arrays.fill(bits, other.bits.length, bits.length, 0);
        }
      }
    }

    /// Equivalent to `setAll(Bits.EMPTY)`.
    public void clearAll() {
      built = Bits.EMPTY;
      isInitializedForIntersection = false;
      if (bits != null) {
        Arrays.fill(bits, 0);
      }
    }

    /// Equivalent to `setAll(Bits.forRange(min, max)`.
    public void setToRange(int min, int max) {
      Preconditions.checkArgument(min >= 0);
      if (min > max) {
        clearAll();
      } else {
        built = null;
        isInitializedForIntersection = false;
        bits = setLongsToRange(min, max, bits);
      }
    }

    /// Returns the number of elements in this Builder's state that are greater than or equal to
    /// `i`.
    ///
    /// `i` must be non-negative.
    public int countGreaterThanOrEq(int i) {
      Preconditions.checkArgument(!isInitializedForIntersection);
      return (built != null) ? built.countGreaterThanOrEq(i) : countGreaterThanOrEqHelper(i, bits);
    }

    /// Returns the smallest element of this Builder's state that is greater than or equal to `i`,
    /// or -1 if there is none.
    ///
    /// `i` must be non-negative.
    public int nextSetBit(int i) {
      // Returning i when isInitializedForIntersection() is true would be technically correct, but
      // in practice this method is only used to iterate through the Builder's elements and that's
      // not likely to be useful.
      Preconditions.checkArgument(!isInitializedForIntersection);
      return (built != null) ? built.nextSetBit(i) : nextSetBitHelper(i, bits);
    }

    /// Returns true if this Builder contains every element of the given Bits.
    public boolean testAll(Bits other) {
      if (isInitializedForIntersection) {
        return true;
      } else if (built != null) {
        return built.testAll(other);
      } else if (other instanceof Small otherSmall) {
        return (otherSmall.bits & ~(int) bits[0]) == 0;
      } else {
        return testAllHelper(bits, ((Large) other).bits);
      }
    }

    /// Returns true if this Builder contains every element of the given Builder.
    public boolean testAll(Bits.Builder other) {
      if (isInitializedForIntersection) {
        return true;
      } else if (other.isInitializedForIntersection) {
        return false;
      } else if (other.built != null) {
        return testAll(other.built);
      } else if (built == null) {
        return testAllHelper(bits, other.bits);
      } else if (built instanceof Large builtLarge) {
        return testAllHelper(builtLarge.bits, other.bits);
      } else {
        return Arrays.stream(other.bits, 1, other.bits.length).allMatch(b -> b == 0)
            && (other.bits[0] & ~((Small) built).bits) == 0;
      }
    }

    /// Returns true if this Builder contains at least one element of the given Bits.
    public boolean testAny(Bits other) {
      if (isInitializedForIntersection) {
        return !other.isEmpty();
      } else if (built != null) {
        return built.testAny(other);
      } else if (other instanceof Small otherSmall) {
        return (otherSmall.bits & (int) bits[0]) != 0;
      } else {
        return testAnyHelper(bits, ((Large) other).bits);
      }
    }

    /// Returns true if this Builder contains at least one element of the given Builder.
    public boolean testAny(Bits.Builder other) {
      if (isInitializedForIntersection) {
        return !other.isEmpty();
      } else if (other.isInitializedForIntersection) {
        return !isEmpty();
      } else if (built != null) {
        return other.testAny(built);
      } else if (other.built != null) {
        return testAny(other.built);
      } else {
        return testAnyHelper(bits, other.bits);
      }
    }

    /// Combine the given Bits with our state, using the given Op.
    ///
    /// Used to implement [Op#into(Builder, Bits)].
    void apply(Op op, Bits src) {
      if (src instanceof Small smallSrc) {
        ensureBits(1);
        bits[0] = op.apply(bits[0], smallSrc.bitsAsLong());
        if (op == Op.INTERSECTION) {
          Arrays.fill(bits, 1, bits.length, 0);
        }
      } else {
        apply(op, ((Large) src).bits);
      }
    }

    /// Combine the given long[] with our state, using the given Op.
    ///
    /// Used to implement [#apply(Op, Bits)] and [Op#into(Builder, Builder)].
    void apply(Op op, long[] srcBits) {
      int limit = srcBits.length;
      if (op == Op.UNION || op == Op.SYMM_DIFF) {
        ensureBits(limit);
      } else {
        ensureBits(1);
        limit = Math.min(limit, bits.length);
        if (op == Op.INTERSECTION) {
          Arrays.fill(bits, limit, bits.length, 0);
        }
      }
      for (int i = 0; i < limit; i++) {
        bits[i] = op.apply(bits[i], srcBits[i]);
      }
    }

    /// Combine Bits.forRange(min, max) with our state, using the given Op.
    ///
    /// Used to implement [Op#rangeInto(Builder, int, int)].
    void applyRange(Op op, int min, int max) {
      int minWord = min / Long.SIZE;
      int maxWord = max / Long.SIZE;
      // Ensure that bits is long enough to hold the result.
      switch (op) {
        case UNION:
        case SYMM_DIFF:
          ensureBits(maxWord + 1);
          break;
        case INTERSECTION:
          ensureBits(1);
          // We also need to clear any words that don't intersect the range.
          if (bits.length <= minWord) {
            clearAll();
            return;
          }
          Arrays.fill(bits, 0, minWord, 0);
          if (maxWord + 1 < bits.length) {
            Arrays.fill(bits, maxWord + 1, bits.length, 0);
          }
          break;
        case DIFFERENCE:
          ensureBits(1);
          if (bits.length <= minWord) {
            return;
          }
          break;
      }
      int limit = Math.min(maxWord, bits.length);
      // bits[minWord+1 .. limit-1] should be combined with -1L
      if (op == Op.SYMM_DIFF) {
        for (int word = minWord + 1; word < limit; word++) {
          bits[word] ^= -1L;
        }
      } else if (op != Op.INTERSECTION && minWord + 1 < limit) {
        // UNION or DIFFERENCE
        Arrays.fill(bits, minWord + 1, limit, op == Op.UNION ? -1L : 0);
      }
      // bits[minWord] should be combined with minMask
      // bits[maxWord] should be combined with maxMask
      long minMask = longBitsGreaterOrEq(min % Long.SIZE);
      if (maxWord < bits.length) {
        long maxMask = ~longBitsGreaterThan(max % Long.SIZE);
        if (minWord == maxWord) {
          minMask &= maxMask;
        } else {
          bits[maxWord] = op.apply(bits[maxWord], maxMask);
        }
      }
      bits[minWord] = op.apply(bits[minWord], minMask);
    }

    /// Returns the current state of this Builder.
    public Bits build() {
      Preconditions.checkState(!isInitializedForIntersection);
      if (built != null) {
        // Repeated calls to build() just return the previously-built Bits.
        return built;
      }
      // Find the last non-zero element of bits.
      for (int word = bits.length - 1; word >= 0; word--) {
        long b = bits[word];
        if (b != 0) {
          if (word == 0 && fitsInSmall(b)) {
            built = Small.make((int) b);
          } else if (word == bits.length - 1) {
            // Use our long[] to make the Bits, which means we must drop our reference to it.
            built = new Large(bits);
            bits = null;
          } else {
            // Trim our long[], to remove the trailing zeros.
            built = new Large(Arrays.copyOf(bits, word + 1));
          }
          return built;
        }
      }
      built = EMPTY;
      return built;
    }

    /// Returns an iterator for the values in this Builder, in ascending order.
    ///
    /// Modifying the Builder during iteration has well-defined behavior: adding or removing an
    /// element less than or equal to the last value returned does not affect iteration, while
    /// adding or removing an element greater than the last value returned does.
    @Override
    public BitsIterator iterator() {
      Preconditions.checkState(!isInitializedForIntersection);
      return new BitsIterator() {
        // One more than the last value returned
        int start = 0;

        private int nextInternal() {
          return nextSetBit(start);
        }

        @Override
        public int nextInt() {
          int next = nextInternal();
          if (next < 0) {
            throw new NoSuchElementException();
          }
          start = next + 1;
          return next;
        }

        @Override
        public boolean hasNext() {
          return nextInternal() >= 0;
        }

        @Override
        public boolean tryAdvance(IntConsumer action) {
          int next = nextInternal();
          if (next < 0) {
            return false;
          }
          start = next + 1;
          action.accept(next);
          return true;
        }

        @Override
        public long estimateSize() {
          return countGreaterThanOrEq(start);
        }
      };
    }

    /// Called when we are about to mutate `bits`.
    ///
    /// Ensures that this Builder's state is in `bits`, that `bits` has length greater than or equal
    /// to `minLength`, and that `built` is null.
    private void ensureBits(int minLength) {
      assert !isInitializedForIntersection;
      if (bits == null) {
        bits = built.copyBits(minLength);
      } else if (bits.length < minLength) {
        bits = Arrays.copyOf(bits, minLength);
      }
      built = null;
    }

    @Override
    public String toString() {
      String contents =
          isInitializedForIntersection
              ? "(initializedForIntersection)"
              : StringHelper.toString(iterator());
      return "@" + StringUtil.id(this) + contents;
    }
  }

  /// Bits with `max()` less than 6 are prebuilt and cached, so they never need to be allocated.
  private static final Small[] SMALLS;

  static {
    int n = 1 << 6;
    SMALLS = new Small[n];
    for (int i = 0; i < n; i++) {
      SMALLS[i] = new Small(i);
    }
    EMPTY = SMALLS[0];
  }

  /// Returns an int with bits less than `i` set to zero, and bits greater than or equal to `i` set
  /// to one.
  private static int intBitsGreaterOrEq(int i) {
    assert i >= 0;
    return (i > 31) ? 0 : (-1 << i);
  }

  /// Returns long with bits less than `i` set to zero, and bits greater than or equal to `i` set to
  /// one.
  private static long longBitsGreaterOrEq(int i) {
    assert i >= 0 && i < Long.SIZE;
    return -1L << i;
  }

  /// Returns long with bits less than or equal to `i` set to zero, and bits greater than `i` set to
  /// one.
  private static long longBitsGreaterThan(int i) {
    assert i >= 0 && i < Long.SIZE;
    return -2L << i;
  }

  /// Returns true if bits is non-negative and less than 2^32.
  private static boolean fitsInSmall(long bits) {
    return (bits & (-1L << 32)) == 0;
  }

  /// Sets the specified bit in the given long[].
  private static void setBit(int i, long[] bits) {
    assert i >= 0;
    bits[i / Long.SIZE] |= 1L << (i % Long.SIZE);
  }

  /// Returns true if the specified bit in the given long[] is set.
  private static boolean getBit(int i, long[] bits) {
    assert i >= 0;
    int word = i / Long.SIZE;
    return word < bits.length && (bits[word] & (1L << (i % Long.SIZE))) != 0;
  }

  /// Returns a long[] with bits min through max set. Uses the given long[] if it is non-null and
  /// large enough, otherwise allocates a new one.
  private static long[] setLongsToRange(int min, int max, long[] bits) {
    int minWord = min / Long.SIZE;
    int maxWord = max / Long.SIZE;
    if (bits == null || bits.length <= maxWord) {
      bits = new long[maxWord + 1];
    } else {
      Arrays.fill(bits, 0, minWord, 0);
      Arrays.fill(bits, maxWord + 1, bits.length, 0);
    }
    // bits[minWord] should be combined with minMask
    // bits[maxWord] should be combined with maxMask
    long minMask = longBitsGreaterOrEq(min % Long.SIZE);
    long maxMask = ~longBitsGreaterThan(max % Long.SIZE);
    if (minWord == maxWord) {
      bits[minWord] = minMask & maxMask;
    } else {
      bits[minWord] = minMask;
      bits[maxWord] = maxMask;
      Arrays.fill(bits, minWord + 1, maxWord, -1L);
    }
    return bits;
  }

  /// Returns the index of the smallest bit that is greater than or equal to `i`.
  private static int nextSetBitHelper(int i, long[] bits) {
    Preconditions.checkArgument(i >= 0);
    long mask = longBitsGreaterOrEq(i % Long.SIZE);
    for (int word = i / Long.SIZE; word < bits.length; word++) {
      long b = bits[word] & mask;
      if (b != 0) {
        return Long.numberOfTrailingZeros(b) + Long.SIZE * word;
      }
      mask = -1L;
    }
    return -1;
  }

  /// Returns the number of bits set with indices greater than or equal to `i`.
  private static int countGreaterThanOrEqHelper(int i, long[] bits) {
    Preconditions.checkArgument(i >= 0);
    int word = i / Long.SIZE;
    if (word >= bits.length) {
      return 0;
    }
    int partial = Long.bitCount(bits[word] & longBitsGreaterOrEq(i % Long.SIZE));
    return partial + countFrom(word + 1, bits);
  }

  /// Returns the number of bits set in `bits[start..]`.
  private static int countFrom(int start, long[] bits) {
    return Arrays.stream(bits, start, bits.length).mapToInt(Long::bitCount).sum();
  }

  /// Returns true if every bit set in y is also set in x.
  private static boolean testAllHelper(long[] x, long[] y) {
    for (int i = y.length - 1; i >= 0; i--) {
      long xi = (i >= x.length) ? 0 : x[i];
      if ((y[i] & ~xi) != 0) {
        return false;
      }
    }
    return true;
  }

  /// Returns true if x and y have at least one set bit in common.
  private static boolean testAnyHelper(long[] x, long[] y) {
    int limit = Math.min(x.length, y.length);
    for (int i = 0; i < limit; i++) {
      if ((x[i] & y[i]) != 0) {
        return true;
      }
    }
    return false;
  }

  /// Small is a very cheap implementation of Bits used when `max()` is less than 32.
  private static class Small extends Bits {
    private final int bits;

    private Small(int bits) {
      this.bits = bits;
    }

    /// Returns a Small containing the given bits.
    static Small make(int bits) {
      if (bits >= 0 && bits < SMALLS.length) {
        return SMALLS[bits];
      } else {
        return new Small(bits);
      }
    }

    @Override
    public int count() {
      return Integer.bitCount(bits);
    }

    @Override
    public int countGreaterThanOrEq(int i) {
      Preconditions.checkArgument(i >= 0);
      return Integer.bitCount(bits & intBitsGreaterOrEq(i));
    }

    @Override
    public int min() {
      Preconditions.checkArgument(bits != 0);
      return Integer.numberOfTrailingZeros(bits);
    }

    @Override
    public int max() {
      Preconditions.checkArgument(bits != 0);
      return 31 - Integer.numberOfLeadingZeros(bits);
    }

    @Override
    public int nextSetBit(int i) {
      Preconditions.checkArgument(i >= 0);
      int b = bits & intBitsGreaterOrEq(i);
      return (b == 0) ? -1 : Integer.numberOfTrailingZeros(b);
    }

    @Override
    boolean contains(int i) {
      assert i >= 0;
      return (i < Integer.SIZE) && (bits & (1 << i)) != 0;
    }

    @Override
    public boolean testAll(Bits other) {
      if (other instanceof Large) {
        return false;
      } else {
        return (((Small) other).bits & ~bits) == 0;
      }
    }

    @Override
    public boolean testAny(Bits other) {
      int otherBits;
      if (other instanceof Large otherLarge) {
        otherBits = (int) otherLarge.bits[0];
      } else {
        otherBits = ((Small) other).bits;
      }
      return (otherBits & bits) != 0;
    }

    /// Returns the contents of this Small as a long.
    private long bitsAsLong() {
      // We don't want sign extension
      return bits & 0xffffffffL;
    }

    @Override
    long[] copyBits(int minLength) {
      long[] longs = new long[minLength];
      longs[0] = bitsAsLong();
      return longs;
    }

    /// Returns a Small containing the given bits, using this if possible.
    private Bits makeIfChanged(int b) {
      return (b == bits) ? this : make(b);
    }

    @Override
    public Bits set(int i) {
      Preconditions.checkArgument(i >= 0);
      if (i <= 31) {
        return makeIfChanged(bits | (1 << i));
      }
      long[] newBits = copyBits(1 + i / Long.SIZE);
      setBit(i, newBits);
      return new Large(newBits);
    }

    @Override
    public Bits clear(int i) {
      Preconditions.checkArgument(i >= 0);
      return (i <= 31) ? makeIfChanged(bits & ~(1 << i)) : this;
    }

    @Override
    public Bits clearLessThan(int i) {
      Preconditions.checkArgument(i >= 0);
      return makeIfChanged(bits & intBitsGreaterOrEq(i));
    }

    @Override
    public Bits clearGreaterThanOrEq(int i) {
      Preconditions.checkArgument(i >= 0);
      return makeIfChanged(bits & ~intBitsGreaterOrEq(i));
    }

    @Override
    public Bits filter(IntPredicate predicate) {
      int newBits = 0;
      for (int remaining = bits; remaining != 0; ) {
        int next = Integer.lowestOneBit(remaining);
        if (predicate.test(Integer.numberOfTrailingZeros(remaining))) {
          newBits |= next;
        }
        remaining &= ~next;
      }
      return makeIfChanged(newBits);
    }

    @Override
    public int hashCode() {
      return bits;
    }

    @Override
    public boolean equals(Object obj) {
      return (obj instanceof Small otherSmall) && bits == otherSmall.bits;
    }
  }

  /// Large is an implementation of Bits used when `max()` is greater than or equal to 32.
  private static class Large extends Bits {
    private final long[] bits;

    /// Takes ownership of the given array, which caller must not mutate after this call.
    ///
    /// The last element of `bits` must be non-zero, and the resulting Bits must not be
    /// representable with a Small.
    Large(long[] bits) {
      // A null bits is used to represent ALL_BITS.  Almost all operations on it will throw
      // exceptions.
      assert bits == null
          || (bits.length > 1
              ? bits[bits.length - 1] != 0
              : Long.numberOfLeadingZeros(bits[0]) < Integer.SIZE);
      this.bits = bits;
    }

    @Override
    public int count() {
      return Bits.countFrom(0, bits);
    }

    @Override
    public int countGreaterThanOrEq(int i) {
      return countGreaterThanOrEqHelper(i, bits);
    }

    @Override
    public int min() {
      int result = nextSetBit(0);
      assert result >= 0;
      return result;
    }

    @Override
    public int max() {
      int topZeros = Long.numberOfLeadingZeros(bits[bits.length - 1]);
      assert topZeros < Long.SIZE;
      return Long.SIZE * bits.length - 1 - topZeros;
    }

    @Override
    public int nextSetBit(int i) {
      return nextSetBitHelper(i, bits);
    }

    @Override
    boolean contains(int i) {
      return getBit(i, bits);
    }

    @Override
    public boolean testAll(Bits other) {
      // Choose the type-specific implementation.
      return (other instanceof Small otherSmall) ? testAll(otherSmall) : testAll((Large) other);
    }

    boolean testAll(Small other) {
      return (other.bits & ~(int) bits[0]) == 0;
    }

    boolean testAll(Large other) {
      return testAllHelper(bits, other.bits);
    }

    @Override
    public boolean testAny(Bits other) {
      // Choose the type-specific implementation.
      return (other instanceof Small otherSmall) ? testAny(otherSmall) : testAny((Large) other);
    }

    boolean testAny(Small other) {
      return (other.bits & (int) bits[0]) != 0;
    }

    boolean testAny(Large other) {
      return testAnyHelper(bits, other.bits);
    }

    @Override
    public Bits set(int i) {
      Preconditions.checkArgument(i >= 0);
      if (getBit(i, bits)) {
        return this;
      }
      long[] newBits = Arrays.copyOf(bits, Math.max(bits.length, 1 + i / Long.SIZE));
      setBit(i, newBits);
      return new Large(newBits);
    }

    @Override
    public Bits clear(int i) {
      Preconditions.checkArgument(i >= 0);
      int word = i / Long.SIZE;
      if (word >= bits.length) {
        return this;
      }
      long b = bits[word];
      long newB = b & ~(1L << (i % Long.SIZE));
      if (b == newB) {
        return this;
      }
      if (word == bits.length - 1) {
        // If we removed the top bit we may be able to use a smaller long[] or even a Small.
        while (newB == 0 && word != 0) {
          newB = bits[--word];
        }
        if (word == 0 && fitsInSmall(newB)) {
          return Small.make((int) newB);
        } else if (word != bits.length - 1) {
          return new Large(Arrays.copyOf(bits, word + 1));
        }
      }
      long[] newBits = bits.clone();
      newBits[word] = newB;
      return new Large(newBits);
    }

    @Override
    public Bits clearLessThan(int i) {
      Preconditions.checkArgument(i >= 0);
      if (i > max()) {
        return EMPTY;
      } else if (i <= min()) {
        return this;
      }
      long[] newBits = bits.clone();
      int word = i / Long.SIZE;
      newBits[word] &= longBitsGreaterOrEq(i % Long.SIZE);
      Arrays.fill(newBits, 0, word, 0);
      return new Large(newBits);
    }

    @Override
    public Bits clearGreaterThanOrEq(int i) {
      Preconditions.checkArgument(i >= 0);
      int word = i / Long.SIZE;
      if (word >= bits.length) {
        return this;
      }
      long newTop = bits[word] & ~longBitsGreaterOrEq(i % Long.SIZE);
      if (word == bits.length - 1 && newTop == bits[word]) {
        return this;
      }
      while (newTop == 0 && word != 0) {
        newTop = bits[--word];
      }
      if (word == 0 && fitsInSmall(newTop)) {
        return Small.make((int) newTop);
      }
      long[] newBits = Arrays.copyOf(bits, word + 1);
      newBits[word] = newTop;
      return new Large(newBits);
    }

    @Override
    public Bits filter(IntPredicate predicate) {
      long[] newBits = null;
      for (int word = bits.length - 1; word >= 0; word--) {
        long result = 0;
        for (long remaining = bits[word]; remaining != 0; ) {
          long next = Long.lowestOneBit(remaining);
          if (predicate.test(Long.numberOfTrailingZeros(remaining) + Long.SIZE * word)) {
            result |= next;
          }
          remaining &= ~next;
        }
        if (result != 0) {
          if (newBits == null) {
            if (word == 0 && fitsInSmall(result)) {
              return Small.make((int) result);
            }
            newBits = new long[word + 1];
          }
          newBits[word] = result;
        }
      }
      if (newBits == null) {
        return EMPTY;
      } else if (Arrays.equals(newBits, bits)) {
        return this;
      } else {
        return new Large(newBits);
      }
    }

    @Override
    long[] copyBits(int minLength) {
      return Arrays.copyOf(bits, Math.max(minLength, bits.length));
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(bits);
    }

    @Override
    public boolean equals(Object obj) {
      return (obj instanceof Large otherLarge) && Arrays.equals(bits, otherLarge.bits);
    }
  }

  /// A base class that makes it easier to implement both iterator() and stream() with a single
  /// class.
  private abstract static class BitsIterator implements PrimitiveIterator.OfInt, Spliterator.OfInt {
    @Override
    public void forEachRemaining(Consumer<? super Integer> action) {
      PrimitiveIterator.OfInt.super.forEachRemaining(action);
    }

    @Override
    public void forEachRemaining(IntConsumer action) {
      PrimitiveIterator.OfInt.super.forEachRemaining(action);
    }

    @Override
    public Spliterator.OfInt trySplit() {
      return null;
    }

    @Override
    public int characteristics() {
      return ORDERED | DISTINCT | SORTED | SIZED | IMMUTABLE;
    }

    @Override
    public Comparator<? super Integer> getComparator() {
      return null;
    }
  }
}
