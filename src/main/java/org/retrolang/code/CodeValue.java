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

package org.retrolang.code;

import java.util.Objects;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;
import org.retrolang.code.Block.SubstitutionOutcome;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeBuilder.PrintOptions;
import org.retrolang.util.Bits;
import org.retrolang.util.StringUtil;

/// A CodeValue represents a single value that can be pushed on the stack during execution. There
/// are three subclasses:
///
/// - [Const]: a constant value
/// - [Register]: a value stored in a local variable
/// - [Op.Result]: a value computed from other CodeValues
///
/// CodeValues are immutable. For simplicity an [Op.Result] that returns no value is also considered
/// a CodeValue, with type `void.class`.
public abstract class CodeValue {
  /// This value's type. Returns null if this is [#NULL]. Returns void.class if this is an
  /// [Op.Result] for an Op that returns no result.
  public abstract Class<?> type();

  /// Emits the instructions to push this value on the stack, coercing it to the given type if
  /// necessary.
  public abstract void push(Emitter emitter, Class<?> type);

  /// Given information about the values of registers, returns a possibly-simplified version of this
  /// CodeValue.
  ///
  /// The result will be fully simplified, i.e. `x.simplify(regs).simplify(regs) ==
  /// x.simplify(regs)`.
  ///
  /// `registerInfo.apply(register.index)` should return information about the value of the
  /// specified register; it should not be null for any register appearing in this CodeValue.
  public abstract CodeValue simplify(IntFunction<ValueInfo> registerInfo);

  /// Given information about the values of registers, returns information about this CodeValue.
  /// Should only be called on the value returned by [#simplify] with the same `registerInfo`. Will
  /// not return a Register.
  public abstract ValueInfo info(IntFunction<ValueInfo> registerInfo);

  /// Enumerates all the registers that are referenced by this CodeValue, and adds (if `add` is
  /// true) or removes their indices from the given Builder.
  public abstract void getLive(boolean add, Bits.Builder live);

  /// Returns true if [#type] is double.class.
  public final boolean isDouble() {
    return type() == double.class;
  }

  /// Returns true if this is not a primitive value.
  public final boolean isPtr() {
    return (this == NULL) || !type().isPrimitive();
  }

  /// Should only be called on instances of Const; returns the value.
  public final Object constValue() {
    return ((Const) this).value;
  }

  /// Should only be called on integer constants; returns the value as an int.
  public final int iValue() {
    return (Integer) constValue();
  }

  /// Should only be called on numeric constants; returns the value as a Number.
  public final Number numberValue() {
    Number result = (Number) constValue();
    // We don't want something like an AtomicInteger here
    assert isJavaLangNumber(result);
    return result;
  }

  /// Should only be called on integer or long constants; returns the value as a long.
  public final long lValue() {
    Number num = numberValue();
    assert !(num instanceof Double || num instanceof Float);
    return num.longValue();
  }

  /// Should only be called on numeric constants; returns the value as a double.
  public final double dValue() {
    return numberValue().doubleValue();
  }

  /// The java.lang package.
  private static final Package JAVA_LANG = Number.class.getPackage();

  /// Returns true if `x` is a "real" number, e.g. a Byte or a Double, not one of the Number
  /// subclasses like AtomicInteger that aren't really numbers.
  private static boolean isJavaLangNumber(Object x) {
    return x instanceof Number && x.getClass().getPackage() == JAVA_LANG;
  }

  /// Returns a [Const] representing the given value.
  ///
  /// Note that Number values return a Const for the corresponding primitive value; there is
  /// currently no way to obtain a Const for the wrapper object. Also note that
  /// `CodeValue.of(Boolean.FALSE)` and `CodeValue.of(Boolean.TRUE)` return `CodeValue.of(0)` and
  /// `CodeValue.of(1)` respectively (i.e. the representation used by the JVM for the corresponding
  /// primitive values).
  public static Const of(Object value) {
    if (value == null) {
      return NULL;
    } else if (isJavaLangNumber(value)) {
      // We currently canonicalize all numbers to int, long, or double to simplify the code
      // that handles them.
      Number num = (Number) value;
      int i = num.intValue();
      if (num instanceof Long) {
        if (i == num.longValue()) {
          // value was given as a long but can be represented by an int
          value = i;
        } else {
          return new Const(value);
        }
      } else if (num instanceof Double || num instanceof Float) {
        double d = num.doubleValue();
        if (i == d) {
          // value was given as floating-point but can be represented by an int
          value = i;
        } else {
          long lng = (long) d;
          if (lng == d) {
            // Prefer a long if it works
            return new Const(lng);
          }
          // If value was a Float, widen it to a Double
          return new Const((value instanceof Double) ? value : d);
        }
      } else {
        assert num instanceof Integer || num instanceof Short || num instanceof Byte;
      }
      // If it's an int, look for a few common cases and fall through to constructing a
      // new Const for everything else.
      switch (i) {
        case -1:
          return NEGATIVE_ONE;
        case 0:
          return ZERO;
        case 1:
          return ONE;
        case 2:
          return TWO;
        case 3:
          return THREE;
        default:
          // Fall through to constructing a new Const
          break;
      }
    } else if (value instanceof Boolean) {
      return ((Boolean) value) ? ONE : ZERO;
    }
    return new Const(value);
  }

  /// True if this value can be stored in a register of the given type (or passed as an argument of
  /// the given type). If the types are primitive we require that they match; for object types we
  /// currently have no requirements.
  ///
  /// Used only for assertions.
  boolean isCompatible(Class<?> lhsType) {
    if (this == NONE) {
      // NONE values should never be used.
      return false;
    } else if (this == CodeValue.NULL) {
      // Null can be stored in any non-primitive.
      return !lhsType.isPrimitive();
    }
    Class<?> t = type();
    if (t == lhsType) {
      return true;
    } else if (lhsType.isPrimitive()) {
      // Anything can be coerced to a double, and we allow any int type to be coerced to a long
      // or any other int type; all other conversions should be explicit.
      return lhsType == double.class
          || (OpCodeType.isStoredAsInt(t)
              && (lhsType == long.class || OpCodeType.isStoredAsInt(lhsType)));
    } else {
      return !t.isPrimitive();
    }
  }

  /// True if the instructions emitted to push this value can throw an exception. Currently assumes
  /// that all Ops can throw an exception.
  public boolean canThrow() {
    return this instanceof Op.Result;
  }

  /// True if the instructions emitted to push this value may have an effect even if the value is
  /// discarded.
  boolean hasSideEffect() {
    return false;
  }

  /// Returns YES if this expression has exactly one occurrence of `register`, NO if it has more
  /// than one occurrence, and KEEP\_TRYING if it has no occurrences.
  public SubstitutionOutcome couldSubstitute(Register register, CodeValue value) {
    // Overridden by Op.Result
    assert this instanceof Register || this instanceof Const;
    return (this == register) ? SubstitutionOutcome.YES : SubstitutionOutcome.KEEP_TRYING;
  }

  /// Returns this CodeValue after replacing each occurrence of `register` with `value`.
  public CodeValue substitute(Register register, CodeValue value) {
    // Overridden by Op.Result
    assert this instanceof Register || this instanceof Const;
    return (this == register) ? value : this;
  }

  /// Convert this value to a String. If `useJvmLocals` is false, returns the same result as
  /// `toString()`; if it is true, registers are identified by [Register#jvmLocal()] rather than
  /// their index.
  public String toString(PrintOptions options) {
    return toString();
  }

  /// A distinguished CodeValue that should only be used when it is known that a Register will never
  /// be read without first being written.
  ///
  /// Sometimes the Java verifier will require that a Register be written even though you know the
  /// value being written will never be read. Using NONE in such situations may allow the optimizer
  /// to apply additional optimizations.
  public static final Const NONE = new Const(null);

  /// A constant CodeValue. Also implements ValueInfo; when used as a ValueInfo, indicates that the
  /// expression has this value.
  public static class Const extends CodeValue implements ValueInfo {
    /// The value. An object of type Number is interpreted as a primitive value, so there is
    /// currently no way to represent boxed numbers.
    public final Object value;

    /// Use [CodeValue#of] to create Const instances.
    private Const(Object value) {
      this.value = value;
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public @Nullable Class<?> type() {
      if (value == null) {
        // If you're looking at the type of NONE you're doing something wrong.
        assert this != NONE;
        return null;
      } else if (value instanceof Integer) {
        return int.class;
      } else if (value instanceof Long) {
        return long.class;
      } else if (value instanceof Double) {
        return double.class;
      } else {
        // Note that CodeValue.of() canonicalizes all Numbers to be int, long, or double, except for
        // the subclasses (like AtomicInteger) that aren't really numbers.
        assert !isJavaLangNumber(value);
        return value.getClass();
      }
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void push(Emitter emitter, Class<?> type) {
      if (OpCodeType.isStoredAsInt(type)) {
        emitter.pushI((this == NONE) ? 0 : (Integer) value);
      } else if (type == double.class) {
        emitter.pushD((this == NONE) ? 0 : ((Number) value).doubleValue());
      } else if (type == long.class) {
        if (this == NONE) {
          emitter.pushL(0);
        } else {
          Number n = (Number) value;
          assert n instanceof Integer || n instanceof Long;
          emitter.pushL(n.longValue());
        }
      } else if (type == float.class) {
        emitter.pushF((this == NONE) ? 0 : ((Number) value).floatValue());
      } else {
        emitter.pushX(value, type);
      }
    }

    @Override
    public ValueInfo info(IntFunction<ValueInfo> registerInfo) {
      return this;
    }

    @Override
    public CodeValue simplify(IntFunction<ValueInfo> registerInfo) {
      return this;
    }

    @Override
    public void getLive(boolean add, Bits.Builder live) {}

    @Override
    @SuppressWarnings("ReferenceEquality")
    public boolean containsValue(Object other) {
      return (this != NONE) && Objects.equals(value, other);
    }

    @Override
    public Number minimum() {
      return (Number) value;
    }

    @Override
    public Number maximum() {
      return (Number) value;
    }

    @Override
    public ValueInfo unionConst(Const constInfo) {
      throw new AssertionError();
    }

    @Override
    public ValueInfo removeConst(Const constInfo) {
      throw new AssertionError();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      } else if (this != NONE && other != NONE && other instanceof Const c) {
        // Because CodeValue.of() canonicalized Numbers, equals() here will do the right thing.
        return Objects.equals(value, c.value);
      } else {
        return false;
      }
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public String toString() {
      if (this == NONE) {
        return "none";
      } else if (value instanceof String s) {
        return StringUtil.escape(s);
      } else {
        return String.valueOf(value);
      }
    }
  }

  public static final Const ZERO = new Const(0);
  public static final Const ONE = new Const(1);
  public static final Const NEGATIVE_ONE = new Const(-1);
  public static final Const TWO = new Const(2);
  public static final Const THREE = new Const(3);
  public static final Const NULL = new Const(null);

  /// Instances of Thrown aren't real CodeValues, but can be returned from [CodeValue#simplify] to
  /// indicate that a CodeValue will always throw the given exception.
  public static class Thrown extends CodeValue {
    public final Throwable throwable;

    public Thrown(Throwable throwable) {
      this.throwable = throwable;
    }

    @Override
    public Class<?> type() {
      throw new AssertionError();
    }

    @Override
    public void push(Emitter emitter, Class<?> type) {
      throw new AssertionError();
    }

    @Override
    public ValueInfo info(IntFunction<ValueInfo> registerInfo) {
      throw new AssertionError();
    }

    @Override
    public CodeValue simplify(IntFunction<ValueInfo> registerInfo) {
      return this;
    }

    @Override
    public void getLive(boolean add, Bits.Builder live) {}

    public static final Thrown ARITHMETIC_EXCEPTION = new Thrown(new ArithmeticException());
  }

  /// Returns true if `v` is a [Thrown] with a Throwable of type `klass`.
  public static boolean isThrown(CodeValue v, Class<? extends Throwable> klass) {
    return (v instanceof Thrown thrown) && klass.isInstance(thrown.throwable);
  }
}
