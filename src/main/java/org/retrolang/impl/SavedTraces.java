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

import com.google.errorprone.annotations.concurrent.GuardedBy;
import org.retrolang.util.SizeOf;

/// A SavedTraces saves TStacks that have been captured by a single trace instruction. It has a
/// fixed sized buffer, so once full each additional stack saved will displace an older one.
///
/// The current policy is that if our buffer has size k, we save the first k/2 and last k/2 traces.
/// We may revisit this policy, and perhaps give users more direct control over it, once we have
/// more experience.
///
/// All SavedTraces objects are stored in a map ([ResourceTracker#savedTraces]). The SavedTraces
/// objects are not themselves refcounted (because it's easiest to leave them in the map
/// indefinitely), but almost all of their memory use is in a counted array of TStacks.
class SavedTraces {
  // Not RefCounted, but holds pointers to RefCounted

  static final long OBJ_SIZE = SizeOf.object(SizeOf.PTR + 2 * SizeOf.INT);

  final int capacity;

  /// The traces array is allocated lazily the first time we save a trace.
  @GuardedBy("this")
  @RC.Counted
  private Object[] traces;

  /// The total number of traces that have been saved.
  ///
  /// If `numTraces` is greater than `capacity`, `numTraces-capacity` traces have been discarded to
  /// stay within our limit.
  @GuardedBy("this")
  private int numTraces;

  /// The position in [#traces] where the next trace will be saved.
  @GuardedBy("this")
  private int nextToReplace;

  /// Creates a SavedTraces; the capacity must be even and greater than zero.
  SavedTraces(int capacity) {
    assert capacity > 0 && (capacity & 1) == 0;
    this.capacity = capacity;
  }

  /// Saves the given trace in our buffer, and records the time it was saved relative to `nanoBase`.
  /// If we have to remove a previously-saved trace to make room for this one, return it; the caller
  /// is responsible for releasing it.
  @SuppressWarnings("GoodTime") // revisit?
  @RC.Out
  synchronized TStack.ForTrace add(
      Allocator allocator, @RC.In TStack.ForTrace trace, long nanoBase) {
    if (traces == null) {
      // If numTraces is non-zero, i.e. we've previously saved and then taken some traces from this
      // instruction, perhaps we should only save max(0, capacity/2 - numTraces) + capacity/2 from
      // here on?  That would seem to make sense, but we need some non-test usage of this before
      // adding any more complexity.
      traces = allocator.allocObjectArray(capacity);
    }
    // Delay the System.nanoTime() call until inside this lock so that it's consistent
    // (increasing index implies increasing nanoTime).
    trace.setIndex(++numTraces, System.nanoTime() - nanoBase);
    TStack.ForTrace prev = (TStack.ForTrace) traces[nextToReplace];
    traces[nextToReplace] = trace;
    if (++nextToReplace == capacity) {
      nextToReplace = capacity / 2;
    }
    return prev;
  }

  /// Returns all the saved traces, clearing the state of this SavedTraces.
  ///
  /// If the result is non-null, all of its non-null elements will be instances of TStack.ForTrace.
  /// The caller should not assume that they will be in order.
  @RC.Out
  synchronized Object[] takeTraces() {
    Object[] result = traces;
    traces = null;
    nextToReplace = 0;
    numTraces = 0;
    return result;
  }

  @GuardedBy("this")
  private void appendTraces(StringBuilder sb, int start, int limit) {
    for (int i = start; i < limit; i++) {
      sb.append("\n").append(traces[i]);
    }
  }

  @Override
  public synchronized String toString() {
    if (traces == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(traces[0]);
    if (traces[nextToReplace] == null) {
      appendTraces(sb, 1, nextToReplace);
    } else {
      appendTraces(sb, 1, capacity / 2);
      appendTraces(sb, nextToReplace, capacity);
      appendTraces(sb, capacity / 2, nextToReplace);
    }
    return sb.toString();
  }
}
