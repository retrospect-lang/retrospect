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
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.Vm;
import org.retrolang.impl.VmFunction.MultipleMethodsException;
import org.retrolang.util.Bits;

@RunWith(JUnit4.class)
public class VmFunctionTest {

  private static class MockImpl implements MethodImpl {
    final Value result;

    public MockImpl(Value result) {
      this.result = result;
    }

    @Override
    public void execute(TState tstate, ResultsInfo results, MethodMemo mMemo, Object[] args) {
      tstate.setResult(result);
    }

    @Override
    public void emit(CodeGen codeGen, ResultsInfo results, MethodMemo mMemo, Object[] args) {
      throw new AssertionError();
    }

    @Override
    public String toString() {
      return result.toString();
    }
  }

  private static VmMethod newMethod(
      VmFunction fn, MethodPredicate mp, boolean isDefault, Value result) {
    return new VmMethod(fn, mp, isDefault, new MockImpl(result), 1, MethodMemo.Factory.TRIVIAL);
  }

  /** Returns the "at(x, y)" function defined by Retrospect's core module (aka "x @ y"). */
  private static VmFunction.General at() {
    return (VmFunction.General) Core.core().lookupFunction("at", 2);
  }

  @Test
  public void simple() throws Exception {
    ModuleBuilder mb = new ModuleBuilder("MyModule");
    VmFunction.General fn = mb.newFunction("myFn", 1, Bits.EMPTY, true, Vm.Access.VISIBLE);
    // "method myFn(Boolean x) = 0"
    VmMethod m1 = newMethod(fn, Core.BOOLEAN.argType(0, true), false, NumValue.ZERO);
    mb.addMethod(m1);
    // "method myFn(x) default = 1"
    VmMethod m2 = newMethod(fn, null, true, NumValue.ONE);
    mb.addMethod(m2);
    mb.build();

    assertThat(fn.findMethod(Core.FALSE)).isSameInstanceAs(m1);
    assertThat(fn.findMethod(Core.NONE)).isSameInstanceAs(m2);
  }

  @Test
  public void ambiguous() throws Exception {
    ModuleBuilder mb = new ModuleBuilder("MyModule");
    VmFunction.General fn = mb.newFunction("myFn", 1, Bits.EMPTY, true, Vm.Access.VISIBLE);
    // "method myFn(Boolean x) = 0"
    VmMethod m1 = newMethod(fn, Core.BOOLEAN.argType(0, true), false, NumValue.ZERO);
    mb.addMethod(m1);
    // "method myFn(x) = 1"
    VmMethod m2 = newMethod(fn, null, false, NumValue.ONE);
    mb.addMethod(m2);
    mb.build();

    assertThat(fn.findMethod(Core.NONE)).isSameInstanceAs(m2);
    assertThrows(MultipleMethodsException.class, () -> fn.findMethod(Core.FALSE));
  }

  @Test
  public void noMethod() throws Exception {
    assertThat(at().findMethod(Core.TRUE, Core.NONE)).isNull();
  }

  @Test
  public void cannotDefine() {
    ModuleBuilder mb = new ModuleBuilder("MyModule");
    // "method at(True, _) = 0"
    // (It is an error for any other module to define a method for a Core function over
    // Core types.)
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                mb.addMethod(
                    newMethod(at(), Core.TRUE.asType().argType(0, true), false, NumValue.ZERO)));
    assertThat(e).hasMessageThat().startsWith("Cannot define");
  }

  @Test
  public void oneGetMethod() throws Exception {
    ModuleBuilder mb = new ModuleBuilder("MyModule");
    VmCompound myCompound = mb.newCompoundType("MyCompound", null, Vm.Access.PRIVATE);
    // "method at(x, y) (x is MyCompound or y is MyCompound) = 0"
    MethodPredicate mp =
        myCompound.asType().argType(0, true).or(myCompound.asType().argType(1, true));
    VmMethod m1 = newMethod(at(), mp, false, NumValue.ZERO);
    mb.addMethod(m1);
    mb.build();

    Value myValue = myCompound.baseType.uncountedOf(NumValue.ZERO);
    assertThat(at().findMethod(myValue, Core.NONE)).isSameInstanceAs(m1);
    assertThat(at().findMethod(Core.NONE, myValue)).isSameInstanceAs(m1);
    assertThat(at().findMethod(myValue, myValue)).isSameInstanceAs(m1);
  }

  @Test
  public void twoGetMethods() throws Exception {
    ModuleBuilder mb = new ModuleBuilder("MyModule");
    VmCompound myCompound = mb.newCompoundType("MyCompound", null, Vm.Access.PRIVATE);
    // "method at(MyCompound x, _) = 0"
    VmMethod m1 = newMethod(at(), myCompound.asType().argType(0, true), false, NumValue.ZERO);
    mb.addMethod(m1);
    // "method at(_, MyCompound y) = 1"
    VmMethod m2 = newMethod(at(), myCompound.asType().argType(1, true), false, NumValue.ONE);
    mb.addMethod(m2);
    mb.build();

    Value myValue = myCompound.baseType.uncountedOf(NumValue.ZERO);
    assertThat(at().findMethod(myValue, Core.NONE)).isSameInstanceAs(m1);
    assertThat(at().findMethod(Core.NONE, myValue)).isSameInstanceAs(m2);
    assertThrows(MultipleMethodsException.class, () -> at().findMethod(myValue, myValue));
  }
}
