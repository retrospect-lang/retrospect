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

import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.retrolang.util.SizeOf;

/**
 * A TStack represents an in-progress computation, unwound into a sequence of stack entry Values.
 *
 * <p>The first entry of a TStack is the most deeply nested stack entry; for a stack capturing an
 * error this will be the error itself, for a stack captured by a trace instruction this will refer
 * to the trace instruction. The following entries identify the call instructions that led to that
 * point.
 */
class TStack extends RefCounted {

  // BASE_SIZE has nothing to do with BASE or BASE_TYPE
  static final long BASE_SIZE = RefCounted.BASE_SIZE + 4 * SizeOf.PTR;
  private static final long OBJ_SIZE = SizeOf.object(BASE_SIZE);

  /** A Singleton used as the last (bottom-most) entry in all TStacks. */
  static final BaseType.SimpleStackEntryType BASE_TYPE =
      new BaseType.SimpleStackEntryType("StackBase");

  /** The ultimate tail of all TStacks. */
  static final TStack BASE;

  static {
    BASE = new TStack(Allocator.UNCOUNTED);
    BASE.set(BASE_TYPE.asValue());
  }

  /**
   * The first stack entry of this stack, or null if {@link #set} has not yet been called.
   *
   * <p>If {@code first} is non-null, {@code first.baseType()} will be a {@link
   * BaseType.StackEntryType}.
   */
  @RC.Counted private Value first;

  /** The stack entries after the first, or null if {@link #set} has not yet been called. */
  @RC.Counted private TStack rest;

  /**
   * The ResultsInfo that will be passed to {@link BaseType.StackEntryType#resume} if this stack is
   * resumed. Ignored if this stack is not resumed.
   */
  private ResultsInfo results;

  /**
   * The MethodMemo that will be passed to {@link BaseType.StackEntryType#resume} if this stack is
   * resumed. Ignored if this stack is not resumed.
   */
  private MethodMemo methodMemo;

  /** Creates a new, uninitialized TStack. */
  @RC.Out
  TStack(Allocator allocator) {
    allocator.recordAlloc(this, OBJ_SIZE);
  }

  // For use by the ForTrace subclass only
  private TStack() {}

  /** True if {@link #set} has been called. */
  boolean isSet() {
    return first != null;
  }

  /** Returns the first entry of this stack. Should only be called when {@link #isSet} is true. */
  Value first() {
    assert isSet();
    return first;
  }

  /**
   * Returns true if the top of this stack is an Err. Should only be called when {@link #isSet} is
   * true.
   */
  boolean hasErr() {
    return first().baseType() instanceof Err;
  }

  /**
   * Returns the stack entries after the first, or null if this is {@link #BASE}. Should only be
   * called when {@link #isSet} is true.
   */
  TStack rest() {
    assert isSet();
    return rest;
  }

  /**
   * Returns the ResultsInfo to be used if this entry is resumed; may be null if the entry cannot be
   * resumed or does not need a ResultsInfo. Should only be called when {@link #isSet} is true.
   */
  ResultsInfo results() {
    assert isSet();
    return results;
  }

  /**
   * Returns the MethodMemo to be used if this entry is resumed; may be null if the entry cannot be
   * resumed or does not need a MethodMemo. Should only be called when {@link #isSet} is true.
   */
  MethodMemo methodMemo() {
    assert isSet();
    return methodMemo;
  }

  /** Equivalent to {@code set(first, null, null)}. */
  void set(@RC.In Value first) {
    set(first, null, null);
  }

  /** One of the set() methods must be called once on each TStack. */
  void set(@RC.In Value first, ResultsInfo results, MethodMemo methodMemo) {
    assert !isSet() && first.baseType() instanceof BaseType.StackEntryType;
    this.first = first;
    this.results = results;
    this.methodMemo = methodMemo;
  }

  /** Must be called exactly once on each TStack (except BASE). */
  void setRest(@RC.In TStack rest) {
    assert isSet() && this.rest == null && this != BASE;
    this.rest = rest;
  }

  /**
   * Returns a sequential stream beginning with this TStack, following the rest links. Note that the
   * final TStack in the sequence may not be set.
   */
  Stream<TStack> stream() {
    return Stream.iterate(this, t -> t != null, t -> t.rest);
  }

  /**
   * Returns the entries on this stack as a stream of Strings. If the final TStack is unset, the
   * final String will be "(incomplete)"
   */
  Stream<String> stringStream() {
    return stream().map(tStack -> tStack.first == null ? "(incomplete)" : tStack.first.toString());
  }

  @Override
  protected long visitRefs(RefVisitor visitor) {
    visitor.visit(first);
    visitor.visitRefCounted(rest);
    return OBJ_SIZE;
  }

  @Override
  public String toString() {
    return stringStream().collect(Collectors.joining("\n"));
  }

  /**
   * A subclass of TStack that captures additional information associated with trace instructions.
   */
  static class ForTrace extends TStack {
    static final long OBJ_SIZE = SizeOf.object(TStack.BASE_SIZE + SizeOf.LONG + SizeOf.INT);

    /**
     * The number of nanoseconds (as reported by {@link System#nanoTime}) from the time the
     * ResourceTracker was initialized until the execution of the trace instruction that created
     * this TStack.
     */
    @SuppressWarnings("GoodTime") // revisit?
    long nanoTime;

    /**
     * Each TStack created for a given trace instruction within a single computation will have a
     * sequentially increasing index, beginning with 1; we use the indices to determine when traces
     * have been dropped to stay within limits.
     */
    int index;

    @RC.Out
    ForTrace(Allocator allocator, @RC.In Value first) {
      set(first);
      allocator.recordAlloc(this, OBJ_SIZE);
    }

    /** Set this TStack's {@link #index} and {@link #nanoTime}. */
    @SuppressWarnings("GoodTime") // revisit?
    void setIndex(int index, long nanoTime) {
      assert this.index == 0;
      this.index = index;
      this.nanoTime = nanoTime;
    }

    @Override
    protected long visitRefs(RefVisitor visitor) {
      // We have the same counted fields as our superclass, but we're a little bigger.
      var unused = super.visitRefs(visitor);
      return OBJ_SIZE;
    }

    @Override
    public String toString() {
      String prefix = String.format("%.1f) (%s) ", nanoTime * 1e-9, index);
      return stringStream().collect(Collectors.joining("  \n", prefix, ""));
    }
  }
}
