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
import java.util.function.Supplier;
import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Register;
import org.retrolang.code.TestBlock;
import org.retrolang.code.TestBlock.IsEq;
import org.retrolang.code.TestBlock.IsLessThan;
import org.retrolang.impl.Err.BuiltinException;

/**
 * A Condition represents a boolean value. There are two constant Conditions (TRUE and FALSE); all
 * other Conditions depend on one or more RValues and so will only be determined when the generated
 * code is executed.
 */
public abstract class Condition {

  /** The Condition that is always true. */
  public static final Condition TRUE = new Const(true);

  /** The Condition that is always false. */
  public static final Condition FALSE = new Const(false);

  /** Returns {@link #TRUE} or @link #FALSE}. */
  public static Condition of(boolean x) {
    return x ? TRUE : FALSE;
  }

  /**
   * Adds one or more blocks that branch to {@code elseBranch} if the Condition is false, and fall
   * through if it is true.
   */
  public abstract void addTest(CodeGen codeGen, FutureBlock elseBranch);

  /** Should only be called on {@link #TRUE} or @link #FALSE}; returns the corresponding boolean. */
  public boolean asBoolean() {
    return ((Const) this).value;
  }

  /** Like {@code Runnable}, except that run() may throw a BuiltinException. */
  public interface RunnableExcept {
    void run() throws BuiltinException;
  }

  /** Like {@code Supplier<Value>}, except that get() may throw a BuiltinException. */
  public interface ValueSupplier {
    Value get() throws BuiltinException;
  }

  /** Executes {@code ifTrue} if this Condition is true, {@code ifFalse} if it is not. */
  public void test(Runnable ifTrue, Runnable ifFalse) {
    // Just use testExcept(), which should not throw a BuiltinException if the Runnables don't.
    try {
      testExcept(ifTrue::run, ifFalse::run);
    } catch (BuiltinException e) {
      throw new AssertionError(e);
    }
  }

  /** Executes {@code ifTrue} if this Condition is true, {@code ifFalse} if it is not. */
  public void testExcept(RunnableExcept ifTrue, RunnableExcept ifFalse) throws BuiltinException {
    // Const overrides this method, so if we get here we must be generating code.
    // When generating code we'll emit blocks to test this Condition, then run ifTrue to emit the
    // blocks that should be run if the Condition is true and ifFalse to emit the blocks that
    // should be run if the Condition is false (i.e. a single call to testExcept() will call both
    // ifTrue and ifFalse).
    CodeGen codeGen = TState.get().codeGen();
    if (!codeGen.cb.nextIsReachable()) {
      return;
    }
    FutureBlock elseBranch = new FutureBlock();
    CodeGen.EscapeState savedEscape = codeGen.escapeState();
    addTest(codeGen, elseBranch);
    // It is possible that the Condition is known to always be false (e.g. if it tests a register
    // whose value is known); if so, we can skip the ifTrue call.
    if (codeGen.cb.nextIsReachable()) {
      try {
        ifTrue.run();
      } catch (BuiltinException err) {
        // Code generation usually calls escape() when it reaches a state that it can't handle, but
        // it may also just throw a BuiltinException.
        codeGen.escape();
      }
    }
    // If the elseBranch is unreachable the Condition is known to always be true, so there is
    // nothing more to do.  Otherwise we emit the ifFalse blocks.
    if (elseBranch.hasInLink()) {
      FutureBlock done = codeGen.cb.swapNext(elseBranch);
      // Running ifTrue might have changed or invalidated the escape; restore the one that was valid
      // before we ran it.
      CodeGen.EscapeState doneEscape = codeGen.escapeState();
      codeGen.restore(savedEscape);
      try {
        ifFalse.run();
      } catch (BuiltinException err) {
        codeGen.escape();
      }
      if (done.hasInLink()) {
        codeGen.cb.mergeNext(done);
        // If both branches left the escape unchanged, we can keep it; otherwise we should
        // invalidate it.
        codeGen.merge(doneEscape);
      }
    }
  }

  /** Returns {@code ifTrue} if this Condition is true, {@code ifFalse} if it is not. */
  public Value choose(Value ifTrueValue, Value ifFalseValue) {
    return new ConditionalValue(this, () -> ifTrueValue, () -> ifFalseValue) {
      @Override
      public Condition isa(VmType type) {
        // Implementing Value.isa(VmType) enables us to method resolution if one of these is passed
        // as a function argument.
        return Condition.this.ternary(ifTrueValue.isa(type), ifFalseValue.isa(type));
      }
    };
  }

  /** Returns {@code ifTrue.get()} if this Condition is true, {@code ifFalse.get()} if it is not. */
  public Value choose(Supplier<Value> ifTrue, Supplier<Value> ifFalse) {
    return new ConditionalValue(this, ifTrue::get, ifFalse::get);
  }

  /** Returns {@code ifTrue.get()} if this Condition is true, {@code ifFalse.get()} if it is not. */
  public Value chooseExcept(ValueSupplier ifTrue, ValueSupplier ifFalse) throws BuiltinException {
    return new ConditionalValue(this, ifTrue, ifFalse);
  }

  /**
   * Returns a CodeValue equal to {@code ifTrue} if this Condition is true, {@code ifFalse} if it is
   * not. May allocate a new register of the specified type to hold the result.
   */
  public CodeValue choose(CodeGen codeGen, CodeValue ifTrue, CodeValue ifFalse, Class<?> type) {
    Register result = codeGen.cb.newRegister(type);
    test(() -> codeGen.emitSet(result, ifTrue), () -> codeGen.emitSet(result, ifFalse));
    return result;
  }

  /** Returns {@code Core.TRUE} if this Condition is true, {@code Core.FALSE} if it is not. */
  @RC.Singleton
  public Value asValue() {
    return choose(Core.TRUE, Core.FALSE);
  }

  /** Returns a Condition that is true if either this Condition or {@code other} is true. */
  public Condition or(Condition other) {
    return ternary(TRUE, other);
  }

  /**
   * Returns a Condition that is true if either this Condition or {@code other.get()} is true. Does
   * not call {@code other.get()} if this is {@link #TRUE}.
   */
  public Condition or(Supplier<Condition> other) {
    return or(other.get());
  }

  /** Returns a Condition that is true if both this Condition and {@code other} are true. */
  public Condition and(Condition other) {
    return ternary(other, FALSE);
  }

  /**
   * Returns a Condition that is true if both this Condition and {@code other.get()} are true. Does
   * not call {@code other.get()} if this is {@link #FALSE}.
   */
  public Condition and(Supplier<Condition> other) {
    return and(other.get());
  }

  /** Returns a Condition that is true if this Condition is false. */
  public Condition not() {
    return new NegatedCondition(this);
  }

  /**
   * Returns a Condition that is equivalent to {@code onTrue} if this is true or {@code onFalse} if
   * this is false.
   */
  public Condition ternary(Condition onTrue, Condition onFalse) {
    if (onTrue == this) {
      onTrue = TRUE;
    }
    if (onFalse == this) {
      onFalse = FALSE;
    }
    if (onTrue == onFalse) {
      return onTrue;
    } else if (onTrue == TRUE && onFalse == FALSE) {
      return this;
    } else if (onTrue == FALSE && onFalse == TRUE) {
      return not();
    } else {
      return new TernaryCondition(this, onTrue, onFalse);
    }
  }

  /**
   * Returns a Condition that is equivalent to {@code onTrue.get()} if this is true or to {@code
   * onFalse.get()} if this is false. If this is a constant condition, only evaluates the relevant
   * Supplier.
   */
  public Condition ternary(Supplier<Condition> onTrue, Supplier<Condition> onFalse) {
    return ternary(onTrue.get(), onFalse.get());
  }

  /**
   * Returns a Condition that is true if {@code v} is {@code Core.TRUE} and false if {@code v} is
   * {@code Core.FALSE}; throws a BuiltinException if {@code v} is some other value.
   */
  public static Condition fromBoolean(Value v) throws BuiltinException {
    if (v instanceof RValue) {
      return new Condition() {
        @Override
        public void addTest(CodeGen codeGen, FutureBlock elseBranch) {
          // If v is False, go to the elseBranch
          v.is(Core.FALSE).not().addTest(codeGen, elseBranch);
          // If v is True, fall through; otherwise escape.
          codeGen.escapeUnless(v.is(Core.TRUE));
        }
      };
    } else {
      return of(BuiltinMethod.testBoolean(v));
    }
  }

  /**
   * Returns a Condition that is implemented by emitting the given TestBlock (since {@link #addTest}
   * may be called more than once, we need a TestBlock supplier).
   */
  public static Condition fromTest(Supplier<TestBlock> test) {
    return new Condition() {
      @Override
      public void addTest(CodeGen codeGen, FutureBlock elseBranch) {
        test.get().setBranch(false, elseBranch).addTo(codeGen.cb);
      }
    };
  }

  /** Returns a Condition that is true if {@code v1} and {@code v2} are equal. */
  public static Condition equal(Value v1, Value v2) {
    if (v1 instanceof RValue || v2 instanceof RValue) {
      return new Condition() {
        @Override
        public void addTest(CodeGen codeGen, FutureBlock elseBranch) {
          emitEqual(codeGen, v1, v2, elseBranch);
        }
      };
    } else {
      return Condition.of(v1.equals(v2));
    }
  }

  /**
   * Returns a Condition that is true if {@code v1} and {@code v2} are equal; both must be of type
   * int.
   */
  public static Condition intEq(CodeValue v1, CodeValue v2) {
    if ((v1 instanceof CodeValue.Const) && (v2 instanceof CodeValue.Const)) {
      return Condition.of(v1.iValue() == v2.iValue());
    }
    return fromTest(() -> new IsEq(OpCodeType.INT, v1, v2));
  }

  /**
   * Returns a Condition that is true if {@code v} (which must be of type int) is not zero (or is
   * true, if {@code v} represents a boolean).
   */
  public static Condition isNonZero(CodeValue v) {
    return intEq(v, CodeValue.ZERO).not();
  }

  /**
   * Returns a Condition that is true if the CodeValue returned by {@code supplier} (which must be
   * of type int) is not zero (or is true, if it represents a boolean).
   */
  public static Condition isNonZero(Function<CodeGen, CodeValue> supplier) {
    return new Condition() {
      @Override
      public void addTest(CodeGen codeGen, FutureBlock elseBranch) {
        CodeValue v = supplier.apply(codeGen);
        new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, v, CodeValue.ZERO)
            .setBranch(true, elseBranch)
            .addTo(codeGen.cb);
      }
    };
  }

  /**
   * Returns a Condition that is true if {@code v1} is less than {@code v2}; both must be of type
   * int.
   */
  public static Condition intLessThan(CodeValue v1, CodeValue v2) {
    if ((v1 instanceof CodeValue.Const) && (v2 instanceof CodeValue.Const)) {
      return Condition.of(v1.iValue() < v2.iValue());
    }
    return fromTest(() -> new IsLessThan(OpCodeType.INT, v1, v2));
  }

  /**
   * Returns a Condition that is true if {@code v1} is less than or equal to {@code v2}; both must
   * be of type int.
   */
  public static Condition intLessOrEq(CodeValue v1, CodeValue v2) {
    return intLessThan(v2, v1).not();
  }

  /**
   * Returns a Condition that is true if {@code v1} is less than {@code v2}; both must be of type
   * int or long.
   */
  public static Condition longLessThan(CodeValue v1, CodeValue v2) {
    if ((v1 instanceof CodeValue.Const) && (v2 instanceof CodeValue.Const)) {
      return Condition.of(v1.lValue() < v2.lValue());
    }
    return fromTest(() -> new IsLessThan(OpCodeType.LONG, v1, v2));
  }

  /** Returns a Condition that is true if {@code v} is null. */
  public static Condition isNull(CodeValue v) {
    if (v instanceof CodeValue.Const c) {
      return Condition.of(c.value == null);
    }
    return fromTest(() -> new IsEq(OpCodeType.OBJ, v, CodeValue.NULL));
  }

  /**
   * Returns a Condition that is true if {@code v1} is equal to {@code v2}; both arguments must be
   * numbers.
   */
  public static Condition numericEq(Value v1, Value v2) {
    if (v1 == v2) {
      return Condition.TRUE;
    } else if (v1 instanceof RValue || v2 instanceof RValue) {
      return new NumericComparison(v1, v2, true);
    } else if (v1 instanceof NumValue.I && v2 instanceof NumValue.I) {
      return Condition.of(NumValue.asInt(v1) == NumValue.asInt(v2));
    } else {
      return Condition.of(NumValue.asDouble(v1) == NumValue.asDouble(v2));
    }
  }

  /**
   * Returns a Condition that is true if {@code v1} is less than {@code v2}; both arguments must be
   * numbers.
   */
  public static Condition numericLessThan(Value v1, Value v2) {
    if (v1 == v2) {
      return Condition.FALSE;
    } else if (v1 instanceof RValue || v2 instanceof RValue) {
      return new NumericComparison(v1, v2, false);
    } else if (v1 instanceof NumValue.I && v2 instanceof NumValue.I) {
      return Condition.of(NumValue.asInt(v1) < NumValue.asInt(v2));
    } else {
      return Condition.of(NumValue.asDouble(v1) < NumValue.asDouble(v2));
    }
  }

  /**
   * Returns a Condition that is true if {@code v1} is less than or equal to {@code v2}; both
   * arguments must be numbers.
   */
  public static Condition numericLessOrEq(Value v1, Value v2) {
    return numericLessThan(v2, v1).not();
  }

  private static class Const extends Condition {
    final boolean value;

    private Const(boolean value) {
      this.value = value;
    }

    @Override
    public Condition not() {
      return value ? FALSE : TRUE;
    }

    @Override
    public void test(Runnable ifTrue, Runnable ifFalse) {
      if (value) {
        ifTrue.run();
      } else {
        ifFalse.run();
      }
    }

    @Override
    public void testExcept(RunnableExcept ifTrue, RunnableExcept ifFalse) throws BuiltinException {
      if (value) {
        ifTrue.run();
      } else {
        ifFalse.run();
      }
    }

    @Override
    public Value asValue() {
      return value ? Core.TRUE : Core.FALSE;
    }

    @Override
    public Value choose(Value ifTrue, Value ifFalse) {
      // If ifTrue or ifFalse is refCounted this is a likely refCount leak (and you should have
      // been using one of the Supplier versions).  Transient values are OK.
      assert RefCounted.isTransient(ifTrue) || !RefCounted.isRefCounted(ifTrue);
      assert RefCounted.isTransient(ifFalse) || !RefCounted.isRefCounted(ifFalse);
      return value ? ifTrue : ifFalse;
    }

    @Override
    public Value choose(Supplier<Value> ifTrue, Supplier<Value> ifFalse) {
      return (value ? ifTrue : ifFalse).get();
    }

    @Override
    public Value chooseExcept(ValueSupplier ifTrue, ValueSupplier ifFalse) throws BuiltinException {
      return (value ? ifTrue : ifFalse).get();
    }

    @Override
    public CodeValue choose(CodeGen codeGen, CodeValue ifTrue, CodeValue ifFalse, Class<?> type) {
      return value ? ifTrue : ifFalse;
    }

    @Override
    public Condition or(Condition other) {
      return value ? this : other;
    }

    @Override
    public Condition or(Supplier<Condition> other) {
      return value ? this : other.get();
    }

    @Override
    public Condition and(Condition other) {
      return value ? other : this;
    }

    @Override
    public Condition and(Supplier<Condition> other) {
      return value ? other.get() : this;
    }

    @Override
    public Condition ternary(Condition onTrue, Condition onFalse) {
      return value ? onTrue : onFalse;
    }

    @Override
    public Condition ternary(Supplier<Condition> onTrue, Supplier<Condition> onFalse) {
      return (value ? onTrue : onFalse).get();
    }

    @Override
    public void addTest(CodeGen codeGen, FutureBlock elseBranch) {
      // We usually don't get here because of the overrides above, but if we do it's easy to
      // implement.
      if (!value) {
        codeGen.cb.branchTo(elseBranch);
      }
    }

    @Override
    public String toString() {
      return value ? "TRUE" : "FALSE";
    }
  }

  private static class NegatedCondition extends Condition {
    final Condition inner;

    NegatedCondition(Condition inner) {
      // Const and NegatedCondition provide their own not() implementations, so shouldn't end up
      // here.
      assert !(inner instanceof Const || inner instanceof NegatedCondition);
      this.inner = inner;
    }

    @Override
    public Condition ternary(Condition onTrue, Condition onFalse) {
      return inner.ternary(onFalse, onTrue);
    }

    @Override
    public void addTest(CodeGen codeGen, FutureBlock elseBranch) {
      FutureBlock innerElse = new FutureBlock();
      inner.addTest(codeGen, innerElse);
      codeGen.cb.branchTo(elseBranch);
      codeGen.cb.setNext(innerElse);
    }

    @Override
    public Condition not() {
      return inner;
    }
  }

  private static class TernaryCondition extends Condition {
    final Condition test;
    final Condition onTrue;
    final Condition onFalse;

    TernaryCondition(Condition test, Condition onTrue, Condition onFalse) {
      assert test != null && onTrue != null && onFalse != null;
      assert !(test instanceof Const || test instanceof NegatedCondition);
      this.test = test;
      this.onTrue = onTrue;
      this.onFalse = onFalse;
    }

    @Override
    public void addTest(CodeGen codeGen, FutureBlock elseBranch) {
      FutureBlock testIsFalse = new FutureBlock();
      test.addTest(codeGen, testIsFalse);
      if (codeGen.cb.nextIsReachable()) {
        onTrue.addTest(codeGen, elseBranch);
      }
      FutureBlock next = codeGen.cb.swapNext(testIsFalse);
      if (codeGen.cb.nextIsReachable()) {
        onFalse.addTest(codeGen, elseBranch);
      }
      codeGen.cb.mergeNext(next);
    }
  }

  private static void emitEqual(CodeGen codeGen, Value v1, Value v2, FutureBlock elseBranch) {
    Template t1 = RValue.toTemplate(v1);
    Template t2 = RValue.toTemplate(v2);
    CopyPlan plan = CopyPlan.create(t1, t2, false);
    if (v2 instanceof RValue) {
      Template.IndexBounds bounds = new Template.IndexBounds(t2);
      plan = CopyOptimizer.equalsRegisters(plan, bounds.minIndex, bounds.maxIndex + 1);
    }
    CopyEmitter.emitEqualRegisters(codeGen, plan, elseBranch);
  }

  private static class NumericComparison extends Condition {
    final Value v1;
    final Value v2;
    final boolean testEq;

    NumericComparison(Value v1, Value v2, boolean testEq) {
      this.v1 = v1;
      this.v2 = v2;
      this.testEq = testEq;
    }

    @Override
    public void addTest(CodeGen codeGen, FutureBlock elseBranch) {
      CodeValue cv1 = codeGen.asCodeValue(v1);
      CodeValue cv2 = codeGen.asCodeValue(v2);
      OpCodeType opType;
      if (cv1.type() == double.class || cv2.type() == double.class) {
        opType = OpCodeType.DOUBLE;
      } else if (cv1.type() == long.class || cv2.type() == long.class) {
        opType = OpCodeType.LONG;
      } else {
        opType = OpCodeType.INT;
      }
      TestBlock test = testEq ? new IsEq(opType, cv1, cv2) : new IsLessThan(opType, cv1, cv2);
      test.setBranch(false, elseBranch).addTo(codeGen.cb);
    }
  }

  static TestBlock isSharedTest(CodeValue v) {
    return new IsEq(OpCodeType.INT, RefCounted.IS_NOT_SHARED_OP.result(v), CodeValue.ZERO);
  }

  /**
   * Returns a Condition that is true if {@link RefCounted#isNotShared(Object)} returns true for
   * {@code v}.
   */
  public static Condition isNotShared(Value v) {
    if (v instanceof RValue) {
      Register r = TState.get().codeGen().register(v);
      return fromTest(() -> isSharedTest(r)).not();
    }
    return Condition.of(RefCounted.isNotShared(v));
  }
}
