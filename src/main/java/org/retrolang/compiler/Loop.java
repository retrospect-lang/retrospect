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

package org.retrolang.compiler;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.retrolang.Vm;
import org.retrolang.Vm.BranchTarget;
import org.retrolang.Vm.Compound;
import org.retrolang.Vm.Expr;
import org.retrolang.Vm.InstructionBlock;
import org.retrolang.Vm.Local;
import org.retrolang.compiler.RetrospectParser.BlockContext;
import org.retrolang.compiler.RetrospectParser.BreakStmtContext;
import org.retrolang.compiler.RetrospectParser.ExpressionContext;
import org.retrolang.compiler.RetrospectParser.ForLoopContext;
import org.retrolang.compiler.RetrospectParser.LowerIdContext;
import org.retrolang.compiler.RetrospectParser.SimpleExtractContext;
import org.retrolang.util.Bits;

/// A subclass of BlockCompiler used to create the InstructionBlock for a loop body. The resulting
/// InstructionBlock will be used to define a method for either `nextState(loop, element, state)`
/// (loops that have a collection) or `at(loop, state)` (loops that don't).
class Loop extends BlockCompiler {
  /// True if this loop included the "sequential" keyword.
  final boolean isSequential;

  /// True if this loop is over a collection ("`for x in y ...`"), false if it is an unbounded
  /// sequential loop ("`for sequential x ...`") (note that parallel loops must have a source,
  /// sequential loops may or may not).
  final boolean hasSource;

  /// A compiler-generated name of the form "loop@<linenumber>", used when creating types for the
  /// loop closure, the loop state, and any break closures.
  final String name;

  /// A Local storing the loop closure argument.
  final Local selfArg;

  /// A Local storing the element argument passed to `nextState()`, or null if [#hasSource] is
  /// false.
  final Local elementArg;

  /// A Local storing the state argument.
  final Local stateArg;

  /// A StateVar for each variable listed after the `sequential` keyword; empty if this is a
  /// parallel loop.
  final List<StateVar> stateVars;

  /// A CollectedVar for each variable listed in the `collect` part of this loop.
  final List<CollectedVar> collectedVars = new ArrayList<>();

  /// A Break for each distinct `break` statement in the loop body.
  ///
  /// If there are multiple `break` statements with no body, they are all represented by a single
  /// `Break` instance.
  final List<Break> breaks = new ArrayList<>();

  /// True if this loop has a source collection and at least one collected variable with an
  /// expression.
  private boolean mayHaveKeyedCollectors;

  /// Set by [PassOne#visitForStatement] if we can't determine statically whether the loop will need
  /// to enumerate both keys and values, in which case we must add a closure var to record the
  /// choice made at runtime.
  Local eKind;

  /// If the [#elementArg] might contain a (key, value) pair, this Local will be non-null and set to
  /// the key (or `Absent` if `elementArg` doesn't actually contain a key -- sometimes we will only
  /// know at runtime).
  Local key;

  /// If this loop has non-trivial state, we will create a new Compound to represent it.
  ///
  /// If the loop needs no state to be saved, `stateCompound` will be null (and `nextState()` or
  /// `at()` will be passed `Absent` as the state).
  Compound stateCompound;

  /// Scope entries with index less than `kvStart` are created implicitly for sequential and
  /// collected vars, and in some cases may not be read in loop body. Scope entries with index
  /// greater than or equal to `kvStart` explicitly named by the user (beginning with any key/value
  /// bound by the loop), and it will be an error if they are unused.
  int kvStart;

  /// Scope entries with index less than `numDefinedAtStart` have their values set by the loop setup
  /// code, and so can be read without being written. Scope entries after this are ordinary
  /// variables that must be assigned to first.
  int numDefinedAtStart;

  /// The set of scope entries in the parent scope that are live after the loop completes, either
  /// through exhausting the collection (if [#hasSource] is true) or through executing any of the
  /// loop's `break` statements.
  Bits liveAfter;

  /// Set by [#compileBody] to the BranchTarget that `continue` instructions should branch to.
  BranchTarget continueTarget;

  Loop(Scope parent, boolean isSequential, boolean hasSource, String name) {
    super(
        parent.blockCompiler.symbols,
        newInstructionBlock(parent.blockCompiler.symbols, hasSource),
        parent);
    this.isSequential = isSequential;
    this.hasSource = hasSource;
    this.name = name;
    selfArg = ib.addArg("_loop");
    stateArg = ib.addArg("_state");
    elementArg = hasSource ? ib.addArg("_element") : null;
    stateVars = isSequential ? new ArrayList<>() : ImmutableList.of();
  }

  private static InstructionBlock newInstructionBlock(Symbols symbols, boolean hasSource) {
    // Loops with a source collection define a method for nextState(loop, element, state);
    // loops without a source collection define a method for at(loop, state).
    int numArgs = hasSource ? 3 : 2;
    int numResults = 1;
    return symbols.module.newInstructionBlock(numArgs, numResults, symbols.source);
  }

  /// A StateVar is created for each variable that appears after the `sequential` keyword in a loop.
  /// It has two scope entries, one outside the loop (the initial and final value of the var) and
  /// one inside (the current value).
  static class StateVar {
    final LowerIdContext id;
    final Scope.Entry outer;
    final Scope.Entry inner;

    StateVar(LowerIdContext id, Scope.Entry outer, Scope.Entry inner) {
      this.id = id;
      this.outer = outer;
      this.inner = inner;
    }
  }

  /// Creates a StateVar and adds it to [#stateVars].
  void addStateVar(LowerIdContext id, String name) {
    Scope.Entry outer = scope.parent.getEntryForWrite(name, id.start);
    Scope.Entry inner = scope.addSequentialState(name, id.start, outer);
    stateVars.add(new StateVar(id, outer, inner));
    id.entry = outer;
  }

  /// A CollectedVar is created for each variable that appears in the `collect` part of a loop. A
  /// typical CollectedVar (e.g. from `collect { x =| sum }` has three related scope entries:
  ///
  /// - one named "x", outside the loop, to receive the final result;
  /// - one named "x\_ro" (read only), inside the loop, to save information about the collector
  ///   `sum`; and
  /// - one named "x\_rw" (read/write), inside the loop, to save the current state of the collector
  ///   (in this example, the sum so far).
  ///
  /// CollectedVars may also share a collector expression (e.g. `collect { x, y =| sum }`); in this
  /// case they will share the read only variable (i.e. "x\_ro" and "y\_ro" will be synonyms for the
  /// same Local, and its value will only appear in the closure type once).
  ///
  /// A CollectedVar without a collector expression (e.g. `collect { x }`) emits to a CollectedVar
  /// in an enclosing loop. In this case there are four scope entries: "x\_ro" and "x\_rw" outside
  /// the loop, and "x\_ro" and "x\_rw" inside the loop.
  static class CollectedVar {
    final int index;
    final LowerIdContext id;
    final ExpressionContext expression;
    final boolean firstWithExpression;
    // In outer scope
    final Scope.Entry output; // x (or x_rw if no expression)
    Scope.Entry outerRo; // only used if no expression
    // In inner scope
    final Scope.Entry ro; // x_ro
    final Scope.Entry rw; // x_rw

    CollectedVar(
        int index,
        LowerIdContext id,
        ExpressionContext expression,
        boolean firstWithExpression,
        Scope.Entry output,
        Scope.Entry ro,
        Scope.Entry rw) {
      this.index = index;
      this.id = id;
      this.expression = expression;
      this.firstWithExpression = firstWithExpression;
      this.output = output;
      this.ro = ro;
      this.rw = rw;
    }
  }

  /// Creates a CollectedVar and adds it to [#collectedVars].
  void addCollectedVar(
      LowerIdContext id, ExpressionContext expression, boolean firstWithExpression) {
    String name = id.start.getText();
    String roName = Compiler.roName(name);
    String rwName = Compiler.rwName(name);
    String outputName = (expression != null) ? name : rwName;
    int index = collectedVars.size();
    Scope.Entry output = scope.parent.getEntryForWrite(outputName, id.start);
    Scope.Entry ro;
    if (expression == null || firstWithExpression) {
      ro = scope.addImmutable(roName, id.start, null);
      scope.addClosureVar(ro);
    } else {
      // Create a synonym for the _ro entry of the previous CV.
      ro = scope.addImmutable(roName, id.start, collectedVars.get(index - 1).ro.local);
    }
    Scope.Entry rw = scope.addMutable(rwName, id.start, true);
    collectedVars.add(new CollectedVar(index, id, expression, firstWithExpression, output, ro, rw));
    id.entry = output;
    if (expression != null) {
      mayHaveKeyedCollectors = hasSource;
    }
  }

  /// Called when PassTwo on the parent block has completed analysis of the code following the loop
  /// and each of our break bodies; at this point we have all the information need to compile the
  /// body.
  void donePassTwo(ForLoopContext forLoop) {
    scope.constructType(name, hasSource ? symbols.vmCore.lookupType("Loop") : symbols.vmLambda);
    symbols.addSubTask(() -> compileBody(forLoop));
  }

  /// Returns true if this loop has a source collection and at least one collected variable with an
  /// expression.
  boolean mayHaveKeyedCollectors() {
    return mayHaveKeyedCollectors;
  }

  /// Returns the names of all (inner scope) state variables, i.e. CollectedVar "\_rw" vars and
  /// StateVars (these are the components of the loop state).
  String[] stateNames() {
    int ncv = collectedVars.size();
    String[] result = new String[ncv + stateVars.size()];
    Arrays.setAll(
        result, i -> (i < ncv) ? collectedVars.get(i).rw.name : stateVars.get(i - ncv).inner.name);
    return result;
  }

  /// Creates a new Break for this loop.
  ///
  /// Usually `insideLoop` will be `Loop.this.scope`, but it may be the scope of a break from an
  /// inner loop if breaks are nested.
  Break addBreak(BreakStmtContext ctx, Scope insideLoop) {
    BlockContext block = ctx.block();
    if (block != null && block.children.isEmpty()) {
      block = null;
    }
    if (block == null) {
      // All breaks with no (or an empty) block are equivalent; we don't need
      // more than one.
      for (Break brk : breaks) {
        if (brk.block == null) {
          return brk;
        }
      }
    }
    Break brk =
        new Break(
            block, new Scope.ForBreak(/* parent= */ insideLoop, /* outsideLoop= */ scope.parent));
    breaks.add(brk);
    if (block != null) {
      // brk.scope and scope.parent have the same behavior for getEntryForWrite,
      // but if we create nested scopes (for lambdas or loops) we want them to
      // inherit from brk.scope.
      PassOne.apply(brk.scope, block);
    }
    return brk;
  }

  /// Runs PassThree to emit instructions for the loop body and then defines a method for the
  /// appropriate function (`nextState:3` or `at:2`).
  ///
  /// If this is a parallel loop with non-trivial state, also defines appropriate methods for
  /// `emptyState:1`, `splitState:2`, and `combineStates:3`.
  void compileBody(ForLoopContext forLoop) {
    scope.emitInitFromSelf(selfArg);
    int ncv = collectedVars.size();
    // The loop's state comprises the _rw values for each collected var plus the state vars.
    Local[] sVars = new Local[ncv + stateVars.size()];
    Expr returnValue;
    if (stateCompound == null) {
      assert sVars.length == 0;
      // We'll be passed Absent as our incoming state, and return it as our outgoing state.
      returnValue = symbols.vmAbsent;
    } else {
      Arrays.setAll(
          sVars,
          i -> (i < ncv) ? collectedVars.get(i).rw.local : stateVars.get(i - ncv).inner.local);
      // Initialize the state locals from the state argument.
      ib.emitCall(sVars, stateCompound.extract(), stateArg);
      // The state returned will be reassembled from those same locals.
      returnValue = stateCompound.make(sVars);
    }
    continueTarget = ib.newTarget();
    // If the loop has no variable bound to the key, and no keyed collectors, we'll EnumerateValues
    // and our _element argument is just the value; otherwise _element might be a [key, value] pair.
    if (mayHaveKeyedCollectors || forLoop.key != null) {
      int tmpState = saveTmpState();
      boolean needKeyExtract = (forLoop.key != null);
      if (mayHaveKeyedCollectors) {
        // Keep a separate copy of the key to pass to emitValue(), since loop body may overwrite any
        // key variable it declares.
        key = ib.newLocal("_key");
      } else if (forLoop.key instanceof SimpleExtractContext) {
        // We can extract directly into the user's key variable
        key = ((SimpleExtractContext) forLoop.key).lowerId().entry.local;
        needKeyExtract = false;
      } else {
        // The key needs to be extracted (e.g. "for [i]: x in ...")
        key = newTmp();
      }
      BranchTarget noKey = null;
      if (forLoop.key == null) {
        // If there was no variable bound to the key, we might be enumerating values or [key, value]
        // pairs depending on the collectors, so we need to check the closure var we added.
        noKey = ib.newTarget();
        ib.emitSet(key, symbols.vmAbsent);
        ib.emitTypeCheckBranch(noKey, eKind, symbols.vmCore.lookupType("EnumerateValues"), true);
      }
      // If we get here _element is [key, value], so extract it (leaving the value in _element).
      ib.emitCall(new Local[] {key, elementArg}, symbols.vm.arrayOfSize(2).extract(), elementArg);
      if (mayHaveKeyedCollectors) {
        // One of the collectors might have requested EnumerateAllKeys, so the value might be
        // Absent.  If it is, we skip the loop body and just emitKey on all the collectors.
        ib.emitTypeCheckBranch(continueTarget, elementArg, symbols.vmAbsent.asType(), true);
      }
      if (needKeyExtract) {
        extract(forLoop.key, key);
      }
      resetTmps(tmpState);
      if (noKey != null) {
        ib.defineTarget(noKey);
      }
    }
    if (forLoop.value != null) {
      extract(forLoop.value, elementArg);
    }
    PassThree.apply(this, forLoop.block());
    ib.defineTarget(continueTarget);
    if (mayHaveKeyedCollectors) {
      // Call emitKey on each collected var; collectors that requested EnumerateValues will ignore
      // this, and collectors that requested EnumerateWithKeys will ignore it unless the loop body
      // emitted to the collector.
      Vm.Function emitKey = symbols.vmCore.lookupFunction("emitKey", 3);
      for (CollectedVar cv : collectedVars) {
        // collected vars without an expression must be EnumerateValues, and hence would ignore
        // an emitKey call anyway.
        if (cv.expression != null) {
          ib.emitCall(cv.rw.local, emitKey, cv.ro.local, cv.rw.local, key);
        }
      }
    }
    ib.emitReturn(returnValue);
    ib.done();
    // Use the InstructionBlock that we've just completed to define a new method for
    // nextState(LoopType, element, state) (or at(LoopType, state) if there was no collection),
    // where "LoopType" is our newly-defined loop closure type.  To do that we need a
    // MethodPredicate of "arg0 is LoopType".
    Vm.MethodPredicate forThisLoopType = scope.getType().argType(0, true);
    ib.addMethod(
        hasSource ? symbols.vmCore.lookupFunction("nextState", 3) : symbols.vmAt,
        forThisLoopType,
        false);
    if (isSequential || stateCompound == null) {
      return;
    }
    defineEmptyStateMethod(forThisLoopType);
    defineSplitStateMethod(forThisLoopType);
    defineCombineStatesMethod(forThisLoopType);
  }

  /// Emits a method definition like
  ///
  /// ```
  /// method emptyState(Loop0 loop) {
  ///   {out1_ro, out2_ro} = loop_
  ///   out1_rw = emptyStateHelper(out1_ro)
  ///   out2_rw = emptyStateHelper(out2_ro)
  ///   return LoopState0_({out1_rw, out2_rw})
  /// }
  /// ```
  private void defineEmptyStateMethod(Vm.MethodPredicate forThisLoopType) {
    InstructionBlock ib = symbols.module.newInstructionBlock(1, 1, symbols.source);
    Local self = ib.addArg("loop");
    Local[] allRo = getAllRo(ib, self);
    Vm.Function helper = symbols.vmCore.lookupFunction("emptyStateHelper", 1);
    Local[] allRw = newRwVars(ib, "");
    for (int i = 0; i < allRw.length; i++) {
      ib.emitCall(allRw[i], helper, allRo[i]);
    }
    ib.emitReturn(stateCompound.make(allRw));
    ib.done();
    ib.addMethod(symbols.vmCore.lookupFunction("emptyState", 1), forThisLoopType, false);
  }

  /// Emits a method definition like
  ///
  /// ```
  /// method splitState(Loop0 loop, state=) {
  ///   {out1_ro, out2_ro} = loop_
  ///   {out1_rw: out1_rw1, out2_rw: out2_rw1} = state_
  ///   out1_rw2 = splitStateHelper(out1_ro, out1_rw1=)
  ///   out2_rw2 = splitStateHelper(out2_ro, out2_rw1=)
  ///   state = LoopState0_({out1_rw: out1_rw1, out2_rw: out2_rw1})
  ///   return LoopState0_({out1_rw: out1_rw2, out2_rw: out2_rw2})
  /// }
  /// ```
  private void defineSplitStateMethod(Vm.MethodPredicate forThisLoopType) {
    InstructionBlock ib = symbols.module.newInstructionBlock(2, 2, symbols.source);
    Local self = ib.addArg("loop");
    Local state = ib.addArg("state");
    Local[] allRo = getAllRo(ib, self);
    // Initialize *_rw1 vars from the state arg.
    // Assumes there are no sequential vars, which is OK since this is only used in parallel loops.
    Local[] rw1 = newRwVars(ib, "1");
    ib.emitCall(rw1, stateCompound.extract(), state);
    Vm.Function helper = symbols.vmCore.lookupFunction("splitStateHelper", 2);
    Local[] rw2 = newRwVars(ib, "2");
    for (int i = 0; i < rw1.length; i++) {
      ib.emitCall(new Local[] {rw2[i], rw1[i]}, helper, allRo[i], rw1[i]);
    }
    ib.emitReturn(stateCompound.make(rw2), stateCompound.make(rw1));
    ib.done();
    ib.addMethod(symbols.vmCore.lookupFunction("splitState", 2), forThisLoopType, false);
  }

  /// Emits a method definition like
  ///
  /// ```
  /// method combineStates(Loop0 loop, state1, state2) {
  ///   {out1_ro, out2_ro} = loop_
  ///   {out1_rw: out1_rw1, out2_rw: out2_rw1} = state1_
  ///   {out1_rw: out1_rw2, out2_rw: out2_rw2} = state2_
  ///   out1_rw1 = combineStatesHelper(out1_ro, out1_rw1, out1_rw2)
  ///   out2_rw1 = combineStatesHelper(out2_ro, out2_rw1, out2_rw2)
  ///   return LoopState0_({out1_rw: out1_rw1, out2_rw: out2_rw1})
  /// }
  /// ```
  private void defineCombineStatesMethod(Vm.MethodPredicate forThisLoopType) {
    InstructionBlock ib = symbols.module.newInstructionBlock(3, 1, symbols.source);
    Local self = ib.addArg("loop");
    Local state1 = ib.addArg("state1");
    Local state2 = ib.addArg("state2");
    Local[] allRo = getAllRo(ib, self);
    Local[] rw1 = newRwVars(ib, "1");
    ib.emitCall(rw1, stateCompound.extract(), state1);
    Local[] rw2 = newRwVars(ib, "2");
    ib.emitCall(rw2, stateCompound.extract(), state2);
    Vm.Function helper = symbols.vmCore.lookupFunction("combineStatesHelper", 3);
    for (int i = 0; i < rw1.length; i++) {
      ib.emitCall(rw1[i], helper, allRo[i], rw1[i], rw2[i]);
    }
    ib.emitReturn(stateCompound.make(rw1));
    ib.done();
    ib.addMethod(symbols.vmCore.lookupFunction("combineStates", 3), forThisLoopType, false);
  }

  /// Returns an array with one Local for the `_ro` part of each of this Loop's collected vars,
  /// initialized by extracting them from the given `self` arg.
  private Local[] getAllRo(InstructionBlock ib, Local self) {
    Local[] result = new Local[collectedVars.size()];
    Local[] extractTo = new Local[scope.closureVars.size()];
    // The CollectedVars are added to closureVars first
    // (This seems fragile; is there a better alternative?)
    int nextExtract = 0;
    Local last = null;
    for (CollectedVar cv : collectedVars) {
      // If there are multiple collected vars that share a collector expression
      // (e.g. "collect { x, y =| sum }") there will only be one closure var, and we only allocate
      // one Local, but we put it in the result array multiple times.
      if (cv.expression == null || cv.firstWithExpression) {
        last = ib.newLocal(cv.ro.name);
        extractTo[nextExtract++] = last;
      }
      result[cv.index] = last;
    }
    ib.emitCall(extractTo, scope.compound.extract(), self);
    return result;
  }

  /// Returns an array with one Local for the `_rw` part of each of this Loop's collected vars. The
  /// given suffix is appended to each name.
  private Local[] newRwVars(InstructionBlock ib, String suffix) {
    return collectedVars.stream().map(cv -> ib.newLocal(cv.rw.name + suffix)).toArray(Local[]::new);
  }

  /// Each `break` statement is linked to a corresponding Loop.Break instance.
  ///
  /// Code is emitted for `break` statements in two places:
  ///
  /// - In the loop body, when the `break` statement is reached, the [#passThree] method (called
  ///   from [PassThree#visitBreakStatement]) emits instructions to return a `LoopExit` value.
  /// - Outside the loop, after `iterate` or `enumerate` has returned, [PassThree#visitForStatement]
  ///   emits instructions to check the return value; if it determines that it was a `LoopExit`
  ///   value created by this Loop.Break, the emitted instructions will extract any state saved at
  ///   the time of the break and then execute the break block.
  class Break {
    final BlockContext block;
    final Scope.ForBreak scope;

    /// Records which of the variables in the scope outside the loop are live if we take this break.
    /// Only used in sequential loops, to determine which of the loop's results are accessed.
    Bits live;

    Break(BlockContext block, Scope.ForBreak scope) {
      this.block = block;
      this.scope = scope;
    }

    /// Called from the backward phase of PassTwo; at this point we know which of the loop body
    /// variables are used in the break body, and hence need to be part of the break closure.
    void finishClosure(Bits live) {
      if (isSequential) {
        this.live = live;
        // After a break in a sequential loop the collector results and sequential vars may be
        // accessed; if they are, we need to pass back their final values.
        for (CollectedVar cv : collectedVars) {
          if (live.test(cv.output.index)) {
            scope.addClosureVar(new Scope.Entry(cv.rw.name, cv.rw, null, -1, cv.output.local));
          }
        }
        for (StateVar stateVar : stateVars) {
          Scope.Entry outer = stateVar.outer;
          if (live.test(outer.index)) {
            scope.addClosureVar(new Scope.Entry(outer.name, stateVar.inner, null, -1, outer.local));
          }
        }
      }
      if (scope.closureVars == null && breaks.size() == 1) {
        // None of the loop's local vars, results, or sequential vars are accessed after the break,
        // and we don't need to distinguish it from other breaks, so we don't need a new closure
        // type at all -- we can just pass back None.
        scope.useNone();
      } else {
        String name;
        if (block == null) {
          name = String.format("%s_break", Loop.this.name);
        } else {
          name = String.format("%s_break@%s", Loop.this.name, block.start.getLine());
        }
        scope.constructType(name);
      }
    }

    /// Emits instructions to return an instance of this break's closure type, wrapped as a
    /// LoopExit.
    void passThree() {
      if (isSequential && mayHaveKeyedCollectors) {
        // The final values of collected vars are accessible after a break in a sequential loop,
        // so we have to call emitKey() on any that might be used
        Vm.Function emitKey = symbols.vmCore.lookupFunction("emitKey", 3);
        for (CollectedVar cv : collectedVars) {
          if (cv.expression != null && live.test(cv.output.index)) {
            ib.emitCall(cv.rw.local, emitKey, cv.ro.local, cv.rw.local, key);
          }
        }
      }
      Local tmp = newTmp();
      ib.emitCall(tmp, symbols.vmCore.lookupFunction("loopExit", 1), scope.getClosure(null));
      ib.emitReturn(tmp);
    }
  }
}
