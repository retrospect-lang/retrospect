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
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import org.retrolang.impl.BaseType.SimpleStackEntryType;
import org.retrolang.impl.BuiltinSupport.BuiltinImpl;
import org.retrolang.impl.BuiltinSupport.ContinuationMethod;
import org.retrolang.impl.Err.BuiltinException;

/**
 * Each subclass of BuiltinMethod implements a single built-in method for a Core function.
 *
 * <p>The class itself is currently trivial (it defines no fields or methods); the subclass contract
 * is determined by annotations (@Core.Method) and naming conventions for fields and methods; see
 * builtins.md for details.
 */
public abstract class BuiltinMethod {
  /**
   * A {@code Continuation} or {@link LoopContinuation} annotation should appear on any method that
   * will be the continuation argument to a Caller or the target of a call to {@link TState#jump}.
   *
   * <p>A {@code Continuation} method may only be referenced from the builtin's {@code begin} method
   * or from a lower-numbered continuation.
   */
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Continuation {
    int order() default 1;
  }

  /**
   * A {@code LoopContinuation} must be used in place of a {@link Continuation} annotation when it
   * will be referenced from higher-numbered continuations (or from itself).
   */
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface LoopContinuation {
    int order() default 1;
  }

  /**
   * A {@code Saved} annotation should appear on at most one Value parameter of a {@link
   * Continuation} method. That parameter and any following Value parameters will have their values
   * provided by a call to {@code saving()} at the {@link TState#startCall} site.
   */
  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Saved {}

  /**
   * Each Caller parameter of a {@link Continuation} or {@link Core.Method} method must have a
   * {@code Fn} annotation identifying the function to be called (or a {@link AnyFn} annotation, if
   * the function to be called will be determined at execution time).
   */
  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Fn {
    String value();
  }

  /**
   * An {@code AnyFn} annotation on a Caller indicates that the function to be called will be
   * determined at execution time. {@code AnyFn} Callers can call any VmFunction that has a single
   * result, and can only be used with the {@code TState.startCall()} method that takes the
   * VmFunction as an additional argument.
   */
  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface AnyFn {}

  /**
   * The result of {@link TState#startCall} is a {@code Saver}, enabling the caller to specify
   * additional values that should be passed to the call's continuation.
   */
  public interface Saver {
    void saving(Value... args);
  }

  /**
   * A distinguished Continuation used as the value of {@link Caller#continuation} for Callers that
   * should return the function result as the result of the builtin. Should never be called.
   */
  static final ContinuationMethod TAIL_CALL =
      new ContinuationMethod(null, "(tail call)", -1, false, null, new String[0]);

  /**
   * Subclasses of BuiltinStatic (such as Caller and ExtraValueMemo) are intended for use as static
   * fields in subclasses of BuiltinMethod and are initialized by {@link
   * BuiltinSupport#addMethodFromNestedClass}.
   */
  abstract static class BuiltinStatic {
    /** Called once on each instance of a BuiltinStatic subclass to initialize it. */
    abstract void setup(String where, BuiltinImpl impl, Map<String, VmFunction> functions);
  }

  /**
   * A Caller pairs a {@link VmFunction} to be called with a {@link Continuation} that will receive
   * the results (and, optionally, some additional saved values).
   */
  public static final class Caller extends BuiltinStatic {
    final String fnKey;

    final String continuationName;

    /** The function to be called; null if this is an {@link AnyFn} Caller. */
    private VmFunction fn;

    private CallSite callSite;

    /** The continuation to be called after the function returns successfully. */
    private BuiltinSupport.ContinuationMethod continuation;

    /**
     * A SimpleStackEntryType that will be used if the stack is unwound during the initial function
     * call. Has a localName for each value passed to {@link Saver#saving}.
     */
    private SimpleStackEntryType duringCall;

    /**
     * A new Caller cannot be used until it has been fully initialized by calling {@link #setup}.
     */
    public Caller(String fnKey, String continuationName) {
      this.fnKey = fnKey;
      this.continuationName = continuationName;
    }

    /**
     * Returns information about the values that have been passed as one of the arguments to this
     * Caller.
     */
    public <T> T argInfo(TState tstate, MethodMemo memo, int argIndex, TProperty<T> property) {
      ResultsInfo resultsInfo;
      synchronized (tstate.scope().memoMerger) {
        CallMemo cm = memo.memoForCall(callSite);
        if (cm == null) {
          resultsInfo = ResultsInfo.EMPTY;
        } else {
          // If this caller has dispatched to more than one method, we're only going to look at
          // the args that were passed in calls to one of those methods.  This is kinda lame, but
          // is good enough for current purposes.
          resultsInfo = cm.firstMethodMemo().argsMemo;
        }
      }
      return resultsInfo.result(argIndex, property);
    }

    /** Creates a fully-initialized tail-calling Caller. */
    Caller(String where, String fnKey, VmFunction fn, BuiltinImpl impl) {
      this.fnKey = fnKey;
      this.continuationName = null;
      setup(where, fn, impl, TAIL_CALL);
    }

    VmFunction fn() {
      assert fn != null;
      return fn;
    }

    ContinuationMethod continuation() {
      assert continuation != null;
      return continuation;
    }

    CallSite callSite() {
      assert callSite != null;
      return callSite;
    }

    SimpleStackEntryType duringCall() {
      assert duringCall != null;
      return duringCall;
    }

    @Override
    void setup(String where, BuiltinImpl impl, Map<String, VmFunction> functions) {
      VmFunction calledFn = functions.get(fnKey);
      Preconditions.checkArgument(calledFn != null, "Unknown function \"%s\" (%s)", fnKey, where);
      ContinuationMethod continuation = impl.continuation(continuationName);
      int expectedNumArgs = calledFn.numResults + continuation.savedNames.length;
      Preconditions.checkArgument(
          continuation.builtinEntry.numArgs() == expectedNumArgs,
          "Continuation doesn't match method (%s)",
          where);
      setup(where, calledFn, impl, continuation);
    }

    /**
     * If {@code fn} is null this is an {@link AnyFn} caller and {@code continuation} must be {@code
     * TAIL_CALL}.
     */
    private void setup(
        String where, VmFunction fn, BuiltinImpl impl, ContinuationMethod continuation) {
      assert this.continuation == null;
      this.fn = fn;
      int numResults = (fn != null) ? fn.numResults : 1;
      this.callSite = new CallSite(impl.nextCallSiteIndex(), continuation.index(), numResults);
      callSite.setValueMemoSize(continuation.numArgs());
      this.continuation = continuation;
      this.duringCall = (fn == null) ? null : new DuringCall(where, this);
      callSite.duringCallEntryType = duringCall;
    }
  }

  /** An ExtraValueMemo allocates a ValueMemo in each instance of this method's memo. */
  public static final class ExtraValueMemo extends BuiltinStatic {
    private int index = -1;
    final int size;

    /** Allocates a ValueMemo of the specified size. */
    public ExtraValueMemo(int size) {
      this.size = size;
    }

    /** Returns the ValueMemo from the given MethodMemo. */
    public ValueMemo valueMemo(TState tstate, MethodMemo memo) {
      return memo.valueMemo(tstate, index, size);
    }

    @Override
    void setup(String where, BuiltinImpl impl, Map<String, VmFunction> functions) {
      assert this.index < 0;
      this.index = impl.extraValueMemoIndex();
    }
  }

  /** Used to create a stack entry if we unwind during the execution of a Caller's call. */
  private static class DuringCall extends SimpleStackEntryType {
    final Caller caller;

    DuringCall(String where, Caller caller) {
      super(where, caller.continuation.savedNames);
      this.caller = caller;
    }

    @Override
    VmFunction called() {
      return caller.fn;
    }

    @Override
    void resume(TState tstate, @RC.In Value entry, ResultsInfo results, MethodMemo mMemo) {
      // The called function was interrupted and unwound, but now we're able to resume.
      assert entry.baseType() == this;
      if (caller.continuation == TAIL_CALL) {
        // A tail caller -- we just return its results as our own.
        tstate.dropValue(entry);
        return;
      }
      int numResults = caller.fn.numResults;
      // Construct the argument array containing function results (from tstate) followed by
      // saved values (from the stack entry).
      Object[] values = tstate.allocObjectArray(numResults + size());
      for (int i = 0; i < numResults; i++) {
        values[i] = tstate.takeResult(i);
      }
      tstate.clearResults();
      for (int i = 0; i < size(); i++) {
        values[numResults + i] = entry.element(i);
      }
      tstate.dropValue(entry);
      tstate.runContinuation(caller.continuation, values, results, mMemo);
      // Normally continuations are called from the loop in {@link TState#finishBuiltin}, but in
      // this case we need to run it explicitly.
      tstate.finishBuiltin(results, mMemo, caller.continuation.builtinEntry.impl);
    }
  }

  /**
   * A convenience method that converts a Value to a Java boolean. Throws an appropriate {@link
   * BuiltinException} if the given Value is not {@code True} or {@code False}.
   */
  public static boolean testBoolean(Value v) throws BuiltinException {
    if (v == Core.TRUE) {
      return true;
    } else if (v == Core.FALSE) {
      return false;
    } else {
      assert !(v instanceof RValue);
      throw Err.NOT_BOOLEAN.asException();
    }
  }
}
