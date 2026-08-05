package org.retrolang.compiler;

import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.retrolang.Vm;

/// A Function, Compound, or something else that can be called with its arguments optionally
/// distributed.
interface Distributable {

  int numArgs();

  int numResults();

  /// Returns true if the Expr returned by [#compileApply] may depend on Locals that were passed as
  /// args, false if it returns a new Local.
  default boolean applyResultReferencesArgs() {
    return this instanceof FromCompound;
  }

  /// Returns an Expr for the result of this Distributable applied to the given (non-distributed)
  /// arguments. If a Call instruction is needed it will be added to `ib`, and the result stored in
  /// the Local returned by `outputLocal`.
  Vm.Expr compileApply(Vm.Expr[] args, Vm.InstructionBlock ib, Supplier<Vm.Local> outputLocal);

  /// If there is a lambda value corresponding to this Distributable, returns it; otherwise returns
  /// null.
  Vm.Expr asLambda();

  /// A Distributable that calls the given Vm.Function (which must have zero or one results and no
  /// inOut arguments).
  ///
  /// (A Function with no results is never distributed; we only support that case to simplify some
  /// logic in [ExpressionCompiler#compileFunctionCall].)
  class FromFunction implements Distributable {
    final Vm.Function function;

    FromFunction(Vm.Function function) {
      assert function.numResults() <= 1
          && IntStream.range(0, function.numArgs()).noneMatch(function::argIsInout);
      this.function = function;
    }

    @Override
    public int numArgs() {
      return function.numArgs();
    }

    @Override
    public int numResults() {
      return function.numResults();
    }

    @Override
    public Vm.Expr compileApply(
        Vm.Expr[] args, Vm.InstructionBlock ib, Supplier<Vm.Local> outputLocal) {
      if (function.numResults() == 1) {
        // The usual case
        Vm.Local result = outputLocal.get();
        ib.emitCall(result, function, args);
        return result;
      } else {
        ib.emitCall(new Vm.Local[0], function, args);
        return null;
      }
    }

    @Override
    public Vm.Expr asLambda() {
      return function.asLambdaExpr();
    }
  }

  /// A Distributable that calls a second (outer) function on the result of an inner Distributable.
  /// This currently only supports wrapping other function calls (not Vm.Compounds), but it would be
  /// straightforward to fix that. Since it is currently only used with `not()` as the outer
  /// function I haven't bothered.
  class NestedFunction implements Distributable {
    final Vm.Function outer;
    final Distributable inner;

    NestedFunction(Vm.Function outer, Distributable inner) {
      assert outer.numResults() == 1 && outer.numArgs() == 1 && !outer.argIsInout(0);
      assert inner.numResults() == 1 && !(inner instanceof FromCompound);
      this.outer = outer;
      this.inner = inner;
    }

    @Override
    public int numArgs() {
      return inner.numArgs();
    }

    @Override
    public int numResults() {
      return 1;
    }

    @Override
    public Vm.Expr compileApply(
        Vm.Expr[] args, Vm.InstructionBlock ib, Supplier<Vm.Local> outputLocal) {
      Vm.Local result = (Vm.Local) inner.compileApply(args, ib, outputLocal);
      // We can do the outer call using the same result local.
      ib.emitCall(result, outer, result);
      return result;
    }

    @Override
    public Vm.Expr asLambda() {
      return null;
    }
  }

  /// A Distributable that creates instances of the given Vm.Compound.
  class FromCompound implements Distributable {
    final Vm.Compound compound;
    final int size;

    FromCompound(Vm.Compound compound, int size) {
      this.compound = compound;
      this.size = size;
    }

    @Override
    public int numArgs() {
      return size;
    }

    @Override
    public int numResults() {
      return 1;
    }

    @Override
    public Vm.Expr compileApply(
        Vm.Expr[] args, Vm.InstructionBlock ib, Supplier<Vm.Local> outputLocal) {
      return compound.make(args);
    }

    @Override
    public Vm.Expr asLambda() {
      return compound.asLambdaExpr();
    }
  }

  /// A synonym for FromCompound; used so that we can identify instances that create arrays and
  /// treat them specially in one situation.
  class FromArray extends FromCompound {
    FromArray(Vm.Compound compound, int size) {
      super(compound, size);
    }
  }
}
