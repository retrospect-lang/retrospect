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

import static com.google.common.truth.Truth.assertThat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DebugInfoTest {

  private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

  private CodeBuilder cb;

  @Before
  public void setup() {
    cb = new CodeBuilder(new ValueInfo.BinaryOps());
    cb.verbose = true;
  }

  @Test
  public void numDigitsPositive() {
    assertThat(DebugInfo.numDigits(0)).isEqualTo(1);
    assertThat(DebugInfo.numDigits(1)).isEqualTo(1);
    assertThat(DebugInfo.numDigits(9)).isEqualTo(1);
    assertThat(DebugInfo.numDigits(10)).isEqualTo(2);
    assertThat(DebugInfo.numDigits(99)).isEqualTo(2);
    assertThat(DebugInfo.numDigits(100)).isEqualTo(3);
    assertThat(DebugInfo.numDigits(999)).isEqualTo(3);
    assertThat(DebugInfo.numDigits(1000)).isEqualTo(4);
    assertThat(DebugInfo.numDigits(9999)).isEqualTo(4);
    assertThat(DebugInfo.numDigits(10000)).isEqualTo(5);
  }

  @Test
  public void numDigitsLargeValues() {
    assertThat(DebugInfo.numDigits(Integer.MAX_VALUE)).isEqualTo(10);
    assertThat(DebugInfo.numDigits(1000000000)).isEqualTo(10);
  }

  @Test
  public void numDigitsNegativeValues() {
    // As documented, implementation returns 1 for negative numbers
    assertThat(DebugInfo.numDigits(-1)).isEqualTo(1);
    assertThat(DebugInfo.numDigits(-100)).isEqualTo(1);
  }

  @Test
  public void printConstantsNull() {
    String result = DebugInfo.printConstants(null, lookup);
    assertThat(result).isEqualTo("No constants\n");
  }

  @Test
  public void printConstantsEmpty() {
    String result = DebugInfo.printConstants(new Object[0], lookup);
    assertThat(result).isEqualTo("No constants\n");
  }

  @Test
  public void printConstantsWithValues() {
    Object[] constants = new Object[3];
    constants[0] = "Hello";
    constants[1] = null;
    constants[2] = Integer.valueOf(42);

    String result = DebugInfo.printConstants(constants, lookup);
    assertThat(result).contains("const 0: (String) Hello");
    assertThat(result).doesNotContain("const 1:");
    assertThat(result).contains("const 2: (Integer) 42");
  }

  @Test
  public void printConstantsWithMethodHandle() throws Throwable {
    MethodHandle mh = lookup.findStatic(
        Math.class, "max", java.lang.invoke.MethodType.methodType(int.class, int.class, int.class));

    Object[] constants = new Object[1];
    constants[0] = mh;

    String result = DebugInfo.printConstants(constants, lookup);
    assertThat(result).contains("const 0:");
    // MethodHandle toString includes method signature
    assertThat(result).contains("max");
  }

  @Test
  public void printClassBytesNull() {
    String result = DebugInfo.printClassBytes(null);
    assertThat(result).isEqualTo("No bytecode\n");
  }

  @Test
  public void printClassBytesWithSimpleMethod() throws Throwable {
    Register n = cb.newArg(int.class);
    new ReturnBlock(n).addTo(cb);

    MethodHandle mh = cb.load("identity", "test", int.class, lookup);
    assertThat(cb.debugInfo.classBytes).isNotNull();

    String bytecode = DebugInfo.printClassBytes(cb.debugInfo.classBytes);
    assertThat(bytecode).contains("identity");
    assertThat(bytecode).contains("RETURN");
  }

  @Test
  public void debugInfoPostOptimization() throws Throwable {
    Register n = cb.newArg(int.class);
    Register temp = cb.newRegister(int.class);

    new SetBlock(temp, Op.ADD_INTS.result(n, CodeValue.ONE)).addTo(cb);
    new ReturnBlock(temp).addTo(cb);

    cb.load("test", "test", int.class, lookup);

    assertThat(cb.debugInfo.postOptimization).isNotNull();
    assertThat(cb.debugInfo.postOptimization).contains("add");
  }

  @Test
  public void debugInfoBlocks() throws Throwable {
    Register n = cb.newArg(int.class);
    new ReturnBlock(CodeValue.ZERO).addTo(cb);

    cb.load("test", "test", int.class, lookup);

    assertThat(cb.debugInfo.blocks).isNotNull();
    assertThat(cb.debugInfo.blocks).contains("return");
  }

  @Test
  public void debugInfoConstants() throws Throwable {
    Register n = cb.newArg(int.class);
    Register result = cb.newRegister(int.class);

    Op mathMax = Op.forMethod(Math.class, "max", int.class, int.class).build();
    new SetBlock(result, mathMax.result(n, CodeValue.ZERO)).addTo(cb);
    new ReturnBlock(result).addTo(cb);

    cb.load("test", "test", int.class, lookup);

    assertThat(cb.debugInfo.constants).isNotNull();
    assertThat(cb.debugInfo.constants.length).isGreaterThan(0);
  }

  @Test
  public void printClassBytesCleanup() throws Throwable {
    Register n = cb.newArg(int.class);
    new ReturnBlock(n).addTo(cb);

    cb.load("test", "test", int.class, lookup);

    String bytecode = DebugInfo.printClassBytes(cb.debugInfo.classBytes);

    // Check that certain cleanup patterns work
    assertThat(bytecode).doesNotContain("class version");
    assertThat(bytecode).doesNotContain("access flags");
  }

  @Test
  public void debugInfoWithLoop() throws Throwable {
    Register n = cb.newArg(int.class);
    Register count = cb.newRegister(int.class);

    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    Loop loop = cb.startLoop(n, count);

    FutureBlock done = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, n, CodeValue.ZERO)
        .setBranch(false, done).addTo(cb);
    new ReturnBlock(count).addTo(cb);

    cb.setNext(done);
    new SetBlock(n, Op.SUBTRACT_INTS.result(n, CodeValue.ONE)).addTo(cb);
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);
    loop.complete();

    cb.load("test", "test", int.class, lookup);

    assertThat(cb.debugInfo.blocks).contains("back");
    assertThat(cb.debugInfo.blocks).contains("loop");
  }

  @Test
  public void debugInfoWithComplexCode() throws Throwable {
    Register n = cb.newArg(int.class);
    Register result = cb.newRegister(int.class);

    FutureBlock elseBranch = new FutureBlock();
    new TestBlock.IsLessThan(CodeBuilder.OpCodeType.INT, n, CodeValue.ZERO)
        .setBranch(false, elseBranch).addTo(cb);

    new SetBlock(result, CodeValue.NEGATIVE_ONE).addTo(cb);
    FutureBlock done = cb.swapNext(elseBranch);

    new SetBlock(result, n).addTo(cb);
    cb.mergeNext(done);

    new ReturnBlock(result).addTo(cb);

    cb.load("test", "test", int.class, lookup);

    String bytecode = DebugInfo.printClassBytes(cb.debugInfo.classBytes);
    assertThat(bytecode).contains("IF");
  }
}