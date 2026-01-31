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
import static org.junit.Assert.assertThrows;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ThrowBlockTest {

  private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

  private CodeBuilder cb;

  @Before
  public void setup() {
    cb = new CodeBuilder(new ValueInfo.BinaryOps());
    cb.verbose = true;
  }

  @Test
  public void throwIllegalStateException() throws Throwable {
    Register n = cb.newArg(int.class);

    Op newException = Op.forConstructor(IllegalStateException.class, String.class).build();
    new ThrowBlock(newException.result(CodeValue.of("Test exception"))).addTo(cb);

    MethodHandle mh = cb.load("throwTest", "test", int.class, lookup);

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> mh.invoke(5));
    assertThat(exception.getMessage()).isEqualTo("Test exception");
  }

  @Test
  public void conditionalThrow() throws Throwable {
    Register n = cb.newArg(int.class);

    FutureBlock noThrow = new FutureBlock();
    new TestBlock.IsLessThan(CodeBuilder.OpCodeType.INT, n, CodeValue.ZERO)
        .setBranch(false, noThrow).addTo(cb);

    Op newException = Op.forConstructor(IllegalArgumentException.class, String.class).build();
    new ThrowBlock(newException.result(CodeValue.of("Negative value"))).addTo(cb);

    cb.setNext(noThrow);
    new ReturnBlock(n).addTo(cb);

    MethodHandle mh = cb.load("conditionalThrow", "test", int.class, lookup);

    // Positive value should not throw
    int result = (int) mh.invoke(5);
    assertThat(result).isEqualTo(5);

    // Negative value should throw
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> mh.invoke(-1));
    assertThat(exception.getMessage()).isEqualTo("Negative value");
  }

  @Test
  public void throwNullPointerException() throws Throwable {
    Register n = cb.newArg(int.class);

    Op newException = Op.forConstructor(NullPointerException.class).build();
    new ThrowBlock(newException.result()).addTo(cb);

    MethodHandle mh = cb.load("throwNPE", "test", int.class, lookup);

    assertThrows(NullPointerException.class, () -> mh.invoke(0));
  }

  @Test
  public void throwWithToString() {
    Op newException = Op.forConstructor(RuntimeException.class, String.class).build();
    ThrowBlock throwBlock = new ThrowBlock(newException.result(CodeValue.of("Error")));

    String str = throwBlock.toString(CodeBuilder.PrintOptions.DEFAULT);
    assertThat(str).contains("throw");
  }

  @Test
  public void throwBlockIsTerminal() {
    Op newException = Op.forConstructor(RuntimeException.class).build();
    ThrowBlock throwBlock = new ThrowBlock(newException.result());

    throwBlock.addTo(cb);

    assertThat(throwBlock).isInstanceOf(Block.Terminal.class);
    assertThat(throwBlock.linksToString(CodeBuilder.PrintOptions.DEFAULT)).isEmpty();
  }

  @Test
  public void throwArithmeticException() throws Throwable {
    Register n = cb.newArg(int.class);

    FutureBlock noThrow = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, n, CodeValue.ZERO)
        .setBranch(false, noThrow).addTo(cb);

    Op newException = Op.forConstructor(ArithmeticException.class, String.class).build();
    new ThrowBlock(newException.result(CodeValue.of("Division by zero"))).addTo(cb);

    cb.setNext(noThrow);
    Register result = cb.newRegister(int.class);
    new SetBlock(result, Op.DIV_INTS.result(CodeValue.of(100), n)).addTo(cb);
    new ReturnBlock(result).addTo(cb);

    MethodHandle mh = cb.load("safeDivide", "test", int.class, lookup);

    int val = (int) mh.invoke(10);
    assertThat(val).isEqualTo(10);

    ArithmeticException exception =
        assertThrows(ArithmeticException.class, () -> mh.invoke(0));
    assertThat(exception.getMessage()).isEqualTo("Division by zero");
  }

  @Test
  public void throwInLoop() throws Throwable {
    Register n = cb.newArg(int.class);
    Register count = cb.newRegister(int.class);

    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    Loop loop = cb.startLoop(n, count);

    // Throw if count reaches a threshold
    FutureBlock noThrow = new FutureBlock();
    new TestBlock.IsLessThan(CodeBuilder.OpCodeType.INT, count, CodeValue.of(5))
        .setBranch(true, noThrow).addTo(cb);

    Op newException = Op.forConstructor(IllegalStateException.class, String.class).build();
    new ThrowBlock(newException.result(CodeValue.of("Too many iterations"))).addTo(cb);

    cb.setNext(noThrow);

    FutureBlock done = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, n, CodeValue.ZERO)
        .setBranch(false, done).addTo(cb);
    new ReturnBlock(count).addTo(cb);

    cb.setNext(done);
    new SetBlock(n, Op.SUBTRACT_INTS.result(n, CodeValue.ONE)).addTo(cb);
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);
    loop.complete();

    MethodHandle mh = cb.load("throwInLoop", "test", int.class, lookup);

    int result = (int) mh.invoke(3);
    assertThat(result).isEqualTo(3);

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> mh.invoke(10));
    assertThat(exception.getMessage()).isEqualTo("Too many iterations");
  }

  @Test
  public void throwCustomException() throws Throwable {
    Register n = cb.newArg(int.class);

    Op newException = Op.forConstructor(
        UnsupportedOperationException.class, String.class).build();
    new ThrowBlock(newException.result(CodeValue.of("Not supported"))).addTo(cb);

    MethodHandle mh = cb.load("throwCustom", "test", int.class, lookup);

    UnsupportedOperationException exception =
        assertThrows(UnsupportedOperationException.class, () -> mh.invoke(0));
    assertThat(exception.getMessage()).isEqualTo("Not supported");
  }
}