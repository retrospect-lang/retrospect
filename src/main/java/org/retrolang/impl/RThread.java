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

/// An RThread represents a Retrospect thread of computation. It may be
///
/// - active (currently being executed by a Java thread, with associated TState),
/// - suspended (waiting for some asynchronous event),
/// - completed (returned a result, encountered an error, or cancelled because its result is no
///   longer needed).
///
/// Active and suspended RThreads have an associated Waiter that will be notified when the thread
/// completes. The Waiter usually has the only counted reference to the RThread; if the RThread's
/// refCount goes to zero it assumes that the Waiter is no longer interested and that any
/// in-progress computation should be cancelled. Note that in this case there is a race condition
/// between the notification being delivered and the RThread being dropped, so the Waiter must be
/// prepared to receive a notification even after its refCount has gone to zero.
class RThread extends RefCounted {

  /// Each RThread is created with a Waiter to be notified when the RThread completes. The Waiter's
  /// [#threadDone] method will be called at most once by the RThread; it will not be called if the
  /// RThread's reference count goes to zero before its execution completes.
  interface Waiter {
    /// Exactly one of `result` and `errorStack` will be non-null.
    void threadDone(TState tstate, @RC.In Value result, @RC.In TStack errorStack);
  }

  /// A trivial Waiter implementation that just discards the RThread's result; substituted for the
  /// original Waiter when the RThread has been dropped.
  private static final Waiter DISCARD_RESULTS =
      (tstate, result, errorStack) -> {
        tstate.dropValue(result);
        tstate.dropReference(errorStack);
      };

  private static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + 2 * SizeOf.PTR);

  /// This is not a counted reference! (If it was counted the Waiter could not be collected until
  /// the RThread completed; we want to allow no-longer referenced futures to be dropped and the
  /// corresponding in-progress RThread to be cancelled.)
  ///
  /// Set to null after the notification has been delivered or the RThread has been dropped.
  @Keep private Waiter waiter;

  /// Non-null if the RThread has been suspended.
  @Keep private TStack suspended;

  static final VarHandle WAITER =
      Handle.forVar(MethodHandles.lookup(), RThread.class, "waiter", Waiter.class);

  static final VarHandle SUSPENDED =
      Handle.forVar(MethodHandles.lookup(), RThread.class, "suspended", TStack.class);

  RThread(Allocator allocator, Waiter waiter) {
    this.waiter = waiter;
    allocator.recordAlloc(this, OBJ_SIZE);
  }

  /// Returns true if this RThread has been cancelled or completed. Inherently racy.
  boolean isCancelled() {
    return WAITER.getOpaque(this) == null;
  }

  /// Returns the waiter and atomically sets it to null. If the waiter was already null, returns
  /// [#DISCARD_RESULTS].
  private Waiter takeWaiter() {
    Waiter result = (Waiter) WAITER.getAndSetAcquire(this, null);
    return (result != null) ? result : DISCARD_RESULTS;
  }

  void setSuspended(TState tstate, @RC.In TStack stack) {
    assert suspended == null;
    Value topOfStack = stack.first();
    BaseType.BlockingEntryType blockingType;
    if (topOfStack.baseType() instanceof BaseType.BlockingEntryType bet) {
      blockingType = bet;
      // After storing stack we no longer own its reference count, so increment this first.
      RefCounted.addRef(topOfStack);
    } else {
      // This thread escaped, so we can resume it immediately.
      blockingType = null;
      topOfStack = null;
    }
    SUSPENDED.setRelease(this, stack);
    if (blockingType != null) {
      blockingType.suspended(tstate, topOfStack, this);
      tstate.dropValue(topOfStack);
    } else {
      resumeAsync(tstate.tracker(), null);
    }
  }

  /// Returns the suspended stack and atomically sets it to null.
  private TStack takeSuspended() {
    return (TStack) SUSPENDED.getAndSetAcquire(this, null);
  }

  /// Resumes an RThread that was previously suspended, using a separate Java thread. If it was
  /// suspended due to a blocking operation, `value` should be either an error (and the RThread's
  /// stack will unwind with that error) or the result of the blocking operation. If the RThread was
  /// suspended due to an escape `value` should be null.
  void resumeAsync(ResourceTracker tracker, @RC.In Value value) {
    ForkJoinPool.commonPool().execute(() -> run(tracker, tstate -> resumeSync(tstate, value)));
  }

  private void resumeSync(TState tstate, @RC.In Value value) {
    TStack suspended = takeSuspended();
    if (suspended == null) {
      assert isCancelled();
      tstate.dropValue(value);
      return;
    }
    assert (value != null) == (suspended.first().baseType() instanceof BaseType.BlockingEntryType);
    if (value != null) {
      if (value.baseType() instanceof Err) {
        tstate.setStackRest(suspended);
        tstate.pushUnwind(value);
        return;
      } else {
        tstate.setResult(value);
      }
    }
    tstate.resumeStack(suspended, TStack.BASE);
  }

  /// Executes `runner` on the current Java thread; when it returns one of these should be true:
  ///
  /// - [TState#unwindStarted] is false, this RThread's result has been saved with
  ///   [TState#setResult], and [TState#stackRestIsBase] is true; or
  /// - [TState#takeUnwind] starts with an error; or
  /// - [TState#takeUnwind] starts with a BlockingEntryType; or
  /// - [#isCancelled] is true.
  ///
  /// In the first or second case the waiter's [Waiter#threadDone] method will be called; in the
  /// third case the RThread will be suspended until a subsequent call to [#resumeAsync] is made.
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
        if (stack.hasErr()) {
          takeWaiter().threadDone(tstate, null, stack);
        } else {
          setSuspended(tstate, stack);
        }
      } else {
        assert tstate.stackRestIsBase();
        Value result = tstate.takeResult(0);
        tstate.clearResults();
        takeWaiter().threadDone(tstate, result, null);
      }
    } catch (RuntimeException | AssertionError e) {
      // Shouldn't happen, but if it does try to construct a meaningful stack entry to go along
      // with the "internal error" message.
      e.printStackTrace();
      Err.INTERNAL_ERROR.pushUnwind(tstate, e.toString());
      TStack stack = tstate.takeUnwind();
      takeWaiter().threadDone(tstate, null, stack);
    } finally {
      tstate.rThread = null;
      tstate.bindTo(prev);
    }
  }

  /// Returns a short, persistent, usually unique identifier for this RThread (suitable for output
  /// but not as a map key).
  String id() {
    return StringUtil.id(this);
  }

  @Override
  protected long visitRefs(RefVisitor visitor) {
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
