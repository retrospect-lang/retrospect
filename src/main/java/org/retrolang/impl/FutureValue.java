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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.util.SizeOf;
import org.retrolang.util.StringUtil;

/**
 * An initial implementation of future().
 *
 * <p>This is currently very simplistic. A more sophisticated approach would be to not hand off the
 * lambda to a new thread immediately, but instead start running it on the current thread. If that's
 * taking a while and there is more parallelism available, unwind back to the future() call and hand
 * off at that point. (This assumes that the lambda will often complete quickly or block.)
 */
public class FutureValue extends RefCounted implements Value, RThread.Waiter {

  @Core.Public static final VmFunctionBuilder future1 = VmFunctionBuilder.create("future", 1);

  @Core.Public
  static final VmFunctionBuilder testFuture = VmFunctionBuilder.create("testFuture", 0);

  @Core.Public static final VmFunctionBuilder waitFor = VmFunctionBuilder.create("waitFor", 1);

  @Core.Public
  static final VmFunctionBuilder setTestFuture =
      VmFunctionBuilder.create("setTestFuture", 2).hasNoResult();

  @Core.Public
  static final BaseType.NonCompositional FUTURE_TYPE =
      new BaseType.NonCompositional(Core.CORE, "Future", FutureValue.class);

  /** The BlockingEntryType for threads that have called waitFor(). */
  static final BaseType.BlockingEntryType WAITFOR_ENTRY =
      new BaseType.BlockingEntryType("WaitFor", "fv") {
        @Override
        VmFunction called() {
          return waitFor.fn();
        }

        @Override
        void suspended(TState tstate, Value entry, RThread thread) {
          ((FutureValue) entry.peekElement(0)).suspended(tstate, thread);
        }
      };

  static class FutureMethod extends BuiltinMethod {
    /** This ValueMemo will be used for the result of the future's call to {@code at()}. */
    static final ExtraValueMemo atResultsMemo = new ExtraValueMemo(1);

    @Core.Method("future(Lambda)")
    static Value begin(TState tstate, MethodMemo mm, @RC.In Value lambda, @Fn("at:2") Caller at) {
      // This isn't ready for code generation!
      assert !tstate.hasCodeGen();
      // Note that we don't do the usual thing of starting a call with our Caller in this thread;
      // we instead pass the Caller to another thread and just return the newly-created FutureValue
      // from this method.
      FutureValue fv = new FutureValue(tstate, true);
      ResourceTracker tracker = tstate.tracker();
      ForkJoinPool.commonPool()
          .execute(() -> fv.lambdaThread.run(tracker, t -> fv.run(t, mm, lambda, at)));
      return fv;
    }
  }

  @Core.Method("testFuture()")
  static Value testFuture(TState tstate) {
    return new FutureValue(tstate, false);
  }

  @Core.Method("waitFor(Future)")
  static Value waitFor(TState tstate, Value fv) {
    return ((FutureValue) fv).doWaitFor(tstate);
  }

  @Core.Method("setTestFuture(Future, _)")
  static void setFuture(TState tstate, Value fv, @RC.In Value result) throws BuiltinException {
    ((FutureValue) fv).setResult(tstate, result, true);
  }

  private static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + 3 * SizeOf.PTR);

  /**
   * If {@code result} is TO_BE_SET this FutureValue has not yet completed. If {@code
   * result.baseType()} is an Err this FutureValue's lambda errored. If {@code result} is null this
   * FutureValue has been dropped and any asynchronous update will be discarded. Otherwise this is
   * the value to be returned by {@link #waitFor}.
   */
  @GuardedBy("this")
  private Value result = Core.TO_BE_SET;

  /**
   * If non-null this FutureValue has a lambda. If null this FutureValue's value will be set by a
   * call to {@link #setFuture}.
   */
  final RThread lambdaThread;

  /**
   * If non-null, {@link #result} must be TO_BE_SET and these threads are waiting for the future's
   * result.
   */
  @GuardedBy("this")
  private List<RThread> pending;

  private FutureValue(TState tstate, boolean hasLambda) {
    this.lambdaThread = hasLambda ? new RThread(tstate, this) : null;
    tstate.recordAlloc(this, OBJ_SIZE);
  }

  /**
   * Called in a separate thread on a newly-created FutureValue. {@code mMemo} is the MethodMemo for
   * the {@code future()} call, and {@code caller} is a Caller for {@code at()}.
   */
  private void run(
      TState tstate, MethodMemo mMemo, @RC.In Value lambda, BuiltinMethod.Caller caller) {
    boolean done = false;
    try {
      tstate.setStackRest(TStack.BASE);
      tstate.startCall(caller, lambda, Core.EMPTY_ARRAY);
      // We're no longer running in the context of a builtin method, so we need to explicitly
      // make the call that is usually provided by BuiltinSupport.
      tstate.finishBuiltin(FutureMethod.atResultsMemo.valueMemo(tstate, mMemo), mMemo, null);
      done = true;
    } finally {
      // Try to ensure that even if something completely unexpected goes wrong we won't leave the
      // rest of the computation waiting for us.
      if (!done) {
        try {
          setResult(tstate, MYSTERY_FAILURE, false);
        } catch (BuiltinException e) {
          // Can't happen, and if it did there's no one left to tell about it
          e.printStackTrace();
        }
      }
    }
  }

  private static final Value MYSTERY_FAILURE =
      Err.INTERNAL_ERROR.uncountedOf(StringValue.uncounted("Unexpected error in FutureValue"));

  /**
   * Called when the RThread completes (for FutureValues with a lambda) or from {@code
   * setTestFuture()}.
   */
  private void setResult(TState tstate, @RC.In Value result, boolean fromSetTestFuture)
      throws BuiltinException {
    boolean tooLate;
    List<RThread> pending;
    synchronized (this) {
      if (result == MYSTERY_FAILURE && this.result != Core.TO_BE_SET) {
        // Never mind; something went wrong, but the future had already been resolved properly
        return;
      }
      pending = this.pending;
      this.pending = null;
      tooLate = (this.result == null);
      if (tooLate) {
        // This FutureValue has already been dropped; silently discard the result.
        // (We tried to cancel the RThread when we were dropped, but a race is possible.)
        assert !fromSetTestFuture && pending == null;
      } else {
        // setResult() should only be called once, and it should not be called from setTestFuture()
        // if this FutureValue has a lambda
        Err.INVALID_ARGUMENT.unless(
            this.result == Core.TO_BE_SET && fromSetTestFuture == (lambdaThread == null));
        if (pending != null) {
          // Once we set this.result we've given up our ownership of result's refCount (an async
          // thread could release it), so first create more references for the calls below.
          RefCounted.addRef(result, pending.size());
        }
        this.result = result;
      }
    }
    if (tooLate) {
      // The FutureValue has already been dropped, so we're not going to store this result.
      tstate.dropValue(result);
    } else if (pending != null) {
      // Note that we already incremented result's refCount above to account for all these
      // @RC.In calls.
      for (RThread rthread : pending) {
        rthread.resumeAsync(tstate.tracker(), result);
      }
    }
  }

  @RC.Out
  private Value doWaitFor(TState tstate) {
    synchronized (this) {
      Value result = this.result;
      if (result != Core.TO_BE_SET) {
        assert result != null && pending == null;
        Value.addRef(result);
        if (result.baseType() instanceof Err) {
          // This FutureValue has already completed with an error.  Set up the stack as if we
          // blocked, so that the error stack doesn't depend on who won the race.
          tstate.pushUnwind(blockingEntry(tstate));
          tstate.pushUnwind(result);
          return null;
        }
        return result;
      }
    }
    // This FutureValue hasn't completed yet, so start unwinding; when unwinding is complete we'll
    // be called back (via suspended()) and will add the RThread to pending at that point.
    tstate.startBlock(blockingEntry(tstate), null, null);
    return null;
  }

  /** Returns the blocking stack entry that will be used for threads calling waitFor(). */
  @RC.Out
  private Value blockingEntry(TState tstate) {
    return tstate.compound(WAITFOR_ENTRY, Value.addRef(this));
  }

  /** Called with a suspended RThread that should be resumed when this FutureValue completes. */
  private void suspended(TState tstate, RThread thread) {
    Value result;
    synchronized (this) {
      result = this.result;
      if (result == Core.TO_BE_SET) {
        if (pending == null) {
          pending = new ArrayList<>();
        }
        pending.add(thread);
        return;
      }
      // By the time we unwound the FutureValue had completed, so we should just resume the thread
      // immediately.
      assert result != null;
      // Do this before we release the lock, in case we race with someone dropping this FutureValue
      RefCounted.addRef(result);
    }
    thread.resumeAsync(tstate.tracker(), result);
  }

  @Override
  public void threadDone(TState tstate, @RC.In Value result, @RC.In TStack errorStack) {
    if (errorStack != null) {
      // Convert the TStack into an "Error while computing future" error
      assert result == null;
      int size = (int) errorStack.stream().count() - 1;
      Object[] entries = tstate.allocObjectArray(size);
      int i = 0;
      for (TStack entry = errorStack; entry != TStack.BASE; entry = entry.rest()) {
        entries[i++] = Value.addRef(entry.first());
      }
      assert i == size;
      tstate.dropReference(errorStack);
      result = tstate.compound(Err.FUTURE_ERROR, tstate.asArrayValue(entries, size));
    }
    try {
      setResult(tstate, result, false);
    } catch (BuiltinException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public BaseType baseType() {
    return FUTURE_TYPE;
  }

  @Override
  long visitRefs(RefVisitor visitor) {
    visitor.visitRefCounted(lambdaThread);
    Value result;
    synchronized (this) {
      result = this.result;
      if (MemoryHelper.isReleaser(visitor)) {
        // Don't let async threads read or write this after we've released it
        this.result = null;
        this.pending = null;
      }
    }
    if (result instanceof RefCounted rc) {
      visitor.visitRefCounted(rc);
    }
    return OBJ_SIZE;
  }

  @Override
  public String toString() {
    // If we created an RThread, print its id as our own to make it easier to match things up.
    String id = (lambdaThread == null) ? StringUtil.id(this) : lambdaThread.id();
    @SuppressWarnings("GuardedBy") // racy is better than acquiring locks in toString()
    Value result = this.result;
    // An empty box (for a not-yet-completed FutureValue), a box with an x (for a FutureValue that
    // errored), or a box with something in it.
    if (result == Core.TO_BE_SET) {
      return "□" + id;
    } else if (result != null && result.baseType() instanceof Err) {
      return "⊠" + id;
    } else {
      return "⊡" + id + ":" + result;
    }
  }
}
