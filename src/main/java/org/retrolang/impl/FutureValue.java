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

import com.google.common.flogger.FluentLogger;
import com.google.common.flogger.StackSize;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

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

  /**
   * Creates a FutureValue that will be set by a non-Retrospect thread started on the first call to
   * {@link #waitFor}. {@code producer} will be called exactly once, but if the future is dropped
   * before {@link #waitFor} is called its argument will be null.
   */
  @RC.Out
  public static FutureValue newLazy(Allocator allocator, Consumer<Consumer<Value>> producer) {
    assert producer != null;
    return new FutureValue(allocator, Core.TO_BE_SET, producer);
  }

  /** Creates a FutureValue whose result is already known. */
  @RC.Out
  public static FutureValue newCompleted(Allocator allocator, @RC.In Value result) {
    assert result != Core.TO_BE_SET && !(result.baseType() instanceof Err);
    return new FutureValue(allocator, result, null);
  }

  /** Creates a FutureValue whose result will be set by a call to {@link #setTestFuture}. */
  @RC.Out
  public static FutureValue newForTest(Allocator allocator) {
    return new FutureValue(allocator, Core.TO_BE_SET, null);
  }

  /** Converts a TStack into an "Error while computing future" error. */
  @RC.Out
  static Value stackAsValue(TState tstate, @RC.In TStack errorStack) {
    assert errorStack.hasErr();
    int size = (int) errorStack.stream().count() - 1;
    Object[] entries = tstate.allocObjectArray(size);
    int i = 0;
    for (TStack entry = errorStack; entry != TStack.BASE; entry = entry.rest()) {
      entries[i++] = Value.addRef(entry.first());
    }
    assert i == size;
    tstate.dropReference(errorStack);
    return tstate.compound(Err.FUTURE_ERROR, tstate.asArrayValue(entries, size));
  }

  @RC.Out
  static FutureValue newWithError(TState tstate, @RC.In TStack errorStack) {
    return new FutureValue(tstate, stackAsValue(tstate, errorStack), null);
  }

  static class FutureMethod extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt").detach();
    static final ExtraValueMemo resultMemo = new ExtraValueMemo(1);

    @Core.Method("future(Lambda)")
    static void begin(TState tstate, @RC.In Value lambda) {
      tstate.startCall(at, lambda, Core.EMPTY_ARRAY);
    }

    @DetachedContinuation
    static Value afterAt(TState tstate, MethodMemo mMemo, @RC.In Value result) {
      if (!tstate.unwindStarted()) {
        assert result != null;
        if (result == Core.TO_BE_SET) {
          // Probably not possible, but just in case...
          result = Core.ABSENT;
        }
        if (!tstate.hasCodeGen()) {
          return newCompleted(tstate, result); // harmonizeResult(tstate, mMemo, result));
        } else {
          throw new UnsupportedOperationException();
        }
      }
      assert result == null;
      if (!tstate.hasCodeGen()) {
        return afterUnwind(tstate);
      } else {
        throw new UnsupportedOperationException();
      }
    }

    static FutureValue afterUnwind(TState tstate) {
      TStack tstack = tstate.takeUnwind();
      if (tstack.hasErr()) {
        return newWithError(tstate, tstack);
      } else {
        FutureValue fv = new FutureValue(tstate);
        fv.lambdaThread.setSuspended(tstate, tstack);
        return fv;
      }
    }
  }

  @Core.Method("testFuture()")
  static Value testFuture(TState tstate) {
    if (!tstate.hasCodeGen()) {
      return newForTest(tstate);
    } else {
      throw new UnsupportedOperationException();
    }
  }

  @Core.Method("waitFor(Future)")
  static void waitFor(TState tstate, ResultsInfo results, Value future) {
    if (future instanceof FutureValue fv) {
      Value result = fv.doWaitFor(tstate);
      if (result != null) {
        tstate.setResult(Value.addRef(result));
      }
    } else {
      throw new UnsupportedOperationException();
    }
  }

  @Core.Method("setTestFuture(Future, _)")
  static void setTestFuture(TState tstate, Value future, @RC.In Value result)
      throws BuiltinException {
    if (future instanceof FutureValue fv) {
      Err.INVALID_ARGUMENT.unless(fv.setTest(tstate, result));
    } else {
      throw new UnsupportedOperationException();
    }
  }

  private static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + 3 * SizeOf.PTR);

  /**
   * If {@code result} is TO_BE_SET this FutureValue has not yet completed. If {@code
   * result.baseType()} is an Err this FutureValue's lambda errored. If {@code result} is null this
   * FutureValue has been dropped and any asynchronous update will be discarded. Otherwise this is
   * the value to be returned by {@link #waitFor}.
   */
  @GuardedBy("this")
  private Value result;

  /**
   * If non-null this FutureValue has a lambda. If null this FutureValue's value will be set by a
   * call to {@link #setResult}.
   */
  final RThread lambdaThread;

  /**
   * If non-null this FutureValue will be set by an asynchronous process that will be started on the
   * first call to {@link #waitFor}. The producer may never be called, and must not have any counted
   * references.
   */
  @GuardedBy("this")
  private Consumer<Consumer<Value>> producer;

  /**
   * If non-null, {@link #result} must be TO_BE_SET and these threads are waiting for the future's
   * result.
   */
  @GuardedBy("this")
  private List<RThread> pending;

  /** Creates a FutureValue with an associated lambda. */
  private FutureValue(Allocator allocator) {
    result = Core.TO_BE_SET;
    this.lambdaThread = new RThread(allocator, this);
    allocator.recordAlloc(this, OBJ_SIZE);
  }

  /**
   * Creates a FutureValue without a lambda.
   *
   * <p>If {@code result} is TO_BE_SET and {@code producer} is null, the result will be set by a
   * call to {@link #setTestFuture}.
   *
   * <p>If {@code result} is TO_BE_SET and {@code producer} is non-null, {@code producer.accept()}
   * will be called on the first call to {@link #waitFor} and is responsible for (asynchronously)
   * calling back with the result. (If the FutureValue is dropped without a call to {@link
   * #waitFor}, {@code producer.accept()} will be called with null and is only responsible for
   * cleaning up state of its own.)
   *
   * <p>If {@code result} is anything other than TO_BE_SET, {@code producer} must be null and this
   * is an already-completed FutureValue.
   */
  private FutureValue(
      Allocator allocator, @RC.In Value result, Consumer<Consumer<Value>> producer) {
    assert result == Core.TO_BE_SET || producer == null;
    this.lambdaThread = null;
    this.producer = producer;
    this.result = result;
    allocator.recordAlloc(this, OBJ_SIZE);
  }

  boolean setTest(TState tstate, @RC.In Value result) {
    return lambdaThread == null && setResult(tstate, result);
  }

  /**
   * Called when the RThread completes (for FutureValues with a lambda), the producer calls {@code
   * accept()} (for FutureValues created by {@link #newLazy}), or from {@code setTestFuture()}.
   * Returns true unless {@code setResult()} has already been called on this FutureValue.
   */
  private boolean setResult(TState tstate, @RC.In Value result) {
    boolean tooLate;
    List<RThread> pending;
    synchronized (this) {
      assert producer == null;
      pending = this.pending;
      this.pending = null;
      tooLate = (this.result == null);
      if (tooLate) {
        // This FutureValue has already been dropped; silently discard the result.
        // (We tried to cancel the RThread when we were dropped, but a race is possible.)
        assert pending == null;
      } else {
        // setResult() should only be called once
        if (this.result != Core.TO_BE_SET) {
          return false;
        }
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
    return true;
  }

  private static void checkOK(boolean success) {
    if (!success) {
      logger.atWarning().withStackTrace(StackSize.MEDIUM).log("FutureValue already has a result");
    }
  }

  /**
   * Returns a non-Err Value if this FutureValue has completed successfully. Returns null and starts
   * unwinding if the FutureValue failed or has not yet completed.
   */
  Value doWaitFor(TState tstate) {
    Value result;
    Consumer<Consumer<Value>> producer;
    synchronized (this) {
      result = this.result;
      assert result != null && (result == Core.TO_BE_SET || pending == null);
      producer = this.producer;
      this.producer = null;
    }
    if (result != Core.TO_BE_SET) {
      if (producer != null) {
        assert false;
        producer.accept(null);
      }
      if (!(result.baseType() instanceof Err)) {
        return result;
      }
      // This FutureValue has already completed with an error.  Set up the stack as if we
      // blocked, so that the error stack doesn't depend on who won the race.
      tstate.pushUnwind(blockingEntry(tstate));
      tstate.pushUnwind(Value.addRef(result));
      return null;
    }
    if (producer != null) {
      ResourceTracker tracker = tstate.tracker();
      assert lambdaThread == null;
      producer.accept(v -> setResultFromOtherThread(tracker, v));
    }
    // This FutureValue hasn't completed yet, so start unwinding; when unwinding is complete we'll
    // be called back (via suspended()) and will add the RThread to pending at that point.
    tstate.startBlock(blockingEntry(tstate), null, null);
    return null;
  }

  void setResultFromOtherThread(ResourceTracker tracker, Value v) {
    TState.<Void>withTracker(
        tracker,
        tstate -> {
          checkOK(setResult(tstate, v));
          return null;
        });
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
    checkOK(setResult(tstate, result));
  }

  @Override
  public BaseType baseType() {
    return FUTURE_TYPE;
  }

  @Override
  protected long visitRefs(RefVisitor visitor) {
    visitor.visitRefCounted(lambdaThread);
    Value result;
    Consumer<Consumer<Value>> producer = null;
    synchronized (this) {
      result = this.result;
      if (MemoryHelper.isReleaser(visitor)) {
        // Don't let async threads read or write this after we've released it
        this.result = null;
        this.pending = null;
        producer = this.producer;
        this.producer = null;
      }
    }
    if (result instanceof RefCounted rc) {
      visitor.visitRefCounted(rc);
    }
    if (producer != null) {
      producer.accept(null);
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
