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
import org.retrolang.code.CodeValue;
import org.retrolang.code.Op;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.core.CollectionCore;
import org.retrolang.util.SizeOf;
import org.retrolang.util.StringUtil;

/**
 * An implementation of Value for strings.
 *
 * <p>For now this is a minimal placeholder; I expect to replace it with something more complete
 * later.
 */
public class StringValue extends RefCounted implements Value {

  private static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + SizeOf.PTR);

  public final String value;

  /**
   * Assumes that value is unshared, and will be released when we are dropped. Will overcount if
   * value is shared, but that's probably harmless.
   */
  public StringValue(Allocator allocator, String value) {
    int nChars = value.length();
    // To keep this fast and simple we want to use length() and charAt() rather than
    // codePointCount() and offsetByCodePoints(), but we can only do that if the string contains
    // no surrogate pairs.
    Preconditions.checkArgument(
        value.codePointCount(0, nChars) == nChars,
        "Code points >= 2^16 are unsupported (%s)",
        value);
    this.value = value;
    allocator.recordAlloc(this, OBJ_SIZE + SizeOf.string(value));
  }

  /** Creates an uncounted StringValue. */
  public static StringValue uncounted(String value) {
    return new StringValue(Allocator.UNCOUNTED, value);
  }

  @Override
  public BaseType baseType() {
    return Core.STRING;
  }

  @Override
  long visitRefs(RefVisitor visitor) {
    return OBJ_SIZE + SizeOf.string(value);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof StringValue sv && value.equals(sv.value);
  }

  @Override
  public int hashCode() {
    // Since all StringValues are immutable (counted or not) they can all be hashable.
    return value.hashCode();
  }

  @Override
  public String toString() {
    return StringUtil.escape(value);
  }

  /** {@code open function codePoints(string)} */
  @Core.Public
  static final VmFunctionBuilder codePoints = VmFunctionBuilder.create("codePoints", 1).isOpen();

  /**
   * {@code private compound ToCodePoint is Lambda}
   *
   * <p>Input is an index; output the corresponding code point from the wrapped String.
   */
  @Core.Private
  static final BaseType.Named TO_CODE_POINT = Core.newBaseType("ToCodePoint", 1, Core.LAMBDA);

  /** {@code method codePoints(String s) = 1..size(s) | ToCodePoint_(s)} */
  @Core.Method("codePoints(String)")
  static Value codePoints(TState tstate, @RC.In Value s) {
    Value length;
    if (s instanceof StringValue sv) {
      int len = sv.length();
      length = NumValue.of(len, tstate);
    } else {
      CodeGen codeGen = tstate.codeGen();
      length = codeGen.intToValue(LENGTH_OP.result(codeGen.asCodeValue(s)));
    }
    return Condition.numericEq(length, NumValue.ZERO)
        .choose(
            () -> {
              tstate.dropValue(s);
              return Core.EMPTY_ARRAY;
            },
            () -> {
              Value range = tstate.compound(Core.RANGE, NumValue.ONE, length);
              Value lambda = tstate.compound(TO_CODE_POINT, s);
              return tstate.compound(CollectionCore.TRANSFORMED_MATRIX, range, lambda);
            });
  }

  /** {@code method at(ToCodePoint cp, Number index) = ...} */
  @Core.Method("at(ToCodePoint, Number)")
  static Value atToCodePoint(TState tstate, Value cp, Value index) throws BuiltinException {
    Value s = cp.peekElement(0);
    if (!(cp instanceof RValue || index instanceof RValue)) {
      int result = ((StringValue) s).codePoint(NumValue.asIntOrMinusOne(index));
      Err.INVALID_ARGUMENT.unless(result >= 0);
      return NumValue.of(result, tstate);
    } else {
      CodeGen codeGen = tstate.codeGen();
      Value result =
          codeGen.intToValue(
              CODE_POINT_OP.result(codeGen.asCodeValue(s), codeGen.verifyInt(index)));
      codeGen.escapeWhen(Condition.numericLessThan(result, NumValue.ZERO));
      return result;
    }
  }

  @Core.Method("equal(String, String)")
  static Value equalStrings(Value x, Value y) {
    return Condition.equal(x, y).asValue();
  }

  @Core.Method("concat(String, String)")
  static Value concatStrings(TState tstate, Value x, Value y) {
    if (!(x instanceof RValue || y instanceof RValue)) {
      return concat(tstate, (StringValue) x, (StringValue) y);
    } else {
      CodeGen codeGen = tstate.codeGen();
      CodeValue result =
          CONCAT_OP.result(
              codeGen.tstateRegister(), codeGen.asCodeValue(x), codeGen.asCodeValue(y));
      return codeGen.toValue(codeGen.materialize(result, StringValue.class), Core.STRING);
    }
  }

  static final Op LENGTH_OP = RcOp.forRcMethod(StringValue.class, "length").build();

  public int length() {
    return value.length();
  }

  static final Op CODE_POINT_OP =
      RcOp.forRcMethod(StringValue.class, "codePoint", int.class).build();

  public int codePoint(int index) {
    if (index > 0 && index <= value.length()) {
      return value.charAt(index - 1);
    } else {
      return -1;
    }
  }

  static final Op CONCAT_OP =
      RcOp.forRcMethod(
              StringValue.class, "concat", TState.class, StringValue.class, StringValue.class)
          .build();

  @RC.Out
  public static StringValue concat(TState tstate, StringValue x, StringValue y) {
    if (x.length() == 0) {
      y.addRef();
      return y;
    } else if (y.value.length() == 0) {
      x.addRef();
      return x;
    }
    return new StringValue(tstate, x.value.concat(y.value));
  }
}
