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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.retrolang.util.Bits.Op;

@RunWith(JUnitParamsRunner.class)
public class BitsTest {

  /// [#setAndClear] will test setting and clearing elements with each subset of these elements.
  /// These values were chosen to try to hit all the likely boundary conditions. They'll be added to
  /// the Builder in the order they appear here, so having them unsorted tests a few more code
  /// paths.
  private static final int[] ELEMENTS = new int[] {256, 32, 0, 15, 31, 63, 64, 127, 128, 255};

  /// Maps each value in ELEMENTS to its position in ELEMENTS, or -1 if absent.
  private static final int[] ELEMENT_INDEX;

  static {
    int maxElement = Arrays.stream(ELEMENTS).max().getAsInt();
    ELEMENT_INDEX = new int[maxElement + 1];
    Arrays.fill(ELEMENT_INDEX, -1);
    for (int i = 0; i < ELEMENTS.length; i++) {
      ELEMENT_INDEX[ELEMENTS[i]] = i;
    }
  }

  /// Returns an IntFunction that returns true if the corresponding bit `iteration` is set.
  private static IntPredicate includedInIteration(int iteration) {
    return j -> (iteration & (1 << j)) != 0;
  }

  /// Returns an array containing all `[i, includedInIteration(i)]` pairs for `i` in `0..(2^limit -
  /// 1)`.
  private static Object[] allSubsets(int limit) {
    int numSubsets = 1 << limit;
    return IntStream.range(0, numSubsets)
        .mapToObj(i -> new Object[] {i, includedInIteration(i)})
        .toArray();
  }

  /// Returns all possible arguments tuples for [#setAndClear].
  private static Object[] allElementSubsets() {
    return allSubsets(ELEMENTS.length);
  }

  /// Systematically tests setting and clearing individual bits in Bits and Builders. Also tests
  /// Builder.test(), Bits.test(), Bits.stream() and Bits.of().
  @Test
  @Parameters(method = "allElementSubsets")
  @TestCaseName("setAndClear_{0}")
  public void setAndClear(Integer iteration, IntPredicate included) {
    Bits.Builder builder = new Bits.Builder();
    // Add the chosen elements to builder.
    IntStream.range(0, ELEMENTS.length)
        .filter(included)
        .forEach(j -> {
          int element = ELEMENTS[j];
          // The first call to set() should succeed, the second should fail since it's
          // already present.
          assertTrue(builder.set(element));
          assertFalse(builder.set(element));
        });
    // builder should only be empty if this is the empty iteration.
    assertEquals(iteration == 0, builder.isEmpty());

    StreamChecker builderChecker = new StreamChecker();
    for (int element : builder) {
      builderChecker.checkNext(element, included);
    }
    // For the final check we need a bits

    // Build a Bits, and ensure that it looks right.
    Bits subset = builder.build();
    builderChecker.checkExpected(subset);
    int size = Integer.bitCount(iteration);
    assertEquals(size, subset.count());
    assertEquals(iteration == 0, subset.isEmpty());

    // test(negativeValue) should always be false, for Bits and Builder.
    assertFalse(subset.test(-1));
    assertFalse(builder.test(-1));

    // Check each of the elements for presence in subset.
    for (int j = 0; j < ELEMENTS.length; j++) {
      int element = ELEMENTS[j];
      boolean isIncluded = included.test(j);
      assertEquals(isIncluded, subset.test(element));

      // Take each element back out of the builder, which should only succeed if it's
      // one we're including on this iteration.
      assertEquals(isIncluded, builder.test(element));
      assertEquals(isIncluded, builder.clear(element));
      // A second attempt to clear it should always fail.
      assertFalse(builder.clear(element));

      // Try removing it from the set
      Bits removed = subset.clear(element);
      if (!isIncluded) {
        // Removing an element that wasn't there is a no-op
        assertSame(subset, removed);
      } else {
        // If it was there, the resulting set should be smaller, and adding it back should give
        // us the original set.
        assertEquals(size - 1, removed.count());
        Bits restored = removed.set(element);
        assertEquals(subset, restored);
        // ... and the difference should be a single element ...
        assertEquals(Bits.of(element), Op.DIFFERENCE.apply(subset, removed));
        // ... and adding it again should have no effect.
        assertSame(subset, subset.set(element));
      }
    }
    assertTrue(builder.isEmpty());

    // Enumerate the Bits, and make sure we get the expected elements in the expected order.
    StreamChecker streamChecker = new StreamChecker();
    subset.stream().forEach(element -> streamChecker.checkNext(element, included));
    streamChecker.checkExpected(subset);

    // Repeat the check using an iterator.
    StreamChecker iteratorChecker = new StreamChecker();
    for (int element : subset) {
      iteratorChecker.checkNext(element, included);
    }
    iteratorChecker.checkExpected(subset);
  }

  /// A helper class to verify that iterators produce the expected values in the expected order. To
  /// use,
  ///
  /// - call [#checkNext] with each element produced by the iterator, together with the predicate
  ///   that was used to subset [#ELEMENTS]; and
  /// - when the iterator completes, call [#checkExpected] with the Bits that should have been
  ///   returned.
  private static class StreamChecker {
    int count;
    int min = Integer.MAX_VALUE;
    int max = Integer.MIN_VALUE;

    void checkNext(int element, IntPredicate included) {
      // This should be one of the elements included in this iteration.
      assertTrue(included.test(ELEMENT_INDEX[element]));
      ++count;
      if (count == 1) {
        min = element;
      } else {
        // We should get the elements in ascending order.
        assertThat(element).isGreaterThan(max);
      }
      max = element;
    }

    void checkExpected(Bits bits) {
      // Verify that we saw all the elements we expected to.
      assertEquals(count, bits.count());
      if (count != 0) {
        assertEquals(min, bits.min());
        assertEquals(max, bits.max());
      }
    }
  }

  /// Returns all possible arguments tuples for [#fromIntAndfromBooleans].
  private static Object[] subsetsUpTo4() {
    return allSubsets(4);
  }

  /// Compare Bits.fromInt(), Bits.fromBooleans(), and Bits.fromPredicate().
  @Test
  @Parameters(method = "subsetsUpTo4")
  @TestCaseName("fromIntAndfromBooleans_{0}")
  public void fromIntAndfromBooleans(Integer iteration, IntPredicate included) {
    Bits bits = Bits.fromInt(iteration);
    assertEquals(Bits.fromPredicate(4, included), bits);
    // There are 3 overloads of Bits.fromBooleans(), so test them all.
    if (iteration < 2) {
      assertEquals(bits, Bits.fromBooleans(included.test(0)));
    }
    if (iteration < 4) {
      assertEquals(bits, Bits.fromBooleans(included.test(0), included.test(1)));
    }
    if (iteration < 8) {
      assertEquals(bits, Bits.fromBooleans(included.test(0), included.test(1), included.test(2)));
    }
  }

  private static class Range {
    final int min;
    final int max;

    /// Represents the integers from min to max, inclusive.
    Range(int min, int max) {
      this.min = min;
      this.max = max;
    }

    boolean contains(int i) {
      return i >= min && i <= max;
    }

    /// Returns Bits.forRange(min, max).
    Bits asBits() {
      return Bits.forRange(min, max);
    }

    /// Returns the number of values in this range.
    int count() {
      return max + 1 - min;
    }

    /// Returns this range clipped to values less than the given value.
    Range keepLessThan(int i) {
      return max < i ? this : new Range(min, Math.max(min - 1, i - 1));
    }

    /// Returns this range clipped to values that are greater than or equal to the given value.
    Range keepGreaterThanOrEq(int i) {
      return min >= i ? this : new Range(Math.min(i, max + 1), max);
    }

    @Override
    public String toString() {
      return min + ".." + max;
    }
  }

  /// Ranges for partialClearAndCount() and rangeOps(). The order they appear here doesn't matter.
  private static final ImmutableList<Range> RANGES =
      ImmutableList.of(
          new Range(0, 0),
          new Range(0, 62),
          new Range(0, 63),
          new Range(1, 31),
          new Range(1, 128),
          new Range(63, 64),
          new Range(63, 255),
          new Range(128, 128),
          new Range(100, 99));

  /// Returns all possible arguments tuples for [#partialClearAndCount].
  private static Object[] allRanges() {
    return RANGES.stream().map(r -> new Object[] {r}).toArray();
  }

  /// Test clearLessThan(), clearGreaterThanOrEq(), and countGreaterThanOrEq().
  @Parameters(method = "allRanges")
  @TestCaseName("partialClearAndCount_{0}")
  @Test
  public void partialClearAndCount(Range range) {
    Bits b = range.asBits();

    // Sanity check count()
    assertEquals(range.count(), b.count());

    assertEquals(range.keepGreaterThanOrEq(0).asBits(), b.clearLessThan(0));
    assertEquals(range.keepGreaterThanOrEq(31).asBits(), b.clearLessThan(31));
    assertEquals(range.keepGreaterThanOrEq(32).asBits(), b.clearLessThan(32));
    assertEquals(range.keepGreaterThanOrEq(63).asBits(), b.clearLessThan(63));
    assertEquals(range.keepGreaterThanOrEq(64).asBits(), b.clearLessThan(64));
    assertEquals(range.keepGreaterThanOrEq(97).asBits(), b.clearLessThan(97));

    assertEquals(range.keepLessThan(0).asBits(), b.clearGreaterThanOrEq(0));
    assertEquals(range.keepLessThan(31).asBits(), b.clearGreaterThanOrEq(31));
    assertEquals(range.keepLessThan(32).asBits(), b.clearGreaterThanOrEq(32));
    assertEquals(range.keepLessThan(63).asBits(), b.clearGreaterThanOrEq(63));
    assertEquals(range.keepLessThan(64).asBits(), b.clearGreaterThanOrEq(64));
    assertEquals(range.keepLessThan(97).asBits(), b.clearGreaterThanOrEq(97));

    assertEquals(range.keepGreaterThanOrEq(0).count(), b.countGreaterThanOrEq(0));
    assertEquals(range.keepGreaterThanOrEq(31).count(), b.countGreaterThanOrEq(31));
    assertEquals(range.keepGreaterThanOrEq(32).count(), b.countGreaterThanOrEq(32));
    assertEquals(range.keepGreaterThanOrEq(63).count(), b.countGreaterThanOrEq(63));
    assertEquals(range.keepGreaterThanOrEq(64).count(), b.countGreaterThanOrEq(64));
    assertEquals(range.keepGreaterThanOrEq(97).count(), b.countGreaterThanOrEq(97));
  }

  /// Given an IntPredicate for each argument, returns an IntPredicate for the result of applying
  /// the given op to those arguments.
  private static IntPredicate apply(Op op, IntPredicate x, IntPredicate y) {
    return switch (op) {
      case UNION -> x.or(y);
      case INTERSECTION -> x.and(y);
      case DIFFERENCE -> x.and(y.negate());
      case SYMM_DIFF -> i -> x.test(i) != y.test(i);
    };
  }

  /// Returns all possible (Op, Range, Range) argument tuples for [#rangeOps].
  private static Object[] allRangeOpTests() {
    return Arrays.stream(Op.values())
        .flatMap(op ->
            RANGES.stream().flatMap(r1 -> RANGES.stream().map(r2 -> new Object[] {op, r1, r2})))
        .toArray();
  }

  /// Systematically tests combining Builders, Bits, and ranges with Ops. Also checks testAll() and
  /// testAny().
  @Parameters(method = "allRangeOpTests")
  @TestCaseName("rangeOps_{1}_{0}_{2}")
  @Test
  public void rangeOps(Op op, Range r1, Range r2) {
    // Computes r1 op r2 several different ways; the results should all match.

    // The simplest approach: Bits.forRange() and Op.apply(Bits, Bits)
    Bits b1 = r1.asBits();
    Bits b2 = r2.asBits();
    Bits combined = op.apply(b1, b2);

    // Confirm that we got what we expected by comparing with Bits.fromPredicate()
    int max = Math.max(r1.max, r2.max);
    assertEquals(combined, Bits.fromPredicate(max, apply(op, r1::contains, r2::contains)));

    // Recompute, this time by using Builder.rangeInto() twice.
    Bits.Builder builder = new Bits.Builder();
    // Union into an empty builder should give us the same bits as b1.
    Op.UNION.rangeInto(builder, r1.min, r1.max);
    assertEquals(b1, builder.build());
    op.rangeInto(builder, r2.min, r2.max);
    assertEquals(combined, builder.build());

    // Recompute a third time, this time using Op.into(Builder, Bits)
    builder.clearAll();
    Op.UNION.rangeInto(builder, r1.min, r1.max);
    op.into(builder, b2);
    assertEquals(combined, builder.build());

    // A fourth time: using Op.into(Builder, Bits) again, but initializing the builder from a Bits
    // (which sometimes uses different code paths).
    builder.setAll(b1);
    op.into(builder, b2);
    assertEquals(combined, builder.build());

    // It's easy to check testAll() and testAny() while we're here.
    if (op == Op.INTERSECTION) {
      assertEquals(!combined.isEmpty(), b1.testAny(b2));
    } else if (op == Op.DIFFERENCE) {
      assertEquals(combined.isEmpty(), b2.testAll(b1));
    }
  }

  @Test
  public void filters() {
    IntPredicate even = i -> (i & 1) == 0;
    IntPredicate odd = i -> (i & 1) == 1;
    IntPredicate divisibleBy3 = i -> (i % 3) == 0;

    assertThat(Bits.EMPTY.filter(even)).isEqualTo(Bits.EMPTY);
    assertThat(Bits.forRange(3, 10).filter(even).toString()).isEqualTo("{4, 6, 8, 10}");
    assertThat(Bits.forRange(3, 10).filter(divisibleBy3).toString()).isEqualTo("{3, 6, 9}");
    assertThat(Bits.forRange(3, 10).filter(even).filter(odd)).isEqualTo(Bits.EMPTY);
    assertThat(Bits.forRange(3, 10).filter(even).filter(divisibleBy3)).isEqualTo(Bits.of(6));

    Bits bigEven = Bits.forRange(32, 128).filter(even);
    Bits bigDivisibleBy3 = Bits.forRange(32, 128).filter(divisibleBy3);
    assertThat(bigEven.count()).isEqualTo(49);
    assertThat(bigEven.max()).isEqualTo(128);
    assertThat(bigDivisibleBy3.count()).isEqualTo(32);
    assertThat(bigDivisibleBy3.max()).isEqualTo(126);
    String multiplesOf6 = "{36, 42, 48, 54, 60, 66, 72, 78, 84, 90, 96, 102, 108, 114, 120, 126}";
    assertThat(bigEven.filter(divisibleBy3).toString()).isEqualTo(multiplesOf6);
    assertThat(bigDivisibleBy3.filter(even).toString()).isEqualTo(multiplesOf6);
    assertThat(bigEven.filter(even)).isSameInstanceAs(bigEven);
    assertThat(bigEven.filter(odd)).isEqualTo(Bits.EMPTY);
  }

  @Test
  public void initializeForIntersection() {
    Bits.Builder builder = new Bits.Builder();
    builder.initializeForIntersection();
    assertThat(builder.isInitializedForIntersection()).isTrue();
    assertThrows(IllegalArgumentException.class, () -> builder.clear(3));
    Op.INTERSECTION.into(builder, Bits.forRange(2, 6));
    assertThat(builder.isInitializedForIntersection()).isFalse();
    assertThat(builder.build()).isEqualTo(Bits.forRange(2, 6));
    builder.initializeForIntersection();
    Op.INTERSECTION.into(builder, Bits.forRange(7, 10));
    Op.INTERSECTION.into(builder, Bits.forRange(10, 12));
    assertThat(builder.build()).isEqualTo(Bits.of(10));
  }

  @Test
  public void toStringOutput() {
    assertEquals("{}", Bits.EMPTY.toString());
    assertEquals("{4}", Bits.of(4).toString());
    assertEquals("{400}", Bits.of(400).toString());
    assertEquals("{4..6}", Bits.forRange(4, 6).toString());
    assertEquals("{400..402, 404..406}", Bits.forRange(400, 406).clear(403).toString());
    assertEquals(
        "{3, 5, 6, 9, 10}", Bits.fromPredicate(10, i -> Integer.bitCount(i) == 2).toString());
  }
}
