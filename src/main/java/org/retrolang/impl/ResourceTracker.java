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

import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.retrolang.Vm;
import org.retrolang.util.SizeOf;

/**
 * A ResourceTracker tracks the total memory use of a Retrospect computation and compares it with a
 * fixed limit. The ResourceTracker is shared among all threads that participate in the computation.
 *
 * <p>Threads may use ResourceTracker as an Allocator directly, or use a MemoryHelper to accumulate
 * multiple updates into a single call. A MemoryHelper must always be used to release objects, since
 * only it has the logic for recursively updating reference counts.
 *
 * <p>A ResourceTracker also supports making a reservation, which effectively increases the "in use"
 * count in anticipation of future allocations; if there is enough available memory, the reservation
 * will succeed and can then be incrementally unreserved as the subsequent allocations are made.
 * Clients are responsible for ensuring that they only unreserve memory which they previously
 * reserved.
 *
 * <p>A ResourceTracker may be allocated with an associated {@link DebugTracker}, which records each
 * allocation and release to help find bugs in the reference-counting logic. This is expensive (in
 * both time and memory), and only intended for use while developing the VM.
 *
 * <p>ResourceTrackers also maintain the storage for stack snapshots saved by trace instructions.
 * Each {@link Instruction.Trace} can have an associated {@link SavedTraces} object, which saves the
 * snapshots created by executing that instruction.
 */
class ResourceTracker implements Allocator, Vm.ResourceTracker {

  final Scope scope;

  final Coordinator coordinator;

  /** The total memory (in bytes) that this ResourceTracker is allowed to use. */
  final long limit;

  /**
   * The maximum number of execution stacks to save for each trace instruction. Must be even; if a
   * trace instruction is executed more than {@code maxTraces} times we save the first {@code
   * maxTraces/2} and last {@code maxTraces/2} stacks.
   *
   * <p>More control over this policy is probably desirable, but that will be easier to make
   * decisions about after we've got some experience with it.
   */
  final int maxTraces;

  /**
   * The total number of objects (RefCounted, byte[], or Object[]) that have been allocated by this
   * ResourceTracker.
   */
  @GuardedBy("this")
  private int allocedObjs;

  /** The total number of bytes that have been allocated by this ResourceTracker. */
  @GuardedBy("this")
  private long allocedBytes;

  /**
   * The total number of previously-allocated objects that have been released by this
   * ResourceTracker.
   */
  @GuardedBy("this")
  private int releasedObjs;

  /**
   * The total number of previously-allocated bytes that have been released by this ResourceTracker.
   */
  @GuardedBy("this")
  private long releasedBytes;

  /** The total number of bytes that have been reserved but not yet allocated. */
  @GuardedBy("this")
  private long reservedBytes;

  /** The maximum value of {@link #inUseBytes}. */
  @GuardedBy("this")
  private long peakBytes;

  /** Maps each trace instruction that has been executed to a corresponding {@link SavedTraces}. */
  private final ConcurrentHashMap<Instruction.Trace, SavedTraces> savedTraces =
      new ConcurrentHashMap<>();

  @SuppressWarnings("GoodTime") // revisit?
  final long startNanoTime = System.nanoTime();

  final DebugTracker debugTracker;

  /** The number of escapes from generated code. */
  private volatile int escapeCount;

  private static final VarHandle ESCAPE_COUNT_VAR;

  static {
    var lookup = MethodHandles.lookup();
    ESCAPE_COUNT_VAR = Handle.forVar(lookup, ResourceTracker.class, "escapeCount", int.class);
  }

  /** Creates a new ResourceTracker with the given limits. */
  ResourceTracker(Scope scope, long limit, int maxTraces, boolean debug) {
    Preconditions.checkArgument(limit > 0);
    Preconditions.checkArgument(maxTraces > 1 && (maxTraces & 1) == 0);
    this.scope = scope;
    this.coordinator = new Coordinator();
    this.limit = limit;
    this.maxTraces = maxTraces;
    this.debugTracker = debug ? new DebugTracker() : null;
  }

  /** Used only for tests. */
  ResourceTracker(Scope scope, long limit, boolean debug) {
    this(scope, limit, 16, debug);
  }

  /** Returns true if this ResourceTracker has an associated DebugTracker. */
  boolean debugTracking() {
    return debugTracker != null;
  }

  @Override
  @RC.Out
  public byte[] allocByteArray(int size) {
    return allocByteArray(size, null);
  }

  /**
   * Returns a byte[] of at least the given length and records its memory use.
   *
   * <p>If {@code helper} is non-null and has a non-zero {@link MemoryHelper#reservedBytes}, this
   * allocation will reduce that helper's reservation.
   *
   * <p>The caller should not assume that the returned byte[] has been zero-filled.
   */
  @RC.Out
  byte[] allocByteArray(int length, MemoryHelper helper) {
    if (length == 0) {
      return EMPTY_BYTES;
    }
    byte[] result = new byte[length];
    recordAlloc(result, SizeOf.array(result), helper);
    return result;
  }

  @Override
  @RC.Out
  public Object[] allocObjectArray(int size) {
    return allocObjectArray(size, null);
  }

  /**
   * Returns a null-filled Object Object[] of at least the given length and records its memory use.
   *
   * <p>If {@code helper} is non-null and has a non-zero {@link MemoryHelper#reservedBytes}, this
   * allocation will reduce that helper's reservation.
   */
  @RC.Out
  Object[] allocObjectArray(int length, MemoryHelper helper) {
    if (length == 0) {
      return EMPTY_OBJECTS;
    }
    Object[] result = new Object[length];
    recordAlloc(result, SizeOf.array(result), helper);
    return result;
  }

  @Override
  public void recordAlloc(RefCounted obj, long size) {
    assert obj != null && SizeOf.isValidSize(size);
    recordAlloc(obj, size, null);
  }

  @Override
  public boolean isCounted() {
    return true;
  }

  /**
   * Records the allocation of the given object.
   *
   * <p>If {@code helper} is non-null and has a non-zero {@link MemoryHelper#reservedBytes}, this
   * allocation will reduce that helper's reservation.
   */
  void recordAlloc(Object obj, long size, MemoryHelper helper) {
    synchronized (this) {
      allocedObjs++;
      allocedBytes += size;
      if (helper != null) {
        reservedBytes -= helper.useReservation(size);
        assert reservedBytes >= 0;
      }
      updatePeakBytes();
    }
    if (debugTracking()) {
      debugTracker.recordAlloc(obj, size);
    }
  }

  @Override
  public void adjustAlloc(RefCounted obj, long sizeDelta) {
    synchronized (this) {
      if (sizeDelta < 0) {
        releasedBytes -= sizeDelta;
      } else {
        allocedBytes += sizeDelta;
        updatePeakBytes();
      }
    }
    if (debugTracking()) {
      debugTracker.adjustAlloc(obj, sizeDelta);
    }
  }

  /**
   * Returns the amount of memory still available (negative if the memory limit has been exceeded).
   * Does not include reserved memory.
   *
   * <p>Inherently racy, so intended primarily for testing purposes.
   */
  synchronized long checkAvailable() {
    return limit - inUseBytes();
  }

  /**
   * First, if {@code unreserve} is non-zero, it should be positive and less than or equal to the
   * remaining size of a previous reservation; that much of the previous reservation will be
   * unreserved.
   *
   * <p>Second, if there are at least {@code reserve} available bytes, reserves that much space for
   * future allocations, and returns true; otherwise does nothing more and returns false. To use the
   * reserved space, call {@link #allocByteArray(int, long)}, {@link #allocObjectArray(int, long)},
   * or {@link #recordAlloc(RefCounted, long, long)} passing a positive value for {@code unreserve}.
   */
  @CanIgnoreReturnValue
  synchronized boolean adjustReservation(long unreserve, long reserve) {
    assert unreserve >= 0 && reserve >= 0 && unreserve <= reservedBytes;
    reservedBytes -= unreserve;
    if (inUseBytes() + reserve <= limit) {
      reservedBytes += reserve;
      updatePeakBytes();
      return true;
    }
    return false;
  }

  @Override
  public Vm.Value asValue(String s) {
    return VmValue.of(new StringValue(this, s), this);
  }

  @Override
  public Vm.Value asValue(int i) {
    return VmValue.of(NumValue.of(i, this), this);
  }

  @Override
  public Vm.Value asValue(double d) {
    return VmValue.of(NumValue.of(d, this), this);
  }

  /**
   * Adds the given values to this ResourceTracker's counters. Returns the amount of memory
   * remaining after the updates and any requested reservation. If the result is non-negative the
   * reservation has been made; otherwise the reservation failed.
   */
  synchronized long update(
      int allocedObjs,
      long allocedBytes,
      int releasedObjs,
      long releasedBytes,
      long unreserve,
      long reserve) {
    assert allocedObjs >= 0
        && allocedBytes >= 0
        && releasedObjs >= 0
        && releasedBytes >= 0
        && unreserve >= 0
        && reserve >= 0
        && unreserve <= reservedBytes;
    // If debugTracking() is true, MemoryHelper should not be doing its own allocations.
    assert !debugTracking() || (allocedBytes == 0 && allocedObjs == 0);
    this.allocedObjs += allocedObjs;
    this.allocedBytes += allocedBytes;
    this.releasedObjs += releasedObjs;
    this.releasedBytes += releasedBytes;
    this.reservedBytes -= unreserve;
    long result = limit - (inUseBytes() + reserve);
    if (result >= 0) {
      reservedBytes += reserve;
    }
    updatePeakBytes();
    return result;
  }

  @GuardedBy("this")
  private long inUseBytes() {
    return allocedBytes + reservedBytes - releasedBytes;
  }

  @GuardedBy("this")
  private void updatePeakBytes() {
    peakBytes = Math.max(peakBytes, inUseBytes());
  }

  /**
   * Returns true if all memory allocated by this ResourceTracker has been released.
   *
   * <p>If not all memory has been released, prints the ResourceTracker's counters and returns
   * false.
   */
  @Override
  public synchronized boolean allReleased() {
    if (debugTracking() && (!debugTracker.allReleased() || debugTracker.errored())) {
      // If DebugTracker says there's a problem we'll skip our own test.
    } else if (allocedBytes == releasedBytes && allocedObjs == releasedObjs) {
      return true;
    }
    System.err.println("** " + this);
    return false;
  }

  /** Returns the SavedTraces object that should be used for the given trace instruction. */
  SavedTraces tracesFor(Instruction.Trace inst, TState tsate) {
    return savedTraces.computeIfAbsent(inst, i -> new SavedTraces(maxTraces));
  }

  /**
   * Copies the contents of the given SavedTraces into the given list and clears the SavedTraces.
   */
  private static void takeTraces(TState tstate, SavedTraces saved, List<TStack.ForTrace> out) {
    Object[] array = saved.takeTraces();
    if (array != null) {
      for (int i = 0; i < array.length; i++) {
        if (array[i] == null) {
          break;
        } else {
          out.add((TStack.ForTrace) array[i]);
          array[i] = null;
        }
      }
      tstate.dropReference(array);
    }
  }

  @Override
  public String takeTraces() {
    // The goal here is to format a set of traces as a (sort of) human-readable string.
    // The basic ideas are:
    // - order them chronologically
    // - show the full stack associated with each trace
    // - since multiple traces will often share some tail of the stack, indicate where that's
    //   happening (both because it's useful to know and to reduce output volume)
    //
    // For example, here's a very simple example of the kind of output we'd like to produce, given
    // two traces generated by a trace instruction at line 77 of src.r8t:
    //
    //             applyToArgs
    //             src.r8t:5 _t0 = main(options)
    //         (1) src.r8t:30 _t0 = foo(x, y) {valid=True, x=13}
    //             src.r8t:50 _t0 = bar(z) {y=[9, 9], sum=91}
    // 0.2s) src.r8t:77 trace {a=[], z=182}
    //              ... (1) ...
    //             src.r8t:50 _t0 = bar(z) {y=[9, 9], sum=112}
    // 0.3s) src.r8t:77 trace {a=[1], z=224}
    //
    // This shows a computation that started with applyToArgs, got to an instruction that called
    // main() (at line 5 of the source file), which then got to an instruction that called foo()
    // (at line 30) -- that's the common stack tail marked with (1).  From there it called bar(),
    // which contains the trace instruction that saved the first TStack (0.2 seconds after the
    // computation started).  The second TStack was saved while still in the same call to foo(),
    // but a new call to bar() (at 0.3 seconds).
    List<TStack.ForTrace> traces = new ArrayList<>();
    // We need a TState to use for releasing objects
    TState tstate = TState.getOrCreate();
    ResourceTracker prev = tstate.bindTo(this);
    try {
      savedTraces.forEach((k, saved) -> takeTraces(tstate, saved, traces));
      if (traces.isEmpty()) {
        return "";
      }
      // Sort all the traces by capture time.
      traces.sort(Comparator.comparing(t -> t.nanoTime));
      // We want to identify where multiple traces have a shared tail.  Throw each reachable TSTack
      // into a map, with value FALSE if we've only seen it once, and TRUE if we've seen it more
      // than once.
      IdentityHashMap<TStack, Boolean> multiple = new IdentityHashMap<>();
      for (TStack.ForTrace trace : traces) {
        for (TStack t = trace.rest(); t != null; t = t.rest()) {
          Boolean multi = multiple.get(t);
          if (!Boolean.TRUE.equals(multi)) {
            // null goes to FALSE, FALSE goes to TRUE
            multiple.put(t, multi != null);
          }
          // If we'd already seen that one, or it's the end of the line, we're done.
          if (multi != null || !t.isSet()) {
            break;
          }
        }
      }
      // Maps TStacks that are TRUE in multiple to a label string (e.g. "(1)").
      IdentityHashMap<TStack, String> labels = new IdentityHashMap<>();
      int nextLabel = 1;
      // Indent lines by 11 blanks.
      String prefixFormat = "%11s";
      String indent = String.format(prefixFormat, "");
      StringBuilder result = new StringBuilder();
      Formatter formatter = new Formatter(result);
      List<String> lines = new ArrayList<>();
      // Maps trace type to the index of the last trace we've seen with that type, so that we can
      // recognize when there's a gap (due overflowing the SavedTraces buffer).
      IdentityHashMap<BaseType, Integer> lastIndex = new IdentityHashMap<>();
      for (TStack.ForTrace trace : traces) {
        // The first entry on the stack (from the trace instruction itself) is formatted
        // separately.
        Value first = trace.first();
        // If the index of this trace isn't one more than the index of the previous trace from the
        // same instruction, we've had to drop some traces to stay within limits.
        Integer prevLast = lastIndex.put(first.baseType(), trace.index);
        int expected = (prevLast == null) ? 1 : 1 + prevLast;
        if (trace.index != expected) {
          formatter.format("(dropped %s)\n", trace.index - expected);
        }
        // Copy the remaining entries into lines, which we'll then reverse
        for (TStack t = trace.rest(); t != null; t = t.rest()) {
          String prefix = indent;
          if (Boolean.TRUE.equals(multiple.get(t))) {
            String label = labels.get(t);
            if (label != null) {
              // We've already printed the rest of this stack, so just print
              // a reference back to it
              lines.add(String.format("%s ... %s ...\n", indent, label));
              break;
            }
            // This is the first time we've seen this stack, but there are other references to it
            // ahead so add a label that can be referred to when we see it again.
            label = String.format("(%d)", nextLabel++);
            labels.put(t, label);
            prefix = String.format(prefixFormat, label);
          }
          if (!t.isSet()) {
            // The rest of this stack hasn't been filled in.
            // I don't think that there's currently any way for that to happen.
            lines.add(prefix + " (incomplete)\n");
            break;
          }
          lines.add(String.format("%s %s\n", prefix, t.first()));
        }
        Collections.reverse(lines);
        lines.forEach(result::append);
        lines.clear();
        formatter.format("%.1fs) %s\n", trace.nanoTime * 1e-9, first);
      }
      return result.toString();
    } finally {
      traces.forEach(tstate::dropReference);
      tstate.bindTo(prev);
    }
  }

  synchronized int allocedObjs() {
    return allocedObjs;
  }

  synchronized long allocedBytes() {
    return allocedBytes;
  }

  synchronized long peakBytes() {
    return peakBytes;
  }

  void incrementEscaped() {
    ESCAPE_COUNT_VAR.getAndAdd(this, 1);
  }

  int escapeCount() {
    return escapeCount;
  }

  @Override
  public synchronized String toString() {
    String result = String.format("allocated=%s/%s", allocedBytes, allocedObjs);
    if (releasedBytes != allocedBytes || releasedObjs != allocedObjs) {
      result = String.format("%s, released=%s/%s", result, releasedBytes, releasedObjs);
    }
    if (reservedBytes != 0) {
      result = String.format("%s (reserved=%s)", result, reservedBytes);
    }
    return result + ", peak=" + peakBytes;
  }
}
