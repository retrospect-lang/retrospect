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

package org.retrolang.impl.core;

import static org.retrolang.impl.Value.addRef;

import java.util.Arrays;
import org.retrolang.impl.BaseType;
import org.retrolang.impl.Condition;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.RefCounted;
import org.retrolang.impl.StructType;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.ValueUtil;

/// Core methods on Structs.
public class StructCore {
  /// `private compound StructAccessor is Lambda`
  ///
  /// Elements `struct`, `eKind`. The `at()` method does not check the validity of its argument!
  /// Intended only for internal use; should not be generally accessible.
  @Core.Private
  static final BaseType.Named STRUCT_ACCESSOR = Core.newBaseType("StructAccessor", 2, Core.LAMBDA);

  /// `private compound StructUpdater is Lambda`
  ///
  /// Elements are `struct`, `index`.
  @Core.Private
  static final BaseType.Named STRUCT_UPDATER = Core.newBaseType("StructUpdater", 2, Core.LAMBDA);

  /// `method at(Struct struct, String key) = ...`
  @Core.Method("at(Struct|StructKeys, String)")
  static Value atStruct(TState tstate, Value struct, Value key) throws BuiltinException {
    BaseType baseType = struct.baseType();
    StructType structType = StructType.from(baseType);
    Value index = StructType.keyToIndex.call(tstate, structType, key);
    Condition indexValid = Condition.numericLessOrEq(NumValue.ZERO, index);
    if (structType == baseType) {
      Err.INVALID_ARGUMENT.unless(indexValid);
      return ValueUtil.element(tstate, struct, index, 0);
    } else {
      // Must be a StructKeys
      return indexValid.choose(() -> addRef(key), () -> Core.ABSENT);
    }
  }

  /// ```
  /// method iterator(Struct struct, EnumerationKind eKind, Loop loop=, initialState=) {
  ///   lambda = StructAccessor_({struct, eKind})
  ///   loop = transformLoop(lambda, loop)
  ///   return SimpleRangeIterator(size(struct))
  /// }
  /// ```
  @Core.Method("iterator(Struct|StructKeys, EnumerationKind, Loop, _)")
  static void iteratorStruct(
      TState tstate,
      @RC.In Value struct,
      @RC.Singleton Value eKind,
      @RC.In Value loop,
      @RC.In Value initialState) {
    Value lambda = tstate.compound(STRUCT_ACCESSOR, struct, eKind);
    loop = tstate.compound(LoopCore.TRANSFORMED_LOOP, lambda, loop);
    StructType structType = StructType.from(struct.baseType());
    Value size = NumValue.of(structType.size(), tstate);
    Value iterator = tstate.compound(RangeCore.SIMPLE_RANGE_ITERATOR, NumValue.NEGATIVE_ONE, size);
    tstate.setResults(iterator, loop, initialState);
  }

  /// ```
  /// method at(StructAccessor accessor, i) {
  ///   v = accessor_.struct @ i
  ///   if accessor_.eKind is EnumerateValues
  ///       or (v is Absent and accessor_.eKind is EnumerateWithKeys) {
  ///     return v
  ///   } else {
  ///     return [keys @ i, v]
  ///   }
  /// ```
  @Core.Method("at(StructAccessor, _)")
  static Value atStructAccessor(TState tstate, Value accessor, Value index)
      throws BuiltinException {
    Value struct = accessor.peekElement(0);
    Value eKind = accessor.peekElement(1);
    BaseType baseType = struct.baseType();
    StructType structType = StructType.from(baseType);
    if (structType == baseType) {
      Value v = ValueUtil.element(tstate, struct, index, 0);
      return eKind
          .is(LoopCore.ENUMERATE_VALUES)
          .or(v.is(Core.ABSENT).and(eKind.is(LoopCore.ENUMERATE_WITH_KEYS)))
          .choose(
              () -> v,
              () -> {
                Value key = StructType.indexToKey.call(tstate, structType, index);
                return tstate.arrayValue(key, v);
              });
    } else {
      Value v = StructType.indexToKey.call(tstate, structType, index);
      return eKind.is(LoopCore.ENUMERATE_VALUES).choose(() -> v, () -> tstate.arrayValue(v, v));
    }
  }

  /// `method replaceElement(Struct struct, String key, v) = ...`
  @Core.Method("replaceElement(Struct, String, _)")
  static Value replaceElementStruct(TState tstate, @RC.In Value struct, Value key, @RC.In Value v)
      throws BuiltinException {
    StructType structType = (StructType) struct.baseType();
    Value index = StructType.keyToIndex.call(tstate, structType, key);
    Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, index));
    return ValueUtil.replaceElement(tstate, struct, index, 0, v);
  }

  /// `method startUpdate(Struct struct, String key) = ...`
  @Core.Method("startUpdate(Struct, String)")
  static void startUpdateStruct(TState tstate, @RC.In Value struct, Value key)
      throws BuiltinException {
    StructType structType = (StructType) struct.baseType();
    Value index = StructType.keyToIndex.call(tstate, structType, key);
    Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, index));
    // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
    Value element = ValueUtil.element(tstate, struct, index, 0);
    struct = ValueUtil.replaceElement(tstate, struct, index, 0, Core.TO_BE_SET);
    tstate.setResults(element, tstate.compound(STRUCT_UPDATER, struct, index));
  }

  /// `method at(StructUpdater updater, newElement) = ...`
  @Core.Method("at(StructUpdater, _)")
  static Value atStructUpdater(TState tstate, @RC.In Value updater, @RC.In Value v) {
    Value struct = updater.element(0);
    Value index = updater.peekElement(1);
    tstate.dropValue(updater);
    return ValueUtil.replaceElement(tstate, struct, index, 0, v);
  }

  /// `method keys(Struct updater) = ...`
  @Core.Method("keys(Struct|StructKeys)")
  static Value keysStruct(Value struct) {
    return StructType.from(struct.baseType()).keySet;
  }

  /// `method new(StructKeys keys, initialValue) = ...`
  @Core.Method("new(StructKeys, _)")
  static Value newStruct(TState tstate, Value keys, Value initial) {
    StructType structType = StructType.from(keys.baseType());
    int size = structType.size();
    if (size == 0) {
      return structType.asValue();
    }
    Object[] elements = tstate.allocObjectArray(size);
    Arrays.fill(elements, 0, size, initial);
    RefCounted.addRef(initial, size);
    return tstate.asCompoundValue(structType, elements);
  }

  private StructCore() {}
}
