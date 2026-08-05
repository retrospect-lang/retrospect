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
import java.util.Arrays;
import org.retrolang.Vm;
import org.retrolang.impl.Err.BuiltinException;

class VmCompound implements Vm.Compound {
  final BaseType baseType;

  /// A Vm.Function that takes a value of this compound's type and returns each of its elements as a
  /// separate result.
  final VmFunction extract;

  final Singleton asLambdaExpr;

  VmCompound(BaseType baseType) {
    assert baseType.isCompositional();
    this.baseType = baseType;
    this.extract =
        new VmFunction.Simple("un" + baseType, baseType.size(), this::extractMethod, "x");
    this.asLambdaExpr = new AsLambdaExpr(baseType).asValue();
  }

  @Override
  public VmType asType() {
    // This will error if BaseType is a StructType or FixedArrayType, which do not have
    // corresponding VmTypes.
    return ((BaseType.Named) baseType).asType;
  }

  @Override
  public VmFunction extract() {
    return extract;
  }

  /// Implements our [#extract] function.
  private void extractMethod(TState tstate, Object[] args) throws BuiltinException {
    Value arg = (Value) args[0];
    if (baseType.isArray()) {
      Err.INVALID_ARGUMENT.unless(arg.isArrayOfLength(baseType.size()));
    } else if (baseType instanceof StructType compound) {
      Err.INVALID_ARGUMENT.unless(compound.matches(arg));
    } else {
      Err.INVALID_ARGUMENT.unless(arg.isa(baseType));
    }
    tstate.setResultsFromElements(baseType.size(), arg);
  }

  @Override
  public VmExpr make(Vm.Expr... elements) {
    Preconditions.checkArgument(elements.length == baseType.size());
    if (Arrays.stream(elements).allMatch(e -> e instanceof VmExpr.Constant)) {
      return VmExpr.Constant.of(baseType.uncountedOf(i -> ((VmExpr.Constant) elements[i]).value));
    } else {
      return new VmExpr.Compound(
          baseType, Arrays.copyOf(elements, elements.length, VmExpr[].class));
    }
  }

  @Override
  public Vm.Value make(Vm.ResourceTracker allocator, Vm.Value... elements) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Vm.Expr asLambdaExpr() {
    return asLambdaExpr;
  }

  /// A singleton BaseType that can be passed as the first argument to `at()` to create values of
  /// the given BaseType.
  private static class AsLambdaExpr extends Core.CoreLambda {
    final BaseType resultType;

    AsLambdaExpr(BaseType resultType) {
      super(0);
      assert resultType.isCompositional();
      this.resultType = resultType;
    }

    @Override
    void at(TState tstate, Value lambda, @RC.In Value arg) throws BuiltinException {
      int size = resultType.size();
      Value result;
      if (size == 1) {
        result = tstate.compound(resultType, arg);
      } else if (resultType instanceof Core.FixedArrayType && arg.baseType() == resultType) {
        result = arg;
      } else {
        Err.INVALID_ARGUMENT.unless(arg.isArrayOfLength(size));
        result = CompoundValue.of(tstate, resultType, arg::element);
        tstate.dropValue(arg);
      }
      tstate.setResult(result);
    }

    @Override
    public String toString() {
      return String.format("`%s_`", resultType);
    }
  }
}
