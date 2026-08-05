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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.retrolang.Vm;
import org.retrolang.util.Bits;
import org.retrolang.util.Bits.Op;

/// Implementation of Vm.InstructionBlock. A builder for an [InstructionBlock].
class VmInstructionBlock implements Vm.InstructionBlock {
  final ModuleBuilder module;
  final InstructionBlock ib;

  private int currentLineNum;

  private int numBranchTargets;
  private int numCalls;

  /// Records the Instruction that most recently set each Local after the most recent instruction.
  /// Null if the next instruction is not reachable.
  private Instruction.LocalSources localSources = Instruction.LocalSources.EMPTY;

  private final List<Local> locals = new ArrayList<>();
  private final List<Instruction> instructions = new ArrayList<>();

  VmInstructionBlock(ModuleBuilder module, int numArgs, int numResults, String source) {
    this.module = module;
    this.ib = new InstructionBlock(numArgs, numResults, source, module.module);
  }

  /// We should only emit instructions after all args have been added, and before calling [#done].
  /// Throws an IllegalStateException if those conditions are not met.
  private void checkEmitOK() {
    Preconditions.checkState(locals.size() >= ib.numArgs, "missing addArg()");
    Preconditions.checkState(!ib.isInitialized(), "InstructionBlock is done()");
  }

  private void add(Instruction inst) {
    checkEmitOK();
    // If we're asked to emit an unreachable instruction we just drop it on the floor.
    if (nextInstructionIsReachable() || inst instanceof Instruction.BranchTarget) {
      inst.initialize(ib, currentLineNum, instructions.size());
      instructions.add(inst);
      localSources = inst.updateLocalSources(localSources);
    }
  }

  /// Throws an IllegalArgumentException if the given Local is not from this InstructionBlock.
  private void checkLocal(Local local) {
    Preconditions.checkArgument(local.ib == ib, "Local from another InstructionBlock");
  }

  /// Casts the argument to a Local and verifies that it's from this InstructionBlock.
  private Local checkVmLocal(Vm.Local vmLocal) {
    Local local = (Local) vmLocal;
    checkLocal(local);
    return local;
  }

  /// Verifies that each of the given Vm.Locals is a Local, and returns their indices as a Bits.
  private Bits checkVmLocalSet(Vm.Local[] locals) {
    if (locals.length == 0) {
      return Bits.EMPTY;
    }
    Bits.Builder result = new Bits.Builder();
    for (Vm.Local local : locals) {
      result.set(checkVmLocal(local).index);
    }
    return result.build();
  }

  /// Casts the argument to a VmExpr and verifies that any Locals it contains are from this
  /// InstructionBlock.
  private VmExpr checkExpr(Vm.Expr vmExpr) {
    VmExpr expr = (VmExpr) vmExpr;
    expr.forEachLocal(this::checkLocal);
    return expr;
  }

  /// Calls [#checkExpr] on each of the given Vm.Exprs and returns an array of the results.
  private VmExpr[] checkExprs(Vm.Expr[] exprs) {
    return Arrays.stream(exprs).map(this::checkExpr).toArray(VmExpr[]::new);
  }

  /// Like [#checkVmLocal], but also accepts null (representing the write-only var "\_").
  private @Nullable Local checkOutput(Vm.Local vmLocal) {
    return (vmLocal == null) ? null : checkVmLocal(vmLocal);
  }

  /// Calls [#checkOutput] on each of the given Vm.Locals and returns an array of the results. Also
  /// verifies that there are no duplicates.
  private Local[] checkOutputs(Vm.Local[] vmLocals) {
    Local[] result = Arrays.stream(vmLocals).map(this::checkOutput).toArray(Local[]::new);
    // Ensure there are no duplicates.  Using a Bits.Builder would be O(n) instead
    // of O(n^2), but in practice n is almost always 1 or 2.
    for (int i = 1; i < result.length; i++) {
      Local local = result[i];
      if (local != null) {
        for (int j = 0; j < i; j++) {
          Preconditions.checkArgument(result[j] != local, "Outputs must be distinct");
        }
      }
    }
    return result;
  }

  /// Verifies that we can emit a branch to the given target.
  private Instruction.BranchTarget checkTarget(Vm.BranchTarget vmTarget) {
    Instruction.BranchTarget target = (Instruction.BranchTarget) vmTarget;
    Preconditions.checkArgument(target.ib() == ib, "Branching to another InstructionBlock");
    Preconditions.checkArgument(!target.isInitialized(), "Backward branch");
    return target;
  }

  @Override
  public @Nullable Local addArg(String name) {
    Preconditions.checkState(locals.size() < ib.numArgs, "All args already added");
    if (name != null) {
      return addLocal(name);
    }
    locals.add(Local.UNNAMED);
    return null;
  }

  @Override
  public Local newLocal(String name) {
    checkEmitOK();
    return addLocal(name);
  }

  private Local addLocal(String name) {
    int index = locals.size();
    Local result = new Local(ib, name, index);
    locals.add(result);
    return result;
  }

  @Override
  public Vm.BranchTarget newTarget() {
    checkEmitOK();
    return new Instruction.BranchTarget(ib, numBranchTargets++);
  }

  @Override
  public boolean nextInstructionIsReachable() {
    return localSources != null;
  }

  @Override
  public void setLineNumber(int lineNum, int charPositionInLine) {
    currentLineNum = lineNum;
  }

  @Override
  public void defineTarget(Vm.BranchTarget vmTarget) {
    checkEmitOK();
    Instruction.BranchTarget target = (Instruction.BranchTarget) vmTarget;
    Preconditions.checkArgument(target.ib() == ib, "Target not from this InstructionBlock");
    Preconditions.checkArgument(!target.isInitialized(), "Target already defined");
    if (target.hasReference()) {
      add(target);
    } else {
      // No references to this target.  Don't add it to the instruction list, but set its location
      // to ensure that it isn't redefined or branched to after this.
      // Any non-negative value would do, but this one's easily recognizable if it turns
      // up somewhere unexpected.
      target.initialize(ib, currentLineNum, 999);
    }
  }

  @Override
  public void emitSet(Vm.Local output, Vm.Expr input) {
    add(new Instruction.Set(checkOutput(output), checkExpr(input)));
  }

  @Override
  public void emitCall(Vm.Local[] outputs, Vm.Function fn, Vm.Expr... inputs) {
    CallSite callSite;
    if (nextInstructionIsReachable()) {
      callSite = new CallSite(numCalls, numCalls, fn.numResults());
      numCalls++;
    } else {
      // If this call instruction isn't reachable we don't want to allocate a CallSite but we still
      // want to check that all the arguments are reasonable.
      callSite = null;
    }
    add(new Instruction.Call(checkOutputs(outputs), (VmFunction) fn, checkExprs(inputs), callSite));
  }

  @Override
  public void emitReturn(Vm.Expr... results) {
    Preconditions.checkArgument(
        results.length == ib.numResults, "Expected %s results", ib.numResults);
    add(new Instruction.Return(checkExprs(results)));
  }

  @Override
  public void emitError(String cause, Vm.Local... include) {
    add(new Instruction.Fail(cause, checkVmLocalSet(include)));
  }

  @Override
  public void emitTrace(String message, Vm.Local... include) {
    add(new Instruction.Trace(message, checkVmLocalSet(include)));
  }

  @Override
  public void emitBranch(Vm.BranchTarget target) {
    checkEmitOK();
    add(new Instruction.Branch(checkTarget(target)));
  }

  @Override
  public void emitConditionalBranch(Vm.BranchTarget target, Vm.Local input, boolean branchIfTrue) {
    checkEmitOK();
    add(new Instruction.ConditionalBranch(checkTarget(target), checkVmLocal(input), branchIfTrue));
  }

  @Override
  public void emitTypeCheckBranch(
      Vm.BranchTarget target, Vm.Local input, Vm.Type type, boolean branchIfContains) {
    checkEmitOK();
    add(
        new Instruction.TypeCheckBranch(
            checkTarget(target), checkVmLocal(input), (VmType) type, branchIfContains));
  }

  @Override
  public void done() {
    checkEmitOK();
    // Analyze local liveness and verify that all named arguments are used.
    Bits.Builder initialLive =
        new Instruction.UpdateLiveHelper(numBranchTargets).visitInstructions(instructions);
    // Usually only the function arguments will be live at the start of the block, but it's possible
    // for locals to be; clear everything except the arguments.
    Op.INTERSECTION.rangeInto(initialLive, 0, ib.numArgs - 1);
    ib.initialize(
        ImmutableList.copyOf(locals),
        ImmutableList.copyOf(instructions),
        initialLive.build(),
        numCalls,
        numBranchTargets);
  }

  @Override
  public void addMethod(Vm.Function vmFn, Vm.MethodPredicate predicate, boolean isDefault) {
    Preconditions.checkState(ib.isInitialized(), "Must call done() before addMethod()");
    VmFunction.General fn = (vmFn instanceof VmFunction.General gFn) ? gFn : null;
    Preconditions.checkArgument(
        fn != null && (fn.module == module.module || fn.open),
        "Cannot define methods for %s",
        vmFn);
    Preconditions.checkArgument(
        fn.numArgs == ib.numArgs && fn.numResults == ib.numResults,
        "Block (numArgs=%s, numResults=%s) does not match function (numArgs=%s, numResults=%s)",
        ib.numArgs,
        ib.numResults,
        fn.numArgs,
        fn.numResults);
    module.addMethod(
        new VmMethod(
            fn, (MethodPredicate) predicate, isDefault, ib, /* baseWeight= */ 1, ib.memoFactory()));
  }

  @Override
  public Vm.Value applyToArgs(Vm.ResourceTracker vmTracker, Vm.Value... args)
      throws Vm.RuntimeError {
    Preconditions.checkState(ib.isInitialized(), "Must call done() before applyToArgs()");
    ResourceTracker tracker = (ResourceTracker) vmTracker;
    return VmValue.of(ib.applyToArgs(tracker, ib.memoForApply(), args), tracker);
  }
}
