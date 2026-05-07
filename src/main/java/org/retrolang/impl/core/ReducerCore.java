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
import org.retrolang.impl.FrameLayout;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.ResultsInfo;
import org.retrolang.impl.Singleton;
import org.retrolang.impl.TProperty;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.ValueUtil;
import org.retrolang.impl.VmFunctionBuilder;
import org.retrolang.impl.VmType;

/** Core Reducer implementations. */
public final class ReducerCore {
  /** {@code open type Reducer is Collector, Loop} */
  @Core.Public
  public static final VmType.Union REDUCER =
      Core.newOpenUnion("Reducer", LoopCore.COLLECTOR, LoopCore.LOOP);

  /** {@code private singleton Count is Reducer} */
  @Core.Private public static final Singleton COUNT = Core.newSingleton("Count", REDUCER);

  /** {@code function count() = Count} */
  @Core.Public
  static final VmFunctionBuilder count = VmFunctionBuilder.fromConstant("count", COUNT);

  /** {@code method emptyState(Count) = 0} */
  @Core.Method("emptyState(Count)")
  static Value emptyStateCount(Value reducer) {
    return NumValue.ZERO;
  }

  /** {@code method nextState(Count, Integer state, _) = state + 1} */
  @Core.Method("nextState(Count, Number, _)")
  static Value nextStateCount(TState tstate, Value reducer, Value state, Value unused)
      throws BuiltinException {
    state = state.verifyInt(Err.INVALID_ARGUMENT);
    Value result = ValueUtil.addInts(tstate, state, NumValue.ONE);
    // Error on overflow
    Err.INVALID_ARGUMENT.unless(Condition.numericLessThan(NumValue.ZERO, result));
    return result;
  }

  /** {@code method combineStates(Count, Integer state1, Integer state2) = state1 + state2} */
  @Core.Method("combineStates(Count, Number, Number)")
  static Value combineStatesCount(TState tstate, Value reducer, Value state1, Value state2)
      throws BuiltinException {
    state1 = state1.verifyInt(Err.INVALID_ARGUMENT);
    state2 = state2.verifyInt(Err.INVALID_ARGUMENT);
    Value result = ValueUtil.addInts(tstate, state1, state2);
    Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, result));
    return result;
  }

  /**
   * {@code private compound Sum is Reducer}
   *
   * <p>Element is the zero value.
   */
  @Core.Private static final BaseType.Named SUM = Core.newBaseType("Sum", 1, REDUCER);

  /** {@code function sum() = Sum_(0)} */
  @Core.Public
  static final VmFunctionBuilder sum0 =
      VmFunctionBuilder.fromConstant("sum", SUM.uncountedOf(NumValue.ZERO));

  /** {@code function sum(zero)} */
  @Core.Public static final VmFunctionBuilder sum1 = VmFunctionBuilder.create("sum", 1);

  /** {@code method sum(x) = Sum_(x)} */
  @Core.Method("sum(_)")
  static Value sum1(TState tstate, @RC.In Value zero) {
    return tstate.compound(SUM, zero);
  }

  /** {@code method emptyState(Sum reducer) = reducer_} */
  @Core.Method("emptyState(Sum)")
  static Value emptyStateSum(Value reducer) {
    return reducer.element(0);
  }

  /**
   * <pre>
   * method nextState(Sum, state, value) {
   *   state += value
   *   return state
   * }
   * </pre>
   *
   * <p>(Defined using "{@code +=}" instead of "{@code +}" to get the right behavior with matrices.)
   */
  @Core.Method("nextState(Sum, _, _)")
  static void nextStateSum(
      TState tstate,
      Value reducer,
      @RC.In Value state,
      @RC.In Value value,
      @Fn("binaryUpdate:3") Caller binaryUpdate) {
    tstate.startCall(binaryUpdate, state, Core.add.asLambdaExpr(), value);
  }

  /**
   * <pre>
   * method combineStates(Sum, state1, state2) {
   *   state1 += state2
   *   return state1
   * }
   * </pre>
   */
  @Core.Method("combineStates(Sum, _, _)")
  static void combineStatesSum(
      TState tstate,
      Value reducer,
      @RC.In Value state1,
      @RC.In Value state2,
      @Fn("binaryUpdate:3") Caller binaryUpdate) {
    tstate.startCall(binaryUpdate, state1, Core.add.asLambdaExpr(), state2);
  }

  /**
   * {@code private compound BooleanReducer is Reducer}
   *
   * <p>Elements are {@code initial} and {@code exitOn}.
   */
  @Core.Private
  static final BaseType.Named BOOLEAN_REDUCER = Core.newBaseType("BooleanReducer", 2, REDUCER);

  /** {@code function allTrue() = BooleanReducer_({initial: True, exitOn: False})} */
  @Core.Public
  static final VmFunctionBuilder allTrue =
      VmFunctionBuilder.fromConstant("allTrue", BOOLEAN_REDUCER.uncountedOf(Core.TRUE, Core.FALSE));

  /** {@code function allFalse() = BooleanReducer_({initial: True, exitOn: True})} */
  @Core.Public
  static final VmFunctionBuilder allFalse =
      VmFunctionBuilder.fromConstant("allFalse", BOOLEAN_REDUCER.uncountedOf(Core.TRUE, Core.TRUE));

  /** {@code function anyTrue() = BooleanReducer_({initial: False, exitOn: True})} */
  @Core.Public
  static final VmFunctionBuilder anyTrue =
      VmFunctionBuilder.fromConstant("anyTrue", BOOLEAN_REDUCER.uncountedOf(Core.FALSE, Core.TRUE));

  /** {@code function anyFalse() = BooleanReducer_({initial: False, exitOn: False})} */
  @Core.Public
  static final VmFunctionBuilder anyFalse =
      VmFunctionBuilder.fromConstant(
          "anyFalse", BOOLEAN_REDUCER.uncountedOf(Core.FALSE, Core.FALSE));

  /**
   * <pre>
   * method nextState(BooleanReducer br, Absent, Boolean value) =
   *     (value == br_.exitOn) ? loopExit(not br_.initial) : Absent
   * </pre>
   *
   * <p>Note that a BooleanReducer's state is always Absent (the initial state, since we don't
   * override {@code emptyState}) or a LoopExit. Since {@code nextState} is never called with a
   * LoopExit, it can only be called with Absent as the current state.
   */
  @Core.Method("nextState(BooleanReducer, Absent, Boolean)")
  static Value nextStateBooleanReducer(TState tstate, Value reducer, Value state, Value value) {
    Value exitOn = reducer.peekElement(1);
    return Condition.equal(exitOn, value)
        .choose(
            () -> {
              Value initial = reducer.peekElement(0);
              return tstate.compound(Core.LOOP_EXIT, Core.not(initial));
            },
            () -> Core.ABSENT);
  }

  /**
   * <pre>
   * method finalState(BooleanReducer br, state) {
   *   assert state is Absent
   *   return br_.initial
   * }
   * </pre>
   */
  @Core.Method("finalState(BooleanReducer, _)")
  static Value finalStateBooleanReducer(Value reducer, Value state) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(state.is(Core.ABSENT));
    return reducer.element(0);
  }

  /**
   * {@code private compound Top is Reducer}
   *
   * <p>Elements are {@code preferFirst} (a Lambda) and {@code swap} (a Boolean).
   */
  @Core.Private static final BaseType.Named TOP = Core.newBaseType("Top", 2, REDUCER);

  private static final Value MIN_REDUCER =
      TOP.uncountedOf(Core.lessThan.asLambdaExpr(), Core.FALSE);
  private static final Value MAX_REDUCER = TOP.uncountedOf(Core.lessThan.asLambdaExpr(), Core.TRUE);

  /** {@code function min() = Top_([x, y] -> x < y, False)} */
  @Core.Public
  static final VmFunctionBuilder min0 = VmFunctionBuilder.fromConstant("min", MIN_REDUCER);

  /** {@code function max() = Top_([x, y] -> x < y, True)} */
  @Core.Public
  static final VmFunctionBuilder max0 = VmFunctionBuilder.fromConstant("max", MAX_REDUCER);

  /** {@code function top(preferFirst)} */
  @Core.Public static final VmFunctionBuilder top = VmFunctionBuilder.create("top", 1);

  /** {@code method top(Lambda preferFirst) = Top_(preferFirst, False)} */
  @Core.Method("top(Lambda)")
  static Value top(TState tstate, @RC.In Value preferFirst) {
    return tstate.compound(TOP, preferFirst, Core.FALSE);
  }

  /**
   * <pre>
   * method nextState(Top reducer, state, value) {
   *   if state is Absent or reducer_.preferFirst @ (r_.swap ? [state, value] : [value, state]) {
   *     return value
   *   }
   *   return state
   * }
   * </pre>
   */
  static class NextStateTop extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("nextState(Top, _, _)")
    static void begin(TState tstate, Value reducer, @RC.In Value state, @RC.In Value value) {
      state
          .is(Core.ABSENT)
          .test(
              () -> tstate.setResult(value),
              () -> {
                Value pair =
                    reducer
                        .peekElement(1)
                        .is(Core.FALSE)
                        .choose(
                            () -> tstate.arrayValue(addRef(value), addRef(state)),
                            () -> tstate.arrayValue(addRef(state), addRef(value)));
                tstate.startCall(at, reducer.element(0), pair).saving(state, value);
              });
    }

    @Continuation
    static Value afterAt(
        TState tstate, Value preferValue, @Saved @RC.In Value state, @RC.In Value value)
        throws BuiltinException {
      return Condition.fromBoolean(preferValue)
          .choose(
              () -> {
                tstate.dropValue(state);
                return value;
              },
              () -> {
                tstate.dropValue(value);
                return state;
              });
    }
  }

  /**
   * <pre>
   * method combineStates(Top reducer, state1, state2) {
   *   return state2 is Absent ? state1 : nextState(reducer, state1, state2)
   * }
   * </pre>
   */
  @Core.Method("combineStates(Top, _, _)")
  static void combineStatesTop(
      TState tstate,
      @RC.In Value reducer,
      @RC.In Value state1,
      @RC.In Value state2,
      @Fn("nextState:3") Caller nextState) {
    state2
        .is(Core.ABSENT)
        .test(
            () -> {
              tstate.dropValue(reducer);
              tstate.setResult(state1);
            },
            () -> tstate.startCall(nextState, reducer, state1, state2));
  }

  /** {@code method min(collection) default = collection | min} */
  @Core.Method("min(Collection) default")
  static void minCollection(TState tstate, @RC.In Value collection, @Fn("pipe:2") Caller pipe) {
    tstate.startCall(pipe, collection, MIN_REDUCER);
  }

  /** {@code method max(collection) default = collection | max} */
  @Core.Method("max(Collection) default")
  static void maxCollection(TState tstate, @RC.In Value collection, @Fn("pipe:2") Caller pipe) {
    tstate.startCall(pipe, collection, MAX_REDUCER);
  }

  /**
   * {@code private compound ElementLessThan is Lambda}
   *
   * <p>Element is {@code key}.
   */
  @Core.Private
  static final BaseType.Named ELEMENT_LESS_THAN =
      Core.newBaseType("ElementLessThan", 1, Core.LAMBDA);

  /** {@code method at(ElementLessThan elt, [x, y]) = (x @ elt_) < (y @ elt_)} */
  static class AtElementLessThan extends BuiltinMethod {
    static final Caller atX = new Caller("at:2", "afterAtX");
    static final Caller atY = new Caller("at:2", "afterAtY");

    @Core.Method("at(ElementLessThan, Array)")
    static void begin(TState tstate, @RC.In Value elt, Value pair) throws BuiltinException {
      Err.NOT_PAIR.unless(pair.isArrayOfLength(2));
      tstate.startCall(atX, pair.element(0), elt.element(0)).saving(elt, pair.element(1));
    }

    @Continuation
    static void afterAtX(TState tstate, @RC.In Value xElement, @Saved Value elt, @RC.In Value y) {
      tstate.startCall(atY, y, elt.element(0)).saving(xElement);
    }

    @Continuation(order = 2)
    static void afterAtY(
        TState tstate,
        @RC.In Value yElement,
        @Saved @RC.In Value xElement,
        @Fn("lessThan:2") Caller lessThan) {
      tstate.startCall(lessThan, xElement, yElement);
    }
  }

  /** {@code function minAt(key)} */
  @Core.Public static final VmFunctionBuilder minAt = VmFunctionBuilder.create("minAt", 1);

  /** {@code method minAt(key) = Top_(ElementLessThan_(key), False)} */
  @Core.Method("minAt(_)")
  static Value minAt(TState tstate, @RC.In Value key) {
    return tstate.compound(TOP, tstate.compound(ELEMENT_LESS_THAN, key), Core.FALSE);
  }

  /** {@code function maxAt(key)} */
  @Core.Public static final VmFunctionBuilder maxAt = VmFunctionBuilder.create("maxAt", 1);

  /** {@code method maxAt(key) = Top_(ElementLessThan_(key), True)} */
  @Core.Method("maxAt(_)")
  static Value maxAt(TState tstate, @RC.In Value key) {
    return tstate.compound(TOP, tstate.compound(ELEMENT_LESS_THAN, key), Core.TRUE);
  }

  /** {@code private singleton SaveUnordered is Reducer} */
  @Core.Private
  public static final Singleton SAVE_UNORDERED = Core.newSingleton("SaveUnordered", REDUCER);

  /** {@code function saveUnordered() = SaveUnordered} */
  @Core.Public
  static final VmFunctionBuilder saveUnordered =
      VmFunctionBuilder.fromConstant("saveUnordered", SAVE_UNORDERED);

  /**
   * <pre>
   * method emptyState(SaveUnordered) = []
   * </pre>
   */
  @Core.Method("emptyState(SaveUnordered)")
  static Value emptyStateSaveUnordered(Value reducer) {
    return Core.EMPTY_ARRAY;
  }

  /**
   * <pre>
   * method nextState(SaveUnordered, state, value) = state &amp; [value]
   * </pre>
   */
  @Core.Method("nextState(SaveUnordered, Array, _)")
  static Value nextStateSaveUnordered(
      TState tstate, ResultsInfo results, Value reducer, @RC.In Value state, @RC.In Value value)
      throws BuiltinException {
    return ValueUtil.appendElement(tstate, state, value, results.result(TProperty.ARRAY_LAYOUT));
  }

  /**
   * <pre>
   * method combineStates(SaveUnordered, state1, state2) = state1 &amp; state2
   * </pre>
   */
  static void combineStatesSaveUnordered(
      TState tstate,
      ResultsInfo results,
      Value reducer,
      @RC.In Value state1,
      @RC.In Value state2,
      @Fn("copyElements:5") Caller copyElements)
      throws BuiltinException {
    Value size2 = ValueUtil.numElements(tstate, state2);
    Condition.numericEq(size2, NumValue.ZERO)
        .testExcept(
            () -> {
              tstate.dropValue(state2);
              tstate.dropValue(size2);
              tstate.setResult(state1);
            },
            () -> {
              tstate.dropOnThrow(size2);
              Value size1 = tstate.getArraySizeAndReserveForChange(state1, size2, null);
              Condition.numericEq(size1, NumValue.ZERO)
                  .testExcept(
                      () -> {
                        tstate.dropValue(state1);
                        tstate.dropValue(size1);
                        tstate.dropValue(size2);
                        tstate.setResult(state2);
                      },
                      () -> {
                        FrameLayout resultLayout = results.result(TProperty.ARRAY_LAYOUT);
                        Value expanded =
                            ValueUtil.removeRange(
                                tstate, state1, size1, NumValue.ZERO, size2, resultLayout);
                        tstate.startCall(
                            copyElements, expanded, size1, state2, NumValue.ZERO, size2);
                      });
            });
  }

  private ReducerCore() {}
}
