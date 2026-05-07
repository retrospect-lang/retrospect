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

import org.retrolang.impl.BaseType;
import org.retrolang.impl.BuiltinMethod;
import org.retrolang.impl.BuiltinMethod.Caller;
import org.retrolang.impl.BuiltinMethod.Fn;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.FrameLayout;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.ResultsInfo;
import org.retrolang.impl.Singleton;
import org.retrolang.impl.TProperty;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.ValueUtil;
import org.retrolang.impl.VmFunctionBuilder;

/** Implementation of save() and friends. */
public final class SaveCore {
  /**
   * {@code private compound SaveWithDefault is Collector}
   *
   * <p>Element is default value.
   */
  @Core.Private
  static final BaseType.Named SAVE_WITH_DEFAULT =
      Core.newBaseType("SaveWithDefault", 1, LoopCore.COLLECTOR);

  /** {@code private singleton SaveElements is Loop} */
  @Core.Private
  public static final Singleton SAVE_ELEMENTS = Core.newSingleton("SaveElements", LoopCore.LOOP);

  /**
   * {@code private compound SaveWithOffset is Loop}
   *
   * <p>Element is offset, an integer. Inputs are expected to be [[index], value] pairs, and the
   * state is an array; each input value is saved in state[index+offset]
   */
  @Core.Private
  public static final BaseType.Named SAVE_WITH_OFFSET =
      Core.newBaseType("SaveWithOffset", 1, LoopCore.LOOP);

  /**
   * {@code private compound SaverLoop is Loop}
   *
   * <p>Element is sequential loop.
   */
  @Core.Private
  static final BaseType.Named SAVER_LOOP = Core.newBaseType("SaverLoop", 1, LoopCore.LOOP);

  /**
   * {@code private compound SaverUpdates}
   *
   * <p>Element is an array of updates to be applied to the primary state.
   */
  @Core.Private static final BaseType.Named SAVER_UPDATES = Core.newBaseType("SaverUpdates", 1);

  /** {@code function save() = SaveWithDefault_(Absent)} */
  @Core.Public
  static final VmFunctionBuilder save =
      VmFunctionBuilder.fromConstant("save", SAVE_WITH_DEFAULT.uncountedOf(Core.ABSENT));

  /** {@code function saveWithDefault(defaultElement)} */
  @Core.Public
  static final VmFunctionBuilder saveWithDefault = VmFunctionBuilder.create("saveWithDefault", 1);

  /** {@code function saverSetup(eKind, loop, initialState)} */
  @Core.Public
  static final VmFunctionBuilder saverSetup = VmFunctionBuilder.create("saverSetup", 3);

  /**
   * <pre>
   * method saveWithDefault(x) = SaveWithDefault_(x)
   * </pre>
   */
  @Core.Method("saveWithDefault(_)")
  static Value saveWithDefault(TState tstate, @RC.In Value x) {
    return tstate.compound(SAVE_WITH_DEFAULT, x);
  }

  /**
   * <pre>
   * method collectorSetup(SaveWithDefault swd, collection) {
   *   if swd_ is not Absent {
   *     eKind = EnumerateWithKeys
   *     initialState = new(keys(collection), swd_)
   *   } else {
   *     // If the default value is Absent we don't initialize the elements of the initial
   *     // collection (which isn't expressible in Retrospect); that means we need to set all
   *     // elements, even if their value is Absent.
   *     // (This may allow the VM to use a representation for our result that is unable to
   *     // represent Absent elements.)
   *     eKind = EnumerateAllKeys
   *     initialState = newUninitialized(keys(collection))
   *   }
   *   return saverSetup(eKind, Save, initialState)
   * }
   * </pre>
   */
  static class CollectorSetupSaveWithDefault extends BuiltinMethod {
    static final Caller keys = new Caller("keys:1", "afterKeys");
    static final Caller new2 = new Caller("new:2", "afterNew");

    @Core.Method("collectorSetup(SaveWithDefault, Collection)")
    static void begin(TState tstate, Value swd, @RC.In Value collection) {
      tstate.startCall(keys, collection).saving(swd.element(0));
    }

    @Continuation
    static void afterKeys(
        TState tstate, @RC.In Value keysCollection, @Saved @RC.In Value initialValue) {
      initialValue
          .is(Core.ABSENT)
          .test(
              () ->
                  tstate
                      .startCall(new2, keysCollection, Core.TO_BE_SET)
                      .saving(LoopCore.ENUMERATE_ALL_KEYS),
              () ->
                  tstate
                      .startCall(new2, keysCollection, initialValue)
                      .saving(LoopCore.ENUMERATE_WITH_KEYS));
    }

    @Continuation(order = 2)
    static Value afterNew(
        TState tstate, @RC.In Value newCollection, @Saved @RC.Singleton Value eKind)
        throws BuiltinException {
      return saverSetup(tstate, eKind, SAVE_ELEMENTS, newCollection);
    }
  }

  /**
   * <pre>
   * method saverSetup(EnumerationKind eKind, Loop loop, initialState) {
   *   assert initialState is not SaverUpdates
   *   return { canParallel: True, eKind, initialState, loop: SaverLoop_(loop) }
   * }
   * </pre>
   */
  @Core.Method("saverSetup(EnumerationKind, Loop, _)")
  static Value saverSetup(
      TState tstate, @RC.Singleton Value eKind, @RC.In Value loop, @RC.In Value initialState)
      throws BuiltinException {
    Err.INVALID_ARGUMENT.when(initialState.isa(SAVER_UPDATES));
    return tstate.compound(
        LoopCore.SETUP_KEYS, Core.TRUE, eKind, initialState, tstate.compound(SAVER_LOOP, loop));
  }

  /**
   * <pre>
   * method nextState(SaveElements, state, [key, value]) = replaceElement(state, key, value)
   * </pre>
   */
  @Core.Method("nextState(SaveElements, _, _)")
  static void nextStateSaveElements(
      TState tstate,
      @RC.Singleton Value saveElements,
      @RC.In Value state,
      Value keyValue,
      @Fn("replaceElement:3") Caller replaceElement)
      throws BuiltinException {
    Err.NOT_PAIR.unless(keyValue.isArrayOfLength(2));
    Value key = keyValue.element(0);
    Value value = keyValue.element(1);
    tstate.startCall(replaceElement, state, key, value);
  }

  /**
   * <pre>
   * method nextState(SaveWithOffset swo, Array state, [[index], value]) =
   *     replaceElement(state, [index + swo_], value)
   * </pre>
   */
  @Core.Method("nextState(SaveWithOffset, Array, _)")
  static Value nextStateSaveWithOffset(TState tstate, Value swo, @RC.In Value state, Value keyValue)
      throws BuiltinException {
    Err.NOT_PAIR.unless(keyValue.isArrayOfLength(2));
    Value key = keyValue.peekElement(0);
    Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(1));
    Value index = key.peekElement(0).verifyInt(Err.INVALID_ARGUMENT);
    if (!(index instanceof RValue || swo instanceof RValue || state instanceof RValue)) {
      int i = NumValue.asInt(index) + swo.elementAsInt(0) - 1;
      Err.INVALID_ARGUMENT.unless(ArrayCore.isValidIndex(state, i));
      Value value = keyValue.element(1);
      return state.replaceElement(tstate, i, value);
    }
    Value i = ValueUtil.oneBasedOffset(tstate, index, swo.element(0));
    ValueUtil.checkIndex(tstate, state, i);
    return ValueUtil.replaceElement(tstate, state, i, 0, keyValue.element(1));
  }

  /**
   * <pre>
   * method nextState(SaverLoop loop, state, element) {
   *   if state is SaverUpdates {
   *     // We don't have the primary state, so just save this element for now.
   *     state_ &amp;= [element]
   *     return state
   *   }
   *   // We have the primary state, so we can update it.
   *   return nextState(loop_, state, element)
   * }
   * </pre>
   */
  @Core.Method("nextState(SaverLoop, _, _)")
  static void nextStateSaverLoop(
      TState tstate,
      ResultsInfo results,
      Value loop,
      @RC.In Value state,
      @RC.In Value element,
      @Fn("nextState:3") Caller nextState)
      throws BuiltinException {
    state
        .isa(SAVER_UPDATES)
        .testExcept(
            () -> {
              // We have to do the memory check before we change any refcounts
              Value updates = state.peekElement(0);
              Value prevSize = tstate.getArraySizeAndReserveForChange(updates, NumValue.ONE, state);
              // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
              updates = updates.makeStorable(tstate);
              Value updatedState = state.replaceElement(tstate, 0, Core.TO_BE_SET);
              // Add one TO_BE_SET element at the end of updates.
              FrameLayout resultLayout = results.result(TProperty.ARRAY_LAYOUT);
              updates =
                  ValueUtil.removeRange(
                      tstate, updates, prevSize, NumValue.ZERO, NumValue.ONE, resultLayout);
              updates = ValueUtil.replaceElement(tstate, updates, prevSize, 0, element);
              tstate.dropValue(prevSize);
              tstate.setResult(updatedState.replaceElement(tstate, 0, updates));
            },
            () -> tstate.startCall(nextState, loop.element(0), state, element));
  }

  private SaveCore() {}
}
