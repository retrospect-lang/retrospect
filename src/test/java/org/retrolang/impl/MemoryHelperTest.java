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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.util.stream.IntStream;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.retrolang.util.SizeOf;

@RunWith(JUnitParamsRunner.class)
public class MemoryHelperTest {

  /** A simple class containing two Tuples and an Object[], to exercise the refcounting logic. */
  static class Tuple extends RefCounted {
    /** The number of bytes used to store a single Tuple. */
    private static final long SIZE = SizeOf.object(BASE_SIZE + 3 * SizeOf.PTR);

    final Tuple left;
    final Tuple right;

    /** The Tuple constructor also creates an Object[] just so that we exercise more of the API. */
    private final Object[] data;

    /** Creates a new Tuple with the given values, updating their refcounts appropriately. */
    @RC.Out
    Tuple(Allocator allocator, Tuple left, Tuple right, int arrayLength) {
      this.left = left;
      RefCounted.addRef(left);
      this.right = right;
      RefCounted.addRef(right);
      this.data = allocator.allocObjectArray(arrayLength);
      if (arrayLength > 0) {
        this.data[0] = left;
        RefCounted.addRef(left);
      }
      if (arrayLength > 1) {
        this.data[1] = right;
        RefCounted.addRef(right);
      }
      if (arrayLength > 2) {
        // Throw in a non-refcounted, non-array object, which should be OK
        this.data[2] = arrayLength;
      }
      allocator.recordAlloc(this, SIZE);
    }

    /** The total number of bytes allocated by the Tuple constructor. */
    static long totalSize(int arrayLength, boolean debug) {
      return SIZE + MemoryHelper.objectArraySize(arrayLength, debug);
    }

    static long totalSize(boolean debug, int... arrayLengths) {
      return IntStream.of(arrayLengths)
          .mapToLong(len -> SIZE + MemoryHelper.objectArraySize(len, debug))
          .sum();
    }

    @Override
    protected long visitRefs(RefVisitor visitor) {
      visitor.visit(left);
      visitor.visit(right);
      visitor.visit(data);
      return SIZE;
    }
  }

  /** Allocates a few linked Tuples and verifies that reference counting works as expected. */
  @Test
  @Parameters({"false", "true"})
  public void basic(boolean debug) {
    int memoryLimit = 3000;
    ResourceTracker tracker = new ResourceTracker(null, memoryLimit, debug);
    MemoryHelper mh = new MemoryHelper();
    mh.bindTo(tracker);
    // Allocate a few Tuples; `two` points to `one`, and `three` points to `one` and `two`.
    Tuple one = new Tuple(mh, null, null, 1);
    Tuple two = new Tuple(mh, one, null, 0);
    Tuple three = new Tuple(mh, one, two, 5);
    // Verify the result of checkAvailable()
    long totalAllocated = Tuple.totalSize(debug, 1, 0, 5);
    long expectedRemaining = memoryLimit - totalAllocated;
    assertWithMessage("mh = %s", mh).that(mh.checkAvailable()).isEqualTo(expectedRemaining);
    // Drop the references from `one` and `two`.  Should have no effect on available memory, since
    // both are linked from `three`.
    // The assignments to one and two don't actually have any effect (since those vars are never
    // used again), but they're there to make clear why we're dropping the reference counts.
    mh.dropReference(one);
    one = null;
    mh.dropReference(two);
    two = null;
    // Check available again; it shouldn't have increased.
    assertWithMessage("mh = %s", mh).that(mh.checkAvailable()).isEqualTo(expectedRemaining);
    // Drop `three` (and, indirectly, the tuples that `one` and `two` had previously pointed to);
    // that should free all the memory we've allocated.
    mh.dropReference(three);
    three = null;
    assertWithMessage("mh = %s", mh).that(mh.checkAvailable()).isEqualTo(memoryLimit);
    mh.bindTo(null);
    assertThat(tracker.allReleased()).isTrue();
  }

  /**
   * Calls {@link MemoryHelper#reserve} and returns true if it succeeds, or false if it throws an
   * OUT_OF_MEMORY exception.
   */
  private boolean tryReserve(MemoryHelper mh, long size) {
    try {
      mh.reserve(size);
      return true;
    } catch (Err.BuiltinException e) {
      assertThat(e.baseType()).isEqualTo(Err.OUT_OF_MEMORY);
      return false;
    }
  }

  /** Verifies the behavior of {@link MemoryHelper#reserve}. */
  @Test
  @Parameters({"false", "true"})
  public void reserve(boolean debug) {
    int memoryLimit = 8000;
    ResourceTracker tracker = new ResourceTracker(null, memoryLimit, debug);
    MemoryHelper mh = new MemoryHelper();
    mh.bindTo(tracker);
    // Allocate something to use up a little memory
    int arrayLengthRequested = 1000;
    byte[] bytes = mh.allocByteArray(arrayLengthRequested);
    assertThat(bytes.length).isAtLeast(arrayLengthRequested);
    long arraySize = SizeOf.array(bytes);
    // Verify the result of checkAvailable()
    long expectedRemaining = memoryLimit - arraySize;
    assertWithMessage("mh = %s", mh).that(mh.checkAvailable()).isEqualTo(expectedRemaining);
    // Reserve all the remaining memory
    assertWithMessage("mh = %s", mh).that(tryReserve(mh, expectedRemaining + 1)).isFalse();
    assertWithMessage("mh = %s", mh).that(tryReserve(mh, expectedRemaining)).isTrue();
    // That doesn't change the amount this thread is still able to allocate...
    assertWithMessage("mh = %s", mh).that(mh.checkAvailable()).isEqualTo(expectedRemaining);
    // ... but does change the amount available directly from the ResourceTracker.
    assertWithMessage("mh = %s", mh).that(tracker.checkAvailable()).isEqualTo(0);
    // Another reserve() for a smaller amount should succeed
    assertWithMessage("mh = %s", mh).that(tryReserve(mh, 1)).isTrue();
    // Drop the array we've allocated.
    mh.dropReference(bytes);
    bytes = null;
    assertWithMessage("mh = %s", mh).that(mh.checkAvailable()).isEqualTo(memoryLimit);
    // The memory that we've freed should now be available to anyone else.
    assertWithMessage("mh = %s", mh).that(tracker.checkAvailable()).isEqualTo(arraySize);
    // Unbinding the MemoryHelper releases the reservation.
    mh.bindTo(null);
    assertWithMessage("mh = %s", mh).that(tracker.checkAvailable()).isEqualTo(memoryLimit);
    assertThat(tracker.allReleased()).isTrue();
  }

  /**
   * Builds a deep chain of linked Tuples, and drops it. A naive recursive descent would cause a
   * StackOverflow.
   */
  @Test
  @Parameters({"false", "true"})
  public void deep(boolean debug) {
    // This must be a multiple of 4
    int depth = 8000;
    // The loop passes an arrayLength of i % 4, so this should give us an accurate prediction
    // of memory use.
    long memoryLimit = (depth / 4) * Tuple.totalSize(debug, 0, 1, 2, 3);
    ResourceTracker tracker = new ResourceTracker(null, memoryLimit, debug);
    MemoryHelper mh = new MemoryHelper();
    mh.bindTo(tracker);
    Tuple top = null;
    for (int i = 0; i < depth; i++) {
      Tuple oldTop = top;
      // Throw in a reservation.  For some (i % 4 == 0) this will be a little too large, for
      // others (i % 4 > 1) it will be a little too small.  That should be OK, as long as we
      // don't try to reserve more memory than we have.
      assertThat(tryReserve(mh, memoryLimit / depth)).isTrue();
      // Each new Tuple links (multiple times) to the preceding one.  As soon as we've created it,
      // drop our own reference to the preceding one so that only the root of the graph has a
      // non-zero rootCount.
      top = new Tuple(mh, oldTop, oldTop, i % 4);
      mh.dropReference(oldTop);
    }
    // That should have used up all available memory.
    assertWithMessage("mh = %s", mh).that(mh.checkAvailable()).isEqualTo(0);
    // Drop it.  That should (a) not crash, and (b) leave all memory free.
    mh.dropReference(top);
    top = null;
    assertWithMessage("mh = %s", mh).that(mh.checkAvailable()).isEqualTo(memoryLimit);
    assertThat(mh.tracker().allReleased()).isTrue();
  }
}
