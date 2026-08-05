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

import org.objectweb.asm.Opcodes;

/// Each ConditionalBranch instance corresponds to a JVM instruction (with the same name) that pops
/// one or two values from the stack and conditionally branches based on their values.
///
/// In addition to emitting the instruction, ConditionalBranch instances make it easy to find the
/// inverse instruction (all these instructions have a matching instruction that branches in exactly
/// the opposite conditions) and, for instructions that compare two integers from the stack, the
/// instructions that makes the same comparison between a single integer and zero.
public enum ConditionalBranch {
  // The ordering here matters!  Instructions are paired with their inverses, and the integer
  // comparisons with and without implied zero are grouped together and in the same order.
  // Don't change anything here without verifying that invert(), isIcmp(), and withZero() will
  // still work.
  IFEQ(Opcodes.IFEQ),
  IFNE(Opcodes.IFNE),
  IFLT(Opcodes.IFLT),
  IFGE(Opcodes.IFGE),
  IFLE(Opcodes.IFLE),
  IFGT(Opcodes.IFGT),
  IF_ICMPEQ(Opcodes.IF_ICMPEQ),
  IF_ICMPNE(Opcodes.IF_ICMPNE),
  IF_ICMPLT(Opcodes.IF_ICMPLT),
  IF_ICMPGE(Opcodes.IF_ICMPGE),
  IF_ICMPLE(Opcodes.IF_ICMPLE),
  IF_ICMPGT(Opcodes.IF_ICMPGT),
  IF_ACMPEQ(Opcodes.IF_ACMPEQ),
  IF_ACMPNE(Opcodes.IF_ACMPNE),
  IFNULL(Opcodes.IFNULL),
  IFNONNULL(Opcodes.IFNONNULL);

  public final int opcode;

  private static final ConditionalBranch[] VALUES = ConditionalBranch.values();

  ConditionalBranch(int opcode) {
    this.opcode = opcode;
  }

  /// Returns the ConditionalBranch that will branch under the opposite conditions to this one.
  public ConditionalBranch invert() {
    return VALUES[ordinal() ^ 1];
  }

  private static final int ICMP_OFFSET = IF_ICMPEQ.ordinal() - IFEQ.ordinal();

  /// Returns true if this an IF\_ICMP\* branch, i.e. it takes two integers from the stack.
  public boolean isIcmp() {
    return ordinal() >= IF_ICMPEQ.ordinal() && ordinal() <= IF_ICMPGT.ordinal();
  }

  /// This must be an IF\_ICMP\* branch; returns the equivalent ConditionalBranch with an implied
  /// second argument of zero (e.g. `IF_ICMPLT.withZero()` returns `IFLT`).
  public ConditionalBranch withZero() {
    assert isIcmp();
    return VALUES[ordinal() - ICMP_OFFSET];
  }
}
