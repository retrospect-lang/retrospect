package org.retrolang.impl;

import static com.google.common.truth.Truth.assertThat;

import org.antlr.v4.runtime.CharStreams;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.Vm;
import org.retrolang.compiler.Compiler;

/**
 * Compiles a trivial program, runs it a couple of times, and prints out the instructions and
 * associated memos as would be available for code generation. Intended to test the {@link
 * InstructionBlock#printInstructions()} code, but might also he useful as a starting point for
 * other tests.
 */
@RunWith(JUnit4.class)
public class InstructionBlockTest {

  VirtualMachine vm = new VirtualMachine();
  ResourceTracker tracker = vm.newResourceTracker(1000, 4, false);

  private InstructionBlock compile(String program) {
    Vm.ModuleBuilder module = vm.newModule("(input)");
    Vm.InstructionBlock instructionBlock =
        Compiler.compile(CharStreams.fromString(program), "program", vm, module, "input");
    module.build();
    return ((VmInstructionBlock) instructionBlock).ib;
  }

  private static Vm.Value intValue(int n) {
    return VmExpr.Constant.of(NumValue.of(n, Allocator.UNCOUNTED));
  }

  static final String PROGRAM_1 =
      """
      array = [3, 5, input + 1]
      _ = startUpdate(array=, [2])
      updated = array @ input
      return updated
      """;

  static final String COMPILED_1 =
      """
        0:    ⟨_t0:b0⟩ = add(input, 1)
        1:    array = [3, 5, _t0]
        2:    ⟨_, array:ArrayUpdater([3, ToBeSet, b0], 1)⟩ = startUpdate(array, [2])
        3:    ⟨updated:[3, b0, b0]⟩ = at(array, input)
        4:    return ⟨updated:[3, b0, b0]⟩
      """;

  @Test
  public void check1() throws Vm.RuntimeError {
    InstructionBlock ib = compile(PROGRAM_1);
    MethodMemo memo = ib.memoForApply();
    Value result = ib.applyToArgs(tracker, memo, intValue(6));
    assertThat(result.toString()).isEqualTo("[3, 6, 7]");
    Value result2 = ib.applyToArgs(tracker, memo, intValue(16));
    assertThat(result2.toString()).isEqualTo("[3, 16, 17]");
    assertThat(ib.printInstructions(memo)).isEqualTo(COMPILED_1);
  }

  static final String PROGRAM_2 =
      """
      array = [input + 1, input + 2, input + 3, input + 4, input + 5]
      [a, _, c, d, _] = array
      _ = a
      _ = d
      return c
      """;

  static final String COMPILED_2 =
      """
        0:    ⟨_t0:b0⟩ = add(input, 1)
        1:    ⟨_t1:b0⟩ = add(input, 2)
        2:    ⟨_t2:b0⟩ = add(input, 3)
        3:    ⟨_t3:b0⟩ = add(input, 4)
        4:    ⟨_t4:b0⟩ = add(input, 5)
        5:    array = [_t0, _t1, _t2, _t3, _t4]
        6:    ⟨_, _, c:b0, _, _⟩ = unarray5(array)
        7:    return ⟨c:b0⟩
      """;

  @Test
  public void check2() throws Vm.RuntimeError {
    InstructionBlock ib = compile(PROGRAM_2);
    MethodMemo memo = ib.memoForApply();
    Value result = ib.applyToArgs(tracker, memo, intValue(0));
    assertThat(result.toString()).isEqualTo("3");
    Value result2 = ib.applyToArgs(tracker, memo, intValue(1));
    assertThat(result2.toString()).isEqualTo("4");
    assertThat(ib.printInstructions(memo)).isEqualTo(COMPILED_2);
  }
}
