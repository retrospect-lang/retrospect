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
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.retrolang.code.Op;

/** A BaseType for structs with a specific set of keys. */
public class StructType extends BaseType {

  public static final Op INDEX_OP =
      RcOp.forRcMethod(StructType.class, "index", Value.class).withConstSimplifier().build();

  public static final Op KEY_OP =
      RcOp.forRcMethod(StructType.class, "key", int.class).withConstSimplifier().build();

  @Core.Private
  static final VmType STRUCT_KEYS =
      new VmType(Core.CORE, "StructKeys", Core.COLLECTION) {
        @Override
        boolean contains(BaseType type) {
          return type instanceof StructKeys;
        }
      };

  /** Given the BaseType of a struct or structKeys, returns the StructType. */
  public static StructType from(BaseType baseType) {
    return baseType instanceof StructType st ? st : ((StructKeys) baseType).struct;
  }

  /** A Singleton for the empty struct, {@code {}}. */
  public static final StructType EMPTY = new StructType();

  final String name;

  public final ImmutableList<String> keys;

  public final ImmutableList<StringValue> keysAsStringValues;

  /** Maps key to index in {@link #keys}. */
  public final ImmutableMap<String, Integer> keyMap;

  /** * The value returned by keys(struct). */
  public final Value keySet;

  final VmCompound vmCompound;

  /** A StructType with the given keys, which must be distinct and sorted. */
  public StructType(ImmutableList<String> keys) {
    super(keys.size());
    this.keys = keys;
    this.name = keys.stream().collect(Collectors.joining(",", "{", "}"));
    ImmutableMap.Builder<String, Integer> keyMap = ImmutableMap.builder();
    ImmutableList.Builder<StringValue> keysAsStringValues = ImmutableList.builder();
    for (int i = 0; i < keys.size(); i++) {
      String key = keys.get(i);
      Preconditions.checkArgument(i == 0 || keys.get(i - 1).compareTo(key) < 0);
      keyMap.put(key, i);
      keysAsStringValues.add(new StringValue(Allocator.UNCOUNTED, key));
    }
    this.keyMap = keyMap.build();
    this.keysAsStringValues = keysAsStringValues.build();
    this.keySet = new StructKeys(this).asValue();
    this.vmCompound = new VmCompound(this);
  }

  /** A StructType with the given keys, which must be distinct and sorted. */
  public StructType(String... keys) {
    this(ImmutableList.copyOf(keys));
  }

  @Override
  VmType vmType() {
    return Core.STRUCT;
  }

  @Override
  String toString(IntFunction<Object> elements) {
    return IntStream.range(0, size())
        .mapToObj(i -> keys.get(i) + ": " + elements.apply(i))
        .collect(Collectors.joining(", ", "{", "}"));
  }

  /** True if the given value is a StructType with the same keys as this one. */
  public Condition matches(Value v) {
    if (v instanceof RValue rv) {
      return rv.typeTest(bt -> bt == this);
    } else {
      return Condition.of(v.baseType() == this);
    }
  }

  /**
   * If {@code key} is one of the keys of this compound, returns its index (in 0..size-1); otherwise
   * returns -1.
   */
  public int index(Value key) {
    assert !(key instanceof RValue);
    if (key instanceof StringValue s) {
      Integer result = keyMap.get(s.value);
      if (result != null) {
        return result;
      }
    }
    return -1;
  }

  public StringValue key(int index) {
    return keysAsStringValues.get(index);
  }

  @Override
  public String toString() {
    return name;
  }

  private static final class StructKeys extends BaseType {
    final StructType struct;

    StructKeys(StructType struct) {
      super(0);
      this.struct = struct;
    }

    @Override
    VmType vmType() {
      return STRUCT_KEYS;
    }

    @Override
    public String toString() {
      return "Keys" + struct.name;
    }
  }
}
