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

import com.google.errorprone.annotations.Keep;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import org.retrolang.util.SizeOf;
import org.retrolang.util.StringUtil;

/**
 * An RThread represents a Retrospect thread of computation. It may be
 *
 * <ul>
 *   <li>active (currently being executed by a Java thread, with associated TState),
 *   <li>suspended (waiting for some asynchronous event),
 *   <li>completed (returned a result, encountered an error, or cancelled because its result is no
 *       longer needed).
 * </ul>
 *
 * <p>Active and suspended RThreads have an associated Waiter that will be notified when the thread
 * completes. The Waiter usually has the only counted reference to the RThread; if the RThread's
 * refCount goes to zero it assumes that the Waiter is no longer interested and that any in-progress
 * computation should be cancelled. Note that in this case there is a race condition between the
 * notification being delivered and the RThread being dropped, so the Waiter must be prepared to
 * receive a notification even after its refCount has gone to zero.
 */
class RThread extends RefCounted {

  /**
   * Each RThread is created with a Waiter to be notified when the RThread completes. The Waiter's
   * {@link #threadDone} method will be called at most once by the RThread; it will not be called if
   * the RThread's reference count goes to zero before its execution completes.
   */
  interface Waiter {
    /** Exactly one of {@code result} and {@code errorStack} will be non-null. */
    void threadDone(TState tstate, @RC.In Value result, @RC.In TStack errorStack);
  }

  /**
   * A trivial Waiter implementation that just discards the RThread's result; substituted for the
   * original Waiter when the RThread has been dropped.
   */
  private static final Waiter DISCARD_RESULTS =
      (tstate, result, errorStack) -> {
        tstate.dropValue(result);
        tstate.dropReference(errorStack);
      };

  private static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + 2 * SizeOf.PTR);

  /**
   * This is not a counted reference! (If it was counted the Waiter could not be collected until the
   * RThread completed; we want to allow no-longer referenced futures to be dropped and the
   * corresponding in-progress RThread to be cancelled.)
   *
   * <p>Set to null after the notification has been delivered or the RThread has been dropped.
   */
  @Keep private Waiter waiter;

  /** Non-null if the RThread has been suspended. */
  @Keep private TStack suspended;

  static final VarHandle WAITER =
      Handle.forVar(MethodHandles.lookup(), RThread.class, "waiter", Waiter.class);

  static final VarHandle SUSPENDED =
      Handle.forVar(MethodHandles.lookup(), RThread.class, "suspended", TStack.class);

  RThread(Allocator allocator, Waiter waiter) {
    this.waiter = waiter;
    allocator.recordAlloc(this, OBJ_SIZE);
  }

  /** Returns true if this RThread has been cancelled or completed. Inherently racy. */
  boolean isCancelled() {
    return WAITER.getOpaque(this) == null;
  }

  /**
   * Returns the waiter and atomically sets it to null. If the waiter was already null, returns
   * {@link #DISCARD_RESULTS}.
   */
  private Waiter takeWaiter() {
    Waiter result = (Waiter) WAITER.getAndSetAcquire(this, null);
    return (result != null) ? result : DISCARD_RESULTS;
  }

  /** Returns the suspended stack and atomically sets it to null. */
  private TStack takeSuspended() {
    return (TStack) SUSPENDED.getAndSetAcquire(this, null);
  }

  /**
   * Resumes an RThread that was previously passed to {@link BaseType.BlockingEntryType#suspended}
   * on a separate Java thread. If {@code value} is an error the RThread's stack will unwind with
   * that error; otherwise the blocking entry will be resumed with {@code value} as its result.
   */
  void resumeAsync(ResourceTracker tracker, @RC.In Value value) {
    ForkJoinPool.commonPool()
        .execute(
            () ->
                run(
                    tracker,
                    tstate -> {
                      TStack suspended = takeSuspended();
                      if (suspended == null) {
                        assert isCancelled();
                        tstate.dropValue(value);
                      } else if (value.baseType() instanceof Err) {
                        tstate.setStackRest(suspended);
                        tstate.pushUnwind(value);
                      } else {
                        tstate.setResult(value);
                        tstate.resumeStack(suspended, TStack.BASE);
                      }
                    }));
  }

  /**
   * Executes {@code runner} on the current Java thread; when it returns one of these should be
   * true:
   *
   * <ul>
   *   <li>{@link TState#unwindStarted} is false, this RThread's result has been saved with {@link
   *       TState#setResult}, and {@link TState#stackRestIsBase} is true; or
   *   <li>{@link TState#takeUnwind} starts with an error; or
   *   <li>{@link TState#takeUnwind} starts with a BlockingEntryType; or
   *   <li>{@link #isCancelled} is true.
   * </ul>
   *
   * In the first or second case the waiter's {@link Waiter#threadDone} method will be called; in
   * the third case the RThread will be suspended until a subsequent call to {@link #resumeAsync} is
   * made.
   */
  void run(ResourceTracker tracker, Consumer<TState> runner) {
    TState tstate = TState.getOrCreate();
    assert tstate.rThread == null;
    ResourceTracker prev = tstate.bindTo(tracker);
    tstate.rThread = this;
    try {
      runner.accept(tstate);
      assert suspended == null;
      if (isCancelled()) {
        // Just return and let the unbind in our finally clause discard anything left in the TState
      } else if (tstate.unwindStarted()) {
        TStack stack = tstate.takeUnwind();
        BaseType topOfStackBaseType = stack.first().baseType();
        if (topOfStackBaseType instanceof BaseType.BlockingEntryType blockingType) {
          Value topOfStack = Value.addRef(stack.first());
          // After storing stack we no longer own its reference count, which is why we needed to
          // increment the refCount on topOfStack first.
          SUSPENDED.setRelease(this, stack);
          // We might have raced against cancellation, so check again; we don't want to
          // leave behind a stack that will never be resumed
          if (isCancelled()) {
            tstate.dropReference(takeSuspended());
          } else {
            blockingType.suspended(tstate, topOfStack, this);
          }
          tstate.dropValue(topOfStack);
        } else {
          assert stack.first().baseType() instanceof Err;
          takeWaiter().threadDone(tstate, null, stack);
        }
      } else {
        assert tstate.stackRestIsBase();
        Value result = tstate.takeResult(0);
        tstate.clearResults();
        takeWaiter().threadDone(tstate, result, null);
      }
    } finally {
      tstate.rThread = null;
      tstate.bindTo(prev);
    }
  }

  /**
   * Returns a short, persistent, usually unique identifier for this RThread (suitable for output
   * but not as a map key).
   */
  String id() {
    return StringUtil.id(this);
  }

  @Override
  long visitRefs(RefVisitor visitor) {
    if (MemoryHelper.isReleaser(visitor)) {
      // Like
      //     this.waiter = null;
      //     this.suspended = null;
      // but guaranteed to be visible (in that order!) from other threads.
      WAITER.setOpaque(this, null);
      visitor.visitRefCounted(takeSuspended());
      // TODO: if a Java thread is currently executing this RThread, notify it
    } else {
      visitor.visitRefCounted(suspended);
    }
    return OBJ_SIZE;
  }

  @Override
  public String toString() {
    return "RThread@" + id();
  }
}
