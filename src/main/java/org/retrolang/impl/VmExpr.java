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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import java.util.function.Consumer;
import org.retrolang.Vm;

/// A base class for implementations of Vm.Expr.
///
/// There are three subclasses: [Constant], [Local], and [Compound].
abstract class VmExpr implements Vm.Expr {

  /// Calls the given consumer with each Local in this VmExpr.
  ///
  /// The more obvious implementation would be an abstract method with implementations on each
  /// subclass, but this seems easier to read.
  void forEachLocal(Consumer<Local> consumer) {
    if (this instanceof Local local) {
      consumer.accept(local);
    } else if (this instanceof Compound compound) {
      for (VmExpr element : compound.elements) {
        element.forEachLocal(consumer);
      }
    }
  }

  /// A constant expression. Note that VmExprs aren't refcounted, so Constants can only be made from
  /// non-refcounted values. Also provides a trivial implementation of Vm.Value.
  ///
  /// [Singleton] is a subclass of Constant.
  static class Constant extends VmExpr implements Vm.Value {
    final Value value;

    private Constant(Value value) {
      assert value != null && !RefCounted.isRefCounted(value);
      this.value = value;
    }

    /// Used only by the Singleton subclass.
    Constant() {
      this.value = (Singleton) this;
    }

    /// Returns a Constant with the given value, which must not be refcounted.
    static Constant of(Value value) {
      return (value instanceof Singleton singleton) ? singleton : new Constant(value);
    }

    @Override
    public void close() {}

    @CanIgnoreReturnValue
    @Override
    public Vm.Value keep() {
      return this;
    }

    @Override
    public String toString() {
      return value.toString();
    }
  }

  /// A compound expression, composed of a BaseType (with positive size) and a VmExpr for each of
  /// its elements.
  static class Compound extends VmExpr {
    final BaseType baseType;
    private final VmExpr[] elements;

    Compound(BaseType baseType, VmExpr... elements) {
      assert elements.length == baseType.size();
      // Compound shouldn't be used if all of the elements are Constant -- in that case we prefer to
      // create a Constant.
      assert !Arrays.stream(elements).allMatch(e -> e instanceof Constant);
      this.baseType = baseType;
      this.elements = elements;
    }

    VmExpr element(int i) {
      return elements[i];
    }

    @Override
    public String toString() {
      return baseType.toString(i -> elements[i]);
    }
  }

  /// Returns a VmExpr for an array containing the given elements.
  static VmExpr array(Vm.Expr... elements) {
    return Core.FixedArrayType.withSize(elements.length).compound.make(elements);
  }
}
