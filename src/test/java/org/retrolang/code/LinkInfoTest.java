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
import static org.retrolang.code.ValueInfo.ANY;

import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.code.ValueInfo.BinaryOps;
import org.retrolang.code.ValueInfo.IntRange;
import org.retrolang.util.Bits;

@RunWith(JUnit4.class)
public class LinkInfoTest {
  CodeBuilder cb;
  LinkInfo.Unioner unioner;
  Register i0;
  Register i1;
  Register i2;
  Register i3;
  Register d4;
  Register d5;
  Register d6;

  /// A top-level loop that updates i0.
  Loop loop0;

  /// A loop nested in i0 that updates i1.
  Loop loop1;

  /// A list of all the int registers, i.e. [i0, i1, i2, i3].
  ImmutableList<Register> intRegisters;

  @Before
  public void setup() {
    // Initialize a new CodeBuilder with one int arg, some additional int and double registers,
    // and two nested loops.
    cb = new CodeBuilder(new BinaryOps());
    unioner = new LinkInfo.Unioner(cb);
    i0 = cb.newArg(int.class);
    i1 = cb.newRegister(int.class);
    i2 = cb.newRegister(int.class);
    i3 = cb.newRegister(int.class);
    intRegisters = ImmutableList.of(i0, i1, i2, i3);
    d4 = cb.newRegister(double.class);
    d5 = cb.newRegister(double.class);
    d6 = cb.newRegister(double.class);
    loop0 = cb.startLoop(i0);
    loop1 = cb.startLoop(i1);
  }

  /// Creates a new LinkInfo and then initializes it with sequential calls to updateForAssignment
  /// for each int register that has a corresponding info in the arguments.
  LinkInfo newLinkInfoWith(ValueInfo... intRegInfos) {
    LinkInfo result = new LinkInfo();
    result.clear(true);
    for (int i = 0; i < intRegInfos.length; i++) {
      if (intRegInfos[i] != null) {
        result.updateForAssignment(intRegisters.get(i), intRegInfos[i], cb);
      }
    }
    return result;
  }

  @Test
  public void updateForAssignment() {
    // Start with the info we'd get after processing
    //    i0 = someExpression; i1 = i0; i2 = i0
    LinkInfo info = newLinkInfoWith(IntRange.of(0, 2), i0, i0);
    // Now add `i0 = 1`, which should not affect the info associated with i1 or i2
    info.updateForAssignment(i0, CodeValue.ONE, cb);
    // i1 and i2 are still IntRange.of(0, 2), *and* they're still known to be equal
    assertThat(info.toString()).isEqualTo("reg{0:1, 1:∈{0..2}, 2:i1}");
  }

  /// Returns a new LinkInfo initialized with the union of the given infos.
  private LinkInfo union(Loop loop, LinkInfo... infos) {
    LinkInfo result = new LinkInfo();
    result.setFrom(infos[0]);
    unioner.start(result, loop);
    for (int i = 1; i < infos.length; i++) {
      unioner.add(infos[i]);
    }
    return unioner.finish();
  }

  @Test
  public void unionBasics() {
    LinkInfo info1 = newLinkInfoWith(IntRange.of(0, 4), i0, i0, ANY);
    info1.updateForAssignment(d4, ANY, cb);
    info1.updateForAssignment(d5, d4, cb);
    info1.updateForAssignment(d6, d4, cb);
    info1.modified(i0, loop1);
    info1.modified(i1, loop1);

    // Union of a single LinkInfo keeps all register infos, discards loopModified entries that
    // are not in loop0
    LinkInfo union1 = union(loop0, info1);
    assertThat(union1.registers()).isEqualTo(info1.registers());
    assertThat(union1.toString())
        .isEqualTo("reg{0:∈{0..4}, 1:i0, 2:i0, 3:any, 4:any, 5:d4, 6:d4} modified{0}");
    // The loopModified difference is significant
    assertThat(union1.isCloseEnoughTo(info1)).isFalse();

    LinkInfo info2 =
        newLinkInfoWith(CodeValue.of(5), CodeValue.of(-1), CodeValue.NONE, CodeValue.of(3));
    info2.updateForAssignment(d5, ANY, cb);
    info2.updateForAssignment(d6, d5, cb);

    // Union of two LinkInfos unions both registers and loopModified.
    LinkInfo union2 = union(loop1, info1, info2);
    assertThat(union2.toString())
        .isEqualTo("reg{0:∈{0..5}, 1:∈-1..4, 2:i0, 3:any, 5:any, 6:d5} modified{0, 1}");
    assertThat(union1.isCloseEnoughTo(info1)).isFalse();

    // Union in the other order returns the same result.
    LinkInfo union2r = union(loop1, info2, info1);
    assertThat(union2r.registers()).isEqualTo(union2.registers());
    assertThat(union2r.isCloseEnoughTo(union2)).isTrue();
  }

  @Test
  public void union1() {
    // One of the tricky cases: "x is copy of y" should be symmetric
    LinkInfo info1 = newLinkInfoWith(IntRange.of(0, 2), i0);
    // We have to set the info for i1 before i0 can be a copy of it
    LinkInfo info2 = newLinkInfoWith(null, IntRange.of(1, 5));
    info2.updateForAssignment(i0, i1, cb);
    // We get different results if we swap the order, but they're equivalent
    assertThat(union(null, info1, info2).toString()).isEqualTo("reg{0:∈{0..5}, 1:i0}");
    assertThat(union(null, info2, info1).toString()).isEqualTo("reg{0:i1, 1:∈{0..5}}");
  }

  @Test
  public void union2() {
    // Another tricky case: "x is a copy of y" is implicit if they're both copies of z
    LinkInfo info1 = newLinkInfoWith(IntRange.of(0, 2), i0, i0);
    LinkInfo info2 = newLinkInfoWith(IntRange.of(1, 5), IntRange.of(1, 5), i1);
    assertThat(union(null, info1, info2).toString()).isEqualTo("reg{0:∈{0..5}, 1:∈{0..5}, 2:i1}");
    assertThat(union(null, info2, info1).toString()).isEqualTo("reg{0:∈{0..5}, 1:∈{0..5}, 2:i1}");
  }

  @Test
  public void union3() {
    // Another tricky case: "x is a copy of y" is implicit if they're both the same constant
    LinkInfo info1 = newLinkInfoWith(IntRange.of(0, 2), i0);
    LinkInfo info2 = newLinkInfoWith(CodeValue.of(3), CodeValue.of(3));
    assertThat(union(null, info1, info2).toString()).isEqualTo("reg{0:∈{0..3}, 1:i0}");
    assertThat(union(null, info2, info1).toString()).isEqualTo("reg{0:∈{0..3}, 1:i0}");
  }

  @Test
  public void union4() {
    // Another tricky case: union can produce "x is a copy of y" even if it was implicit in both
    // inputs.
    LinkInfo info1 = newLinkInfoWith(CodeValue.of(3), CodeValue.of(3));
    LinkInfo info2 = newLinkInfoWith(CodeValue.of(4), CodeValue.of(4));
    assertThat(union(null, info1, info2).toString()).isEqualTo("reg{0:∈{3, 4}, 1:i0}");
    assertThat(union(null, info2, info1).toString()).isEqualTo("reg{0:∈{3, 4}, 1:i0}");
  }

  @Test
  public void union5() {
    // A combination that triggered a (now fixed) bug
    LinkInfo info1 = newLinkInfoWith(IntRange.of(0, 3), i0, i0);
    LinkInfo info2 = newLinkInfoWith(CodeValue.of(3), CodeValue.of(3), i1);
    assertThat(union(null, info1, info2).toString()).isEqualTo("reg{0:∈{0..3}, 1:i0, 2:i0}");
    assertThat(union(null, info2, info1).toString()).isEqualTo("reg{0:∈{0..3}, 1:i0, 2:i0}");
  }

  @Test
  public void union6() {
    // Another tricky case
    LinkInfo info1 = newLinkInfoWith(ANY, ANY, i0, i0);
    LinkInfo info2 = newLinkInfoWith(ANY, ANY, i1, i1);
    assertThat(union(null, info1, info2).toString()).isEqualTo("reg{0:any, 1:any, 2:any, 3:i2}");
    assertThat(union(null, info2, info1).toString()).isEqualTo("reg{0:any, 1:any, 2:any, 3:i2}");
  }

  @Test
  public void union7() {
    // Another tricky case
    LinkInfo info1 = newLinkInfoWith(ANY, ANY, ANY, i2);
    info1.updateForAssignment(i0, i2, cb);
    LinkInfo info2 = newLinkInfoWith(ANY, ANY, i1, i1);
    assertThat(union(null, info1, info2).toString()).isEqualTo("reg{0:any, 1:any, 2:any, 3:i2}");
    assertThat(union(null, info2, info1).toString()).isEqualTo("reg{0:any, 1:any, 2:any, 3:i2}");
  }

  @Test
  public void isConsistentChange() {
    LinkInfo info1 = newLinkInfoWith(IntRange.of(0, 2), i0);
    LinkInfo info2 = newLinkInfoWith(CodeValue.ZERO, CodeValue.ZERO);
    // info2 is a refinement of info1, but not vice versa
    assertThat(info2.isConsistentChange(info1, null, cb.binaryOps)).isTrue();
    assertThat(info1.isConsistentChange(info2, null, cb.binaryOps)).isFalse();
    LinkInfo info3 = newLinkInfoWith(CodeValue.ZERO, CodeValue.ONE);
    // info3 is not a refinement of info1
    assertThat(info3.isConsistentChange(info1, null, cb.binaryOps)).isFalse();
    assertThat(info1.isConsistentChange(info3, null, cb.binaryOps)).isFalse();
  }

  @Test
  public void ensureNoDependencies() {
    LinkInfo info = newLinkInfoWith(ANY, i0, i0, i0);
    info.ensureNoDependencies(Bits.forRange(0, 1), cb);
    assertThat(info.toString()).isEqualTo("reg{0:any, 1:any, 2:any, 3:i2}");
  }
}
