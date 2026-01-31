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
import org.retrolang.util.Bits;

@RunWith(JUnit4.class)
public class LoopTest {

  private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

  private CodeBuilder cb;

  @Before
  public void setup() {
    cb = new CodeBuilder(new ValueInfo.BinaryOps());
    cb.verbose = true;
  }

  @Test
  public void simpleLoop() throws Throwable {
    Register n = cb.newArg(int.class);
    Register count = cb.newRegister(int.class);

    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    Loop loop = cb.startLoop(n, count);

    assertThat(loop).isNotNull();
    assertThat(loop.index()).isEqualTo(0);

    FutureBlock done = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, n, CodeValue.ZERO)
        .setBranch(false, done).addTo(cb);
    new ReturnBlock(count).addTo(cb);

    cb.setNext(done);
    new SetBlock(n, Op.SUBTRACT_INTS.result(n, CodeValue.ONE)).addTo(cb);
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);

    assertThat(loop.isCompleted()).isFalse();
    loop.complete();
    assertThat(loop.isCompleted()).isTrue();

    MethodHandle mh = cb.load("count", "test", int.class, lookup);
    int result = (int) mh.invoke(5);
    assertThat(result).isEqualTo(5);
  }

  @Test
  public void loopRegisters() {
    Register n = cb.newArg(int.class);
    Register count = cb.newRegister(int.class);

    Loop loop = cb.startLoop(n, count);

    Bits registers = loop.registers();
    assertThat(registers.test(n.index)).isTrue();
    assertThat(registers.test(count.index)).isTrue();

    loop.complete();
  }

  @Test
  public void nestedLoops() throws Throwable {
    Register n = cb.newArg(int.class);
    Register count = cb.newRegister(int.class);

    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    Loop outerLoop = cb.startLoop(n, count);

    Register i = cb.newRegister(int.class);
    new SetBlock(i, CodeValue.ZERO).addTo(cb);
    Loop innerLoop = cb.startLoop(i);

    assertThat(innerLoop.nestedIn()).isSameInstanceAs(outerLoop);
    assertThat(outerLoop.nestedIn()).isNull();

    FutureBlock innerDone = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, i, CodeValue.of(3))
        .setBranch(false, innerDone).addTo(cb);
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);
    cb.branchTo(innerDone);

    cb.setNext(innerDone);
    new SetBlock(i, Op.ADD_INTS.result(i, CodeValue.ONE)).addTo(cb);
    innerLoop.complete();

    FutureBlock outerDone = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, n, CodeValue.ZERO)
        .setBranch(false, outerDone).addTo(cb);
    new ReturnBlock(count).addTo(cb);

    cb.setNext(outerDone);
    new SetBlock(n, Op.SUBTRACT_INTS.result(n, CodeValue.ONE)).addTo(cb);
    outerLoop.complete();

    MethodHandle mh = cb.load("nested", "test", int.class, lookup);
    int result = (int) mh.invoke(2);
    assertThat(result).isEqualTo(6); // 2 * 3
  }

  @Test
  public void loopWithBreak() throws Throwable {
    Register n = cb.newArg(int.class);
    Register count = cb.newRegister(int.class);

    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    Loop loop = cb.startLoop(n, count);
    FutureBlock loopExit = new FutureBlock();

    // Break if count >= 10
    new TestBlock.IsLessThan(CodeBuilder.OpCodeType.INT, count, CodeValue.of(10))
        .setBranch(true, loopExit).addTo(cb);

    // Check loop condition
    FutureBlock done = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, n, CodeValue.ZERO)
        .setBranch(false, done).addTo(cb);
    new SetBlock(n, CodeValue.ZERO).addTo(cb);
    cb.branchTo(loopExit);

    cb.setNext(done);
    new SetBlock(n, Op.SUBTRACT_INTS.result(n, CodeValue.ONE)).addTo(cb);
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);
    loop.complete();

    cb.setNext(loopExit);
    new ReturnBlock(count).addTo(cb);

    MethodHandle mh = cb.load("withBreak", "test", int.class, lookup);
    int result = (int) mh.invoke(100);
    assertThat(result).isEqualTo(10);
  }

  @Test
  public void loopWithMultipleRegisters() throws Throwable {
    Register n = cb.newArg(int.class);
    Register count = cb.newRegister(int.class);
    Register sum = cb.newRegister(int.class);

    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    new SetBlock(sum, CodeValue.ZERO).addTo(cb);
    Loop loop = cb.startLoop(n, count, sum);

    assertThat(loop.registers().size()).isEqualTo(3);

    FutureBlock done = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, count, CodeValue.of(n))
        .setBranch(false, done).addTo(cb);
    new ReturnBlock(sum).addTo(cb);

    cb.setNext(done);
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);
    new SetBlock(sum, Op.ADD_INTS.result(sum, count)).addTo(cb);
    loop.complete();

    MethodHandle mh = cb.load("sumTo", "test", int.class, lookup);
    int result = (int) mh.invoke(5);
    assertThat(result).isEqualTo(15); // 1+2+3+4+5
  }

  @Test
  public void loopIndex() {
    Register n = cb.newArg(int.class);
    Loop loop1 = cb.startLoop(n);
    assertThat(loop1.index()).isEqualTo(0);
    loop1.complete();

    Register m = cb.newArg(int.class);
    Loop loop2 = cb.startLoop(m);
    assertThat(loop2.index()).isEqualTo(1);
    loop2.complete();
  }

  @Test
  public void loopBackRef() {
    Register n = cb.newArg(int.class);
    Loop loop = cb.startLoop(n);

    assertThat(loop.backRefs).hasSize(1);
    Loop.BackRef backRef = loop.backRefs.get(0);
    assertThat(backRef.loop()).isSameInstanceAs(loop);

    loop.complete();
  }

  @Test
  public void emptyLoopOptimization() throws Throwable {
    Register n = cb.newArg(int.class);
    Register result = cb.newRegister(int.class);

    new SetBlock(result, CodeValue.ZERO).addTo(cb);
    Loop loop = cb.startLoop(n);

    // Immediately exit the loop
    FutureBlock exit = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, CodeValue.ONE, CodeValue.ONE)
        .setBranch(true, exit).addTo(cb);

    new SetBlock(n, Op.SUBTRACT_INTS.result(n, CodeValue.ONE)).addTo(cb);
    loop.complete();

    cb.setNext(exit);
    new ReturnBlock(result).addTo(cb);

    MethodHandle mh = cb.load("empty", "test", int.class, lookup);
    int val = (int) mh.invoke(10);
    assertThat(val).isEqualTo(0);
  }

  @Test
  public void loopWithConditionalUpdate() throws Throwable {
    Register n = cb.newArg(int.class);
    Register count = cb.newRegister(int.class);

    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    Loop loop = cb.startLoop(n, count);

    FutureBlock done = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, n, CodeValue.ZERO)
        .setBranch(false, done).addTo(cb);
    new ReturnBlock(count).addTo(cb);

    cb.setNext(done);

    // Only increment count if n is even
    FutureBlock skipIncrement = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT,
        Op.MOD_INTS.result(n, CodeValue.TWO), CodeValue.ZERO)
        .setBranch(false, skipIncrement).addTo(cb);
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);

    cb.mergeNext(skipIncrement);
    new SetBlock(n, Op.SUBTRACT_INTS.result(n, CodeValue.ONE)).addTo(cb);
    loop.complete();

    MethodHandle mh = cb.load("evenCount", "test", int.class, lookup);
    int result = (int) mh.invoke(10);
    assertThat(result).isEqualTo(5); // 2,4,6,8,10
  }
}