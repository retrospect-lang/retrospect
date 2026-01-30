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
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.RecordLayout;
import org.retrolang.impl.Singleton;
import org.retrolang.impl.StructType;
import org.retrolang.impl.TState;
import org.retrolang.impl.Template;
import org.retrolang.impl.Value;
import org.retrolang.impl.VmFunctionBuilder;
import org.retrolang.impl.VmType;
import org.retrolang.util.ArrayUtil;

/** Core methods providing support for loops. */
public final class LoopCore {

  /** {@code type EnumerationKind} */
  @Core.Public public static final VmType.Union ENUMERATION_KIND = Core.newUnion("EnumerationKind");

  /** {@code singleton EnumerateValues is EnumerationKind} */
  @Core.Public
  public static final Singleton ENUMERATE_VALUES =
      Core.newSingleton("EnumerateValues", ENUMERATION_KIND);

  /** {@code singleton EnumerateWithKeys is EnumerationKind} */
  @Core.Public
  public static final Singleton ENUMERATE_WITH_KEYS =
      Core.newSingleton("EnumerateWithKeys", ENUMERATION_KIND);

  /** {@code singleton EnumerateAllKeys is EnumerationKind} */
  @Core.Public
  public static final Singleton ENUMERATE_ALL_KEYS =
      Core.newSingleton("EnumerateAllKeys", ENUMERATION_KIND);

  /**
   * Values of type Iterator are expected to provide a method for {@code next(it=)}.
   *
   * <p>{@code open type Iterator}
   */
  @Core.Public public static final VmType.Union ITERATOR = Core.newOpenUnion("Iterator");

  /**
   * Returns an Iterator that returns all the elements of {@code collection}, optionally paired with
   * their keys.
   *
   * <p>{@code open function iterator(collection, eKind)}
   */
  @Core.Public
  static final VmFunctionBuilder iterator = VmFunctionBuilder.create("iterator", 2).isOpen();

  /**
   * Returns the next element from an Iterator, or Absent if there are no elements remaining.
   *
   * <p>{@code open function next(it=)}
   */
  @Core.Public
  static final VmFunctionBuilder next = VmFunctionBuilder.create("next", 1).hasInoutArg(0).isOpen();

  /**
   * Given the current state and the next element of the collection, returns the new state.
   *
   * <p>{@code open function nextState(loop, state, element)}
   */
  @Core.Public
  static final VmFunctionBuilder nextState = VmFunctionBuilder.create("nextState", 3).isOpen();

  /**
   * Returns the empty state of a parallelizable loop.
   *
   * <p>{@code open function emptyState(loop)}
   */
  @Core.Public
  static final VmFunctionBuilder emptyState = VmFunctionBuilder.create("emptyState", 1).isOpen();

  /**
   * Returns a new state and updates the given state; the result of combining those two states
   * should be equivalent to the original state. The default implementation leaves {@code state}
   * unchanged and returns {@code emptyState(loop)}.
   *
   * <p>{@code open function splitState(loop, state=)}
   */
  @Core.Public
  static final VmFunctionBuilder splitState =
      VmFunctionBuilder.create("splitState", 2).hasInoutArg(1).isOpen();

  /**
   * Combines two states. Will not be called with either state Absent or a LoopExit.
   *
   * <p>{@code open function combineStates(loop, state1, state2)}
   */
  @Core.Public
  static final VmFunctionBuilder combineStates =
      VmFunctionBuilder.create("combineStates", 3).isOpen();

  /**
   * Starting from the given state, sequentially updates it by calling {@code nextState(loop, state,
   * element)} with each element of the given collection (optionally paired with the corresponding
   * key) until the end of the collection is reached or a LoopExit state is reached. Returns the
   * final state.
   *
   * <p>{@code function iterate(collection, eKind, loop, state)}
   */
  @Core.Public static final VmFunctionBuilder iterate = VmFunctionBuilder.create("iterate", 4);

  /**
   * Starting from the given state, sequentially updates it by calling {@code lambda @ state} until
   * a LoopExit state is reached. Returns the final state.
   *
   * <p>{@code function iterateUnbounded(lambda, state)}
   */
  @Core.Public
  static final VmFunctionBuilder iterateUnbounded = VmFunctionBuilder.create("iterateUnbounded", 2);

  /** {@code open function enumerate(collection, eKind, loop, state)} */
  @Core.Public
  static final VmFunctionBuilder enumerate = VmFunctionBuilder.create("enumerate", 4).isOpen();

  /**
   * {@code private compound TrivialIterator is Iterator}
   *
   * <p>An Iterator that returns no values (if element is Absent) or a single value (its element).
   */
  @Core.Private
  static final BaseType.Named TRIVIAL_ITERATOR = Core.newBaseType("TrivialIterator", 1, ITERATOR);

  /** {@code open type Collector} */
  @Core.Public public static final VmType.Union COLLECTOR = Core.newOpenUnion("Collector");

  /** {@code private compound SequentialCollector is Collector} */
  @Core.Private
  static final BaseType.Named SEQUENTIAL_COLLECTOR =
      Core.newBaseType("SequentialCollector", 1, COLLECTOR);

  /** {@code function loopExit(finalState)} */
  @Core.Public static final VmFunctionBuilder loopExit = VmFunctionBuilder.create("loopExit", 1);

  /** {@code function loopExitState(loopExit)} */
  @Core.Public
  static final VmFunctionBuilder loopExitState = VmFunctionBuilder.create("loopExitState", 1);

  /** {@code function sequentially(collector)} */
  @Core.Public
  static final VmFunctionBuilder sequentially = VmFunctionBuilder.create("sequentially", 1);

  /** {@code function saveSequential() = sequentially(saveUnordered)} */
  @Core.Public
  static final VmFunctionBuilder saveSequential =
      VmFunctionBuilder.fromConstant(
          "saveSequential", SEQUENTIAL_COLLECTOR.uncountedOf(ReducerCore.SAVE_UNORDERED));

  static final Value EMPTY_ITERATOR = TRIVIAL_ITERATOR.uncountedOf(Core.ABSENT);

  /** {@code function emptyIterator() = TrivialIterator_(Absent)} */
  @Core.Public
  static final VmFunctionBuilder emptyIterator =
      VmFunctionBuilder.fromConstant("emptyIterator", EMPTY_ITERATOR);

  /** {@code function oneElementIterator(element)} */
  @Core.Public
  static final VmFunctionBuilder oneElementIterator =
      VmFunctionBuilder.create("oneElementIterator", 1);

  /**
   * {@code open function collectorSetup(collector, collection)}
   *
   * <p>Returns a struct {@code { canParallel, eKind, initialState, loop }}
   */
  @Core.Public
  static final VmFunctionBuilder collectorSetup =
      VmFunctionBuilder.create("collectorSetup", 2).isOpen();

  /**
   * The expected struct keys for the result of {@link #collectorSetup}.
   *
   * <p>Must be in alphabetical order.
   */
  static final StructType SETUP_KEYS =
      new StructType("canParallel", "eKind", "initialState", "loop");

  /**
   * Given the final state of a Collector's loop, returns the value of the pipeline. The default
   * implementation returns the final state after removing any LoopExit wrapper.
   *
   * <p>{@code open function finalResult(loop, finalState)}
   */
  @Core.Public
  static final VmFunctionBuilder finalResult = VmFunctionBuilder.create("finalResult", 2).isOpen();

  /**
   * {@code private compound LoopRO}
   *
   * <p>Elements are {@code eKind}, {@code loop}, {@code canParallel}.
   *
   * <p>A LoopRO value is created for each of a "for" loop's collected variables, holding data
   * returned by the {@code collectorSetup()} function.
   */
  @Core.Private static final BaseType.Named LOOP_RO = Core.newBaseType("LoopRO", 3);

  /**
   * {@code private compound LoopRW}
   *
   * <p>Elements are {@code pendingValue}, {@code state}.
   *
   * <p>A LoopRW value holds the current state of a "for" loop's collected variable.
   */
  @Core.Private static final BaseType.Named LOOP_RW = Core.newBaseType("LoopRW", 2);

  /**
   * {@code procedure emitValue(ro, rw=, v)}
   *
   * <p>A call to {@code emitValue()} is emitted by the compiler for each "{@code <<}" statement.
   */
  @Core.Public
  static final VmFunctionBuilder emitValue =
      VmFunctionBuilder.create("emitValue", 3).hasInoutArg(1).hasNoResult();

  /**
   * {@code procedure emitAll(ro, rw=, key)}
   *
   * <p>A call to {@code emitAll()} is emitted by the compiler for each "{@code <<^}" statement.
   */
  @Core.Public
  static final VmFunctionBuilder emitAll =
      VmFunctionBuilder.create("emitAll", 3).hasInoutArg(1).hasNoResult();

  /**
   * {@code procedure emitKey(ro, rw=, key)}
   *
   * <p>A call to {@code emitKey()} is emitted by the compiler at the end of each loop iteration for
   * for each collected variable.
   */
  @Core.Public
  static final VmFunctionBuilder emitKey =
      VmFunctionBuilder.create("emitKey", 3).hasInoutArg(1).hasNoResult();

  /**
   * {@code function loopHelper(collector, collection, maxEKind=, isParallel)}
   *
   * <p>Returns three results: {@code ro}, {@code rw}, and updated {@code maxEKind}.
   *
   * <p>A call to this {@code loopHelper()} is emitted by the compiler when generating code for a
   * "for" loop over a collection. See docs/loops.md#compiling-for-loops for details.
   */
  @Core.Public
  static final VmFunctionBuilder loopHelper4 =
      VmFunctionBuilder.create("loopHelper", 4).hasInoutArg(2).hasResults(2);

  /**
   * {@code function loopHelper(collector)}
   *
   * <p>Returns two results: {@code ro} and {@code rw}.
   *
   * <p>A call to this {@code loopHelper()} is emitted by the compiler when generating code for an
   * unbounded "for" loop. See docs/loops.md#compiling-for-loops for details.
   */
  @Core.Public
  static final VmFunctionBuilder loopHelper1 =
      VmFunctionBuilder.create("loopHelper", 1).hasResults(2);

  /**
   * {@code function finalResultHelper(ro, rw)}
   *
   * <p>A call to {@code finalResultHelper()} is emitted by the compiler to determine the value of
   * each collected variable when a "for" loop completes.
   */
  @Core.Public
  static final VmFunctionBuilder finalResultHelper2 =
      VmFunctionBuilder.create("finalResultHelper", 2);

  /**
   * {@code function finalResultHelper(ro, rw, key)}
   *
   * <p>A call to this {@code finalResultHelper()} is emitted by the compiler for a {@code break}
   * out of a sequential loop; it combines the functionality of {@code emitKey()} and {@code
   * finalResultHelper(ro, rw)}.
   */
  @Core.Public
  static final VmFunctionBuilder finalResultHelper3 =
      VmFunctionBuilder.create("finalResultHelper", 3);

  /**
   * {@code function emptyStateHelper(ro)}
   *
   * <p>Used to implement the {@code emptyState()} method for the body of a parallelizable loop. See
   * docs/loops.md#compiling-for-loops for details.
   */
  @Core.Public
  static final VmFunctionBuilder emptyStateHelper = VmFunctionBuilder.create("emptyStateHelper", 1);

  /**
   * {@code function splitStateHelper(ro, rw=)}
   *
   * <p>Used to implement the {@code splitState()} method for the body of a parallelizable loop. See
   * docs/loops.md#compiling-for-loops for details.
   */
  @Core.Public
  static final VmFunctionBuilder splitStateHelper =
      VmFunctionBuilder.create("splitStateHelper", 2).hasInoutArg(1);

  /**
   * {@code function combineStatesHelper(ro, rw1, rw2)}
   *
   * <p>Used to implement the {@code combineStates()} method for the body of a parallelizable loop.
   * See docs/loops.md#compiling-for-loops for details.
   */
  @Core.Public
  static final VmFunctionBuilder combineStatesHelper =
      VmFunctionBuilder.create("combineStatesHelper", 3);

  /**
   * {@code procedure verifyEV(ro)}
   *
   * <p>A call to {@code verifyEV()} is emitted by the compiler when an inner loop inherits a
   * collected var from an outer loop; its only function is to error if the collector is keyed.
   */
  @Core.Public
  static final VmFunctionBuilder verifyEv = VmFunctionBuilder.create("verifyEV", 1).hasNoResult();

  /** {@code method oneElementIterator(x) = TrivialIterator_(x)} */
  @Core.Method("oneElementIterator(_)")
  static Value oneElementIterator(TState tstate, @RC.In Value element) {
    return tstate.compound(TRIVIAL_ITERATOR, element);
  }

  /**
   * <pre>
   * method next(TrivialIterator it=) {
   *   result = it_
   *   it = emptyIterator()
   *   return result
   * }
   * </pre>
   */
  @Core.Method("next(TrivialIterator)")
  static void nextTrivialIterator(TState tstate, Value it) {
    tstate.setResults(it.element(0), EMPTY_ITERATOR);
  }

  /** {@code method emptyState(_) default = Absent} */
  @Core.Method("emptyState(_) default")
  static Value emptyStateDefault(Value ignored) {
    return Core.ABSENT;
  }

  /**
   * <pre>
   * method splitState(loop, state=) default = emptyState(loop)
   * </pre>
   */
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

  /**
   * <pre>
   * method finalResult(Loop loop, finalState) default =
   *     finalState is LoopExit ? loopExitState(finalState) : finalState
   * </pre>
   */
  @Core.Method("finalResult(_, _) default")
  static Value finalResultDefault(Value loop, Value finalState) {
    return finalState
        .isa(Core.LOOP_EXIT)
        .choose(() -> finalState.element(0), () -> addRef(finalState));
  }

  /**
   * <pre>
   * function loopExit(finalState) = finalState is LoopExit ? finalState : LoopExit_(finalState)
   * </pre>
   */
  @Core.Method("loopExit(_)")
  static Value loopExit(TState tstate, @RC.In Value finalState) {
    return finalState
        .isa(Core.LOOP_EXIT)
        .choose(() -> finalState, () -> tstate.compound(Core.LOOP_EXIT, finalState));
  }

  /**
   * <pre>
   * function loopExitState(exit) {
   *   assert exit is LoopExit
   *   return exit_
   * }
   * </pre>
   */
  @Core.Method("loopExitState(_)")
  static Value loopExitState(Value exit) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(exit.isa(Core.LOOP_EXIT));
    return exit.element(0);
  }

  /**
   * <pre>
   * method collectorSetup(Reducer reducer, _) = {
   *     canParallel: True,
   *     eKind: EnumerateValues,
   *     initialState: emptyState(reducer),
   *     loop: reducer
   *   }
   * </pre>
   */
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

  /**
   * <pre>
   * method enumerate(collection, eKind, loop, state) default =
   *     iterate(collection, eKind, loop, state)
   * </pre>
   */
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

  /**
   * <pre>
   * function iterateUnbounded(lambda, state) {
   *   for sequential state {
   *     if state is LoopExit { break }
   *     state = at(lambda, state)
   *   }
   *   return state
   * }
   * </pre>
   */
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

  /**
   * Given the iterator argument passed to {@link Iterate#afterIterator}, checks to see if we can
   * determine an upper bound on the number of iterations. If so, returns the bound; otherwise
   * returns -1.
   *
   * <p>Currently recognizes
   *
   * <ul>
   *   <li>ArrayIterator if the array's length is known,
   *   <li>RangeIterator or ReversedRangeIterator with a bound other than None,
   *   <li>constant base matrices (as returned by {@code keys(Matrix)}),
   *   <li>a transformed or reshaped collection if we recognize the base collection, and
   *   <li>a join result, if we recognize either of the collections being joined.
   * </ul>
   */
  private static int iteratorBound(Value it) {
    it = RValue.exploreSafely(it);
    for (; ; ) {
      BaseType baseType = it.baseType();
      if (baseType == ArrayCore.ARRAY_ITERATOR) {
        Value array = it.peekElement(0);
        int numElements = array.numElements();
        if (numElements > 0) {
          Value prevIndex = it.peekElement(2);
          if (prevIndex instanceof NumValue) {
            numElements -= NumValue.asInt(prevIndex);
          }
        }
        return numElements;
      } else if (baseType == RangeCore.RANGE_ITERATOR
          || baseType == RangeCore.REVERSED_RANGE_ITERATOR) {
        Value next = it.peekElement(0);
        Value end = it.peekElement(1);
        if (next instanceof NumValue && end instanceof NumValue) {
          long result = NumValue.asInt(end) - (long) NumValue.asInt(next);
          result = Math.abs(result + (baseType == RangeCore.RANGE_ITERATOR ? 1 : -1));
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
      } else if (baseType == StructCore.STRUCT_ITERATOR) {
        Value v = it.peekElement(0);
        BaseType vBaseType = v.baseType();
        if (vBaseType == null) {
          return -1;
        }
        int size = vBaseType.size();
        Value prevIndex = it.peekElement(2);
        return (prevIndex instanceof NumValue) ? size - NumValue.asInt(prevIndex) : size;
      } else if (baseType == MatrixCore.BASE_ITERATOR) {
        Value sizes = it.peekElement(2);
        if (RValue.isExplorer(sizes)) {
          return -1;
        }
        int n = sizes.numElements();
        int size = ArrayUtil.productAsInt(sizes::elementAsIntOrMinusOne, n);
        if (size < 0) {
          // Overflowed
          return size;
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
      } else if (baseType == CollectionCore.TRANSFORMED_ITERATOR
          || baseType == CollectionCore.WITH_KEYS_ITERATOR
          || baseType == MatrixCore.RESHAPED_ITERATOR
          || baseType == CollectionCore.JOINED_ITERATOR) {
        it = it.peekElement(0);
        continue;
      }
      // TODO: CONCAT_ITERATOR?
      return -1;
    }
  }

  /**
   * <pre>
   * function iterate(collection, eKind, loop, state) {
   *   if state is LoopExit { return state }
   *   it = iterator(collection, eKind)
   *   for sequential state, it {
   *     element = next(it=)
   *     if element is Absent { break }
   *     state = nextState(loop, state, element)
   *     if state is LoopExit { break }
   *   }
   *   return state
   * }
   * </pre>
   */
  static class Iterate extends BuiltinMethod {
    static final Caller iterator = new Caller("iterator:2", "afterIterator");
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
              () -> tstate.startCall(iterator, collection, eKind).saving(loop, state));
    }

    /**
     * May be called by BuiltinSupport (with the arguments to our LoopContinuation) to request an
     * upper bound on the number of backward branches that will be required to complete an
     * in-progress call; if the bound is low-ish and the loop is simple-ish then code generation may
     * choose to unroll the loop. May return -1 to indicate that no bound is available.
     */
    static int loopBound(Object[] continuationArgs) {
      return iteratorBound((Value) continuationArgs[0]);
    }

    @LoopContinuation
    static void afterIterator(
        TState tstate, @RC.In Value it, @Saved @RC.In Value loop, @RC.In Value state) {
      tstate.startCall(next, it).saving(loop, state);
    }

    @Continuation(order = 2)
    static void afterNext(
        TState tstate,
        @RC.In Value element,
        @RC.In Value it,
        @Saved @RC.In Value loop,
        @RC.In Value state) {
      element
          .is(Core.ABSENT)
          .test(
              () -> {
                tstate.dropValue(it);
                tstate.dropValue(loop);
                tstate.setResult(state);
              },
              () -> tstate.startCall(nextState, addRef(loop), state, element).saving(loop, it));
    }

    @Continuation(order = 3)
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
              () -> tstate.jump("afterIterator", it, loop, state));
    }
  }

  /**
   * <pre>
   * method pipe(Collection collection, Collector collector) {
   *   { canParallel, eKind, initialState, loop } = collectorSetup(collector, collection)
   *   if initialState is LoopExit {
   *     state = initialState
   *   } else if canParallel {
   *     state = enumerate(collection, eKind, loop, initialState)
   *   } else {
   *     state = iterate(collection, eKind, loop, initialState)
   *   }
   *   return finalResult(loop, state)
   * }
   * </pre>
   */
  static class PipeCollectionCollector extends BuiltinMethod {
    static final Caller collectorSetup = new Caller("collectorSetup:2", "afterCollectorSetup");
    static final Caller enumerate = new Caller("enumerate:4", "done");
    static final Caller iterate = new Caller("iterate:4", "done");

    @Core.Method("pipe(Collection, Collector) default")
    static void begin(TState tstate, @RC.In Value collection, @RC.In Value collector) {
      tstate.startCall(collectorSetup, collector, addRef(collection)).saving(collection);
    }

    @Continuation
    static void afterCollectorSetup(TState tstate, Value csResult, @Saved Value collection)
        throws BuiltinException {
      Err.COLLECTOR_SETUP_RESULT.unless(SETUP_KEYS.matches(csResult));
      Value canParallel = csResult.peekElement(0);
      Value eKind = csResult.peekElement(1);
      Err.COLLECTOR_SETUP_RESULT.unless(
          canParallel.isa(Core.BOOLEAN).and(eKind.isa(ENUMERATION_KIND)));
      Value initialState = csResult.element(2);
      Value loop = csResult.element(3);
      initialState
          .isa(Core.LOOP_EXIT)
          .test(
              () -> tstate.jump("done", initialState, loop),
              () ->
                  canParallel
                      .is(Core.TRUE)
                      .test(
                          () ->
                              tstate
                                  .startCall(
                                      enumerate,
                                      addRef(collection),
                                      eKind,
                                      addRef(loop),
                                      initialState)
                                  .saving(loop),
                          () ->
                              tstate
                                  .startCall(
                                      iterate,
                                      addRef(collection),
                                      eKind,
                                      addRef(loop),
                                      initialState)
                                  .saving(loop)));
    }

    @Continuation(order = 2)
    static void done(
        TState tstate,
        @RC.In Value state,
        @Saved @RC.In Value loop,
        @Fn("finalResult:2") Caller finalResult) {
      tstate.startCall(finalResult, loop, state);
    }
  }

  /**
   * <pre>
   * function loopHelper(collector, collection, maxEKind=, isParallel) {
   *   { canParallel, eKind, initialState, loop } = collectorSetup(collector, collection)
   *   assert canParallel or not isParallel, "Can't use sequential collector in parallel loop"
   *   if eKind is not EnumerateValues and maxEKind is not EnumerateAllKeys {
   *     maxEKind = eKind
   *   }
   *   return {ro: LoopRO_({canParallel, eKind, loop}),
   *           rw: LoopRW_({pendingValue: Absent, state: initialState})}
   * }
   * </pre>
   */
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

  /**
   * <pre>
   * // For use in unbounded loops (with no source collection)
   * function loopHelper(collector) {
   *   { canParallel, eKind, loop, initialState } = collectorSetup(collector, Absent)
   *   assert eKind is EnumerateValues
   *   return {ro: LoopRO_({canParallel, eKind, loop}),
   *           rw: LoopRW_({pendingValue: Absent, state: initialState})}
   * }
   * </pre>
   */
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

  /**
   * <pre>
   * function finalResultHelper(ro, rw) {
   *   {pendingValue, state} = rw_
   *   assert pendingValue is Absent
   *   return finalResult(ro_.loop, state)
   * }
   * </pre>
   */
  @Core.Method("finalResultHelper(LoopRO, LoopRW)")
  static void finalResultHelper(
      TState tstate, Value ro, Value rw, @Fn("finalResult:2") Caller finalResult)
      throws BuiltinException {
    Value pendingValue = rw.peekElement(0);
    Err.INVALID_ARGUMENT.unless(pendingValue.is(Core.ABSENT));
    Value loop = ro.element(1);
    Value state = rw.element(1);
    tstate.startCall(finalResult, loop, state);
  }

  /**
   * <pre>
   * procedure emitValue(ro, rw=, v) {
   *   if v is Absent {
   *     return
   *   }
   *   {pendingValue, state} = rw_
   *   assert pendingValue is Absent, "Can't emit twice to keyed collector"
   *   if state is not LoopExit {
   *     if ro_.eKind is EnumerateValues {
   *       // Unkeyed collector updates state immediately
   *       rw_.state = nextState(ro_.loop, state, v)
   *     } else {
   *       // Keyed collector just saves the emitted value; the state will be updated by a call
   *       // to emitKey() at the end of the loop body.
   *       rw_.pendingValue = v
   *     }
   *   }
   * }
   * </pre>
   */
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

  /**
   * <pre>
   * procedure emitAll(ro, rw=, collection) {
   *   assert ro_.eKind is EnumerateValues, "Can't emitAll to keyed collector"
   *   {pendingValue, state} = rw_
   *   assert pendingValue is Absent
   *   if state is not LoopExit {
   *     if ro_.canParallel {
   *       state = enumerate(collection, EnumerateValues, ro_.loop, state)
   *     } else {
   *       state = iterate(collection, EnumerateValues, ro_.loop, state)
   *     }
   *     rw_.state = state
   *   }
   * }
   * </pre>
   */
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
                Value loop = ro.element(1);
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
    static Value done(TState tstate, @RC.In Value state) {
      return tstate.compound(LOOP_RW, Core.ABSENT, state);
    }
  }

  /**
   * <pre>
   * procedure emitKey(ro, rw=, key) {
   *   if ro_.eKind is EnumerateValues {
   *     return
   *   }
   *   {pendingValue, state} = rw_
   *   if state is not LoopExit and (pendingValue is not Absent or ro_.eKind is EnumerateAllKeys) {
   *     rw_.pendingValue = Absent
   *     rw_.state = nextState(ro_.loop, state, [key, pendingValue])
   *   }
   * }
   * </pre>
   */
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

  /**
   * <pre>
   * function emptyStateHelper(ro) = LoopRW_({pendingValue: Absent, state: emptyState(ro_.loop)})
   * </pre>
   */
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

  /**
   * <pre>
   * function splitStateHelper(ro, rw=) {
   *   assert rw_.pendingValue is Absent
   *   if rw_.state is LoopExit {
   *     // We can't call splitState with a LoopExit, so just create another
   *     // LoopExit that we'll discard when it gets back to combineStatesHelper.
   *     return loopExit(Absent)
   *   }
   *   state2 = splitState(ro_.loop, rw_.state=)
   *   return LoopRW_({pendingValue: Absent, state: state2})
   * }
   * </pre>
   */
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

  /**
   * <pre>
   * function combineStatesHelper(ro, rw1, rw2) {
   *   assert rw1_.pendingValue is Absent and rw2_.pendingValue is Absent
   *   if rw1_.state is LoopExit {
   *     // See splitStateHelper()
   *     if rw2_.state is LoopExit and loopExitState(rw1_.state) is Absent {
   *       return rw2
   *     }
   *     return rw1
   *   } else if rw2_.state is Absent {
   *     return rw1
   *   } else if rw1_.state is Absent or rw2_.state is LoopExit {
   *     return rw2
   *   }
   *   combined = combineStates(ro_.loop, rw1_.state, rw2_.state)
   *   return LoopRW_({pendingValue: Absent, state: combined})
   * }
   * </pre>
   */
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

  /**
   * <pre>
   * procedure verifyEV(ro) {
   *   assert ro_.eKind is EnumerateValues, "Can't inherit a keyed collector"
   * }
   * </pre>
   */
  @Core.Method("verifyEV(LoopRO)")
  static void verifyEV(Value ro) throws BuiltinException {
    Value eKind = ro.peekElement(0);
    Err.INHERIT_KEYED_COLLECTOR.unless(eKind.is(ENUMERATE_VALUES));
  }

  /**
   * <pre>
   * function sequentially(Collector collector) = SequentialCollector_(collector)
   * </pre>
   */
  @Core.Method("sequentially(Collector)")
  static Value sequentially(TState tstate, @RC.In Value collector) {
    return tstate.compound(SEQUENTIAL_COLLECTOR, collector);
  }

  /**
   * <pre>
   * method collectorSetup(SequentialCollector sc, collection) {
   *   {canParallel, eKind, initialState, loop} = collectorSetup(sc_, collection)
   *   assert canParallel is Boolean and eKind is EnumerationKind
   *   return {canParallel: False, eKind, initialState, loop}
   * }
   * </pre>
   */
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
