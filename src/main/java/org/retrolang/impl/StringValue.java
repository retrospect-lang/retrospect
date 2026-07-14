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

  /** {@code method size(String s) = ...} */
  @Core.Method("size(String)")
  public static Value size(TState tstate, Value s) {
    if (s instanceof StringValue sv) {
      return NumValue.of(sv.length(), tstate);
    }
    CodeGen codeGen = tstate.codeGen();
    return codeGen.intToValue(LENGTH_OP.result(codeGen.asCodeValue(s)));
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

  /** {@code method at(ToCodePoint cp, Number index) = ...} */
  @Core.Method("at(ToCodePoint, Number)")
  static Value atToCodePoint(TState tstate, Value cp, Value index) throws BuiltinException {
    Value result = codePoint(tstate, cp.peekElement(0), index, 1);
    Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, result));
    return result;
  }

  /**
   * Returns the code point of {@code s} identified by {@code index} (which may be zero-based or
   * one-based), or -1 if {@code index} is invalid.
   */
  @RC.Out
  public static Value codePoint(TState tstate, Value s, Value index, int firstIndex)
      throws BuiltinException {
    if (!(s instanceof RValue || index instanceof RValue)) {
      int result = ((StringValue) s).codePoint(NumValue.asIntOrMinusOne(index) - firstIndex);
      return NumValue.of(result, tstate);
    } else {
      CodeGen codeGen = tstate.codeGen();
      return codeGen.intToValue(
          CODE_POINT_OP.result(
              codeGen.asCodeValue(s), verifyAndAdjust(codeGen, index, firstIndex)));
    }
  }

  /** If {@code index} is an int, returns {@code index - firstIndex}; otherwise escapes. */
  private static CodeValue verifyAndAdjust(CodeGen codeGen, Value index, int firstIndex)
      throws BuiltinException {
    CodeValue result = codeGen.verifyInt(index);
    return (firstIndex == 0) ? result : Op.SUBTRACT_INTS.result(result, CodeValue.of(firstIndex));
  }

  /** {@code method at(String, Array) = ...} */
  @Core.Method("at(String, Array)")
  static Value atStringRange(TState tstate, Value s, Value range) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(range.isArrayOfLength(1));
    range = range.peekElement(0);
    Err.INVALID_ARGUMENT.unless(range.isa(Core.RANGE));
    Value min = range.peekElement(0);
    min = min.is(Core.NONE).choose(NumValue.ONE, min);
    Value max = range.peekElement(1);
    max = max.is(Core.NONE).choose(NumValue.NEGATIVE_ONE, max);
    return substring(tstate, s, min, max, 1);
  }

  /**
   * Returns the substring from {@code start - firstIndex} (inclusive) to {@code end} (exclusive).
   * If {@code end} is -1, the end of the string is used.
   *
   * <p>(Note that when {@code firstIndex} is 1, {@code end} is effectively inclusive since we do
   * not subtract {@code firstIndex} from it.)
   */
  @RC.Out
  public static Value substring(TState tstate, Value s, Value start, Value end, int firstIndex)
      throws BuiltinException {
    if (!(s instanceof RValue || start instanceof RValue || end instanceof RValue)) {
      Value result =
          ((StringValue) s)
              .substring(
                  tstate,
                  NumValue.asIntOrMinusOne(start) - firstIndex,
                  NumValue.asIntOrMinusOne(end));
      Err.INVALID_ARGUMENT.when(result == null);
      return result;
    } else {
      CodeGen codeGen = tstate.codeGen();
      CodeValue result =
          SUBSTRING_OP.result(
              codeGen.asCodeValue(s),
              codeGen.tstateRegister(),
              verifyAndAdjust(codeGen, start, firstIndex),
              codeGen.verifyInt(end));
      result = codeGen.materialize(result, StringValue.class);
      Err.INVALID_ARGUMENT.when(Condition.isNull(result));
      return codeGen.toValue(result, Core.STRING);
    }
  }

  /**
   * If the characters beginning at {@code start} are an optional '+' or '-' followed by one or more
   * digits, returns the position of the first non-digit following the digits; otherwise returns -1.
   */
  @RC.Out
  public static Value skipInt(TState tstate, Value s, Value start) {
    if (!(s instanceof RValue || start instanceof RValue)) {
      int result = ((StringValue) s).skipInt(NumValue.asIntOrMinusOne(start));
      return NumValue.of(result, tstate);
    } else {
      CodeGen codeGen = tstate.codeGen();
      CodeValue startCv;
      try {
        startCv = codeGen.verifyInt(start);
      } catch (BuiltinException e) {
        // Could just escape and return NumValue.ZERO, but I don't think this is possible
        throw new AssertionError();
      }
      return codeGen.intToValue(SKIP_INT_OP.result(codeGen.asCodeValue(s), startCv));
    }
  }

  /** {@code function parseInt(string)} */
  @Core.Public static final VmFunctionBuilder parseInt = VmFunctionBuilder.create("parseInt", 1);

  @Core.Method("parseInt(String)")
  public static Value parseInt(TState tstate, ResultsInfo results, Value s)
      throws BuiltinException {
    Value end = StringValue.skipInt(tstate, s, NumValue.ZERO);
    Err.INVALID_ARGUMENT.unless(lengthIs(tstate, s, end));
    return parseInt(tstate, results, s, NumValue.ZERO, end);
  }

  /**
   * The substring of {@code sv} from {@code start} (inclusive) to {@code end} (exclusive) must be
   * an optional '+' or '-' followed by one or more digits; returns the corresponding number.
   */
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

  /**
   * A Condition that is true if the length of {@code s} (a StringValue) is equal to {@code n} (an
   * integer).
   */
  public static Condition lengthIs(TState tstate, Value s, Value n) {
    if (s instanceof StringValue sv && n instanceof NumValue) {
      return Condition.of(NumValue.equals(n, sv.value.length()));
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue length = LENGTH_OP.result(codeGen.asCodeValue(s));
    return Condition.intEq(codeGen.asCodeValue(n), length);
  }

  /**
   * A Condition that is true if {@code start} is a valid index in {@code s}, and the substring of
   * {@code s} beginning at {@code start} starts with {@code sub}.
   */
  public static Condition substringStartsWith(TState tstate, Value s, Value start, Value sub)
      throws BuiltinException {
    if (s instanceof StringValue sv
        && start instanceof NumValue
        && sub instanceof StringValue sv2) {
      Err.INVALID_ARGUMENT.unless(NumValue.isInt(start));
      return Condition.of(substringStartsWith(sv, NumValue.asInt(start), sv2));
    }
    return Condition.isNonZero(
        codeGen -> {
          Value iStart;
          try {
            iStart = start.verifyInt(Err.INVALID_ARGUMENT);
          } catch (BuiltinException e) {
            codeGen.escape();
            return CodeValue.ZERO;
          }
          return SUBSTRING_STARTS_WITH_OP.result(
              codeGen.asCodeValue(s), codeGen.asCodeValue(iStart), codeGen.asCodeValue(sub));
        });
  }

  /**
   * A Condition that is true if {@code s} contains the code point {@code cp}; caller is responsible
   * for verifying that {@code cp} is an integer.
   */
  public static Condition containsCodePoint(TState tstate, Value s, Value cp) {
    if (s instanceof StringValue sv && cp instanceof NumValue) {
      return Condition.of(containsCodePoint(sv, NumValue.asInt(cp)));
    }
    return Condition.isNonZero(
        codeGen -> CONTAINS_OP.result(codeGen.asCodeValue(s), codeGen.asCodeValue(cp)));
  }

  static final Op LENGTH_OP =
      RcOp.forRcMethod(StringValue.class, "length").withConstSimplifier().build();

  public int length() {
    return value.length();
  }

  static final Op CODE_POINT_OP =
      RcOp.forRcMethod(StringValue.class, "codePoint", int.class).withConstSimplifier().build();

  /** Returns the code point at the given index, or -1 if {@code index} is invalid. */
  public int codePoint(int index) {
    if (index >= 0 && index < value.length()) {
      return value.charAt(index);
    } else {
      return -1;
    }
  }

  static final Op SUBSTRING_OP =
      RcOp.forRcMethod(StringValue.class, "substring", TState.class, int.class, int.class)
          .withConstSimplifier()
          .build();

  /**
   * Returns the substring from {@code start} (inclusive) to {@code end} (exclusive). If {@code end}
   * is -1, the end of the string is used.
   */
  @RC.Out
  public StringValue substring(TState tstate, int start, int end) {
    int size = value.length();
    if (end == -1) {
      end = size;
    }
    if (start == 0 && end == size) {
      addRef();
      return this;
    } else if (start < 0 || end > size || start > end) {
      return null;
    } else {
      return new StringValue(tstate, value.substring(start, end));
    }
  }

  static final Op SKIP_INT_OP =
      RcOp.forRcMethod(StringValue.class, "skipInt", int.class).withConstSimplifier().build();

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  /**
   * If the characters beginning at {@code pos} are an optional '+' or '-' followed by one or more
   * digits, returns the position of the first non-digit following the digits; otherwise returns -1.
   */
  public int skipInt(int start) {
    if (start < 0) {
      return -1;
    }
    int pos = start;
    int size = value.length();
    int result = -1;
    while (pos < size) {
      char c = value.charAt(pos);
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

  static final Op CONCAT_OP =
      RcOp.forRcMethod(
              StringValue.class, "concat", TState.class, StringValue.class, StringValue.class)
          .build();

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

  static final Op SUBSTRING_STARTS_WITH_OP =
      RcOp.forRcMethod(
              StringValue.class,
              "substringStartsWith",
              StringValue.class,
              int.class,
              StringValue.class)
          .withConstSimplifier()
          .build();

  public static boolean substringStartsWith(StringValue x, int n, StringValue y) {
    return x.value.startsWith(y.value, n);
  }

  static final Op CONTAINS_OP =
      RcOp.forRcMethod(StringValue.class, "containsCodePoint", StringValue.class, int.class)
          .withConstSimplifier()
          .build();

  public static boolean containsCodePoint(StringValue x, int cp) {
    return x.value.indexOf(cp) >= 0;
  }
}
