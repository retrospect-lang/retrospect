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

import java.util.Arrays;
import org.retrolang.code.CodeBuilder;
import org.retrolang.code.Register;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.TemplateBuilder.VarAllocator;

/// A VarAllocator that allocates registers in a given CodeBuilder to represent values.
class RegisterAllocator implements VarAllocator {
  final CodeBuilder cb;

  /// If true, registers will be allocated with [CodeBuilder#newArg]; otherwise they will be
  /// allocated with [CodeBuilder#newRegister].
  final boolean allocateArgs;

  /// Indexed by (ordinal) VarType; all registers of that type with indices less than this are
  /// already in use.
  private final int[] next;

  /// The three types of registers we can allocate. Note that the JVM doesn't have registers smaller
  /// than an int32, so uint8 values are stored as ints.
  enum VarType {
    INT(int.class, NumVar.INT32),
    DOUBLE(double.class, NumVar.FLOAT64),
    PTR(Object.class, null);

    final Class<?> type;
    final NumVar numVar;

    VarType(Class<?> type, NumVar numVar) {
      this.type = type;
      this.numVar = numVar;
    }
  }

  static final int NUM_VAR_TYPES = VarType.values().length;

  /// Creates a new RegisterAllocator. This allocator must be used before any other operations that
  /// might allocate registers are called.
  RegisterAllocator(CodeBuilder cb, boolean allocateArgs) {
    this.cb = cb;
    this.allocateArgs = allocateArgs;
    int firstRegister = cb.numRegisters();
    next = new int[NUM_VAR_TYPES];
    Arrays.fill(next, firstRegister);
  }

  private RegisterAllocator(RegisterAllocator toCopy) {
    cb = toCopy.cb;
    allocateArgs = toCopy.allocateArgs;
    next = Arrays.copyOf(toCopy.next, NUM_VAR_TYPES);
  }

  /// Finds the next available register of the given type, or creates a new one.
  private int allocVar(VarType varType) {
    int n = next[varType.ordinal()];
    // Scan the already allocated registers with indices >= n; if we find one with the
    // right type, use it, otherwise allocate a new one.
    for (; ; n++) {
      if (n == cb.numRegisters()) {
        // We need a new register
        Register r = allocateArgs ? cb.newArg(varType.type) : cb.newRegister(varType.type);
        assert r.index == n;
        break;
      }
      Register r = cb.register(n);
      if (r.type() == varType.type) {
        break;
      }
    }
    next[varType.ordinal()] = n + 1;
    return n;
  }

  @Override
  public NumVar allocNumVar(NumVar forEncoding) {
    VarType varType = (forEncoding.encoding == NumEncoding.FLOAT64) ? VarType.DOUBLE : VarType.INT;
    return varType.numVar.withIndex(allocVar(varType));
  }

  @Override
  public int allocRefVar() {
    return allocVar(VarType.PTR);
  }

  @Override
  public boolean refVarsTrackLayoutEvolution() {
    // We'd rather be consistently out-of-date than change our mind about what layout we're using
    // part way through code generation.
    return false;
  }

  @Override
  public RegisterAllocator duplicate() {
    return new RegisterAllocator(this);
  }

  @Override
  public void resetTo(VarAllocator other) {
    int[] otherNext = ((RegisterAllocator) other).next;
    System.arraycopy(otherNext, 0, next, 0, NUM_VAR_TYPES);
  }

  @Override
  public void union(VarAllocator other) {
    int[] otherNext = ((RegisterAllocator) other).next;
    for (int i = 0; i < NUM_VAR_TYPES; i++) {
      next[i] = Math.max(next[i], otherNext[i]);
    }
  }
}
