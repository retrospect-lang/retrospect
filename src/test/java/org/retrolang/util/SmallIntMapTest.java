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

import com.google.common.collect.ImmutableList;
import java.util.function.BinaryOperator;
import java.util.stream.IntStream;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class SmallIntMapTest {

  @Test
  public void basics() {
    SmallIntMap.Builder<String> builder = new SmallIntMap.Builder<>();
    assertThat(builder.get(1)).isNull();
    builder.put(0, "zero");
    assertThat(builder.get(1)).isNull();
    BinaryOperator<String> concat = (x, y) -> x + " " + y;
    builder.put(200, "two", concat);
    builder.put(200, "hundred", concat);
    builder.put(3, "three");
    assertThat(builder.get(200)).isEqualTo("two hundred");
    assertThat(builder.get(1000)).isNull();
    assertThat(builder.size()).isEqualTo(3);
    assertThat(builder.minKey()).isEqualTo(0);
    assertThat(builder.maxKey()).isEqualTo(200);
    assertThat(builder.allMatch((i, k) -> (i < 100) == (k.indexOf('e') >= i))).isTrue();
    assertThat(builder.allMatch((i, k) -> (i < 100) == (k.indexOf('e') > i))).isFalse();
    SmallIntMap<String> map1 = builder.build();
    assertThat(builder.isEmpty()).isTrue();
    assertThat(builder.hashCode()).isNotEqualTo(map1.hashCode());
    builder.setFrom(map1);
    assertThat(builder.hashCode()).isEqualTo(map1.hashCode());
    assertThat(builder).isEqualTo(map1);
    builder.put(0, null);
    builder.put(300, null);
    BinaryOperator<String> removeIfLonger = (x, y) -> (x.length() > y.length()) ? null : x;
    builder.put(3, "whatever", removeIfLonger);
    builder.put(200, "whatever", removeIfLonger);
    SmallIntMap<String> map2 = builder.build();
    assertThat(map1.contains(map2, String::equals)).isTrue();
    assertThat(map2.contains(map1, String::equals)).isFalse();
    assertThat(map1.toString()).isEqualTo("{0:zero, 3:three, 200:two hundred}");
    assertThat(map2.toString()).isEqualTo("{3:three}");
  }

  @Test
  public void equals() {
    SmallIntMap.Builder<String> builder = new SmallIntMap.Builder<>();
    builder.put(0, "zero");
    builder.put(200, "two hundred");
    builder.put(3, "three");
    SmallIntMap<String> map1 = builder.build();
    builder.put(0, "zero");
    builder.put(2, "two");
    builder.put(3, "three");
    SmallIntMap<String> map2 = builder.build();
    assertThat(map1).isNotEqualTo(map2);
  }

  @Test
  public void matches() {
    SmallIntMap.Builder<String> builder = new SmallIntMap.Builder<>();
    builder.put(0, "zero");
    builder.put(2, "two hundred");
    builder.put(3, "three");
    SmallIntMap<String> map1 = builder.build();
    builder.put(0, "zero");
    builder.put(2, "two");
    builder.put(3, "three");
    SmallIntMap<String> map2 = builder.build();
    // They match if you only look at the first 3 chars of each value
    assertThat(map1.matches(map2, (x, y) -> x.regionMatches(0, y, 0, 3))).isTrue();
    // ... but differ if you look at the first 4
    assertThat(map1.matches(map2, (x, y) -> x.regionMatches(0, y, 0, 4))).isFalse();
  }

  @Test
  public void updateEntries() {
    SmallIntMap.Builder<Integer> builder = new SmallIntMap.Builder<>();
    for (int i = 5; i <= 10; i++) {
      builder.put(i, i * 10);
    }
    SmallIntMap<Integer> map1 = builder.build();
    builder.setFrom(map1);
    builder.updateEntries((k, v) -> (k % 3) == 0 ? null : v + k);
    assertThat(builder.toString()).isEqualTo("{5:55, 7:77, 8:88, 10:110}");
    assertThat(map1.contains(builder, Integer::equals)).isFalse();
    assertThat(map1.contains(builder, (v1, v2) -> v2.equals(11 * (v1 / 10)))).isTrue();
  }

  @Test
  public void updateValues() {
    SmallIntMap.Builder<String> builder = new SmallIntMap.Builder<>();
    for (int i = 5; i <= 7; i++) {
      builder.put(i, "x" + i);
    }
    builder.updateValues(v -> v + "y");
    assertThat(builder.toString()).isEqualTo("{5:x5y, 6:x6y, 7:x7y}");
  }

  /**
   * Exercises {@link SmallIntMap.Builder#innerJoin} by calling it with different combinations of
   * SmallIntMaps. Each Sequence maps each key in an arithmetic sequence to itself. The right-hand
   * side of the join is just the map from {@code s2}. For the left-hand side we start with the map
   * from {@code s1}, but then apply {@link #tweakLeft} to each value; this ensures that the
   * corresponding values from left and right are sometimes the same and sometimes different. The
   * ValueCombiner (used when the values differ) sometimes returns null to test dropping elements.
   *
   * <p>{@link JoinChecker} is used to verify that the result of the join is correct.
   */
  @Parameters(method = "sequencePairs")
  @Test
  public void innerJoin(Sequence s1, Sequence s2) {
    SmallIntMap.Builder<Integer> builder = new SmallIntMap.Builder<>();
    builder.setFrom(s1.map);
    builder.updateValues(SmallIntMapTest::tweakLeft);
    builder.innerJoin(s2.map, COMBINER);
    new JoinChecker(s1, s2, false).check(builder);
    // We can also test the contains() method -- the join result should be contained in both inputs.
    assertThat(s1.map.contains(builder, (v1, joinResult) -> joinResult.equals(expected(v1))))
        .isTrue();
    assertThat(s2.map.contains(builder, (v2, joinResult) -> joinResult.equals(expected(v2))))
        .isTrue();
  }

  /**
   * Exercises {@link SmallIntMap.Builder#outerJoin} using the same approach as {@link #innerJoin}.
   */
  @Parameters(method = "sequencePairs")
  @Test
  public void outerJoin(Sequence s1, Sequence s2) {
    SmallIntMap.Builder<Integer> builder = new SmallIntMap.Builder<>();
    builder.setFrom(s1.map);
    builder.updateValues(SmallIntMapTest::tweakLeft);
    builder.outerJoin(s2.map, COMBINER);
    new JoinChecker(s1, s2, true).check(builder);
    // Note that because our ValueCombiner sometimes returns null, we can't do a contains() test
    // comparable to the one in innerJoin().
  }

  /** Represents an increasing sequence of regularly-spaced integers. */
  private static class Sequence {
    final int start;
    final int step;
    final int size;

    /** A SmallIntMap that maps each int in this sequence to itself. */
    final SmallIntMap<Integer> map;

    Sequence(int start, int step, int size) {
      this.start = start;
      this.step = step;
      this.size = size;
      SmallIntMap.Builder<Integer> builder = new SmallIntMap.Builder<>();
      IntStream.range(0, size).map(i -> start + i * step).forEach(i -> builder.put(i, i));
      map = builder.build();
    }

    boolean contains(int i) {
      boolean result = (i >= start) && ((i - start) % step == 0) && (i < start + step * size);
      // Might as well test containsKey() while we're here
      assertThat(map.containsKey(i)).isEqualTo(result);
      return result;
    }

    /**
     * Returns the last element of the sequence, or {@code start - step} if the sequence is empty.
     */
    int last() {
      return start + (size - 1) * step;
    }

    @Override
    public String toString() {
      if (size == 0) {
        return "[]";
      } else if (size == 1) {
        return "[" + start + "]";
      } else {
        String end = (size == 2) ? "" : ", ... " + last();
        return String.format("[%s, %s%s]", start, start + step, end);
      }
    }
  }

  /**
   * Sequences for {@link #innerJoin} and {@link #outerJoin}. The order they appear here doesn't
   * matter; we test all possible pairs.
   *
   * <p>Since the key masks are allocated in units of 64 bits, I've tried to ensure that we exercise
   * different mask lengths, masks with all zeros in leading elements, etc.
   */
  static final ImmutableList<Sequence> SEQUENCES =
      ImmutableList.of(
          new Sequence(0, 1, 0),
          new Sequence(1, 1, 2),
          new Sequence(0, 2, 41),
          new Sequence(100, 2, 41),
          new Sequence(0, 3, 9),
          new Sequence(2, 5, 50),
          new Sequence(3, 7, 3),
          new Sequence(0, 11, 20),
          new Sequence(3, 13, 30),
          new Sequence(0, 200, 5));

  /** An int larger than any element of the sequences in {@link #SEQUENCES}. */
  static final int SEQUENCES_LIMIT =
      SEQUENCES.stream().mapToInt(Sequence::last).max().getAsInt() + 1;

  /** Returns all pairs of elements from {@link #SEQUENCES}. */
  private static Object[] sequencePairs() {
    return SEQUENCES.stream()
        .flatMap(s -> SEQUENCES.stream().map(s2 -> new Object[] {s, s2}))
        .toArray();
  }

  /**
   * A ValueCombiner for Integers. The details are arbitrary, but to find bugs we want something
   * non-symmetric that depends on both arguments and sometimes returns null.
   */
  static final SmallIntMap.ValueCombiner<Integer> COMBINER =
      (k, x, y) -> {
        // The combiner should not be called when the values are identical
        assertThat(x).isNotSameInstanceAs(y);
        // We don't use k, but for this test it should always match y
        assertThat(k).isEqualTo(y);
        int result = 2 * x + y;
        return (result % 5 == 0) ? null : result;
      };

  /**
   * Verifies that a join operation returned the expected result. After initialization, call {@link
   * #check} with the result of the join.
   */
  static class JoinChecker {
    final Sequence s1;
    final Sequence s2;
    final boolean outerJoin;

    int prev = -1;

    /**
     * Creates a JoinChecker that expects to see the results of s1 (after applying {@link
     * #tweakLeft} to each of its elements) joined with s2.
     *
     * <p>A JoinChecker should only be used once.
     */
    JoinChecker(Sequence s1, Sequence s2, boolean outerJoin) {
      this.s1 = s1;
      this.s2 = s2;
      this.outerJoin = outerJoin;
    }

    /** Verify the given result. */
    void check(SmallIntMap.Builder<Integer> result) {
      result.forEachEntry(this::checkNext);
      checkUpTo(SEQUENCES_LIMIT);
    }

    private void checkNext(int k, Integer v) {
      assertThat(k).isGreaterThan(prev);
      assertThat(v).isNotNull();
      // First make sure that any we skipped should have been skipped
      checkUpTo(k);
      prev = k;
      if (!s1.contains(k)) {
        // If we got an element for a key that's not in s1, this must be an outer join and
        // we should have copied the entry from s2.
        assertThat(outerJoin).isTrue();
        assertThat(s2.contains(k)).isTrue();
        assertThat(v).isEqualTo(k);
      } else if (!s2.contains(k)) {
        // Similarly for a key that's in s2 but not s1, although this time we expect the value
        // to have been tweaked.
        assertThat(outerJoin).isTrue();
        assertThat(v).isEqualTo(tweakLeft(k));
      } else {
        assertThat(v).isEqualTo(expected(k));
      }
    }

    /**
     * Verifies that none of the keys between {@link #prev} and {@code k} were expected to have a
     * value.
     */
    private void checkUpTo(int k) {
      for (int i = prev + 1; i < k; i++) {
        if (s1.contains(i) && s2.contains(i)) {
          // Should only have been skipped if this is a combination the combiner drops
          assertThat(expected(i)).isNull();
        } else if (outerJoin) {
          // Should only have been skipped if neither sequence contains i
          assertThat(s1.contains(i)).isFalse();
          assertThat(s2.contains(i)).isFalse();
        }
      }
    }
  }

  /**
   * Arbitrarily modify some elements of the left argument to join (since all of our sequences have
   * the key=value, if we didn't do this the ValueCombiner would never be used).
   */
  @SuppressWarnings("UnnecessaryBoxedVariable")
  static Integer tweakLeft(Integer v) {
    return (v % 2 == 0) ? v + 1 : v;
  }

  /** Returns the value expected for a join with matching keys. */
  @SuppressWarnings({"BoxedPrimitiveEquality", "ReferenceEquality"})
  static Integer expected(int k) {
    Integer left = tweakLeft(k);
    Integer right = k;
    return (left == right) ? left : COMBINER.apply(k, left, right);
  }
}
