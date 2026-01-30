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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableList;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.code.CodeValue;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.ReturnBlock;
import org.retrolang.code.SetBlock;
import org.retrolang.impl.BaseType.StackEntryType;
import org.retrolang.impl.Template.Compound;
import org.retrolang.impl.Template.Constant;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.Union;

/** Some minimal tests for the components of code generation. */
@RunWith(JUnit4.class)
public final class SimpleCodeGenTest {

  private static final Template BOOLEAN = union(Core.FALSE.asTemplate, Core.TRUE.asTemplate);

  /** Returns a Compound template for an array containing the given elements. */
  private static Template arrayOf(Template... elements) {
    return Compound.of(Core.FixedArrayType.withSize(elements.length), elements);
  }

  /** Returns a Constant template for a number. */
  private static Template constant(Number num) {
    return Constant.of(NumValue.of(num, Allocator.UNCOUNTED));
  }

  /** Returns a Union template. The tag always has index 0, so likely only useful as a builder */
  private static Union union(Template... choices) {
    return new Union(NumVar.UINT8, null, choices);
  }

  private Value alloc(boolean asArg, TemplateBuilder builder) {
    return RValue.fromTemplate(builder.build(new RegisterAllocator(codeGen.cb, asArg)));
  }

  private static final MethodHandles.Lookup lookup = MethodHandles.lookup();
  private static final int MEMORY_LIMIT = 3000;

  VirtualMachine vm = new VirtualMachine();
  TState tstate;
  ResourceTracker tracker;

  private CodeGen codeGen;
  private ImmutableList<Value> args;

  @Before
  public void setup() {
    tracker = new ResourceTracker(vm.scope, MEMORY_LIMIT, false);
    tstate = TState.resetAndGet();
    tstate.bindTo(tracker);
  }

  private void startCodeGen(
      ImmutableList<Template> argTemplates, ImmutableList<Template> resultTemplates) {
    CodeGenTarget target =
        new CodeGenTarget(
            "test",
            null,
            null,
            null,
            alloc -> build(argTemplates, alloc),
            alloc -> build(resultTemplates, alloc));
    codeGen = target.codeGen;
    codeGen.cb.verbose = true;
    args = target.args.stream().map(RValue::fromTemplate).collect(ImmutableList.toImmutableList());
  }

  /** Build a Template for each of the List's elements. */
  private static ImmutableList<Template> build(
      List<Template> templates, TemplateBuilder.VarAllocator alloc) {
    return templates.stream()
        .map(t -> t.toBuilder().build(alloc))
        .collect(ImmutableList.toImmutableList());
  }

  private void emitReturn(Value... results) {
    codeGen.emitSaveResults(results);
    emitReturnBlock(0);
  }

  private void emitReturnBlock(int value) {
    new ReturnBlock(CodeValue.of(value)).addTo(codeGen.cb);
  }

  /** Sets codeGen's escape to return the given value. */
  private void setEscapeToReturn(int value) {
    codeGen.setNewEscape(() -> emitReturnBlock(value));
  }

  private static final Op PUSH_UNWIND =
      RcOp.forRcMethod(lookup, TState.class, "pushUnwind", Value.class).build();

  /** Sets codeGen's escape to start unwinding with the given stack entry. */
  private void setEscapeToUnwind(StackEntryType entryType, Value... args) {
    codeGen.setNewEscape(
        () -> {
          CodeValue entryCv = StackEntryBlock.create(tstate.compound(entryType, args), codeGen.cb);
          PUSH_UNWIND.block(codeGen.tstateRegister(), entryCv).addTo(codeGen.cb);
          emitReturnBlock(-1);
        });
  }

  /**
   * Calls codeGen.emit() with the given runnable, and asserts that it doesn't actually throw an
   * exception.
   */
  private void emit(Condition.RunnableExcept runnable) {
    codeGen.emit(
        () -> {
          try {
            runnable.run();
          } catch (Err.BuiltinException e) {
            throw new AssertionError(e);
          }
        });
  }

  private Destination newDestination(Template... templates) {
    return Destination.fromTemplates(ImmutableList.copyOf(templates));
  }

  private void branchToDestination(Destination destination, Value... values) {
    destination.addBranch(codeGen, values);
  }

  @Test
  public void one() {
    ImmutableList<Template> argTemplates =
        ImmutableList.of(
            // The first Retrospect arg (using a single int) is Boolean
            BOOLEAN,
            // The second Retrospect arg (using an int and a double) is i2⸨0:d3; 1:[7, d3]; 2:None⸩
            // (i.e. a Number, a 2-element array, or None)
            union(NumVar.FLOAT64, arrayOf(constant(7), NumVar.FLOAT64), Core.NONE.asTemplate));
    ImmutableList<Template> resultTemplates =
        ImmutableList.of(
            // A single Retrospect result, a pair containing a Boolean and a double
            arrayOf(BOOLEAN, NumVar.FLOAT64));
    startCodeGen(argTemplates, resultTemplates);
    emit(
        () -> {
          setEscapeToReturn(1);
          Destination addAndReturn = newDestination(NumVar.FLOAT64, NumVar.FLOAT64);
          // Save the current escape so that we can compare it later.
          CodeGen.EscapeState initEscape = codeGen.escapeState();
          Value arg1 = args.get(0);
          Value arg2 = args.get(1);
          Condition.fromBoolean(arg1)
              .and(arg1.isa(Core.ARRAY).not())
              .testExcept(
                  () -> {
                    // This check should always pass.
                    codeGen.escapeUnless(arg1.isa(Core.ARRAY).or(arg1.isa(Core.BOOLEAN)));
                    setEscapeToReturn(2);
                    Err.INVALID_ARGUMENT.when(arg2.is(Core.FALSE).or(arg2.is(Core.NONE)));
                    branchToDestination(
                        addAndReturn, NumValue.of(-1, tstate), NumValue.of(-2, tstate));
                  },
                  () -> {
                    // The ifTrue branch changed the escape, but that shouldn't affect us.
                    assertThat(codeGen.escapeState()).isEqualTo(initEscape);
                    Err.NOT_PAIR.unless(arg2.isa(Core.ARRAY).or(arg2.isa(Core.STRING)));
                    Value arg3 =
                        arg2.replaceElement(
                            codeGen.tstate(), 0, NumValue.of(0.1, Allocator.UNCOUNTED));
                    branchToDestination(addAndReturn, arg3.peekElement(0), arg3.peekElement(1));
                  });
          Value[] elements = addAndReturn.emit(codeGen);
          // If either of the branches changed the escape it should now be invalidated.
          assertThat(codeGen.needNewEscape()).isTrue();
          CodeValue element0 = codeGen.asCodeValue(elements[0]);
          CodeValue element1 = codeGen.asCodeValue(elements[1]);
          Register sum = codeGen.cb.newRegister(double.class);
          new SetBlock(sum, Op.ADD_DOUBLES.result(element0, element1)).addTo(codeGen.cb);
          emitReturn(tstate.arrayValue(arg1, codeGen.toValue(sum)));
        });
    MethodHandle unused = codeGen.cb.load("one", "test", int.class, lookup);
    assertThat(codeGen.cb.debugInfo.postOptimization)
        .isEqualTo(
            """
              1: test i1 0 (< 2); T:→ 5
              2: test i2 2 (< 3); T:→ 15
              3: d4 ← -1;
              4: d5 ← -2; → 8
            - 5: test i2 1 (< 3); F:→ 14
              6: d4 ← 0.1;
              7: d5 ← d3;
            - 8: d6 ← dAdd(d4, d5);
              9: TState.setResultTemplates(x0, [[i0⸨0:False; 1:True⸩, d8]]);
             10: x7 ← TState.fnResultBytes(x0, 16);
             11: setInt[](x7, 0, i1);
             12: setDouble[](x7, 8, d6);
             13: return 0
            -14: return 1
            -15: return 2
            """);
  }

  private Value copyTo(Template t, Value rhs) {
    int dstStart = codeGen.cb.numRegisters();
    Value dst = alloc(false, t.toBuilder());
    int dstEnd = codeGen.cb.numRegisters();
    codeGen.emitStore(RValue.toTemplate(rhs), RValue.toTemplate(dst), dstStart, dstEnd);
    return dst;
  }

  @Test
  public void two() {
    ImmutableList<Template> argTemplates =
        ImmutableList.of(
            // Retrospect arg is [7, i1, []]
            arrayOf(constant(7), NumVar.INT32, Core.EMPTY_ARRAY.asTemplate));
    ImmutableList<Template> resultTemplates =
        ImmutableList.of(
            // Retrospect result is a pair containing an int and a double (the uint8 will be
            // upgraded)
            arrayOf(NumVar.UINT8, NumVar.FLOAT64));
    startCodeGen(argTemplates, resultTemplates);
    VArrayLayout varrayOfString = VArrayLayout.newFromBuilder(vm.scope, Core.STRING.asRefVar);
    emit(
        () -> {
          Value arg1 = args.get(0);
          setEscapeToReturn(-1);
          // temp1 is [i2, 42, x3]
          Value temp1 = copyTo(arrayOf(NumVar.INT32, constant(42), varrayOfString.asRefVar), arg1);
          assertThat(codeGen.cb.nextInfoResolved(3))
              .isEqualTo(CodeValue.Const.of(varrayOfString.empty));
          Destination returnBlock = Destination.fromTemplates(resultTemplates);
          branchToDestination(returnBlock, tstate.arrayValue(arg1.element(1), temp1.element(0)));
          assertThat(codeGen.cb.nextIsReachable()).isFalse();
          Value[] returnValues = returnBlock.emit(codeGen);
          emitReturn(returnValues);
        });
    MethodHandle unused = codeGen.cb.load("two", "test", int.class, lookup);
    assertThat(codeGen.cb.debugInfo.postOptimization)
        .isEqualTo(
            """
             1: test i1 == 42; F:→ 7
             2: TState.setResultTemplates(x0, [[i0, d8]]);
             3: x4 ← TState.fnResultBytes(x0, 16);
             4: setInt[](x4, 0, 42);
             5: setDouble[](x4, 8, 7);
             6: return 0
            -7: return -1
            """);
  }

  @Test
  public void three() {
    ImmutableList<Template> argTemplates =
        ImmutableList.of(
            // Retrospect arg is i1⸨0:i2; 1:[i2]; 2:False, 3:True, 4:None⸩
            union(
                NumVar.INT32,
                arrayOf(NumVar.INT32),
                Core.FALSE.asTemplate,
                Core.TRUE.asTemplate,
                Core.NONE.asTemplate));
    ImmutableList<Template> resultTemplates =
        ImmutableList.of(
            // Retrospect result is a pair containing a boolean and a double
            arrayOf(BOOLEAN, NumVar.FLOAT64));
    startCodeGen(argTemplates, resultTemplates);
    emit(
        () -> {
          Value arg1 = args.get(0);
          setEscapeToReturn(-1);
          // temp1 is i3⸨0:i4; 1:[7]; 2:False, 3:True⸩
          Value temp1 =
              copyTo(
                  union(
                      NumVar.INT32,
                      arrayOf(constant(7)),
                      Core.FALSE.asTemplate,
                      Core.TRUE.asTemplate),
                  arg1);
          setEscapeToReturn(-2);
          Destination returnBlock = Destination.fromTemplates(resultTemplates);
          temp1
              .isa(Core.BOOLEAN)
              .test(
                  () -> branchToDestination(returnBlock, tstate.arrayValue(arg1, NumValue.ZERO)),
                  () -> branchToDestination(returnBlock, tstate.arrayValue(Core.TRUE, temp1)));
          Value[] returnValues = returnBlock.emit(codeGen);
          emitReturn(returnValues);
        });
    MethodHandle unused = codeGen.cb.load("three", "test", int.class, lookup);
    assertThat(codeGen.cb.debugInfo.postOptimization)
        .isEqualTo(
            """
              1: test i1 == 1; F:→ 3
              2: test i2 == 7; T:→ 17, F:→ 18
            - 3: test i1 4 (< 5); T:→ 18
              4: test i1 ∈{2, 3} (< 4); F:→ 9
              5: test i1 == 2; F:→ 7
              6: i5 ← 0; → 8
            - 7: i5 ← 1;
            - 8: d6 ← 0; → 12
            - 9: test i1 == 0; F:→ 17
             10: i5 ← 1;
             11: d6 ← i2;
            -12: TState.setResultTemplates(x0, [[i0⸨0:False; 1:True⸩, d8]]);
             13: x7 ← TState.fnResultBytes(x0, 16);
             14: setInt[](x7, 0, i5);
             15: setDouble[](x7, 8, d6);
             16: return 0
            -17: return -2
            -18: return -1
            """);
  }

  /**
   * Runs the given MethodHandle with args, and verifies that it returned -1 and pushed a single
   * unwind entry. Returns the stack entry.
   */
  private Value getUnwind(MethodHandle mh, Object... args) throws Throwable {
    tstate.setStackRest(TStack.BASE);
    int result1 = (int) mh.invokeWithArguments(args);
    assertThat(result1).isEqualTo(-1);
    TStack unwind = tstate.takeUnwind();
    assertThat(unwind.rest()).isSameInstanceAs(TStack.BASE);
    Value result = Value.addRef(unwind.first());
    tstate.dropReference(unwind);
    return result;
  }

  @Test
  public void unwinds() throws Throwable {
    // Retrospect args are two Booleans
    ImmutableList<Template> argTemplates = ImmutableList.of(NumVar.INT32, NumVar.INT32);
    // Retrospect results are two ints
    ImmutableList<Template> resultTemplates = ImmutableList.of(NumVar.INT32, NumVar.INT32);
    startCodeGen(argTemplates, resultTemplates);
    emit(
        () -> {
          Value arg1 = args.get(0);
          Value arg2 = args.get(1);
          StackEntryType entry1 = new BaseType.SimpleStackEntryType("entry1", "arg1", "arg2");
          setEscapeToUnwind(entry1, arg1, arg2);
          // Error unless arg2 >= 0
          Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, arg2));
          StackEntryType entry2 = new BaseType.SimpleStackEntryType("entry2", "arg1", "arg2");
          // Copy arg1 into arg2
          codeGen.emitStore(arg1, RValue.toTemplate(arg2), 2, 3);
          setEscapeToUnwind(entry2, arg1, arg2);
          // Error unless arg1 >= 0
          Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, arg1));
          StackEntryType entry3 = new BaseType.SimpleStackEntryType("entry3", "arg1", "arg2");
          setEscapeToUnwind(entry3, arg1, arg2);
          // Error when arg2 < 1
          Err.INVALID_ARGUMENT.when(Condition.numericLessThan(arg2, NumValue.ONE));
          emitReturn(arg1, arg2);
        });
    MethodHandle mh = codeGen.cb.load("four", "test", int.class, lookup);
    assertThat(codeGen.cb.debugInfo.postOptimization)
        .isEqualTo(
            """
              1: test i2 < 0; T:→ 14
              2: test i1 < 0; T:→ 11
              3: test i1 < 1; T:→ 9
              4: TState.setResultTemplates(x0, [i0, i4]);
              5: x3 ← TState.fnResultBytes(x0, 8);
              6: setInt[](x3, 0, i1);
              7: setInt[](x3, 4, i1);
              8: return 0
            - 9: TState.pushUnwind(x0, ⟦entry3 ∥ arg1=0, arg2=0⟧);
             10: return -1
            -11: x5 ← newStackEntry(⟦entry2 ∥ arg1=i1, arg2=i1⟧);
             12: TState.pushUnwind(x0, x5);
             13: return -1
            -14: x6 ← newStackEntry(⟦entry1 ∥ arg1=i1, arg2=i2⟧);
             15: TState.pushUnwind(x0, x6);
             16: return -1
            """);
    Value unwind1 = getUnwind(mh, tstate, 1, -1);
    assertThat(unwind1.layout().template().toString()).isEqualTo("⟦entry1 ∥ arg1=i0, arg2=i4⟧");
    assertThat(unwind1.toString()).isEqualTo("⟦entry1 ∥ arg1=1, arg2=-1⟧");
    tstate.dropValue(unwind1);
    Value unwind2 = getUnwind(mh, tstate, -1, 1);
    assertThat(unwind2.layout().template().toString()).isEqualTo("⟦entry2 ∥ arg1=i0, arg2=i0⟧");
    assertThat(unwind2.toString()).isEqualTo("⟦entry2 ∥ arg1=-1, arg2=-1⟧");
    tstate.dropValue(unwind2);
    Value unwind3 = getUnwind(mh, tstate, 0, 0);
    assertThat(unwind3.layout()).isNull();
    assertThat(unwind3.toString()).isEqualTo("⟦entry3 ∥ arg1=0, arg2=0⟧");
    tstate.dropValue(unwind3);
    assertThat((int) mh.invokeWithArguments(tstate, 4, 5)).isEqualTo(0);
    assertThat(NumValue.equals(tstate.takeResult(0), 4)).isTrue();
    assertThat(NumValue.equals(tstate.takeResult(1), 4)).isTrue();
    tstate.clearResults();
    // Now everything should have been released
    assertWithMessage("Reference counting error: %s", tracker).that(tracker.allReleased()).isTrue();
  }
}
