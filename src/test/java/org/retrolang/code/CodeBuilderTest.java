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

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.ValueInfo.IntRange;
import org.retrolang.util.Bits;

@RunWith(JUnit4.class)
public class CodeBuilderTest {

  private static final Path TESTDATA = Path.of("src/test/java/org/retrolang/code/testdata");

  private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

  private CodeBuilder cb;

  @Before
  public void setup() {
    cb = new CodeBuilder(new ValueInfo.BinaryOps());
    cb.verbose = true;
  }

  /// Each test compares the CodeBuilder's results to a `".out"` golden file. The results are
  /// captured at three stages:
  ///
  /// - the block graph after optimizations are completed;
  /// - the finalized block graph just before bytecode is emitted, using Java locals in place of
  ///   register ids; and
  /// - the emitted bytecode, together with any constants that it references.
  ///
  /// The golden files contain each of these results, separated by "---\n" (the final set of results
  /// has two sections -- constants followed by bytecodes -- so the files actually have four
  /// sections).
  private void checkOutput(String filename) {
    String source;
    try {
      source = Files.readString(TESTDATA.resolve(filename));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    String[] parts = source.split("(?m)^---\n", 4);
    assertThat(parts).hasLength(4);
    assertThat(cb.debugInfo.postOptimization).isEqualTo(parts[0]);
    assertThat(cb.debugInfo.blocks).isEqualTo(parts[1]);
    assertThat(DebugInfo.printConstants(cb.debugInfo.constants, lookup)).isEqualTo(parts[2]);
    String byteCode = DebugInfo.printClassBytes(cb.debugInfo.classBytes);
    // Canonicalize the constant references inserted by Loader, so that the output doesn't
    // depend on which Loader subclass we used.
    // pre-JDK 17:
    byteCode = byteCode.replaceAll("LDC \\\"_(\\d+)_\\\"\n\\s*CHECKCAST .*", "LoadConst $1");
    // post-JDK 17, older versions of ASM:
    byteCode = byteCode.replaceAll("LDC _ : .*\\[(\\d+)\\]", "LoadConst $1");
    // post-JDK 17, more recent versions of ASM:
    byteCode =
        byteCode.replaceAll(
            "LDC _ : .*\\[\\n[^\\]]*// arguments:\\s*(\\d+)\\s*\\]", "LoadConst $1");
    assertThat(byteCode).isEqualTo(parts[3]);
  }

  /// Construct a function that counts the number of values other than 1 in the Collatz sequence
  /// starting from a given value (https://en.wikipedia.org/wiki/Collatz\_conjecture).
  ///
  /// ```
  /// int collatz(int n) {
  ///   int count = 0
  ///   for sequential n, count {
  ///     if n == 1 {
  ///       return count
  ///     }
  ///     n = (n % 2 == 0) ? n / 2 : 3 * n + 1
  ///     count += 1
  ///   }
  /// }
  /// ```
  @Test
  public void collatz() throws Throwable {
    Register n = cb.newArg(int.class);
    cb.setNextSrc("count = 0");
    Register count = cb.newRegister(int.class);
    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("for sequential n, count {");
    Loop loop = cb.startLoop(n, count);
    cb.setNextSrc("if n == 1 {");
    FutureBlock else1 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, n, CodeValue.of(1)).setBranch(false, else1).addTo(cb);
    cb.setNextSrc("return count");
    new ReturnBlock(count).addTo(cb);
    cb.setNextSrc("}");
    cb.setNext(else1);
    cb.setNextSrc("n = (n % 2 == 0) ? n / 2 : 3 * n + 1");
    FutureBlock else2 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, Op.MOD_INTS.result(n, CodeValue.of(2)), CodeValue.of(0))
        .setBranch(false, else2)
        .addTo(cb);
    new SetBlock(n, Op.DIV_INTS.result(n, CodeValue.of(2))).addTo(cb);
    FutureBlock done2 = cb.swapNext(else2);
    new SetBlock(
            n, Op.ADD_INTS.result(Op.MULTIPLY_INTS.result(CodeValue.of(3), n), CodeValue.of(1)))
        .addTo(cb);
    cb.mergeNext(done2);
    cb.setNextSrc("count += 1");
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("}");
    loop.complete();

    MethodHandle mh = cb.load("collatz", "test", int.class, lookup);
    int result12 = (int) mh.invoke(12);
    assertThat(result12).isEqualTo(9);
    int result27 = (int) mh.invoke(27);
    assertThat(result27).isEqualTo(111);
    int sum = 0;
    for (int i = 1; i < 100; i++) {
      sum += (int) mh.invoke(i);
    }
    assertThat(sum).isEqualTo(3117);
    checkOutput("collatz.out");
  }

  /// Construct a function that computes factorials recursively.
  ///
  /// ```
  /// double factorial(n) {
  ///   if n < 2 {
  ///     return 1
  ///   }
  ///   int t0 = n - 1
  ///   double t1 = factorial(t0)
  ///   double t2 = n * t1
  ///   return t2
  /// }
  /// ```
  @Test
  public void recursiveFactorial() throws Throwable {
    // Rather than having this function call itself directly (by name), we have it call indirectly
    // through a CallSite that will be bound to the function after it is constructed; this is more
    // representative of how separately-generated pieces of code can call each other (since
    // dynamically-loaded classes don't have real names).
    MutableCallSite recurse = new MutableCallSite(MethodType.methodType(double.class, int.class));
    Op recurseCall = Op.forMethodHandle("factorial", recurse.dynamicInvoker()).build();

    Register n = cb.newArg(int.class);
    cb.setNextSrc("if n < 2 {");
    FutureBlock else1 = new FutureBlock();
    new TestBlock.IsLessThan(OpCodeType.INT, n, CodeValue.of(2)).setBranch(false, else1).addTo(cb);
    cb.setNextSrc("return 1");
    new ReturnBlock(CodeValue.ONE).addTo(cb);
    cb.setNextSrc("}");
    cb.setNext(else1);
    cb.setNextSrc("t0 = n - 1");
    Register t0 = cb.newRegister(int.class);
    new SetBlock(t0, Op.SUBTRACT_INTS.result(n, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("t1 = factorial(t0)");
    Register t1 = cb.newRegister(double.class);
    new SetBlock(t1, recurseCall.result(t0)).addTo(cb);
    cb.setNextSrc("t2 = n * t1");
    Register t2 = cb.newRegister(double.class);
    new SetBlock(t2, Op.MULTIPLY_DOUBLES.result(n, t1)).addTo(cb);
    cb.setNextSrc("return t2");
    new ReturnBlock(t2).addTo(cb);

    MethodHandle mh = cb.load("factorial", "test", double.class, lookup);
    recurse.setTarget(mh);
    double result8 = (double) mh.invoke(8);
    assertThat(result8).isEqualTo(40320);
    double result20 = (double) mh.invoke(20);
    assertThat(result20).isEqualTo(2.43290200817664e18);
    checkOutput("recursive_factorial.out");
  }

  /// Construct a function that computes factorials recursively, using ints and returning -1 on
  /// overflow.
  ///
  /// ```
  /// int factorial(int n) {
  ///   if n < 2 {
  ///     return 1
  ///   }
  ///   int t0 = n - 1
  ///   int t1 = factorial(t0)
  ///   if t1 >= 0 {
  ///     int t2 = multiplyExact(n, t1); on ArithmeticException goto overflow
  ///     return t2
  ///   }
  /// overflow:
  ///   return -1
  /// }
  /// ```
  @Test
  public void intFactorial() throws Throwable {
    MutableCallSite recurse = new MutableCallSite(MethodType.methodType(int.class, int.class));
    Op recurseCall = Op.forMethodHandle("factorial", recurse.dynamicInvoker()).build();

    Register n = cb.newArg(int.class);
    cb.setNextSrc("if n < 2 {");
    FutureBlock else1 = new FutureBlock();
    new TestBlock.IsLessThan(OpCodeType.INT, n, CodeValue.of(2)).setBranch(false, else1).addTo(cb);
    cb.setNextSrc("return 1");
    new ReturnBlock(CodeValue.ONE).addTo(cb);
    cb.setNextSrc("}");
    cb.setNext(else1);
    cb.setNextSrc("t0 = n - 1");
    Register t0 = cb.newRegister(int.class);
    new SetBlock(t0, Op.SUBTRACT_INTS.result(n, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("t1 = factorial(t0)");
    Register t1 = cb.newRegister(int.class);
    new SetBlock(t1, recurseCall.result(t0)).addTo(cb);
    cb.setNextSrc("if t1 >= 0 {");
    FutureBlock else2 = new FutureBlock();
    new TestBlock.IsLessThan(OpCodeType.INT, t1, CodeValue.ZERO).setBranch(true, else2).addTo(cb);
    cb.setNextSrc("t2 = multiplyExact(n, t1); on ArithmeticException goto overflow");
    Register t2 = cb.newRegister(int.class);
    FutureBlock overflow = new FutureBlock();
    new SetBlock.WithCatch(
            t2, Op.MULTIPLY_INTS_EXACT.result(n, t1), ArithmeticException.class, overflow)
        .addTo(cb);
    cb.setNextSrc("return t2");
    new ReturnBlock(t2).addTo(cb);
    cb.setNextSrc("}");
    cb.setNext(else2);
    cb.setNextSrc("overflow:");
    cb.mergeNext(overflow);
    cb.setNextSrc("return -1");
    new ReturnBlock(CodeValue.NEGATIVE_ONE).addTo(cb);

    MethodHandle mh = cb.load("factorial", "test", int.class, lookup);
    recurse.setTarget(mh);
    int result8 = (int) mh.invoke(8);
    assertThat(result8).isEqualTo(40320);
    int result20 = (int) mh.invoke(20);
    assertThat(result20).isEqualTo(-1);
    checkOutput("int_factorial.out");
  }

  /// Exercise the substitution logic.
  ///
  /// ```
  /// int subs(int n) {
  ///   int n1 = n + 1
  ///   int n2 = n + 2
  ///   int n3 = n2 + 1
  ///   if n1 > 0 {
  ///     return n3
  ///   }
  ///   return n2
  /// }
  /// ```
  @Test
  public void substitutions() throws Throwable {
    Register n = cb.newArg(int.class);
    cb.setNextSrc("n1 = n + 1");
    Register n1 = cb.newRegister(int.class);
    new SetBlock(n1, Op.ADD_INTS.result(n, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("n2 = n + 2");
    Register n2 = cb.newRegister(int.class);
    new SetBlock(n2, Op.ADD_INTS.result(n, CodeValue.TWO)).addTo(cb);
    cb.setNextSrc("n3 = n2 + 1");
    Register n3 = cb.newRegister(int.class);
    new SetBlock(n3, Op.ADD_INTS.result(n2, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("if n1 > 0 {");
    FutureBlock done1 = new FutureBlock();
    new TestBlock.IsLessThan(OpCodeType.INT, CodeValue.ZERO, n1).setBranch(false, done1).addTo(cb);
    cb.setNextSrc("return n3");
    new ReturnBlock(n3).addTo(cb);
    cb.setNextSrc("}");
    cb.setNext(done1);
    cb.setNextSrc("return n2");
    new ReturnBlock(n2).addTo(cb);

    MethodHandle mh = cb.load("subs", "test", int.class, lookup);
    int result0 = (int) mh.invoke(0);
    assertThat(result0).isEqualTo(3);
    int result1 = (int) mh.invoke(-1);
    assertThat(result1).isEqualTo(1);
    checkOutput("substitutions.out");
  }

  /// Test an edge case: an Op that always throws an exception.
  ///
  /// ```
  /// int bomb(int m, int n) {
  ///   if n == 1_000_000 {
  ///     m = multiplyExact(n, n); on ArithmeticException goto overflow
  ///   }
  ///   return m
  /// overflow:
  ///   return -1
  /// }
  /// ```
  @Test
  public void bomb() throws Throwable {
    Register m = cb.newArg(int.class);
    Register n = cb.newArg(int.class);
    cb.setNextSrc("if n == 1_000_000 {");
    FutureBlock done1 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, n, CodeValue.of(1_000_000))
        .setBranch(false, done1)
        .addTo(cb);
    cb.setNextSrc("m = multiplyExact(n, n); on ArithmeticException goto overflow");
    FutureBlock overflow = new FutureBlock();
    new SetBlock.WithCatch(
            m, Op.MULTIPLY_INTS_EXACT.result(n, n), ArithmeticException.class, overflow)
        .addTo(cb);
    cb.setNextSrc("}");
    cb.setNext(done1);
    cb.setNextSrc("return m");
    new ReturnBlock(m).addTo(cb);
    cb.setNextSrc("overflow:");
    cb.setNext(overflow);
    cb.setNextSrc("return -1");
    new ReturnBlock(CodeValue.NEGATIVE_ONE).addTo(cb);

    MethodHandle mh = cb.load("bomb", "test", int.class, lookup);
    int result0 = (int) mh.invoke(1234, 200);
    assertThat(result0).isEqualTo(1234);
    int result1 = (int) mh.invoke(1234, 1_000_000);
    assertThat(result1).isEqualTo(-1);
    checkOutput("bomb.out");
  }

  /// Another implementation of the collatz function, but this time with lots of inefficiencies for
  /// the optimizer to work on.
  ///
  /// ```
  /// int collatz(int n) {
  ///   if n == 1 {
  ///     return 0
  ///   }
  ///   int count = 0
  ///   int useless = n
  ///   n = useless
  ///   int done = 0
  ///   for sequential n, count, useless, done {
  ///     int t0 = n mod 2
  ///     int t5
  ///     if done == 1 {
  ///       if done < 1 {
  ///         t5 = 7
  ///       } else {
  ///         t5 = count
  ///       }
  ///       return t5
  ///     } else {
  ///       int t3 = count + 1
  ///       int isEven
  ///       if t0 == 0 {
  ///         isEven = 1
  ///       } else {
  ///         isEven = 0
  ///       }
  ///       int isEven2 = isEven
  ///       int t2
  ///       if isEven2 == 1 {
  ///         t2 = n / 2
  ///       } else {
  ///         t2 = 3 * n
  ///       }
  ///       int t4 = t2 + 1
  ///       int t1
  ///       if isEven == 1 {
  ///         t1 = t2
  ///       } else {
  ///         t1 = t4
  ///       }
  ///       n = t1
  ///       count = t3
  ///       useless = isEven
  ///     }
  ///     if n == 1 {
  ///       done = 1
  ///     } else {
  ///       done = 0
  ///     }
  ///   }
  /// }
  /// ```
  @Test
  public void optimizations() throws Throwable {
    Register n = cb.newArg(int.class);
    cb.setNextSrc("if n == 1 {");
    FutureBlock done0 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, n, CodeValue.ONE).setBranch(false, done0).addTo(cb);
    cb.setNextSrc("return 0");
    new ReturnBlock(CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done0);
    cb.setNextSrc("count = 0");
    Register count = cb.newRegister(int.class);
    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("useless = n");
    Register useless = cb.newRegister(int.class);
    new SetBlock(useless, n).addTo(cb);
    cb.setNextSrc("n = useless");
    new SetBlock(n, useless).addTo(cb);
    cb.setNextSrc("done = 0");
    Register done = cb.newRegister(int.class);
    new SetBlock(done, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("for sequential n, count, useless, done {");
    Loop loop = cb.startLoop(n, count, useless, done);
    cb.setNextSrc("t0 = n mod 2");
    Register t0 = cb.newRegister(int.class);
    new SetBlock(t0, Op.MOD_INTS.result(n, CodeValue.TWO)).addTo(cb);
    Register t5 = cb.newRegister(int.class);
    cb.setNextSrc("if done == 1 {");
    FutureBlock else1 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, done, CodeValue.ONE).setBranch(false, else1).addTo(cb);
    cb.setNextSrc("if done < 1 {");
    FutureBlock else5 = new FutureBlock();
    new TestBlock.IsLessThan(OpCodeType.INT, done, CodeValue.ONE).setBranch(false, else5).addTo(cb);
    assertThat(cb.nextIsReachable()).isFalse();
    cb.setNextSrc("} else {");
    FutureBlock done5 = cb.swapNext(else5);
    cb.setNextSrc("t5 = count");
    new SetBlock(t5, count).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done5);
    cb.setNextSrc("return t5");
    new ReturnBlock(t5).addTo(cb);
    cb.setNextSrc("} else {");
    FutureBlock done1 = cb.swapNext(else1);
    cb.setNextSrc("t3 = count + 1");
    Register t3 = cb.newRegister(int.class);
    new SetBlock(t3, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("if t0 == 0 {");
    FutureBlock else4 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, t0, CodeValue.ZERO).setBranch(false, else4).addTo(cb);
    cb.setNextSrc("isEven = 1");
    Register isEven = cb.newRegister(int.class);
    new SetBlock(isEven, CodeValue.ONE).addTo(cb);
    cb.setNextSrc("} else {");
    FutureBlock done4 = cb.swapNext(else4);
    cb.setNextSrc("isEven = 0");
    new SetBlock(isEven, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done4);
    cb.setNextSrc("isEven2 = isEven");
    Register isEven2 = cb.newRegister(int.class);
    new SetBlock(isEven2, isEven).addTo(cb);
    cb.setNextSrc("if isEven2 == 1 {");
    FutureBlock else2 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, isEven2, CodeValue.ONE).setBranch(false, else2).addTo(cb);
    cb.setNextSrc("t2 = n / 2");
    Register t2 = cb.newRegister(int.class);
    new SetBlock(t2, Op.DIV_INTS.result(n, CodeValue.of(2))).addTo(cb);
    cb.setNextSrc("} else {");
    FutureBlock done2 = cb.swapNext(else2);
    cb.setNextSrc("t2 = 3 * n");
    new SetBlock(t2, Op.MULTIPLY_INTS.result(CodeValue.of(3), n)).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done2);
    cb.setNextSrc("t4 = t2 + 1");
    Register t4 = cb.newRegister(int.class);
    new SetBlock(t4, Op.ADD_INTS.result(t2, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("if isEven == 1 {");
    FutureBlock else3 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, isEven, CodeValue.ONE).setBranch(false, else3).addTo(cb);
    cb.setNextSrc("t1 = t2");
    Register t1 = cb.newRegister(int.class);
    new SetBlock(t1, t2).addTo(cb);
    cb.setNextSrc("} else {");
    FutureBlock done3 = cb.swapNext(else3);
    cb.setNextSrc("t1 = t4");
    new SetBlock(t1, t4).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done3);
    cb.setNextSrc("n = t1");
    new SetBlock(n, t1).addTo(cb);
    cb.setNextSrc("count = t3");
    new SetBlock(count, t3).addTo(cb);
    cb.setNextSrc("useless = isEven");
    new SetBlock(useless, isEven).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done1);
    cb.setNextSrc("if n == 1 {");
    FutureBlock else6 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, n, CodeValue.ONE).setBranch(false, else6).addTo(cb);
    cb.setNextSrc("done = 1");
    new SetBlock(done, CodeValue.ONE).addTo(cb);
    cb.setNextSrc("} else {");
    FutureBlock done6 = cb.swapNext(else6);
    cb.setNextSrc("done = 0");
    new SetBlock(done, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done6);
    cb.setNextSrc("}");
    loop.complete();

    MethodHandle mh = cb.load("collatz", "test", int.class, lookup);
    int result12 = (int) mh.invoke(12);
    assertThat(result12).isEqualTo(9);
    int result27 = (int) mh.invoke(27);
    assertThat(result27).isEqualTo(111);
    checkOutput("optimizations.out");
  }

  /// A function that given an int[], decrements the largest element(s) by one and returns the
  /// number of other elements. Intentionally implemented a little awkwardly to exercise some loop
  /// optimizations.
  ///
  /// ```
  /// int decrementMax(int[] nums) {
  ///   int n = nums.length
  ///   int isInitialized = 0
  ///   int max = 0
  ///   int count = 0
  ///   int j = 0
  ///   for sequential isInitialized, max, count, j {
  ///     if isInitialized == 0 {
  ///       max = -1
  ///       for (int i = 0; i != n; i++) {
  ///         max = Math.max(max, nums[i])
  ///       }
  ///     }
  ///     isInitialized = 1
  ///     if j == n {
  ///       result = n - count
  ///       return result
  ///     }
  ///     if nums[j] == max {
  ///       count += 1
  ///       nums[j] = max - 1
  ///     }
  ///     j += 1
  ///   }
  /// }
  /// ```
  @Test
  public void loopOptimizationsOne() throws Throwable {
    Register nums = cb.newArg(int[].class);
    cb.setNextSrc("n = nums.length");
    Register n = cb.newRegister(int.class);
    new SetBlock(n, Op.INT_ARRAY_LENGTH.result(nums)).addTo(cb);
    cb.setNextSrc("isInitialized = 0");
    Register isInitialized = cb.newRegister(int.class);
    new SetBlock(isInitialized, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("max = 0");
    Register max = cb.newRegister(int.class);
    new SetBlock(max, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("count = 0");
    Register count = cb.newRegister(int.class);
    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("j = 0");
    Register j = cb.newRegister(int.class);
    new SetBlock(j, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("for sequential isInitialized, max, count, j {");
    Loop outerLoop = cb.startLoop(isInitialized, max, count, j);
    cb.setNextSrc("if isInitialized == 0 {");
    FutureBlock done1 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, isInitialized, CodeValue.ZERO)
        .setBranch(false, done1)
        .addTo(cb);
    cb.setNextSrc("max = -1");
    new SetBlock(max, CodeValue.NEGATIVE_ONE).addTo(cb);
    cb.setNextSrc("i = 0");
    Register i = cb.newRegister(int.class);
    new SetBlock(i, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("for sequential i, max {");
    Loop innerLoop = cb.startLoop(i, max);
    cb.setNextSrc("if i == n { break }");
    new TestBlock.IsEq(OpCodeType.INT, i, n).setBranch(true, done1).addTo(cb);
    cb.setNextSrc("max = Math.max(max, nums[i])");
    Op mathMax = Op.forMethod(Math.class, "max", int.class, int.class).build();
    new SetBlock(max, mathMax.result(max, Op.INT_ARRAY_ELEMENT.result(nums, i))).addTo(cb);
    cb.setNextSrc("i += 1");
    new SetBlock(i, Op.ADD_INTS.result(i, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("}");
    innerLoop.complete();
    cb.setNext(done1);
    cb.setNextSrc("isInitialized = 1");
    new SetBlock(isInitialized, CodeValue.ONE).addTo(cb);
    cb.setNextSrc("if j == n {");
    FutureBlock done2 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, j, n).setBranch(false, done2).addTo(cb);
    cb.setNextSrc("result = n - count");
    Register result = cb.newRegister(int.class);
    new SetBlock(result, Op.SUBTRACT_INTS.result(n, count)).addTo(cb);
    cb.setNextSrc("return result");
    new ReturnBlock(result).addTo(cb);
    cb.setNextSrc("}");
    cb.setNext(done2);
    cb.setNextSrc("if nums[j] == max {");
    FutureBlock done3 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, Op.INT_ARRAY_ELEMENT.result(nums, j), max)
        .setBranch(false, done3)
        .addTo(cb);
    cb.setNextSrc("count += 1");
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("nums[j] = max - 1");
    Op.SET_INT_ARRAY_ELEMENT.block(nums, j, Op.SUBTRACT_INTS.result(max, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done3);
    cb.setNextSrc("j += 1");
    new SetBlock(j, Op.ADD_INTS.result(j, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("}");
    outerLoop.complete();

    MethodHandle mh = cb.load("decrementMax", "test", int.class, lookup);
    int[] array = new int[] {1, 5, 2, 5, 4};
    int result1 = (int) mh.invoke(array);
    assertThat(result1).isEqualTo(3);
    assertThat(array).asList().isEqualTo(ImmutableList.of(1, 4, 2, 4, 4));
    int result2 = (int) mh.invoke(array);
    assertThat(result2).isEqualTo(2);
    assertThat(array).asList().isEqualTo(ImmutableList.of(1, 3, 2, 3, 3));
    checkOutput("loop_optimizations_1.out");
  }

  /// Another version of the same method as [#loopOptimizationsOne]; this version has the loop
  /// nesting inverted to exercise a different set of optimizations.
  ///
  /// ```
  /// int decrementMax(int[] nums) {
  ///   int n = nums.length
  ///   int max = -1
  ///   int i = 0
  ///   for sequential i, max {
  ///     if i == n {
  ///       int count = 0
  ///       int j = 0
  ///       for sequential count, j {
  ///         if j == n {
  ///           result = n - count
  ///           return result
  ///         }
  ///         if nums[j] == max {
  ///           count += 1
  ///           nums[j] = max - 1
  ///         }
  ///         j += 1
  ///       }
  ///     }
  ///     max = Math.max(max, nums[i])
  ///     i += 1
  ///   }
  /// }
  /// ```
  @Test
  public void loopOptimizationsTwo() throws Throwable {
    Register nums = cb.newArg(int[].class);
    cb.setNextSrc("n = nums.length");
    Register n = cb.newRegister(int.class);
    new SetBlock(n, Op.INT_ARRAY_LENGTH.result(nums)).addTo(cb);
    cb.setNextSrc("max = -1");
    Register max = cb.newRegister(int.class);
    new SetBlock(max, CodeValue.NEGATIVE_ONE).addTo(cb);
    cb.setNextSrc("i = 0");
    Register i = cb.newRegister(int.class);
    new SetBlock(i, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("for sequential i, max {");
    Loop outerLoop = cb.startLoop(i, max);
    cb.setNextSrc("if i == n {");
    FutureBlock else1 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, i, n).setBranch(false, else1).addTo(cb);
    cb.setNextSrc("count = 0");
    Register count = cb.newRegister(int.class);
    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("j = 0");
    Register j = cb.newRegister(int.class);
    new SetBlock(j, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("for sequential count, j {");
    Loop innerLoop = cb.startLoop(count, j);
    cb.setNextSrc("if j == n {");
    FutureBlock else2 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, j, n).setBranch(false, else2).addTo(cb);
    cb.setNextSrc("result = n - count");
    Register result = cb.newRegister(int.class);
    new SetBlock(result, Op.SUBTRACT_INTS.result(n, count)).addTo(cb);
    cb.setNextSrc("return result");
    new ReturnBlock(result).addTo(cb);
    cb.setNextSrc("}");
    cb.setNext(else2);
    cb.setNextSrc("if nums[j] == max {");
    FutureBlock done3 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, Op.INT_ARRAY_ELEMENT.result(nums, j), max)
        .setBranch(false, done3)
        .addTo(cb);
    cb.setNextSrc("count += 1");
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("nums[j] = max - 1");
    Op.SET_INT_ARRAY_ELEMENT.block(nums, j, Op.SUBTRACT_INTS.result(max, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done3);
    cb.setNextSrc("j += 1");
    new SetBlock(j, Op.ADD_INTS.result(j, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("}");
    innerLoop.complete();
    cb.mergeNext(else1);
    cb.setNextSrc("max = Math.max(max, nums[i])");
    Op mathMax = Op.forMethod(Math.class, "max", int.class, int.class).build();
    new SetBlock(max, mathMax.result(max, Op.INT_ARRAY_ELEMENT.result(nums, i))).addTo(cb);
    cb.setNextSrc("i += 1");
    new SetBlock(i, Op.ADD_INTS.result(i, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("}");
    outerLoop.complete();

    MethodHandle mh = cb.load("decrementMax", "test", int.class, lookup);
    int[] array = new int[] {1, 5, 2, 5, 4};
    int result1 = (int) mh.invoke(array);
    assertThat(result1).isEqualTo(3);
    assertThat(array).asList().isEqualTo(ImmutableList.of(1, 4, 2, 4, 4));
    int result2 = (int) mh.invoke(array);
    assertThat(result2).isEqualTo(2);
    assertThat(array).asList().isEqualTo(ImmutableList.of(1, 3, 2, 3, 3));
    checkOutput("loop_optimizations_2.out");
  }

  /// A simple loop to test more optimizations.
  ///
  /// ```
  /// int loopOp3(int n) {
  ///   int i = 0
  ///   if n < 0 {
  ///     n = -n
  ///   }
  ///   for sequential i {
  ///     if i == 0 {
  ///       i = 1
  ///     } else {
  ///       i += 1
  ///       if i <= 0 or i > n {
  ///         if n > 10 {
  ///           return n
  ///         } else {
  ///           return i
  ///         }
  ///       }
  ///     }
  ///   }
  /// }
  /// ```
  @Test
  public void loopOptimizationsThree() throws Throwable {
    Register n = cb.newArg(int.class);
    cb.setNextSrc("i = 0");
    Register i = cb.newRegister(int.class);
    new SetBlock(i, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("if n < 0 {");
    FutureBlock done0 = new FutureBlock();
    new TestBlock.IsLessThan(OpCodeType.INT, n, CodeValue.ZERO).setBranch(false, done0).addTo(cb);
    cb.setNextSrc("n = -n");
    new SetBlock(n, Op.NEGATE_INT.result(n)).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done0);
    cb.setNextSrc("for sequential i {");
    Loop loop = cb.startLoop(i);
    cb.setNextSrc("if i == 0 {");
    FutureBlock else1 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, i, CodeValue.ZERO).setBranch(false, else1).addTo(cb);
    cb.setNextSrc("i = 1");
    new SetBlock(i, CodeValue.ONE).addTo(cb);
    cb.setNextSrc("} else {");
    FutureBlock done1 = cb.swapNext(else1);
    cb.setNextSrc("i += 1");
    new SetBlock(i, Op.ADD_INTS.result(i, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("if i <= 0 or i > n {");
    FutureBlock then2 = new FutureBlock();
    FutureBlock done2 = new FutureBlock();
    new TestBlock.IsLessThan(OpCodeType.INT, CodeValue.ZERO, i).setBranch(false, then2).addTo(cb);
    new TestBlock.IsLessThan(OpCodeType.INT, n, i).setBranch(false, done2).addTo(cb);
    cb.mergeNext(then2);
    cb.setNextSrc("if n > 10 {");
    FutureBlock else3 = new FutureBlock();
    new TestBlock.IsLessThan(OpCodeType.INT, CodeValue.of(10), n).setBranch(false, else3).addTo(cb);
    cb.setNextSrc("return n");
    new ReturnBlock(n).addTo(cb);
    cb.setNextSrc("} else {");
    cb.setNext(else3);
    cb.setNextSrc("return i");
    new ReturnBlock(i).addTo(cb);
    cb.setNextSrc("}");
    cb.setNext(done2);
    cb.setNextSrc("}");
    cb.mergeNext(done1);
    cb.setNextSrc("}");
    loop.complete();

    MethodHandle mh = cb.load("loopOp3", "test", int.class, lookup);
    int result1 = (int) mh.invoke(0);
    assertThat(result1).isEqualTo(2);
    int result2 = (int) mh.invoke(5);
    assertThat(result2).isEqualTo(6);
    int result3 = (int) mh.invoke(20);
    assertThat(result3).isEqualTo(20);
    checkOutput("loop_optimizations_3.out");
  }

  /// A loop that doesn't actually loop.
  ///
  /// ```
  /// int trivial(int n) {
  ///   int i = 0
  ///   for sequential i {
  ///     if i == 0 {
  ///       i = 1
  ///     } else {
  ///       if n < 0 {
  ///         n = -n
  ///       }
  ///       return i + n
  ///     }
  ///   }
  /// }
  /// ```
  @Test
  public void trivialLoop() throws Throwable {
    Register n = cb.newArg(int.class);
    cb.setNextSrc("i = 0");
    Register i = cb.newRegister(int.class);
    new SetBlock(i, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("for sequential i {");
    Loop loop = cb.startLoop(i);
    cb.setNextSrc("if i == 0 {");
    FutureBlock else1 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, i, CodeValue.ZERO).setBranch(false, else1).addTo(cb);
    cb.setNextSrc("i = 1");
    new SetBlock(i, CodeValue.ONE).addTo(cb);
    cb.setNextSrc("} else {");
    FutureBlock done1 = cb.swapNext(else1);
    cb.setNextSrc("if n < 0 {");
    FutureBlock done0 = new FutureBlock();
    new TestBlock.IsLessThan(OpCodeType.INT, n, CodeValue.ZERO).setBranch(false, done0).addTo(cb);
    cb.setNextSrc("n = -n");
    new SetBlock(n, Op.NEGATE_INT.result(n)).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done0);
    cb.setNextSrc("return i + n");
    new ReturnBlock(Op.ADD_INTS.result(i, n)).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done1);
    cb.setNextSrc("}");
    loop.complete();

    MethodHandle mh = cb.load("trivial", "test", int.class, lookup);
    int result1 = (int) mh.invoke(-10);
    assertThat(result1).isEqualTo(11);
    checkOutput("trivial_loop.out");
  }

  /// Test setting argument info. This function is only intended to be called with 0 or 1, and
  /// inverts its argument in a rather awkward way. The redundant test should be optimized away.
  ///
  /// ```
  /// int argInfo(bool n) {
  ///   if n == 1 {
  ///     n = 0
  ///     goto label
  ///   }
  ///   if n == 0 {
  ///     n = 1
  ///   }
  /// label:
  ///   return n
  /// }
  /// ```
  @Test
  public void argInfo() throws Throwable {
    Register n = cb.newArg(int.class);
    // Assert that n's initial value will be 0 or 1
    new RegisterInfoBlock(n, IntRange.of(0, 1)).addTo(cb);

    cb.setNextSrc("if n == 1 {");
    FutureBlock done1 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, n, CodeValue.ONE).setBranch(false, done1).addTo(cb);
    cb.setNextSrc("n = 0");
    new SetBlock(n, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("goto label");
    FutureBlock label = new FutureBlock();
    cb.branchTo(label);
    cb.setNextSrc("}");
    cb.mergeNext(done1);
    // Verify that n is believed to be zero at this point (so the following test will be
    // optimized away).
    assertThat(cb.nextInfoResolved(n.index)).isEqualTo(CodeValue.ZERO);
    cb.setNextSrc("if n == 0 {");
    FutureBlock done2 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, n, CodeValue.ZERO).setBranch(false, done2).addTo(cb);
    cb.setNextSrc("n = 1");
    new SetBlock(n, CodeValue.ONE).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(done2);
    cb.setNextSrc("label:");
    cb.mergeNext(label);
    cb.setNextSrc("return n");
    new ReturnBlock(n).addTo(cb);

    MethodHandle mh = cb.load("argInfo", "test", int.class, lookup);
    // Note that we're calling with an argument that violates our setArgInfo()
    // call (we promised the arg would be in 0..1), and the value returned doesn't
    // match the source code.
    int result11 = (int) mh.invoke(11);
    assertThat(result11).isEqualTo(1);
    checkOutput("arg_info.out");
  }

  /// Another variant on collatz, to test propagation of variable information through BackRefs.
  /// (Everything involving setting and testing "`firstTime`" can be optimized away.)
  ///
  /// ```
  /// int backRefInfo(int n, int m) {
  ///   int count = 0
  ///   int firstTime = 1
  ///   for sequential n, count, firstTime {
  ///     if firstTime != 0 {
  ///       count = 0
  ///     }
  ///     if m < 0 {
  ///       return m
  ///     }
  ///     if n == 1 {
  ///       return count
  ///     }
  ///     n = (n % 2 == 0) ? n / 2 : 3 * n + 1
  ///     count += 1
  ///     firstTime = 0
  ///   }
  /// }
  /// ```
  @Test
  public void backRefInfo() throws Throwable {
    Register n = cb.newArg(int.class);
    Register m = cb.newArg(int.class);
    cb.setNextSrc("count = 0");
    Register count = cb.newRegister(int.class);
    new SetBlock(count, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("firstTime = 1");
    Register firstTime = cb.newRegister(int.class);
    new SetBlock(firstTime, CodeValue.ONE).addTo(cb);
    cb.setNextSrc("for sequential n, count, firstTime {");
    Loop loop = cb.startLoop(n, count, firstTime);
    cb.setNextSrc("if firstTime != 0 {");
    FutureBlock else3 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, firstTime, CodeValue.of(0)).setBranch(true, else3).addTo(cb);
    cb.setNextSrc("firstTime = 0");
    new SetBlock(firstTime, CodeValue.ZERO).addTo(cb);
    cb.setNextSrc("}");
    cb.mergeNext(else3);
    cb.setNextSrc("if m < 0 {");
    FutureBlock else0 = new FutureBlock();
    new TestBlock.IsLessThan(OpCodeType.INT, m, CodeValue.of(0)).setBranch(false, else0).addTo(cb);
    cb.setNextSrc("return m");
    new ReturnBlock(m).addTo(cb);
    cb.setNextSrc("}");
    cb.setNext(else0);
    cb.setNextSrc("if n == 1 {");
    FutureBlock else1 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, n, CodeValue.of(1)).setBranch(false, else1).addTo(cb);
    cb.setNextSrc("return count");
    new ReturnBlock(count).addTo(cb);
    cb.setNextSrc("}");
    cb.setNext(else1);
    cb.setNextSrc("n = (n % 2 == 0) ? n / 2 : 3 * n + 1");
    FutureBlock else2 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, Op.MOD_INTS.result(n, CodeValue.of(2)), CodeValue.of(0))
        .setBranch(false, else2)
        .addTo(cb);
    new SetBlock(n, Op.DIV_INTS.result(n, CodeValue.of(2))).addTo(cb);
    FutureBlock done2 = cb.swapNext(else2);
    new SetBlock(
            n, Op.ADD_INTS.result(Op.MULTIPLY_INTS.result(CodeValue.of(3), n), CodeValue.of(1)))
        .addTo(cb);
    cb.mergeNext(done2);
    cb.setNextSrc("count += 1");
    new SetBlock(count, Op.ADD_INTS.result(count, CodeValue.ONE)).addTo(cb);
    cb.setNextSrc("}");
    loop.complete();

    MethodHandle mh = cb.load("collatz", "test", int.class, lookup);
    int result12 = (int) mh.invoke(12, 3);
    assertThat(result12).isEqualTo(9);
    int resultNeg = (int) mh.invoke(12, -3);
    assertThat(resultNeg).isEqualTo(-3);
    checkOutput("backref_info.out");
  }

  /// Test the TagCheck block.
  ///
  /// ```
  /// int tagCheck(int n) {
  ///   // All the tests below are TagChecks, with limit 9 (i.e. assuming n ∈ 0..8)
  ///   if n ∈ {0, 1, 4, 5, 6} {
  ///     // It's more efficient to test the inverse of that condition
  ///     if n ∈ {2, 3} {
  ///       // Not reachable
  ///     } else if n ∈ {0, 1, 7} {
  ///       // n can't be 7, so that can be implemented as n <= 1
  ///       return 1
  ///     } else if n ∈ {4, 5, 8} {
  ///       // That's equivalent to n != 6
  ///       return 2
  ///     } else {
  ///       return n / 2
  ///     }
  ///   } else if n ∈ {0, 1, 7, 8} {
  ///     // n can't be 0 or 1, so that can be implemented as n >= 7
  ///     return 4
  ///   } else if n == 2 {
  ///     return 5
  ///   } else {
  ///     // n is 3
  ///     return n * 2
  ///   }
  /// }
  /// ```
  @Test
  public void tagCheck() throws Throwable {
    Register n = cb.newArg(int.class);

    cb.setNextSrc("if n ∈ {0, 1, 4, 5, 6} {");
    FutureBlock else0 = new FutureBlock();
    new TestBlock.TagCheck(n, 9, Bits.fromBooleans(true, true, false, false, true, true, true))
        .setBranch(false, else0)
        .addTo(cb);

    cb.setNextSrc("if n ∈ {2, 3} {");
    FutureBlock else1 = new FutureBlock();
    new TestBlock.TagCheck(n, 9, Bits.fromBooleans(false, false, true, true))
        .setBranch(false, else1)
        .addTo(cb);
    assertThat(cb.nextIsReachable()).isFalse();

    cb.setNextSrc("} else if n ∈ {0, 1, 7} {");
    cb.setNext(else1);
    FutureBlock else2 = new FutureBlock();
    new TestBlock.TagCheck(
            n, 9, Bits.fromBooleans(true, true, false, false, false, false, false, true))
        .setBranch(false, else2)
        .addTo(cb);
    cb.setNextSrc("return 1");
    new ReturnBlock(CodeValue.ONE).addTo(cb);

    cb.setNextSrc("} else if n ∈ {4, 5, 8} {");
    cb.setNext(else2);
    FutureBlock else3 = new FutureBlock();
    new TestBlock.TagCheck(
            n, 9, Bits.fromBooleans(false, false, false, false, true, true, false, false, true))
        .setBranch(false, else3)
        .addTo(cb);
    cb.setNextSrc("return 2");
    new ReturnBlock(CodeValue.of(2)).addTo(cb);

    cb.setNextSrc("} else {");
    cb.setNext(else3);
    cb.setNextSrc("return n / 2");
    new ReturnBlock(Op.DIV_INTS.result(n, CodeValue.of(2))).addTo(cb);

    cb.setNextSrc("} else if n ∈ {0, 1, 7, 8} {");
    cb.setNext(else0);
    FutureBlock else4 = new FutureBlock();
    new TestBlock.TagCheck(
            n, 9, Bits.fromBooleans(true, true, false, false, false, false, false, true, true))
        .setBranch(false, else4)
        .addTo(cb);
    cb.setNextSrc("return 4");
    new ReturnBlock(CodeValue.of(4)).addTo(cb);

    cb.setNextSrc("} else if n == 2 {");
    cb.setNext(else4);
    FutureBlock else5 = new FutureBlock();
    new TestBlock.IsEq(OpCodeType.INT, n, CodeValue.of(2)).setBranch(false, else5).addTo(cb);
    cb.setNextSrc("return 5");
    new ReturnBlock(CodeValue.of(5)).addTo(cb);

    cb.setNextSrc("} else {");
    cb.setNext(else5);
    cb.setNextSrc("return n * 2");
    new ReturnBlock(Op.MULTIPLY_INTS.result(n, CodeValue.of(2))).addTo(cb);

    MethodHandle mh = cb.load("tagCheck", "test", int.class, lookup);
    int result0 = (int) mh.invoke(0);
    assertThat(result0).isEqualTo(1);
    int result1 = (int) mh.invoke(3);
    assertThat(result1).isEqualTo(6);
    checkOutput("tag_check.out");
  }
}
