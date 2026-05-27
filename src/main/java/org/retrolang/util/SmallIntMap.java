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
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

/**
 * An efficient implementation of a map from smallish non-negative integers to (non-null) objects of
 * a given type.
 *
 * <p>A map with k elements is represented by an Object[] of length at least k whose first k
 * elements are the values in key order, and a long[] bitmap with enough elements to identify the
 * keys. Reads are technically linear in the key being looked up, but effectively constant time for
 * smallish keys (the intended use case; "smallish" here could mean < 256, although it should
 * degrade gracefully even with significantly larger keys). Adding a new key to a map is linear in
 * the number of elements already in the map, but again the constant is small enough (a shift of the
 * tail of the values array) that it's likely to be close to constant time for the expected uses.
 *
 * <p>Instances of SmallIntMap are immutable; instances of {@link SmallIntMap.Builder} are mutable.
 * They share all the read-only methods, and have the same implementation internally, so if you just
 * want a mutable map you can use a Builder without ever building it.
 */
public class SmallIntMap<E> extends SmallIntMapBase<E> {
  // All the non-static fields and methods of SmallIntMap are inherited from SmallIntBase.

  private static final long[] EMPTY_KEYS = new long[0];
  private static final Object[] EMPTY_VALUES = new Object[0];

  private static final SmallIntMap<Object> EMPTY = new SmallIntMap<>(EMPTY_KEYS, EMPTY_VALUES, 0);

  /** Returns an empty SmallIntMap. */
  @SuppressWarnings("unchecked")
  public static <E> SmallIntMap<E> empty() {
    return (SmallIntMap<E>) EMPTY;
  }

  /** The argument to {@link #forEachEntry}. */
  public interface EntryVisitor<E> extends EntryPredicate<E> {
    void visit(int key, E value);

    default boolean test(int key, E value) {
      visit(key, value);
      return true;
    }
  }

  /** The argument to {@link #allMatch}. */
  public interface EntryPredicate<E> {
    boolean test(int key, E value);
  }

  /** The argument to {@link Builder#updateEntries}. */
  public interface EntryUpdater<E> {
    E apply(int key, E value);
  }

  /** The argument to {@link Builder#innerJoin} or {@link Builder#outerJoin}. */
  public interface ValueCombiner<E> {
    E apply(int key, E value1, E value2);
  }

  private SmallIntMap(long[] keys, Object[] values, int numValues) {
    super(keys, values, numValues);
  }

  /**
   * A Builder can be used as a mutable map and/or to construct an immutable SmallIntMap instance.
   */
  public static class Builder<E> extends SmallIntMapBase<E> {
    /** Creates an empty Builder. */
    public Builder() {
      super(EMPTY_KEYS, EMPTY_VALUES, 0);
    }

    /**
     * Returns a SmallIntMap with the current contents of this Builder. Leaves the Builder empty.
     */
    public SmallIntMap<E> build() {
      if (numValues == 0) {
        return empty();
      }
      // Find the last non-zero element of keys (there must be one, since numValues != 0)
      int keyLength = keys.length;
      while (keys[keyLength - 1] == 0) {
        --keyLength;
      }
      // If the keys array is already the minimal length, take it; otherwise copy it to minimize
      // memory use (assuming that the SmallIntMap we return will outlive this Builder).
      long[] resultKeys;
      if (keyLength == keys.length) {
        resultKeys = keys;
        keys = EMPTY_KEYS;
      } else {
        resultKeys = Arrays.copyOf(keys, keyLength);
        // Leave the builder in a consistent state in case it's reused.
        Arrays.fill(keys, 0, keyLength, 0);
      }
      // ... and similarly for the values array.
      int valuesLength = roundUpValuesSize(numValues);
      Object[] resultValues;
      if (valuesLength == values.length) {
        resultValues = values;
        values = EMPTY_VALUES;
      } else {
        resultValues = Arrays.copyOf(values, valuesLength);
        Arrays.fill(values, 0, numValues, null);
      }
      int resultNumValues = numValues;
      numValues = 0;
      return new SmallIntMap<>(resultKeys, resultValues, resultNumValues);
    }

    /**
     * Adds or replaces the value for {@code key}. If {@code value} is null, deletes any existing
     * value for {@code key}.
     */
    public void put(int key, E value) {
      put(key, value, null);
    }

    /**
     * Adds or updates the value for {@code key}. If {@code value} is null, deletes any existing
     * value for {@code key}.
     *
     * <p>If {@code combiner} is non-null,
     *
     * <ul>
     *   <li>{@code value} must be non-null, and
     *   <li>if there is an existing value for {@code key}, {@code combiner} will be called to
     *       determine the new value.
     * </ul>
     */
    public void put(int key, E value, BinaryOperator<E> combiner) {
      Preconditions.checkArgument(key >= 0 && (combiner == null || value != null));
      int keyIndex = key / Long.SIZE;
      if (keyIndex >= keys.length) {
        if (value == null) {
          return;
        }
        keys = Arrays.copyOf(keys, keyIndex + 1);
      }
      long keyElement = keys[keyIndex];
      long bit = 1L << (key % Long.SIZE);
      int valuePos = Long.bitCount(keyElement & (bit - 1)) + countKeys(0, keyIndex);
      if (value != null) {
        long newKeyElement = keyElement | bit;
        if (newKeyElement != keyElement) {
          keys[keyIndex] = newKeyElement;
          if (numValues == values.length) {
            // This currently grows the values array as little as possible, which minimizes memory
            // use if we usually only add one or two values at a time.  If we tended to add large
            // batches we might do better with something like ArrayList's "grow by 50%" policy.
            values = Arrays.copyOf(values, roundUpValuesSize(numValues + 1));
          }
          // Shift up all elements of the values array starting at valuePos
          System.arraycopy(values, valuePos, values, valuePos + 1, numValues - valuePos);
          ++numValues;
        } else if (combiner != null) {
          @SuppressWarnings("unchecked")
          E prev = (E) values[valuePos];
          value = combiner.apply(prev, value);
        }
        if (value != null) {
          values[valuePos] = value;
          return;
        }
      }
      // value is null; remove the element
      long newKeyElement = keyElement & ~bit;
      if (newKeyElement != keyElement) {
        keys[keyIndex] = newKeyElement;
        deleteValue(valuePos);
      }
    }

    /** Removes all elements from this Builder. */
    public void clear() {
      if (numValues != 0) {
        Arrays.fill(keys, 0);
        Arrays.fill(values, null);
        numValues = 0;
      }
    }

    /**
     * Initializes this builder to contain the same elements as the given SmallIntMap or Builder.
     */
    @SuppressWarnings("ReferenceEquality")
    public void setFrom(SmallIntMapBase<E> src) {
      // Trying to set a Builder from itself seems likely to be a bug.
      Preconditions.checkArgument(src != this);
      if (keys.length < src.keys.length) {
        keys = Arrays.copyOf(src.keys, src.keys.length);
      } else {
        System.arraycopy(src.keys, 0, keys, 0, src.keys.length);
        Arrays.fill(keys, src.keys.length, keys.length, 0);
      }
      if (values.length < src.numValues) {
        values = Arrays.copyOf(src.values, roundUpValuesSize(src.numValues));
      } else {
        System.arraycopy(src.values, 0, values, 0, src.numValues);
        if (src.numValues < numValues) {
          Arrays.fill(values, src.numValues, numValues, null);
        }
      }
      numValues = src.numValues;
    }

    /**
     * Calls {@code updater.apply()} with each value in this map, and replaces the value with its
     * result. {@code apply()} must not return null
     *
     * <p>Equivalent to {@code updateEntries((i, v) -> updater.apply(v))}, but more efficient if you
     * don't need the keys and don't need to remove values.
     */
    public void updateValues(UnaryOperator<E> updater) {
      for (int i = 0; i < numValues; i++) {
        @SuppressWarnings("unchecked")
        E v = (E) values[i];
        v = updater.apply(v);
        Preconditions.checkArgument(v != null);
        values[i] = v;
      }
    }

    /**
     * Calls {@code updater.apply()} with each key/value pair in this map, and replaces the value
     * with its result. If {@code apply()} returns null the entry will be removed.
     */
    public void updateEntries(EntryUpdater<E> updater) {
      visitEntries(null, updater);
    }

    /**
     * Modifies this Builder by joining its contents with those of {@code other}.
     *
     * <p>For each {@code (k, thisVal)} in this builder, let {@code otherVal} be {@code
     * other.apply(k)}; then:
     *
     * <ul>
     *   <li>If {@code thisVal == otherVal} then the value for {@code k} is unchanged.
     *   <li>If {@code otherVal} is null then the value for {@code k} is deleted.
     *   <li>Otherwise the value for {@code k} is replaced with {@code combiner.apply(k, thisVal,
     *       otherVal)}, or deleted if {@code combiner} returns null.
     * </ul>
     *
     * <p>{@code combiner.apply()} may do read operations on this Builder, but must not modify it.
     * Such read operations may see some elements modified by the join and others not yet modified.
     */
    public void innerJoin(SmallIntMapBase<E> other, ValueCombiner<E> combiner) {
      join(other, false, combiner);
    }

    /**
     * Modifies this Builder by joining its contents with those of {@code other}.
     *
     * <p>For each {@code k} that has a value in {@code this} or {@code other}, let {@code thisVal}
     * be {@code this.apply(k)} and {@code otherVal} be {@code other.apply(k)}; then
     *
     * <ul>
     *   <li>If {@code thisVal == otherVal} or {@code otherVal} is null, then the value for {@code
     *       k} is unchanged.
     *   <li>If {@code thisVal} is null then {@code otherVal} is inserted as the value for {@code
     *       k}.
     *   <li>Otherwise ({@code thisVal != otherVal} and neither is null), the value for {@code k} is
     *       replaced with {@code combiner.apply(k, thisVal, otherVal)}, or deleted if {@code
     *       combiner} returns null.
     * </ul>
     *
     * <p>{@code combiner.apply()} may do read operations on this Builder, but must not modify it.
     * Such read operations may see some elements modified by the join and others not yet modified.
     */
    public void outerJoin(SmallIntMapBase<E> other, ValueCombiner<E> combiner) {
      join(other, true, combiner);
    }

    /**
     * Modifies this Builder by joining its contents with those of {@code other}.
     *
     * <p>For each {@code k} that has a value in {@code this} or {@code other}, let {@code thisVal}
     * be {@code this.apply(k)} and {@code otherVal} be {@code other.apply(k)}; then
     *
     * <ul>
     *   <li>If {@code thisVal == otherVal}, or {@code outerJoin} is true and {@code otherVal} is
     *       null, or {@code outerJoin} is false and {@code thisVal} is null, then the value for
     *       {@code k} is unchanged.
     *   <li>If {@code outerJoin} is false and {@code otherVal} is null then the value for {@code k}
     *       is deleted.
     *   <li>If {@code outerJoin} is true and {@code thisVal} is null then {@code otherVal} is
     *       inserted as the value for {@code k}.
     *   <li>Otherwise ({@code thisVal != otherVal} and neither is null), the value for {@code k} is
     *       replaced with {@code combiner.apply(k, thisVal, otherVal)}, or deleted if {@code
     *       combiner} returns null.
     * </ul>
     *
     * <p>{@code combiner.apply()} may do read operations on this Builder, but must not modify it.
     * Such read operations may see some elements modified by the join and others not yet modified.
     */
    @SuppressWarnings({"unchecked", "ReferenceEquality"})
    private void join(SmallIntMapBase<E> other, boolean outerJoin, ValueCombiner<E> combiner) {
      if (other == this) {
        // A self-join is always a no-op.
        return;
      }
      int commonLength = Math.min(keys.length, other.keys.length);
      if (outerJoin) {
        // Ensure that we've got enough space to insert all the unmatched elements of other
        // (conservatively assuming that none of the combined values are dropped).
        int maxNumValues =
            numValues
                + IntStream.range(0, commonLength)
                    .map(i -> Long.bitCount(other.keys[i] & ~keys[i]))
                    .sum();
        if (commonLength < other.keys.length) {
          maxNumValues += other.countKeys(commonLength, other.keys.length);
        }
        if (maxNumValues > values.length) {
          values = Arrays.copyOf(values, roundUpValuesSize(maxNumValues));
        }
      } else if (commonLength < keys.length) {
        // Inner join: drop any values that don't have corresponding key elements in other
        int numDropped = countKeys(commonLength, keys.length);
        if (numDropped != 0) {
          Arrays.fill(keys, commonLength, keys.length, 0);
          deleteValueRange(numValues - numDropped, numValues);
        }
      }
      // We process one key word (64 bits) at a time, keep track of our current position in
      // each values array.
      int thisValuePos = 0;
      int otherValuePos = 0;
      for (int i = 0; i < commonLength; i++) {
        // Get the corresponding key words.  We will use these to keep track of which entries
        // we still need to process; when both are zero we can move on to the next key word.
        long thisKeyElement = keys[i];
        long otherKeyElement = other.keys[i];
        // Each iteration of this loop processes a matching pair, i.e. a bit that is set in both
        // key elements.
        for (; ; ) {
          long common = thisKeyElement & otherKeyElement;
          // Before we process the matching key, we first have to take care of any non-matching
          // values before the matching key (even if there are no more matching keys in this word,
          // we still have to take care of any remaining non-matching ones; that's why we do this
          // before checking common == 0 below).
          long skip = bitsBelowLowestOneBit(common);
          long thisSkip = thisKeyElement & skip;
          long otherSkip = otherKeyElement & skip;
          if (!outerJoin) {
            // For inner join all we need to do is delete values from this and skip values in other.
            if (thisSkip != 0) {
              deleteValueRange(thisValuePos, thisValuePos + Long.bitCount(thisSkip));
              keys[i] -= thisSkip;
            }
            otherValuePos += Long.bitCount(otherSkip);
          } else if (thisSkip != 0 || otherSkip != 0) {
            // Outer join is trickier: we need to insert unmatched elements from other,
            // and the positions to insert them depend on the unmatched elements in this.
            // This loop will process alternating runs of unmatched elements from this, then from
            // other, until all unmatched elements have been processed.  We could do it one element
            // at a time, but it's just as easy to handle a whole run and performs better in the
            // case where one argument is empty or very sparse.
            keys[i] += otherSkip;
            for (; ; ) {
              // Find all the unmatched elements in this before the first unmatched element of other
              long thisBeforeOther = thisSkip & bitsBelowLowestOneBit(otherSkip);
              thisValuePos += Long.bitCount(thisBeforeOther);
              thisSkip -= thisBeforeOther;
              if (otherSkip == 0) {
                assert thisSkip == 0;
                break;
              }
              long otherBeforeThis = otherSkip & bitsBelowLowestOneBit(thisSkip);
              assert otherBeforeThis != 0;
              int n = Long.bitCount(otherBeforeThis);
              // Insert next n elements from other into this
              System.arraycopy(
                  values, thisValuePos, values, thisValuePos + n, numValues - thisValuePos);
              System.arraycopy(other.values, otherValuePos, values, thisValuePos, n);
              numValues += n;
              thisValuePos += n;
              otherValuePos += n;
              otherSkip -= otherBeforeThis;
            }
          }
          if (common == 0) {
            break;
          }
          E thisV = (E) values[thisValuePos];
          E otherV = (E) other.values[otherValuePos];
          if (thisV != otherV) {
            int k = (i * Long.SIZE) + Long.numberOfTrailingZeros(common);
            E combined = combiner.apply(k, thisV, otherV);
            if (combined == null) {
              deleteValue(thisValuePos--);
              keys[i] -= Long.lowestOneBit(common);
            } else {
              values[thisValuePos] = combined;
            }
          }
          ++thisValuePos;
          ++otherValuePos;
          // Remove the common bit plus any bits we skipped, from thisKeyElement and otherKeyElement
          long keep = (~skip) << 1;
          thisKeyElement &= keep;
          otherKeyElement &= keep;
        }
      }
      // Any values that we haven't looked at yet must correspond to keys that are outside
      // other's range...
      assert numValues - thisValuePos == countKeys(commonLength, keys.length);
      // ... and vice-versa.
      assert other.numValues - otherValuePos == other.countKeys(commonLength, other.keys.length);
      if (outerJoin) {
        int remaining = other.numValues - otherValuePos;
        if (remaining != 0) {
          // Append other's extra key elements to this.keys
          assert other.keys.length > keys.length;
          long[] newKeys = Arrays.copyOf(keys, other.keys.length);
          System.arraycopy(
              other.keys, keys.length, newKeys, keys.length, other.keys.length - keys.length);
          this.keys = newKeys;
          // We already grew the values array before we started
          System.arraycopy(other.values, otherValuePos, values, thisValuePos, remaining);
          numValues += remaining;
        }
      } else {
        // For an inner join we deleted any trailing unmatched values before we started the loop
        assert thisValuePos == numValues;
      }
    }
  }
}
