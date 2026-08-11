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
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Map;
import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.TestBlock;
import org.retrolang.impl.BaseType.SimpleStackEntryType;
import org.retrolang.impl.BuiltinSupport.BuiltinImpl;
import org.retrolang.impl.BuiltinSupport.ContinuationMethod;
import org.retrolang.impl.Err.BuiltinException;

/// Each subclass of BuiltinMethod implements a single built-in method for a Core function.
///
/// The class itself is currently trivial (it defines no fields or methods); the subclass contract
/// is determined by annotations (@Core.Method) and naming conventions for fields and methods; see
/// builtins.md for details.
public abstract class BuiltinMethod {
  /// A `Continuation`, [LoopContinuation], or [DetachedContinuation] annotation must appear on any
  /// method that will be the continuation argument to a Caller or the target of a call to
  /// [TState#jump].
  ///
  /// A `Continuation` method may only be referenced from the builtin's `begin` method or from a
  /// lower-numbered continuation.
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Continuation {
    int order() default 1;
  }

  /// A `LoopContinuation` must be used in place of a [Continuation] annotation when it will be
  /// referenced from higher-numbered continuations (or from itself).
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface LoopContinuation {
    int order() default 1;
  }

  /// A `DetachedContinuation` must be used in place of a [Continuation] annotation for the
  /// continuation of a detached Caller ([Caller#detach]).
  ///
  /// The first step of a `DetachedContinuation` method should usually be to check
  /// [TState#unwindStarted]; if it is true
  ///
  /// - the function result arguments that have been passed will be null (since the function call
  ///   has not completed successfully);
  /// - any `@Saved arguments` will have the values that were saved by the caller;
  /// - the method should call [TState#takeUnwind] and do something appropriate with the stack
  ///   before returning; and
  /// - the method must complete without unwinding or tracing.
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface DetachedContinuation {
    int order() default 1;
  }

  /// A `Saved` annotation should appear on at most one Value parameter of a [Continuation] method.
  /// That parameter and any following Value parameters will have their values provided by a call to
  /// `saving()` at the [TState#startCall] site.
  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Saved {}

  /// Each Caller parameter of a [Continuation] or [Core.Method] method must have a `Fn` annotation
  /// identifying the function to be called (or a [AnyFn] annotation, if the function to be called
  /// will be determined at execution time).
  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Fn {
    String value();
  }

  /// An `AnyFn` annotation on a Caller indicates that the function to be called will be determined
  /// at execution time. `AnyFn` Callers can call any VmFunction that has a single result, and can
  /// only be used with the `TState.startCall()` method that takes the VmFunction as an additional
  /// argument.
  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface AnyFn {}

  /// The result of [TState#startCall] is a `Saver`, enabling the caller to specify additional
  /// values that should be passed to the call's continuation.
  public interface Saver {
    void saving(Value... args);
  }

  public static class SimpleRuntimeHelper {
    boolean canSimplifyConstants;

    /// The underlying Java method
    private Op op;

    /// 1 if the first argument of [#op] is a TState, 0 otherwise.
    private int argStart;

    /// [#op] wrapped to take two arguments (TState, Object[]) and return a Value. The result is
    /// [RC.Out] iff `RcOp.isResultRcOut(op)` is true, and the `i`th element of the Object[] is
    /// [RC.In] iff `RcOp.isArgRcIn(op)` contains `i` (the array itself is never [RC.In]).
    private MethodHandle mh;

    private BaseType.NonCompositional resultType;

    public SimpleRuntimeHelper canSimplifyConstants() {
      assert op == null;
      canSimplifyConstants = true;
      return this;
    }

    void initialize(Op op, MethodHandle mh, BaseType.NonCompositional resultType) {
      this.op = op;
      this.argStart = (op.argTypes.get(0) == TState.class) ? 1 : 0;
      this.mh = mh;
      this.resultType = resultType;
    }

    public Op op() {
      assert op != null;
      return op;
    }

    private boolean callNow(TState tstate, Object[] args) {
      assert args.length == op.argTypes.size() - argStart;
      if (!tstate.hasCodeGen()) {
        return true;
      } else {
        return canSimplifyConstants && Arrays.stream(args).noneMatch(arg -> arg instanceof RValue);
      }
    }

    public Value call(TState tstate, Object... args) {
      if (callNow(tstate, args)) {
        return callWrapped(tstate, args);
      } else {
        CodeGen codeGen = tstate.codeGen();
        Register r = BuiltinSupport.emitCall(op, argStart, codeGen, args);
        if (resultType != null) {
          return codeGen.toValue(r, resultType);
        } else if (op.resultType != boolean.class) {
          return codeGen.toValue(r);
        } else {
          return RValue.intToBoolean(r);
        }
      }
    }

    public Condition asCondition(TState tstate, Object... args) {
      assert op.resultType == boolean.class;
      if (callNow(tstate, args)) {
        Value result = callWrapped(tstate, args);
        return Condition.of(result == Core.TRUE);
      } else {
        return new Condition() {
          @Override
          public void addTest(CodeGen codeGen, FutureBlock elseBranch) {
            Register r = BuiltinSupport.emitCall(op, argStart, codeGen, args);
            new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, r, CodeValue.ZERO)
                .setBranch(true, elseBranch)
                .addTo(codeGen.cb);
          }
        };
      }
    }

    private Value callWrapped(TState tstate, Object... args) {
      try {
        return (Value) mh.invokeExact(tstate, args);
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable e) {
        throw new AssertionError(e);
      }
    }
  }

  /// A distinguished Continuation used as the value of [Caller#continuation] for Callers that
  /// should return the function result as the result of the builtin. Should never be called.
  static final ContinuationMethod TAIL_CALL =
      new ContinuationMethod(null, "(tail call)", -1, false, false, null, new String[0]);

  /// Subclasses of BuiltinStatic (such as Caller and ExtraValueMemo) are intended for use as static
  /// fields in subclasses of BuiltinMethod and are initialized by
  /// [BuiltinSupport#addMethodFromNestedClass].
  abstract static class BuiltinStatic {
    /// Called once on each instance of a BuiltinStatic subclass to initialize it.
    abstract void setup(String where, BuiltinImpl impl, Map<String, VmFunction> functions);
  }

  /// A Caller pairs a [VmFunction] to be called with a [Continuation] that will receive the results
  /// (and, optionally, some additional saved values).
  public static final class Caller extends BuiltinStatic {
    final String fnKey;

    final String continuationName;

    private boolean detached;

    /// The function to be called; null if this is an [AnyFn] Caller.
    private VmFunction fn;

    private CallSite callSite;

    /// The continuation to be called after the function returns successfully.
    private ContinuationMethod continuation;

    /// A SimpleStackEntryType that will be used if the stack is unwound during the initial function
    /// call. Has a localName for each value passed to [Saver#saving].
    private SimpleStackEntryType duringCall;

    /// A new Caller cannot be used until it has been fully initialized by calling [#setup].
    public Caller(String fnKey, String continuationName) {
      this.fnKey = fnKey;
      this.continuationName = continuationName;
    }

    /// Makes this Caller detached. A detached Caller will always call its continuation promptly,
    /// but the function may not have completed when it does.
    ///
    /// If the function has completed, the continuation will be called normally.
    ///
    /// If the function has not completed, the continuation will be called with null as the function
    /// result(s) and with [TState#unwindStarted] true.
    public Caller detach() {
      Preconditions.checkState(!detached && callSite == null);
      detached = true;
      return this;
    }

    /// Returns information about the values that have been passed as one of the arguments to this
    /// Caller.
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

    /// Creates a fully-initialized tail-calling Caller.
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

    boolean isDetached() {
      return detached;
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
      Preconditions.checkArgument(
          continuation.isDetached == detached,
          "Continuation %s be detached (%s)",
          (detached ? "must" : "must not"),
          where);
      setup(where, calledFn, impl, continuation);
    }

    /// If `fn` is null this is an [AnyFn] caller and `continuation` must be `TAIL_CALL`.
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

  /// An ExtraValueMemo allocates a ValueMemo in each instance of this method's memo.
  public static final class ExtraValueMemo extends BuiltinStatic {
    private int index = -1;
    final int size;

    /// Allocates a ValueMemo of the specified size.
    public ExtraValueMemo(int size) {
      this.size = size;
    }

    /// Returns the ValueMemo from the given MethodMemo.
    public ValueMemo valueMemo(TState tstate, MethodMemo memo) {
      return memo.valueMemo(tstate, index, size);
    }

    @Override
    void setup(String where, BuiltinImpl impl, Map<String, VmFunction> functions) {
      assert this.index < 0;
      this.index = impl.extraValueMemoIndex();
    }
  }

  /// Used to create a stack entry if we unwind during the execution of a Caller's call.
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
      assert !caller.isDetached();
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
      entry.getElements(size(), values, numResults);
      tstate.dropValue(entry);
      tstate.resumeBuiltin(caller.continuation, values, results, mMemo);
    }
  }

  /// A convenience method that converts a Value to a Java boolean. Throws an appropriate
  /// [BuiltinException] if the given Value is not `True` or `False`.
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
