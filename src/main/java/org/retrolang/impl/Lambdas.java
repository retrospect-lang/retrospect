package org.retrolang.impl;

import com.google.common.base.Preconditions;
import java.util.Arrays;
import org.retrolang.Vm;

/**
 * A static-only class that creates BaseTypes for {@link VmFunction#asLambdaExpr} and for partial
 * applications of those lambdas.
 */
public class Lambdas {
  /**
   * A singleton BaseType that can be passed as the first argument to {@code at()} to execute the
   * given VmFunction. Only created for functions with one result and no inouts. Also used as the
   * StackEntryType for the tail calls made by {@code at()}.
   *
   * <p>See {@link Vm.Function#asLambdaExpr} for details of how the second argument to {@code at()}
   * is interpreted.
   */
  static class AsLambdaExpr extends BaseType.StackEntryType {
    final VmFunction function;

    AsLambdaExpr(VmFunction function) {
      super(0);
      this.function = function;
    }

    @Override
    public VmType vmType() {
      return Core.FUNCTION_LAMBDA;
    }

    /**
     * Implements the {@code at()} method. {@code caller} is an {@link BuiltinMethod.AnyFn} Caller.
     */
    void at(TState tstate, @RC.In Value arg, BuiltinMethod.Caller caller)
        throws Err.BuiltinException {
      int numArgs = function.numArgs;
      if (numArgs != 1) {
        Err.INVALID_ARGUMENT.unless(arg.isArrayOfLength(numArgs));
      }
      Object[] args = tstate.allocObjectArray(numArgs);
      if (numArgs == 1) {
        args[0] = arg;
      } else {
        arg.getElements(numArgs, args, 0);
        tstate.dropValue(arg);
      }
      tstate.startCall(caller, function, this, args);
    }

    /**
     * Creates a new {@link PartialApplication} baseType that saves all but one of the arguments to
     * this function, and uses the lambda argument as the specified argument. Only valid for
     * functions with at least two arguments.
     */
    BaseType partialApplication(int argNum) {
      Preconditions.checkArgument(argNum >= 0 && argNum < function.numArgs && function.numArgs > 1);
      return new PartialApplication(this, argNum);
    }

    // Implementation of StackEntryType

    @Override
    String localName(int i) {
      // Tail call, so no locals
      throw new AssertionError();
    }

    @Override
    VmFunction called() {
      return function;
    }

    @Override
    void resume(TState tstate, @RC.In Value entry, ResultsInfo results, MethodMemo mMemo) {
      assert entry.baseType() == this;
      // Since this is a tail call we just return its results as our own.
      // We're responsible for calling dropValue(entry), but we know it's a singleton so that would
      // be a no-op.
    }

    @Override
    public String toString() {
      return "`" + function + "`";
    }
  }

  /** A compound BaseType created by calls to {@link VmFunction#partialApplication}. */
  static class PartialApplication extends BaseType {
    final AsLambdaExpr asLambda;
    final int argNum;

    PartialApplication(AsLambdaExpr asLambda, int argNum) {
      super(asLambda.function.numArgs - 1);
      this.asLambda = asLambda;
      this.argNum = argNum;
    }

    @Override
    public VmType vmType() {
      return Core.PARTIAL_APPLICATION_LAMBDA;
    }

    /**
     * Implements the {@code at()} method. {@code caller} is an {@link BuiltinMethod.AnyFn} Caller.
     */
    void at(TState tstate, Value lambda, @RC.In Value arg, BuiltinMethod.Caller caller)
        throws Err.BuiltinException {
      assert lambda.baseType() == this;
      VmFunction function = asLambda.function;
      int numArgs = asLambda.function.numArgs;
      Object[] args = tstate.allocObjectArray(numArgs);
      for (int i = 0; i < numArgs; i++) {
        args[i] = (i == argNum) ? arg : lambda.element(i - (i > argNum ? 1 : 0));
      }
      // Rather than define our own stackEntryType we can just re-use the AsLambdaExpr
      tstate.startCall(caller, asLambda.function, asLambda, args);
    }

    @Override
    public String toString() {
      char[] marks = new char[asLambda.function.numArgs];
      Arrays.fill(marks, '.');
      marks[argNum] = '#';
      return String.format("`%s:%s`", asLambda.function, new String(marks));
    }
  }
}
