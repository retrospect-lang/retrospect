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

import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeValue;
import org.retrolang.code.Op;
import org.retrolang.code.TestBlock;
import org.retrolang.impl.BaseType;
import org.retrolang.impl.CodeGen;
import org.retrolang.impl.Condition;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.ResultsInfo;
import org.retrolang.impl.TProperty;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.ValueUtil;
import org.retrolang.impl.VmFunctionBuilder;

/** Core methods for Range. */
public class RangeCore {
  @Core.Public static final VmFunctionBuilder range = VmFunctionBuilder.create("range", 2);

  @Core.Public
  static final VmFunctionBuilder rangeWithSize = VmFunctionBuilder.create("rangeWithSize", 2);

  /**
   * {@code private compound ReversedRange is Matrix}
   *
   * <p>Elements are {@code min}, {@code max}.
   */
  @Core.Private
  static final BaseType.Named REVERSED_RANGE = Core.newBaseType("ReversedRange", 2, Core.MATRIX);

  /**
   * {@code private compound SimpleRangeIterator is Iterator}
   *
   * <p>Elements are {@code previous}, {@code limit}. A simplified version of RangeIterator.
   */
  @Core.Private
  static final BaseType.Named SIMPLE_RANGE_ITERATOR =
      Core.newBaseType("SimpleRangeIterator", 2, LoopCore.ITERATOR);

  /**
   * {@code private compound RangeIterator is Iterator}
   *
   * <p>Elements are {@code next}, {@code max}, {@code keyOffsetOrNone}.
   */
  @Core.Private
  static final BaseType.Named RANGE_ITERATOR =
      Core.newBaseType("RangeIterator", 3, LoopCore.ITERATOR);

  /**
   * {@code private compound ReversedRangeIterator is Iterator}
   *
   * <p>Elements are {@code next}, {@code min}, {@code keyOffsetOrNone}.
   */
  @Core.Private
  static final BaseType.Named REVERSED_RANGE_ITERATOR =
      Core.newBaseType("ReversedRangeIterator", 3, LoopCore.ITERATOR);

  /**
   * <pre>
   * method range(min, max) {
   *   assert (min is None or min is Integer) and (max is None or max is Integer)
   *   if min is not None and max is not None {
   *     assert min &lt;= max + 1
   *   }
   *   return Range_({min, max}))
   * }
   * </pre>
   */
  @Core.Method("range(_, _)")
  static Value range(TState tstate, Value min, Value max) throws BuiltinException {
    Value checkedMin = checkBound(tstate, min);
    tstate.dropOnThrow(checkedMin);
    Value checkedMax = checkBound(tstate, max);
    tstate.dropOnThrow(checkedMax);
    Err.INVALID_ARGUMENT.unless(validBounds(tstate, checkedMin, checkedMax));
    return tstate.compound(Core.RANGE, checkedMin, checkedMax);
  }

  /** If {@code bound} is a valid bound, returns it; otherwise throws INVALID_ARGUMENT. */
  @RC.Out
  private static Value checkBound(TState tstate, Value bound) throws BuiltinException {
    return bound
        .is(Core.NONE)
        .chooseExcept(
            () -> Core.NONE, () -> bound.verifyInt(Err.INVALID_ARGUMENT).makeStorable(tstate));
  }

  /**
   * Returns TRUE if {@code min..max} is a valid range, i.e. if {@code min} is None, {@code max} is
   * None, or {@code min <= max + 1}.
   */
  private static Condition validBounds(TState tstate, Value min, Value max) {
    if (min instanceof RValue || max instanceof RValue) {
      // A slightly less readable version of the non-RValue logic below.
      return min.is(Core.NONE)
          .or(max.is(Core.NONE))
          .or(() -> intBoundsInvalid(tstate, min, max).not());
    } else if (min == Core.NONE || max == Core.NONE) {
      return Condition.TRUE;
    } else {
      // Use long arithmetic to correctly handle the case where max == Integer.MAX_VALUE
      return Condition.of(NumValue.asInt(min) <= 1L + NumValue.asInt(max));
    }
  }

  /**
   * {@code min} and {@code max} are ints. Returns true if {@code max < min - 1}, but has to be
   * careful to avoid integer overflow.
   */
  private static Condition intBoundsInvalid(TState tstate, Value min, Value max) {
    CodeGen codeGen = tstate.codeGen();
    CodeValue cvMin = codeGen.asCodeValue(min);
    CodeValue cvMax = codeGen.asCodeValue(max);
    // If either one is constant this can be simpler
    if (cvMin instanceof CodeValue.Const) {
      int iMin = cvMin.iValue();
      if (iMin <= Integer.MIN_VALUE + 1) {
        // Any value of max is valid.
        return Condition.FALSE;
      } else {
        return Condition.fromTest(
            () ->
                new TestBlock.IsLessThan(
                    CodeBuilder.OpCodeType.INT, cvMax, CodeValue.of(iMin - 1)));
      }
    } else if (cvMax instanceof CodeValue.Const) {
      int iMax = cvMax.iValue();
      if (iMax >= Integer.MAX_VALUE - 1) {
        // Any value of min is valid.
        return Condition.FALSE;
      } else {
        return Condition.fromTest(
            () ->
                new TestBlock.IsLessThan(
                    CodeBuilder.OpCodeType.INT, CodeValue.of(iMax + 1), cvMin));
      }
    }
    // Neither is constant, so we need to do the more careful test
    CodeValue maxPlusOne = Op.ADD_LONGS.result(cvMax, CodeValue.ONE);
    return Condition.fromTest(
        () -> new TestBlock.IsLessThan(CodeBuilder.OpCodeType.LONG, maxPlusOne, cvMin));
  }

  /**
   * <pre>
   * method rangeWithSize(Number min, Number size) {
   *   assert min is Integer and size is Integer and size &gt= 0
   *   max = min + size - 1
   *   assert max is Integer
   *   return Range_({min, max})
   * }
   * </pre>
   */
  @Core.Method("rangeWithSize(_, _)")
  static Value rangeWithSize(TState tstate, Value min, Value size) throws BuiltinException {
    Value checkedMin = min.verifyInt(Err.INVALID_ARGUMENT);
    Value checkedSize = size.verifyInt(Err.INVALID_ARGUMENT);
    Value max = computeMax(tstate, checkedMin, checkedSize);
    return tstate.compound(Core.RANGE, checkedMin.makeStorable(tstate), max);
  }

  @RC.Out
  private static Value computeMax(TState tstate, Value min, Value size) throws BuiltinException {
    if (!(min instanceof RValue || size instanceof RValue)) {
      int iMin = NumValue.asInt(min);
      int iSize = NumValue.asInt(size);
      int iMax = iMin + iSize - 1;
      Err.INVALID_ARGUMENT.unless(iMin > iMax ? iSize == 0 : iSize > 0);
      return NumValue.of(iMax, tstate);
    }
    Value max = ValueUtil.oneBasedOffset(tstate, min, size);
    Err.INVALID_ARGUMENT.unless(
        Condition.numericLessThan(max, min)
            .ternary(
                Condition.numericEq(size, NumValue.ZERO),
                Condition.numericLessThan(NumValue.ZERO, size)));
    return max;
  }

  /**
   * <pre>
   * method reverse(Range r) = ReversedRange_(r_)
   * </pre>
   */
  @Core.Method("reverse(Range)")
  static Value reverseRange(TState tstate, Value range) {
    return tstate.compound(REVERSED_RANGE, range.element(0), range.element(1));
  }

  /**
   * <pre>
   * method reverse(ReversedRange r) = Range_(r_)
   * </pre>
   */
  @Core.Method("reverse(ReversedRange)")
  static Value reverseReversedRange(TState tstate, Value range) {
    return tstate.compound(Core.RANGE, range.element(0), range.element(1));
  }

  /**
   * <pre>
   * method min(Range r) = r_.min
   * method min(ReversedRange r) = r_.min
   * </pre>
   */
  @Core.Method("min(Range|ReversedRange)")
  static Value minRange(Value range) {
    return range.element(0);
  }

  /**
   * <pre>
   * method max(Range r) = r_.max
   * method max(ReversedRange r) = r_.max
   * </pre>
   */
  @Core.Method("max(Range|ReversedRange)")
  static Value maxRange(Value range) {
    return range.element(1);
  }

  /**
   * <pre>
   * method size(Range r) = r_.max - r_.min + 1
   * method size(ReversedRange r) = r_.max - r_.min + 1
   * </pre>
   */
  @Core.Method("size(Range|ReversedRange)")
  static Value sizeRange(TState tstate, ResultsInfo results, Value range) throws BuiltinException {
    return size(tstate, results, range);
  }

  /**
   * <pre>
   * method sizes(Range r) = [size(r)]
   * method sizes(ReversedRange r) = [size(r)]
   * </pre>
   */
  @Core.Method("sizes(Range|ReversedRange)")
  static Value sizesRange(TState tstate, ResultsInfo results, Value range) throws BuiltinException {
    Value size = size(tstate, null, range);
    return tstate.arrayValue(size);
  }

  /**
   * Returns the size of the given Range or ReversedRange. If {@code results} is non-null, may
   * return a double; if {@code results} is null will throw INVALID_ARGUMENT if the result does not
   * fit in an int.
   */
  @RC.Out
  private static Value size(TState tstate, ResultsInfo results, Value range)
      throws BuiltinException {
    Value min = range.peekElement(0);
    Value max = range.peekElement(1);
    Err.RANGE_IS_UNBOUNDED.when(min.is(Core.NONE).or(max.is(Core.NONE)));
    if (NumValue.equals(min, 1)) {
      return max.makeStorable(tstate);
    }
    if (!(min instanceof RValue || max instanceof RValue)) {
      int intMin = NumValue.asInt(min);
      int intMax = NumValue.asInt(max);
      int size;
      try {
        size = Math.addExact(Math.subtractExact(intMax, intMin), 1);
      } catch (ArithmeticException e) {
        Err.INVALID_ARGUMENT.when(results == null);
        return NumValue.of((intMax - (double) intMin) + 1, tstate);
      }
      return NumValue.of(size, tstate);
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue cvMin = codeGen.asCodeValue(min);
    CodeValue cvMax = codeGen.asCodeValue(max);
    CodeValue size;
    if (results != null && results.result(TProperty.COERCES_TO_FLOAT)) {
      size = Op.SUBTRACT_DOUBLES.result(cvMax, Op.SUBTRACT_DOUBLES.result(cvMin, CodeValue.ONE));
      size = codeGen.materialize(size, double.class);
    } else if (cvMin instanceof CodeValue.Const && cvMin.iValue() >= 1) {
      // Can't overflow
      size = Op.SUBTRACT_INTS.result(cvMax, CodeValue.of(cvMin.iValue() - 1));
      size = codeGen.materialize(size, int.class);
    } else {
      size = Op.ADD_INTS_EXACT.result(Op.SUBTRACT_INTS_EXACT.result(cvMax, cvMin), CodeValue.ONE);
      size = codeGen.materializeCatchingArithmeticException(size);
    }
    return codeGen.toValue(size);
  }

  /**
   * <pre>
   * method element(Range r, [index]) = r_.min + index - 1
   * </pre>
   */
  @Core.Method("element(Range, Array)")
  static Value elementRange(TState tstate, ResultsInfo results, Value range, Value key)
      throws BuiltinException {
    return elementHelper(tstate, results, range.peekElement(0), range.peekElement(1), key, true);
  }

  /**
   * <pre>
   * method element(ReversedRange r, [index]) = r_.max - (index - 1)
   * </pre>
   */
  @Core.Method("element(ReversedRange, Array)")
  static Value elementReversedRange(TState tstate, ResultsInfo results, Value range, Value key)
      throws BuiltinException {
    return elementHelper(tstate, results, range.peekElement(1), range.peekElement(0), key, false);
  }

  private static Value elementHelper(
      TState tstate, ResultsInfo results, Value first, Value last, Value key, boolean forward)
      throws BuiltinException {
    Err.INVALID_ARGUMENT.when(first.is(Core.NONE));
    Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(1));
    if (!(first instanceof RValue || key instanceof RValue || last instanceof RValue)) {
      int index = NumValue.asIntOrMinusOne(key.peekElement(0));
      Err.INVALID_ARGUMENT.unless(index > 0);
      int iFirst = NumValue.asInt(first);
      int i = index - 1;
      try {
        i = forward ? Math.addExact(iFirst, i) : Math.subtractExact(iFirst, i);
      } catch (ArithmeticException e) {
        Err.INVALID_ARGUMENT.unless(last.is(Core.NONE));
        double d = forward ? iFirst + (long) i : iFirst - (long) i;
        return NumValue.of(d, tstate);
      }
      if (last != Core.NONE) {
        int iLast = NumValue.asInt(last);
        Err.INVALID_ARGUMENT.unless(forward ? i <= iLast : i >= iLast);
      }
      return NumValue.of(i, tstate);
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue cvIndex = codeGen.verifyInt(key.peekElement(0));
    CodeValue cvFirst = codeGen.asCodeValue(first);
    // TODO: check in range!
    CodeValue result;
    if (results.result(TProperty.COERCES_TO_FLOAT)) {
      result =
          (forward ? Op.ADD_LONGS : Op.SUBTRACT_LONGS)
              .result(cvFirst, Op.SUBTRACT_LONGS.result(cvIndex, CodeValue.ONE));
      result = codeGen.materialize(result, double.class);
    } else {
      result =
          (forward ? Op.ADD_INTS_EXACT : Op.SUBTRACT_INTS_EXACT)
              .result(cvFirst, Op.SUBTRACT_INTS_EXACT.result(cvIndex, CodeValue.ONE));
      result = codeGen.materializeCatchingArithmeticException(result);
    }
    return codeGen.toValue(result);
  }

  /**
   * <pre>
   * method next(SimpleRangeIterator it=) {
   *   { previous, limit } = it_
   *   next = previous + 1
   *   if next &gt;= limit {
   *     return Absent
   *   }
   *   it_.previous = next
   *   return next
   * }
   * </pre>
   */
  @Core.Method("next(SimpleRangeIterator)")
  static void nextSimpleRangeIterator(TState tstate, @RC.In Value it) {
    Value previous = it.peekElement(0);
    Value limit = it.peekElement(1);
    // previous is always less than limit, so this add cannot overflow
    Value next = ValueUtil.addInts(tstate, previous, NumValue.ONE);
    Condition.numericLessThan(next, limit)
        .test(
            () -> {
              Value updated = it.replaceElement(tstate, 0, addRef(next));
              tstate.setResults(next, updated);
            },
            () -> {
              tstate.dropValue(next);
              tstate.setResults(Core.ABSENT, it);
            });
  }

  /**
   * <pre>
   * method iterator(Range r, EnumerationKind eKind, Loop loop=, initialState=) {
   *   { min, max } = r_
   *   assert min is not None
   *   keyOffset = (eKind is EnumerateValues) ? None : 1 - min
   *   return RangeIterator_({next: min, max, keyOffset})
   * }
   * </pre>
   */
  @Core.Method("iterator(Range, EnumerationKind, Loop, _)")
  static void iteratorRange(
      TState tstate,
      Value range,
      @RC.Singleton Value eKind,
      @RC.In Value loop,
      @RC.In Value initialState)
      throws BuiltinException {
    Value min = range.element(0);
    Err.RANGE_HAS_NO_LOWER_BOUND.when(min.is(Core.NONE));
    Value max = range.element(1);
    tstate.setResults(iteratorHelper(tstate, min, max, eKind, false), loop, initialState);
  }

  /**
   * <pre>
   * method iterator(ReversedRange r, EnumerationKind eKind, Loop loop=, initialState=) {
   *   { min, max } = r_
   *   assert max is not None
   *   keyOffset = (eKind is EnumerateValues) ? None : max + 1
   *   return ReversedRangeIterator_({next: max, min, keyOffset})
   * }
   * </pre>
   */
  @Core.Method("iterator(ReversedRange, EnumerationKind, Loop, _)")
  static void iteratorReversedRange(
      TState tstate,
      Value range,
      @RC.Singleton Value eKind,
      @RC.In Value loop,
      @RC.In Value initialState)
      throws BuiltinException {
    Value max = range.element(1);
    Err.RANGE_HAS_NO_UPPER_BOUND.when(max.is(Core.NONE));
    Value min = range.element(0);
    tstate.setResults(iteratorHelper(tstate, max, min, eKind, true), loop, initialState);
  }

  private static Value iteratorHelper(
      TState tstate, Value first, Value last, Value eKind, boolean reversed) {
    BaseType baseType = reversed ? REVERSED_RANGE_ITERATOR : RANGE_ITERATOR;
    return eKind
        .is(LoopCore.ENUMERATE_VALUES)
        .choose(
            () -> tstate.compound(baseType, first, last, Core.NONE),
            () -> {
              if (!(first instanceof RValue)) {
                int i = NumValue.asInt(first);
                // The next step could overflow but we will still get the right answer if it does
                i = reversed ? 1 + i : 1 - i;
                return tstate.compound(baseType, first, last, NumValue.of(i, tstate));
              }
              CodeGen codeGen = tstate.codeGen();
              CodeValue keyOffset =
                  (reversed ? Op.ADD_INTS : Op.SUBTRACT_INTS)
                      .result(CodeValue.ONE, codeGen.asCodeValue(first));
              return tstate.compound(baseType, first, last, codeGen.intToValue(keyOffset));
            });
  }

  /**
   * <pre>
   * method next(RangeIterator it=) {
   *   { next, max, keyOffset } = it_
   *   if max is not None and next &gt; max {
   *     return Absent
   *   }
   *   it_.next = next + 1
   *   assert it_.next > next
   *   return keyOffset is None ? next : [ [next + keyOffset], next ]
   * }
   * </pre>
   */
  @Core.Method("next(RangeIterator)")
  static void nextRangeIterator(TState tstate, ResultsInfo results, @RC.In Value it)
      throws BuiltinException {
    nextIterator(tstate, results, it, false);
  }

  /**
   * <pre>
   * method next(ReversedRangeIterator it=) {
   *   { next, min, keyOffset } = it_
   *   if min is not None and next &lt; min {
   *     return Absent
   *   }
   *   it_.next = next - 1
   *   assert it_.next < next
   *   return keyOffset is None ? next : [ [keyOffset - next], next ]
   * }
   * </pre>
   */
  @Core.Method("next(ReversedRangeIterator)")
  static void nextReversedRangeIterator(TState tstate, ResultsInfo results, @RC.In Value it)
      throws BuiltinException {
    nextIterator(tstate, results, it, true);
  }

  private static void nextIterator(
      TState tstate, ResultsInfo results, @RC.In Value it, boolean reversed)
      throws BuiltinException {
    Value last = it.peekElement(1);
    if (!(it instanceof RValue)) {
      int iNext = it.elementAsInt(0);
      if (last != Core.NONE) {
        int iLast = NumValue.asInt(last);
        if (reversed ? (iNext < iLast) : (iNext > iLast)) {
          tstate.setResults(Core.ABSENT, it);
          return;
        }
      }
      int nextNext = iNext + (reversed ? -1 : 1);
      // Unbounded iterators aren't allowed to wrap
      Err.INVALID_ARGUMENT.unless(reversed ? nextNext < iNext : nextNext > iNext);
      Value result = it.element(0);
      Value keyOffset = it.peekElement(2);
      if (keyOffset != Core.NONE) {
        int intKey = NumValue.asInt(keyOffset);
        intKey += reversed ? -iNext : iNext;
        if (intKey < 0) {
          // If you enumerate e.g. -5.. or reverse(..5) with keys the key will overflow before the
          // value does.
          tstate.dropValue(result);
          throw Err.INVALID_ARGUMENT.asException();
        }
        result = tstate.arrayValue(tstate.arrayValue(NumValue.of(intKey, tstate)), result);
      }
      Value updatedIt = it.replaceElement(tstate, 0, NumValue.of(nextNext, tstate));
      tstate.setResults(result, updatedIt);
      return;
    }
    Value next = it.peekElement(0);
    last.is(Core.NONE)
        .not()
        .and(
            () ->
                reversed
                    ? Condition.numericLessThan(next, last)
                    : Condition.numericLessThan(last, next))
        .testExcept(
            () -> tstate.setResults(Core.ABSENT, it),
            () -> {
              CodeGen codeGen = tstate.codeGen();
              CodeValue cvNext = codeGen.asCodeValue(next);
              CodeValue nextNext =
                  (reversed ? Op.SUBTRACT_INTS_EXACT : Op.ADD_INTS_EXACT)
                      .result(cvNext, CodeValue.ONE);
              nextNext = codeGen.materializeCatchingArithmeticException(nextNext);
              Value keyOffset = it.peekElement(2);
              Value updatedIt = it.replaceElement(tstate, 0, codeGen.toValue(nextNext));
              keyOffset
                  .is(Core.NONE)
                  .testExcept(
                      () -> tstate.setResults(next, updatedIt),
                      () -> {
                        CodeValue key =
                            (reversed ? Op.SUBTRACT_INTS_EXACT : Op.ADD_INTS_EXACT)
                                .result(codeGen.asCodeValue(keyOffset), cvNext);
                        key = codeGen.materializeCatchingArithmeticException(key);
                        Value result =
                            tstate.arrayValue(tstate.arrayValue(codeGen.toValue(key)), next);
                        tstate.setResults(result, updatedIt);
                      });
            });
  }

  private RangeCore() {}
}
