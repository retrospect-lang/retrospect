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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.retrolang.Vm;
import org.retrolang.impl.Instruction.BranchTarget;
import org.retrolang.util.Bits;

/**
 * A sequence of Instructions that can be executed. Constructed by a VmInstructionBlock.
 *
 * <p>The InstructionBlock is created at the same time as the VmInstructionBlock, but the {@link
 * #locals}, {@link #instructions}, {@link #liveArgs}, and {@link #numBranchTargets} fields are only
 * filled in when {@link VmInstructionBlock#done} is called.
 */
class InstructionBlock implements MethodImpl {
  final int numArgs;
  final int numResults;
  final String source;
  final VmModule module;

  // These are set by initialize()
  private ImmutableList<Local> locals;
  private ImmutableList<Instruction> instructions;
  private Bits liveArgs;
  private int numCalls;
  private int numBranchTargets;
  private int numValueMemos;

  /**
   * Creates a new InstructionBlock.
   *
   * @param numArgs The number of args that will be passed to {@link #applyToArgs}. If this
   *     InstructionBlock is used to define a method (via {@link Vm.InstructionBlock#addMethod}),
   *     must match the function's {@code numArgs}.
   * @param numResults The number of results that will be returned from {@link #applyToArgs}. If
   *     this InstructionBlock is used to define a method (via {@link
   *     Vm.InstructionBlock#addMethod}), must match the function's {@code numResults}.
   * @param source An identifier for where the source code may be found; will appear in stack
   *     traces.
   */
  InstructionBlock(int numArgs, int numResults, String source, VmModule module) {
    this.numArgs = numArgs;
    this.numResults = numResults;
    this.source = source;
    this.module = module;
  }

  /**
   * Finishes setting up this InstructionBlock. Must be called exactly once on each InstructionBlock
   * before any methods (other than {@link #isInitialized}) are called.
   *
   * @param locals All of the Locals created by this InstructionBlock, in index order. The first
   *     {@link #numArgs} locals will be intitialized with the arguments before execution begins;
   *     all other locals will be undefined and must be assigned a value before use. If an argument
   *     is not referred to by any instructions the corresponding entry in {@code locals} may be
   *     null.
   * @param liveArgs A subset of {@code 0..(numArgs-1)}, identifying which argument values are used
   *     by the InstructionBlock; unused arguments are discarded and any corresponding local is not
   *     initialized.
   * @param numBranchTargets The number of {@link BranchTarget}s allocated in this InstructionBlock.
   */
  void initialize(
      ImmutableList<Local> locals,
      ImmutableList<Instruction> instructions,
      Bits liveArgs,
      int numCalls,
      int numBranchTargets) {
    assert this.locals == null && locals != null && instructions != null;
    this.locals = locals;
    this.instructions = instructions;
    this.liveArgs = liveArgs;
    this.numCalls = numCalls;
    this.numBranchTargets = numBranchTargets;
    // We start with a ValueMemo for each Call instruction, and then add an additional ValueMemo
    // for each BranchTarget that merges locals from different sources.
    int numValueMemos = numCalls;
    for (Instruction inst : instructions) {
      if (inst instanceof Instruction.BranchTarget target
          && target.setValueMemoIndex(numValueMemos)) {
        ++numValueMemos;
      }
    }
    this.numValueMemos = numValueMemos;
  }

  /** Returns true if {@link #initialize} has been called. */
  boolean isInitialized() {
    return locals != null;
  }

  /** Returns the specified Local. */
  Local getLocal(int i) {
    return locals.get(i);
  }

  /** Returns the names of the specified locals, separated by commas. */
  String localNames(int... indices) {
    return Arrays.stream(indices)
        .mapToObj(i -> locals.get(i).toString())
        .collect(Collectors.joining(", "));
  }

  /** Returns the names of the specified locals, separated by commas. */
  String localNames(Bits indices) {
    return indices.stream()
        .mapToObj(i -> locals.get(i).toString())
        .collect(Collectors.joining(", "));
  }

  /** Allocates an array suitable for storing this InstructionBlock's locals. */
  @RC.Out
  Object[] newLocalValues(Allocator allocator) {
    return allocator.allocObjectArray(locals.size());
  }

  /**
   * Returns an appropriate MethodMemo Factory for creating a VmMethod from this InstructionBlock.
   */
  MethodMemo.Factory memoFactory() {
    return MethodMemo.Factory.create(numArgs, numResults, numCalls, numValueMemos);
  }

  /** Returns a new MethodMemo suitable for passing to {@link #applyToArgs}. */
  MethodMemo memoForApply() {
    return new MethodMemo.AnonymousFactory(numArgs, numResults, numCalls, numValueMemos).newMemo();
  }

  /**
   * Executes this InstructionBlock with the given initial values for its arguments. Should only be
   * called on InstructionBlocks with {@link #numResults} == 1.
   *
   * <p>Returns the value returned by the InstructionBlock or throws a {@link Vm.RuntimeError}.
   */
  @RC.Out
  Value applyToArgs(ResourceTracker tracker, MethodMemo memo, Vm.Value... args)
      throws Vm.RuntimeError {
    Preconditions.checkArgument(args.length == numArgs);
    Preconditions.checkArgument(numResults == 1);
    Preconditions.checkArgument(memo.perMethod == null);
    Waiter waiter = new Waiter();
    // It's simplest to just make the RThread uncounted, since we know it can't outlive the
    // applyToArgs() call and the only counted reference it holds (the suspended stack) is
    // guaranteed to be null by the time we return.
    new RThread(Allocator.UNCOUNTED, waiter)
        .run(
            tracker,
            tstate -> {
              @RC.Counted Object[] localValues = newLocalValues(tstate);
              for (int i = 0; i < args.length; i++) {
                Value arg = VmValue.asValue(args[i], tracker);
                if (liveArgs.test(i)) {
                  localValues[i] = arg;
                } else {
                  tstate.dropValue(arg);
                }
              }
              tstate.setStackRest(TStack.BASE);
              execute(tstate, localValues, 0, memo);
            });
    try {
      ForkJoinPool.managedBlock(waiter);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    if (waiter.errorStack == null) {
      return waiter.result;
    } else {
      throw new RuntimeError(null, tracker, waiter.errorStack);
    }
  }

  private static class Waiter implements RThread.Waiter, ForkJoinPool.ManagedBlocker {
    @RC.Counted Value result;
    @RC.Counted TStack errorStack;

    @Override
    public synchronized void threadDone(
        TState tstate, @RC.In Value result, @RC.In TStack errorStack) {
      this.result = result;
      this.errorStack = errorStack;
      notifyAll();
    }

    @Override
    public synchronized boolean block() throws InterruptedException {
      while (!isReleasable()) {
        wait();
      }
      return true;
    }

    @Override
    public synchronized boolean isReleasable() {
      return result != null || errorStack != null;
    }
  }

  /** Executes this InstructionBlock with the given initial values for its arguments. */
  @Override
  public void execute(TState tstate, ResultsInfo results, MethodMemo mMemo, @RC.In Object[] args) {
    assert Value.containsValues(args, numArgs);
    @RC.Counted Object[] localValues;
    if (args.length >= this.locals.size()) {
      // If the args array is long enough we can reuse it to store the locals
      // (after dropping any args that are unused).
      for (int i = 0; i < numArgs; i++) {
        if (!liveArgs.test(i)) {
          tstate.clearElement(args, i);
        }
      }
      localValues = args;
    } else {
      // Otherwise we need to allocate a new array for the locals, initialize it from the args,
      // and then drop the args array.
      localValues = newLocalValues(tstate);
      for (int i = 0; i < numArgs; i++) {
        if (liveArgs.test(i)) {
          localValues[i] = args[i];
          args[i] = null;
        }
      }
      tstate.dropReference(args);
    }
    execute(tstate, localValues, 0, mMemo);
  }

  /**
   * Executes instructions beginning at pc. Either saves results in tstate and returns (if execution
   * reaches a return statement), or starts unwinding.
   */
  void execute(TState tstate, @RC.In Object[] localValues, int pc, MethodMemo memo) {
    try {
      do {
        Instruction inst = instructions.get(pc);
        if (tstate.isOverMemoryLimit()) {
          inst.startUnwind(tstate, localValues);
          Err.OUT_OF_MEMORY.pushUnwind(tstate);
          break;
        }
        try {
          tstate.syncWithCoordinator();
          pc = inst.execute(tstate, localValues, memo);
        } catch (RuntimeException e) {
          // Shouldn't happen, but if it does try to construct a meaningful stack entry to go along
          // with the "internal error" message.
          e.printStackTrace();
          System.err.format("%s while at %s in %s", Arrays.toString(localValues), pc, this);
          // We may have been part way through returning results
          tstate.clearResults();
          if (!tstate.unwindStarted()) {
            inst.startUnwind(tstate, localValues);
          }
          Err.INTERNAL_ERROR.pushUnwind(tstate, e.toString());
          break;
        }
      } while (pc >= 0);
    } finally {
      tstate.dropReference(localValues);
    }
  }

  @Override
  public void emit(CodeGen codeGen, ResultsInfo results, MethodMemo mMemo, Object[] args) {
    // Initialize the locals array from the arguments.
    Value[] localValues = new Value[locals.size()];
    Arrays.fill(localValues, Core.UNDEF);
    for (int i = 0; i < numArgs; i++) {
      if (liveArgs.test(i)) {
        localValues[i] = (Value) args[i];
      }
    }
    // Create an EmitState, and then generate code for each of the instructions
    EmitState emitState = new EmitState(mMemo, localValues, numBranchTargets);
    for (int pc = 0; pc < instructions.size(); pc++) {
      Instruction instruction = instructions.get(pc);
      if (!codeGen.cb.nextIsReachable() && !(instruction instanceof Instruction.BranchTarget)) {
        // Don't generate code for unreachable instructions
        continue;
      }
      Value stackEntry =
          instruction.stackEntryType().newStackEntry(codeGen.tstate(), emitState.locals);
      codeGen.setCurrentInstruction(stackEntry);
      if (codeGen.needNewEscape() && instruction.couldEscape()) {
        codeGen.setNewEscape(stackEntry);
      }
      instruction.emit(codeGen, emitState);
    }
  }

  @Override
  public VmModule module() {
    return module;
  }

  @Override
  public String toString() {
    if (isInitialized()) {
      return String.format("%s:%s", source, instructions.get(0).lineNum());
    } else {
      return super.toString();
    }
  }

  /**
   * We (lazily) create a TargetInfo for each BranchTarget in the InstructionBlock, to save the
   * local values that will be used when emitting the instruction following the BranchTarget.
   *
   * <p>This could be made a record (since both its fields are final), but that doesn't really feel
   * appropriate; the equals() method wouldn't make sense, and the Value[] it holds isn't immutable.
   */
  static class TargetInfo {
    /**
     * A Destination corresponding to this BranchTarget, with a value for each local that is
     * harmonized.
     */
    final Destination destination;

    /** The values of all unharmonized locals. */
    final Value[] unharmonizedLocals;

    TargetInfo(Destination destination, Value[] unharmonizedLocals) {
      this.destination = destination;
      this.unharmonizedLocals = unharmonizedLocals;
    }
  }

  /** An EmitState holds the state involved in a single call to {@link #emit}. */
  static class EmitState {
    /** A MethodMemo created by the result of {@link #memoFactory}. */
    final MethodMemo memo;

    /** A TargetInfo for each BranchTarget in this InstructionBlock. */
    final TargetInfo[] targets;

    /** The value of each local when the current instruction is reached. */
    Value[] locals;

    EmitState(MethodMemo memo, Value[] locals, int numBranchTargets) {
      this.memo = memo;
      this.targets = new TargetInfo[numBranchTargets];
      this.locals = locals;
    }

    void setLocal(int index, Value value) {
      assert locals[index] == null || locals[index] == Core.UNDEF;
      locals[index] = value;
    }

    void clearLocals(int[] toClear) {
      for (int i : toClear) {
        locals[i] = null;
      }
    }
  }

  /**
   * Returns a readable representation of the instructions in this block; currently only used for
   * debugging.
   */
  String printInstructions() {
    return IntStream.range(0, instructions.size())
        .mapToObj(
            i -> {
              Instruction inst = instructions.get(i);
              String s = inst.toString();
              if (inst instanceof BranchTarget) {
                s = s + ":";
              } else {
                s = "   " + s;
              }
              return String.format("%3d: %s\n", i, s);
            })
        .collect(Collectors.joining());
  }
}
