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

/// An implementation of Value for strings.
///
/// For now this is a minimal placeholder; I expect to replace it with something more complete
/// later.
public class StringValue extends RefCounted implements Value {

  private static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + SizeOf.PTR);

  public static final StringValue EMPTY = new StringValue(Allocator.UNCOUNTED, "");

  public final String value;

  /// Assumes that value is unshared, and will be released when we are dropped. Will overcount if
  /// value is shared, but that's probably harmless.
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

  /// Creates an uncounted StringValue.
  public static StringValue uncounted(String value) {
    return new StringValue(Allocator.UNCOUNTED, value);
  }

  @Override
  public BaseType baseType() {
    return Core.STRING;
  }

  @Override
  protected long visitRefs(RefVisitor visitor) {
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

  /// `method size(String s) = ...`
  @Core.Method("size(String)")
  public static Value size(TState tstate, Value s) {
    if (s instanceof StringValue sv) {
      return NumValue.of(sv.length(), tstate);
    }
    CodeGen codeGen = tstate.codeGen();
    return codeGen.intToValue(LENGTH_OP.result(codeGen.asCodeValue(s)));
  }

  /// A Condition that is true if the length of `s` (a StringValue) is equal to `n` (an integer).
  public static Condition lengthIs(TState tstate, Value s, Value n) {
    if (s instanceof StringValue sv && n instanceof NumValue) {
      return Condition.of(NumValue.equals(n, sv.value.length()));
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue length = LENGTH_OP.result(codeGen.asCodeValue(s));
    return Condition.intEq(codeGen.asCodeValue(n), length);
  }

  /// `open function codePoints(string)`
  @Core.Public
  static final VmFunctionBuilder codePoints = VmFunctionBuilder.create("codePoints", 1).isOpen();

  /// `private compound ToCodePoint is Lambda`
  ///
  /// Input is an index; output the corresponding code point from the wrapped String.
  @Core.Private
  static final BaseType.Named TO_CODE_POINT = Core.newBaseType("ToCodePoint", 1, Core.LAMBDA);

  /// `method codePoints(String s) = 1..size(s) | ToCodePoint_(s)`
  @Core.Method("codePoints(String)")
  static Value codePoints(TState tstate, @RC.In Value s) {
    Value length = size(tstate, s);
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

  /// `method at(ToCodePoint cp, Number index) = ...`
  @Core.Method("at(ToCodePoint, Number)")
  static Value atToCodePoint(TState tstate, Value cp, Value index) throws BuiltinException {
    index = index.verifyInt(Err.INVALID_ARGUMENT);
    index = ValueUtil.subtractInts(tstate, index, NumValue.ONE);
    Value result = codePoint.call(tstate, cp.peekElement(0), index);
    tstate.dropValue(index);
    Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, result));
    return result.makeStorable(tstate);
  }

  /// `method at(String, Array) = ...`
  @Core.Method("at(String, Array)")
  static Value atStringRange(TState tstate, Value s, Value range) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(range.isArrayOfLength(1));
    range = range.peekElement(0);
    Err.INVALID_ARGUMENT.unless(range.isa(Core.RANGE));
    Value min = range.peekElement(0);
    min = min.is(Core.NONE).choose(NumValue.ONE, min);
    min = ValueUtil.subtractInts(tstate, min, NumValue.ONE);
    Value max = range.peekElement(1);
    max = max.is(Core.NONE).choose(NumValue.NEGATIVE_ONE, max);
    Value result = substring.call(tstate, s, min, max);
    tstate.dropValue(min);
    Err.INVALID_ARGUMENT.when(Condition.isNull(result));
    return result;
  }

  /// `function parseInt(string)`
  @Core.Public static final VmFunctionBuilder parseInt = VmFunctionBuilder.create("parseInt", 1);

  @Core.Method("parseInt(String)")
  public static Value parseInt(TState tstate, ResultsInfo results, Value s)
      throws BuiltinException {
    Value end = skipInt.call(tstate, s, NumValue.ZERO);
    Err.INVALID_ARGUMENT.unless(lengthIs(tstate, s, end));
    return parseInt(tstate, results, s, NumValue.ZERO, end);
  }

  /// The substring of `sv` from `start` (inclusive) to `end` (exclusive) must be an optional '+' or
  /// '-' followed by one or more digits; returns the corresponding number.
  @RC.Out
  public static Value parseInt(TState tstate, ResultsInfo results, Value sv, Value start, Value end)
      throws BuiltinException {
    if (!(sv instanceof RValue || start instanceof RValue || end instanceof RValue)) {
      int iStart = NumValue.asIntOrMinusOne(start);
      int iEnd = NumValue.asIntOrMinusOne(end);
      String s = ((StringValue) sv).value;
      // Very long strings of digits can be handled correctly by parseDouble, but not by parseLong.
      if ((iEnd - iStart) < 19) {
        long result = Long.parseLong(s, iStart, iEnd, 10);
        int iResult = (int) result;
        return (iResult == result)
            ? NumValue.of(iResult, tstate)
            : NumValue.of((double) result, tstate);
      }
      return NumValue.of(Double.parseDouble(s.substring(iStart, iEnd)), tstate);
    } else {
      CodeGen codeGen = tstate.codeGen();
      CodeValue startCv = codeGen.verifyInt(start);
      CodeValue endCv = codeGen.verifyInt(end);
      CodeValue sCv = codeGen.asCodeValue(sv);
      CodeValue result;
      if (results.result(TProperty.COERCES_TO_FLOAT)) {
        result = codeGen.materialize(PARSE_DOUBLE_OP.result(sCv, startCv, endCv), double.class);
      } else {
        result =
            codeGen.materializeCatchingArithmeticException(
                PARSE_INT_OP.result(sCv, startCv, endCv));
      }
      return codeGen.toValue(result);
    }
  }

  @Core.Method("equal(String, String)")
  static Value equalStrings(Value x, Value y) {
    return Condition.equal(x, y).asValue();
  }

  @Core.Method("concat(String, String)")
  static Value concatStrings(TState tstate, Value x, Value y) {
    return concat.call(tstate, x, y);
  }

  static final Op LENGTH_OP =
      RcOp.forRcMethod(StringValue.class, "length").withConstSimplifier().build();

  public int length() {
    return value.length();
  }

  public static final BuiltinMethod.SimpleRuntimeHelper codePoint =
      new BuiltinMethod.SimpleRuntimeHelper().canSimplifyConstants();

  /// Returns the code point at the given index, or -1 if `index` is invalid.
  public static int codePoint(StringValue s, int index) {
    if (index >= 0 && index < s.value.length()) {
      return s.value.charAt(index);
    } else {
      return -1;
    }
  }

  public static final BuiltinMethod.SimpleRuntimeHelper substring =
      new BuiltinMethod.SimpleRuntimeHelper().canSimplifyConstants();

  /// Returns the substring from `start` (inclusive) to `end` (exclusive). If `end` is -1, the end
  /// of the string is used.
  @RC.Out
  public static StringValue substring(TState tstate, StringValue s, int start, int end) {
    int size = s.value.length();
    if (end == -1) {
      end = size;
    }
    if (start == 0 && end == size) {
      s.addRef();
      return s;
    } else if (start < 0 || end > size || start > end) {
      return null;
    } else {
      return new StringValue(tstate, s.value.substring(start, end));
    }
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  public static final BuiltinMethod.SimpleRuntimeHelper skipInt =
      new BuiltinMethod.SimpleRuntimeHelper().canSimplifyConstants();

  /// If the characters beginning at `start` are an optional '+' or '-' followed by one or more
  /// digits, returns the position of the first non-digit following the digits; otherwise returns
  /// -1.
  public static int skipInt(StringValue s, int start) {
    if (start < 0) {
      return -1;
    }
    int pos = start;
    int size = s.value.length();
    int result = -1;
    while (pos < size) {
      char c = s.value.charAt(pos);
      if (isDigit(c)) {
        result = ++pos;
      } else if (pos == start && (c == '-' || c == '+')) {
        // A leading '+' or '-' is OK
        ++pos;
      } else {
        // Anything else means we're done
        break;
      }
    }
    return result;
  }

  static final Op PARSE_INT_OP =
      RcOp.forRcMethod(StringValue.class, "parseInt", int.class, int.class)
          .withConstSimplifier()
          .build();

  public int parseInt(int start, int end) throws ArithmeticException {
    try {
      return Integer.parseInt(value, start, end, 10);
    } catch (NumberFormatException e) {
      throw new ArithmeticException();
    }
  }

  static final Op PARSE_DOUBLE_OP =
      RcOp.forRcMethod(StringValue.class, "parseDouble", int.class, int.class)
          .withConstSimplifier()
          .build();

  public double parseDouble(int start, int end) {
    return Double.parseDouble(value.substring(start, end));
  }

  public static final BuiltinMethod.SimpleRuntimeHelper concat =
      new BuiltinMethod.SimpleRuntimeHelper().canSimplifyConstants();

  @RC.Out
  public static StringValue concat(TState tstate, StringValue x, StringValue y) {
    if (x.value.isEmpty()) {
      y.addRef();
      return y;
    } else if (y.value.isEmpty()) {
      x.addRef();
      return x;
    }
    return new StringValue(tstate, x.value.concat(y.value));
  }

  public static final BuiltinMethod.SimpleRuntimeHelper substringStartsWith =
      new BuiltinMethod.SimpleRuntimeHelper().canSimplifyConstants();

  /// True if `start` is a valid index in `s`, and the substring of `s` beginning at `start` starts
  /// with `sub`.
  public static boolean substringStartsWith(StringValue x, int n, StringValue y) {
    return x.value.startsWith(y.value, n);
  }

  public static final BuiltinMethod.SimpleRuntimeHelper containsCodePoint =
      new BuiltinMethod.SimpleRuntimeHelper().canSimplifyConstants();

  /// True if `s` contains the code point `cp`.
  public static boolean containsCodePoint(StringValue x, int cp) {
    return x.value.indexOf(cp) >= 0;
  }
}
