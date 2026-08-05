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

import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.TemplateBuilder.VarAllocator;

@RunWith(JUnit4.class)
public class RecordLayoutTest {

  /// Returns a template for an array containing the given elements.
  private static Template arrayOf(Template... elements) {
    return Template.Compound.of(Core.FixedArrayType.withSize(elements.length), elements);
  }

  @Test
  public void permutationsWithOffset() {
    // Allocates NumVars with different encodings in all possible orders, and verifies that the
    // results are as expected.
    VarAllocator allocator = RecordLayout.VarAllocator.newWithOffsetDoubles();
    tryAllOrders(
        allocator, 8, 3, 2, result -> assertThat(result.toString()).isEqualTo("36b-36i-36d-0x"));
  }

  @Test
  public void permutationsAligned() {
    // Allocates NumVars with different encodings in all possible orders, and verifies that the
    // results are as expected.
    VarAllocator allocator = RecordLayout.VarAllocator.newWithAlignedDoubles();
    tryAllOrders(
        allocator, 8, 2, 2, result -> assertThat(result.toString()).isEqualTo("32b-32i-32d-0x"));
  }

  @Test
  public void permutationsNoBytes() {
    // Allocates NumVars with different encodings in all possible orders, and verifies that the
    // results are as expected.
    VarAllocator allocator = RecordLayout.VarAllocator.newWithAlignedDoubles().setUpgradeSubInts();
    tryAllOrders(
        allocator, 2, 2, 2, result -> assertThat(result.toString()).isEqualTo("32i-32d-0x"));
  }

  /// Allocates NumVars with the specified number of each encoding in all possible orders, and calls
  /// `finalCheck` with each of the resulting VarAllocators.
  private static void tryAllOrders(
      VarAllocator allocator, int numB, int numI, int numD, Consumer<VarAllocator> finalCheck) {
    if (numB == 0 && numI == 0 && numD == 0) {
      finalCheck.accept(allocator);
      return;
    }
    VarAllocator dup = allocator.duplicate();
    if (numB != 0) {
      var unused = dup.allocNumVar(NumVar.UINT8);
      tryAllOrders(dup, numB - 1, numI, numD, finalCheck);
      dup.resetTo(allocator);
    }
    if (numI != 0) {
      var unused = dup.allocNumVar(NumVar.INT32);
      tryAllOrders(dup, numB, numI - 1, numD, finalCheck);
    }
    if (numD != 0) {
      var unused = allocator.allocNumVar(NumVar.FLOAT64);
      tryAllOrders(allocator, numB, numI, numD - 1, finalCheck);
    }
  }

  @Test
  public void union() {
    Template dst =
        arrayOf(
            new Template.Union(
                NumVar.UINT8,
                null,
                NumVar.INT32,
                arrayOf(NumVar.UINT8, NumVar.UINT8, NumVar.UINT8)),
            new Template.Union(NumVar.UINT8, null, NumVar.FLOAT64, Core.NONE.asTemplate));

    assertThat(dst.toBuilder().build(RecordLayout.VarAllocator.newWithOffsetDoubles()).toString())
        .isEqualTo("[b0⸨0:i4; 1:[b1, b2, b3]⸩, b8⸨0:d12; 1:None⸩]");
    assertThat(dst.toBuilder().build(RecordLayout.VarAllocator.newWithAlignedDoubles()).toString())
        .isEqualTo("[b0⸨0:i4; 1:[b1, b2, b3]⸩, b8⸨0:d16; 1:None⸩]");
  }
}
