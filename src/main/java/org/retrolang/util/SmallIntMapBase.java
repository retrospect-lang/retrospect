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
import java.util.Arrays;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * The common superclass of {@link SmallIntMap} and {@link SmallIntMap.Builder}; includes all the
 * non-mutating methods.
 */
public abstract class SmallIntMapBase<E> implements IntFunction<E> {
  /** This map contains key {@code k} (>= 0) if bit {@code k%64} of {@code keys[k/64]} is 1. */
  long[] keys;

  /**
   * The (non-null) values stored in this map, in ascending key order.
   *
   * <p>Any unused elements at the end of the array are null, i.e. {@code Arrays.stream(values,
   * numValues, values.length).allMatch(v -> v == null)}.
   */
  Object[] values;

  /** The total number of bits set in {@link #keys}. */
  int numValues;

  SmallIntMapBase(long[] keys, Object[] values, int numValues) {
    this.keys = keys;
    this.values = values;
    this.numValues = numValues;
  }

  /** Returns true if this map contains no entries. */
  public boolean isEmpty() {
    return numValues == 0;
  }

  /** Returns the number of keys with non-null values. */
  public int size() {
    return numValues;
  }

  /** Returns true if {@link #get} would return a non-null value for the given key. */
  public boolean containsKey(int key) {
    Preconditions.checkArgument(key >= 0);
    int keyIndex = key / Long.SIZE;
    if (keyIndex >= keys.length) {
      return false;
    }
    long keyElement = keys[keyIndex];
    long mask = 1L << (key % Long.SIZE);
    return (keyElement & mask) != 0;
  }

  /**
   * Returns the smallest key for which {@link #get} would return a non-null value.
   *
   * <p>{@code isEmpty()} must be false.
   */
  public int minKey() {
    for (int i = 0; ; i++) {
      long keyElement = keys[i];
      if (keyElement != 0) {
        return Long.numberOfTrailingZeros(keyElement) + Long.SIZE * i;
      }
    }
  }

  /**
   * Returns the largest key for which {@link #get} would return a non-null value.
   *
   * <p>{@code isEmpty()} must be false.
   */
  public int maxKey() {
    for (int i = keys.length - 1; ; i--) {
      long keyElement = keys[i];
      if (keyElement != 0) {
        int topZeros = Long.numberOfLeadingZeros(keyElement);
        return Long.SIZE * i + 63 - topZeros;
      }
    }
  }

  /**
   * Returns the value corresponding to the given key, which must be non-negative. Returns null if
   * the given key is not present.
   */
  @SuppressWarnings("unchecked")
  public E get(int key) {
    Preconditions.checkArgument(key >= 0);
    int keyIndex = key / Long.SIZE;
    if (keyIndex >= keys.length) {
      return null;
    }
    long keyElement = keys[keyIndex];
    long mask = 1L << (key % Long.SIZE);
    if ((keyElement & mask) == 0) {
      return null;
    }
    // Count the number of set key bits before this one to get its index in the values array.
    int valuePos = Long.bitCount(keyElement & (mask - 1)) + countKeys(0, keyIndex);
    return (E) values[valuePos];
  }

  /** A synonym for {@link #get}, so that a SmallIntMap can be used as an {@link IntFunction}. */
  @Override
  public E apply(int value) {
    return get(value);
  }

  /** Returns a Stream of this map's current values, in key order. */
  @SuppressWarnings("unchecked")
  public Stream<E> streamValues() {
    return (Stream<E>) Arrays.stream(values, 0, numValues);
  }

  /** Calls {@code visitor.visit()} with each key/value pair in this map. */
  public void forEachEntry(SmallIntMap.EntryVisitor<E> visitor) {
    visitEntries(visitor, null);
  }

  /** Returns true if {@code predicate.test()} returns true for each key/value pair in this map. */
  public boolean allMatch(SmallIntMap.EntryPredicate<E> predicate) {
    return visitEntries(predicate, null);
  }

  @Override
  public int hashCode() {
    // This would be easier if java.util.Arrays provided hashCode(long[], start, end) and
    // hashCode(Object[], start, end) methods.
    int result = 1;
    boolean sawNonZero = false;
    for (int i = keys.length - 1; i >= 0; i--) {
      long k = keys[i];
      if (k != 0 || sawNonZero) {
        result = 31 * result + Long.hashCode(k);
        sawNonZero = true;
      }
    }
    for (int i = 0; i < numValues; i++) {
      result = 31 * result + values[i].hashCode();
    }
    return result;
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof SmallIntMapBase<?>) && equals((SmallIntMapBase<?>) other);
  }

  /** Returns true if {@code other} contains the same entries as this map. */
  @SuppressWarnings("NonOverridingEquals")
  public boolean equals(SmallIntMapBase<?> other) {
    return matches(other, Object::equals);
  }

  /**
   * Returns true if {@code other} contains entries with the same keys as this map, and each pair of
   * corresponding values is identical or returns true from {@code matcher}.
   */
  @SuppressWarnings("ReferenceEquality")
  public boolean matches(SmallIntMapBase<?> other, BiPredicate<E, E> matcher) {
    if (other == this) {
      return true;
    } else if (other.numValues != numValues) {
      return false;
    } else if (numValues == 0) {
      return true;
    }
    int commonLength = Math.min(keys.length, other.keys.length);
    if (!Arrays.equals(keys, 0, commonLength, other.keys, 0, commonLength)) {
      return false;
    }
    // If they have the same numValues and the keys arrays match up to the length of the shorter
    // one, any additional elements in the longer keys array must be zero.
    assert Arrays.stream(keys, commonLength, keys.length).allMatch(k -> k == 0);
    assert Arrays.stream(other.keys, commonLength, other.keys.length).allMatch(k -> k == 0);
    // They have the same keys, so all that's left to check is whether they have the same values.
    for (int i = 0; i < numValues; i++) {
      @SuppressWarnings("unchecked")
      E v1 = (E) values[i];
      @SuppressWarnings("unchecked")
      E v2 = (E) other.values[i];
      if (v1 != v2 && !matcher.test(v1, v2)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if for each {@code (k, v2)} in {@code other}, there is a corresponding {@code (k,
   * v)} in this map and {@code valueContains(v, v2)} returns true.
   */
  @SuppressWarnings({"unchecked", "ReferenceEquality"})
  public boolean contains(SmallIntMapBase<E> other, BiPredicate<E, E> valueContains) {
    if (other == this || other.numValues == 0) {
      return true;
    } else if (other.numValues > numValues) {
      return false;
    }
    // If there are any unmatched bits in other's key array we can stop now.
    for (int i = 0; i < other.keys.length; i++) {
      long fromKey = (i < keys.length) ? keys[i] : 0;
      if ((other.keys[i] & ~fromKey) != 0) {
        return false;
      }
    }
    int commonLength = Math.min(keys.length, other.keys.length);
    int thisValuePos = 0;
    int otherValuePos = 0;
    for (int i = 0; i < commonLength; i++) {
      long thisKeyElement = keys[i];
      long otherKeyElement = other.keys[i];
      while (otherKeyElement != 0) {
        long skip = bitsBelowLowestOneBit(otherKeyElement);
        thisValuePos += Long.bitCount(thisKeyElement & skip);
        E thisV = (E) values[thisValuePos++];
        E otherV = (E) other.values[otherValuePos++];
        if (!valueContains.test(thisV, otherV)) {
          return false;
        }
        // Remove the common bit plus any bits we skipped, from thisKeyElement and otherKeyElement
        long keep = (~skip) << 1;
        otherKeyElement &= keep;
        thisKeyElement &= keep;
      }
      thisValuePos += Long.bitCount(thisKeyElement);
    }
    assert otherValuePos == other.numValues;
    assert numValues - thisValuePos == countKeys(commonLength, keys.length);
    return true;
  }

  /** Formats the contents as a string, using the given functions to format each key and value. */
  public String toString(IntFunction<String> keyToString, Function<E, String> valueToString) {
    if (numValues == 0) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder();
    forEachEntry(
        (k, v) ->
            sb.append(' ')
                .append(keyToString.apply(k))
                .append(':')
                .append(valueToString.apply(v))
                .append(','));
    sb.setCharAt(0, '{');
    sb.setCharAt(sb.length() - 1, '}');
    return sb.toString();
  }

  @Override
  public String toString() {
    return toString(String::valueOf, String::valueOf);
  }

  /**
   * The shared implementation of {@link #forEachEntry}, {@link #allMatch}, and {@link
   * SmallIntMap.Builder#updateEntries}. Exactly one of {@code predicate} or {@code updater} must be
   * non-null.
   */
  boolean visitEntries(
      SmallIntMap.EntryPredicate<E> predicate, SmallIntMap.EntryUpdater<E> updater) {
    assert (predicate == null) != (updater == null);
    int valuePos = 0;
    for (int i = 0; i < keys.length; i++) {
      for (long keyElement = keys[i]; keyElement != 0; keyElement &= (keyElement - 1)) {
        int k = (i * Long.SIZE) + Long.numberOfTrailingZeros(keyElement);
        @SuppressWarnings("unchecked")
        E v = (E) values[valuePos];
        if (predicate != null) {
          if (!predicate.test(k, v)) {
            return false;
          }
        } else {
          v = updater.apply(k, v);
          if (v == null) {
            deleteValue(valuePos--);
            keys[i] &= ~Long.lowestOneBit(keyElement);
          } else {
            values[valuePos] = v;
          }
        }
        valuePos++;
      }
    }
    assert valuePos == numValues;
    return true;
  }

  /**
   * Returns the number of bits set in elements of {@link #keys} from {@code start} (inclusive) to
   * {@code end} (exclusive).
   */
  int countKeys(int start, int end) {
    return (int) Arrays.stream(keys, start, end).map(Long::bitCount).sum();
  }

  /** Removes the value at the given position; caller is responsible for updating {@link #keys}. */
  void deleteValue(int pos) {
    assert this instanceof SmallIntMap.Builder<?>;
    --numValues;
    System.arraycopy(values, pos + 1, values, pos, numValues - pos);
    values[numValues] = null;
  }

  /**
   * Removes the values between start (inclusive) and end (exclusive); caller is responsible for
   * updating {@link #keys}.
   */
  void deleteValueRange(int start, int end) {
    assert this instanceof SmallIntMap.Builder<?>;
    int prevNumValues = numValues;
    numValues -= (end - start);
    System.arraycopy(values, end, values, start, prevNumValues - end);
    Arrays.fill(values, numValues, prevNumValues, null);
  }

  /** Chooses a size for the {@code #values} array that can store at least {@code size} elements. */
  static int roundUpValuesSize(int size) {
    // We might as well round up to next multiple of 2, since Java allocates arrays in units of 8
    // bytes anyway.
    return (size + 1) & ~1;
  }

  /**
   * Returns a mask with all bits below the lowest one bit of x set. Returns -1 (all bits set) if x
   * is 0.
   */
  static long bitsBelowLowestOneBit(long x) {
    // Or, equivalently: return (x - 1) & ~x;
    return Long.lowestOneBit(x) - 1;
  }
}
