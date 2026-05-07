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
import java.util.function.BinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.function.UnaryOperator;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.TestBlock;
import org.retrolang.impl.Allocator;
import org.retrolang.impl.CodeGen;
import org.retrolang.impl.Condition;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.ResultsInfo;
import org.retrolang.impl.StructType;
import org.retrolang.impl.TProperty;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.VmFunctionBuilder;

/** Core methods on Numbers. */
public final class NumberCore {
  @Core.Public
  static final VmFunctionBuilder number = VmFunctionBuilder.create("number", 1).isOpen();

  @Core.Public
  static final VmFunctionBuilder negative = VmFunctionBuilder.create("negative", 1).isOpen();

  @Core.Public static final VmFunctionBuilder floor = VmFunctionBuilder.create("floor", 1).isOpen();

  @Core.Public
  static final VmFunctionBuilder ceiling = VmFunctionBuilder.create("ceiling", 1).isOpen();

  @Core.Public static final VmFunctionBuilder abs = VmFunctionBuilder.create("abs", 1).isOpen();

  @Core.Public static final VmFunctionBuilder asInt = VmFunctionBuilder.create("asInt", 1).isOpen();

  @Core.Public static final VmFunctionBuilder u32 = VmFunctionBuilder.create("u32", 1).isOpen();

  @Core.Public static final VmFunctionBuilder uAdd = VmFunctionBuilder.create("uAdd", 2);

  @Core.Public static final VmFunctionBuilder uSubtract = VmFunctionBuilder.create("uSubtract", 2);

  @Core.Public static final VmFunctionBuilder uMultiply = VmFunctionBuilder.create("uMultiply", 2);

  @Core.Public
  static final VmFunctionBuilder uDivWithRemainder =
      VmFunctionBuilder.create("uDivWithRemainder", 2);

  @Core.Public static final VmFunctionBuilder bitAnd = VmFunctionBuilder.create("bitAnd", 2);

  @Core.Public static final VmFunctionBuilder bitOr = VmFunctionBuilder.create("bitOr", 2);

  @Core.Public static final VmFunctionBuilder bitAndNot = VmFunctionBuilder.create("bitAndNot", 2);

  @Core.Public static final VmFunctionBuilder bitInvert = VmFunctionBuilder.create("bitInvert", 1);

  @Core.Public static final VmFunctionBuilder bitCount = VmFunctionBuilder.create("bitCount", 1);

  @Core.Public
  static final VmFunctionBuilder bitFirstZero = VmFunctionBuilder.create("bitFirstZero", 1);

  @Core.Public
  static final VmFunctionBuilder bitFirstOne = VmFunctionBuilder.create("bitFirstOne", 1);

  @Core.Public
  static final VmFunctionBuilder bitLastZero = VmFunctionBuilder.create("bitLastZero", 1);

  @Core.Public
  static final VmFunctionBuilder bitLastOne = VmFunctionBuilder.create("bitLastOne", 1);

  @Core.Public static final VmFunctionBuilder bitShift = VmFunctionBuilder.create("bitShift", 2);

  @Core.Public static final VmFunctionBuilder bitRotate = VmFunctionBuilder.create("bitRotate", 2);

  @Core.Public static final VmFunctionBuilder sqrt = mathBuiltin("sqrt", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder hypot = mathBuiltin("hypot", 2).isOpen();

  @Core.Public static final VmFunctionBuilder exp = mathBuiltin("exp", 1).isOpen();

  @Core.Public static final VmFunctionBuilder log = mathBuiltin("log", 1).mayReturnNaN().isOpen();

  @Core.Public
  static final VmFunctionBuilder log10 = mathBuiltin("log10", 1).mayReturnNaN().isOpen();

  @Core.Public
  static final VmFunctionBuilder log1p = mathBuiltin("log1p", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder sin = mathBuiltin("sin", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder cos = mathBuiltin("cos", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder tan = mathBuiltin("tan", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder asin = mathBuiltin("asin", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder acos = mathBuiltin("acos", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder atan = mathBuiltin("atan", 1).isOpen();

  @Core.Public static final VmFunctionBuilder sinh = mathBuiltin("sinh", 1).isOpen();

  @Core.Public static final VmFunctionBuilder cosh = mathBuiltin("cosh", 1).isOpen();

  @Core.Public static final VmFunctionBuilder tanh = mathBuiltin("tanh", 1).isOpen();

  @Core.Public static final VmFunctionBuilder random0 = mathBuiltin("random", 0);

  @Core.Public static final VmFunctionBuilder pi = constant("pi", Math.PI);

  @Core.Public static final VmFunctionBuilder e = constant("e", Math.E);

  @Core.Public
  static final VmFunctionBuilder degreesPerRadian = constant("degreesPerRadian", 180 / Math.PI);

  @Core.Public
  static final VmFunctionBuilder radiansPerDegree = constant("radiansPerDegree", Math.PI / 180);

  /**
   * Most U32 arithmetic operations return a struct with these keys.
   *
   * <p>Must be in alphabetical order.
   */
  static final StructType HIGH_LOW_KEYS = new StructType("high", "low");

  /**
   * uDivWithRemainder returns a struct with these keys.
   *
   * <p>Must be in alphabetical order.
   */
  static final StructType Q_R_KEYS = new StructType("q", "r");

  /** Create a new builtin with the given name that returns a constant value. */
  private static VmFunctionBuilder constant(String name, double d) {
    return VmFunctionBuilder.fromConstant(name, NumValue.of(d, Allocator.UNCOUNTED));
  }

  /**
   * Create a new builtin with the given signature that is implemented by calling the method of the
   * same name in java.lang.Math.
   */
  private static VmFunctionBuilder.FromOp mathBuiltin(String name, int numArgs) {
    Class<?>[] argTypes = new Class<?>[numArgs];
    Arrays.fill(argTypes, double.class);
    return VmFunctionBuilder.fromOp(name, Op.forMethod(Math.class, name, argTypes).build());
  }

  @Core.Method("number(Number)")
  static Value numberNumber(@RC.In Value x) {
    return x;
  }

  @Core.Method("add(Number, Number)")
  static Value addNumbers(TState tstate, ResultsInfo results, Value x, Value y)
      throws BuiltinException {
    if (x instanceof RValue || y instanceof RValue) {
      return doMath(tstate.codeGen(), x, y, results, Op.ADD_INTS_EXACT, Op.ADD_DOUBLES);
    }
    return doMath(tstate, x, y, Math::addExact, (a, b) -> a + b);
  }

  @Core.Method("subtract(Number, Number)")
  static Value subtractNumbers(TState tstate, ResultsInfo results, Value x, Value y)
      throws BuiltinException {
    if (x instanceof RValue || y instanceof RValue) {
      return doMath(tstate.codeGen(), x, y, results, Op.SUBTRACT_INTS_EXACT, Op.SUBTRACT_DOUBLES);
    }
    return doMath(tstate, x, y, Math::subtractExact, (a, b) -> a - b);
  }

  @Core.Method("multiply(Number, Number)")
  static Value multiplyNumbers(TState tstate, ResultsInfo results, Value x, Value y)
      throws BuiltinException {
    if (x instanceof RValue || y instanceof RValue) {
      return doMath(tstate.codeGen(), x, y, results, Op.MULTIPLY_INTS_EXACT, Op.MULTIPLY_DOUBLES);
    }
    return doMath(tstate, x, y, Math::multiplyExact, (a, b) -> a * b);
  }

  private static void checkForNaNResult(CodeGen codeGen, CodeValue result) {
    result = codeGen.materialize(result, double.class);
    FutureBlock isNotNaN = new FutureBlock();
    codeGen.testIsNaN(result, true, isNotNaN);
    codeGen.setResults(Core.NONE);
    codeGen.cb.setNext(isNotNaN);
    codeGen.setResults(codeGen.toValue(result));
  }

  @Core.Method("divide(Number, Number)")
  static void divideNumbers(TState tstate, ResultsInfo results, Value x, Value y) {
    if (x instanceof RValue || y instanceof RValue) {
      CodeGen codeGen = tstate.codeGen();
      CodeValue cx = codeGen.asCodeValue(x);
      CodeValue cy = codeGen.asCodeValue(y);
      codeGen.setResultWithNaNCheck(Op.DIV_DOUBLES.result(cx, cy));
    } else {
      tstate.setResult(NumValue.orNan(NumValue.asDouble(x) / NumValue.asDouble(y), tstate));
    }
  }

  @Core.Method("negative(Number)")
  static Value negative(TState tstate, ResultsInfo results, Value x) throws BuiltinException {
    if (x instanceof RValue) {
      return doMath(tstate.codeGen(), x, results, Op.NEGATE_INT_EXACT, Op.NEGATE_DOUBLE);
    } else if (x instanceof NumValue.I ni) {
      try {
        int i = Math.negateExact(ni.value);
        return NumValue.of(i, tstate);
      } catch (ArithmeticException e) {
        // fall through
      }
    }
    return NumValue.of(-NumValue.asDouble(x), tstate);
  }

  @Core.Method("floor(Number)")
  static Value floor(TState tstate, ResultsInfo results, Value x) throws BuiltinException {
    return floorOrCeiling(tstate, results, x, true);
  }

  @Core.Method("ceiling(Number)")
  static Value ceiling(TState tstate, ResultsInfo results, Value x) throws BuiltinException {
    return floorOrCeiling(tstate, results, x, false);
  }

  private static final Op FLOOR_OP = Op.forMethod(Math.class, "floor", double.class).build();
  private static final Op CEIL_OP = Op.forMethod(Math.class, "ceil", double.class).build();

  private static Value floorOrCeiling(TState tstate, ResultsInfo results, Value x, boolean floor)
      throws BuiltinException {
    if (x instanceof NumValue.I) {
      return x;
    } else if (!(x instanceof RValue)) {
      double d = NumValue.asDouble(x);
      double dResult = floor ? Math.floor(d) : Math.ceil(d);
      int iResult = (int) dResult;
      if (dResult == iResult) {
        return NumValue.of(iResult, tstate);
      } else if (dResult == d) {
        return x;
      } else {
        return NumValue.of(dResult, tstate);
      }
    }
    CodeGen codeGen = tstate.codeGen();
    Register xr = codeGen.register(x);
    if (xr.type() == int.class) {
      return x;
    }
    CodeValue v = (floor ? FLOOR_OP : CEIL_OP).result(xr);
    if (results.result(TProperty.COERCES_TO_FLOAT)) {
      v = codeGen.materialize(v, double.class);
    } else {
      v =
          codeGen.materializeCatchingArithmeticException(
              Op.LONG_TO_INT_EXACT.result(Op.DOUBLE_TO_LONG.result(v)));
    }
    return codeGen.toValue(v);
  }

  private static final Op FLOOR_DIV_OP =
      Op.forMethod(Math.class, "floorDiv", long.class, int.class).build();

  @Core.Method("div(Number, Number)")
  static Value divNumbers(TState tstate, ResultsInfo results, Value x, Value y) {
    if (x instanceof NumValue.I nix && y instanceof NumValue.I niy) {
      int ix = nix.value;
      int iy = niy.value;
      if (iy == 0) {
        return (ix == 0)
            ? Core.NONE
            : (ix < 0) ? NumValue.NEGATIVE_INFINITY : NumValue.POSITIVE_INFINITY;
      }
      // Math.floorDiv(int, int) returns the wrong result for (Integer.MIN_VALUE, -1), so  use the
      // long version instead.
      long longResult = Math.floorDiv((long) ix, iy);
      int result;
      try {
        result = Math.toIntExact(longResult);
      } catch (ArithmeticException e) {
        // There's only one way for the result to not be an int
        assert ix == Integer.MIN_VALUE && iy == -1;
        return NumValue.of((double) longResult, tstate);
      }
      return NumValue.of(result, tstate);
    } else if (!(x instanceof RValue || y instanceof RValue)) {
      double q = Math.floor(NumValue.asDouble(x) / NumValue.asDouble(y));
      int iq = (int) q;
      return (q == iq) ? NumValue.of(iq, tstate) : NumValue.orNan(q, tstate);
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue cx = codeGen.asCodeValue(x);
    CodeValue cy = codeGen.asCodeValue(y);
    if (cx.type() == double.class
        || cy.type() == double.class
        || results.result(TProperty.COERCES_TO_FLOAT)) {
      codeGen.setResultWithNaNCheck(FLOOR_OP.result(Op.DIV_DOUBLES.result(cx, cy)));
    } else {
      Condition.intEq(cy, CodeValue.ZERO)
          .test(
              () -> {
                // If x is non-zero we should be returning positive or negative infinity, but we
                // can't do that unless our result is represented as a double (in which case we
                // would have taken the other "if" branch above).
                codeGen.escapeUnless(Condition.intEq(cx, CodeValue.ZERO));
                codeGen.setResults(Core.NONE);
              },
              () -> {
                CodeValue result = Op.LONG_TO_INT_EXACT.result(FLOOR_DIV_OP.result(cx, cy));
                try {
                  result = codeGen.materializeCatchingArithmeticException(result);
                } catch (BuiltinException e) {
                  // Always fails?  Not reachable, I think.
                  assert false;
                  codeGen.escape();
                  return;
                }
                codeGen.setResults(codeGen.toValue(result));
              });
    }
    return null;
  }

  private static final Op FLOOR_MOD_OP =
      Op.forMethod(Math.class, "floorMod", int.class, int.class).build();

  private static final Op MODULO_OP =
      Op.forMethod(NumberCore.class, "modulo", double.class, double.class).build();

  @Core.Method("modulo(Number, Number)")
  static void moduloNumbers(TState tstate, Value x, Value y) {
    if (!(x instanceof RValue || y instanceof RValue)) {
      if (x instanceof NumValue.I nix && y instanceof NumValue.I niy) {
        int ix = nix.value;
        int iy = niy.value;
        tstate.setResult(iy == 0 ? Core.NONE : NumValue.of(Math.floorMod(ix, iy), tstate));
      } else {
        double dx = NumValue.asDouble(x);
        double dy = NumValue.asDouble(y);
        tstate.setResult(NumValue.orNan(modulo(dx, dy), tstate));
      }
      return;
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue cx = codeGen.asCodeValue(x);
    CodeValue cy = codeGen.asCodeValue(y);
    if (cx.type() == double.class || cy.type() == double.class) {
      checkForNaNResult(codeGen, MODULO_OP.result(cx, cy));
    } else {
      Condition.intEq(cy, CodeValue.ZERO)
          .test(
              () -> codeGen.setResults(Core.NONE),
              () -> {
                codeGen.setResults(codeGen.intToValue(FLOOR_MOD_OP.result(cx, cy)));
              });
    }
  }

  /**
   * Retrospect's definition of {@code double % double} has some subtle differences from Java's
   * (because Java's is wrong).
   */
  public static double modulo(double x, double y) {
    if (Double.isInfinite(y)) {
      // Java thinks that x % inf == x, but that's clearly wrong if x and y have different signs;
      // rather than trying to make up something meaningful here I'm just going to assume that no
      // one actually wants "modulo(x, infinity)" to work.
      return Double.NaN;
    }
    double r = x % y;
    // Java gives us r with the same sign as x, but we want it to have the same sign as y
    if ((Double.doubleToRawLongBits(r) ^ Double.doubleToRawLongBits(y)) < 0 && r != 0) {
      r += y;
    }
    return r;
  }

  private static final Op POW_OP =
      Op.forMethod(Math.class, "pow", double.class, double.class).build();

  @Core.Method("exponent(Number, Number)")
  static Value exponentNumbers(TState tstate, ResultsInfo results, Value x, Value y)
      throws BuiltinException {
    if (y instanceof NumValue) {
      switch (NumValue.asIntOrMinusOne(y)) {
        case 0:
          return NumValue.ONE;
        case 1:
          return addRef(x);
        case 2:
          return multiplyNumbers(tstate, results, x, x);
      }
    }
    if (!(x instanceof RValue || y instanceof RValue)) {
      double d = Math.pow(NumValue.asDouble(x), NumValue.asDouble(y));
      if (x instanceof NumValue.I && y instanceof NumValue.I) {
        int i = (int) d;
        if (d == i) {
          return NumValue.of(i, tstate);
        }
      }
      return NumValue.orNan(d, tstate);
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue cx = codeGen.asCodeValue(x);
    CodeValue cy = codeGen.asCodeValue(y);
    codeGen.setResultWithNaNCheck(POW_OP.result(cx, cy));
    return null;
  }

  /**
   * If {@code x} and {@code y} are both integers, try applying {@code intOp} to them. If that
   * succeeds, return its result as a {@link NumValue.I}. If either argument is a double or if
   * {@code intOp} throws an ArithmeticException, apply {@code doubleOp} to {@code x} and {@code y}
   * and return its result as a {@link NumValue.D}.
   */
  @RC.Out
  private static Value doMath(
      TState tstate, Value x, Value y, IntBinaryOperator intOp, DoubleBinaryOperator doubleOp) {
    if (x instanceof NumValue.I nix && y instanceof NumValue.I niy) {
      try {
        int i = intOp.applyAsInt(nix.value, niy.value);
        return NumValue.of(i, tstate);
      } catch (ArithmeticException e) {
        // fall through
      }
    }
    double d = doubleOp.applyAsDouble(NumValue.asDouble(x), NumValue.asDouble(y));
    return NumValue.orNan(d, tstate);
  }

  /**
   * If {@code x} and {@code y} are both integers and our result is to be stored as an integer, emit
   * code that applies {@code intOp} and escapes if it throws an ArithmeticException; otherwise emit
   * code that applies {@code doubleOp}.
   */
  private static Value doMath(
      CodeGen codeGen, Value x, Value y, ResultsInfo results, Op exactIntOp, Op doubleOp)
      throws BuiltinException {
    CodeValue cx = codeGen.asCodeValue(x);
    CodeValue cy = codeGen.asCodeValue(y);
    CodeValue result;
    if (cx.type() == double.class
        || cy.type() == double.class
        || results.result(TProperty.COERCES_TO_FLOAT)) {
      result = codeGen.materialize(doubleOp.result(cx, cy), double.class);
    } else {
      result = codeGen.materializeCatchingArithmeticException(exactIntOp.result(cx, cy));
    }
    return codeGen.toValue(result);
  }

  /**
   * If {@code x} is an integer and our result is to be stored as an integer, emit code that applies
   * {@code intOp} and escapes if it throws an ArithmeticException; otherwise emit code that applies
   * {@code doubleOp}.
   */
  private static Value doMath(
      CodeGen codeGen, Value x, ResultsInfo results, Op exactIntOp, Op doubleOp)
      throws BuiltinException {
    CodeValue cx = codeGen.asCodeValue(x);
    CodeValue result;
    if (cx.type() == double.class || results.result(TProperty.COERCES_TO_FLOAT)) {
      result = codeGen.materialize(doubleOp.result(cx), double.class);
    } else {
      result = codeGen.materializeCatchingArithmeticException(exactIntOp.result(cx));
    }
    return codeGen.toValue(result);
  }

  @Core.Method("lessThan(Number, Number)")
  static Value lessThanNumbers(Value x, Value y) {
    return Condition.numericLessThan(x, y).asValue();
  }

  @Core.Method("equal(Number, Number)")
  static Value equalNumbers(Value x, Value y) {
    return Condition.numericEq(x, y).asValue();
  }

  @Core.Method("asInt(Number)")
  static Value asInt(TState tstate, Value x) throws BuiltinException {
    if (x instanceof NumValue.I) {
      return addRef(x);
    } else if (x instanceof NumValue.D nd) {
      double d = nd.value;
      int i = (int) d;
      Err.INVALID_ARGUMENT.unless(i == d);
      return NumValue.of(i, tstate);
    }
    CodeGen codeGen = tstate.codeGen();
    return codeGen.toValue(codeGen.verifyInt(x));
  }

  @Core.Method("abs(Number)")
  static Value abs(TState tstate, ResultsInfo results, Value x) throws BuiltinException {
    if (x instanceof RValue) {
      return doMath(tstate.codeGen(), x, results, Op.ABS_INT_EXACT, Op.ABS_DOUBLE);
    } else if (x instanceof NumValue.I ni) {
      int i = ni.value;
      if (i >= 0) {
        return addRef(x);
      } else if (i != Integer.MIN_VALUE) {
        return NumValue.of(-i, tstate);
      }
    }
    double d = NumValue.asDouble(x);
    if (d >= 0) {
      return addRef(x);
    } else {
      return NumValue.of(-d, tstate);
    }
  }

  static final Op TO_U32_OP =
      Op.forMethod(NumberCore.class, "toU32", double.class).withConstSimplifier().build();

  /**
   * If {@code d} is an integer between 0 and 2**32-1, returns it as a long; otherwise returns -1.
   */
  public static long toU32(double d) {
    long i = (long) d;
    return (i == d && i <= 0xffffffffL) ? i : -1;
  }

  /**
   * {@code method u32(Number x) = ...}
   *
   * <p>The {@code x} must be an integer between 0 and 2**32-1
   */
  @Core.Method("u32(Number)")
  static Value u32(TState tstate, Value x) throws BuiltinException {
    Value u32;
    if (x instanceof NumValue.I ni) {
      Err.INVALID_ARGUMENT.unless(ni.value >= 0);
      u32 = addRef(x);
    } else if (!(x instanceof RValue)) {
      long temp = toU32(((NumValue.D) x).value);
      Err.INVALID_ARGUMENT.unless(temp >= 0);
      u32 = NumValue.of((int) temp, tstate);
    } else {
      CodeGen codeGen = tstate.codeGen();
      CodeValue cx = codeGen.asCodeValue(x);
      if (cx.type() == int.class) {
        Err.ESCAPE.when(Condition.intLessThan(cx, CodeValue.ZERO));
        u32 = x;
      } else {
        CodeValue asLong = codeGen.materialize(TO_U32_OP.result(cx), long.class);
        Err.ESCAPE.when(Condition.longLessThan(asLong, CodeValue.ZERO));
        u32 = codeGen.intToValue(Op.LONG_TO_INT.result(asLong));
      }
    }
    return tstate.compound(Core.U32, u32);
  }

  public static final Op TO_UNSIGNED_LONG =
      Op.forMethod(Integer.class, "toUnsignedLong", int.class).withConstSimplifier().build();

  @Core.Method("number(U32)")
  static Value numberU32(TState tstate, ResultsInfo results, Value x) {
    if (!(x instanceof RValue)) {
      Value asInt = x.peekElement(0);
      int i = ((NumValue.I) asInt).value;
      if (i >= 0) {
        return asInt.makeStorable(tstate);
      } else {
        return NumValue.of((double) Integer.toUnsignedLong(i), tstate);
      }
    }
    CodeGen codeGen = tstate.codeGen();
    if (results.result(TProperty.COERCES_TO_FLOAT)) {
      CodeValue asDouble = codeGen.materialize(u32ToLong(codeGen, x), double.class);
      return codeGen.toValue(asDouble);
    } else {
      Value asInt = x.peekElement(0);
      codeGen.escapeWhen(Condition.numericLessThan(asInt, NumValue.ZERO));
      return asInt;
    }
  }

  private static int u32ToInt(Value v) {
    assert v.baseType() == Core.U32;
    return v.elementAsInt(0);
  }

  private static long u32ToLong(Value v) {
    return Integer.toUnsignedLong(u32ToInt(v));
  }

  private static CodeValue u32ToInt(CodeGen codeGen, Value v) {
    assert v.baseType() == Core.U32;
    return codeGen.asCodeValue(v.peekElement(0));
  }

  private static CodeValue u32ToLong(CodeGen codeGen, Value v) {
    return TO_UNSIGNED_LONG.result(u32ToInt(codeGen, v));
  }

  @Core.Method("lessThan(U32, U32)")
  static Value lessThanU32U32(TState tstate, Value x, Value y) {
    if (!(x instanceof RValue || y instanceof RValue)) {
      return Core.bool(u32ToLong(x) < u32ToLong(y));
    }
    CodeGen codeGen = tstate.codeGen();
    return Condition.longLessThan(u32ToLong(codeGen, x), u32ToLong(codeGen, y)).asValue();
  }

  @Core.Method("lessThan(U32, Number)")
  static Value lessThanU32Number(TState tstate, Value x, Value y) {
    if (!(x instanceof RValue || y instanceof RValue)) {
      long xl = u32ToLong(x);
      boolean result;
      if (y instanceof NumValue.I niy) {
        result = xl < niy.value;
      } else {
        result = xl < ((NumValue.D) y).value;
      }
      return Core.bool(result);
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue xLong = u32ToLong(codeGen, x);
    CodeValue yCv = codeGen.asCodeValue(y);
    OpCodeType opType = (yCv.type() == double.class) ? OpCodeType.DOUBLE : OpCodeType.LONG;
    return Condition.fromTest(() -> new TestBlock.IsLessThan(opType, xLong, yCv)).asValue();
  }

  @Core.Method("lessThan(Number, U32)")
  static Value lessThanNumberU32(TState tstate, Value x, Value y) {
    if (!(x instanceof RValue || y instanceof RValue)) {
      long yl = u32ToLong(y);
      boolean result;
      if (x instanceof NumValue.I nix) {
        result = nix.value < yl;
      } else {
        result = ((NumValue.D) x).value < yl;
      }
      return Core.bool(result);
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue yLong = u32ToLong(codeGen, y);
    CodeValue xCv = codeGen.asCodeValue(x);
    OpCodeType opType = (xCv.type() == double.class) ? OpCodeType.DOUBLE : OpCodeType.LONG;
    return Condition.fromTest(() -> new TestBlock.IsLessThan(opType, xCv, yLong)).asValue();
  }

  @Core.Method("equal(U32, U32)")
  static Value equalU32U32(TState tstate, Value x, Value y) {
    if (!(x instanceof RValue || y instanceof RValue)) {
      return Core.bool(u32ToInt(x) == u32ToInt(y));
    }
    CodeGen codeGen = tstate.codeGen();
    return Condition.intEq(u32ToInt(codeGen, x), u32ToInt(codeGen, y)).asValue();
  }

  @Core.Method("equal(U32, Number)")
  static Value equalU32Number(TState tstate, Value x, Value y) {
    if (!(x instanceof RValue || y instanceof RValue)) {
      long xl = u32ToLong(x);
      boolean result;
      if (y instanceof NumValue.I niy) {
        result = xl == niy.value;
      } else {
        result = xl == ((NumValue.D) y).value;
      }
      return Core.bool(result);
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue xLong = u32ToLong(codeGen, x);
    CodeValue yCv = codeGen.asCodeValue(y);
    OpCodeType opType = (yCv.type() == double.class) ? OpCodeType.DOUBLE : OpCodeType.LONG;
    return Condition.fromTest(() -> new TestBlock.IsEq(opType, xLong, yCv)).asValue();
  }

  @Core.Method("equal(Number, U32)")
  static Value equalNumberU32(TState tstate, Value x, Value y) {
    return equalU32Number(tstate, y, x);
  }

  @RC.Out
  private static Value asHighLow(TState tstate, Value low, Value high) {
    return tstate.compound(
        HIGH_LOW_KEYS, tstate.compound(Core.U32, high), tstate.compound(Core.U32, low));
  }

  /** Converts a long to a {@code {high, low}} pair of U32s. */
  @RC.Out
  private static Value asHighLow(TState tstate, long value) {
    Value low = NumValue.of((int) value, tstate);
    Value high = NumValue.of((int) (value >> 32), tstate);
    return asHighLow(tstate, low, high);
  }

  /** The number of bits in an int, as a CodeValue. */
  private static final CodeValue INT_BITS = CodeValue.of(32);

  /** Converts a long CodeValue to a {@code {high, low}} pair of U32s. */
  private static Value asHighLow(CodeGen codeGen, CodeValue value) {
    value = codeGen.materialize(value, long.class);
    Value low = codeGen.intToValue(Op.LONG_TO_INT.result(value));
    Value high =
        codeGen.intToValue(Op.LONG_TO_INT.result(Op.SHIFT_RIGHT_LONG.result(value, INT_BITS)));
    return asHighLow(codeGen.tstate(), low, high);
  }

  /**
   * Combines two U32s using an operation on longs, and returns the result as a {@code {high, low}}
   * pair of U32s.
   */
  private static Value u32sToHighLow(
      TState tstate, Value x, Value y, LongBinaryOperator binOp, Op op) {
    if (!(x instanceof RValue || y instanceof RValue)) {
      return asHighLow(tstate, binOp.applyAsLong(u32ToLong(x), u32ToLong(y)));
    } else {
      CodeGen codeGen = tstate.codeGen();
      return asHighLow(codeGen, op.result(u32ToLong(codeGen, x), u32ToLong(codeGen, y)));
    }
  }

  @Core.Method("uAdd(U32, U32)")
  static Value uAdd(TState tstate, Value x, Value y) {
    return u32sToHighLow(tstate, x, y, (a, b) -> a + b, Op.ADD_LONGS);
  }

  @Core.Method("uMultiply(U32, U32)")
  static Value uMultiply(TState tstate, Value x, Value y) {
    return u32sToHighLow(tstate, x, y, (a, b) -> a * b, Op.MULTIPLY_LONGS);
  }

  @Core.Method("uSubtract(U32, U32)")
  static Value uSubtract(TState tstate, Value x, Value y) {
    return u32sToHighLow(tstate, x, y, (a, b) -> a - b, Op.SUBTRACT_LONGS);
  }

  /** Converts a {@code {high, low}} pair of U32s to a long. */
  static long highLowToLong(Value struct) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(HIGH_LOW_KEYS.matches(struct));
    Value high = struct.peekElement(0);
    Value low = struct.peekElement(1);
    Err.INVALID_ARGUMENT.unless(high.isa(Core.U32).and(low.isa(Core.U32)));
    return (u32ToLong(high) << 32) + u32ToLong(low);
  }

  static CodeValue highLowToLong(CodeGen codeGen, Value struct) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(HIGH_LOW_KEYS.matches(struct));
    Value high = struct.peekElement(0);
    Value low = struct.peekElement(1);
    Err.INVALID_ARGUMENT.unless(high.isa(Core.U32).and(low.isa(Core.U32)));
    CodeValue highCv = codeGen.asCodeValue(high.peekElement(0));
    CodeValue lowCv = u32ToLong(codeGen, low);
    return Op.ADD_LONGS.result(Op.SHIFT_LEFT_LONG.result(highCv, INT_BITS), lowCv);
  }

  private static final Op LONG_DIVIDE_UNSIGNED =
      Op.forMethod(Long.class, "divideUnsigned", long.class, long.class).build();
  private static final Op LONG_REMAINDER_UNSIGNED =
      Op.forMethod(Long.class, "remainderUnsigned", long.class, long.class).build();

  @Core.Method("uDivWithRemainder(Struct, U32)")
  static Value uDivWithRemainder(TState tstate, Value x, Value y) throws BuiltinException {
    Value q;
    Value r;
    if (!(x instanceof RValue || y instanceof RValue)) {
      long xl = highLowToLong(x);
      long yl = u32ToLong(y);
      q = asHighLow(tstate, Long.divideUnsigned(xl, yl));
      long rl = Long.remainderUnsigned(xl, yl);
      assert (rl & 0xffffffffL) == rl;
      r = NumValue.of((int) rl, tstate);
    } else {
      CodeGen codeGen = tstate.codeGen();
      CodeValue xl = codeGen.materialize(highLowToLong(codeGen, x), long.class);
      CodeValue yl = codeGen.materialize(u32ToLong(codeGen, y), long.class);
      q = asHighLow(codeGen, LONG_DIVIDE_UNSIGNED.result(xl, yl));
      r = codeGen.intToValue(Op.LONG_TO_INT.result(LONG_REMAINDER_UNSIGNED.result(xl, yl)));
    }
    return tstate.compound(Q_R_KEYS, q, tstate.compound(Core.U32, r));
  }

  /** Applies an int operation to a U32 and returns the result as an Integer. */
  private static Value unaryBitOp(
      TState tstate, Value x, IntUnaryOperator intOp, UnaryOperator<CodeValue> op) {
    if (!(x instanceof RValue)) {
      return NumValue.of(intOp.applyAsInt(u32ToInt(x)), tstate);
    } else {
      CodeGen codeGen = tstate.codeGen();
      return codeGen.intToValue(op.apply(u32ToInt(codeGen, x)));
    }
  }

  /** {@code v} must be a U32 or an Integer; returns it as an int. */
  private static int toInt(Value v) throws BuiltinException {
    if (v.baseType() == Core.U32) {
      return v.elementAsInt(0);
    } else {
      Err.INVALID_ARGUMENT.unless(NumValue.isInt(v));
      return NumValue.asInt(v);
    }
  }

  /** {@code v} must be a U32 or an Integer; returns it as an int CodeValue. */
  private static CodeValue toInt(CodeGen codeGen, Value v) throws BuiltinException {
    return (v.baseType() == Core.U32) ? u32ToInt(codeGen, v) : codeGen.verifyInt(v);
  }

  /**
   * Applies an int operation to two arguments, each a U32 or an Integer, and returns the result as
   * an Integer.
   *
   * <p>Accepting arguments as either type lets us use this for both operations like bitAnd (which
   * takes two U32s) and bitShift (which takes a U32 and an Integer).
   */
  private static Value binaryBitOp(
      TState tstate, Value x, Value y, IntBinaryOperator intOp, BinaryOperator<CodeValue> op)
      throws BuiltinException {
    if (!(x instanceof RValue || y instanceof RValue)) {
      return NumValue.of(intOp.applyAsInt(toInt(x), toInt(y)), tstate);
    } else {
      CodeGen codeGen = tstate.codeGen();
      return codeGen.intToValue(op.apply(toInt(codeGen, x), toInt(codeGen, y)));
    }
  }

  @Core.Method("bitAnd(U32, U32)")
  static Value bitAnd(TState tstate, Value x, Value y) throws BuiltinException {
    return tstate.compound(
        Core.U32, binaryBitOp(tstate, x, y, (a, b) -> a & b, Op.BIT_AND_INTS::result));
  }

  @Core.Method("bitOr(U32, U32)")
  static Value bitOr(TState tstate, Value x, Value y) throws BuiltinException {
    return tstate.compound(
        Core.U32, binaryBitOp(tstate, x, y, (a, b) -> a | b, Op.BIT_OR_INTS::result));
  }

  @Core.Method("bitAndNot(U32, U32)")
  static Value bitAndNot(TState tstate, Value x, Value y) throws BuiltinException {
    return tstate.compound(
        Core.U32,
        binaryBitOp(
            tstate,
            x,
            y,
            (a, b) -> a & ~b,
            (a, b) ->
                Op.BIT_AND_INTS.result(a, Op.BIT_XOR_INTS.result(b, CodeValue.NEGATIVE_ONE))));
  }

  @Core.Method("bitInvert(U32)")
  static Value bitInvert(TState tstate, Value x) {
    return tstate.compound(
        Core.U32,
        unaryBitOp(tstate, x, a -> ~a, a -> Op.BIT_XOR_INTS.result(a, CodeValue.NEGATIVE_ONE)));
  }

  private static final Op BIT_COUNT_OP = Op.forMethod(Integer.class, "bitCount", int.class).build();

  @Core.Method("bitCount(U32)")
  static Value bitCount(TState tstate, Value x) {
    return unaryBitOp(tstate, x, Integer::bitCount, BIT_COUNT_OP::result);
  }

  public static int bitFirstOne(int i) {
    int result = Integer.numberOfTrailingZeros(i);
    return result == 32 ? -1 : result;
  }

  public static int bitLastOne(int i) {
    return 31 - Integer.numberOfLeadingZeros(i);
  }

  private static final Op BIT_FIRST_ONE_OP =
      Op.forMethod(NumberCore.class, "bitFirstOne", int.class).build();
  private static final Op BIT_LAST_ONE_OP =
      Op.forMethod(NumberCore.class, "bitLastOne", int.class).build();

  @Core.Method("bitFirstZero(U32)")
  static Value bitFirstZero(TState tstate, Value x) {
    return unaryBitOp(
        tstate,
        x,
        a -> bitFirstOne(~a),
        a1 -> BIT_FIRST_ONE_OP.result(Op.BIT_XOR_INTS.result(a1, CodeValue.NEGATIVE_ONE)));
  }

  @Core.Method("bitFirstOne(U32)")
  static Value bitFirstOne(TState tstate, Value x) {
    return unaryBitOp(tstate, x, NumberCore::bitFirstOne, BIT_FIRST_ONE_OP::result);
  }

  @Core.Method("bitLastZero(U32)")
  static Value bitLastZero(TState tstate, Value x) {
    return unaryBitOp(
        tstate,
        x,
        a -> bitLastOne(~a),
        a1 -> BIT_LAST_ONE_OP.result(Op.BIT_XOR_INTS.result(a1, CodeValue.NEGATIVE_ONE)));
  }

  @Core.Method("bitLastOne(U32)")
  static Value bitLastOne(TState tstate, Value x) {
    return unaryBitOp(tstate, x, NumberCore::bitLastOne, BIT_LAST_ONE_OP::result);
  }

  public static int bitShift(int x, int nBits) {
    // This test was `Math.abs(nBits) > 31`, but CodeRabbit pointed out that that doesn't catch
    // nBits == Integer.MIN_VALUE !
    if (nBits > 31 || nBits < -31) {
      // Java shifts by mod(nBits, 32), which is just wrong
      return 0;
    } else {
      return (nBits >= 0) ? x << nBits : x >>> -nBits;
    }
  }

  private static final Op BIT_SHIFT =
      Op.forMethod(NumberCore.class, "bitShift", int.class, int.class).build();

  @Core.Method("bitShift(U32, Number)")
  static Value bitShift(TState tstate, Value x, Value nBits) throws BuiltinException {
    return tstate.compound(
        Core.U32, binaryBitOp(tstate, x, nBits, NumberCore::bitShift, BIT_SHIFT::result));
  }

  @Core.Method("bitRotate(U32, Number)")
  static Value bitRotate(TState tstate, Value x, Value nBits) throws BuiltinException {
    return tstate.compound(
        Core.U32, binaryBitOp(tstate, x, nBits, Integer::rotateLeft, Op.ROTATE_LEFT_INT::result));
  }

  @Core.Method("bitRotate(Struct, Number)")
  static Value rotateHighLow(TState tstate, Value x, Value nBits) throws BuiltinException {
    Value result;
    if (!(x instanceof RValue || nBits instanceof RValue)) {
      Err.INVALID_ARGUMENT.unless(NumValue.isInt(nBits));
      long longResult = Long.rotateLeft(highLowToLong(x), NumValue.asInt(nBits));
      result = NumValue.of((int) longResult, tstate);
    } else {
      CodeGen codeGen = tstate.codeGen();
      CodeValue xl = highLowToLong(codeGen, x);
      CodeValue n = codeGen.verifyInt(nBits);
      result = codeGen.intToValue(Op.LONG_TO_INT.result(Op.ROTATE_LEFT_LONG.result(xl, n)));
    }
    return tstate.compound(Core.U32, result);
  }

  private NumberCore() {}
}
