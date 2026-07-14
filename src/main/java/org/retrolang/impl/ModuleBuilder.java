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
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import org.retrolang.Vm;
import org.retrolang.util.Bits;
import org.retrolang.util.StringUtil;

/** Implements Vm.ModuleBuilder. */
class ModuleBuilder implements Vm.ModuleBuilder {

  /**
   * The module being built.
   *
   * <p>Note that the ModuleBuilder will be dropped after it has been built, so objects that will
   * live on as part of the Module (such as types and functions) should refer to the Module
   * (available from this field), not the ModuleBuilder.
   */
  final VmModule module;

  /**
   * Any public types that have been constructed by {@link #newSingleton}, {@link #newCompoundType},
   * {@link #newUnionType}, or {@link #newTypeFromUnion}, keyed by their name.
   */
  private Map<String, VmType> types = new HashMap<>();

  /**
   * All types (public or private) that have been constructed by {@link #newSingleton}, {@link
   * #newCompoundType}, {@link #newUnionType}, or {@link #newTypeFromUnion}.
   */
  private List<VmType> allTypes = new ArrayList<>();

  /**
   * Any public functions that have been constructed by {@link #newFunction}, with keys created by
   * {@link VmFunction#key}.
   */
  private Map<String, VmFunction> functions = new HashMap<>();

  /** All functions (public or private) that have been constructed by {@link #newFunction}. */
  private List<VmFunction.General> allFunctions = new ArrayList<>();

  /** The function that implements the "_" suffix operator for this module. */
  private final VmFunction unCompound;

  /**
   * The function that implements the "_" suffix operator when used on the left-hand side of an "=".
   */
  private final VmFunction updateCompound;

  /** Creates a new ModuleBuilder and the corresponding VmModule. */
  ModuleBuilder(String name) {
    this.module = new VmModule(name, this);
    this.types = new HashMap<>();
    this.allTypes = new ArrayList<>();
    this.functions = new HashMap<>();
    this.allFunctions = new ArrayList<>();
    this.unCompound = new VmFunction.Simple("_", 1, module::unCompoundMethod, "compound");
    this.updateCompound =
        new VmFunction.Simple("_update", 2, module::updateCompoundMethod, "compound");
  }

  /**
   * Creates a stub ModuleBuilder for the Core module. This ModuleBuilder has no state and none of
   * its methods work; the actual module initialization is handled by the static initializers of
   * {@link Core}.
   */
  ModuleBuilder() {
    this.module = new VmModule("Core", this);
    this.unCompound = null;
    this.updateCompound = null;
  }

  @Override
  public synchronized Singleton newSingleton(String name, Vm.Access access, Vm.Type... supers) {
    checkNotBuilt();
    checkAccess(access, false);
    checkSupers(supers);
    BaseType.Named baseType = new BaseType.Named(module, name, 0, supers);
    addType(name, baseType.asType, access);
    return baseType.asValue();
  }

  @Override
  public synchronized VmCompound newCompoundType(
      String name, String[] elementNames, Vm.Access access, Vm.Type... supers) {
    checkNotBuilt();
    checkAccess(access, false);
    checkSupers(supers);
    Compound compound = new Compound(module, name, elementNames, supers);
    addType(name, compound.asType, access);
    return compound.vmCompound;
  }

  @Override
  public synchronized VmType.Union newUnionType(String name, Vm.Access access, Vm.Type... supers) {
    checkNotBuilt();
    checkAccess(access, true);
    checkSupers(supers);
    VmType.Union result = new VmType.Union(module, name, access == Vm.Access.OPEN, supers);
    addType(name, result, access);
    return result;
  }

  @Override
  public synchronized VmType.Union newTypeFromUnion(
      String name, Vm.Access access, Vm.Type... subtypes) {
    checkNotBuilt();
    checkAccess(access, true);
    VmType.Union result =
        VmType.Union.withSubTypes(module, name, access == Vm.Access.OPEN, subtypes);
    addType(name, result, access);
    return result;
  }

  @Override
  public synchronized VmFunction.General newFunction(
      String name, int numArgs, IntPredicate argIsInout, boolean hasResult, Vm.Access access) {
    Preconditions.checkArgument(numArgs >= 0);
    checkNotBuilt();
    checkAccess(access, true);
    Bits inout = Bits.fromPredicate(numArgs - 1, argIsInout);
    VmFunction.General result =
        new VmFunction.General(
            module,
            name,
            numArgs,
            inout,
            (hasResult ? 1 : 0) + inout.count(),
            access == Vm.Access.OPEN);
    if (access != Vm.Access.PRIVATE) {
      String key = VmFunction.key(name, numArgs);
      Preconditions.checkArgument(
          functions.putIfAbsent(key, result) == null,
          "There is already a function named '%s'",
          key);
    }
    allFunctions.add(result);
    return result;
  }

  @Override
  public Vm.InstructionBlock newInstructionBlock(int numArgs, int numResults, Object source) {
    return new VmInstructionBlock(this, numArgs, numResults, String.valueOf(source));
  }

  @Override
  public VmFunction unCompound() {
    return unCompound;
  }

  @Override
  public Vm.Function updateCompound() {
    return updateCompound;
  }

  @Override
  public synchronized void build() {
    checkNotBuilt();
    // Finalize functions first, since doing so may attach methods to some of our types.
    allFunctions.forEach(VmFunction.General::finalizeMethods);
    allTypes.forEach(VmType::finalizeType);
    this.allTypes = null;
    this.allFunctions = null;
    ImmutableMap<String, VmType> types = ImmutableMap.copyOf(this.types);
    ImmutableMap<String, VmFunction> functions = ImmutableMap.copyOf(this.functions);
    this.types = null;
    this.functions = null;
    module.initialize(types, functions);
  }

  /**
   * Adds a new method. If this is a method for a function not created by this ModuleBuilder, the
   * method must have a MethodPredicate that restricts at least one argument to a type created by
   * this ModuleBuilder.
   */
  synchronized void addMethod(VmMethod method) {
    checkNotBuilt();
    VmFunction.General fn = (VmFunction.General) method.function;
    if (fn.module != module) {
      Preconditions.checkArgument(
          fn.canAttachToTypes(method, module),
          "Cannot define a method for %s with predicate %s",
          fn,
          method.predicate);
    } else {
      fn.addMethod(method);
    }
  }

  private void checkNotBuilt() {
    Preconditions.checkArgument(types != null, "Module is already built");
  }

  private void checkAccess(Vm.Access access, boolean mayBeOpen) {
    Preconditions.checkArgument(
        access != null && (mayBeOpen || access != Vm.Access.OPEN), "Access may not be %s", access);
  }

  private void addType(String name, VmType type, Vm.Access access) {
    if (access != Vm.Access.PRIVATE) {
      Preconditions.checkArgument(
          types.putIfAbsent(name, type) == null, "There is already a type named '%s'", name);
    }
    allTypes.add(type);
  }

  private void checkSupers(Vm.Type... supers) {
    for (Vm.Type t : supers) {
      Preconditions.checkArgument(t instanceof VmType.Union, "'%s' is not a union", t);
      VmType.Union u = (VmType.Union) t;
      Preconditions.checkArgument(u.open || u.module == module, "'%s' is not an open union", t);
    }
  }

  /** A BaseType created by {@link #newCompoundType}. */
  static class Compound extends BaseType.Named {
    private final String[] elementNames;
    final VmCompound vmCompound;

    Compound(VmModule module, String name, String[] elementNames, Vm.Type[] superTypes) {
      super(module, name, elementNames == null ? 1 : elementNames.length, superTypes);
      this.elementNames = elementNames;
      vmCompound = new VmCompound(this);
    }

    /**
     * Returns true if {@link #elementNames} is null, which includes all non-built-in compound
     * types.
     */
    boolean isSimple() {
      return elementNames == null;
    }

    @Override
    public String toString(IntFunction<Object> elements) {
      if (isSimple()) {
        return super.toString(elements);
      } else {
        return StringUtil.joinElements(
            this + "⸨", "⸩", size(), i -> elementNames[i] + "=" + elements.apply(i));
      }
    }
  }
}
