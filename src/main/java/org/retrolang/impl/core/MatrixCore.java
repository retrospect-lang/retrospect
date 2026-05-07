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

import java.util.Arrays;
import java.util.stream.IntStream;
import org.retrolang.code.CodeValue;
import org.retrolang.code.Register;
import org.retrolang.impl.*;
import org.retrolang.impl.BuiltinMethod.Caller;
import org.retrolang.impl.BuiltinMethod.Fn;
import org.retrolang.impl.Err.BuiltinException;

/** Core methods for Matrix. */
public final class MatrixCore {
  /**
   * {@code function matrix(sizes)}
   *
   * <p>Returns a Matrix with the given sizes, where each element's value is the same as its key.
   */
  @Core.Public static final VmFunctionBuilder matrix1 = VmFunctionBuilder.create("matrix", 1);

  /**
   * {@code function matrix(sizes, elements)}
   *
   * <p>Returns a Matrix with the given sizes and elements; {@code elements} must be a Matrix with
   * the appropriate number of elements.
   */
  @Core.Public static final VmFunctionBuilder matrix2 = VmFunctionBuilder.create("matrix", 2);

  /** {@code function reverse(vector)} */
  @Core.Public
  static final VmFunctionBuilder reverse1 = VmFunctionBuilder.create("reverse", 1).isOpen();

  /**
   * {@code open function element(matrix, key)}
   *
   * <p>Returns the specified element of a matrix.
   */
  @Core.Public
  static final VmFunctionBuilder element = VmFunctionBuilder.create("element", 2).isOpen();

  /**
   * {@code open function subMatrix(matrix, key)}
   *
   * <p>Returns a matrix containing only the specified elements.
   */
  @Core.Public
  static final VmFunctionBuilder subMatrix = VmFunctionBuilder.create("subMatrix", 2).isOpen();

  /** {@code open function newMatrix(sizes)} */
  @Core.Public static final VmFunctionBuilder newMatrix1 = VmFunctionBuilder.create("newMatrix", 1);

  /** {@code open function newMatrix(sizes, initialValue)} */
  @Core.Public static final VmFunctionBuilder newMatrix2 = VmFunctionBuilder.create("newMatrix", 2);

  /** {@code function concat(vector, vector)} */
  @Core.Public
  static final VmFunctionBuilder concat = VmFunctionBuilder.create("concat", 2).isOpen();

  /** {@code procedure concatUpdate(vector=, vector)} */
  @Core.Public
  static final VmFunctionBuilder concatUpdate =
      VmFunctionBuilder.create("concatUpdate", 2).isOpen().hasInoutArg(0).hasNoResult();

  @Core.Private
  static final VmFunctionBuilder joinAndPipe = VmFunctionBuilder.create("joinAndPipe", 3).isOpen();

  /**
   * {@code private compound SubMatrix is Matrix}
   *
   * <p>Elements are {@code matrix}, {@code baseKey}, {@code axes}, {@code sizes}. {@code baseKey},
   * {@code axes} and {@code sizes} are arrays of ints.
   *
   * <p>{@code baseKey} is the key in {@code matrix} of the first element of the submatrix, i.e. the
   * element with key {@code [1, 1, ..., 1]} in the submatrix.
   *
   * <p>{@code axes} contains strictly increasing zero-based indices into keys of {@code matrix},
   * identifying the dimensions that have a corresponding dimension in the submatrix. The size of
   * {@code axes} is less than or equal to the number of dimensions of {@code matrix}.
   *
   * <p>{@code sizes} is the dimensions of the submatrix; it has the same length as {@code axes},
   * and all elements of {@code sizes} are greater than zero (instances of SubMatrix cannot be
   * empty).
   *
   * <p>For example, if {@code m} is a 3x4 matrix, then
   *
   * <ul>
   *   <li>{@code SubMatrix_(m, [3, 1], [1], [4])} is a 4-element vector containing the third row of
   *       {@code m} (it starts at [3, 1], extends only in the second dimension, and contains 4
   *       elements); and
   *   <li>{@code SubMatrix_(m, [1, 1], [0, 1], [2, 4])} is a 2x4 matrix containing the other two
   *       rows of {@code m}).
   * </ul>
   */
  @Core.Private
  static final BaseType.Named SUB_MATRIX = Core.newBaseType("SubMatrix", 4, Core.MATRIX);

  /**
   * {@code private compound BaseMatrix is Matrix}
   *
   * <p>Element is an array of sizes; all elements are integers greater than zero.
   *
   * <p>A BaseMatrix is returned by {@code matrix(sizes)}; its values are the same as its keys.
   */
  @Core.Private
  static final BaseType.Named BASE_MATRIX = Core.newBaseType("BaseMatrix", 1, Core.MATRIX);

  /**
   * {@code private compound BaseIterator is Iterator}
   *
   * <p>Elements are {@code eKind}, {@code prev}, {@code sizes}.
   */
  @Core.Private
  static final BaseType.Named BASE_ITERATOR =
      Core.newBaseType("BaseIterator", 3, LoopCore.ITERATOR);

  /**
   * {@code private compound OffsetKey is Lambda}
   *
   * <p>Element is {@code offset}. Equivalent to {@code [[i], v] -> [[i + offset], v]}.
   */
  @Core.Private
  static final BaseType.Named OFFSET_KEY = Core.newBaseType("OffsetKey", 1, Core.LAMBDA);

  /**
   * {@code private compound Reshaped is Matrix}
   *
   * <p>Elements are {@code sizes}, {@code elements}.
   */
  @Core.Private static final BaseType.Named RESHAPED = Core.newBaseType("Reshaped", 2, Core.MATRIX);

  /**
   * {@code private compound ReshapedArray is Matrix}
   *
   * <p>Elements are {@code sizes}, {@code elements}.
   *
   * <p>A variant of Reshaped used when {@code elements} is an Array.
   */
  @Core.Private
  static final BaseType.Named RESHAPED_ARRAY = Core.newBaseType("ReshapedArray", 2, Core.MATRIX);

  /**
   * {@code private compound KeyConverter is Lambda}
   *
   * <p>Elements are {@code inSizes}, {@code outSizes}.
   */
  @Core.Private
  static final BaseType.Named KEY_CONVERTER = Core.newBaseType("KeyConverter", 2, Core.LAMBDA);

  /**
   * {@code private compound ConcatVector is Matrix}
   *
   * <p>Elements are {@code size}, {@code v1}, {@code v2}.
   */
  @Core.Private
  static final BaseType.Named CONCAT_VECTOR = Core.newBaseType("ConcatVector", 3, Core.MATRIX);

  /**
   * {@code private compound ConcatIterator is Iterator}
   *
   * <p>Elements are {@code it1}, {@code loop1}, {@code it2}, {@code loop2}.
   */
  @Core.Private
  static final BaseType.Named CONCAT_ITERATOR =
      Core.newBaseType("ConcatIterator", 4, LoopCore.ITERATOR);

  /**
   * {@code private compound MatrixUpdater is Lambda}
   *
   * <p>Elements are {@code reshaped}, {@code elements}, {@code index}.
   */
  @Core.Private
  static final BaseType.Named MATRIX_UPDATER = Core.newBaseType("MatrixUpdater", 3, Core.LAMBDA);

  /**
   * {@code private compound BinaryUpdateWithScalar is Loop}
   *
   * <p>Elements are {@code lambda} and {@code rhs}. The state is a collection, and inputs are
   * expected to be keys for that collection. {@code nextState()} updates the specified element of
   * the collection by calling {@code binaryUpdate(element=, lambda, rhs)}.
   */
  @Core.Private
  public static final BaseType.Named BINARY_UPDATE_WITH_SCALAR =
      Core.newBaseType("BinaryUpdateWithScalar", 2, LoopCore.LOOP);

  /**
   * {@code private compound BinaryUpdateWithCollection is Loop}
   *
   * <p>Element is a Lambda. The state is a collection, and inputs are expected to be {@code [key,
   * rhsElement]} pairs. {@code nextState()} updates the specified element of the collection by
   * calling {@code binaryUpdate(element=, lambda, rhsElement)}.
   */
  @Core.Private
  public static final BaseType.Named BINARY_UPDATE_WITH_COLLECTION =
      Core.newBaseType("BinaryUpdateWithCollection", 1, LoopCore.LOOP);

  /**
   * <pre>
   * method size(Matrix m) default {
   *   sizes = sizes(m)
   *   assert sizes | -&gt; # is Integer and # &gt;= 0 | allTrue
   *   return sizes | product
   * }
   * </pre>
   */
  static class SizeMatrix extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("size(Matrix) default")
    static void begin(TState tstate, @RC.In Value m) {
      tstate.startCall(sizes, m);
    }

    @Continuation
    static Value afterSizes(TState tstate, Value sizes) throws BuiltinException {
      return ValueUtil.sizeFromSizes(tstate, sizes, Err.INVALID_SIZES);
    }
  }

  /** {@code method size(m) (m is Reshaped or m is ReshapedArray) = size(m_.elements)} */
  @Core.Method("size(Reshaped|ReshapedArray)")
  static void sizeReshaped(TState tstate, Value m, @Fn("size:1") Caller size) {
    tstate.startCall(size, m.element(1));
  }

  /**
   * <pre>
   * method matrix(Array sizes) {
   *   assert sizes | -&gt; # is Integer and # &gt;= 0 | allTrue
   *   if sizes | -&gt; # == 0 | anyTrue {
   *     return size(sizes) == 1 ? [] : ReshapedArray_({sizes, elements: []})
   *   } else {
   *     return BaseMatrix_(sizes)
   *   }
   * }
   * </pre>
   */
  @Core.Method("matrix(Array)")
  static void matrix1(TState tstate, @RC.In Value sizes) throws BuiltinException {
    ValueUtil.checkSizes(sizes, Err.INVALID_ARGUMENT);
    ValueUtil.containsZero(sizes)
        .test(
            () -> setResultToEmptyMatrix(tstate, sizes),
            () -> tstate.setResult(tstate.compound(BASE_MATRIX, sizes)));
  }

  private static void setResultToEmptyMatrix(TState tstate, @RC.In Value sizes) {
    sizes
        .isArrayOfLength(1)
        .test(
            () -> {
              tstate.dropValue(sizes);
              tstate.setResult(Core.EMPTY_ARRAY);
            },
            () -> tstate.setResult(tstate.compound(RESHAPED_ARRAY, sizes, Core.EMPTY_ARRAY)));
  }

  /**
   * <pre>
   * method matrix(Array newSizes, Matrix base) {
   *   assert newSizes | -&gt; # is Integer and # &gt;= 0 | allTrue
   *   if base is Reshaped {
   *     base = base_.elements
   *   }
   *   baseSizes = sizes(base)
   *   assert baseSizes is Array and baseSizes | -&gt; (# is Integer and # &gt;= 0) | allTrue
   *   if newSizes == baseSizes {
   *     return base
   *   }
   *   assert (newSizes | product) == (baseSizes | product)
   *   return base is Array ? ReshapedArray_({sizes: newSizes, elements: base})
   *                        : Reshaped_({sizes: newSizes, elements: base})
   * }
   * </pre>
   */
  static class Matrix2 extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("matrix(Array, Matrix)")
    static void begin(TState tstate, @RC.In Value newSizes, @RC.In Value base)
        throws BuiltinException {
      Value newSize = ValueUtil.sizeFromSizes(tstate, newSizes, Err.INVALID_ARGUMENT);
      base.isa(RESHAPED)
          .test(
              () -> {
                // There's no reason to nest reshapes.
                Value inner = base.element(1);
                tstate.dropValue(base);
                tstate.startCall(sizes, addRef(inner)).saving(newSize, newSizes, inner);
              },
              () -> tstate.startCall(sizes, addRef(base)).saving(newSize, newSizes, base));
    }

    @Continuation
    static Value afterSizes(
        TState tstate,
        Value baseSizes,
        @Saved Value newSize,
        @RC.In Value newSizes,
        @RC.In Value base)
        throws BuiltinException {
      return Condition.equal(baseSizes, newSizes)
          .chooseExcept(
              () -> {
                // Reshape to the same sizes is a no-op.
                tstate.dropValue(newSizes);
                return base;
              },
              () -> {
                // The Matrix to be reshaped must have the right number of elements
                Value baseSize = ValueUtil.sizeFromSizes(tstate, baseSizes, Err.INVALID_ARGUMENT);
                Condition sizeMatches = Condition.numericEq(baseSize, newSize);
                tstate.dropValue(baseSize);
                Err.INVALID_ARGUMENT.unless(sizeMatches);
                return base.isa(Core.ARRAY)
                    .choose(
                        () -> tstate.compound(RESHAPED_ARRAY, newSizes, base),
                        () -> tstate.compound(RESHAPED, newSizes, base));
              });
    }
  }

  /**
   * <pre>
   * method at(Matrix m, Array key) =
   *   (key^ is Number | allTrue) ? element(m, Key) : subMatrix(m, key)
   * </pre>
   *
   * If {@code key} contains only numbers, calls {@code element()} to get the result; otherwise
   * calls {@code subMatrix()}.
   */
  @Core.Method("at(Matrix, Array)")
  static void at(
      TState tstate,
      @RC.In Value m,
      @RC.In Value key,
      @Fn("element:2") Caller element,
      @Fn("subMatrix:2") Caller subMatrix) {
    allNumbers(key)
        .test(() -> tstate.startCall(element, m, key), () -> tstate.startCall(subMatrix, m, key));
  }

  private static Condition allNumbers(Value key) {
    int n = key.numElements();
    for (int i = 0; i < n; i++) {
      if (key.peekElement(i).baseType() != Core.NUMBER) {
        return Condition.FALSE;
      }
    }
    return Condition.TRUE;
  }

  /**
   * <pre>
   * method subMatrix(Matrix m, Array key) default {
   *   sizes = sizes(m)
   *   assert sizes is Array and (sizes | -&gt; # is Integer and # &gt;= 0 | allTrue)
   *   assert size(key) == size(sizes)
   *   for [i]: key_i in key sequential {
   *     if key_i is Range {
   *       size = sizes[i]
   *       min = min(key_i)
   *       if min is None {
   *         min = 1
   *       }
   *       max = max(key_i)
   *       if max is None {
   *         max = size
   *       }
   *       newSize = max - (min - 1)
   *       assert min &gt;= 1 and max &lt;= size and newSize &gt;= 0
   *       axes &lt;&lt; i
   *       newSizes &lt;&lt; newSize
   *       baseKey &lt;&lt; min
   *       isSubset &lt;&lt; newSize != size
   *       isEmpty &lt;&lt; newSize == 0
   *     } else {
   *       assert key_i is Integer and key_i &gt;= 1 and key_i &lt;= sizes[i]
   *       baseKey &lt;&lt; key_i
   *       isSubset &lt;&lt; True
   *     }
   *   } collect {
   *     axes, newSizes =| saveSequential
   *     baseKey =| save
   *     isSubset, isEmpty =| anyTrue
   *   }
   *   if not isSubset {
   *     return m
   *   } else if isEmpty {
   *     return size(newSizes) == 1 ? [] : ReshapedArray_({sizes: newSizes, elements: []})
   *   } else {
   *     return SubMatrix_({matrix: m, baseKey, axes, sizes: newSizes})
   *   }
   * }
   * </pre>
   *
   * Checks that {@code key} is an array of the appropriate length and that each {@code key[i]} is
   * either an integer in {@code 1..sizes()[i]} or a subbrange of that range. Returns an appropriate
   * SubMatrix (or empty matrix).
   */
  static class SubMatrix extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("subMatrix(Matrix, Array) default")
    static void begin(TState tstate, @RC.In Value m, @RC.In Value key) {
      tstate.startCall(sizes, addRef(m)).saving(m, key);
    }

    @Continuation
    static void afterSizes(TState tstate, Value sizes, @Saved Value m, Value key)
        throws BuiltinException {
      ValueUtil.checkSizes(sizes, Err.INVALID_SIZES);
      int nDims = sizes.numElements();
      Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(nDims));
      int numRanges =
          (int)
              IntStream.range(0, nDims)
                  .filter(i -> key.peekElement(i).baseType() == Core.RANGE)
                  .count();
      @RC.Counted Object[] axes = tstate.allocObjectArray(numRanges);
      tstate.dropOnThrow(axes);
      @RC.Counted Object[] newSizes = tstate.allocObjectArray(numRanges);
      tstate.dropOnThrow(newSizes);
      int baseDims;
      @RC.Counted Object[] baseKey;
      if (m.baseType() == SUB_MATRIX) {
        // Rather than creating a nested SubMatrix, we merge them.  To do that we start with the
        // baseKey of the inner SubMatrix.
        Value innerBaseKey = m.peekElement(1);
        baseDims = innerBaseKey.numElements();
        assert baseDims >= nDims;
        baseKey = copyArray(tstate, innerBaseKey);
      } else {
        baseDims = nDims;
        baseKey = tstate.allocObjectArray(nDims);
      }
      tstate.dropOnThrow(baseKey);
      Condition isEmpty = Condition.FALSE;
      Condition isSubset = Condition.FALSE;
      int numNewSizes = 0;
      for (int i = 0; i < nDims; i++) {
        int keyIndex = i;
        Value offset = null;
        if (m.baseType() == SUB_MATRIX) {
          Value innerAxes = m.peekElement(2);
          keyIndex = innerAxes.elementAsInt(i);
          offset = (Value) baseKey[keyIndex];
          baseKey[keyIndex] = null;
        }
        Value k = key.peekElement(i);
        if (k.baseType() == Core.RANGE) {
          Value size = sizes.peekElement(i);
          Value min = k.peekElement(0);
          min = min.is(Core.NONE).choose(NumValue.ONE, min);
          Value max = k.peekElement(1);
          max = max.is(Core.NONE).choose(size, max);
          baseKey[keyIndex] =
              (offset == null)
                  ? min.makeStorable(tstate)
                  : ValueUtil.oneBasedOffset(tstate, min, offset);
          axes[numNewSizes] = NumValue.of(keyIndex, tstate);
          Value newSize = ValueUtil.rangeSize(tstate, min, max);
          newSizes[numNewSizes] = newSize;
          numNewSizes++;
          isEmpty = isEmpty.or(Condition.numericEq(newSize, NumValue.ZERO));
          isSubset = isSubset.or(Condition.numericLessThan(newSize, size));
          Err.INVALID_ARGUMENT.unless(Condition.numericLessThan(NumValue.ZERO, min));
          Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(max, size));
          Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, newSize));
        } else {
          k = ValueUtil.verifyBoundedInt(tstate, k, NumValue.ONE, sizes.peekElement(i));
          if (offset == null) {
            baseKey[keyIndex] = k;
          } else {
            baseKey[keyIndex] = ValueUtil.oneBasedOffset(tstate, k, offset);
            tstate.dropValue(k);
          }
          isSubset = Condition.TRUE;
        }
      }
      Condition finalIsEmpty = isEmpty;
      isSubset
          .not()
          .test(
              () -> {
                tstate.dropReference(axes);
                tstate.dropReference(newSizes);
                tstate.dropReference(baseKey);
                tstate.setResult(addRef(m));
              },
              () ->
                  finalIsEmpty.test(
                      () -> {
                        tstate.dropReference(axes);
                        tstate.dropReference(baseKey);
                        if (numRanges == 1) {
                          tstate.dropReference(newSizes);
                          tstate.setResult(Core.EMPTY_ARRAY);
                        } else {
                          tstate.setResult(
                              tstate.compound(
                                  RESHAPED_ARRAY,
                                  tstate.asArrayValue(newSizes, numRanges),
                                  Core.EMPTY_ARRAY));
                        }
                      },
                      () ->
                          tstate.setResult(
                              tstate.compound(
                                  SUB_MATRIX,
                                  m.baseType() == SUB_MATRIX ? m.element(0) : addRef(m),
                                  tstate.asArrayValue(baseKey, baseDims),
                                  tstate.asArrayValue(axes, numRanges),
                                  tstate.asArrayValue(newSizes, numRanges)))));
    }
  }

  /** Copies the elements of a Retrospect array into a Java array. */
  @RC.Out
  private static Object[] copyArray(TState tstate, Value array) {
    int size = array.numElements();
    Object[] result = tstate.allocObjectArray(size);
    for (int i = 0; i < size; i++) {
      result[i] = array.element(i);
    }
    return result;
  }

  /** {@code method sizes(BaseMatrix base) = base_} */
  @Core.Method("sizes(BaseMatrix)")
  static Value sizesBaseMatrix(Value baseMatrix) {
    return baseMatrix.element(0);
  }

  /** {@code method keys(BaseMatrix base) = base} */
  @Core.Method("keys(BaseMatrix)")
  static Value keysBaseMatrix(@RC.In Value baseMatrix) {
    return baseMatrix;
  }

  /** {@code method keys(Matrix m) default = matrix(sizes(m))} */
  static class KeysMatrix extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("keys(Matrix) default")
    static void begin(TState tstate, @RC.In Value m) {
      tstate.startCall(sizes, m);
    }

    @Continuation
    static void afterSizes(TState tstate, @RC.In Value sizes) throws BuiltinException {
      matrix1(tstate, sizes);
    }
  }

  /** {@code method new(BaseMatrix base, initialValue) = newMatrix(sizes(base), initialValue)} */
  @Core.Method("new(BaseMatrix, _)")
  static void newBaseMatrix(TState tstate, ResultsInfo results, Value baseMatrix, Value initial)
      throws BuiltinException {
    Value sizes = baseMatrix.element(0);
    tstate.dropOnThrow(sizes);
    newMatrix(tstate, results, sizes, initial);
  }

  /**
   * The key matrix of an empty multi-dimensional matrix is a reshaped []. No other ReshapedArray is
   * a valid key matrix.
   *
   * <pre>
   * method new(ReshapedArray empty, initialValue) {
   *   assert size(empty_.elements) == 0
   *   return empty
   * }
   * </pre>
   */
  @Core.Method("new(ReshapedArray, _)")
  static Value newArray(TState tstate, @RC.In Value empty, Value initial) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(empty.peekElement(1).isArrayOfLength(0));
    return empty;
  }

  /** A TProperty for the layout of the Array element of a ReshapedArray result. */
  private static final TProperty<FrameLayout> RESHAPED_ARRAY_LAYOUT =
      TProperty.ARRAY_LAYOUT.elementOf(RESHAPED_ARRAY, 1);

  /** A TProperty for the layout or base type of the Array element of a ReshapedArray result. */
  private static final TProperty<Object> RESHAPED_ARRAY_LAYOUT_OR_BASE_TYPE =
      TProperty.ARRAY_LAYOUT_OR_BASE_TYPE.elementOf(RESHAPED_ARRAY, 1);

  /**
   * newMatrix() results with more than this many elements will always be represented with a Frame.
   */
  private static final int MAX_ARRAY_COMPOUND_LENGTH = 6;

  /** {@code method newMatrix(Array sizes) = newMatrix(sizes, Absent)} */
  @Core.Method("newMatrix(Array)")
  static void newMatrix(TState tstate, ResultsInfo results, @RC.In Value sizes)
      throws BuiltinException {
    newMatrix(tstate, results, sizes, Core.ABSENT);
  }

  /** {@code method newMatrix(Array sizes, initialValue) = ...} */
  @Core.Method("newMatrix(Array, _)")
  static void newMatrix(TState tstate, ResultsInfo results, @RC.In Value sizes, Value initial)
      throws BuiltinException {
    Value size = ValueUtil.sizeFromSizes(tstate, sizes, Err.INVALID_ARGUMENT);
    if (tstate.hasCodeGen()) {
      sizes
          .isArrayOfLength(1)
          .testExcept(
              () -> tstate.setResult(emitNewArray(tstate, results, size, initial, false)),
              () -> {
                Value array = emitNewArray(tstate, results, size, initial, true);
                tstate.setResult(tstate.compound(RESHAPED_ARRAY, sizes, array));
              });
      return;
    }
    int iSize = NumValue.asInt(size);
    assert iSize >= 0;
    tstate.dropValue(size);
    boolean needsReshape = (sizes.numElements() != 1);
    Value result;
    if (iSize == 0) {
      result = Core.EMPTY_ARRAY;
    } else {
      // Find out if our result is going to be cast to a frame.  (Note that if we're producing a
      // multi-dimensional matrix we have to look inside the RESHAPED_ARRAY compound that we'll
      // wrap it in).
      FrameLayout layout =
          results.result(0, needsReshape ? RESHAPED_ARRAY_LAYOUT : TProperty.ARRAY_LAYOUT);
      if (layout != null || iSize > MAX_ARRAY_COMPOUND_LENGTH) {
        result = FrameLayout.newArray(tstate, layout, iSize, initial);
      } else {
        tstate.reserve(null, iSize);
        Object[] array = tstate.allocObjectArray(iSize);
        Arrays.fill(array, 0, iSize, initial);
        RefCounted.addRef(initial, iSize);
        result = tstate.asArrayValue(array, iSize);
      }
    }
    if (needsReshape) {
      result = tstate.compound(RESHAPED_ARRAY, sizes, result);
    } else {
      tstate.dropValue(sizes);
    }
    tstate.setResult(result);
  }

  private static Value emitNewArray(
      TState tstate, ResultsInfo results, Value size, Value initial, boolean needsReshape)
      throws BuiltinException {
    Object layoutOrBaseType =
        results.result(
            0,
            needsReshape
                ? RESHAPED_ARRAY_LAYOUT_OR_BASE_TYPE
                : TProperty.ARRAY_LAYOUT_OR_BASE_TYPE);
    if (layoutOrBaseType instanceof VArrayLayout layout) {
      CodeGen codeGen = tstate.codeGen();
      CodeValue cvSize = codeGen.asCodeValue(size);
      Err.ESCAPE.unless(
          Condition.isNonZero(
              TState.RESERVE_FOR_CHANGE_OP.result(
                  codeGen.tstateRegister(), CodeValue.of(layout), CodeValue.NULL, cvSize)));
      Register result = codeGen.cb.newRegister(Object.class);
      codeGen.emitSet(result, layout.emitAlloc(codeGen, cvSize));
      layout.emitSetElements(codeGen, result, CodeValue.ZERO, cvSize, RValue.toTemplate(initial));
      return CodeGen.asValue(result, layout);
    } else {
      Err.ESCAPE.when(layoutOrBaseType == null);
      BaseType baseType =
          (layoutOrBaseType instanceof BaseType bt)
              ? bt
              : ((RecordLayout) layoutOrBaseType).template.baseType();
      assert baseType.isArray();
      int iSize = baseType.size();
      Err.ESCAPE.unless(Condition.numericEq(size, NumValue.of(iSize, Allocator.UNCOUNTED)));
      if (iSize == 0) {
        return Core.EMPTY_ARRAY;
      }
      assert !RefCounted.isRefCounted(initial);
      Value[] elements = new Value[iSize];
      Arrays.fill(elements, 0, iSize, initial);
      return tstate.arrayValue(elements);
    }
  }

  /** {@code method sizes(m) (m is Reshaped or m is ReshapedArray) = m_.sizes} */
  @Core.Method("sizes(Reshaped|ReshapedArray)")
  static Value sizesReshaped(Value m) {
    return m.element(0);
  }

  /** {@code method sizes(TransformedMatrix m) = sizes(m_.base)} */
  @Core.Method("sizes(TransformedMatrix)")
  static void sizesTransformedMatrix(TState tstate, Value m, @Fn("sizes:1") Caller sizes) {
    tstate.startCall(sizes, m.element(0));
  }

  /** {@code method sizes(WithKeysMatrix m) = sizes(m_)} */
  @Core.Method("sizes(WithKeysMatrix)")
  static void sizesWithKeysMatrix(TState tstate, Value m, @Fn("sizes:1") Caller sizes) {
    tstate.startCall(sizes, m.element(0));
  }

  /** {@code method sizes(SubMatrix m) = m_.sizes} */
  @Core.Method("sizes(SubMatrix)")
  static Value sizesSubMatrix(Value m) {
    return m.element(3);
  }

  /** {@code method element(BaseMatrix base, key) = key} */
  @Core.Method("element(BaseMatrix, _)")
  static Value elementBaseMatrix(TState tstate, Value baseMatrix, @RC.In Value key)
      throws BuiltinException {
    ValueUtil.checkKey(tstate, key, baseMatrix.peekElement(0));
    return key;
  }

  /**
   * <pre>
   * method element(m, Array key) (m is Reshaped or m is ReshapedArray) =
   *     element(m_.elements, convertKey(key, m_.sizes, sizes(m_.elements))
   * </pre>
   */
  static class ElementReshaped extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("element(Reshaped|ReshapedArray, Array)")
    static void begin(TState tstate, @RC.In Value m, @RC.In Value key) {
      tstate.startCall(sizes, m.element(1)).saving(m, key);
    }

    @Continuation
    static void afterSizes(
        TState tstate,
        MethodMemo mMemo,
        Value baseSizes,
        @Saved Value m,
        Value key,
        @Fn("element:2") Caller element)
        throws BuiltinException {
      ValueUtil.checkSizes(baseSizes, Err.INVALID_SIZES);
      Value index = ValueUtil.keyToIndex(tstate, key, m.peekElement(0));
      Value transformedKey =
          ValueUtil.indexToKey(
              tstate,
              index,
              baseSizes,
              () -> element.argInfo(tstate, mMemo, 1, TProperty.ARRAY_LAYOUT_OR_BASE_TYPE));
      tstate.startCall(element, m.element(1), transformedKey);
    }
  }

  /**
   * <pre>
   * method replaceElement(ReshapedArray m, Array key, value) {
   *   arrayKey = convertKey(key, m_.sizes, sizes(m_.elements))
   *   return ReshapedArray_(m_.sizes, replaceElement(m_.elements, arrayKey, value))
   * </pre>
   */
  @Core.Method("replaceElement(ReshapedArray, Array, _)")
  static Value replaceElement(TState tstate, @RC.In Value matrix, Value key, @RC.In Value value)
      throws BuiltinException {
    Value sizes = matrix.peekElement(0);
    Value index = ValueUtil.keyToIndex(tstate, key, sizes);
    Value elements = matrix.element(1);
    tstate.dropOnThrow(elements);
    ValueUtil.checkIndex(tstate, elements, index);
    // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
    matrix = matrix.replaceElement(tstate, 1, Core.TO_BE_SET);
    elements = ValueUtil.replaceElement(tstate, elements, index, 0, value);
    matrix = matrix.replaceElement(tstate, 1, elements);
    return matrix;
  }

  /**
   * <pre>
   * method startUpdate(ReshapedArray m=, Array key) { ... }
   * </pre>
   */
  @Core.Method("startUpdate(ReshapedArray, Array)")
  static void startUpdate(TState tstate, @RC.In Value matrix, Value key) throws BuiltinException {
    Value sizes = matrix.peekElement(0);
    Value index = ValueUtil.keyToIndex(tstate, key, sizes);
    Value elements = matrix.element(1);
    tstate.dropOnThrow(elements);
    ValueUtil.checkIndex(tstate, elements, index);
    // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
    matrix = matrix.replaceElement(tstate, 1, Core.TO_BE_SET);
    Value element = ValueUtil.element(tstate, elements, index, 0);
    elements = ValueUtil.replaceElement(tstate, elements, index, 0, Core.TO_BE_SET);
    tstate.setResults(
        element, tstate.compound(MATRIX_UPDATER, matrix, elements, index.makeStorable(tstate)));
  }

  /**
   * <pre>
   * method at(MatrixUpdater updater, newElement) = ...
   * </pre>
   */
  @Core.Method("at(MatrixUpdater, _)")
  static Value atMatrixUpdater(TState tstate, @RC.In Value updater, @RC.In Value v) {
    Value matrix = updater.element(0);
    assert matrix.baseType() == RESHAPED_ARRAY;
    Value elements = updater.element(1);
    Value index = updater.peekElement(2);
    tstate.dropValue(updater);
    elements = ValueUtil.replaceElement(tstate, elements, index, 0, v);
    return matrix.replaceElement(tstate, 1, elements);
  }

  /**
   * <pre>
   * method iterator(m, EnumerationKind eKind, loop=, initialState=) (m is Reshaped or m is ReshapedArray) {
   *   if eKind is not EnumerateValues {
   *     inSizes = sizes(m_.elements)
   *     outSizes = m_.sizes
   *     loop = transformLoop([k, v] -&gt; [convertKey(k, inSizes, outSizes), v], loop)
   *   }
   *   return iterator(m_.elements, eKind, loop=, initialState=)
   * }
   * </pre>
   */
  static class IteratorReshaped extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("iterator(Reshaped|ReshapedArray, EnumerationKind, Loop, _)")
    static void begin(
        TState tstate,
        Value m,
        @RC.Singleton Value eKind,
        @RC.In Value loop,
        @RC.In Value initialState,
        @Fn("iterator:4") Caller iterator) {
      Value elements = m.element(1);
      eKind
          .is(LoopCore.ENUMERATE_VALUES)
          .test(
              () -> tstate.startCall(iterator, elements, eKind, loop, initialState),
              () -> tstate.startCall(sizes, elements).saving(addRef(m), eKind, loop, initialState));
    }

    @Continuation
    static void afterSizes(
        TState tstate,
        @RC.In Value inSizes,
        @Saved Value m,
        @RC.Singleton Value eKind,
        @RC.In Value loop,
        @RC.In Value initialState,
        @Fn("iterator:4") Caller iterator)
        throws BuiltinException {
      ValueUtil.checkSizes(inSizes, Err.INVALID_SIZES);
      Value outSizes = m.element(0);
      Value elements = m.element(1);
      Value lambda = tstate.compound(KEY_CONVERTER, inSizes, outSizes);
      loop = tstate.compound(LoopCore.TRANSFORMED_LOOP, lambda, loop);
      tstate.startCall(iterator, elements, eKind, loop, initialState);
    }
  }

  /** {@code method at(OffsetKey ok, [[i], v]) = [[i + ok_], v]} */
  @Core.Method("at(OffsetKey, Array)")
  static Value atKeyConverter(TState tstate, Value ok, Value pair) throws BuiltinException {
    if (!(ok instanceof RValue) && ok.elementAsIntOrMinusOne(0) == 0) {
      return addRef(pair);
    }
    Err.NOT_PAIR.unless(pair.isArrayOfLength(2));
    Value k = pair.peekElement(0);
    Err.INVALID_ARGUMENT.unless(k.isArrayOfLength(1));
    Value i = k.peekElement(0).verifyInt(Err.INVALID_ARGUMENT);
    Value sum = ValueUtil.addInts(tstate, i, ok.element(0));
    return tstate.arrayValue(tstate.arrayValue(sum), pair.element(1));
  }

  private static final TProperty<Object> ARRAY_LAYOUT_OR_BASETYPE_IN_PAIR =
      TProperty.ARRAY_LAYOUT_OR_BASE_TYPE.elementOf(Core.FixedArrayType.withSize(2), 0);

  /** {@code method at(KeyConverter kc, [k, v]) = [convertKey(k, kc_.inSizes, kc_.outSizes), v]} */
  @Core.Method("at(KeyConverter, Array)")
  static Value atKeyConverter(TState tstate, ResultsInfo results, Value kc, Value pair)
      throws BuiltinException {
    Err.NOT_PAIR.unless(pair.isArrayOfLength(2));
    Value k = pair.peekElement(0);
    Value inSizes = kc.peekElement(0);
    Value outSizes = kc.peekElement(1);
    Value index = ValueUtil.keyToIndex(tstate, k, inSizes);
    Value convertedKey =
        ValueUtil.indexToKey(
            tstate, index, outSizes, () -> results.result(ARRAY_LAYOUT_OR_BASETYPE_IN_PAIR));
    return tstate.arrayValue(convertedKey, pair.element(1));
  }

  /**
   * <pre>
   * method element(SubMatrix m, Array key) = element(m_.matrix, offsetKey(key, m))
   * </pre>
   */
  @Core.Method("element(SubMatrix, Array)")
  static void elementSubMatrix(TState tstate, Value m, Value key, @Fn("element:2") Caller element)
      throws BuiltinException {
    Value axes = m.peekElement(2);
    Value sizes = m.peekElement(3);
    int nDims = axes.numElements();
    Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(nDims));
    Value newKey = m.element(1);
    if (m instanceof RValue || key instanceof RValue) {
      for (int i = 0; i < nDims; i++) {
        Value k =
            ValueUtil.verifyBoundedInt(
                tstate, key.peekElement(i), NumValue.ONE, sizes.peekElement(i));
        if (k instanceof NumValue && NumValue.equals(k, 1)) {
          // No update needed
        } else {
          int keyIndex = axes.elementAsInt(i);
          Value nk = ValueUtil.oneBasedOffset(tstate, newKey.peekElement(keyIndex), k);
          newKey = newKey.replaceElement(tstate, keyIndex, nk);
        }
      }
    } else {
      for (int i = 0; i < nDims; i++) {
        int k = key.elementAsIntOrMinusOne(i);
        if (k < 1 || k > sizes.elementAsInt(i)) {
          tstate.dropValue(newKey);
          throw Err.INVALID_ARGUMENT.asException();
        } else if (k == 1) {
          continue;
        }
        int keyIndex = axes.elementAsInt(i);
        int nk = newKey.elementAsInt(keyIndex) + k - 1;
        newKey = newKey.replaceElement(tstate, keyIndex, NumValue.of(nk, tstate));
      }
    }
    Value base = m.element(0);
    tstate.startCall(element, base, newKey);
  }

  /** A TProperty for the layout or base type of the prev element of a BaseIterator result. */
  private static final TProperty<Object> BASE_ITERATOR_PREV_LAYOUT_OR_BASE_TYPE =
      TProperty.ARRAY_LAYOUT_OR_BASE_TYPE.elementOf(BASE_ITERATOR, 1);

  /**
   * <pre>
   * method iterator(SubMatrix m, EnumerationKind eKind, Loop loop=, initialState=) {
   *   lambda = applyToValues(k -> element(m, k), eKind)
   *   loop = transformLoop(lambda, loop)
   *   return iterator(matrix(m_.sizes), eKind, loop=, initialState=)
   * }
   * </pre>
   */
  @Core.Method("iterator(SubMatrix, EnumerationKind, Loop, _)")
  static void iteratorSubMatrix(
      TState tstate,
      ResultsInfo results,
      @RC.In Value m,
      @RC.Singleton Value eKind,
      @RC.In Value loop,
      @RC.In Value initialState) {
    Value lambda = tstate.compound(BinaryOp.ELEMENT.partialLeft, m);
    lambda = LoopCore.applyToValues(tstate, lambda, eKind);
    loop = tstate.compound(LoopCore.TRANSFORMED_LOOP, lambda, loop);
    Value sizes = m.element(3);
    Object layoutOrBaseType = results.result(BASE_ITERATOR_PREV_LAYOUT_OR_BASE_TYPE);
    tstate.setResults(
        iteratorForBaseMatrixWithSizes(tstate, sizes, eKind, layoutOrBaseType), loop, initialState);
  }

  // Trivial results results returned by iterator(emptyMatrix
  private static final Value EMPTY_ARRAY_ONLY =
      LoopCore.TRIVIAL_ITERATOR.uncountedOf(Core.EMPTY_ARRAY);
  private static final Value EMPTY_PAIR =
      Core.FixedArrayType.withSize(2).uncountedOf(Core.EMPTY_ARRAY, Core.EMPTY_ARRAY);
  private static final Value EMPTY_PAIR_ONLY = LoopCore.TRIVIAL_ITERATOR.uncountedOf(EMPTY_PAIR);

  /**
   * <pre>
   * method iterator(BaseMatrix m, EnumerationKind eKind, loop=, initialState=) {
   *   sizes = m_
   *   if size(sizes) == 0 {
   *     return oneElementIterator(eKind is EnumerateValues ? [] : [[], []])
   *   } else {
   *     nDims = size(sizes)
   *     prev = newMatrix(nDims, 1)
   *     prev[nDims] = 0
   *     return BaseIterator_({eKind, prev, sizes})
   *   }
   * }
   * </pre>
   */
  @Core.Method("iterator(BaseMatrix, EnumerationKind, Loop, _)")
  static void iteratorBaseMatrix(
      TState tstate,
      ResultsInfo results,
      Value m,
      @RC.Singleton Value eKind,
      @RC.In Value loop,
      @RC.In Value initialState) {
    Value sizes = m.element(0);
    sizes
        .isArrayOfLength(0)
        .test(
            () -> {
              tstate.dropValue(sizes);
              tstate.setResults(
                  eKind.is(LoopCore.ENUMERATE_VALUES).choose(EMPTY_ARRAY_ONLY, EMPTY_PAIR_ONLY),
                  loop,
                  initialState);
            },
            () -> {
              Object layoutOrBaseType = results.result(BASE_ITERATOR_PREV_LAYOUT_OR_BASE_TYPE);
              tstate.setResults(
                  iteratorForBaseMatrixWithSizes(tstate, sizes, eKind, layoutOrBaseType),
                  loop,
                  initialState);
            });
  }

  @RC.Out
  private static Value iteratorForBaseMatrixWithSizes(
      TState tstate, @RC.In Value sizes, @RC.Singleton Value eKind, Object layoutOrBaseType) {
    int nDims;
    if (!(sizes instanceof RValue && sizes.baseType() == Core.VARRAY)) {
      nDims = sizes.numElements();
    } else if (layoutOrBaseType instanceof VArrayLayout) {
      throw new UnsupportedOperationException();
    } else {
      if (layoutOrBaseType instanceof BaseType) {
        nDims = ((BaseType) layoutOrBaseType).size();
      } else {
        nDims = ((RecordLayout) layoutOrBaseType).baseType().size();
      }
      tstate.codeGen().escapeUnless(sizes.isArrayOfLength(nDims));
    }
    Object[] prev = tstate.allocObjectArray(nDims);
    Arrays.fill(prev, 0, nDims - 1, NumValue.ONE);
    prev[nDims - 1] = NumValue.ZERO;
    return tstate.compound(BASE_ITERATOR, eKind, tstate.asArrayValue(prev, nDims), sizes);
  }

  /**
   * <pre>
   * method next(BaseIterator it=) {
   *   {eKind, prev: key, sizes} = it_
   *   for i in reverse(1..size(key)) sequential key {
   *     if key[i] &lt; sizes[i] {
   *       key[i] += 1
   *       break {
   *         it_.prev = key
   *         return eKind is EnumerateValues ? key : [key, key]
   *       }
   *     }
   *     key[i] = 1
   *   }
   *   return Absent
   * }
   * </pre>
   */
  @Core.Method("next(BaseIterator)")
  static void nextBaseIterator(TState tstate, @RC.In Value it) {
    @RC.Singleton Value eKind = it.peekElement(0);
    Value key = it.element(1);
    Value sizes = it.peekElement(2);
    // TODO: handle key and/or sizes being a varray
    assert key.baseType() == sizes.baseType();
    int nDims = sizes.numElements();
    if (key instanceof RValue || sizes instanceof RValue) {
      nextBaseIteratorHelper(tstate.codeGen(), nDims - 1, nDims, eKind, key, sizes, it);
      return;
    }
    for (int i = nDims - 1; i >= 0; i--) {
      int k = key.elementAsInt(i);
      if (k < sizes.elementAsInt(i)) {
        // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
        it = it.replaceElement(tstate, 1, Core.TO_BE_SET);
        key = key.replaceElement(tstate, i, NumValue.of(k + 1, tstate));
        for (int j = i + 1; j < nDims; j++) {
          key = key.replaceElement(tstate, j, NumValue.ONE);
        }
        Value result = addRef(key);
        it = it.replaceElement(tstate, 1, key);
        if (eKind != LoopCore.ENUMERATE_VALUES) {
          result = tstate.arrayValue(addRef(result), result);
        }
        tstate.setResults(result, it);
        return;
      }
    }
    tstate.dropValue(key);
    tstate.setResults(Core.ABSENT, it);
  }

  /**
   * Using recursion is the easiest way to implement this but in theory could cause a stack overflow
   * if key/size were (very) implausibly large.
   */
  private static void nextBaseIteratorHelper(
      CodeGen codeGen, int i, int nDims, Value eKind, Value key, Value sizes, Value it) {
    if (i < 0) {
      codeGen.setResults(Core.ABSENT, it);
      return;
    }
    Value k = key.peekElement(i);
    Condition.numericLessThan(k, sizes.peekElement(i))
        .test(
            () -> {
              TState tstate = codeGen.tstate();
              Value updatedIt = it.replaceElement(tstate, 1, Core.TO_BE_SET);
              Value updatedKey =
                  key.replaceElement(tstate, i, ValueUtil.addInts(tstate, k, NumValue.ONE));
              for (int j = i + 1; j < nDims; j++) {
                updatedKey = updatedKey.replaceElement(tstate, j, NumValue.ONE);
              }
              updatedIt = updatedIt.replaceElement(tstate, 1, updatedKey);
              Value result =
                  eKind
                      .is(LoopCore.ENUMERATE_VALUES)
                      .choose(updatedKey, tstate.arrayValue(updatedKey, updatedKey));
              tstate.setResults(result, updatedIt);
            },
            () -> nextBaseIteratorHelper(codeGen, i - 1, nDims, eKind, key, sizes, it));
  }

  /**
   * <pre>
   * method join(Matrix c1, Matrix c2) {
   *   assert sizes(c1) == sizes(c2)
   *   return JoinedMatrix_({c1, c2})
   * }
   * </pre>
   */
  static class JoinMatrix extends BuiltinMethod {
    static final Caller sizesM1 = new Caller("sizes:1", "afterSizesM1");
    static final Caller sizesM2 = new Caller("sizes:1", "afterSizesM2");

    @Core.Method("join(Matrix, Matrix)")
    static void begin(TState tstate, @RC.In Value m1, @RC.In Value m2) {
      tstate.startCall(sizesM1, addRef(m1)).saving(m1, m2);
    }

    @Continuation
    static void afterSizesM1(
        TState tstate, @RC.In Value m1Sizes, @Saved @RC.In Value m1, @RC.In Value m2) {
      tstate.startCall(sizesM2, addRef(m2)).saving(m1, m2, m1Sizes);
    }

    @Continuation(order = 2)
    static Value afterSizesM2(
        TState tstate, Value m2Sizes, @Saved @RC.In Value m1, @RC.In Value m2, Value m1Sizes)
        throws BuiltinException {
      checkSizesMatch(tstate, m1Sizes, m2Sizes);
      return tstate.compound(CollectionCore.JOINED_MATRIX, m1, m2);
    }
  }

  private static void checkSizesMatch(TState tstate, Value sizes1, Value sizes2)
      throws BuiltinException {
    ValueUtil.checkSizes(sizes1, Err.INVALID_SIZES);
    ValueUtil.checkSizes(sizes2, Err.INVALID_SIZES);
    if (!(sizes1 instanceof RValue || sizes2 instanceof RValue)) {
      int nDims = sizes1.numElements();
      Err.INVALID_ARGUMENT.unless(
          sizes2.numElements() == nDims
              && IntStream.range(0, nDims)
                  .allMatch(i -> sizes1.elementAsInt(i) == sizes2.elementAsInt(i)));
    } else {
      CodeGen codeGen = tstate.codeGen();
      int n;
      if (sizes1.baseType() != Core.VARRAY || !(sizes1 instanceof RValue)) {
        n = sizes1.numElements();
        Err.INVALID_SIZES.unless(sizes2.isArrayOfLength(n));
      } else if (sizes2.baseType() != Core.VARRAY || !(sizes2 instanceof RValue)) {
        n = sizes2.numElements();
        Err.INVALID_SIZES.unless(sizes1.isArrayOfLength(n));
      } else {
        throw new UnsupportedOperationException();
      }
      for (int i = 0; i < n; i++) {
        codeGen.escapeUnless(Condition.numericEq(sizes1.peekElement(i), sizes2.peekElement(i)));
      }
    }
  }

  /**
   * <pre>
   * method sizes(JoinedMatrix m) = sizes(m_.c1)
   * </pre>
   */
  @Core.Method("sizes(JoinedMatrix)")
  static void sizesJoinedMatrix(TState tstate, Value m, @Fn("sizes:1") Caller sizes) {
    tstate.startCall(sizes, m.element(0));
  }

  /**
   * <pre>
   * method concat(Matrix v1, Matrix v2) {
   *   [len1] = sizes(v1)
   *   [len2] = sizes(v2)
   *   assert len1 >= 0 and len2 >= 0
   *   if len1 == 0 { return v2 }
   *   if len2 == 0 { return v1 }
   *   return ConcatVector_({size: len1 + len2, v1, v2})
   * }
   * </pre>
   */
  static class Concat extends BuiltinMethod {
    static final Caller sizesV1 = new Caller("sizes:1", "afterSizesV1");
    static final Caller sizesV2 = new Caller("sizes:1", "afterSizesV2");

    @Core.Method("concat(Matrix, Matrix)")
    static void begin(TState tstate, @RC.In Value v1, @RC.In Value v2) {
      tstate.startCall(sizesV1, addRef(v1)).saving(v1, v2);
    }

    @Continuation
    static void afterSizesV1(
        TState tstate, @RC.In Value sizes1, @Saved @RC.In Value v1, @RC.In Value v2) {
      tstate.startCall(sizesV2, addRef(v2)).saving(v1, v2, sizes1);
    }

    @Continuation(order = 2)
    static void afterSizesV2(
        TState tstate, Value sizes2, @Saved @RC.In Value v1, @RC.In Value v2, Value sizes1)
        throws BuiltinException {
      Err.INVALID_ARGUMENT.unless(sizes1.isArrayOfLength(1).and(sizes2.isArrayOfLength(1)));
      Value len1 = sizes1.peekElement(0).verifyInt(Err.INVALID_SIZES);
      Err.INVALID_SIZES.when(Condition.numericLessThan(len1, NumValue.ZERO));
      Value len2 = sizes2.peekElement(0).verifyInt(Err.INVALID_SIZES);
      Err.INVALID_SIZES.when(Condition.numericLessThan(len2, NumValue.ZERO));
      Condition.numericEq(len1, NumValue.ZERO)
          .test(
              () -> {
                tstate.dropValue(v1);
                tstate.setResult(v2);
              },
              () ->
                  Condition.numericEq(len2, NumValue.ZERO)
                      .test(
                          () -> {
                            tstate.dropValue(v2);
                            tstate.setResult(v1);
                          },
                          () -> {
                            tstate.setResult(
                                tstate.compound(
                                    CONCAT_VECTOR, ValueUtil.addInts(tstate, len1, len2), v1, v2));
                          }));
    }
  }

  /**
   * <pre>
   * method sizes(ConcatVector cv) = [cv_.size]
   * </pre>
   */
  @Core.Method("sizes(ConcatVector)")
  static Value sizesConcatVector(TState tstate, Value cv) {
    return tstate.arrayValue(cv.element(0));
  }

  /**
   * <pre>
   * method element(ConcatVector cv, [index]) {
   *   [len1] = sizes(cv_.v1)
   *   return index &lt;= len1 ? element(cv_.v1, [index]) : element(cv_.v2, [index - len1])
   * }
   * </pre>
   */
  static class ElementConcat extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("element(ConcatVector, Array)")
    static void begin(TState tstate, @RC.In Value cv, @RC.In Value key) throws BuiltinException {
      Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(1));
      Err.INVALID_ARGUMENT.unless(key.elementAsIntOrMinusOne(0) >= 1);
      Value v1 = cv.element(1);
      tstate.startCall(sizes, v1).saving(cv, key);
    }

    @Continuation
    static void afterSizes(
        TState tstate, Value sizes1, @Saved Value cv, Value key, @Fn("element:2") Caller element)
        throws BuiltinException {
      Err.INVALID_ARGUMENT.unless(sizes1.isArrayOfLength(1));
      int size1 = sizes1.elementAsIntOrMinusOne(0);
      Err.INVALID_SIZES.unless(size1 >= 0);
      int diff = key.elementAsInt(0) - size1;
      Value v = cv.element(diff <= 0 ? 1 : 2);
      Value vKey = diff <= 0 ? addRef(key) : tstate.arrayValue(NumValue.of(diff, tstate));
      tstate.startCall(element, v, vKey);
    }
  }

  /**
   * <pre>
   * method iterator(ConcatVector cv, EnumerationKind eKind, Loop loop=) {
   *   loop1 = SimpleLoop
   *   initialState1 = Absent
   *   it1 = iterator(cv_.v1, eKind, loop1=, initialState1=)
   *   assert initialState1 is Absent and isOnlyTransforms(loop1)
   *   loop2 = SimpleLoop
   *   if eKind is not EnumerateValues {
   *     size1 = size(cv_.v1)
   *     loop2 = transformLoop([[i], v] -> [[i + size1], v], loop2)
   *   }
   *   initialState2 = Absent
   *   it2 = iterator(cv_.v2, eKind, loop2=, initialState2=)
   *   assert initialState2 is Absent and isOnlyTransforms(loop2)
   *   return ConcatIterator_({it1, loop1, it2, loop2})
   * }
   * </pre>
   */
  static class IteratorConcat extends BuiltinMethod {
    static final Caller iterator1 = new Caller("iterator:4", "afterIterator1");
    static final Caller size = new Caller("size:1", "afterSize");
    static final Caller iterator2 = new Caller("iterator:4", "afterIterator2");

    @Core.Method("iterator(ConcatVector, EnumerationKind, Loop, _)")
    static void begin(
        TState tstate,
        @RC.In Value cv,
        @RC.Singleton Value eKind,
        @RC.In Value loop,
        @RC.In Value initialState) {
      Value v1 = cv.element(1);
      tstate
          .startCall(iterator1, v1, eKind, LoopCore.SIMPLE_LOOP, Core.ABSENT)
          .saving(cv, eKind, loop, initialState);
    }

    @Continuation
    static void afterIterator1(
        TState tstate,
        @RC.In Value it1,
        @RC.In Value loop1,
        @RC.In Value initialState1,
        @Saved Value cv,
        @RC.Singleton Value eKind,
        @RC.In Value loop,
        @RC.In Value initialState)
        throws BuiltinException {
      Err.INVALID_ARGUMENT.unless(initialState1.is(Core.ABSENT));
      Err.INVALID_ARGUMENT.unless(isOnlyTransforms(loop1));
      Value v2 = cv.element(2);
      eKind
          .is(LoopCore.ENUMERATE_VALUES)
          .test(
              () -> tstate.jump("afterSize", Core.UNDEF, it1, loop1, v2, eKind, loop, initialState),
              () -> {
                Value v1 = cv.element(1);
                tstate.startCall(size, v1).saving(it1, loop1, v2, eKind, loop, initialState);
              });
    }

    @Continuation(order = 2)
    static void afterSize(
        TState tstate,
        @RC.In Value size1,
        @Saved @RC.In Value it1,
        @RC.In Value loop1,
        @RC.In Value v2,
        @RC.Singleton Value eKind,
        @RC.In Value loop,
        @RC.In Value initialState) {
      Value loop2 =
          eKind
              .is(LoopCore.ENUMERATE_VALUES)
              .choose(
                  () -> LoopCore.SIMPLE_LOOP,
                  () ->
                      tstate.compound(
                          LoopCore.TRANSFORMED_LOOP,
                          tstate.compound(OFFSET_KEY, size1),
                          LoopCore.SIMPLE_LOOP));
      tstate
          .startCall(iterator2, v2, eKind, loop2, Core.ABSENT)
          .saving(it1, loop1, loop, initialState);
    }

    @Continuation(order = 3)
    static void afterIterator2(
        TState tstate,
        @RC.In Value it2,
        @RC.In Value loop2,
        @RC.In Value initialState2,
        @Saved @RC.In Value it1,
        @RC.In Value loop1,
        @RC.In Value loop,
        @RC.In Value initialState)
        throws BuiltinException {
      Err.INVALID_ARGUMENT.unless(initialState2.is(Core.ABSENT));
      Err.INVALID_ARGUMENT.unless(isOnlyTransforms(loop2));
      tstate.setResults(
          tstate.compound(CONCAT_ITERATOR, it1, loop1, it2, loop2), loop, initialState);
    }
  }

  private static Condition isOnlyTransforms(Value value) {
    return value
        .is(LoopCore.SIMPLE_LOOP)
        .or(
            () ->
                value
                    .isa(LoopCore.TRANSFORMED_LOOP)
                    .and(() -> isOnlyTransforms(value.peekElement(1))));
  }

  /**
   * <pre>
   * method next(ConcatIterator it=) {
   *   for sequential it {
   *     if it_.it1 is not Absent {
   *       x = next(it_.it1=)
   *       if x is Absent {
   *         it_.it1 = Absent
   *         continue
   *       }
   *       x = nextState(loop1, Absent, x)
   *     } else {
   *       x = next(it_.it2=)
   *       if x is Absent {
   *         return Absent
   *       }
   *       x = nextState(loop2, Absent, x)
   *     }
   *     if x is not Absent {
   *       return x
   *     }
   *   }
   * }
   * </pre>
   */
  static class NextConcatIterator extends BuiltinMethod {
    static final Caller next1 = new Caller("next:1", "afterNext1");
    static final Caller nextState1 = new Caller("nextState:3", "afterNextState");
    static final Caller next2 = new Caller("next:1", "afterNext2");
    static final Caller nextState2 = new Caller("nextState:3", "afterNextState");

    @Core.Method("next(ConcatIterator)")
    static void begin(TState tstate, @RC.In Value it) {
      tstate.jump("loop", it);
    }

    @LoopContinuation
    static void loop(TState tstate, @RC.In Value it) {
      Value it1 = it.element(0);
      it1.is(Core.ABSENT)
          .not()
          .test(
              () -> {
                Value updated = it.replaceElement(tstate, 0, Core.TO_BE_SET);
                tstate.startCall(next1, it1).saving(updated);
              },
              () -> {
                Value it2 = it.element(2);
                Value updated = it.replaceElement(tstate, 2, Core.TO_BE_SET);
                tstate.startCall(next2, it2).saving(updated);
              });
    }

    @Continuation(order = 2)
    static void afterNext1(
        TState tstate, @RC.In Value x, @RC.In Value it1, @Saved @RC.In Value it) {
      x.is(Core.ABSENT)
          .test(
              () -> {
                tstate.dropValue(it1);
                Value updated = it.replaceElement(tstate, 0, Core.ABSENT);
                tstate.jump("loop", updated);
              },
              () -> {
                Value updated = it.replaceElement(tstate, 0, it1);
                Value loop1 = updated.element(1);
                tstate.startCall(nextState1, loop1, Core.ABSENT, x).saving(updated);
              });
    }

    @Continuation(order = 3)
    static void afterNext2(
        TState tstate, @RC.In Value x, @RC.In Value it2, @Saved @RC.In Value it) {
      Value updated = it.replaceElement(tstate, 2, it2);
      x.is(Core.ABSENT)
          .test(
              () -> tstate.setResults(Core.ABSENT, updated),
              () -> {
                Value loop2 = updated.element(3);
                tstate.startCall(nextState2, loop2, Core.ABSENT, x).saving(updated);
              });
    }

    @Continuation(order = 4)
    static void afterNextState(TState tstate, @RC.In Value x, @Saved @RC.In Value it) {
      x.is(Core.ABSENT).test(() -> tstate.jump("loop", it), () -> tstate.setResults(x, it));
    }
  }

  /**
   * <pre>
   * method concatUpdate(Array lhs, Matrix rhs) = ...
   * </pre>
   */
  static class ConcatUpdate extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("concatUpdate(Array, Matrix)")
    static void begin(TState tstate, @RC.In Value lhs, @RC.In Value rhs) {
      tstate.startCall(sizes, addRef(rhs)).saving(lhs, rhs);
    }

    @Continuation
    static void afterSizes(
        TState tstate,
        ResultsInfo results,
        Value sizes,
        @Saved @RC.In Value lhs,
        @RC.In Value rhs,
        @Fn("enumerate:4") Caller enumerate)
        throws BuiltinException {
      Err.INVALID_SIZES.unless(sizes.isArrayOfLength(1));
      Value size2 = sizes.peekElement(0).verifyInt(Err.INVALID_SIZES);
      Err.INVALID_SIZES.when(Condition.numericLessThan(size2, NumValue.ZERO));
      Condition.numericEq(size2, NumValue.ZERO)
          .testExcept(
              () -> {
                tstate.dropValue(rhs);
                tstate.setResult(lhs);
              },
              () -> {
                Value size1 = tstate.getArraySizeAndReserveForChange(lhs, size2, null);
                // Add size2 TO_BE_SET elements at the end of lhs
                FrameLayout resultLayout = results.result(TProperty.ARRAY_LAYOUT);
                Value afterRemove =
                    ValueUtil.removeRange(tstate, lhs, size1, NumValue.ZERO, size2, resultLayout);
                Value loop = tstate.compound(SaveCore.SAVE_WITH_OFFSET, size1);
                // Wrapping SaveWithOffset (a sequential loop) in SaverLoop makes it parallelizable
                // (since we don't care in which order the elements are computed, as long as each is
                // saved in the corresponding element of the result).
                loop = tstate.compound(SaveCore.SAVER_LOOP, loop);
                // Enumerate the elements of rhs and store each into the appropriate place in the
                // copy
                tstate.startCall(enumerate, rhs, LoopCore.ENUMERATE_ALL_KEYS, loop, afterRemove);
              });
    }
  }

  /**
   * <pre>
   * method binaryUpdate(Matrix lhs=, Lambda lambda, rhs) {
   *   if rhs is Matrix {
   *     assert sizes(rhs) == sizes(lhs)
   *     return iterate(rhs, EnumerateAllKeys, BinaryUpdateWithCollection_(lambda), lhs)
   *   } else {
   *     return iterate(keys(lhs), EnumerateValues, BinaryUpdateWithScalar_({lambda, rhs}), lhs)
   *   }
   * }
   * </pre>
   *
   * {@code binaryUpdate()} is overridden when the left hand side is a Matrix to behave more like an
   * "in place" update to the matrix:
   *
   * <ul>
   *   <li>The calculation is done eagerly rather than lazily (if we didn't override {@code
   *       binaryUpdate()}, the default implementation of e.g. "{@code +=}" would use {@code add()},
   *       which is lazy for matrices).
   *   <li>The left hand side must be an updatable matrix (generally an Array or ReshapedArray).
   * </ul>
   *
   * Could we parallelize this? Wrapping the update loop in a SaverLoop and replacing iterate() with
   * enumerate() would "work", but mostly wouldn't help; most or all of the work is done in the
   * update loop's nextState, which would be serialized by the SaverLoop. The most straightforward
   * way to parallelize is to switch from update-in-place to something like
   *
   * <pre>
   * method binaryUpdate(Matrix lhs=, Lambda lambda, rhs) {
   *   if rhs is Matrix {
   *     return join(lhs, rhs) | lambda | save
   *   } else {
   *     return lhs | -&gt; lambda[#, rhs] | save
   *   }
   * }
   * </pre>
   */
  static class BinaryUpdateMatrix extends BuiltinMethod {
    static final Caller sizesLhs = new Caller("sizes:1", "afterSizesLhs");
    static final Caller sizesRhs = new Caller("sizes:1", "afterSizesRhs");
    static final Caller keys = new Caller("keys:1", "afterKeys");

    @Core.Method("binaryUpdate(Matrix, Lambda, _)")
    static void begin(TState tstate, @RC.In Value lhs, @RC.In Value lambda, @RC.In Value rhs) {
      rhs.isa(Core.MATRIX)
          .test(
              () -> tstate.startCall(sizesLhs, addRef(lhs)).saving(lhs, lambda, rhs),
              () -> tstate.startCall(keys, addRef(lhs)).saving(lhs, lambda, rhs));
    }

    @Continuation
    static void afterSizesLhs(
        TState tstate,
        @RC.In Value lhsSizes,
        @Saved @RC.In Value lhs,
        @RC.In Value lambda,
        @RC.In Value rhs) {
      tstate.startCall(sizesRhs, addRef(rhs)).saving(lhs, lambda, rhs, lhsSizes);
    }

    @Continuation(order = 2)
    static void afterSizesRhs(
        TState tstate,
        Value rhsSizes,
        @Saved @RC.In Value lhs,
        @RC.In Value lambda,
        @RC.In Value rhs,
        Value lhsSizes,
        @Fn("iterate:4") Caller iterate)
        throws BuiltinException {
      checkSizesMatch(tstate, lhsSizes, rhsSizes);
      Value loop = tstate.compound(BINARY_UPDATE_WITH_COLLECTION, lambda);
      tstate.startCall(iterate, rhs, LoopCore.ENUMERATE_ALL_KEYS, loop, lhs);
    }

    @Continuation(order = 3)
    static void afterKeys(
        TState tstate,
        @RC.In Value keys,
        @Saved @RC.In Value lhs,
        @RC.In Value lambda,
        @RC.In Value rhs,
        @Fn("iterate:4") Caller iterate) {
      Value loop = tstate.compound(BINARY_UPDATE_WITH_SCALAR, lambda, rhs);
      tstate.startCall(iterate, keys, LoopCore.ENUMERATE_VALUES, loop, lhs);
    }
  }

  /**
   * <pre>
   * method nextState(BinaryUpdateWithScalar loop, lhs, key) {
   *   { lambda, rhs } = loop_
   *   binaryUpdate(lhs@key=, lambda, rhs)
   *   return lhs
   * }
   * // Or equivalently:
   * method nextState(BinaryUpdateWithScalar loop, lhs, key) {
   *   element = startUpdate(lhs=, key)
   *   { lambda, rhs } = loop_
   *   binaryUpdate(element=, lambda, rhs)
   *   return lhs@element
   * }
   * </pre>
   */
  static class BinaryUpdateWithScalar extends BuiltinMethod {
    static final Caller startUpdate = new Caller("startUpdate:2", "afterStartUpdate");
    static final Caller binaryUpdate = new Caller("binaryUpdate:3", "afterBinaryUpdate");

    @Core.Method("nextState(BinaryUpdateWithScalar, _, _)")
    static void begin(TState tstate, @RC.In Value loop, @RC.In Value lhs, @RC.In Value key) {
      tstate.startCall(startUpdate, lhs, key).saving(loop);
    }

    @Continuation
    static void afterStartUpdate(
        TState tstate, @RC.In Value element, @RC.In Value lhsUpdater, @Saved Value loop) {
      Value lambda = loop.element(0);
      Value rhs = loop.element(1);
      tstate.startCall(binaryUpdate, element, lambda, rhs).saving(lhsUpdater);
    }

    @Continuation(order = 2)
    static void afterBinaryUpdate(
        TState tstate,
        @RC.In Value element,
        @Saved @RC.In Value lhsUpdater,
        @Fn("at:2") Caller at) {
      tstate.startCall(at, lhsUpdater, element);
    }
  }

  /**
   * <pre>
   * method nextState(BinaryUpdateWithCollection loop, lhs, [key, rhsElement]) {
   *   element = startUpdate(lhs=, key)
   *   // Skip calling the lambda if both elements are Absent, to be consistent with join()
   *   if element is not Absent or rhsElement is not Absent {
   *     binaryUpdate(element=, loop_, rhsElement)
   *   }
   *   return lhs@element
   * }
   * </pre>
   */
  static class BinaryUpdateWithCollection extends BuiltinMethod {
    static final Caller startUpdate = new Caller("startUpdate:2", "afterStartUpdate");
    static final Caller binaryUpdate = new Caller("binaryUpdate:3", "afterBinaryUpdate");

    @Core.Method("nextState(BinaryUpdateWithCollection, _, _)")
    static void begin(TState tstate, @RC.In Value loop, @RC.In Value lhs, Value pair)
        throws BuiltinException {
      Err.NOT_PAIR.unless(pair.isArrayOfLength(2));
      Value key = pair.element(0);
      Value rhsElement = pair.element(1);
      tstate.startCall(startUpdate, lhs, key).saving(loop, rhsElement);
    }

    @Continuation
    static void afterStartUpdate(
        TState tstate,
        @RC.In Value element,
        @RC.In Value lhsUpdater,
        @Saved Value loop,
        @RC.In Value rhsElement) {
      element
          .is(Core.ABSENT)
          .and(rhsElement.is(Core.ABSENT))
          .test(
              () -> tstate.jump("afterBinaryUpdate", Core.ABSENT, lhsUpdater),
              () ->
                  tstate
                      .startCall(binaryUpdate, element, loop.element(0), rhsElement)
                      .saving(lhsUpdater));
    }

    @Continuation(order = 2)
    static void afterBinaryUpdate(
        TState tstate,
        @RC.In Value element,
        @Saved @RC.In Value lhsUpdater,
        @Fn("at:2") Caller at) {
      tstate.startCall(at, lhsUpdater, element);
    }
  }

  /**
   * <pre>
   * method joinAndPipe(Matrix m1, Matrix m2, Lambda lambda) = join(m1, m2) | lambda
   * </pre>
   */
  static class JoinAndPipe extends BuiltinMethod {
    static final Caller join = new Caller("join:2", "afterJoin");

    @Core.Method("joinAndPipe(Matrix, Matrix, Lambda)")
    static void begin(TState tstate, @RC.In Value m1, @RC.In Value m2, @RC.In Value lambda) {
      tstate.startCall(join, m1, m2).saving(lambda);
    }

    @Continuation
    static Value afterJoin(TState tstate, @RC.In Value joined, @Saved @RC.In Value lambda) {
      return tstate.compound(CollectionCore.TRANSFORMED_MATRIX, joined, lambda);
    }
  }

  /**
   * A wrapper for a binary VmFunction that supports mapping it over a Matrix with a constant left
   * or right argument.
   *
   * <p>Calls to these functions with a Matrix as one argument and a Number as the other are
   * implicitly distributed, but the usual implementation of distributed operations (where the
   * compiler defines a new lambda type) would be awkward here, so instead we create
   * PartialApplication baseTypes (which were defined just to support this use).
   */
  private enum BinaryOp {
    ADD(Core.add.fn()),
    SUBTRACT(Core.subtract.fn()),
    MULTIPLY(Core.multiply.fn()),
    DIVIDE(Core.divide.fn()),
    MODULO(Core.modulo.fn()),
    EXPONENT(Core.exponent.fn()),
    ELEMENT(element.fn());

    /**
     * A single-element Lambda baseType whose {@code at(self, arg)} method calls {@code op(self_,
     * arg)}.
     */
    final BaseType partialLeft;

    /**
     * A single-element Lambda baseType whose {@code at(self, arg)} method calls {@code op(arg,
     * self_)}.
     */
    final BaseType partialRight;

    BinaryOp(VmFunction function) {
      partialLeft = function.partialApplication(1);
      partialRight = function.partialApplication(0);
    }

    /** Returns {@code fn(^m, rightValue)} (aka {@code m | # -> function(#, rightValue)}). */
    @RC.Out
    Value applyMatrixLeft(TState tstate, @RC.In Value m, @RC.In Value rightValue) {
      return tstate.compound(
          CollectionCore.TRANSFORMED_MATRIX, m, tstate.compound(partialRight, rightValue));
    }

    /** Returns {@code fn(leftValue, ^m)} (aka {@code m | # -> function(leftValue, #)}). */
    @RC.Out
    Value applyMatrixRight(TState tstate, @RC.In Value m, @RC.In Value leftValue) {
      return tstate.compound(
          CollectionCore.TRANSFORMED_MATRIX, m, tstate.compound(partialLeft, leftValue));
    }
  }

  /**
   * <pre>
   * method negative(Matrix m) = m | -&gt; -#
   * </pre>
   */
  @Core.Method("negative(Matrix)")
  static Value negativeMatrix(TState tstate, @RC.In Value m) {
    return tstate.compound(
        CollectionCore.TRANSFORMED_MATRIX, m, NumberCore.negative.asLambdaExpr());
  }

  /**
   * <pre>
   * method add(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x + y)  // x ^+^ y
   * </pre>
   */
  @Core.Method("add(Matrix, Matrix)")
  static void addMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.add.asLambdaExpr());
  }

  /**
   * <pre>
   * method add(Matrix m, Number x) = m | -&gt; # + x
   * </pre>
   */
  @Core.Method("add(Matrix, Number)")
  static Value addMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return BinaryOp.ADD.applyMatrixLeft(tstate, m, x);
  }

  /**
   * <pre>
   * method add(Number x, Matrix m) = m | -&gt; x + #
   * </pre>
   */
  @Core.Method("add(Number, Matrix)")
  static Value addNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return BinaryOp.ADD.applyMatrixRight(tstate, m, x);
  }

  /**
   * <pre>
   * method subtract(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x - y)  // x ^-^ y
   * </pre>
   */
  @Core.Method("subtract(Matrix, Matrix)")
  static void subtractMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.subtract.asLambdaExpr());
  }

  /**
   * <pre>
   * method subtract(Matrix m, Number x) = m | -&gt; # - x
   * </pre>
   */
  @Core.Method("subtract(Matrix, Number)")
  static Value subtractMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return BinaryOp.SUBTRACT.applyMatrixLeft(tstate, m, x);
  }

  /**
   * <pre>
   * method subtract(Number x, Matrix m) = m | -&gt; x - #
   * </pre>
   */
  @Core.Method("subtract(Number, Matrix)")
  static Value subtractNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return BinaryOp.SUBTRACT.applyMatrixRight(tstate, m, x);
  }

  /**
   * <pre>
   * method multiply(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x * y)  // x ^*^ y
   * </pre>
   */
  @Core.Method("multiply(Matrix, Matrix)")
  static void multiplyMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.multiply.asLambdaExpr());
  }

  /**
   * <pre>
   * method multiply(Matrix m, Number x) = m | -&gt; # * x
   * </pre>
   */
  @Core.Method("multiply(Matrix, Number)")
  static Value multiplyMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return BinaryOp.MULTIPLY.applyMatrixLeft(tstate, m, x);
  }

  /**
   * <pre>
   * method multiply(Number x, Matrix m) = m | -&gt; x * #
   * </pre>
   */
  @Core.Method("multiply(Number, Matrix)")
  static Value multiplyNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return BinaryOp.MULTIPLY.applyMatrixRight(tstate, m, x);
  }

  /**
   * <pre>
   * method divide(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x / y)  // x ^/^ y
   * </pre>
   */
  @Core.Method("divide(Matrix, Matrix)")
  static void divideMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.divide.asLambdaExpr());
  }

  /**
   * <pre>
   * method divide(Matrix m, Number x) = m | -&gt; # / x
   * </pre>
   */
  @Core.Method("divide(Matrix, Number)")
  static Value divideMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return BinaryOp.DIVIDE.applyMatrixLeft(tstate, m, x);
  }

  /**
   * <pre>
   * method divide(Number x, Matrix m) = m | -&gt; x / #
   * </pre>
   */
  @Core.Method("divide(Number, Matrix)")
  static Value divideNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return BinaryOp.DIVIDE.applyMatrixRight(tstate, m, x);
  }

  /**
   * <pre>
   * method modulo(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x % y)  // x ^%^ y
   * </pre>
   */
  @Core.Method("modulo(Matrix, Matrix)")
  static void moduloMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.modulo.asLambdaExpr());
  }

  /**
   * <pre>
   * method modulo(Matrix m, Number x) = m | -&gt; # % x
   * </pre>
   */
  @Core.Method("modulo(Matrix, Number)")
  static Value moduloMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return BinaryOp.MODULO.applyMatrixLeft(tstate, m, x);
  }

  /**
   * <pre>
   * method modulo(Number x, Matrix m) = m | -&gt; x % #
   * </pre>
   */
  @Core.Method("modulo(Number, Matrix)")
  static Value moduloNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return BinaryOp.MODULO.applyMatrixRight(tstate, m, x);
  }

  /**
   * <pre>
   * method exponent(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x ** y)  // x ^**^ y
   * </pre>
   */
  @Core.Method("exponent(Matrix, Matrix)")
  static void exponentMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.exponent.asLambdaExpr());
  }

  /**
   * <pre>
   * method exponent(Matrix m, Number x) = m | -&gt; # ** x
   * </pre>
   */
  @Core.Method("exponent(Matrix, Number)")
  static Value exponentMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return BinaryOp.EXPONENT.applyMatrixLeft(tstate, m, x);
  }

  /**
   * <pre>
   * method exponent(Number x, Matrix m) = m | -&gt; x ** #
   * </pre>
   */
  @Core.Method("exponent(Number, Matrix)")
  static Value exponentNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return BinaryOp.EXPONENT.applyMatrixRight(tstate, m, x);
  }

  private MatrixCore() {}
}
