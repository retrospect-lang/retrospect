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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import org.retrolang.code.CodeValue;
import org.retrolang.code.Op;
import org.retrolang.util.Bits;

/**
 * Used to create a builtin function, optionally with an associated method. Instances of
 * VmFunctionBuilder are intended to be created in a class's static constructor; the actual
 * VmFunction creation may be postponed until the class is scanned during module build time (adding
 * methods to a VmFunction.Generic involves locks, and acquiring locks during static construction is
 * an easy way to create deadlocks).
 */
public abstract class VmFunctionBuilder {
  private VmFunction fn;

  /**
   * This method will be called once while building the module; should set {@link #fn} and return
   * it.
   */
  final VmFunction build(VmModule module) {
    // We don't worry about locking here; our caller is responsible for synchronization.
    assert isNotBuilt();
    this.fn = doBuild(module);
    return fn;
  }

  /** Should only be called from {@link #build}. */
  abstract VmFunction doBuild(VmModule module);

  /** Only used for assertions. */
  final boolean isNotBuilt() {
    return fn == null;
  }

  /**
   * Returns the VmFunction that was built by this builder. Should only be called after the module
   * has been initialized.
   */
  public final VmFunction fn() {
    assert fn != null;
    return fn;
  }

  /**
   * Returns the VmFunction that was built by this builder as a lambda value. Should only be called
   * after the module has been initialized.
   */
  public final Singleton asLambdaExpr() {
    return fn().asLambdaExpr();
  }

  /** Creates a zero-arg function that always returns the given value. */
  public static VmFunctionBuilder fromConstant(String name, Value v) {
    assert !RefCounted.isRefCounted(v);
    VmFunction fn = new VmFunction.Simple(name, 1, (tstate, args) -> tstate.setResult(v));
    return new VmFunctionBuilder() {
      @Override
      VmFunction doBuild(VmModule module) {
        return fn;
      }
    };
  }

  /**
   * Creates a function with the specified name and number of arguments. By default the function
   * will return a result, have no inout arguments, and will not be open, but those properties can
   * all be changed by calling methods on the returned builder.
   */
  public static General create(String name, int numArgs) {
    return new General(name, numArgs);
  }

  /** A VmFunctionBuilder with additional methods to set properties of the built VmFunction. */
  public static class General extends VmFunctionBuilder {
    final String name;
    final int numArgs;
    private Bits inout = Bits.EMPTY;
    private int numResults = 1;
    private boolean isOpen = false;

    private General(String name, int numArgs) {
      this.name = name;
      this.numArgs = numArgs;
    }

    /** Specifies that the function will not return a result. */
    public General hasNoResult() {
      assert isNotBuilt() && this.numResults == 1;
      this.numResults = 0;
      return this;
    }

    /**
     * Specifies that the function will return the specified number of results, in addition to any
     * inout arguments. A function returning more than one result cannot be referenced from
     * Retrospect-language code, but may be used internally.
     */
    public General hasResults(int numResults) {
      assert isNotBuilt() && numResults >= 0 && this.numResults == 1;
      this.numResults = numResults;
      return this;
    }

    /** Specifies that one of the function's arguments is inout. */
    public General hasInoutArg(int argIndex) {
      assert isNotBuilt();
      assert argIndex < numArgs && !inout.test(argIndex);
      inout = inout.set(argIndex);
      return this;
    }

    /** Specifies that the function is open. */
    public General isOpen() {
      assert isNotBuilt() && !this.isOpen;
      this.isOpen = true;
      return this;
    }

    @Override
    VmFunction.General doBuild(VmModule module) {
      int totalResults = this.numResults + inout.count();
      return new VmFunction.General(module, name, numArgs, inout, totalResults, isOpen);
    }
  }

  /**
   * Creates a function with the specified name and a method implemented by calling an {@link Op}.
   * Currently the Op may only take double arguments and return a double.
   *
   * <p>By default the method is assumed to never return NaN; if it might, you must call {@link
   * FromOp#mayReturnNaN} on the returned builder.
   */
  public static FromOp fromOp(String name, Op op) {
    return new FromOp(name, op);
  }

  /**
   * A VmFunctionBuilder.General with a built-in method provided by a Java method, applicable when
   * all arguments are doubles and the result is a double.
   */
  public static final class FromOp extends General {
    private final Op op;
    private boolean mayReturnNaN = false;

    private FromOp(String name, Op op) {
      super(name, op.argTypes.size());
      // op must return a double, and all of its args must be doubles
      assert op.resultType == double.class && op.argTypes.stream().allMatch(t -> t == double.class);
      this.op = op;
    }

    /**
     * Specifies that the MethodHandle provided for this function may return NaN (which should be
     * converted to {@link Core#NONE}).
     */
    public FromOp mayReturnNaN() {
      assert isNotBuilt();
      this.mayReturnNaN = true;
      return this;
    }

    @Override
    VmFunction.General doBuild(VmModule module) {
      VmFunction.General fn = super.doBuild(module);
      // Our method is applicable if all args are numbers
      MethodPredicate predicate = MethodPredicate.TRUE;
      for (int i = 0; i < numArgs; i++) {
        predicate = predicate.and(Core.NUMBER.asType.argType(i, true));
      }
      fn.addMethod(
          new VmMethod(
              fn,
              predicate,
              /* isDefault= */ false,
              new SimpleImpl(name, op, mayReturnNaN),
              MethodMemo.EXLINE_CALL_WEIGHT,
              MethodMemo.Factory.TRIVIAL));
      return fn;
    }
  }

  private static class SimpleImpl implements MethodImpl {
    final String name;

    // An Op with signature double <- double, ..., double
    final Op op;

    // An mh with signature double <- Object[]
    final MethodHandle wrappedMh;

    final boolean mayReturnNaN;

    SimpleImpl(String name, Op op, boolean mayReturnNaN) {
      this.name = name;
      this.op = op;
      this.mayReturnNaN = mayReturnNaN;
      MethodHandle wrappedMh = op.mh;
      int n = op.argTypes.size();
      // Call NumValue.asDouble on each arg
      for (int i = 0; i < n; i++) {
        wrappedMh = MethodHandles.filterArguments(wrappedMh, i, NumValue.AS_DOUBLE);
      }
      // Convert multiple Value args to a single Object[] arg
      wrappedMh = BuiltinSupport.spreadObjArray(wrappedMh, n);
      this.wrappedMh = wrappedMh;
    }

    @Override
    public void execute(
        TState tstate, ResultsInfo results, MethodMemo mMemo, @RC.In Object[] args) {
      double result;
      try {
        result = (double) wrappedMh.invokeExact(args);
      } catch (Throwable e) {
        throw new AssertionError(e);
      }
      tstate.dropReference(args);
      Value v = NumValue.orNan(result, tstate);
      assert v != Core.NONE || mayReturnNaN;
      tstate.setResult(v);
    }

    @Override
    public void emit(CodeGen codeGen, ResultsInfo results, MethodMemo mMemo, Object[] args) {
      CodeValue[] codeValues = new CodeValue[op.argTypes.size()];
      Arrays.setAll(codeValues, i -> codeGen.asCodeValue((Value) args[i]));
      codeGen.setResultWithNaNCheck(op.result(codeValues));
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
