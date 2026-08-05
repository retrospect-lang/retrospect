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

import java.util.function.Function;
import org.retrolang.impl.TemplateBuilder.TestOption;
import org.retrolang.impl.TemplateBuilder.VarAllocator;

/// A TProperty identifies some aspect of how a Template or TemplateBuilder represents values, such
/// as "are numeric values represented as doubles?" ([#COERCES_TO_FLOAT]) or "what FrameLayout (if
/// any) is used for array values?" ([#ARRAY_LAYOUT]).
///
/// Conceptually a TProperty is just a function from TemplateBuilder to some type T, but we require
/// that they be defined here to ensure that they are safe to call (e.g. they won't mutate the
/// TemplateBuilder passed to them).
public final class TProperty<T> {

  final Function<TemplateBuilder, T> fn;

  /// TProperty implementations must be defined in this file.
  private TProperty(Function<TemplateBuilder, T> fn) {
    this.fn = fn;
  }

  /// A TProperty that returns true if this result is never used by the caller.
  public static final TProperty<Boolean> IS_DISCARDED = new TProperty<>(t -> false);

  /// A TProperty that returns true if numeric values are represented as doubles.
  public static final TProperty<Boolean> COERCES_TO_FLOAT =
      new TProperty<>(TemplateBuilder::coercesToFloat);

  /// A TProperty that returns the FrameLayout that will be used for array values, or null if none
  /// has been chosen.
  public static final TProperty<FrameLayout> ARRAY_LAYOUT = layout(BaseType.SORT_ORDER_ARRAY);

  /// A TProperty that returns the FrameLayout that will be used for array values, or a
  /// FixedArrayType or null.
  public static final TProperty<Object> ARRAY_LAYOUT_OR_BASE_TYPE =
      layoutOrBaseType(BaseType.SORT_ORDER_ARRAY);

  /// A TProperty that returns the FrameLayout that will be used for values with the given sort
  /// order, or null if none has been chosen.
  public static TProperty<FrameLayout> layout(long sortOrder) {
    return new TProperty<>(t -> t.layout(sortOrder));
  }

  /// If a FrameLayout has been chosen for values with the given sort order, returns it. Otherwise
  /// if a compound is being used, returns its base type. Otherwise returns null.
  public static TProperty<Object> layoutOrBaseType(long sortOrder) {
    return new TProperty<>(
        t -> {
          if (t instanceof TemplateBuilder.UnionBase union) {
            int i = union.indexOf(sortOrder);
            if (i < 0) {
              return null;
            }
            t = union.choiceBuilder(i);
          } else if (t == Template.EMPTY) {
            return null;
          }
          FrameLayout layout = t.layout(sortOrder);
          if (layout != null) {
            return layout;
          }
          BaseType baseType = t.baseType();
          return (baseType.sortOrder == sortOrder) ? baseType : null;
        });
  }

  /// A TProperty that returns true if all of the known possible values are contained in the given
  /// Template.
  public static TProperty<Boolean> isSubsetOf(Template other, TestOption option) {
    return new TProperty<>(t -> t.isSubsetOf(other.toBuilder(), option));
  }

  /// A TProperty that returns true if all of the given Template's possible values are included.
  public static TProperty<Boolean> isSupersetOf(Template other, TestOption option) {
    return new TProperty<>(t -> other.toBuilder().isSubsetOf(t, option));
  }

  /// A TProperty that returns a new Template built with the given VarAllocator.
  public static TProperty<Template> build(VarAllocator allocator) {
    return new TProperty<>(t -> t.build(allocator));
  }

  /// Returns a new TProperty that returns the result of this TProperty when applied to the
  /// specified element of values with the given baseType.
  ///
  /// For example, `COERCES_TO_FLOAT.elementOf(LOOP_EXIT, 0)` tests whether values contained in a
  /// LoopExit compound are represented as doubles.
  public TProperty<T> elementOf(BaseType baseType, int index) {
    // It's not possible for only part of a value to be discarded.
    assert baseType.size() > index && this != IS_DISCARDED;
    Function<TemplateBuilder, T> inner = fn;
    return new TProperty<>(t -> inner.apply(t.builderForElement(baseType, index)));
  }
}
