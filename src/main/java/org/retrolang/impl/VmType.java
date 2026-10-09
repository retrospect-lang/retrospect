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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.retrolang.Vm;

/// The implementation of Vm.Type; subclasses are responsible for defining [#contains].
///
/// This class has four subtypes: [BaseType.AsType], [Union], [Core#INTEGER], and [Core#ARRAY].
public abstract class VmType implements Vm.Type {

  final VmModule module;
  final String name;

  /// If this is a Union, `superTypes` includes this; otherwise it does not.
  final SuperTypes superTypes;

  /// A lambda value that returns True if the arg has this type
  final Singleton testLambda;

  /// Methods attached to this type. The key uniquely identifies the function + argument position.
  ///
  /// Note that methods may be saved with the VmFunction, or attached to one or more of their
  /// argument types; see [ModuleBuilder#addMethod] for details.
  ///
  /// Protected by locking module.builder; once that is null this field is immutable (and its value
  /// is either null or an ImmutableMap of ImmutableLists).
  private Map<VmFunction.ArgKey, List<VmMethod>> methods;

  VmType(VmModule module, String name, Vm.Type... superTypes) {
    this.module = module;
    this.name = name;
    this.superTypes = SuperTypes.construct((this instanceof Union u) ? u : null, superTypes);
    this.testLambda = new TestLambda(this).asValue();
  }

  /// Returns true if this type contains all values in the given type.
  ///
  /// More precisely:
  ///
  /// - If this type corresponds to a BaseType, returns true if the argument is the same type as
  ///   this.
  /// - If this type corresponds to a union declared with supertypes ("type X is ..."), returns true
  ///   if type was declared to have this as a supertype (directly or indirectly).
  /// - If this type corresponds to a union declared with subtypes ("type X contains ..."), returns
  ///   true if type was declared to have some type T as a supertype, and this was declared to have
  ///   T as a subtype.
  public abstract boolean contains(BaseType type);

  @Override
  public MethodPredicate argType(int argIndex, boolean positive) {
    Preconditions.checkArgument(this != Core.INTEGER);
    return new MethodPredicate.Simple(argIndex, this, positive);
  }

  @Override
  public Vm.Expr testLambda() {
    return testLambda;
  }

  @Override
  public String toString() {
    return name;
  }

  @Nullable ImmutableList<VmMethod> getMethods(VmFunction.ArgKey key) {
    return (methods == null) ? null : (ImmutableList<VmMethod>) methods.get(key);
  }

  /// Attach the given method to this type, for the function/argIndex represented by the given key.
  void addMethod(VmFunction.ArgKey key, VmMethod method) {
    assert Thread.holdsLock(module.builder());
    assert key != null;
    if (methods == null) {
      methods = new HashMap<>();
    }
    List<VmMethod> list = methods.computeIfAbsent(key, k -> new ArrayList<>());
    if (!list.contains(method)) {
      list.add(method);
    }
  }

  /// If any methods have been attached to this type, copy the [#methods] map into an ImmutableMap
  /// of ImmutableLists. After this has been called, no additional methods can be attached to this
  /// type.
  void finalizeType() {
    assert Thread.holdsLock(module.builder());
    if (methods != null) {
      assert !(methods instanceof ImmutableMap);
      methods = ImmutableMap.copyOf(Maps.transformValues(methods, ImmutableList::copyOf));
    }
  }

  /// A singleton BaseType that can be passed as the first argument to `at()` to test for values of
  /// the given VmType.
  private static class TestLambda extends Core.CoreLambda {
    final VmType vmType;

    TestLambda(VmType vmType) {
      super(0);
      this.vmType = vmType;
    }

    @Override
    void at(TState tstate, Value lambda, @RC.In Value arg) {
      Value result = arg.isa(vmType).asValue();
      tstate.dropValue(arg);
      tstate.setResult(result);
    }

    @Override
    public String toString() {
      return String.format("`is%s`", vmType);
    }
  }

  /// A set of Unions closed under the supertype relation; i.e. if a SuperTypes contains x and y is
  /// declared to be a supertype of x, then that SuperTypes also contains y.
  static class SuperTypes {
    static final SuperTypes EMPTY = new SuperTypes(ImmutableSet.of());

    final ImmutableSet<Union> asSet;

    private SuperTypes(ImmutableSet<Union> asSet) {
      this.asSet = asSet;
    }

    /// Returns a SuperTypes that contains `self` (if it is non-null) and each of the given super
    /// types, and all of their direct and indirect super types.
    ///
    /// The superTypes must all be instances of VmType.Union.
    static SuperTypes construct(Union self, Vm.Type... superTypes) {
      // Optimize some common simple cases.
      if (self == null) {
        if (superTypes.length == 0) {
          return EMPTY;
        } else if (superTypes.length == 1) {
          return ((Union) superTypes[0]).superTypes;
        }
      } else if (superTypes.length == 0) {
        return new SuperTypes(ImmutableSet.of(self));
      }
      // The full, general implementation.
      ImmutableSet.Builder<Union> builder = ImmutableSet.builder();
      for (Vm.Type superType : superTypes) {
        builder.addAll(((Union) superType).superTypes.asSet);
      }
      if (self != null) {
        builder.add(self);
      }
      return new SuperTypes(builder.build());
    }
  }

  /// A subclass of VmType for types defined by the Retrospect "type" declaration. There are two
  /// flavors of this declaration:
  ///
  /// - "`type T is Super1, Super2, ...`" creates a Union with empty [#subTypes], whose
  ///   [#superTypes] contains T, the listed super types, and all of their direct and indirect
  ///   supertypes.
  /// - "`type T contains Sub1, Sub2, ...`" creates a Union whose [#subTypes] contains the listed
  ///   subtypes and their subtypes, and whose [#superTypes] contains only T.
  public static class Union extends VmType {
    final boolean open;

    /// Does not include this.
    final ImmutableSet<VmType> subTypes;

    /// Creates a new union type that with the given supertypes.
    Union(VmModule module, String name, boolean open, Vm.Type... superTypes) {
      super(module, name, superTypes);
      this.open = open;
      this.subTypes = ImmutableSet.of();
    }

    /// Creates a new union type that contains the given subtypes.
    private Union(VmModule module, String name, Vm.Type[] subTypes, boolean open) {
      super(module, name);
      this.open = open;
      ImmutableSet.Builder<VmType> builder = ImmutableSet.builder();
      for (Vm.Type subType : subTypes) {
        builder.add((VmType) subType);
        if (subType instanceof Union u) {
          builder.addAll(u.subTypes);
        } else {
          // Core.INTEGER is the only subclass of VmType that isn't a BaseType.AsType or Union.
          Preconditions.checkArgument(
              subType instanceof BaseType.AsType, "%s cannot be included in union", subType);
        }
      }
      this.subTypes = builder.build();
    }

    /// Creates a new union type that contains the given subtypes.
    static Union withSubTypes(VmModule module, String name, boolean open, Vm.Type... subTypes) {
      return new Union(module, name, subTypes, open);
    }

    @Override
    public boolean contains(BaseType baseType) {
      VmType start = baseType.vmType();
      ImmutableSet<Union> baseTypeSuperTypes = start.superTypes.asSet;
      if (baseTypeSuperTypes.contains(this) || subTypes.contains(start)) {
        return true;
      }
      // Return true if subTypes and baseTypeSuperTypes have a common element.
      // Make this more efficient by ordering the sets?
      if (baseTypeSuperTypes.size() < subTypes.size()) {
        return baseTypeSuperTypes.stream().anyMatch(subTypes::contains);
      } else {
        return subTypes.stream().anyMatch(baseTypeSuperTypes::contains);
      }
    }
  }
}
