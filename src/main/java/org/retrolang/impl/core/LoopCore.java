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

package org.retrolang.impl.core;

import static org.retrolang.impl.Value.addRef;

import org.retrolang.impl.BaseType;
import org.retrolang.impl.BuiltinMethod;
import org.retrolang.impl.BuiltinMethod.Caller;
import org.retrolang.impl.BuiltinMethod.Fn;
import org.retrolang.impl.Condition;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.Singleton;
import org.retrolang.impl.StructType;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.ValueUtil;
import org.retrolang.impl.VmFunctionBuilder;
import org.retrolang.impl.VmType;
import org.retrolang.util.ArrayUtil;

/// Core methods providing support for loops.
public final class LoopCore {

  /// `type EnumerationKind`
  @Core.Public public static final VmType.Union ENUMERATION_KIND = Core.newUnion("EnumerationKind");

  /// `singleton EnumerateValues is EnumerationKind`
  @Core.Public
  public static final Singleton ENUMERATE_VALUES =
      Core.newSingleton("EnumerateValues", ENUMERATION_KIND);

  /// `singleton EnumerateWithKeys is EnumerationKind`
  @Core.Public
  public static final Singleton ENUMERATE_WITH_KEYS =
      Core.newSingleton("EnumerateWithKeys", ENUMERATION_KIND);

  /// `singleton EnumerateAllKeys is EnumerationKind`
  @Core.Public
  public static final Singleton ENUMERATE_ALL_KEYS =
      Core.newSingleton("EnumerateAllKeys", ENUMERATION_KIND);

  /// `open type Iterator`
  ///
  /// Values of type Iterator are expected to provide a method for `next(it=)`.
  @Core.Public public static final VmType.Union ITERATOR = Core.newOpenUnion("Iterator");

  /// Returns an Iterator that can be used to call `nextState()` on `loop` with each element of
  /// `collection`, optionally paired its key.
  ///
  /// `open function iterator(collection, eKind, loop=, initialState=)`
  @Core.Public
  static final VmFunctionBuilder iterator =
      VmFunctionBuilder.create("iterator", 4).hasInoutArg(2).hasInoutArg(3).isOpen();

  /// Returns the next element from an Iterator, or Absent if there are no elements remaining.
  ///
  /// `open function next(it=)`
  @Core.Public
  static final VmFunctionBuilder next = VmFunctionBuilder.create("next", 1).hasInoutArg(0).isOpen();

  /// Called once on a Loop if all of the input elements have been processed without reaching a
  /// LoopExit state. The default method just returns `state` unchanged.
  ///
  /// `open function finalState(loop, state)`
  @Core.Public
  static final VmFunctionBuilder finalState = VmFunctionBuilder.create("finalState", 2).isOpen();

  /// Returns the empty state of a parallelizable loop.
  ///
  /// `open function emptyState(loop)`
  @Core.Public
  static final VmFunctionBuilder emptyState = VmFunctionBuilder.create("emptyState", 1).isOpen();

  /// Returns a new state and updates the given state; the result of combining those two states
  /// should be equivalent to the original state. The default implementation leaves `state`
  /// unchanged and returns `emptyState(loop)`.
  ///
  /// `open function splitState(loop, state=)`
  @Core.Public
  static final VmFunctionBuilder splitState =
      VmFunctionBuilder.create("splitState", 2).hasInoutArg(1).isOpen();

  /// Combines two states. Will not be called with either state Absent or a LoopExit.
  ///
  /// `open function combineStates(loop, state1, state2)`
  @Core.Public
  static final VmFunctionBuilder combineStates =
      VmFunctionBuilder.create("combineStates", 3).isOpen();

  /// Starting from the given state, sequentially updates it by calling `nextState(loop, state,
  /// element)` with each element of the given collection (optionally paired with the corresponding
  /// key) until the end of the collection is reached or a LoopExit state is reached. Returns the
  /// final state.
  ///
  /// `function iterate(collection, eKind, loop, state)`
  @Core.Public static final VmFunctionBuilder iterate = VmFunctionBuilder.create("iterate", 4);

  /// Starting from the given state, sequentially updates it by calling `lambda @ state` until a
  /// LoopExit state is reached. Returns the final state.
  ///
  /// `function iterateUnbounded(lambda, state)`
  @Core.Public
  static final VmFunctionBuilder iterateUnbounded = VmFunctionBuilder.create("iterateUnbounded", 2);

  /// Updates `loop` and possibly `state` to execute the given pipeline step at the beginning of the
  /// loop.
  ///
  /// `open procedure addLoopStep(PipelineStep step, eKind, loop=, initialState=)`
  @Core.Public
  static final VmFunctionBuilder addLoopStep =
      VmFunctionBuilder.create("addLoopStep", 4)
          .hasNoResult()
          .hasInoutArg(2)
          .hasInoutArg(3)
          .isOpen();

  /// `private compound CompoundStep is PipelineStep`
  ///
  /// Elements are `step1`, `step2`.
  @Core.Private
  static final BaseType.Named COMPOUND_STEP =
      Core.newBaseType("CompoundStep", 2, Core.PIPELINE_STEP);

  /// `private compound LimitStep is PipelineStep`
  ///
  /// Element is `limit`.
  @Core.Private
  static final BaseType.Named LIMIT_STEP = Core.newBaseType("LimitStep", 1, Core.PIPELINE_STEP);

  /// `singleton SimpleLoop is Loop`
  @Core.Private
  public static final Singleton SIMPLE_LOOP = Core.newSingleton("SimpleLoop", Core.LOOP);

  /// Returns a PipelineStep that terminates the loop after the specified number of elements have
  /// been processed.
  @Core.Public static final VmFunctionBuilder limit = VmFunctionBuilder.create("limit", 1);

  /// `private compound EmitAllLoop is Loop`
  ///
  /// Element is `inner`.
  @Core.Private
  static final BaseType.Named EMIT_ALL_LOOP = Core.newBaseType("EmitAllLoop", 1, Core.LOOP);

  /// `private compound KeyValueLambda is Lambda`
  ///
  /// Elements are `inner`, `dropAbsent`. Expects the argument to be a [key, value] pair, and
  /// applies `inner` to the value. If that returns Absent and `dropAbsent` is True, returns Absent;
  /// otherwise returns the original key paired with the result.
  @Core.Private
  static final BaseType.Named KEY_VALUE_LAMBDA = Core.newBaseType("KeyValueLambda", 2, Core.LAMBDA);

  /// `open function enumerate(collection, eKind, loop, state)`
  @Core.Public
  static final VmFunctionBuilder enumerate = VmFunctionBuilder.create("enumerate", 4).isOpen();

  /// `private compound TrivialIterator is Iterator`
  ///
  /// An Iterator that returns no values (if element is Absent) or a single value (its element).
  @Core.Private
  static final BaseType.Named TRIVIAL_ITERATOR = Core.newBaseType("TrivialIterator", 1, ITERATOR);

  /// `private compound PipedCollector is Collector`
  ///
  /// Elements are `step`, `collector`.
  @Core.Private
  static final BaseType.Named PIPED_COLLECTOR =
      Core.newBaseType("PipedCollector", 2, Core.COLLECTOR);

  /// `singleton PipedCollectionCannotSave is Collection`
  ///
  /// This is used only as a dummy value when calling `collectorSetup` through a pipeline with a
  /// `limit`.
  @Core.Public
  public static final Singleton PIPED_COLLECTION_NO_KEYS =
      Core.newSingleton("PipedCollectionCannotSave", Core.COLLECTION);

  /// `private compound SequentialCollector is Collector`
  @Core.Private
  static final BaseType.Named SEQUENTIAL_COLLECTOR =
      Core.newBaseType("SequentialCollector", 1, Core.COLLECTOR);

  /// `function loopExit(finalState)`
  @Core.Public static final VmFunctionBuilder loopExit = VmFunctionBuilder.create("loopExit", 1);

  /// `function loopExitState(loopExit)`
  @Core.Public
  static final VmFunctionBuilder loopExitState = VmFunctionBuilder.create("loopExitState", 1);

  /// `function sequentially(collector)`
  @Core.Public
  static final VmFunctionBuilder sequentially = VmFunctionBuilder.create("sequentially", 1);

  /// `function saveSequential() = sequentially(saveUnordered)`
  @Core.Public
  static final VmFunctionBuilder saveSequential =
      VmFunctionBuilder.fromConstant(
          "saveSequential", SEQUENTIAL_COLLECTOR.uncountedOf(ReducerCore.SAVE_UNORDERED));

  static final Value EMPTY_ITERATOR = TRIVIAL_ITERATOR.uncountedOf(Core.ABSENT);

  /// `function emptyIterator() = TrivialIterator_(Absent)`
  @Core.Public
  static final VmFunctionBuilder emptyIterator =
      VmFunctionBuilder.fromConstant("emptyIterator", EMPTY_ITERATOR);

  /// `function oneElementIterator(element)`
  @Core.Public
  static final VmFunctionBuilder oneElementIterator =
      VmFunctionBuilder.create("oneElementIterator", 1);

  /// `open function collectorSetup(collector, collection)`
  ///
  /// Returns a struct `{ canParallel, eKind, initialState, loop }`
  @Core.Public
  static final VmFunctionBuilder collectorSetup =
      VmFunctionBuilder.create("collectorSetup", 2).isOpen();

  /// The expected struct keys for the result of [#collectorSetup].
  ///
  /// Must be in alphabetical order.
  static final StructType SETUP_KEYS =
      new StructType("canParallel", "eKind", "initialState", "loop");

  /// `private compound LoopRO`
  ///
  /// Elements are `eKind`, `loop`, `canParallel`.
  ///
  /// A LoopRO value is created for each of a "for" loop's collected variables, holding data
  /// returned by the `collectorSetup()` function.
  @Core.Private static final BaseType.Named LOOP_RO = Core.newBaseType("LoopRO", 3);

  /// `private compound LoopRW`
  ///
  /// Elements are `pendingValue`, `state`.
  ///
  /// A LoopRW value holds the current state of a "for" loop's collected variable.
  @Core.Private static final BaseType.Named LOOP_RW = Core.newBaseType("LoopRW", 2);

  /// `private compound CollectorExited`
  ///
  /// Element is the value from the collector's LoopExit.
  @Core.Private
  static final BaseType.Named COLLECTOR_EXITED = Core.newBaseType("CollectorExited", 1);

  /// `procedure emitValue(ro, rw=, v)`
  ///
  /// A call to `emitValue()` is emitted by the compiler for each "`<<`" statement.
  @Core.Public
  static final VmFunctionBuilder emitValue =
      VmFunctionBuilder.create("emitValue", 3).hasInoutArg(1).hasNoResult();

  /// `procedure emitAll(ro, rw=, key)`
  ///
  /// A call to `emitAll()` is emitted by the compiler for each "`<<^`" statement.
  @Core.Public
  static final VmFunctionBuilder emitAll =
      VmFunctionBuilder.create("emitAll", 3).hasInoutArg(1).hasNoResult();

  /// `procedure emitKey(ro, rw=, key)`
  ///
  /// A call to `emitKey()` is emitted by the compiler at the end of each loop iteration for for
  /// each collected variable.
  @Core.Public
  static final VmFunctionBuilder emitKey =
      VmFunctionBuilder.create("emitKey", 3).hasInoutArg(1).hasNoResult();

  /// `function loopHelper(collector, collection, maxEKind=, isParallel)`
  ///
  /// Returns three results: `ro`, `rw`, and updated `maxEKind`.
  ///
  /// A call to this `loopHelper()` is emitted by the compiler when generating code for a "for" loop
  /// over a collection. See docs/loops.md#compiling-for-loops for details.
  @Core.Public
  static final VmFunctionBuilder loopHelper4 =
      VmFunctionBuilder.create("loopHelper", 4).hasInoutArg(2).hasResults(2);

  /// `function loopHelper(collector)`
  ///
  /// Returns two results: `ro` and `rw`.
  ///
  /// A call to this `loopHelper()` is emitted by the compiler when generating code for an unbounded
  /// "for" loop. See docs/loops.md#compiling-for-loops for details.
  @Core.Public
  static final VmFunctionBuilder loopHelper1 =
      VmFunctionBuilder.create("loopHelper", 1).hasResults(2);

  /// `function finalStateHelper(ro, rw)`
  ///
  /// A call to `finalStateHelper()` is emitted by the compiler to determine the value of each
  /// collected variable when a "for" loop completes.
  @Core.Public
  static final VmFunctionBuilder finalStateHelper2 =
      VmFunctionBuilder.create("finalStateHelper", 2);

  /// `function finalStateHelper(ro, rw, key)`
  ///
  /// A call to this `finalStateHelper()` is emitted by the compiler for a `break` out of a
  /// sequential loop; it combines the functionality of `emitKey()` and `finalStateHelper(ro, rw)`.
  @Core.Public
  static final VmFunctionBuilder finalStateHelper3 =
      VmFunctionBuilder.create("finalStateHelper", 3);

  /// `function emptyStateHelper(ro)`
  ///
  /// Used to implement the `emptyState()` method for the body of a parallelizable loop. See
  /// docs/loops.md#compiling-for-loops for details.
  @Core.Public
  static final VmFunctionBuilder emptyStateHelper = VmFunctionBuilder.create("emptyStateHelper", 1);

  /// `function splitStateHelper(ro, rw=)`
  ///
  /// Used to implement the `splitState()` method for the body of a parallelizable loop. See
  /// docs/loops.md#compiling-for-loops for details.
  @Core.Public
  static final VmFunctionBuilder splitStateHelper =
      VmFunctionBuilder.create("splitStateHelper", 2).hasInoutArg(1);

  /// `function combineStatesHelper(ro, rw1, rw2)`
  ///
  /// Used to implement the `combineStates()` method for the body of a parallelizable loop. See
  /// docs/loops.md#compiling-for-loops for details.
  @Core.Public
  static final VmFunctionBuilder combineStatesHelper =
      VmFunctionBuilder.create("combineStatesHelper", 3);

  /// `procedure verifyEV(ro)`
  ///
  /// A call to `verifyEV()` is emitted by the compiler when an inner loop inherits a collected var
  /// from an outer loop; its only function is to error if the collector is keyed.
  @Core.Public
  static final VmFunctionBuilder verifyEv = VmFunctionBuilder.create("verifyEV", 1).hasNoResult();

  /// `method nextState(SimpleLoop, state, element) = element`
  @Core.Method("nextState(SimpleLoop, _, _)")
  static Value nextStateSimpleLoop(
      @RC.Singleton Value simpleLoop, Value state, @RC.In Value element) {
    return element;
  }

  /// ```
  /// method nextState(TransformedLoop transformed, state, item) {
  ///   item = transformed_.lambda @ item
  ///   // If item transformed to Absent, skip the rest of the loop by returning state unchanged
  ///   if item is Absent {
  ///     return state
  ///   }
  ///   // Run the rest of the loop on the transformed value.
  ///   return nextState(transformed_.loop, state, item)
  /// }
  /// ```
  static class NextStateTransformedLoop extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("nextState(TransformedLoop, _, _)")
    static void begin(
        TState tstate, @RC.In Value transformed, @RC.In Value state, @RC.In Value item)
        throws BuiltinException {
      Value lambda = transformed.element(0);
      tstate.startCall(at, lambda, item).saving(transformed, state);
    }

    @Continuation
    static void afterAt(
        TState tstate,
        @RC.In Value item,
        @Saved Value transformed,
        @RC.In Value state,
        @Fn("nextState:3") Caller nextState) {
      item.is(Core.ABSENT)
          .test(
              () -> tstate.setResult(state),
              () -> {
                Value inner = transformed.element(1);
                tstate.startCall(nextState, inner, state, item);
              });
    }
  }

  /// ```
  /// method finalState(TransformedLoop transformed, state) = finalState(transformed_.loop, state)
  /// ```
  @Core.Method("finalState(TransformedLoop, _)")
  static void finalStateTransformedLoop(
      TState tstate, Value transformed, @RC.In Value state, @Fn("finalState:2") Caller finalState) {
    Value loop = transformed.element(1);
    tstate.startCall(finalState, loop, state);
  }

  /// ```
  /// method applyToValues(Lambda lambda, EnumerationKind eKind) =
  ///     (eKind is EnumerateValues) ? lambda
  ///                                : KeyValueLambda_({inner: lambda, dropAbsent: eKind is EnumerateWithKeys})
  /// ```
  @RC.Out
  static Value applyToValues(TState tstate, @RC.In Value lambda, @RC.Singleton Value eKind) {
    return eKind
        .is(ENUMERATE_VALUES)
        .choose(
            () -> lambda,
            () ->
                tstate.compound(KEY_VALUE_LAMBDA, lambda, eKind.is(ENUMERATE_WITH_KEYS).asValue()));
  }

  /// ```
  /// method at(KeyValueLambda kvLambda, [key, value]) {
  ///   // Don't transform Absent
  ///   if value is Absent {
  ///     return [key, Absent]
  ///   }
  ///   value = value kvLambda_.inner @ value
  ///   return (value is Absent and kvLambda_.dropAbsent) ? Absent : [key, value]
  /// }
  /// ```
  static class AtKeyValueLambda extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("at(KeyValueLambda, Array)")
    static void begin(TState tstate, Value kvLambda, Value pair) throws BuiltinException {
      Err.NOT_PAIR.unless(pair.isArrayOfLength(2));
      Value value = pair.element(1);
      value
          .is(Core.ABSENT)
          .test(
              () -> tstate.setResult(addRef(pair)),
              () -> {
                Value key = pair.element(0);
                Value inner = kvLambda.element(0);
                Value dropAbsent = kvLambda.element(1);
                tstate.startCall(at, inner, value).saving(dropAbsent, key);
              });
    }

    @Continuation
    static void afterAt(
        TState tstate,
        @RC.In Value value,
        @Saved @RC.Singleton Value dropAbsent,
        @RC.In Value key) {
      value
          .is(Core.ABSENT)
          .and(dropAbsent.is(Core.TRUE))
          .test(
              () -> {
                tstate.dropValue(key);
                tstate.setResult(Core.ABSENT);
              },
              () -> tstate.setResult(tstate.arrayValue(key, value)));
    }
  }

  /// ```
  /// method limit(n) {
  ///   assert n is Integer and n >= 0
  ///   return LimitStep_(n)
  /// }
  /// ```
  @Core.Method("limit(_)")
  static Value limit(TState tstate, Value limit) throws BuiltinException {
    limit = limit.verifyInt(Err.INVALID_ARGUMENT);
    Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, limit));
    return tstate.compound(LIMIT_STEP, limit.makeStorable(tstate));
  }

  /// ```
  /// method addLoopStep(LimitStep limitStep, eKind, loop=, initialState=) {
  ///   if initialState is not LoopExit {
  ///     limit = limitStep_
  ///     if limit > 0 {
  ///       initialState = [limit, initialState]
  ///     } else {
  ///       state = finalState(loop, initialState)
  ///       initialState = (state is LoopExit) ? state : loopExit(state)
  ///     }
  ///   }
  ///   loop = LimitedLoop_(loop)
  /// }
  /// ```
  static class AddLoopStepLimit extends BuiltinMethod {
    static final Caller finalState = new Caller("finalState:2", "afterFinalState");

    @Core.Method("addLoopStep(LimitStep, EnumerationKind, Loop, _)")
    static void begin(
        TState tstate,
        Value limitStep,
        Value eKind,
        @RC.In Value innerLoop,
        @RC.In Value initialState) {
      Value loop = tstate.compound(Core.LIMITED_LOOP, innerLoop);
      initialState
          .isa(Core.LOOP_EXIT)
          .test(
              () -> tstate.setResults(loop, initialState),
              () -> {
                Value limit = limitStep.element(0);
                Condition.numericLessThan(NumValue.ZERO, limit)
                    .test(
                        () -> tstate.setResults(loop, tstate.arrayValue(limit, initialState)),
                        () ->
                            tstate
                                .startCall(finalState, loop.element(0), initialState)
                                .saving(loop));
              });
    }

    @Continuation
    static void afterFinalState(TState tstate, @RC.In Value state, @Saved @RC.In Value loop) {
      Value loopExit =
          state
              .isa(Core.LOOP_EXIT)
              .choose(() -> state, () -> tstate.compound(Core.LOOP_EXIT, state));
      tstate.setResults(loop, loopExit);
    }
  }

  /// ```
  /// method nextState(LimitedLoop limited, [countDown, state], element) {
  ///   assert countDown is Integer and countDown > 0
  ///   state = nextState(limited_, state, element)
  ///   if state is LoopExit {
  ///     return state
  ///   } else if countDown > 1 {
  ///     return [countDown - 1, state]
  ///   }
  ///   state = finalState(limited_, state)
  ///   return state is LoopExit ? state : loopExit(state)
  /// }
  /// ```
  static class NextStateLimited extends BuiltinMethod {
    static final Caller nextState = new Caller("nextState:3", "afterNextState");
    static final Caller finalState = new Caller("finalState:2", "afterFinalState");

    @Core.Method("nextState(LimitedLoop, _, _)")
    static void begin(TState tstate, Value loop, Value state, @RC.In Value element)
        throws BuiltinException {
      Err.NOT_PAIR.unless(state.isArrayOfLength(2));
      Value countDown = state.peekElement(0).verifyInt(Err.INVALID_ARGUMENT);
      Err.INVALID_ARGUMENT.unless(Condition.numericLessThan(NumValue.ZERO, countDown));
      countDown = ValueUtil.subtractInts(tstate, countDown, NumValue.ONE);
      Value innerLoop = loop.element(0);
      Value innerState = state.element(1);
      tstate
          .startCall(nextState, innerLoop, innerState, element)
          .saving(countDown, addRef(innerLoop));
    }

    @Continuation
    static void afterNextState(
        TState tstate, @RC.In Value state, @Saved Value countDown, Value innerLoop) {
      state
          .isa(Core.LOOP_EXIT)
          .test(
              () -> tstate.setResult(state),
              () -> {
                Condition.numericLessThan(NumValue.ZERO, countDown)
                    .test(
                        () -> tstate.setResult(tstate.arrayValue(addRef(countDown), state)),
                        () -> tstate.startCall(finalState, addRef(innerLoop), state));
              });
    }

    @Continuation(order = 2)
    static Value afterFinalState(TState tstate, @RC.In Value state) {
      return state
          .isa(Core.LOOP_EXIT)
          .choose(() -> state, () -> tstate.compound(Core.LOOP_EXIT, state));
    }
  }

  /// `method finalState(LimitedLoop limited, [countDown, state]) = finalState(limited_, state)`
  @Core.Method("finalState(LimitedLoop, _)")
  static void finalStateLimited(
      TState tstate, Value loop, Value state, @Fn("finalState:2") Caller finalState)
      throws BuiltinException {
    Err.NOT_PAIR.unless(state.isArrayOfLength(2));
    Value innerLoop = loop.element(0);
    Value innerState = state.element(1);
    tstate.startCall(finalState, innerLoop, innerState);
  }

  /// `method oneElementIterator(x) = TrivialIterator_(x)`
  @Core.Method("oneElementIterator(_)")
  static Value oneElementIterator(TState tstate, @RC.In Value element) {
    return tstate.compound(TRIVIAL_ITERATOR, element);
  }

  /// ```
  /// method next(TrivialIterator it=) {
  ///   result = it_
  ///   it = emptyIterator()
  ///   return result
  /// }
  /// ```
  @Core.Method("next(TrivialIterator)")
  static void nextTrivialIterator(TState tstate, Value it) {
    tstate.setResults(it.element(0), EMPTY_ITERATOR);
  }

  /// `method emptyState(_) default = Absent`
  @Core.Method("emptyState(_) default")
  static Value emptyStateDefault(Value ignored) {
    return Core.ABSENT;
  }

  /// ```
  /// method splitState(loop, state=) default = emptyState(loop)
  /// ```
  static class SplitStateDefault extends BuiltinMethod {
    static final Caller emptyState = new Caller("emptyState:1", "afterEmptyState");

    @Core.Method("splitState(_, _) default")
    static void begin(TState tstate, @RC.In Value loop, @RC.In Value state) {
      tstate.startCall(emptyState, loop).saving(state);
    }

    @Continuation
    static void afterEmptyState(TState tstate, @RC.In Value emptyState, @Saved @RC.In Value state) {
      tstate.setResults(emptyState, state);
    }
  }

  /// ```
  /// method finalState(Loop loop, state) default = state
  /// ```
  @Core.Method("finalState(_, _) default")
  static Value finalStateDefault(Value loop, @RC.In Value finalState) {
    return finalState;
  }

  /// ```
  /// function loopExit(finalState) = finalState is LoopExit ? finalState : LoopExit_(finalState)
  /// ```
  @Core.Method("loopExit(_)")
  static Value loopExit(TState tstate, @RC.In Value finalState) {
    return finalState
        .isa(Core.LOOP_EXIT)
        .choose(() -> finalState, () -> tstate.compound(Core.LOOP_EXIT, finalState));
  }

  /// ```
  /// function loopExitState(exit) = (exit is LoopExit) ? exit_ : exit
  /// ```
  @Core.Method("loopExitState(_)")
  static Value loopExitState(Value exit) {
    return exit.isa(Core.LOOP_EXIT).choose(() -> exit.element(0), () -> addRef(exit));
  }

  /// ```
  /// method addLoopStep(Lambda lambda, eKind, loop=, initialState=) {
  ///   lambda = applyToValues(lambda, eKind)
  ///   loop = TransformedLoop_({lambda, loop})
  /// }
  /// ```
  @Core.Method("addLoopStep(Lambda, EnumerationKind, Loop, _)")
  static void addLoopStepLambda(
      TState tstate,
      @RC.In Value lambda,
      Value eKind,
      @RC.In Value loop,
      @RC.In Value initialState) {
    lambda = applyToValues(tstate, lambda, eKind);
    loop = tstate.compound(Core.TRANSFORMED_LOOP, lambda, loop);
    tstate.setResults(loop, initialState);
  }

  /// ```
  /// method addLoopStep(CompoundStep cs, eKind, loop=, initialState=) {
  ///   {step1, step2} = cs_
  ///   addLoopStep(step2, eKind, loop=, initialState=)
  ///   addLoopStep(step1, eKind, loop=, initialState=)
  /// }
  /// ```
  static class AddLoopStepCompound extends BuiltinMethod {
    static final Caller addStep2 = new Caller("addLoopStep:4", "afterAddStep2");

    @Core.Method("addLoopStep(CompoundStep, EnumerationKind, Loop, _)")
    static void begin(
        TState tstate, Value cs, Value eKind, @RC.In Value loop, @RC.In Value initialState) {
      Value step1 = cs.element(0);
      Value step2 = cs.element(1);
      tstate.startCall(addStep2, step2, eKind, loop, initialState).saving(step1, eKind);
    }

    @Continuation
    static void afterAddStep2(
        TState tstate,
        @RC.In Value loop,
        @RC.In Value initialState,
        @Saved @RC.In Value step1,
        Value eKind,
        @Fn("addLoopStep:4") Caller addStep1) {
      tstate.startCall(addStep1, step1, eKind, loop, initialState);
    }
  }

  /// ```
  /// method collectorSetup(Reducer reducer, _) = {
  ///     canParallel: True,
  ///     eKind: EnumerateValues,
  ///     initialState: emptyState(reducer),
  ///     loop: reducer
  ///   }
  /// ```
  static class CollectorSetupReducer extends BuiltinMethod {
    static final Caller emptyState = new Caller("emptyState:1", "afterEmptyState");

    @Core.Method("collectorSetup(Reducer, _)")
    static void begin(TState tstate, @RC.In Value reducer, Value ignored) {
      tstate.startCall(emptyState, addRef(reducer)).saving(reducer);
    }

    @Continuation
    static Value afterEmptyState(
        TState tstate, @RC.In Value emptyState, @Saved @RC.In Value reducer) {
      return tstate.compound(SETUP_KEYS, Core.TRUE, ENUMERATE_VALUES, emptyState, reducer);
    }
  }

  /// ```
  /// method enumerate(collection, eKind, loop, state) default =
  ///     iterate(collection, eKind, loop, state)
  /// ```
  @Core.Method("enumerate(_, _, _, _) default")
  static void enumerateDefault(
      TState tstate,
      @RC.In Value collection,
      @RC.In Value eKind,
      @RC.In Value loop,
      @RC.In Value state,
      @Fn("iterate:4") Caller iterate) {
    tstate.startCall(iterate, collection, eKind, loop, state);
  }

  /// ```
  /// function iterateUnbounded(lambda, state) {
  ///   for sequential state {
  ///     if state is LoopExit { break }
  ///     state = at(lambda, state)
  ///   }
  ///   return state
  /// }
  /// ```
  static class IterateUnbounded extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("iterateUnbounded(_, _)")
    static void begin(TState tstate, @RC.In Value lambda, @RC.In Value state) {
      tstate.jump("afterAt", state, lambda);
    }

    @LoopContinuation
    static void afterAt(TState tstate, @RC.In Value state, @Saved Value lambda) {
      state
          .isa(Core.LOOP_EXIT)
          .test(
              () -> tstate.setResult(state),
              () -> tstate.startCall(at, addRef(lambda), state).saving(addRef(lambda)));
    }
  }

  /// Given the iterator argument passed to [Iterate#afterIterator], checks to see if we can
  /// determine an upper bound on the number of iterations. If so, returns the bound; otherwise
  /// returns -1.
  ///
  /// Currently recognizes
  ///
  /// - SimpleRangeIterator,
  /// - RangeIterator or ReversedRangeIterator with a bound other than None, and
  /// - constant base matrices (as returned by `keys(Matrix)`).
  private static int iteratorBound(Value it) {
    it = RValue.exploreSafely(it);
    BaseType baseType = it.baseType();
    if (baseType == RangeCore.RANGE_ITERATOR
        || baseType == RangeCore.REVERSED_RANGE_ITERATOR
        || baseType == RangeCore.SIMPLE_RANGE_ITERATOR) {
      Value next = it.peekElement(0);
      Value end = it.peekElement(1);
      if (next instanceof NumValue && end instanceof NumValue) {
        long result = NumValue.asInt(end) - (long) NumValue.asInt(next);
        if (baseType == RangeCore.RANGE_ITERATOR) {
          result = result + 1;
        } else if (baseType == RangeCore.REVERSED_RANGE_ITERATOR) {
          result = 1 - result;
        } else {
          assert baseType == RangeCore.SIMPLE_RANGE_ITERATOR;
          result = result - 1;
        }
        try {
          return Math.toIntExact(result);
        } catch (ArithmeticException e) {
          return -1;
        }
      }
      return -1;
    } else if (baseType == TRIVIAL_ITERATOR) {
      Value v = it.peekElement(0);
      return (v == Core.ABSENT) ? 0 : 1;
    } else if (baseType == MatrixCore.BASE_ITERATOR) {
      Value sizes = it.peekElement(2);
      if (RValue.isExplorer(sizes)) {
        return -1;
      }
      int n = sizes.numElements();
      int size = ArrayUtil.productAsInt(sizes::elementAsIntOrMinusOne, n);
      if (size < 0) {
        // Overflowed
        return -1;
      }
      Value prev = it.peekElement(1);
      if (RValue.isExplorer(prev)) {
        return size;
      }
      int skip = 0;
      for (int i = 0; i < n; i++) {
        skip = skip * sizes.elementAsInt(i) + prev.elementAsInt(i) - 1;
      }
      skip += 1;
      assert skip >= 0 && skip <= size;
      return size - skip;
    }
    // TODO: CONCAT_ITERATOR?
    return -1;
  }

  /// ```
  /// function iterate(collection, eKind, loop, state) {
  ///   if state is LoopExit { return state }
  ///   it = iterator(collection, eKind, loop=, state=)
  ///   for sequential state, it {
  ///     if state is LoopExit {
  ///       break { return state }
  ///     }
  ///     element = next(it=)
  ///     if element is Absent {
  ///       break { return finalState(loop, state) }
  ///     }
  ///     state = nextState(loop, state, element)
  ///   }
  /// }
  /// ```
  static class Iterate extends BuiltinMethod {
    static final Caller iterator = new Caller("iterator:4", "afterIterator");
    static final Caller next = new Caller("next:1", "afterNext");
    static final Caller nextState = new Caller("nextState:3", "afterNextState");

    @Core.Method("iterate(_, EnumerationKind, _, _)")
    static void begin(
        TState tstate,
        @RC.In Value collection,
        @RC.Singleton Value eKind,
        @RC.In Value loop,
        @RC.In Value state) {
      state
          .isa(Core.LOOP_EXIT)
          .test(
              () -> {
                tstate.dropValue(collection);
                tstate.dropValue(loop);
                tstate.setResult(state);
              },
              () -> tstate.startCall(iterator, collection, eKind, loop, state));
    }

    /// May be called by BuiltinSupport (with the arguments to our LoopContinuation) to request an
    /// upper bound on the number of backward branches that will be required to complete an
    /// in-progress call; if the bound is low-ish and the loop is simple-ish then code generation
    /// may choose to unroll the loop. May return -1 to indicate that no bound is available.
    static int loopBound(Object[] continuationArgs) {
      return iteratorBound((Value) continuationArgs[2]);
    }

    @Continuation
    static void afterIterator(
        TState tstate, @RC.In Value it, @RC.In Value loop, @RC.In Value state) {
      tstate.jump("afterNextState", state, loop, it);
    }

    @LoopContinuation(order = 2)
    static void afterNextState(
        TState tstate, @RC.In Value state, @Saved @RC.In Value loop, @RC.In Value it) {
      state
          .isa(Core.LOOP_EXIT)
          .test(
              () -> {
                tstate.dropValue(it);
                tstate.dropValue(loop);
                tstate.setResult(state);
              },
              () -> tstate.startCall(next, it).saving(loop, state));
    }

    @Continuation(order = 3)
    static void afterNext(
        TState tstate,
        @RC.In Value element,
        @RC.In Value it,
        @Saved @RC.In Value loop,
        @RC.In Value state,
        @Fn("finalState:2") Caller finalState) {
      element
          .is(Core.ABSENT)
          .test(
              () -> {
                tstate.dropValue(it);
                tstate.startCall(finalState, loop, state);
              },
              () -> tstate.startCall(nextState, addRef(loop), state, element).saving(loop, it));
    }
  }

  /// ```
  /// method pipe(Collection collection, Collector collector) {
  ///   { canParallel, eKind, initialState, loop } = collectorSetup(collector, collection)
  ///   if initialState is LoopExit {
  ///     state = initialState
  ///   } else if canParallel {
  ///     state = enumerate(collection, eKind, loop, initialState)
  ///   } else {
  ///     state = iterate(collection, eKind, loop, initialState)
  ///   }
  ///   return loopExitState(state)
  /// }
  /// ```
  static class PipeCollectionCollector extends BuiltinMethod {
    static final Caller collectorSetup = new Caller("collectorSetup:2", "afterCollectorSetup");
    static final Caller enumerate = new Caller("enumerate:4", "done");
    static final Caller iterate = new Caller("iterate:4", "done");

    @Core.Method("pipe(Collection, Collector) default")
    static void begin(TState tstate, @RC.In Value collection, @RC.In Value collector) {
      tstate.startCall(collectorSetup, collector, addRef(collection)).saving(collection);
    }

    @Continuation
    static void afterCollectorSetup(TState tstate, Value csResult, @Saved @RC.In Value collection)
        throws BuiltinException {
      Err.COLLECTOR_SETUP_RESULT.unless(SETUP_KEYS.matches(csResult));
      Value canParallel = csResult.peekElement(0);
      Value eKind = csResult.peekElement(1);
      Err.COLLECTOR_SETUP_RESULT.unless(
          canParallel.isa(Core.BOOLEAN).and(eKind.isa(ENUMERATION_KIND)));
      Value initialState = csResult.element(2);
      initialState
          .isa(Core.LOOP_EXIT)
          .test(
              () -> {
                tstate.dropValue(collection);
                tstate.setResult(loopExitState(initialState));
                tstate.dropValue(initialState);
              },
              () -> {
                Value loop = csResult.element(3);
                canParallel
                    .is(Core.TRUE)
                    .test(
                        () -> tstate.startCall(enumerate, collection, eKind, loop, initialState),
                        () -> tstate.startCall(iterate, collection, eKind, loop, initialState));
              });
    }

    @Continuation(order = 2)
    static Value done(Value state) {
      return loopExitState(state);
    }
  }

  /// ```
  /// function loopHelper(collector, collection, maxEKind=, isParallel) {
  ///   { canParallel, eKind, initialState, loop } = collectorSetup(collector, collection)
  ///   assert canParallel or not isParallel, "Can't use sequential collector in parallel loop"
  ///   if eKind is not EnumerateValues and maxEKind is not EnumerateAllKeys {
  ///     maxEKind = eKind
  ///   }
  ///   return {ro: LoopRO_({canParallel, eKind, loop}),
  ///           rw: LoopRW_({pendingValue: Absent, state: initialState})}
  /// }
  /// ```
  static class LoopHelper extends BuiltinMethod {
    static final Caller collectorSetup = new Caller("collectorSetup:2", "afterCollectorSetup");

    @Core.Method("loopHelper(Collector, _, EnumerationKind, Boolean)")
    static void begin(
        TState tstate,
        @RC.In Value collector,
        @RC.In Value collection,
        @RC.Singleton Value maxEKind,
        @RC.Singleton Value isParallel) {
      tstate.startCall(collectorSetup, collector, collection).saving(maxEKind, isParallel);
    }

    @Continuation
    static void afterCollectorSetup(
        TState tstate,
        Value csResult,
        @Saved @RC.Singleton Value maxEKind,
        @RC.Singleton Value isParallel)
        throws BuiltinException {
      Err.COLLECTOR_SETUP_RESULT.unless(SETUP_KEYS.matches(csResult));
      Value canParallel = csResult.peekElement(0);
      Value eKind = csResult.peekElement(1);
      Err.COLLECTOR_SETUP_RESULT.unless(
          canParallel.isa(Core.BOOLEAN).and(eKind.isa(ENUMERATION_KIND)));
      Err.SEQUENTIAL_COLLECTOR.when(isParallel.is(Core.TRUE).and(canParallel.is(Core.FALSE)));
      Value initialState = csResult.element(2);
      Value loop = csResult.element(3);
      Value ro = tstate.compound(LOOP_RO, eKind, loop, canParallel);
      Value rw = tstate.compound(LOOP_RW, Core.ABSENT, initialState);
      Value eKindResult =
          eKind.is(ENUMERATE_VALUES).or(maxEKind.is(ENUMERATE_ALL_KEYS)).choose(maxEKind, eKind);
      tstate.setResults(ro, rw, eKindResult);
    }
  }

  /// ```
  /// // For use in unbounded loops (with no source collection)
  /// function loopHelper(collector) {
  ///   { canParallel, eKind, loop, initialState } = collectorSetup(collector, Absent)
  ///   assert eKind is EnumerateValues
  ///   return {ro: LoopRO_({canParallel, eKind, loop}),
  ///           rw: LoopRW_({pendingValue: Absent, state: initialState})}
  /// }
  /// ```
  static class SimpleLoopHelper extends BuiltinMethod {
    static final Caller collectorSetup = new Caller("collectorSetup:2", "afterCollectorSetup");

    @Core.Method("loopHelper(Collector)")
    static void begin(TState tstate, @RC.In Value collector) {
      tstate.startCall(collectorSetup, collector, Core.ABSENT);
    }

    @Continuation
    static void afterCollectorSetup(TState tstate, Value csResult) throws BuiltinException {
      Err.COLLECTOR_SETUP_RESULT.unless(SETUP_KEYS.matches(csResult));
      Value canParallel = csResult.peekElement(0);
      Value eKind = csResult.peekElement(1);
      Err.COLLECTOR_SETUP_RESULT.unless(
          canParallel.isa(Core.BOOLEAN).and(eKind.isa(ENUMERATION_KIND)));
      Err.KEYED_COLLECTOR.unless(eKind.is(ENUMERATE_VALUES));
      Value initialState = csResult.element(2);
      Value loop = csResult.element(3);
      tstate.setResults(
          tstate.compound(LOOP_RO, eKind, loop, canParallel),
          tstate.compound(LOOP_RW, Core.ABSENT, initialState));
    }
  }

  /// ```
  /// function finalStateHelper(ro, rw) {
  ///   {pendingValue, state} = rw_
  ///   assert pendingValue is Absent
  ///   if state is not LoopExit {
  ///     state = finalState(ro_.loop, state)
  ///   }
  ///   return loopExitState(state)
  /// }
  /// ```
  static class FinalStateHelper2 extends BuiltinMethod {
    static final Caller finalState = new Caller("finalState:2", "afterFinalState");

    @Core.Method("finalStateHelper(LoopRO, LoopRW)")
    static void begin(TState tstate, Value ro, Value rw) throws BuiltinException {
      Value pendingValue = rw.peekElement(0);
      Err.INVALID_ARGUMENT.unless(pendingValue.is(Core.ABSENT));
      Value state = rw.peekElement(1);
      state
          .isa(Core.LOOP_EXIT)
          .test(
              () -> tstate.setResult(state.element(0)),
              () -> {
                Value loop = ro.element(1);
                tstate.startCall(finalState, loop, state.makeStorable(tstate));
              });
    }

    @Continuation
    static Value afterFinalState(Value state) {
      return loopExitState(state);
    }
  }

  /// ```
  /// function finalStateHelper(ro, rw, key) {
  ///   {pendingValue, state} = rw_
  ///   if ro_.eKind is not EnumerateValues {
  ///     if state is not LoopExit and (pendingValue is not Absent or ro_.eKind is EnumerateAllKeys) {
  ///       state = nextState(ro_.loop, state, [key, pendingValue])
  ///     }
  ///   }
  ///   if state is not LoopExit {
  ///     state = finalState(ro_.loop, state)
  ///   }
  ///   return loopExitState(state)
  /// }
  /// ```
  static class FinalStateHelper3 extends BuiltinMethod {
    static final Caller nextState = new Caller("nextState:3", "afterNextState");
    static final Caller finalState = new Caller("finalState:2", "afterFinalState");

    @Core.Method("finalStateHelper(LoopRO, LoopRW, _)")
    static void begin(TState tstate, Value ro, Value rw, @RC.In Value key) {
      Value pendingValue = rw.peekElement(0);
      Value state = rw.element(1);
      Value eKind = ro.peekElement(0);
      Value loop = ro.element(1);
      eKind
          .is(ENUMERATE_VALUES)
          .or(state.isa(Core.LOOP_EXIT))
          .or(pendingValue.is(Core.ABSENT).and(eKind.is(ENUMERATE_WITH_KEYS)))
          .test(
              () -> {
                tstate.dropValue(key);
                tstate.jump("afterNextState", state, loop);
              },
              () -> {
                tstate
                    .startCall(nextState, loop, state, tstate.arrayValue(key, addRef(pendingValue)))
                    .saving(addRef(loop));
              });
    }

    @Continuation
    static void afterNextState(TState tstate, @RC.In Value state, @Saved @RC.In Value loop) {
      state
          .isa(Core.LOOP_EXIT)
          .test(
              () -> {
                tstate.dropValue(loop);
                tstate.setResult(state);
              },
              () -> tstate.startCall(finalState, loop, state));
    }

    @Continuation(order = 2)
    static Value afterFinalState(Value state) {
      return loopExitState(state);
    }
  }

  /// ```
  /// procedure emitValue(ro, rw=, v) {
  ///   if v is Absent {
  ///     return
  ///   }
  ///   {pendingValue, state} = rw_
  ///   assert pendingValue is Absent, "Can't emit twice to keyed collector"
  ///   if state is not LoopExit {
  ///     if ro_.eKind is EnumerateValues {
  ///       // Unkeyed collector updates state immediately
  ///       rw_.state = nextState(ro_.loop, state, v)
  ///     } else {
  ///       // Keyed collector just saves the emitted value; the state will be updated by a call
  ///       // to emitKey() at the end of the loop body.
  ///       rw_.pendingValue = v
  ///     }
  ///   }
  /// }
  /// ```
  static class EmitValue extends BuiltinMethod {
    static final Caller nextState = new Caller("nextState:3", "afterNextState");

    @Core.Method("emitValue(LoopRO, LoopRW, _)")
    static void begin(TState tstate, Value ro, Value rw, Value v) throws BuiltinException {
      v.is(Core.ABSENT)
          .testExcept(
              () -> tstate.setResult(addRef(rw)),
              () -> {
                Value pendingValue = rw.peekElement(0);
                Err.DOUBLE_EMIT.unless(pendingValue.is(Core.ABSENT));
                Value state = rw.element(1);
                state
                    .isa(Core.LOOP_EXIT)
                    .test(
                        () -> {
                          tstate.dropValue(state);
                          tstate.setResult(addRef(rw));
                        },
                        () -> {
                          Value eKind = ro.peekElement(0);
                          eKind
                              .is(ENUMERATE_VALUES)
                              .test(
                                  () -> {
                                    Value loop = ro.element(1);
                                    tstate.startCall(nextState, loop, state, addRef(v));
                                  },
                                  () ->
                                      tstate.setResult(tstate.compound(LOOP_RW, addRef(v), state)));
                        });
              });
    }

    @Continuation
    static Value afterNextState(TState tstate, @RC.In Value state) {
      return tstate.compound(LOOP_RW, Core.ABSENT, state);
    }
  }

  /// ```
  /// procedure emitAll(ro, rw=, collection) {
  ///   assert ro_.eKind is EnumerateValues, "Can't emitAll to keyed collector"
  ///   {pendingValue, state} = rw_
  ///   assert pendingValue is Absent
  ///   if state is not LoopExit {
  ///     // Wrap the collector's loop so that we can handle the exit conditions
  ///     // properly.
  ///     loop = EmitAllLoop_(ro_.loop)
  ///     if ro_.canParallel {
  ///       state = enumerate(collection, EnumerateValues, loop, state)
  ///     } else {
  ///       state = iterate(collection, EnumerateValues, loop, state)
  ///     }
  ///     if state is LoopExit {
  ///       // If this LoopExit came from the collector, we keep it (but remove
  ///       // the CollectorExited tag).  If it came from somewhere else in the
  ///       // pipeline (e.g. a `limit()` step) then we drop it -- the collector
  ///       // isn't done.
  ///       state = loopExitState(state)
  ///       if state is CollectorExited {
  ///         state = LoopExit_(state_)
  ///       }
  ///     }
  ///     rw_.state = state
  ///   }
  /// }
  /// ```
  static class EmitAll extends BuiltinMethod {
    static final Caller enumerate = new Caller("enumerate:4", "done");
    static final Caller iterate = new Caller("iterate:4", "done");

    @Core.Method("emitAll(LoopRO, LoopRW, _)")
    static void begin(TState tstate, Value ro, Value rw, @RC.In Value collection)
        throws BuiltinException {
      Value eKind = ro.peekElement(0);
      Err.EMIT_ALL_KEYED.unless(eKind.is(LoopCore.ENUMERATE_VALUES));
      Value pendingValue = rw.peekElement(0);
      Err.INVALID_ARGUMENT.unless(pendingValue.is(Core.ABSENT));
      Value state = rw.element(1);
      state
          .isa(Core.LOOP_EXIT)
          .test(
              () -> {
                tstate.dropValue(state);
                tstate.dropValue(collection);
                tstate.setResult(addRef(rw));
              },
              () -> {
                Value loop = tstate.compound(EMIT_ALL_LOOP, ro.element(1));
                Value canParallel = ro.peekElement(2);
                canParallel
                    .is(Core.TRUE)
                    .test(
                        () ->
                            tstate.startCall(
                                enumerate, collection, LoopCore.ENUMERATE_VALUES, loop, state),
                        () ->
                            tstate.startCall(
                                iterate, collection, LoopCore.ENUMERATE_VALUES, loop, state));
              });
    }

    @Continuation
    static void done(TState tstate, @RC.In Value state) {
      state
          .isa(Core.LOOP_EXIT)
          .test(
              () -> {
                Value inner = state.element(0);
                tstate.dropValue(state);
                inner
                    .isa(COLLECTOR_EXITED)
                    .test(
                        () -> {
                          Value s = tstate.compound(Core.LOOP_EXIT, inner.element(0));
                          tstate.dropValue(inner);
                          tstate.setResult(tstate.compound(LOOP_RW, Core.ABSENT, s));
                        },
                        () -> tstate.setResult(tstate.compound(LOOP_RW, Core.ABSENT, inner)));
              },
              () -> tstate.setResult(tstate.compound(LOOP_RW, Core.ABSENT, state)));
    }
  }

  /// ```
  /// method nextState(EmitAllLoop loop, state, element) {
  ///   assert state is not LoopExit
  ///   state = nextState(loop_, state, element)
  ///   if state is LoopExit {
  ///     // If the collector returns a LoopExit, we pass that back (so that
  ///     // iterate/enumerate stops), but we tag it with a CollectorExited
  ///     // wrapper so that we can then handle it properly in emitAll()
  ///     state = loopExitState(state)
  ///     assert state is not CollectorExited
  ///     return LoopExit_(CollectorExited_(state))
  ///   }
  ///   return state
  /// }
  /// ```
  ///
  /// Note that we don't provide a finalState method for EmitAllLoop, so the call from
  /// iterate/enumerate in emitAll is not passed through to the collector.
  static class NextStateEmitAll extends BuiltinMethod {
    static final Caller nextState = new Caller("nextState:3", "afterNextState");

    @Core.Method("nextState(EmitAllLoop, _, _)")
    static void begin(TState tstate, Value loop, @RC.In Value state, @RC.In Value element)
        throws BuiltinException {
      Err.INVALID_ARGUMENT.when(state.isa(Core.LOOP_EXIT));
      Value innerLoop = loop.element(0);
      tstate.startCall(nextState, innerLoop, state, element);
    }

    @Continuation
    static Value afterNextState(TState tstate, @RC.In Value state) throws BuiltinException {
      return state
          .isa(Core.LOOP_EXIT)
          .chooseExcept(
              () -> {
                Value inner = state.peekElement(0);
                Err.INVALID_ARGUMENT.when(inner.isa(COLLECTOR_EXITED));
                Value wrapped = tstate.compound(COLLECTOR_EXITED, inner.makeStorable(tstate));
                tstate.dropValue(state);
                return tstate.compound(Core.LOOP_EXIT, wrapped);
              },
              () -> state);
    }
  }

  /// ```
  /// procedure emitKey(ro, rw=, key) {
  ///   if ro_.eKind is EnumerateValues {
  ///     return
  ///   }
  ///   {pendingValue, state} = rw_
  ///   if state is not LoopExit and (pendingValue is not Absent or ro_.eKind is EnumerateAllKeys) {
  ///     rw_.pendingValue = Absent
  ///     rw_.state = nextState(ro_.loop, state, [key, pendingValue])
  ///   }
  /// }
  /// ```
  static class EmitKey extends BuiltinMethod {
    static final Caller nextState = new Caller("nextState:3", "afterNextState");

    @Core.Method("emitKey(LoopRO, LoopRW, _)")
    static void begin(TState tstate, Value ro, Value rw, Value key) {
      Value eKind = ro.peekElement(0);
      eKind
          .is(ENUMERATE_VALUES)
          .test(
              () -> tstate.setResult(addRef(rw)),
              () -> {
                Value pendingValue = rw.element(0);
                Value state = rw.element(1);
                state
                    .isa(Core.LOOP_EXIT)
                    .or(pendingValue.is(Core.ABSENT).and(eKind.is(ENUMERATE_WITH_KEYS)))
                    .test(
                        () -> {
                          tstate.dropValue(pendingValue);
                          tstate.dropValue(state);
                          tstate.setResult(addRef(rw));
                        },
                        () -> {
                          Value loop = ro.element(1);
                          tstate.startCall(
                              nextState, loop, state, tstate.arrayValue(addRef(key), pendingValue));
                        });
              });
    }

    @Continuation
    static Value afterNextState(TState tstate, @RC.In Value state) {
      return tstate.compound(LOOP_RW, Core.ABSENT, state);
    }
  }

  /// ```
  /// function emptyStateHelper(ro) = LoopRW_({pendingValue: Absent, state: emptyState(ro_.loop)})
  /// ```
  static class EmptyStateHelper extends BuiltinMethod {
    static final Caller emptyState = new Caller("emptyState:1", "afterEmptyState");

    @Core.Method("emptyStateHelper(LoopRO)")
    static void begin(TState tstate, Value ro) {
      Value loop = ro.element(1);
      tstate.startCall(emptyState, loop);
    }

    @Continuation
    static Value afterEmptyState(TState tstate, @RC.In Value state) {
      return tstate.compound(LOOP_RW, Core.ABSENT, state);
    }
  }

  /// ```
  /// function splitStateHelper(ro, rw=) {
  ///   assert rw_.pendingValue is Absent
  ///   if rw_.state is LoopExit {
  ///     // We can't call splitState with a LoopExit, so just create another
  ///     // LoopExit that we'll discard when it gets back to combineStatesHelper.
  ///     return loopExit(Absent)
  ///   }
  ///   state2 = splitState(ro_.loop, rw_.state=)
  ///   return LoopRW_({pendingValue: Absent, state: state2})
  /// }
  /// ```
  static class SplitStateHelper extends BuiltinMethod {
    static final Caller splitState = new Caller("splitState:2", "afterSplitState");

    @Core.Method("splitStateHelper(LoopRO, LoopRW)")
    static void begin(TState tstate, Value ro, Value rw) throws BuiltinException {
      Value pendingValue = rw.peekElement(0);
      Err.INVALID_ARGUMENT.unless(pendingValue.is(Core.ABSENT));
      Value state = rw.element(1);
      state
          .isa(Core.LOOP_EXIT)
          .test(
              () -> {
                tstate.dropValue(state);
                tstate.setResults(tstate.compound(Core.LOOP_EXIT, Core.ABSENT), addRef(rw));
              },
              () -> {
                Value loop = ro.element(1);
                tstate.startCall(splitState, loop, state);
              });
    }

    @Continuation
    static void afterSplitState(TState tstate, @RC.In Value state2, @RC.In Value state) {
      tstate.setResults(
          tstate.compound(LOOP_RW, Core.ABSENT, state2),
          tstate.compound(LOOP_RW, Core.ABSENT, state));
    }
  }

  /// ```
  /// function combineStatesHelper(ro, rw1, rw2) {
  ///   assert rw1_.pendingValue is Absent and rw2_.pendingValue is Absent
  ///   if rw1_.state is LoopExit {
  ///     // See splitStateHelper()
  ///     if rw2_.state is LoopExit and loopExitState(rw1_.state) is Absent {
  ///       return rw2
  ///     }
  ///     return rw1
  ///   } else if rw2_.state is Absent {
  ///     return rw1
  ///   } else if rw1_.state is Absent or rw2_.state is LoopExit {
  ///     return rw2
  ///   }
  ///   combined = combineStates(ro_.loop, rw1_.state, rw2_.state)
  ///   return LoopRW_({pendingValue: Absent, state: combined})
  /// }
  /// ```
  static class CombineStatesHelper extends BuiltinMethod {
    static final Caller combineStates = new Caller("combineStates:3", "afterCombineStates");

    @Core.Method("combineStatesHelper(LoopRO, LoopRW, LoopRW)")
    static void begin(TState tstate, Value ro, Value rw1, Value rw2) throws BuiltinException {
      Value pendingValue1 = rw1.peekElement(0);
      Value pendingValue2 = rw2.peekElement(0);
      Err.INVALID_ARGUMENT.unless(pendingValue1.is(Core.ABSENT).and(pendingValue2.is(Core.ABSENT)));
      Value state1 = rw1.peekElement(1);
      Value state2 = rw2.peekElement(1);
      state1
          .isa(Core.LOOP_EXIT)
          .test(
              () -> {
                Value value1 = state1.peekElement(0);
                value1
                    .is(Core.ABSENT)
                    .and(state2.isa(Core.LOOP_EXIT))
                    .test(() -> tstate.setResult(addRef(rw2)), () -> tstate.setResult(addRef(rw1)));
              },
              () ->
                  state2
                      .is(Core.ABSENT)
                      .test(
                          () -> tstate.setResult(addRef(rw1)),
                          () ->
                              state1
                                  .is(Core.ABSENT)
                                  .or(state2.isa(Core.LOOP_EXIT))
                                  .test(
                                      () -> tstate.setResult(addRef(rw2)),
                                      () -> {
                                        Value loop = ro.element(1);
                                        tstate.startCall(
                                            combineStates,
                                            loop,
                                            state1.makeStorable(tstate),
                                            state2.makeStorable(tstate));
                                      })));
    }

    @Continuation
    static Value afterCombineStates(TState tstate, @RC.In Value state) {
      return tstate.compound(LOOP_RW, Core.ABSENT, state);
    }
  }

  /// ```
  /// procedure verifyEV(ro) {
  ///   assert ro_.eKind is EnumerateValues, "Can't inherit a keyed collector"
  /// }
  /// ```
  @Core.Method("verifyEV(LoopRO)")
  static void verifyEV(Value ro) throws BuiltinException {
    Value eKind = ro.peekElement(0);
    Err.INHERIT_KEYED_COLLECTOR.unless(eKind.is(ENUMERATE_VALUES));
  }

  /// ```
  /// method pipe(PipelineStep step, Collector collector) (step is not Collection) =
  ///     PipedCollector({step, collector})
  /// ```
  @Core.Method("pipe(PipelineStep-Collection, Collector)")
  static Value pipeStepCollector(TState tstate, @RC.In Value step, @RC.In Value collector) {
    return tstate.compound(PIPED_COLLECTOR, step, collector);
  }

  /// ```
  /// method collectorSetup(PipedCollector pc, collection) {
  ///   // If the step is anything other than a lambda, replace the collection with something that
  ///   // doesn't implement keys(); this will ensure that e.g. `matrix | limit(5) | save` gets an
  ///   // error.
  ///   if pc_.step is  not Lambda {
  ///     collection = PipedCollectionCannotSave
  ///   }
  ///   {eKind, loop, initialState, canParallel} = collectorSetup(pc_.collector, collection)
  ///   addLoopStep(pc_.step, eKind, loop=, initialState=)
  ///   return {eKind, loop, initialState, canParallel}
  /// }
  /// ```
  static class CollectorSetupPiped extends BuiltinMethod {
    static final Caller collectorSetup = new Caller("collectorSetup:2", "afterCollectorSetup");
    static final Caller addLoopStep = new Caller("addLoopStep:4", "afterAddLoopStep");

    @Core.Method("collectorSetup(PipedCollector, _)")
    static void begin(TState tstate, Value pc, @RC.In Value collection) {
      Value step = pc.element(0);
      Value collector = pc.element(1);
      Value c =
          step.isa(Core.LAMBDA)
              .choose(
                  () -> collection,
                  () -> {
                    tstate.dropValue(collection);
                    return PIPED_COLLECTION_NO_KEYS;
                  });
      tstate.startCall(collectorSetup, collector, c).saving(step);
    }

    @Continuation
    static void afterCollectorSetup(TState tstate, Value csResult, @Saved @RC.In Value step)
        throws BuiltinException {
      Err.COLLECTOR_SETUP_RESULT.unless(SETUP_KEYS.matches(csResult));
      Value canParallel = csResult.peekElement(0);
      Value eKind = csResult.peekElement(1);
      Err.COLLECTOR_SETUP_RESULT.unless(
          canParallel.isa(Core.BOOLEAN).and(eKind.isa(ENUMERATION_KIND)));
      Value initialState = csResult.element(2);
      Value loop = csResult.element(3);
      tstate.startCall(addLoopStep, step, eKind, loop, initialState).saving(canParallel, eKind);
    }

    @Continuation(order = 2)
    static Value afterAddLoopStep(
        TState tstate,
        @RC.In Value loop,
        @RC.In Value initialState,
        @Saved @RC.In Value canParallel,
        @RC.In Value eKind) {
      return tstate.compound(SETUP_KEYS, canParallel, eKind, initialState, loop);
    }
  }

  /// ```
  /// function sequentially(Collector collector) = SequentialCollector_(collector)
  /// ```
  @Core.Method("sequentially(Collector)")
  static Value sequentially(TState tstate, @RC.In Value collector) {
    return tstate.compound(SEQUENTIAL_COLLECTOR, collector);
  }

  /// ```
  /// method collectorSetup(SequentialCollector sc, collection) {
  ///   {canParallel, eKind, initialState, loop} = collectorSetup(sc_, collection)
  ///   assert canParallel is Boolean and eKind is EnumerationKind
  ///   return {canParallel: False, eKind, initialState, loop}
  /// }
  /// ```
  static class CollectorSetupSequential extends BuiltinMethod {
    static final Caller collectorSetup = new Caller("collectorSetup:2", "afterCollectorSetup");

    @Core.Method("collectorSetup(SequentialCollector, _)")
    static void begin(TState tstate, Value sc, @RC.In Value collection) {
      tstate.startCall(collectorSetup, sc.element(0), collection);
    }

    @Continuation
    static Value afterCollectorSetup(TState tstate, Value csResult) throws BuiltinException {
      Err.COLLECTOR_SETUP_RESULT.unless(SETUP_KEYS.matches(csResult));
      Value canParallel = csResult.peekElement(0);
      Value eKind = csResult.peekElement(1);
      Err.COLLECTOR_SETUP_RESULT.unless(
          canParallel.isa(Core.BOOLEAN).and(eKind.isa(ENUMERATION_KIND)));
      Value initialState = csResult.element(2);
      Value loop = csResult.element(3);
      return tstate.compound(SETUP_KEYS, Core.FALSE, eKind, initialState, loop);
    }
  }

  private LoopCore() {}
}
