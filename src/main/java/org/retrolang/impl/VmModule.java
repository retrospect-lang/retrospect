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

import com.google.common.collect.ImmutableMap;
import org.jspecify.annotations.Nullable;
import org.retrolang.Vm;
import org.retrolang.impl.Err.BuiltinException;

/// The primary function of a VmModule is to hold maps of its public types and functions.
///
/// A VmModule is created before any of its types or functions. After they have all been created,
/// the [#initialize] method is called; only then are the [#lookupType], [#lookupSingleton], and
/// [#lookupFunction] methods usable.
class VmModule implements Vm.Module {

  final String name;
  private ImmutableMap<String, VmType> types;
  private ImmutableMap<String, VmFunction> functions;

  private ModuleBuilder builder;

  VmModule(String name, ModuleBuilder builder) {
    this.name = name;
    this.builder = builder;
  }

  @Override
  public VmType lookupType(String name) {
    return types.get(name);
  }

  @Override
  public @Nullable Singleton lookupSingleton(String name) {
    // We don't need a separate map for singletons; we just look it up in the type table and see
    // if we find a singleton type.
    VmType type = types.get(name);
    if (type instanceof BaseType.AsType) {
      BaseType baseType = ((BaseType.AsType) type).baseType;
      if (baseType.isSingleton()) {
        return baseType.asValue();
      }
    }
    return null;
  }

  @Override
  public Vm.Function lookupFunction(String name, int numArgs) {
    return functions.get(VmFunction.key(name, numArgs));
  }

  /// Should be called exactly once on each VmModule.
  void initialize(ImmutableMap<String, VmType> types, ImmutableMap<String, VmFunction> functions) {
    assert this.types == null;
    this.types = types;
    this.functions = functions;
    this.builder = null;
  }

  /// If this module is still under construction, returns its ModuleBuilder; otherwise returns null.
  ModuleBuilder builder() {
    return builder;
  }

  /// If the given BaseType is a simple compound defined by this module, returns the corresponding
  /// VmCompound; otherwise returns null.
  private VmCompound isMyCompound(BaseType baseType) {
    if (baseType instanceof ModuleBuilder.Compound compound
        && compound.asType.module == this
        && compound.isSimple()) {
      return compound.vmCompound;
    }
    return null;
  }

  /// The method that mplements the "`_`" suffix operator for this module.
  ///
  /// (From language\_reference.md#indexing: "\`_\` is a postfix operator that unwraps a compound
  /// value... It can only be applied to values whose type is a compound defined in the current
  /// package.")
  ///
  /// This is a named class so that it can be recognized by [LoopAnalyzer].
  static class UnCompound implements VmFunction.Simple.SimpleMethodImpl {
    final VmModule module;

    UnCompound(VmModule module) {
      this.module = module;
    }

    /// Returns true if this method is applicable to values with the given baseType.
    boolean accepts(BaseType baseType) {
      return module.isMyCompound(baseType) != null;
    }

    @Override
    public void executeSimpleMethod(TState tstate, Object[] args) throws BuiltinException {
      ((Value) args[0])
          .withKnownBaseTypeExcept(
              tstate,
              input -> {
                Err.CANT_UNCOMPOUND.unless(accepts(input.baseType()));
                tstate.setResult(input.element(0));
              });
    }
  }

  /// The method that implements the "`_`" suffix operator when used on the left-hand side of an
  /// `=`.
  void updateCompoundMethod(TState tstate, Object[] args) throws BuiltinException {
    ((Value) args[0])
        .withKnownBaseTypeExcept(
            tstate,
            input -> {
              VmCompound vmCompound = isMyCompound(input.baseType());
              Err.CANT_UNCOMPOUND.when(vmCompound == null);
              tstate.setResults(input.element(0), vmCompound.asLambdaExpr);
            });
  }
}
