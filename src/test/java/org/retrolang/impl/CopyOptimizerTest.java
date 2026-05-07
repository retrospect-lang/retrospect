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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.impl.CopyOptimizer.Policy;
import org.retrolang.impl.Template.Constant;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.TemplateBuilder.VarAllocator;

@RunWith(JUnit4.class)
public class CopyOptimizerTest {

  /** Returns a Compound template for an array containing the given elements. */
  private static Template arrayOf(Template... elements) {
    return Template.Compound.of(Core.FixedArrayType.withSize(elements.length), elements);
  }

  /** Returns a Constant template for an int. */
  private static Template constant(int i) {
    return Constant.of(NumValue.of(i, Allocator.UNCOUNTED));
  }

  /** Returns a Compound template for a LoopExit containing the given element. */
  private static Template loopExit(Template element) {
    return Template.Compound.of(Core.LOOP_EXIT, element);
  }

  /** Converts double quotes to single quotes, to make expected values easier to read. */
  private static String cleanString(CopyPlan plan) {
    return plan.toString().replace('"', '\'');
  }

  Scope scope;
  FrameLayout bytes;

  Template src1;
  Template dst1;

  @Before
  public void setup() {
    scope = new Scope();
    bytes = VArrayLayout.newFromBuilder(scope, NumVar.UINT8);
    dst1 =
        arrayOf(
            new Template.Union(
                NumVar.UINT8, null, NumVar.INT32, arrayOf(NumVar.INT32), loopExit(NumVar.INT32)),
            new Template.Union(
                NumVar.UINT8,
                null,
                NumVar.INT32,
                arrayOf(NumVar.UINT8, NumVar.UINT8, NumVar.UINT8),
                loopExit(NumVar.INT32)),
            new Template.Union(
                NumVar.UINT8, null, NumVar.INT32, arrayOf(NumVar.INT32), Core.NONE.asTemplate),
            new Template.Union(NumVar.UINT8, null, arrayOf(NumVar.INT32), loopExit(NumVar.INT32)));
    src1 =
        arrayOf(
            new Template.Union(
                NumVar.UINT8,
                null,
                NumVar.INT32.withIndex(1),
                arrayOf(NumVar.INT32.withIndex(1)),
                loopExit(constant(0))),
            new Template.Union(
                NumVar.UINT8.withIndex(2),
                null,
                NumVar.INT32.withIndex(3),
                arrayOf(constant(0), constant(0), constant(1))),
            new Template.Union(
                NumVar.UINT8.withIndex(4),
                null,
                NumVar.INT32.withIndex(5),
                arrayOf(NumVar.INT32.withIndex(5)),
                Core.NONE.asTemplate),
            new Template.Union(
                NumVar.UINT8.withIndex(6),
                null,
                arrayOf(NumVar.INT32.withIndex(7)),
                loopExit(NumVar.INT32.withIndex(7))));
  }

  /**
   * A simple VarAllocator that simulates how we allocate registers for code generation.
   *
   * <p>NumVar and RefVar indices are register numbers, and each register is one of int, double, or
   * pointer (int registers are used for uint8s).
   */
  static class RegisterAllocator implements TemplateBuilder.VarAllocator {
    enum RegisterType {
      INT,
      DOUBLE,
      PTR
    }

    static final int NUM_VAR_TYPES = RegisterType.values().length;

    /**
     * Maps register number to type; shared when a RegisterAllocator is duplicated, so that each
     * branch of a union uses the same type for a given register.
     */
    final List<RegisterType> types;

    /** Indexed by RegisterType.ordinal(). */
    final int[] next;

    RegisterAllocator() {
      types = new ArrayList<>();
      next = new int[NUM_VAR_TYPES];
    }

    RegisterAllocator(RegisterAllocator toDuplicate) {
      types = toDuplicate.types;
      next = Arrays.copyOf(toDuplicate.next, NUM_VAR_TYPES);
    }

    int numAllocated() {
      return types.size();
    }

    @SuppressWarnings("EnumOrdinal")
    private int allocVar(RegisterType varType) {
      // Return the first register number greater than or equal to next[varType] that has the
      // requested type.
      int n = next[varType.ordinal()];
      for (; ; n++) {
        if (n == types.size()) {
          types.add(varType);
          break;
        } else if (types.get(n) == varType) {
          break;
        }
      }
      next[varType.ordinal()] = n + 1;
      return n;
    }

    @Override
    public NumVar allocNumVar(NumVar forEncoding) {
      RegisterType varType =
          (forEncoding.encoding == NumEncoding.FLOAT64) ? RegisterType.DOUBLE : RegisterType.INT;
      return forEncoding.withIndex(allocVar(varType));
    }

    @Override
    public int allocRefVar() {
      return allocVar(RegisterType.PTR);
    }

    @Override
    public RegisterAllocator duplicate() {
      return new RegisterAllocator(this);
    }

    @Override
    public void resetTo(VarAllocator other) {
      System.arraycopy(((RegisterAllocator) other).next, 0, next, 0, NUM_VAR_TYPES);
    }

    @Override
    public void union(VarAllocator other) {
      int[] otherNext = ((RegisterAllocator) other).next;
      for (int i = 0; i < NUM_VAR_TYPES; i++) {
        next[i] = Math.max(next[i], otherNext[i]);
      }
    }
  }

  @Test
  public void basic() {
    RegisterAllocator allocator = new RegisterAllocator();
    CopyPlan initial = CopyPlan.create(src1, dst1.toBuilder().build(allocator), false);
    assertThat(cleanString(initial))
        .isEqualTo(
            """
            copy(b0, b0), b0⸨0:copy(i1, i1); 1:copy(i1, i1); 2:set(0, i1)⸩, \
            copy(b2, b2), b2⸨0:copy(i3, i3); 1:set(0, b3), set(0, b4), set(1, b5)⸩, \
            copy(b4, b6), b4⸨0:copy(i5, i7); 1:copy(i5, i7); 2:EMPTY⸩, \
            copy(b6, b8), b6⸨0:copy(i7, i9); 1:copy(i7, i9)⸩\
            """);
    CopyPlan optimized =
        CopyOptimizer.optimize(initial, 0, 0, allocator.numAllocated(), Policy.BASE);
    assertThat(cleanString(optimized))
        .isEqualTo(
            """
            copy(i1, i1), copy(i5, i7), copy(i7, i9), \
            copy(b0, b0), b0⸨0:EMPTY; 1:EMPTY; 2:set(0, i1)⸩, \
            copy(b2, b2), b2⸨0:copy(i3, i3); 1:set(0, b3), set(0, b4), set(1, b5)⸩, \
            copy(b4, b6), \
            copy(b6, b8)\
            """);
  }

  @Test
  public void toRegisters() {
    RegisterAllocator allocator = new RegisterAllocator();
    Template dst = dst1.toBuilder().build(allocator);
    CopyPlan initial = CopyPlan.create(src1, dst, false);
    assertThat(cleanString(CopyOptimizer.toRegisters(initial, 0, allocator.numAllocated(), dst)))
        .isEqualTo(
            """
            copy(i1, i1), set(0, b4), set(1, b5), copy(i5, i7), copy(i7, i9), \
            copy(b0, b0), b0⸨0:EMPTY; 1:EMPTY; 2:set(0, i1)⸩, \
            copy(b2, b2), b2⸨0:copy(i3, i3); 1:set(0, b3)⸩, \
            copy(b4, b6), \
            copy(b6, b8)\
            """);
  }

  @Test
  public void initialization() {
    Template src =
        arrayOf(
            constant(-3),
            new Template.Union(NumVar.UINT8, null, NumVar.INT32, loopExit(constant(6))),
            Core.NONE.asTemplate,
            loopExit(constant(6)));
    RegisterAllocator allocator = new RegisterAllocator();
    src = src.toBuilder().build(allocator);
    int dstStart = allocator.numAllocated();
    Template dst = dst1.toBuilder().build(allocator);
    CopyPlan initial = CopyPlan.create(src, dst, false);
    assertThat(
            cleanString(
                CopyOptimizer.toRegisters(initial, dstStart, allocator.numAllocated(), dst)))
        .isEqualTo(
            """
            set(0, b6), set(0, b7), set(0, i9), \
            set(0, b2), set(-3, i3), \
            b0⸨0:set(0, b4), copy(i1, i5); 1:set(2, b4), set(6, i5)⸩, \
            set(2, b8), \
            set(1, b10), set(6, i11)\
            """);
  }

  @Test
  public void equalsRegisters() {
    RegisterAllocator allocator = new RegisterAllocator();
    CopyPlan initial = CopyPlan.create(src1, dst1.toBuilder().build(allocator), false);
    assertThat(cleanString(CopyOptimizer.equalsRegisters(initial, 0, allocator.numAllocated())))
        .isEqualTo(
            """
            copy(i7, i9), \
            copy(b0, b0), b0⸨0:copy(i1, i1); 1:copy(i1, i1); 2:set(0, i1)⸩, \
            copy(b2, b2), b2⸨0:copy(i3, i3); 1:set(0, b3), set(0, b4), set(1, b5)⸩, \
            copy(b4, b6), b4⸨0:copy(i5, i7); 1:copy(i5, i7); 2:EMPTY⸩, \
            copy(b6, b8)\
            """);
  }

  @Test
  public void toRecord() {
    RecordLayout layout = RecordLayout.newFromBuilder(scope, dst1.toBuilder());
    CopyPlan initial = CopyPlan.create(src1, layout.template, true);
    assertThat(cleanString(initial))
        .isEqualTo(
            """
            copy(b0, b0), b0⸨0:copy(i1, i4); 1:copy(i1, i4); 2:set(0, i4)⸩, \
            copy(b2, b1), b2⸨0:copy(i3, i8); 1:set(0, b2), set(0, b3), set(1, b8)⸩, \
            copy(b4, b12), b4⸨0:copy(i5, i16); 1:copy(i5, i16); 2:EMPTY⸩, \
            copy(b6, b13), b6⸨0:copy(i7, i20); 1:copy(i7, i20)⸩\
            """);
    assertThat(cleanString(CopyOptimizer.toRecord(initial, layout)))
        .isEqualTo(
            """
            set(0, b2), set(0, b3), copy(i5, i16), copy(i7, i20), \
            copy(b0, b0), b0⸨0:copy(i1, i4); 1:copy(i1, i4); 2:set(0, i4)⸩, \
            copy(b2, b1), b2⸨0:copy(i3, i8); 1:set(1, b8)⸩, \
            copy(b4, b12), \
            copy(b6, b13)\
            """);
  }

  @Test
  public void refs() {
    Template dst =
        arrayOf(
            new Template.Union(
                NumVar.UINT8, null, bytes.asRefVar, Core.NONE.asTemplate, Core.STRING.asRefVar),
            new Template.Union(
                NumVar.UINT8, null, bytes.asRefVar, Core.NONE.asTemplate, Core.STRING.asRefVar));
    Template src =
        arrayOf(
            new Template.Union(null, bytes.asRefVar, bytes.asRefVar, Core.STRING.asRefVar),
            new Template.Union(
                null,
                bytes.asRefVar.withIndex(1),
                bytes.asRefVar.withIndex(1),
                Core.NONE.asTemplate,
                Core.STRING.asRefVar.withIndex(1)));
    RegisterAllocator allocator = new RegisterAllocator();
    dst = dst.toBuilder().build(allocator);
    CopyPlan initial = CopyPlan.create(src, dst, false);
    assertThat(cleanString(initial))
        .isEqualTo(
            """
            x0⸨Array:set(0, b0), copy(x0, x1); String:set(2, b0), copy(x0, x1)⸩, \
            x1⸨Array:set(0, b2), copy(x1, x3); None:set(1, b2); String:set(2, b2), copy(x1, x3)⸩\
            """);
    assertThat(cleanString(CopyOptimizer.toRegisters(initial, 0, allocator.numAllocated(), dst)))
        .isEqualTo(
            """
            copy(x0, x1), set(null, x3), x0⸨Array:set(0, b0); String:set(2, b0)⸩, \
            x1⸨Array:set(0, b2), copy(x1, x3); None:set(1, b2); String:set(2, b2), copy(x1, x3)⸩\
            """);
  }

  @Test
  public void trickyRefs() {
    Template dst =
        new Template.Union(
            NumVar.UINT8,
            null,
            arrayOf(bytes.asRefVar, Core.STRING.asRefVar),
            loopExit(Core.STRING.asRefVar));
    Template src =
        new Template.Union(
            NumVar.UINT8,
            null,
            arrayOf(Core.EMPTY_ARRAY.asTemplate, Core.STRING.asRefVar),
            loopExit(Core.STRING.asRefVar));
    RegisterAllocator allocator = new RegisterAllocator();
    dst = dst.toBuilder().build(allocator);
    CopyPlan initial = CopyPlan.create(src, dst, false);
    assertThat(cleanString(initial))
        .isEqualTo("copy(b0, b0), b0⸨0:set([], x1), copy(x0, x2); 1:copy(x0, x1)⸩");
    assertThat(cleanString(CopyOptimizer.toRegisters(initial, 0, allocator.numAllocated(), dst)))
        .isEqualTo("set(null, x2), copy(b0, b0), b0⸨0:set([], x1), copy(x0, x2); 1:copy(x0, x1)⸩");
  }
}
