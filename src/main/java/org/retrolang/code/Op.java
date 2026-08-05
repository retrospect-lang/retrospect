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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.retrolang.code.Block.SubstitutionOutcome;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeBuilder.PrintOptions;
import org.retrolang.code.CodeValue.Const;
import org.retrolang.code.ValueInfo.IntRange;
import org.retrolang.util.Bits;

/// An Op represents an operation that can be performed by the JVM, typically by a simple opcode or
/// method call. Each Op has fixed number of arguments of specified types, and a result type (which
/// may be void for Ops that return no result). An Op is used to construct an [Op.Result].
public class Op {

  /// An OpSimplifier is used to implement the [Op.Result#simplify] method.
  public interface OpSimplifier {
    /// Given the Op's (already simplified) arguments and what is known about the values of
    /// registers, returns a CodeValue that will always evaluate to the same value as `Op.Result(op,
    /// args)` but is simpler, or null if there is no simpler CodeValue.
    CodeValue simplify(ImmutableList<CodeValue> args, IntFunction<ValueInfo> registerInfo);
  }

  /// An OpInfo is used to implement the [Op.Result#info] method.
  public interface OpInfo {
    /// Given the Op's (already simplified) arguments and what is known about the values of
    /// registers, returns a ValueInfo that includes all possible values of `Op.Result(op, args)`.
    ValueInfo info(ImmutableList<CodeValue> args, IntFunction<ValueInfo> registerInfo);
  }

  /// An OpEmit is used to implement the [Op.Result#push] method.
  public interface OpEmit {
    void emit(Emitter emitter, Result result);

    default void emit(Emitter emitter, Result result, Class<?> type) {
      emit(emitter, result);
      emitter.coerce(result.type(), type);
    }
  }

  /// Used only for `toString()`.
  public final String name;

  /// The type of result returned by this Op, or `void.class` if the Op returns no result.
  public final Class<?> resultType;

  /// The argument types required by this Op.
  public final ImmutableList<Class<?>> argTypes;

  /// False if executing this Op and discarding any result is a no-op; otherwise true.
  public final boolean hasSideEffect;

  /// True if at least one element of [#simplifiers] depends on the `registerInfo` argument (most
  /// simplifiers don't, and knowing that can save us some pointless re-checking).
  public final boolean hasInfoSensitiveSimplifier;

  /// If non-null, this Op can be implemented by invoking `mh`.
  public final MethodHandle mh;

  /// If non-null, will emit the bytecode to implement this Op. If null, mh must be non-null and the
  /// emitted bytecode will invoke it.
  public final OpEmit opEmit;

  /// Implementations of [#simplify]. If there are multiple simplifiers they will be tried in order
  /// until one returns a non-null result.
  public final ImmutableList<OpSimplifier> simplifiers;

  /// If non-null, will be called to implement [#info].
  public final OpInfo resultInfo;

  /// A Builder is used to construct a new Op.
  public static class Builder {
    final String name;
    final Class<?> resultType;
    final ImmutableList<Class<?>> argTypes;
    final MethodHandle mh;
    final OpEmit opEmit;

    private boolean hasSideEffect = false;
    private boolean hasInfoSensitiveSimplifier = false;
    private final ImmutableList.Builder<OpSimplifier> simplifiers = ImmutableList.builder();
    private OpInfo resultInfo;

    public Builder(
        String name, Class<?> resultType, ImmutableList<Class<?>> argTypes, OpEmit opEmit) {
      this.name = name;
      this.resultType = resultType;
      this.argTypes = argTypes;
      this.mh = null;
      this.opEmit = opEmit;
    }

    public Builder(String name, MethodHandle mh, OpEmit opEmit) {
      this.name = name;
      this.resultType = mh.type().returnType();
      this.argTypes = ImmutableList.copyOf(mh.type().parameterArray());
      this.mh = mh;
      this.opEmit = opEmit;
    }

    public Builder(Op op) {
      this.name = op.name;
      this.resultType = op.resultType;
      this.argTypes = op.argTypes;
      this.hasSideEffect = op.hasSideEffect;
      this.hasInfoSensitiveSimplifier = op.hasInfoSensitiveSimplifier;
      this.mh = op.mh;
      this.opEmit = op.opEmit;
      this.simplifiers.addAll(op.simplifiers);
      this.resultInfo = op.resultInfo;
    }

    @CanIgnoreReturnValue
    public Builder hasSideEffect() {
      this.hasSideEffect = true;
      return this;
    }

    public Op build() {
      return new Op(this);
    }

    /// Adds a simplifier that calls the MethodHandle if the arguments are all constants. Should
    /// only be called if this Op has an associated MethodHandle.
    @CanIgnoreReturnValue
    public Builder withConstSimplifier() {
      assert mh != null;
      simplifiers.add(constSimplifier(mh));
      return this;
    }

    /// Adds a simplifier that calls the given function if the argument is a constant. Should only
    /// be called if this Op has a single argument.
    @CanIgnoreReturnValue
    public Builder withConstSimplifier(UnaryOperator<Object> constSimplifier) {
      assert argTypes.size() == 1;
      simplifiers.add(
          (args, infos) ->
              allConst(args)
                  ? CodeValue.of(constSimplifier.apply(args.get(0).constValue()))
                  : null);
      return this;
    }

    /// Adds a simplifier that calls the given function if the arguments are constants. Should only
    /// be called if this Op has two arguments.
    @CanIgnoreReturnValue
    public Builder withConstSimplifier(BinaryOperator<Object> constSimplifier) {
      assert argTypes.size() == 2;
      simplifiers.add(
          (args, infos) ->
              allConst(args)
                  ? CodeValue.of(
                      constSimplifier.apply(args.get(0).constValue(), args.get(1).constValue()))
                  : null);
      return this;
    }

    /// Adds a simplifier that calls the given function with the argument CodeValues. Should only be
    /// called if this Op has two arguments.
    @CanIgnoreReturnValue
    public Builder withSimplifier(BinaryOperator<CodeValue> binarySimplifier) {
      assert argTypes.size() == 2;
      simplifiers.add((args, infos) -> binarySimplifier.apply(args.get(0), args.get(1)));
      return this;
    }

    /// Adds a simplifier that depends only on the argument CodeValues.
    @CanIgnoreReturnValue
    public Builder withSimplifier(Function<ImmutableList<CodeValue>, CodeValue> simplifier) {
      simplifiers.add((args, infos) -> simplifier.apply(args));
      return this;
    }

    /// Adds a simplifier that depends on both the arguments and the register infos.
    @CanIgnoreReturnValue
    public Builder withOpSimplifier(OpSimplifier simplifier) {
      simplifiers.add(simplifier);
      // If you're using this entry point it's presumably because you depend on the infos
      this.hasInfoSensitiveSimplifier = true;
      return this;
    }

    /// Sets a function that will be called to implement [Op#info].
    @CanIgnoreReturnValue
    public Builder withInfo(OpInfo resultInfo) {
      this.resultInfo = resultInfo;
      return this;
    }

    /// Returns a const simplifier that invokes the given MethodHandle.
    private static OpSimplifier constSimplifier(MethodHandle mh) {
      return (args, infos) -> {
        if (!allConst(args)) {
          return null;
        }
        Object[] argValues = args.stream().map(CodeValue::constValue).toArray();
        Object result;
        try {
          result = mh.invokeWithArguments(argValues);
        } catch (Throwable t) {
          return new CodeValue.Thrown(t);
        }
        return CodeValue.of(result);
      };
    }

    private static boolean allConst(ImmutableList<CodeValue> args) {
      return args.stream().allMatch(arg -> arg instanceof Const);
    }
  }

  protected Op(Builder builder) {
    this.name = builder.name;
    this.resultType = builder.resultType;
    this.argTypes = builder.argTypes;
    this.hasSideEffect = builder.hasSideEffect || (builder.resultType == void.class);
    this.hasInfoSensitiveSimplifier = builder.hasInfoSensitiveSimplifier;
    this.mh = builder.mh;
    this.opEmit = builder.opEmit;
    this.simplifiers = builder.simplifiers.build();
    this.resultInfo = builder.resultInfo;
  }

  /// Returns a new CodeValue that applies this Op to the given arguments.
  public final CodeValue result(CodeValue... args) {
    return resultWithInfo(null, args);
  }

  /// Returns a new Block that applies this Op to the given arguments and discards any result.
  public final Block.NonTerminal block(CodeValue... args) {
    assert hasSideEffect;
    return new SetBlock(null, result(args));
  }

  /// Returns a new CodeValue that applies this Op to the given args. If `infoOverride` is non-null,
  /// asserts that the result satisfies the given ValueInfo.
  public CodeValue resultWithInfo(ValueInfo infoOverride, CodeValue... args) {
    return resultWithInfo(infoOverride, ImmutableList.copyOf(args));
  }

  /// Returns a new CodeValue that applies this Op to the given args. If `infoOverride` is non-null,
  /// asserts that the result satisfies the given ValueInfo.
  public CodeValue resultWithInfo(ValueInfo infoOverride, ImmutableList<CodeValue> args) {
    return resultWithInfo(infoOverride, args, i -> ValueInfo.ANY);
  }

  /// Returns a new CodeValue that applies this Op to the given args. If `infoOverride` is non-null,
  /// asserts that the result satisfies the given ValueInfo. If `registerInfo` is non-null, attempts
  /// to simplify the result and if successful returns the simplification instead.
  private CodeValue resultWithInfo(
      ValueInfo infoOverride, ImmutableList<CodeValue> args, IntFunction<ValueInfo> registerInfo) {
    if (registerInfo != null) {
      CodeValue result = simplify(args, registerInfo);
      if (result != null) {
        return result;
      }
    }
    return new Result(this, args, infoOverride);
  }

  /// Returns a ValueInfo that is satisfied by the result of this Op applied to the given args.
  public ValueInfo info(ImmutableList<CodeValue> args, IntFunction<ValueInfo> registerInfo) {
    return (resultInfo == null) ? ValueInfo.ANY : resultInfo.info(args, registerInfo);
  }

  /// If the result of this Op applied to the given args can be represented by a simpler CodeValue,
  /// returns it; otherwise returns null.
  @Nullable CodeValue simplify(ImmutableList<CodeValue> args, IntFunction<ValueInfo> registerInfo) {
    for (OpSimplifier simplifier : simplifiers) {
      CodeValue result = simplifier.simplify(args, registerInfo);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  /// Emits code to push the result of applying this Op to the given args.
  public void emit(Emitter emitter, CodeValue... args) {
    // It's too late to do the usual simplification, etc.; just create a Result and immediately
    // call push().
    new Result(this, ImmutableList.copyOf(args), null).push(emitter, resultType);
  }

  /// Returns an Op that reads the specified field from an object of the given Class.
  public static Op getField(Class<?> klass, String name, Class<?> type) {
    return forField(Opcodes.GETFIELD, klass, name, type, ImmutableList.of(klass));
  }

  /// Returns an Op that reads the specified static field.
  public static Op getStatic(Class<?> klass, String name, Class<?> type) {
    return forField(Opcodes.GETSTATIC, klass, name, type, ImmutableList.of());
  }

  private static Op forField(
      int opcode, Class<?> klass, String name, Class<?> type, ImmutableList<Class<?>> argTypes) {
    OpEmit emitter =
        new FieldEmit(
            opcode,
            org.objectweb.asm.Type.getInternalName(klass),
            name,
            org.objectweb.asm.Type.getDescriptor(type));
    return new Builder(klass.getSimpleName() + "." + name, type, argTypes, emitter).build();
  }

  /// Only valid on an Op returned by [#getField] or [#getStatic]; returns a Builder for an Op that
  /// writes the same field.
  public Builder setter() {
    FieldEmit emitter = (FieldEmit) this.opEmit;
    int newOpcode;
    ImmutableList<Class<?>> newArgTypes;
    switch (emitter.opCode) {
      case Opcodes.GETFIELD:
        newOpcode = Opcodes.PUTFIELD;
        newArgTypes = ImmutableList.of(argTypes.get(0), resultType);
        break;
      case Opcodes.GETSTATIC:
        newOpcode = Opcodes.PUTSTATIC;
        newArgTypes = ImmutableList.of(resultType);
        break;
      default:
        throw new IllegalArgumentException();
    }
    return new Builder(name + ".set", void.class, newArgTypes, emitter.withOpCode(newOpcode))
        .hasSideEffect();
  }

  /// Only valid on an Op returned by [#getField] with a non-primitive type; returns a Builder for
  /// an Op that writes null to the same field and returns its previous value (non-atomically).
  public Builder taker() {
    FieldEmit emitter = (FieldEmit) this.opEmit;
    Preconditions.checkArgument(emitter.opCode == Opcodes.GETFIELD && !resultType.isPrimitive());
    return new Builder(
            name + ".take", resultType, argTypes, emitter.withOpCode(FieldEmit.TAKE_OPCODE))
        .hasSideEffect();
  }

  /// Calls [Class#getDeclaredMethod], converting any (checked) ReflectiveOperationException to an
  /// (unchecked) LinkageError.
  public static Method getDeclaredMethod(Class<?> klass, String name, Class<?>... argTypes) {
    try {
      return klass.getDeclaredMethod(name, argTypes);
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("Shouldn't happen", e);
    }
  }

  /// Calls [Lookup#unreflect], converting any (checked) IllegalAccessException to an (unchecked)
  /// IllegalArgumentException.
  public static MethodHandle findMethod(Lookup lookup, Method method) {
    try {
      return lookup.unreflect(method);
    } catch (IllegalAccessException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /// Returns a Builder for an Op that calls the specified method, which must be public.
  public static Builder forMethod(Class<?> klass, String name, Class<?>... argTypes) {
    return forMethod(MethodHandles.publicLookup(), klass, name, argTypes);
  }

  /// Returns a Builder for an Op that calls the specified method, which must be accessible with the
  /// given Lookup.
  public static Builder forMethod(
      Lookup lookup, Class<?> klass, String name, Class<?>... argTypes) {
    return forMethod(lookup, getDeclaredMethod(klass, name, argTypes));
  }

  /// Returns a Builder for an Op that calls the specified method, which must be accessible with the
  /// given Lookup.
  public static Builder forMethod(Lookup lookup, Method method) {
    MethodHandle mh = findMethod(lookup, method);
    Class<?> c = method.getDeclaringClass();
    String name = c.getSimpleName() + "." + method.getName();
    String className = org.objectweb.asm.Type.getInternalName(c);
    String methodName = method.getName();
    String typeDescriptor = org.objectweb.asm.Type.getMethodDescriptor(method);
    boolean isInterface = c.isInterface();
    int opCode;
    if (Modifier.isStatic(method.getModifiers())) {
      opCode = Opcodes.INVOKESTATIC;
    } else if (isInterface) {
      opCode = Opcodes.INVOKEINTERFACE;
    } else {
      opCode = Opcodes.INVOKEVIRTUAL;
    }
    OpEmit opEmit =
        (emitter, result) -> {
          result.pushArgs(emitter);
          emitter.mv.visitMethodInsn(opCode, className, methodName, typeDescriptor, isInterface);
        };
    return new Builder(name, mh, opEmit);
  }

  /// Returns a Builder for an Op that invokes the given MethodHandle.
  public static Builder forMethodHandle(String name, MethodHandle mh) {
    return new Builder(name, mh, null);
  }

  @Override
  public String toString() {
    return name;
  }

  /// A CodeValue that applies an Op.
  public static class Result extends CodeValue {
    public final Op op;
    public final ImmutableList<CodeValue> args;

    /// If non-null, this is the ValueInfo that should be returned by [#info(IntFunction)].
    private final ValueInfo infoOverride;

    /// Do not call this directly; use [Op#result], [Op#resultWithInfo], or [Op#block].
    protected Result(Op op, ImmutableList<CodeValue> args, ValueInfo infoOverride) {
      assert op.argTypes.size() == args.size();
      assert IntStream.range(0, args.size())
          .allMatch(i -> args.get(i).isCompatible(op.argTypes.get(i)));
      this.op = op;
      this.args = args;
      this.infoOverride = infoOverride;
    }

    /// Emits instructions to push the arguments on the stack, in order, with the types expected by
    /// the Op.
    public void pushArgs(Emitter emitter) {
      for (int i = 0; i < args.size(); i++) {
        pushArg(emitter, i, op.argTypes.get(i));
      }
    }

    /// Emits instructions to a single argument on the stack.
    public void pushArg(Emitter emitter, int i, Class<?> type) {
      args.get(i).push(emitter, type);
    }

    @Override
    public Class<?> type() {
      return op.resultType;
    }

    @Override
    public ValueInfo info(IntFunction<ValueInfo> registerInfo) {
      return (infoOverride != null) ? infoOverride : op.info(args, registerInfo);
    }

    @Override
    public CodeValue simplify(IntFunction<ValueInfo> registerInfo) {
      CodeValue result = simplify(arg -> arg.simplify(registerInfo), registerInfo);
      if (result == this && op.hasInfoSensitiveSimplifier) {
        // This op/args combination has already been simplified once; unless its
        // simplification might depend on the registerInfo we can skip this check.
        CodeValue simplified = op.simplify(args, registerInfo);
        if (simplified != null) {
          return simplified;
        }
      }
      return result;
    }

    /// Applies `argSimplifier` to each of this Op's arguments. If all of them are returned
    /// unchanged, returns this; otherwise creates a copy with the simplified arguments.
    ///
    /// The arguments are simplified in reverse order; this usually doesn't matter, but one client
    /// (RcCodeBuilder.BackwardPass) relies on it.
    ///
    /// @param registerInfo if non-null and any of the arguments simplify, passed in the
    ///   [Op#simplify] call to check for further optimization
    public CodeValue simplify(
        Function<CodeValue, CodeValue> argSimplifier, IntFunction<ValueInfo> registerInfo) {
      CodeValue[] newArgs = null;
      for (int i = args.size() - 1; i >= 0; i--) {
        CodeValue arg = args.get(i);
        CodeValue newArg = argSimplifier.apply(arg);
        if (newArg == arg) {
          continue;
        }
        assert newArg.isCompatible(op.argTypes.get(i));
        if (newArgs == null) {
          newArgs = args.toArray(CodeValue[]::new);
        }
        newArgs[i] = newArg;
      }
      return (newArgs != null)
          ? op.resultWithInfo(infoOverride, ImmutableList.copyOf(newArgs), registerInfo)
          : this;
    }

    @Override
    boolean hasSideEffect() {
      return op.hasSideEffect || args.stream().anyMatch(CodeValue::hasSideEffect);
    }

    @Override
    public void push(Emitter emitter, Class<?> type) {
      int initialStackDepth = emitter.currentStackDepth();
      if (op.opEmit == null) {
        emitter.pushX(op.mh, MethodHandle.class);
        pushArgs(emitter);
        emitter.emitInvokeExact(op.mh);
        emitter.coerce(op.resultType, type);
      } else {
        op.opEmit.emit(emitter, this, type);
      }
      emitter.setStackDepth(
          initialStackDepth + (type == void.class ? 0 : OpCodeType.localSize(type)));
    }

    @Override
    public void getLive(boolean add, Bits.Builder live) {
      for (CodeValue arg : args) {
        arg.getLive(add, live);
      }
    }

    @Override
    public SubstitutionOutcome couldSubstitute(Register register, CodeValue value) {
      SubstitutionOutcome result = SubstitutionOutcome.KEEP_TRYING;
      for (CodeValue arg : args) {
        // We can substitute an arg of an op with a side effect (since the op isn't executed until
        // after the args are evaluated), but we can't substitute if one of an op's args has a
        // side effect (the ordering might be unpredictable).
        if (arg instanceof Result argResult && argResult.op.hasSideEffect) {
          return SubstitutionOutcome.NO;
        }
        result = result.combine(arg.couldSubstitute(register, value));
        if (result == SubstitutionOutcome.NO) {
          return SubstitutionOutcome.NO;
        }
      }
      return result;
    }

    @Override
    public CodeValue substitute(Register register, CodeValue value) {
      ImmutableList<CodeValue> newArgs =
          args.stream()
              .map(arg -> arg.substitute(register, value))
              .collect(ImmutableList.toImmutableList());
      return op.resultWithInfo(infoOverride, newArgs);
    }

    @Override
    public String toString(CodeBuilder.PrintOptions options) {
      return args.stream()
          .map(arg -> arg.toString(options))
          .collect(Collectors.joining(", ", op.name + "(", ")"));
    }

    @Override
    public String toString() {
      return toString(PrintOptions.DEFAULT);
    }
  }

  /// Emits the instructions to get, set, or take a field.
  private static class FieldEmit implements OpEmit {

    /// One of GETFIELD, PUTFIELD, GETSTATIC, PUTSTATIC, or TAKE\_OPCODE.
    final int opCode;

    final String className;
    final String fieldName;
    final String typeDescriptor;

    /// A flag value for [#opCode] to indicate that this Op was created by [#taker].
    static final int TAKE_OPCODE = -1;

    FieldEmit(int opCode, String className, String fieldName, String typeDescriptor) {
      this.opCode = opCode;
      this.className = className;
      this.fieldName = fieldName;
      this.typeDescriptor = typeDescriptor;
    }

    /// Returns a new FieldEmit with the given opCode; other fields are unchanged.
    FieldEmit withOpCode(int opCode) {
      return new FieldEmit(opCode, className, fieldName, typeDescriptor);
    }

    private void emitInstruction(Emitter emitter, int opCode) {
      assert opCode != TAKE_OPCODE;
      emitter.mv.visitFieldInsn(opCode, className, fieldName, typeDescriptor);
    }

    @Override
    public void emit(Emitter emitter, Result result) {
      result.pushArgs(emitter);
      // Everything except "take" is just a single instruction.
      if (opCode != TAKE_OPCODE) {
        emitInstruction(emitter, opCode);
        return;
      }
      // If the argument is a Register (or Const, although that seems unlikely) we can easily push
      // it a second time, but if it's a Result it's worth a DUP/SWAP to avoid doing it twice.
      boolean useDup = (result.args.get(0) instanceof Result);
      if (useDup) {
        emitter.mv.visitInsn(Opcodes.DUP);
      }
      emitInstruction(emitter, Opcodes.GETFIELD);
      if (useDup) {
        emitter.mv.visitInsn(Opcodes.SWAP);
      } else {
        result.pushArgs(emitter);
      }
      emitter.mv.visitInsn(Opcodes.ACONST_NULL);
      emitter.addToStackDepth(useDup ? 2 : 1);
      emitInstruction(emitter, Opcodes.PUTFIELD);
    }
  }

  /// Returns a new Builder for an Op that is implemented by the given OpEmit.
  public static Builder simple(
      String name, OpEmit emit, Class<?> resultType, Class<?>... argTypes) {
    return new Builder(name, resultType, ImmutableList.copyOf(argTypes), emit);
  }

  /// Returns a new Builder for an Op that is implemented by a simple instruction.
  public static Builder simple(String name, int opcode, Class<?> resultType, Class<?>... argTypes) {
    OpEmit emit = (emitter, result) -> emitOpcode(opcode, emitter, result);
    return simple(name, emit, resultType, argTypes);
  }

  private static void emitOpcode(int opcode, Emitter emitter, Result result) {
    result.pushArgs(emitter);
    emitter.mv.visitInsn(opcode);
  }

  /// An Op implemented by the IADD instruction.
  public static final Op ADD_INTS =
      simple("iAdd", Opcodes.IADD, int.class, int.class, int.class)
          .withSimplifier(
              (x, y) -> {
                if (x instanceof Const) {
                  return simplifyAdd(y, x.iValue());
                } else if (y instanceof Const) {
                  return simplifyAdd(x, y.iValue());
                }
                return null;
              })
          .withInfo(Op::iAddInfo)
          .build();

  /// An Op implemented by the INEG instruction.
  public static final Op NEGATE_INT =
      simple("iNeg", Opcodes.INEG, int.class, int.class).withConstSimplifier(x -> -(int) x).build();

  /// An Op implemented by the ISUB instruction.
  public static final Op SUBTRACT_INTS =
      simple("iSub", Opcodes.ISUB, int.class, int.class, int.class)
          .withSimplifier(
              (x, y) -> {
                if (y instanceof Const) {
                  return simplifyAdd(x, -y.iValue());
                } else if (CodeValue.ZERO.equals(x)) {
                  return NEGATE_INT.result(y);
                }
                return null;
              })
          .build();

  /// Returns a ValueInfo for `ADD_INTS.result(args.get(0), args.get(1))`.
  private static ValueInfo iAddInfo(
      ImmutableList<CodeValue> args, IntFunction<ValueInfo> registerInfo) {
    ValueInfo info0 = args.get(0).info(registerInfo);
    ValueInfo info1 = args.get(1).info(registerInfo);
    Integer min =
        addExact(
            asInt(info0.minimum(), Integer.MIN_VALUE), asInt(info1.minimum(), Integer.MIN_VALUE));
    Integer max =
        addExact(
            asInt(info0.maximum(), Integer.MAX_VALUE), asInt(info1.maximum(), Integer.MAX_VALUE));
    // Since IADD wraps around, if either bound overflows we've got nothing.
    return (min == null || max == null) ? ValueInfo.ANY : IntRange.of(min, max);
  }

  private static int asInt(Number n, int ifNull) {
    return (n != null) ? n.intValue() : ifNull;
  }

  private static @Nullable Integer addExact(int x, int y) {
    try {
      return Math.addExact(x, y);
    } catch (ArithmeticException e) {
      return null;
    }
  }

  /// Returns a CodeValue equivalent to `arg + add`, if it is possible to do so with something
  /// simpler than an ADD\_INTS wrapped around `arg`. Otherwise returns null.
  private static @Nullable CodeValue simplifyAdd(CodeValue arg, int add) {
    if (add == 0) {
      return arg;
    } else if (arg instanceof Const) {
      return CodeValue.of(arg.iValue() + add);
    } else if (arg instanceof Result r) {
      // If we add a or subtract a const, and then add or subtract a const to the result of that,
      // we can just combine the two constants.
      if (r.op == ADD_INTS || r.op == SUBTRACT_INTS) {
        CodeValue arg0 = r.args.get(0);
        CodeValue arg1 = r.args.get(1);
        if (arg0 instanceof Const) {
          return r.op.result(CodeValue.of(arg0.iValue() + add), arg1);
        } else if (arg1 instanceof Const) {
          return r.op.result(arg0, CodeValue.of(arg1.iValue() + (r.op == ADD_INTS ? add : -add)));
        }
      }
    }
    return null;
  }

  /// If either of the CodeValues is ZERO, returns the other; otherwise returns null. Used to
  /// simplify addition operations other than IADD, and for "addition-like" operations (e.g. IOR).
  private static @Nullable CodeValue simplifyAdd(CodeValue x, CodeValue y) {
    if (CodeValue.ZERO.equals(x)) {
      return y;
    } else if (CodeValue.ZERO.equals(y)) {
      return x;
    }
    return null;
  }

  /// If `y` is ZERO, returns `x`; otherwise returns null. Used to simplify subtraction operations
  /// other than ISUB.
  private static @Nullable CodeValue simplifySubtract(CodeValue x, CodeValue y) {
    if (CodeValue.ZERO.equals(y)) {
      return x;
    }
    return null;
  }

  /// If either of the CodeValues is ONE, returns the other; if either is ZERO, returns ZERO.
  /// Otherwise returns null. Used to simplify multiplication operations.
  private static @Nullable CodeValue simplifyMultiply(CodeValue x, CodeValue y) {
    if (CodeValue.ONE.equals(x) || CodeValue.ZERO.equals(y)) {
      return y;
    } else if (CodeValue.ONE.equals(y) || CodeValue.ZERO.equals(x)) {
      return x;
    }
    return null;
  }

  /// If `y` is ONE, returns `x`; if `y` is ZERO returns ARITHMETIC\_EXCEPTION; otherwise returns
  /// null. Used to simplify divide operations.
  private static @Nullable CodeValue simplifyDivide(CodeValue x, CodeValue y) {
    if (CodeValue.ONE.equals(y)) {
      return x;
    } else if (CodeValue.ZERO.equals(y)) {
      return CodeValue.Thrown.ARITHMETIC_EXCEPTION;
    }
    // TODO: If x is zero and we have info that y is non-zero we could return zero here.
    // Also if x is equal to y and we have info that they're non-zero we could return one.
    // Would either of those be useful?
    return null;
  }

  /// If `y` is ONE or NEGATIVE\_ONE, returns ZERO; if `y` is ZERO returns ARITHMETIC\_EXCEPTION;
  /// otherwise returns null. Used to simplify modulo operations.
  private static @Nullable CodeValue simplifyMod(CodeValue x, CodeValue y) {
    if (CodeValue.ONE.equals(y) || CodeValue.NEGATIVE_ONE.equals(y)) {
      return CodeValue.ZERO;
    } else if (CodeValue.ZERO.equals(y)) {
      return CodeValue.Thrown.ARITHMETIC_EXCEPTION;
    }
    // TODO: If x is zero and we have info that y is non-zero we could return zero here.
    // Also if x is equal to y and we have info that they're non-zero we could return zero.
    // Would either of those be useful?
    return null;
  }

  /// If either of the CodeValues is ZERO, returns ZERO; otherwise returns null. Used to simplify
  /// IAND and LAND.
  private static @Nullable CodeValue simplifyZeroToZero(CodeValue x, CodeValue y) {
    if (CodeValue.ZERO.equals(x) || CodeValue.ZERO.equals(y)) {
      return CodeValue.ZERO;
    }
    return null;
  }

  /// If either of the two CodeValues is ZERO, returns `x`; otherwise returns null. Used to simplify
  /// shift and rotation operations.
  private static @Nullable CodeValue simplifyShift(CodeValue x, CodeValue y) {
    if (CodeValue.ZERO.equals(x) || CodeValue.ZERO.equals(y)) {
      return x;
    }
    return null;
  }

  /// An Op implemented by the L2I instruction.
  public static final Op LONG_TO_INT =
      simple("toInt", Opcodes.L2I, int.class, long.class)
          .withConstSimplifier(x -> ((Number) x).intValue())
          .build();

  /// An Op implemented by the IMUL instruction.
  public static final Op MULTIPLY_INTS =
      simple("iMul", Opcodes.IMUL, int.class, int.class, int.class)
          .withSimplifier(Op::simplifyMultiply)
          .withConstSimplifier((x, y) -> ((int) x) * ((int) y))
          .build();

  /// An Op implemented by the IAND instruction.
  public static final Op BIT_AND_INTS =
      simple("iAnd", Opcodes.IAND, int.class, int.class, int.class)
          .withSimplifier(Op::simplifyZeroToZero)
          .withConstSimplifier((x, y) -> ((int) x) & ((int) y))
          .build();

  /// An Op implemented by the IOR instruction.
  public static final Op BIT_OR_INTS =
      simple("iOr", Opcodes.IOR, int.class, int.class, int.class)
          .withSimplifier(Op::simplifyAdd)
          .withConstSimplifier((x, y) -> ((int) x) | ((int) y))
          .build();

  /// An Op implemented by the IXOR instruction.
  public static final Op BIT_XOR_INTS =
      simple("iXor", Opcodes.IXOR, int.class, int.class, int.class)
          .withSimplifier(Op::simplifyAdd)
          .withConstSimplifier((x, y) -> ((int) x) ^ ((int) y))
          .build();

  /// An Op implemented by the LAND instruction.
  public static final Op BIT_AND_LONGS =
      simple("lAnd", Opcodes.LAND, long.class, long.class, long.class)
          .withSimplifier(Op::simplifyZeroToZero)
          .withConstSimplifier((x, y) -> asLong(x) & asLong(y))
          .build();

  /// An Op implemented by the [Math#addExact] method.
  public static final Op ADD_INTS_EXACT =
      forMethod(Math.class, "addExact", int.class, int.class)
          .withSimplifier(Op::simplifyAdd)
          .withConstSimplifier()
          .build();

  /// An Op implemented by the [Math#subtractExact] method.
  public static final Op SUBTRACT_INTS_EXACT =
      forMethod(Math.class, "subtractExact", int.class, int.class)
          .withSimplifier(Op::simplifySubtract)
          .withConstSimplifier()
          .build();

  /// An Op implemented by the [Math#multiplyExact] method.
  public static final Op MULTIPLY_INTS_EXACT =
      forMethod(Math.class, "multiplyExact", int.class, int.class)
          .withSimplifier(Op::simplifyMultiply)
          .withConstSimplifier()
          .build();

  /// An Op implemented by the [Math#negateExact] method.
  public static final Op NEGATE_INT_EXACT =
      forMethod(Math.class, "negateExact", int.class).withConstSimplifier().build();

  /// An Op implemented by the [Math#absExact] method.
  public static final Op ABS_INT_EXACT =
      forMethod(Math.class, "absExact", int.class).withConstSimplifier().build();

  /// An Op implemented by the [Math#toIntExact] method.
  public static final Op LONG_TO_INT_EXACT =
      forMethod(Math.class, "toIntExact", long.class).withConstSimplifier().build();

  /// An Op implemented by the ISHL instruction.
  public static final Op SHIFT_LEFT_INT =
      simple("iShl", Opcodes.ISHL, int.class, int.class, int.class)
          .withSimplifier(Op::simplifyShift)
          .withConstSimplifier((x, y) -> ((int) x) << ((int) y))
          .build();

  /// An Op implemented by the [Integer#rotateLeft] method.
  public static final Op ROTATE_LEFT_INT =
      forMethod(Integer.class, "rotateLeft", int.class, int.class)
          .withSimplifier(Op::simplifyShift)
          .withConstSimplifier((x, y) -> Integer.rotateLeft((int) x, (int) y))
          .build();

  /// An Op implemented by the LSHL instruction.
  public static final Op SHIFT_LEFT_LONG =
      simple("lShl", Opcodes.LSHL, long.class, long.class, int.class)
          .withSimplifier(Op::simplifyShift)
          .withConstSimplifier((x, y) -> asLong(x) << ((int) y))
          .build();

  /// An Op implemented by the LSHR instruction.
  public static final Op SHIFT_RIGHT_LONG =
      simple("lShr", Opcodes.LSHR, long.class, long.class, int.class)
          .withSimplifier(Op::simplifyShift)
          .withConstSimplifier((x, y) -> asLong(x) >> ((int) y))
          .build();

  /// An Op implemented by the [Long#rotateLeft] method.
  public static final Op ROTATE_LEFT_LONG =
      forMethod(Long.class, "rotateLeft", long.class, int.class)
          .withSimplifier(Op::simplifyShift)
          .withConstSimplifier((x, y) -> Long.rotateLeft(asLong(x), (int) y))
          .build();

  /// An Op implemented by the IDIV instruction.
  public static final Op DIV_INTS =
      simple("iDiv", Opcodes.IDIV, int.class, int.class, int.class)
          .withSimplifier(Op::simplifyDivide)
          // simplifyDivide() handled y == 0, so this should be safe
          .withConstSimplifier((x, y) -> ((int) x) / ((int) y))
          .build();

  /// An Op implemented by the IREM instruction.
  public static final Op MOD_INTS =
      simple("iMod", Opcodes.IREM, int.class, int.class, int.class)
          .withSimplifier(Op::simplifyMod)
          // simplifyMod() handled y == 0, so this should be safe
          .withConstSimplifier((x, y) -> ((int) x) % ((int) y))
          .build();

  /// An Op implemented by the LADD instruction.
  public static final Op ADD_LONGS =
      simple("lAdd", Opcodes.LADD, long.class, long.class, long.class)
          .withSimplifier(Op::simplifyAdd)
          .withConstSimplifier((x, y) -> asLong(x) + asLong(y))
          .build();

  /// An Op implemented by the LSUB instruction.
  public static final Op SUBTRACT_LONGS =
      simple("lSub", Opcodes.LSUB, long.class, long.class, long.class)
          .withSimplifier(Op::simplifySubtract)
          .withConstSimplifier((x, y) -> asLong(x) - asLong(y))
          .build();

  /// An Op implemented by the LMUL instruction.
  public static final Op MULTIPLY_LONGS =
      simple("lMul", Opcodes.LMUL, long.class, long.class, long.class)
          .withSimplifier(Op::simplifyMultiply)
          .withConstSimplifier((x, y) -> asLong(x) * asLong(y))
          .build();

  /// An Op implemented by the D2I instruction.
  public static final Op DOUBLE_TO_INT =
      simple("d2i", Opcodes.D2I, int.class, double.class)
          .withConstSimplifier(x -> ((Number) x).intValue())
          .build();

  /// An Op implemented by the D2L instruction.
  public static final Op DOUBLE_TO_LONG =
      simple("d2l", Opcodes.D2L, long.class, double.class)
          .withConstSimplifier(x -> ((Number) x).longValue())
          .build();

  private static double asDouble(Object x) {
    return ((Number) x).doubleValue();
  }

  private static long asLong(Object x) {
    return ((Number) x).longValue();
  }

  /// An Op implemented by the DADD instruction.
  public static final Op ADD_DOUBLES =
      simple("dAdd", Opcodes.DADD, double.class, double.class, double.class)
          .withSimplifier(Op::simplifyAdd)
          .withConstSimplifier((x, y) -> asDouble(x) + asDouble(y))
          .build();

  /// An Op implemented by the DNEG instruction.
  public static final Op NEGATE_DOUBLE =
      simple("dNeg", Opcodes.DNEG, double.class, double.class)
          .withConstSimplifier(x -> -asDouble(x))
          .build();

  /// An Op implemented by the [Math#abs(double)] method.
  public static final Op ABS_DOUBLE =
      forMethod(Math.class, "abs", double.class).withConstSimplifier().build();

  /// An Op implemented by the DSUB instruction.
  public static final Op SUBTRACT_DOUBLES =
      simple("dSub", Opcodes.DSUB, double.class, double.class, double.class)
          .withSimplifier(Op::simplifySubtract)
          .withConstSimplifier((x, y) -> asDouble(x) - asDouble(y))
          .build();

  /// An Op implemented by the DMUL instruction.
  public static final Op MULTIPLY_DOUBLES =
      simple("dMul", Opcodes.DMUL, double.class, double.class, double.class)
          .withSimplifier(Op::simplifyMultiply)
          .withConstSimplifier((x, y) -> asDouble(x) * asDouble(y))
          .build();

  /// An Op implemented by the DDIV instruction.
  public static final Op DIV_DOUBLES =
      simple("dDiv", Opcodes.DDIV, double.class, double.class, double.class)
          .withSimplifier(Op::simplifyDivide)
          .withConstSimplifier((x, y) -> asDouble(x) / asDouble(y))
          .build();

  /// An Op implemented by the ARRAYLENGTH instruction on an int[].
  public static final Op INT_ARRAY_LENGTH =
      simple("lengthInt[]", Opcodes.ARRAYLENGTH, int.class, int[].class)
          .withConstSimplifier(Array::getLength)
          .build();

  /// An Op implemented by the IALOAD instruction.
  public static final Op INT_ARRAY_ELEMENT =
      simple("int[]", Opcodes.IALOAD, int.class, int[].class, int.class)
          .withConstSimplifier((ints, index) -> ((int[]) ints)[(int) index])
          .build();

  /// An Op implemented by the IASTORE instruction.
  public static final Op SET_INT_ARRAY_ELEMENT =
      simple("setInt[]", Opcodes.IASTORE, void.class, int[].class, int.class, int.class).build();

  /// An Op implemented by the BALOAD instruction, except that the result is in [0..255] rather than
  /// [-128..127].
  public static final Op UINT8_ARRAY_ELEMENT =
      simple("uint8[]", Op::emitUint8ArrayElement, int.class, byte[].class, int.class)
          .withConstSimplifier((bytes, index) -> ((byte[]) bytes)[(int) index] & 255)
          .withInfo((cvs, infos) -> IntRange.UINT8)
          .build();

  private static void emitUint8ArrayElement(Emitter emitter, Result result) {
    result.pushArgs(emitter);
    emitter.mv.visitInsn(Opcodes.BALOAD);
    emitter.pushI(255);
    emitter.mv.visitInsn(Opcodes.IAND);
  }

  /// An Op implemented by the BASTORE instruction.
  public static final Op SET_UINT8_ARRAY_ELEMENT =
      simple("setUint8[]", Opcodes.BASTORE, void.class, byte[].class, int.class, int.class).build();

  /// An Op implemented by the AALOAD instruction.
  public static final Op OBJ_ARRAY_ELEMENT =
      simple("Object[]", Opcodes.AALOAD, Object.class, Object[].class, int.class)
          .withConstSimplifier((objs, index) -> ((Object[]) objs)[(int) index])
          .build();

  /// An Op implemented by the AASTORE instruction.
  public static final Op SET_OBJ_ARRAY_ELEMENT =
      simple("setObject[]", Opcodes.AASTORE, void.class, Object[].class, int.class, Object.class)
          .build();

  /// An Op implemented by the [Objects#equals] method.
  public static final Op EQUAL =
      forMethod(Objects.class, "equals", Object.class, Object.class).withConstSimplifier().build();
}
