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
import org.retrolang.code.Block.NonTerminal;
import org.retrolang.code.Block.Split;
import org.retrolang.code.Block.Terminal;
import org.retrolang.util.Bits;

@RunWith(JUnit4.class)
public class BlockTest {

  private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

  private CodeBuilder cb;

  @Before
  public void setup() {
    cb = new CodeBuilder(new ValueInfo.BinaryOps());
    cb.verbose = true;
  }

  @Test
  public void blockIndexAssignment() {
    Register n = cb.newArg(int.class);
    Register result = cb.newRegister(int.class);

    new SetBlock(result, CodeValue.ZERO).addTo(cb);
    Block setBlock = cb.block(cb.numBlocks() - 1);
    assertThat(setBlock.index()).isEqualTo(cb.numBlocks() - 1);
    assertThat(setBlock.index()).isGreaterThan(0);
  }

  @Test
  public void blockLiveRegisters() throws Throwable {
    Register n = cb.newArg(int.class);
    Register count = cb.newRegister(int.class);

    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);
    new ReturnBlock(count).addTo(cb);

    MethodHandle mh = cb.load("test", "test", int.class, lookup);
    int result = (int) mh.invoke(5);
    assertThat(result).isEqualTo(1);
  }

  @Test
  public void blockWithInputs() {
    Register n = cb.newArg(int.class);
    Register result = cb.newRegister(int.class);

    SetBlock setBlock = new SetBlock(result, Op.ADD_INTS.result(n, CodeValue.ONE));
    setBlock.addTo(cb);

    assertThat(setBlock.numInputs()).isEqualTo(1);
    CodeValue input = setBlock.input(0);
    assertThat(input).isInstanceOf(Op.Result.class);
  }

  @Test
  public void blockModifiedRegisters() {
    Register n = cb.newArg(int.class);
    Register count = cb.newRegister(int.class);

    SetBlock setBlock = new SetBlock(count, CodeValue.ZERO);
    setBlock.addTo(cb);

    assertThat(cb.block(setBlock.index())).isSameInstanceAs(setBlock);
  }

  @Test
  public void terminalBlock() throws Throwable {
    Register n = cb.newArg(int.class);

    ReturnBlock returnBlock = new ReturnBlock(n);
    returnBlock.addTo(cb);

    assertThat(returnBlock).isInstanceOf(Terminal.class);
    assertThat(returnBlock.linksToString(CodeBuilder.PrintOptions.DEFAULT)).isEmpty();

    MethodHandle mh = cb.load("test", "test", int.class, lookup);
    int result = (int) mh.invoke(42);
    assertThat(result).isEqualTo(42);
  }

  @Test
  public void nonTerminalBlock() {
    Register n = cb.newArg(int.class);
    Register result = cb.newRegister(int.class);

    SetBlock setBlock = new SetBlock(result, CodeValue.ZERO);
    setBlock.addTo(cb);

    assertThat(setBlock).isInstanceOf(NonTerminal.class);
    assertThat(((NonTerminal) setBlock).next).isNotNull();
  }

  @Test
  public void splitBlock() {
    Register n = cb.newArg(int.class);
    FutureBlock elseBranch = new FutureBlock();

    TestBlock.IsEq testBlock = new TestBlock.IsEq(
        CodeBuilder.OpCodeType.INT, n, CodeValue.ZERO);
    testBlock.setBranch(false, elseBranch);
    testBlock.addTo(cb);

    assertThat(testBlock).isInstanceOf(Split.class);
    Split split = (Split) testBlock;
    assertThat(split.alternate).isNotNull();
  }

  @Test
  public void blockContainingLoop() {
    Register n = cb.newArg(int.class);
    Loop loop = cb.startLoop(n);

    Register count = cb.newRegister(int.class);
    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    Block setBlock = cb.block(cb.numBlocks() - 1);

    assertThat(setBlock.containingLoop()).isSameInstanceAs(loop);

    loop.complete();
  }

  @Test
  public void blockIsNoOp() {
    Register n = cb.newArg(int.class);
    Register result = cb.newRegister(int.class);

    SetBlock setBlock = new SetBlock(result, n);
    setBlock.addTo(cb);

    // A SetBlock that just copies a register might be optimized as a no-op
    // but by default it's not a no-op
    assertThat(setBlock.isNoOp()).isFalse();
  }

  @Test
  public void blockToStringWithSource() {
    Register n = cb.newArg(int.class);
    Register result = cb.newRegister(int.class);

    cb.setNextSrc("result = 0");
    new SetBlock(result, CodeValue.ZERO).addTo(cb);
    Block setBlock = cb.block(cb.numBlocks() - 1);

    String blockStr = setBlock.toString();
    assertThat(blockStr).contains("result");
    assertThat(blockStr).contains("0");
  }

  @Test
  public void initialBlock() {
    Block.Initial initial = (Block.Initial) cb.block(0);

    assertThat(initial).isInstanceOf(Block.Initial.class);
    assertThat(initial.toString(CodeBuilder.PrintOptions.DEFAULT)).isEqualTo("initial");
  }

  @Test
  public void blockPropagationResult() {
    // Test that PropagationResult enum has expected values
    assertThat(Block.PropagationResult.DONE).isNotNull();
    assertThat(Block.PropagationResult.REMOVE).isNotNull();
    assertThat(Block.PropagationResult.SKIP).isNotNull();
  }

  @Test
  public void substitutionOutcome() {
    // Test SubstitutionOutcome combine logic
    assertThat(Block.SubstitutionOutcome.YES.combine(Block.SubstitutionOutcome.KEEP_TRYING))
        .isEqualTo(Block.SubstitutionOutcome.YES);
    assertThat(Block.SubstitutionOutcome.KEEP_TRYING.combine(Block.SubstitutionOutcome.YES))
        .isEqualTo(Block.SubstitutionOutcome.YES);
    assertThat(Block.SubstitutionOutcome.YES.combine(Block.SubstitutionOutcome.YES))
        .isEqualTo(Block.SubstitutionOutcome.NO);
    assertThat(Block.SubstitutionOutcome.NO.combine(Block.SubstitutionOutcome.KEEP_TRYING))
        .isEqualTo(Block.SubstitutionOutcome.NO);
  }

  @Test
  public void blockWithCatchHandler() throws Throwable {
    Register n = cb.newArg(int.class);
    Register result = cb.newRegister(int.class);
    FutureBlock overflow = new FutureBlock();

    new SetBlock.WithCatch(
        result, Op.MULTIPLY_INTS_EXACT.result(n, n), ArithmeticException.class, overflow)
        .addTo(cb);
    new ReturnBlock(result).addTo(cb);
    cb.setNext(overflow);
    new ReturnBlock(CodeValue.NEGATIVE_ONE).addTo(cb);

    MethodHandle mh = cb.load("test", "test", int.class, lookup);
    int result1 = (int) mh.invoke(100);
    assertThat(result1).isEqualTo(10000);
    int result2 = (int) mh.invoke(100000);
    assertThat(result2).isEqualTo(-1);
  }

  @Test
  public void blockSimplification() throws Throwable {
    Register n = cb.newArg(int.class);
    Register temp = cb.newRegister(int.class);
    Register result = cb.newRegister(int.class);

    // Create a chain that can be simplified
    new SetBlock(temp, Op.ADD_INTS.result(n, CodeValue.ONE)).addTo(cb);
    new SetBlock(result, Op.ADD_INTS.result(temp, CodeValue.ONE)).addTo(cb);
    new ReturnBlock(result).addTo(cb);

    MethodHandle mh = cb.load("test", "test", int.class, lookup);
    int val = (int) mh.invoke(5);
    assertThat(val).isEqualTo(7);
  }
}