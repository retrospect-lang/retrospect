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
import static org.retrolang.code.CodeValue.NONE;
import static org.retrolang.code.ValueInfo.ANY;

import java.util.function.IntFunction;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.code.CodeValue.Const;
import org.retrolang.code.ValueInfo.IntRange;
import org.retrolang.code.ValueInfo.SmallIntSet;
import org.retrolang.util.Bits;

@RunWith(JUnit4.class)
public class ValueInfoTest {

  static final ValueInfo.BinaryOps binaryOps = new ValueInfo.BinaryOps();

  static final Register I0 = new Register(0, int.class);
  static final Register I1 = new Register(1, int.class);
  static final Register I2 = new Register(2, int.class);
  static final Register I3 = new Register(3, int.class);

  /// Returns a simple IntFunction that returns the corresponding argument.
  static IntFunction<ValueInfo> registers(ValueInfo... values) {
    return i -> values[i];
  }

  @Test
  public void ints() {
    Const one = CodeValue.ONE;
    Const seven = CodeValue.of(7);
    // Consts implement ValueInfo
    assertThat(one.containsConst(seven)).isFalse();
    assertThat(one.containsValue(7)).isFalse();
    assertThat(seven.containsConst(seven)).isTrue();
    assertThat(seven.containsValue(7)).isTrue();
    assertThat(seven.minimum()).isEqualTo(7);
    assertThat(seven.maximum()).isEqualTo(7);
    assertThat(binaryOps.mightIntersect(seven, seven)).isTrue();
    assertThat(binaryOps.mightIntersect(one, seven)).isFalse();
    assertThat(binaryOps.intersection(seven, seven)).isEqualTo(seven);
    assertThat(binaryOps.intersection(one, seven)).isEqualTo(NONE);
    assertThat(binaryOps.containsAll(seven, seven)).isTrue();
    assertThat(binaryOps.containsAll(seven, one)).isFalse();
  }

  @Test
  public void intUnions() {
    Const one = CodeValue.ONE;
    Const seven = CodeValue.of(7);
    // union is {1, 7}
    ValueInfo union = binaryOps.union(one, seven);
    assertThat(binaryOps.union(seven, one)).isEqualTo(union);
    assertThat(union).isInstanceOf(SmallIntSet.class);
    assertThat(union.minimum()).isEqualTo(1);
    assertThat(union.maximum()).isEqualTo(7);
    assertThat(union.containsConst(one)).isTrue();
    assertThat(union.containsValue(7)).isTrue();
    assertThat(union.containsValue(3)).isFalse();
    assertThat(union.containsConst(CodeValue.NULL)).isFalse();
    assertThat(binaryOps.mightIntersect(union, one)).isTrue();
    assertThat(binaryOps.mightIntersect(seven, union)).isTrue();
    assertThat(binaryOps.mightIntersect(union, CodeValue.of(3))).isFalse();
    assertThat(binaryOps.intersection(union, seven)).isEqualTo(seven);
    assertThat(binaryOps.intersection(CodeValue.of(3), union)).isEqualTo(NONE);
    assertThat(binaryOps.containsAll(union, seven)).isTrue();
    assertThat(binaryOps.containsAll(seven, union)).isFalse();
    assertThat(binaryOps.union(union, seven)).isEqualTo(union);

    // union2 is -1..7 (since SmallIntSets don't support negative values we switch to a range)
    ValueInfo union2 = binaryOps.union(union, CodeValue.of(-1));
    assertThat(union2).isEqualTo(IntRange.of(-1, 7));
    assertThat(binaryOps.union(CodeValue.of(-1), union)).isEqualTo(union2);
    assertThat(union2.minimum()).isEqualTo(-1);
    assertThat(union2.maximum()).isEqualTo(7);
    assertThat(union2.containsValue(-1)).isTrue();
    assertThat(binaryOps.mightIntersect(union2, one)).isTrue();
    assertThat(binaryOps.mightIntersect(union, union2)).isTrue();
    assertThat(binaryOps.intersection(union2, union)).isEqualTo(union);
    assertThat(binaryOps.intersection(union, union2)).isEqualTo(union);
    assertThat(binaryOps.intersection(CodeValue.of(-2), union2)).isEqualTo(NONE);
    assertThat(binaryOps.containsAll(union2, union)).isTrue();
    assertThat(binaryOps.containsAll(union, union2)).isFalse();
    assertThat(binaryOps.union(union, union2)).isEqualTo(union2);
  }

  @Test
  public void doubleUnions() {
    Const half = CodeValue.of(0.5);
    Const quarter = CodeValue.of(0.25);
    assertThat(binaryOps.union(half, half)).isEqualTo(half);
    assertThat(binaryOps.union(half, quarter)).isEqualTo(ANY);
  }

  @Test
  public void none() {
    Const seven = CodeValue.of(7);
    assertThat(NONE.containsConst(seven)).isFalse();
    assertThat(NONE.containsValue(null)).isFalse();
    assertThat(binaryOps.union(seven, NONE)).isEqualTo(seven);
    assertThat(binaryOps.mightIntersect(ANY, NONE)).isFalse();
    assertThat(binaryOps.mightIntersect(NONE, ANY)).isFalse();
    assertThat(binaryOps.intersection(NONE, seven)).isEqualTo(NONE);
    assertThat(binaryOps.containsAll(NONE, seven)).isFalse();
    assertThat(binaryOps.containsAll(seven, NONE)).isTrue();
  }

  @Test
  public void any() {
    ValueInfo union = binaryOps.union(CodeValue.of(3), CodeValue.of(3_000_000));
    assertThat(union).isEqualTo(IntRange.of(3, 3_000_000));
    assertThat(binaryOps.union(ANY, union)).isEqualTo(ANY);
    assertThat(binaryOps.mightIntersect(union, ANY)).isTrue();
    assertThat(binaryOps.intersection(ANY, union)).isEqualTo(union);
    assertThat(binaryOps.containsAll(union, ANY)).isFalse();
    assertThat(binaryOps.containsAll(ANY, union)).isTrue();
  }

  @Test
  public void intRange() {
    assertThat(IntRange.of(5, 4)).isEqualTo(CodeValue.NONE);
    assertThat(IntRange.of(4, 4)).isEqualTo(CodeValue.of(4));
    assertThat(IntRange.of(3, 5)).isEqualTo(new SmallIntSet(Bits.forRange(3, 5)));
    assertThat(IntRange.of(Integer.MIN_VALUE, Integer.MAX_VALUE)).isEqualTo(ANY);
    assertThat(binaryOps.union(IntRange.of(-1, 1), CodeValue.TWO).toString()).isEqualTo("∈-1..2");
    assertThat(IntRange.lessThan(Math.PI, true)).isEqualTo(IntRange.of(Integer.MIN_VALUE, 3));
    assertThat(IntRange.lessThan(3, false)).isEqualTo(IntRange.of(Integer.MIN_VALUE, 2));
  }

  @Test
  public void info() {
    // A register's info() is just looked up
    ValueInfo intRange = IntRange.of(-1, 1);
    IntFunction<ValueInfo> registers = registers(intRange);
    assertThat(I0.info(registers)).isEqualTo(intRange);
    // A constant's info() is just itself
    assertThat(Const.ONE.info(registers)).isEqualTo(Const.ONE);
  }

  @Test
  public void simplify() {
    // If register values are known (directly or indirectly) they'll be used by simplification
    IntFunction<ValueInfo> registers = registers(CodeValue.of(3), null, CodeValue.of(5), I2);
    assertThat(I0.simplify(registers)).isEqualTo(CodeValue.of(3));
    assertThat(I3.simplify(registers)).isEqualTo(CodeValue.of(5));
    // If they can't be reduced to a constant they'll be left as registers, but possibly not the
    // same register
    IntFunction<ValueInfo> registers2 = registers(ANY, null, IntRange.of(-1, 1), I2);
    assertThat(I0.simplify(registers2)).isEqualTo(I0);
    assertThat(I3.simplify(registers2)).isEqualTo(I2);
    // Constants simplify to themselves
    assertThat(Const.ONE.simplify(registers)).isEqualTo(Const.ONE);
  }

  @Test
  public void simplifyAdd() {
    IntFunction<ValueInfo> registers = registers(CodeValue.of(7), ANY, CodeValue.of(0), I2);
    CodeValue op1 = Op.NEGATE_INT.result(Op.ADD_INTS.result(I0, I3));
    assertThat(op1.simplify(registers)).isEqualTo(CodeValue.of(-7));
    CodeValue op2 = Op.ADD_INTS.result(I1, I3);
    assertThat(op2.simplify(registers)).isEqualTo(I1);
    CodeValue op3 = Op.ADD_INTS.result(I0, I1);
    assertThat(op3.simplify(registers).toString()).isEqualTo("iAdd(7, i1)");
    CodeValue op4 = Op.ADD_INTS.result(Op.ADD_INTS.result(I0, I1), I0);
    assertThat(op4.simplify(registers).toString()).isEqualTo("iAdd(14, i1)");
    CodeValue op5 = Op.ADD_INTS.result(Op.SUBTRACT_INTS.result(I1, I3), I0);
    assertThat(op5.simplify(registers).toString()).isEqualTo("iAdd(i1, 7)");
  }

  @Test
  public void simplifyAddExact() {
    int big = Integer.MAX_VALUE - 10;
    IntFunction<ValueInfo> registers = registers(CodeValue.of(big), CodeValue.ONE, I0, I1);
    CodeValue op1 = Op.ADD_INTS_EXACT.result(I0, I1);
    assertThat(op1.simplify(registers)).isEqualTo(CodeValue.of(big + 1));
    CodeValue op2 = Op.ADD_INTS_EXACT.result(I0, I2);
    assertThat(op2.simplify(registers)).isInstanceOf(CodeValue.Thrown.class);
  }

  @Test
  public void simplifyDivide() {
    IntFunction<ValueInfo> registers = registers(CodeValue.ZERO, CodeValue.ONE, CodeValue.TWO, ANY);
    CodeValue op1 = Op.DIV_INTS.result(CodeValue.of(-7), CodeValue.TWO);
    assertThat(op1.simplify(registers)).isEqualTo(CodeValue.of(-3));
    CodeValue op2 = Op.DIV_INTS.result(I0, I2);
    assertThat(op2.simplify(registers)).isEqualTo(CodeValue.ZERO);
    CodeValue op3 = Op.DIV_INTS.result(I0, I0);
    assertThat(CodeValue.isThrown(op3.simplify(registers), ArithmeticException.class)).isTrue();
    CodeValue op4 = Op.DIV_INTS.result(I3, I0);
    assertThat(CodeValue.isThrown(op4.simplify(registers), ArithmeticException.class)).isTrue();
    CodeValue op5 = Op.DIV_INTS.result(I3, I1);
    assertThat(op5.simplify(registers)).isEqualTo(I3);
  }

  @Test
  public void simplifyMod() {
    IntFunction<ValueInfo> registers = registers(CodeValue.ZERO, CodeValue.ONE, CodeValue.TWO, ANY);
    CodeValue op1 = Op.MOD_INTS.result(CodeValue.of(-7), CodeValue.TWO);
    assertThat(op1.simplify(registers)).isEqualTo(CodeValue.of(-1));
    CodeValue op2 = Op.MOD_INTS.result(I0, I2);
    assertThat(op2.simplify(registers)).isEqualTo(CodeValue.ZERO);
    CodeValue op3 = Op.MOD_INTS.result(I0, I0);
    assertThat(CodeValue.isThrown(op3.simplify(registers), ArithmeticException.class)).isTrue();
    CodeValue op4 = Op.MOD_INTS.result(I3, I0);
    assertThat(CodeValue.isThrown(op4.simplify(registers), ArithmeticException.class)).isTrue();
    CodeValue op5 = Op.MOD_INTS.result(I3, I1);
    assertThat(op5.simplify(registers)).isEqualTo(CodeValue.ZERO);
  }

  @Test
  public void simplifyBitXor() {
    IntFunction<ValueInfo> registers =
        registers(CodeValue.ZERO, ANY, CodeValue.THREE, CodeValue.ONE);
    CodeValue op1 = Op.BIT_XOR_INTS.result(I0, I1);
    assertThat(op1.simplify(registers)).isEqualTo(I1);
    CodeValue op2 = Op.BIT_XOR_INTS.result(I2, I1);
    assertThat(op2.simplify(registers).toString()).isEqualTo("iXor(3, i1)");
    CodeValue op3 = Op.BIT_XOR_INTS.result(I2, I3);
    assertThat(op3.simplify(registers)).isEqualTo(CodeValue.TWO);
  }

  @Test
  public void opInfo() {
    IntFunction<ValueInfo> registers =
        registers(CodeValue.of(-1), IntRange.of(2, 4), IntRange.greaterThan(0, true));
    CodeValue op1 = Op.ADD_INTS.result(I0, I1);
    assertThat(op1.info(registers)).isEqualTo(IntRange.of(1, 3));
    CodeValue op2 = Op.ADD_INTS.result(I1, I1);
    assertThat(op2.info(registers)).isEqualTo(IntRange.of(4, 8));
    CodeValue op3 = Op.ADD_INTS.result(I0, I2);
    assertThat(op3.info(registers)).isEqualTo(IntRange.of(-1, Integer.MAX_VALUE - 1));
    CodeValue op4 = Op.ADD_INTS.result(I1, I2);
    assertThat(op4.info(registers)).isEqualTo(ANY);
  }

  @Test
  public void closeEnough() {
    assertThat(IntRange.of(-1, 3).isCloseEnoughTo(CodeValue.of(2))).isFalse();
    assertThat(IntRange.of(-1, 3).isCloseEnoughTo(IntRange.of(-1, 3))).isTrue();
    assertThat(IntRange.of(-1, 3).isCloseEnoughTo(IntRange.of(-1, 4))).isFalse();
    assertThat(IntRange.greaterThan(2, true).isCloseEnoughTo(IntRange.greaterThan(2, false)))
        .isFalse();
    assertThat(IntRange.greaterThan(2000, true).isCloseEnoughTo(IntRange.greaterThan(2000, false)))
        .isTrue();
  }
}
