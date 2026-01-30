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
import com.google.common.collect.Lists;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.retrolang.Vm;
import org.retrolang.code.FutureBlock;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.InstructionBlock.EmitState;
import org.retrolang.impl.InstructionBlock.TargetInfo;
import org.retrolang.util.Bits;

/**
 * A VM instruction. There are nine subclasses: {@link Set}, {@link Call}, {@link Return}, {@link
 * Fail}, {@link Trace}, {@link Branch}, {@link ConditionalBranch}, and {@link TypeCheckBranch}
 * (each created by one of the {@code emit*} methods on {@link Vm.InstructionBlock}), and {@link
 * BranchTarget} (created by {@link Vm.InstructionBlock#newTarget}).
 *
 * <p>Each instance of Instruction has a corresponding StackEntryType, with fields for each of the
 * locals that are live when the instruction is executed. Because only a small fraction of
 * instructions are expected to error (and hence actually need their StackEntryType) we wait until
 * the first call to {@link #stackEntryType} before creating it.
 */
abstract class Instruction {
  // These are set by initialize()
  private InstructionBlock ib;
  private int lineNum;
  private int location = -1;

  /**
   * Before {@link #stackEntryType} is called, the set of locals that are live when starting
   * execution of this instruction; converted to a StackEntryType the first time one is needed.
   */
  private Object unwindBeforeExecution;

  /** Called when an Instruction is added to an InstructionBlock. */
  void initialize(InstructionBlock ib, int lineNum, int location) {
    assert this.location < 0 && location >= 0;
    // BranchTargets have their ib field set at creation, but are not added to the InstructionBlock
    // until defineTarget() is called.
    assert ib != null && (this.ib == null || this.ib == ib);
    this.ib = ib;
    this.lineNum = lineNum;
    this.location = location;
  }

  /**
   * Called once (from {@link UpdateLiveHelper#visitInstructions}) on each Instruction with the set
   * of locals that are live when starting execution of this instruction.
   */
  void saveLive(Bits live) {
    assert unwindBeforeExecution == null;
    unwindBeforeExecution = live;
  }

  /**
   * Given the LocalSources after the previous instruction, returns the LocalSources following
   * execution of this instruction, or null if this instruction never falls through.
   *
   * <p>The default implementation returns null.
   */
  LocalSources updateLocalSources(LocalSources sources) {
    return null;
  }

  /** Returns the InstructionBlock that this Instruction belongs to. */
  final InstructionBlock ib() {
    assert ib != null;
    return ib;
  }

  /** Returns true if {@link #initialize} has been called. */
  final boolean isInitialized() {
    return location >= 0;
  }

  /** Returns the {@code lineNum} that was passed to {@link #initialize}. */
  final int lineNum() {
    assert isInitialized();
    return lineNum;
  }

  /** Returns the {@code location} that was passed to {@link #initialize}. */
  final int location() {
    assert isInitialized();
    return location;
  }

  /**
   * {@link VmInstructionBlock#done} calls {@code updateLive} on each instruction in the block, in
   * reverse order, giving it a chance to compute any state that depends on the following
   * instructions (in particular, the liveness of locals).
   */
  abstract void updateLive(UpdateLiveHelper helper);

  /**
   * Returns the index of next instruction to execute, or -1 if {@code tstate.unwindStarted()}
   * becomes true (e.g. because of an error).
   *
   * <p>If -1 is returned, any values remaining in {@code locals} will be discarded.
   */
  abstract int execute(TState tstate, @RC.Counted Object[] locals, MethodMemo memo);

  /** Generates code for this instruction. */
  abstract void emit(CodeGen codeGen, EmitState emitState);

  /** Returns the result of {@code toString()}, prefixed with source, line number, and location. */
  final String describe() {
    return String.format("%s:%s_%s %s", ib.source, lineNum, location, this);
  }

  /** Returns this Instruction's StackEntryType. */
  synchronized StackEntryType stackEntryType() {
    if (!(unwindBeforeExecution instanceof StackEntryType)) {
      unwindBeforeExecution = new StackEntryType(this, (Bits) unwindBeforeExecution);
    }
    return (StackEntryType) unwindBeforeExecution;
  }

  /** Called if this instruction errors. */
  void startUnwind(TState tstate, Object[] locals) {
    assert !tstate.unwindStarted();
    tstate.pushUnwind(stackEntryType().newStackEntry(tstate, locals));
  }

  /**
   * Returns the value of the specified local. If its value is undefined, returns null and starts
   * unwinding.
   */
  @RC.Out
  @Nullable Object toValue(TState tstate, Local var, Object[] locals) {
    Object v = Value.fromArray(locals, var.index);
    if (v != null) {
      RefCounted.addRef(v);
      return v;
    }
    startUnwind(tstate, locals);
    // Note that pushUnwind() will put the UNDEFINED_VAR entry at the top of the stack,
    // followed by the entry describing this instruction.
    Err.UNDEFINED_VAR.pushUnwind(tstate, var.name);
    return null;
  }

  /**
   * Returns the value of the given VmExpr, substituting the values of locals from the given array.
   * If any local is undefined, returns null and starts unwinding.
   */
  @RC.Out
  @Nullable Object toValue(TState tstate, VmExpr input, Object[] locals) {
    if (input instanceof VmExpr.Constant c) {
      // VmExpr.Constant can only wrap a non-refcounted value, so we don't need to increment its
      // refcount here.
      return c.value;
    } else if (input instanceof Local local) {
      return toValue(tstate, local, locals);
    } else {
      VmExpr.Compound compound = (VmExpr.Compound) input;
      Object[] elements = toValues(tstate, compound::element, compound.baseType.size(), locals);
      if (elements == null) {
        return null;
      }
      return new CompoundValue(tstate, compound.baseType, elements);
    }
  }

  /**
   * Calls {@link #toValue(TState, VmExpr, Object[])} with each VmExpr returned by {@code inputs}.
   * Returns null if any of those calls returns null; otherwise returns an array of the results.
   */
  @RC.Out
  Object @Nullable [] toValues(
      TState tstate, IntFunction<VmExpr> inputs, int size, Object[] locals) {
    @RC.Counted Object[] values = tstate.allocObjectArray(size);
    for (int i = 0; i < size; i++) {
      Object v = toValue(tstate, inputs.apply(i), locals);
      if (v == null) {
        // This will drop any values returned by previous iterations of the loop.
        tstate.dropReference(values);
        return null;
      }
      values[i] = v;
    }
    return values;
  }

  /**
   * Calls {@link #toValue(TState, VmExpr, Object[])} with each element of {@code inputs}. Returns
   * null if any of those calls returns null; otherwise returns an array of the results.
   */
  @RC.Out
  Object[] toValues(TState tstate, VmExpr[] inputs, Object[] locals) {
    return toValues(tstate, i -> inputs[i], inputs.length, locals);
  }

  /**
   * Returns the value of the given VmExpr, substituting the values of locals from the given array.
   * Escapes if any local is undefined.
   *
   * <p>This is the code generation equivalent of {@link #toValue(TState, VmExpr, Object[])}.
   */
  Value toRValue(CodeGen codeGen, VmExpr input, Value[] locals) {
    if (input instanceof VmExpr.Constant c) {
      return c.value;
    } else if (input instanceof Local local) {
      Value v = locals[local.index];
      codeGen.escapeUnless(v.is(Core.UNDEF).not());
      return codeGen.simplify(v);
    } else {
      VmExpr.Compound compound = (VmExpr.Compound) input;
      Value[] values = toRValues(codeGen, compound::element, compound.baseType.size(), locals);
      return codeGen.tstate().asCompoundValue(compound.baseType, values);
    }
  }

  /** Calls {@link #toRValue} with each VmExpr returned by {@code inputs}. */
  Value[] toRValues(CodeGen codeGen, IntFunction<VmExpr> inputs, int size, Value[] locals) {
    Value[] result = new Value[size];
    Arrays.setAll(result, i -> toRValue(codeGen, inputs.apply(i), locals));
    return result;
  }

  /** Calls {@link #toRValue} with each element of {@code inputs}. */
  Value[] toRValues(CodeGen codeGen, VmExpr[] inputs, Value[] locals) {
    return toRValues(codeGen, i -> inputs[i], inputs.length, locals);
  }

  /**
   * Sets {@code locals[index]} (which should be null) to {@code value} (which should not be null).
   */
  static void setLocal(int index, @RC.In Value value, @RC.Counted Object[] locals) {
    Object prev = locals[index];
    if (value == null || prev != null) {
      throw new IllegalStateException(
          String.format("Can't set local %s (%s -> %s)", index, prev, value));
    }
    locals[index] = value;
  }

  /** Sets each of the specified entries of {@code locals} to null. */
  static void clearLocals(TState tstate, int[] indices, @RC.Counted Object[] locals) {
    for (int i : indices) {
      tstate.clearElement(locals, i);
    }
  }

  /**
   * Returns true if the code generated for this instruction could escape. The default
   * implementation returns true.
   */
  boolean couldEscape() {
    return true;
  }

  /** Returns {@code local.toString()}, or {@code "_"} if {@code local} is null. */
  static String toString(Local local) {
    return (local == null) ? "_" : local.toString();
  }

  /** A no-op "Instruction" that implements Vm.BranchTarget. */
  static class BranchTarget extends Instruction implements Vm.BranchTarget {
    /** Each BranchTarget in an InstructionBlock is assigned a distinct index. */
    final int index;

    private HarmonizerOrBuilder harmonizer = new HarmonizerBuilder();

    BranchTarget(InstructionBlock ib, int index) {
      // Most Instructions have their ib field set when they're added to the InstructionBlock, but
      // BranchTargets are created and referenced before they're defined, and we want to be able to
      // check for cross-InstructionBlock branches (which aren't allowed).
      ((Instruction) this).ib = ib;
      this.index = index;
    }

    /**
     * Called each time we emit a branch to this target; returns true if a reference to {@code
     * localSources} was saved, false if the information was copied from it.
     */
    @CanIgnoreReturnValue
    boolean addReference(LocalSources sources) {
      HarmonizerBuilder builder = (HarmonizerBuilder) harmonizer;
      builder.merge(sources);
      return builder.sources == sources;
    }

    /** Returns true if at least one branch to this instruction has been emitted. */
    boolean hasReference() {
      return ((HarmonizerBuilder) harmonizer).sources != null;
    }

    @Override
    LocalSources updateLocalSources(LocalSources sources) {
      HarmonizerBuilder builder = (HarmonizerBuilder) harmonizer;
      builder.merge(sources);
      return builder.completed();
    }

    @Override
    void updateLive(UpdateLiveHelper helper) {
      // Associate the current liveset with our index, so that it will be available for branches
      // to this target.
      helper.saveCurrentLive(this);
      // Only live locals need harmonization.
      ((HarmonizerBuilder) harmonizer).setLive(helper.live.build());
    }

    /**
     * Called from {@link InstructionBlock#initialize}; if this BranchTarget requires harmonization,
     * saves the given valueMemoIndex and returns true, otherwise returns false.
     */
    boolean setValueMemoIndex(int valueMemoIndex) {
      harmonizer = ((HarmonizerBuilder) harmonizer).build(valueMemoIndex);
      return needsHarmonization();
    }

    private boolean needsHarmonization() {
      // This should only be called after initialization.
      assert harmonizer instanceof Harmonizer;
      return harmonizer != Harmonizer.NONE;
    }

    @Override
    boolean couldEscape() {
      // If any locals are harmonized at this BranchTarget it is possible that the incoming
      // value cannot be represented by the chosen template.
      return needsHarmonization();
    }

    @Override
    int execute(TState tstate, @RC.Counted Object[] locals, MethodMemo memo) {
      ((Harmonizer) harmonizer).harmonize(tstate, locals, memo);
      return location() + 1;
    }

    private int[] harmonizedIndices() {
      return ((Harmonizer) harmonizer).indices;
    }

    @Override
    void emit(CodeGen codeGen, EmitState emitState) {
      TargetInfo target = emitState.targets[index];
      if (target == null) {
        // No branches to this target were reachable; nothing is needed in either the fall-through
        // or the no-fall-through case.
        return;
      }
      if (codeGen.cb.nextIsReachable()) {
        // Fall-through is treated as another branch to this target
        emitBranch(codeGen, emitState, EMPTY_INTS);
      }
      Value[] harmonized = target.destination.emit(codeGen);
      emitState.locals = target.unharmonizedLocals;
      if (harmonized != null) {
        int[] harmonizedIndices = harmonizedIndices();
        assert harmonizedIndices.length == harmonized.length;
        for (int i = 0; i < harmonizedIndices.length; i++) {
          assert emitState.locals[harmonizedIndices[i]] == null;
          emitState.locals[harmonizedIndices[i]] = harmonized[i];
        }
      }
    }

    /** Generates code for a branch to this target. */
    void emitBranch(CodeGen codeGen, EmitState emitState, int[] clearLocals) {
      assert codeGen.cb.nextIsReachable();
      TargetInfo target = emitState.targets[index];
      // If this is the first branch to this target we need to create a corresponding Destination
      if (target == null) {
        target = newTarget(codeGen, emitState, clearLocals);
        emitState.targets[index] = target;
      } else {
        assert nonHarmonizedLocalsAreUnchanged(
            emitState.locals, clearLocals, target.unharmonizedLocals);
      }
      Value[] harmonized = null;
      if (needsHarmonization()) {
        // We need a copy of the current values of the harmonized locals.
        int[] harmonizedIndices = harmonizedIndices();
        harmonized = new Value[harmonizedIndices.length];
        Arrays.setAll(harmonized, i -> emitState.locals[harmonizedIndices[i]]);
      }
      // Destination.addBranch() does all the actual code generation
      target.destination.addBranch(codeGen, harmonized);
    }

    /** Creates a new TargetInfo for this target. */
    private TargetInfo newTarget(CodeGen codeGen, EmitState emitState, int[] clearLocals) {
      Destination destination;
      if (needsHarmonization()) {
        Harmonizer harmonizer = (Harmonizer) this.harmonizer;
        int numHarmonized = harmonizer.indices.length;
        ValueMemo vMemo =
            emitState.memo.valueMemo(codeGen.tstate(), harmonizer.valueMemoIndex, numHarmonized);
        destination = Destination.fromValueMemo(vMemo);
      } else {
        destination = Destination.newSimple();
      }
      Value[] unharmonizedLocals = copyUnharmonizedLocals(emitState.locals, clearLocals);
      return new TargetInfo(destination, unharmonizedLocals);
    }

    /**
     * Only intended for use in assertions. Verifies that a call to {@link #copyUnharmonizedLocals}
     * in the current state returns (essentially) the same results that a previous call did, i.e.
     * that the harmonized locals are the only ones that differ between incoming branches.
     */
    private boolean nonHarmonizedLocalsAreUnchanged(
        Value[] locals, int[] clearLocals, Value[] prev) {
      Value[] current = copyUnharmonizedLocals(locals, clearLocals);
      if (current.length != prev.length) {
        // Something is very wrong.
        return false;
      }
      for (int i = 0; i < current.length; i++) {
        Value v1 = current[i];
        Value v2 = prev[i];
        // The only case where v1 != v2 is OK is if one is null and the other is UNDEF
        // (which can happen only when the local is not live).
        if (v1 != v2 && ((v1 != null && v1 != Core.UNDEF) || (v2 != null && v2 != Core.UNDEF))) {
          return false;
        }
      }
      return true;
    }

    /**
     * Returns a copy of the current locals array, with elements that are in {@code clearLocals} or
     * {@link #harmonizedIndices} set to null.
     */
    private Value[] copyUnharmonizedLocals(Value[] locals, int[] clearLocals) {
      Value[] result = Arrays.copyOf(locals, locals.length);
      for (int i : clearLocals) {
        result[i] = null;
      }
      for (int i : harmonizedIndices()) {
        result[i] = null;
      }
      return result;
    }

    @Override
    public String toString() {
      return "b_" + index;
    }

    /** Shared base class for Harmonizer and HarmonizerBuilder. */
    private static class HarmonizerOrBuilder {}

    /**
     * A HarmonizerBuilder is used to accumulate information while the InstructionBlock is being
     * built.
     */
    private class HarmonizerBuilder extends HarmonizerOrBuilder {
      /**
       * Contains the merged LocalSources from all code paths that can reach this BranchTarget.
       *
       * <p>Null if either there are not yet any paths to this BranchTarget ({@code
       * needHarmonization == null}) or this target has already been emitted ({@code
       * needHarmonization != null}).
       */
      private LocalSources sources;

      /**
       * After the BranchTarget is emitted (and thus all code paths that can reach it are known),
       * the locals that will need harmonization are saved here.
       */
      private Bits needHarmonization;

      void merge(LocalSources moreSources) {
        assert needHarmonization == null;
        this.sources = LocalSources.merge(this.sources, moreSources, BranchTarget.this);
      }

      /**
       * Called when the target is emitted; saves the locals that need harmonization and returns the
       * LocalSources for the target.
       */
      LocalSources completed() {
        assert needHarmonization == null;
        needHarmonization = sources.needHarmonization(BranchTarget.this);
        LocalSources result = sources;
        sources = null;
        return result;
      }

      /**
       * Called from {@link BranchTarget#updateLive}; narrows {@link #needHarmonization} to just the
       * locals that are live after execution of this target.
       */
      void setLive(Bits live) {
        needHarmonization = Bits.Op.INTERSECTION.apply(needHarmonization, live);
      }

      /** Returns an appropriate Harmonizer based on our analysis. */
      Harmonizer build(int valueMemoIndex) {
        if (needHarmonization.isEmpty()) {
          return Harmonizer.NONE;
        } else {
          return new Harmonizer(needHarmonization.toArray(), valueMemoIndex);
        }
      }
    }

    /** A Harmonizer is responsible for harmonizing locals as needed for a BranchTarget. */
    private static class Harmonizer extends HarmonizerOrBuilder {
      /** Used for BranchTargets that do not need any harmonization. */
      static final Harmonizer NONE = new Harmonizer(EMPTY_INTS, -1);

      /** The locals that need harmonization. */
      final int[] indices;

      /**
       * The index of the ValueMemo in the InstructionBlock's MethodMemo that will be used for this
       * BranchTarget's harmonization.
       */
      final int valueMemoIndex;

      Harmonizer(int[] indices, int valueMemoIndex) {
        this.indices = indices;
        this.valueMemoIndex = valueMemoIndex;
      }

      void harmonize(TState tstate, Object[] locals, MethodMemo memo) {
        if (this == NONE) {
          return;
        }
        ValueMemo vMemo = memo.valueMemo(tstate, valueMemoIndex, indices.length);
        for (int i = 0; i < indices.length; i++) {
          int localIndex = indices[i];
          Value v = (Value) locals[localIndex];
          // A null entry in the local array is equivalent to Core.UNDEF.  Harmonization won't
          // change it, but will ensure that the ValueMemo includes UNDEF.
          if (v == null) {
            Value harmonized = vMemo.harmonize(tstate, i, Core.UNDEF);
            assert harmonized == Core.UNDEF;
          } else {
            locals[localIndex] = vMemo.harmonize(tstate, i, v);
          }
        }
      }
    }
  }

  /** The "set" instruction, which is just about as simple as you'd hope. */
  static class Set extends Instruction {
    /**
     * The local that will be set by this instruction.
     *
     * <p>May be null (indicating that the right hand side's value should be discarded); all that
     * does is error if any of the vars in the RHS are undefined.
     */
    private Local output;

    /** The value to set it to. */
    final VmExpr input;

    /**
     * The locals that stop being live after this instruction is executed.
     *
     * <p>In other words, these are locals that appear in {@link #input} but whose value will not be
     * used by any following instruction.
     */
    private int[] toClear;

    Set(Local output, VmExpr input) {
      this.output = output;
      this.input = input;
    }

    @Override
    LocalSources updateLocalSources(LocalSources sources) {
      return (output == null) ? sources : sources.set(output, this);
    }

    @Override
    void updateLive(UpdateLiveHelper helper) {
      // If our output is never used, don't set it.
      if (output != null && !helper.outputIsUsed(output)) {
        output = null;
      }
      // Mark all locals appearing in our RHS as live.  Note that we do this after calling
      // outputIsUsed() (which removes our output from the liveset), so that e.g. `x` is live
      // after visiting `x = [x, y]`.
      helper.addLocalRefs(input);
      toClear = helper.getNewLive();
    }

    @Override
    int execute(TState tstate, @RC.Counted Object[] locals, MethodMemo memo) {
      Value v = (Value) toValue(tstate, input, locals);
      if (v == null) {
        return -1;
      }
      // Note that for e.g. `x = [x, y]`, `x` will be in toClear, so it will be cleared before
      // setLocal() is called.
      clearLocals(tstate, toClear, locals);
      if (output != null) {
        setLocal(output.index, v, locals);
      } else if (v instanceof RefCounted rc) {
        tstate.dropReference(rc);
      }
      return location() + 1;
    }

    @Override
    void emit(CodeGen codeGen, EmitState emitState) {
      // Will escape if any referenced vars are UNDEF
      Value v = toRValue(codeGen, input, emitState.locals);
      emitState.clearLocals(toClear);
      if (output != null) {
        emitState.locals[output.index] = v;
      }
    }

    @Override
    public String toString() {
      return String.format("%s = %s", toString(output), input);
    }
  }

  static class Call extends Instruction {
    /**
     * The locals that will be set to the function's results. May include nulls for results that are
     * to be discarded.
     */
    private final Local[] outputs;

    /** The function to be called. */
    final VmFunction fn;

    final CallSite callSite;

    /** A VmExpr for each function argument. */
    private final VmExpr[] inputs;

    /** The locals that stop being live after this instruction is executed. */
    private int[] toClear;

    /** The StackEntryType used when unwinding during a call. */
    private StackEntryType duringCallStackEntryType;

    Call(Local[] outputs, VmFunction fn, VmExpr[] inputs, CallSite callSite) {
      Preconditions.checkArgument(inputs.length == fn.numArgs());
      Preconditions.checkArgument(outputs.length == fn.numResults());
      this.outputs = outputs;
      this.fn = fn;
      this.inputs = inputs;
      this.callSite = callSite;
    }

    @Override
    LocalSources updateLocalSources(LocalSources sources) {
      for (Local output : outputs) {
        if (output != null) {
          sources = sources.set(output, this);
        }
      }
      return sources;
    }

    @Override
    void updateLive(UpdateLiveHelper helper) {
      boolean hasIgnored = false;
      for (int i = 0; i < outputs.length; i++) {
        Local output = outputs[i];
        if (output == null) {
          hasIgnored = true;
        } else if (!helper.outputIsUsed(output)) {
          outputs[i] = null;
          hasIgnored = true;
        }
      }
      if (hasIgnored) {
        callSite.setIgnoredResults(i -> outputs[i] == null);
      }
      duringCallStackEntryType = new DuringCallStackEntryType(this, helper.live.build());
      callSite.duringCallEntryType = duringCallStackEntryType;
      helper.addLocalRefs(inputs);
      toClear = helper.getNewLive();
    }

    @Override
    int execute(TState tstate, @RC.Counted Object[] locals, MethodMemo memo) {
      Object[] args = toValues(tstate, inputs, locals);
      if (args == null) {
        return -1;
      }
      clearLocals(tstate, toClear, locals);
      TStack savedTail = tstate.beforeCall();
      ValueMemo valueMemo = callSite.valueMemo(tstate, memo);
      fn.doCall(tstate, valueMemo, memo, callSite, args);
      if (tstate.callEntryNeeded()) {
        // DuringCallStackEntryType.resume() doesn't use the ResultsInfo, so we can just pass null.
        tstate.afterCall(
            savedTail, duringCallStackEntryType.newStackEntry(tstate, locals), null, memo);
      } else {
        tstate.afterCall(savedTail);
      }
      if (tstate.unwindStarted()) {
        return -1;
      } else {
        saveResults(tstate, valueMemo, locals);
        return location() + 1;
      }
    }

    /**
     * Takes the function results from {@code tstate} and stores them in the appropriate elements of
     * {@code locals}.
     */
    void saveResults(TState tstate, ValueMemo valueMemo, @RC.Counted Object[] locals) {
      int memoIndex = 0;
      for (int i = 0; i < fn.numResults; i++) {
        Local output = outputs[i];
        if (output != null) {
          assert locals[output.index] == null;
          locals[output.index] = valueMemo.harmonize(tstate, memoIndex++, tstate.takeResult(i));
        }
      }
      tstate.clearResults();
    }

    @Override
    void emit(CodeGen codeGen, EmitState emitState) {
      Value[] args = toRValues(codeGen, inputs, emitState.locals);
      if (!codeGen.cb.nextIsReachable()) {
        // One of our args is always UNDEF.  Unlikely but possible.
        return;
      }
      emitState.clearLocals(toClear);
      Value stackEntry = duringCallStackEntryType.newStackEntry(codeGen.tstate(), emitState.locals);
      Destination callDone = codeGen.emitCall(fn, args, callSite, null, stackEntry);
      Value[] results = callDone.emit(codeGen);
      if (codeGen.cb.nextIsReachable()) {
        for (int i = 0; i < fn.numResults; i++) {
          Local output = outputs[i];
          if (output != null) {
            emitState.setLocal(output.index, results[i]);
          }
        }
      }
    }

    @Override
    public String toString() {
      String lhs;
      if (outputs.length == 0) {
        lhs = "";
      } else {
        lhs =
            Arrays.stream(outputs)
                .map(x -> toString(x))
                .collect(Collectors.joining(", ", "", " = "));
      }
      String args =
          Arrays.stream(inputs).map(Object::toString).collect(Collectors.joining(", ", "(", ")"));
      return lhs + fn.name + args;
    }
  }

  static class Return extends Instruction {
    private final VmExpr[] results;

    Return(VmExpr[] results) {
      this.results = results;
    }

    @Override
    void updateLive(UpdateLiveHelper helper) {
      // This is a terminal instruction, so nothing is live after it.
      helper.live.setAll(Bits.EMPTY);
      helper.addLocalRefs(results);
      // We don't need to clear the no-longer-live locals before we exit.
      helper.discardNewLive();
    }

    @Override
    int execute(TState tstate, @RC.Counted Object[] locals, MethodMemo memo) {
      Object[] values = toValues(tstate, results, locals);
      if (values == null) {
        return -1;
      }
      tstate.setResults(results.length, values);
      tstate.harmonizeResults(memo);
      // Our caller will clean up locals, so we don't need to.
      return -1;
    }

    @Override
    void emit(CodeGen codeGen, EmitState emitState) {
      Value[] values = toRValues(codeGen, results, emitState.locals);
      if (!codeGen.cb.nextIsReachable()) {
        // One of our args is always UNDEF.  Unlikely but possible.
        return;
      }
      codeGen.setResults(values);
    }

    @Override
    public String toString() {
      return "return "
          + Arrays.stream(results).map(Object::toString).collect(Collectors.joining(", "));
    }
  }

  static class Fail extends Instruction {
    private final String msg;

    /**
     * The locals that were passed to emitError(). These will be included in our stack entry, along
     * with any locals that would be live if execution continued at the next instruction (unless
     * this is the last instruction in the block).
     */
    private final Bits includeLocals;

    Fail(String msg, Bits includeLocals) {
      this.msg = msg;
      this.includeLocals = includeLocals;
    }

    @Override
    void updateLive(UpdateLiveHelper helper) {
      // Although fail is a terminal instruction, all the locals that are live on the following
      // instruction are made live for the fail (in addition to those in includeLocals).  This
      // makes it easy to the get intended behavior for "assert cond [, locals]", which is
      // implemented as
      //    if not condition jump to label
      //    fail locals
      //    label:
      Bits.Op.UNION.into(helper.live, includeLocals);
    }

    @Override
    int execute(TState tstate, @RC.Counted Object[] locals, MethodMemo memo) {
      startUnwind(tstate, locals);
      if (msg == null) {
        Err.ASSERTION_FAILED.pushUnwind(tstate);
      } else {
        Err.ASSERTION_FAILED_WITH_MSG.pushUnwind(tstate, msg);
      }
      return -1;
    }

    @Override
    void emit(CodeGen codeGen, EmitState emitState) {
      codeGen.escape();
    }

    @Override
    public String toString() {
      String result = (msg == null) ? "error" : String.format("error \"%s\"", msg);
      return includeLocals.isEmpty() ? result : result + " " + ib().localNames(includeLocals);
    }
  }

  static class Trace extends Instruction {
    private final String msg;

    /** The locals that were passed to emitTrace(). */
    private final Bits includeLocals;

    /** The locals that stop being live after this instruction is executed. */
    private int[] toClear;

    Trace(String msg, Bits includeLocals) {
      this.msg = msg;
      this.includeLocals = includeLocals;
    }

    @Override
    LocalSources updateLocalSources(LocalSources sources) {
      return sources;
    }

    @Override
    void updateLive(UpdateLiveHelper helper) {
      helper.addLocalRefs(includeLocals);
      toClear = helper.getNewLive();
    }

    @Override
    int execute(TState tstate, @RC.Counted Object[] locals, MethodMemo memo) {
      StackEntryType entryType = stackEntryType();
      tstate.trace(this, entryType.newStackEntry(tstate, locals));
      clearLocals(tstate, toClear, locals);
      return location() + 1;
    }

    @Override
    boolean couldEscape() {
      return false;
    }

    @Override
    void emit(CodeGen codeGen, EmitState emitState) {
      StackEntryType entryType = stackEntryType();
      codeGen.emitTrace(this, entryType.newStackEntry(codeGen.tstate(), emitState.locals));
      emitState.clearLocals(toClear);
      codeGen.invalidateEscape();
    }

    @Override
    public String toString() {
      String result = (msg == null) ? "trace" : String.format("trace \"%s\"", msg);
      return includeLocals.isEmpty() ? result : result + " " + ib().localNames(includeLocals);
    }
  }

  static class Branch extends Instruction {
    final BranchTarget target;

    Branch(BranchTarget target) {
      this.target = target;
    }

    @Override
    LocalSources updateLocalSources(LocalSources sources) {
      target.addReference(sources);
      return null;
    }

    @Override
    void updateLive(UpdateLiveHelper helper) {
      // If no one called defineTarget() with our target (which would be a compiler bug), this is
      // is where we'll find out.
      Preconditions.checkState(target.isInitialized(), "%s not defined", target);
      // Restore the liveLocals that were saved by the BranchTarget.
      helper.live.setAll(helper.liveAtTarget(target));
    }

    @Override
    int execute(TState tstate, @RC.Counted Object[] locals, MethodMemo memo) {
      return target.location();
    }

    @Override
    boolean couldEscape() {
      return target.needsHarmonization();
    }

    @Override
    void emit(CodeGen codeGen, EmitState emitState) {
      target.emitBranch(codeGen, emitState, EMPTY_INTS);
    }

    @Override
    public String toString() {
      return String.format("jump %s", target);
    }
  }

  /**
   * Conditionally branch based on the value of a Boolean local. Also subclassed to implement
   * TypeCheckBranch.
   */
  static class ConditionalBranch extends Instruction {
    private final BranchTarget target;
    final Local input;
    final boolean branchIfTrue;

    /**
     * The locals whose value is needed for the test or for the fall through code, but not if we
     * take the branch.
     */
    private int[] clearForBranch;

    /**
     * The locals whose value is needed for the test or if we take the branch, but not if we fall
     * through.
     */
    private int[] clearForNext;

    ConditionalBranch(BranchTarget target, Local input, boolean branchIfTrue) {
      this.target = target;
      this.input = input;
      this.branchIfTrue = branchIfTrue;
    }

    @Override
    LocalSources updateLocalSources(LocalSources sources) {
      // The same LocalSources instance will be used for both possible code paths,
      // so make sure it is immutable.
      boolean wasShared = sources.shared;
      sources.shared = true;
      boolean keptReference = target.addReference(sources);
      // Optimization: if the target copied what it needed out of the LocalSources (and didn't keep
      // a pointer to it), it no longer needs to be immutable.
      if (!keptReference) {
        sources.shared = wasShared;
      }
      return sources;
    }

    @Override
    void updateLive(UpdateLiveHelper helper) {
      Preconditions.checkState(target.isInitialized(), "%s not defined", target);
      // Save the locals that are live on fall-through, and add in those that are needed
      // for the test or live if we take the branch.
      Bits fallThroughLive = helper.live.build();
      helper.live.set(input.index);
      Bits branchLive = helper.liveAtTarget(target);
      Bits.Op.UNION.into(helper.live, branchLive);
      // Get the locals that are no longer needed if we branch...
      Bits liveBefore = helper.live.build();
      clearForBranch = Bits.Op.DIFFERENCE.apply(liveBefore, branchLive).toArray();
      // ... or if we don't
      clearForNext = Bits.Op.DIFFERENCE.apply(liveBefore, fallThroughLive).toArray();
    }

    /**
     * Returns a Condition that is true if the given value satisfies the branch condition. Throws a
     * BuiltinException if the given value is not allowed for this instruction. Overridden in the
     * TypeCheckBranch subclass.
     */
    Condition isTrue(Value v) throws BuiltinException {
      return Condition.fromBoolean(v);
    }

    @Override
    int execute(TState tstate, @RC.Counted Object[] locals, MethodMemo memo) {
      Value v = (Value) toValue(tstate, input, locals);
      if (v == null) {
        return -1;
      }
      Condition c;
      try {
        c = isTrue(v);
      } catch (BuiltinException e) {
        tstate.dropValue(v);
        startUnwind(tstate, locals);
        tstate.pushUnwind(e.takeStackEntry());
        return -1;
      }
      tstate.dropValue(v);
      // Invert if branchIfTrue is false
      boolean shouldBranch = (c.asBoolean() == branchIfTrue);
      clearLocals(tstate, shouldBranch ? clearForBranch : clearForNext, locals);
      return shouldBranch ? target.location() : this.location() + 1;
    }

    @Override
    void emit(CodeGen codeGen, EmitState emitState) {
      Value v = toRValue(codeGen, input, emitState.locals);
      if (!codeGen.cb.nextIsReachable()) {
        // v always UNDEF.  Unlikely but possible.
        return;
      }
      Condition c;
      try {
        c = isTrue(v);
      } catch (BuiltinException e) {
        // The local never has a boolean value
        codeGen.escape();
        return;
      }
      if (!branchIfTrue) {
        c = c.not();
      }
      FutureBlock fallThrough = new FutureBlock();
      c.addTest(codeGen, fallThrough);
      if (codeGen.cb.nextIsReachable()) {
        target.emitBranch(codeGen, emitState, clearForBranch);
      }
      codeGen.cb.setNext(fallThrough);
      emitState.clearLocals(clearForNext);
    }

    /** Used by toString(), overridden by subclass. */
    String conditionString() {
      return (branchIfTrue ? "" : "not ") + input;
    }

    @Override
    public String toString() {
      String msg = String.format("branch %s if %s", target, conditionString());
      if (clearForBranch != null && clearForBranch.length != 0) {
        msg += " (clear " + ib().localNames(clearForBranch) + " on branch)";
      }
      if (clearForNext != null && clearForNext.length != 0) {
        msg += " (clear " + ib().localNames(clearForNext) + " if no branch)";
      }
      return msg;
    }
  }

  static class TypeCheckBranch extends ConditionalBranch {
    VmType type;

    TypeCheckBranch(BranchTarget target, Local input, VmType type, boolean branchIfTrue) {
      super(target, input, branchIfTrue);
      this.type = type;
    }

    @Override
    Condition isTrue(Value v) {
      return v.isa(type);
    }

    @Override
    String conditionString() {
      return String.format("%s is %s%s", input, branchIfTrue ? "" : "not ", type);
    }
  }

  /**
   * A {@link BaseType.StackEntryType} that captures the execution state at an Instruction. Calling
   * {@link #resume} on an instance of this class will begin execution with the corresponding
   * instruction.
   */
  static class StackEntryType extends BaseType.StackEntryType {
    final Instruction instruction;

    /**
     * The indices of the locals that are live, and captured by instances of this StackEntryType.
     */
    private final int[] live;

    StackEntryType(Instruction instruction, Bits live) {
      super(live.count());
      this.instruction = instruction;
      if (isSingleton()) {
        assert live.isEmpty();
        this.live = null;
      } else {
        this.live = live.toArray();
      }
    }

    @Override
    String localName(int i) {
      return instruction.ib.getLocal(live[i]).name;
    }

    /** The index of the specified saved local. */
    int localIndex(int i) {
      return live[i];
    }

    /** Returns a stack entry, copying the values of saved locals from the given execution state. */
    @RC.Out
    Value newStackEntry(TState tstate, Object[] locals) {
      if (live == null) {
        return asValue();
      }
      Object[] savedLocals = tstate.allocObjectArray(live.length);
      for (int i = 0; i < live.length; i++) {
        Value local = Value.fromArray(locals, live[i]);
        if (local == null) {
          local = Core.UNDEF;
        } else {
          RefCounted.addRef(local);
        }
        savedLocals[i] = local;
      }
      return tstate.asCompoundValue(this, savedLocals);
    }

    @Override
    void resume(TState tstate, @RC.In Value entry, ResultsInfo results, MethodMemo mMemo) {
      assert entry.baseType() == this;
      InstructionBlock ib = instruction.ib;
      // Rebuild the locals array from the values saved in the stack entry
      @RC.Counted Object[] locals = ib.newLocalValues(tstate);
      for (int i = 0; i < size(); i++) {
        Value v = entry.element(i);
        if (v != Core.UNDEF) {
          locals[localIndex(i)] = v;
        }
      }
      tstate.dropValue(entry);
      int resumeFrom = instruction.location;
      if (this instanceof DuringCallStackEntryType) {
        // Add in the function results from tstate.
        Call call = (Call) instruction;
        call.saveResults(tstate, call.callSite.valueMemo(tstate, mMemo), locals);
        // Resume execution with the following instruction.
        ++resumeFrom;
      }
      // Now we're ready to continue execution
      ib.execute(tstate, locals, resumeFrom, mMemo);
    }

    @Override
    public String toString() {
      return instruction.describe();
    }
  }

  /**
   * A subclass of {@link StackEntryType} for stack entries that represent state during a call.
   *
   * <p>Compared with the {@link StackEntryType} for the same Call,
   *
   * <ul>
   *   <li>this stack entry may have a reduced liveset (if variables required for the args are no
   *       longer needed after the call); and
   *   <li>its {@link #resume} method expects to be called with the function results in the TState,
   *       and after storing them in the appropriate locals execution will continue with the
   *       following instruction.
   * </ul>
   */
  static class DuringCallStackEntryType extends StackEntryType {
    DuringCallStackEntryType(Instruction instruction, Bits live) {
      super(instruction, live);
      assert instruction instanceof Call;
    }

    @Override
    VmFunction called() {
      return ((Call) instruction).fn;
    }
  }

  private static final int[] EMPTY_INTS = new int[0];

  /**
   * An UpdateLiveHelper is used to visit each instruction in an InstructionBlock in reverse order,
   * computing the set of live locals at each step.
   *
   * <p>The general pattern for an {@link Instruction#updateLive} method is
   *
   * <ul>
   *   <li>Call {@link #outputIsUsed} on each of the statement's outputs, to determine which are
   *       actually used by subsequent instructions and to mark them as "not live" for preceding
   *       instructions (since this instruction overwrites the value).
   *   <li>Call {@link #addLocalRefs} on each of the statement's inputs, to mark them as live (note
   *       that this should be done after any calls to {@link #outputIsUsed}, to get the correct
   *       behavior when a Local is both input and output).
   *   <li>Call {@link #getNewLive} to get the indices of all locals whose last use is this
   *       instrution (i.e. are live for this instruction, and either not live for the subsequent
   *       instruction or are an output of this instruction); these are locals that should be
   *       cleared after loading the instruction's inputs and before setting its outputs.
   * </ul>
   */
  static class UpdateLiveHelper {
    /**
     * For each BranchTarget in the InstructionBlock, the set of live locals at that target. Indexed
     * by {@link BranchTarget#index}.
     */
    private final Bits[] liveAtTarget;

    /** The currently-live locals. */
    final Bits.Builder live = new Bits.Builder();

    /**
     * A scratch array used to record the indices of locals that were not live after the current
     * instruction, but are live before it.
     */
    private int[] newLive = new int[6];

    /** The number of valid entries in {@link #newLive}. */
    private int numNewLive;

    UpdateLiveHelper(int numBranchTargets) {
      liveAtTarget = new Bits[numBranchTargets];
    }

    /**
     * Visits each element of {@code instructions} in reverse order, returning the final live set
     * (i.e. the locals that are live before executing the first instruction). Should only be called
     * once on each UpdateLiveHelper.
     */
    Bits.Builder visitInstructions(List<Instruction> instructions) {
      for (Instruction inst : Lists.reverse(instructions)) {
        assert numNewLive == 0;
        inst.updateLive(this);
        inst.saveLive(live.build());
      }
      return live;
    }

    /** Returns the liveset at the given target, which must have been already visited. */
    Bits liveAtTarget(BranchTarget target) {
      Bits result = liveAtTarget[target.index];
      assert result != null;
      return result;
    }

    /**
     * Returns true if the given local was live. Marks it as not live (since the current instruction
     * is overwriting its value, the previous value cannot be read).
     */
    boolean outputIsUsed(Local output) {
      return live.clear(output.index);
    }

    /**
     * Adds each local in {@code inputs} to the current liveset. Any local that was not previously
     * live (i.e. for which this is the last instruction to use that local) is added to {@link
     * #newLive}.
     */
    void addLocalRefs(VmExpr... inputs) {
      for (VmExpr input : inputs) {
        input.forEachLocal(v -> addLocalRef(v.index));
      }
    }

    /**
     * Adds each local whose index is in the given set to the current liveset. Any not previously
     * live are added to {@link #newLive}.
     */
    void addLocalRefs(Bits locals) {
      locals.stream().forEach(this::addLocalRef);
    }

    private void addLocalRef(int index) {
      if (live.set(index)) {
        if (numNewLive == newLive.length) {
          newLive = Arrays.copyOf(newLive, numNewLive * 2);
        }
        newLive[numNewLive++] = index;
      }
    }

    /** Returns the indices of all locals that were made live by the current instruction. */
    int[] getNewLive() {
      if (numNewLive == 0) {
        return EMPTY_INTS;
      }
      int n = numNewLive;
      numNewLive = 0;
      return Arrays.copyOf(newLive, n);
    }

    /** Equivalent to calling {@link #getNewLive} and discarding the result. */
    void discardNewLive() {
      numNewLive = 0;
    }

    /** Saves the current liveset for the given target. */
    void saveCurrentLive(BranchTarget target) {
      assert liveAtTarget[target.index] == null;
      liveAtTarget[target.index] = live.build();
    }
  }

  /**
   * A LocalSources records the instruction that most recently set each local. We track this so that
   * when multiple control paths converge at a BranchTarget we can identify which locals should be
   * harmonized.
   */
  static class LocalSources {

    static final LocalSources EMPTY = new LocalSources();

    /**
     * Maps each Local to the Instruction that set its value. If the Instruction corresponding to a
     * Local is null, the Local's value has not been changed since the beginning of the block (if it
     * is an argument it has its original value, and otherwise it is undefined).
     *
     * <p>If this is the LocalSources for a BranchTarget and a Local has different sources depending
     * on which path was taken, the corresponding entry in this array will be the BranchTarget
     * itself (and the BranchTarget will harmonize the incoming value).
     */
    private final Instruction[] sources;

    /**
     * True if this LocalSources has been shared by more than one code path (due to a conditional
     * branch), and so must be immutable. Unshared LocalSources may be modified.
     */
    private boolean shared;

    /** Creates the empty LocalSources. */
    private LocalSources() {
      shared = true;
      sources = new Instruction[0];
    }

    /**
     * Creates a new, mutable copy of the given LocalSources, ensuring that its {@link #sources}
     * array has at least the specified length.
     */
    private LocalSources(LocalSources src, int minSize) {
      shared = false;
      sources = Arrays.copyOf(src.sources, Math.max(src.sources.length, minSize));
    }

    /**
     * Returns a LocalSources identical to this except that the given local's mapping has been
     * changed.
     */
    LocalSources set(Local local, Instruction instruction) {
      LocalSources result;
      // If we can make the change in place, do so; otherwise copy this LocalSources and apply the
      // change to the copy.
      if (!shared && sources.length > local.index) {
        result = this;
      } else {
        result = new LocalSources(this, local.index + 1);
      }
      result.sources[local.index] = instruction;
      return result;
    }

    /**
     * If either of the given LocalSources is null, returns the other. Otherwise returns a
     * LocalSources where locals that had the same value in both {@code x} and {@code y} are
     * unchanged, and locals that had different values are set to {@code target}.
     */
    static LocalSources merge(LocalSources x, LocalSources y, BranchTarget target) {
      if (x == null || x == y) {
        return y;
      } else if (y == null) {
        return x;
      }
      // If we can modify x or y to represent the result of the merge, do so; otherwise create a
      // new LocalSources and return it.
      LocalSources merged;
      LocalSources other;
      if (!x.shared && x.sources.length >= y.sources.length) {
        merged = x;
        other = y;
      } else {
        other = x;
        if (!y.shared && y.sources.length >= x.sources.length) {
          merged = y;
        } else {
          merged = new LocalSources(y, x.sources.length);
        }
      }
      for (int i = 0; i < merged.sources.length; i++) {
        Instruction otherSource = (i < other.sources.length) ? other.sources[i] : null;
        if (merged.sources[i] != otherSource) {
          merged.sources[i] = target;
        }
      }
      return merged;
    }

    /** Returns a Bits containing the locals that need harmonization at this target. */
    Bits needHarmonization(BranchTarget target) {
      if (shared) {
        // If we had merged multiple paths the result would be unshared
        assert Arrays.stream(sources).noneMatch(inst -> inst == target);
        return Bits.EMPTY;
      }
      return Bits.fromPredicate(sources.length - 1, i -> sources[i] == target);
    }
  }
}
