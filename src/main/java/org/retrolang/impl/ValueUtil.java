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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeValue;
import org.retrolang.code.CodeValue.Const;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.TestBlock;
import org.retrolang.code.ValueInfo;
import org.retrolang.util.ArrayUtil;

/** A static-only class with simple value operations that work equally well with RValues. */
public class ValueUtil {
  private ValueUtil() {}

  /** Returns the same value as {@link Value#numElements}, but as a transient Value. */
  public static Value numElements(TState tstate, Value v) {
    if (v instanceof RValue) {
      CodeGen codeGen = tstate.codeGen();
      v = codeGen.simplify(v);
      if (v instanceof RValue rValue) {
        Template t = rValue.template;
        BaseType baseType = t.baseType();
        if (baseType.isCompositional()) {
          return NumValue.of(baseType.size(), Allocator.UNCOUNTED);
        } else {
          return codeGen.intToValue(codeGen.vArrayLength((Template.RefVar) t));
        }
      }
    }
    return NumValue.of(v.numElements(), Allocator.TRANSIENT);
  }

  /**
   * Returns the element of {@code array} identified by {@code index}, which may be zero-based or
   * one-based.
   */
  @RC.Out
  public static Value element(TState tstate, Value array, Value index, int firstIndex) {
    assert array.baseType().isArray() || array.baseType().isCompositional();
    assert firstIndex == 0 || (array.baseType().isArray() && firstIndex == 1);
    if (!(index instanceof RValue)) {
      return array.element(NumValue.asInt(index) - firstIndex);
    }
    CodeGen codeGen = tstate.codeGen();
    index = codeGen.simplify(index);
    if (!(index instanceof RValue)) {
      return array.element(NumValue.asInt(index) - firstIndex);
    }
    BaseType baseType = array.baseType();
    if (baseType.isCompositional() && baseType.size() == 1) {
      return array.element(0);
    }
    FrameLayout layout = array.layout();
    Template t = RValue.toTemplate(array);
    if (t instanceof Template.RefVar refVar) {
      codeGen.ensureLayout(codeGen.register(refVar), layout);
    }
    // Create an RValue that we'll store the result in.  array is usually a varray, which makes this
    // easy (we can just use the element template); but if it's not we need to construct a template
    // that could hold any of the elements.
    TemplateBuilder elementBuilder;
    if (baseType == Core.VARRAY) {
      elementBuilder = layout.template().toBuilder();
    } else if (!(array instanceof RValue)) {
      // Picking an element from a constant array.
      elementBuilder = Template.EMPTY.addElements(array);
    } else {
      // t is either a Compound or a RefVar with a RecordLayout
      Template.Compound compound = (Template.Compound) (layout == null ? t : layout.template());
      elementBuilder = compound.mergeElementsInto(Template.EMPTY);
    }
    int registerStart = codeGen.cb.numRegisters();
    Template result = elementBuilder.build(codeGen.newAllocator());
    int registerEnd = codeGen.cb.numRegisters();
    CodeValue indexCV = codeGen.register(index);
    if (baseType == Core.VARRAY) {
      Template.RefVar refVar = (Template.RefVar) t;
      Register arrayCV = codeGen.register(refVar);
      if (firstIndex != 0) {
        indexCV =
            codeGen.materialize(
                Op.SUBTRACT_INTS.result(indexCV, CodeValue.of(firstIndex)), int.class);
      }
      CopyPlan plan = CopyPlan.create(layout.template(), result, false);
      plan = CopyOptimizer.toRegisters(plan, registerStart, registerEnd, result);
      ((VArrayLayout) layout).copyFrom(arrayCV, indexCV).emit(codeGen, plan, codeGen.escapeLink());
    } else {
      FutureBlock done = new FutureBlock();
      codeGen.emitSwitch(
          indexCV,
          firstIndex,
          firstIndex + baseType.size() - 1,
          i -> {
            codeGen.emitStore(array.element(i - firstIndex), result, registerStart, registerEnd);
            codeGen.cb.branchTo(done);
          });
      codeGen.cb.setNext(done);
    }
    return RValue.fromTemplate(result);
  }

  /**
   * Returns a copy of {@code array} with the specified element replaced; {@code index} may be
   * zero-based or one-based.
   */
  @RC.Out
  public static Value replaceElement(
      TState tstate, @RC.In Value array, Value index, int firstIndex, @RC.In Value newElement) {
    assert array.baseType().isArray() || array.baseType().isCompositional();
    assert firstIndex == 0 || (array.baseType().isArray() && firstIndex == 1);
    if (!(index instanceof RValue)) {
      return array.replaceElement(tstate, NumValue.asInt(index) - firstIndex, newElement);
    }
    CodeGen codeGen = tstate.codeGen();
    index = codeGen.simplify(index);
    if (!(index instanceof RValue)) {
      return array.replaceElement(tstate, NumValue.asInt(index) - firstIndex, newElement);
    }
    CodeValue indexCV = codeGen.asCodeValue(index);
    FrameLayout layout = array.layout();
    Template t = RValue.toTemplate(array);
    if (t instanceof Template.RefVar refVar) {
      Register frame = codeGen.register(refVar);
      codeGen.ensureLayout(frame, layout);
      if (firstIndex != 0) {
        indexCV =
            codeGen.materialize(
                Op.SUBTRACT_INTS.result(indexCV, CodeValue.of(firstIndex)), int.class);
      }
      Register result = layout.emitReplaceElement(codeGen, frame, indexCV, newElement);
      if (result == frame) {
        return array;
      } else {
        return CodeGen.asValue(result, layout);
      }
    }
    Template newElementT = RValue.toTemplate(newElement);
    BaseType baseType = array.baseType();
    int size = baseType.size();
    if (size == 1) {
      return RValue.fromTemplate(Template.Compound.of(baseType, newElementT));
    }
    Template arrayT = RValue.toTemplate(array);
    CodeBuilder cb = codeGen.cb;
    if (newElement == Core.TO_BE_SET) {
      FutureBlock done = new FutureBlock();
      codeGen.emitSwitch(
          indexCV,
          firstIndex,
          firstIndex + size - 1,
          i -> {
            clear(codeGen, arrayT.element(i - firstIndex));
            cb.branchTo(done);
          });
      cb.setNext(done);
      return array;
    }
    Template[] elements = new Template[size];
    Arrays.setAll(elements, arrayT::element);
    Template[] merged = Arrays.copyOf(elements, size);
    int[] registerStart = new int[size + 1];
    registerStart[0] = cb.numRegisters();
    TemplateBuilder.VarAllocator alloc = codeGen.newAllocator();
    // First allocate new registers to hold each of the elements that might be changed
    ValueInfo info = cb.nextInfoResolved(((Register) indexCV).index);
    for (int i = 0; i < size; i++) {
      if (info.containsValue(firstIndex + i)) {
        merged[i] = elements[i].toBuilder().merge(newElementT.toBuilder()).build(alloc);
      }
      registerStart[i + 1] = cb.numRegisters();
    }
    // Then initialize those registers assuming no change
    for (int i = 0; i < size; i++) {
      if (merged[i] != elements[i]) {
        codeGen.emitStore(elements[i], merged[i], registerStart[i], registerStart[i + 1]);
      }
    }
    // Then switch on the index register and change the corresponding registers
    FutureBlock done = new FutureBlock();
    codeGen.emitSwitch(
        indexCV,
        firstIndex,
        firstIndex + size - 1,
        ii -> {
          int i = ii - firstIndex;
          if (merged[i] != elements[i]) {
            codeGen.emitStore(newElementT, merged[i], registerStart[i], registerStart[i + 1]);
          } else {
            assert newElementT instanceof Template.Constant && merged[i].equals(newElementT);
          }
          cb.branchTo(done);
        });
    cb.setNext(done);
    return RValue.fromTemplate(Template.Compound.of(baseType, merged));
  }

  /** Emits blocks to clear each of the pointer-valued registers in {@code t}. */
  private static void clear(CodeGen codeGen, Template t) {
    // If t contains a Union we may end up emitting multiple instructions that set the same Register
    // to null.  That's OK; the optimizer will clean it up (all but the last will be removed since
    // they're setting a register that's never read).  I don't expect that to have often enough to
    // be worth the (minor) hassle that would be involved in not emitting them in the first place.
    Template.visitVars(t, refVar -> codeGen.emitSet(codeGen.register(refVar), CodeValue.NULL));
  }

  /** For use in place of {@link Value#numElements} when {@code array} might be an RValue. */
  public static CodeValue numElementsAsCodeValue(CodeGen codeGen, Value array) {
    array = codeGen.simplify(array);
    BaseType baseType = array.baseType();
    if (baseType.isCompositional()) {
      return CodeValue.of(baseType.size());
    } else if (array instanceof Frame) {
      return CodeValue.of(array.numElements());
    } else {
      return codeGen.vArrayLength(array);
    }
  }

  /** Errors unless {@code index} is an int in {@code 0..size(array)-1}. */
  public static void checkIndex(TState tstate, Value array, Value index)
      throws Err.BuiltinException {
    if (!(array instanceof RValue || index instanceof RValue)) {
      int i = NumValue.asIntOrMinusOne(index);
      Err.INVALID_ARGUMENT.unless(i >= 0 && i < array.numElements());
    } else {
      CodeGen codeGen = tstate.codeGen();
      CodeValue i = codeGen.verifyInt(index);
      CodeValue size = numElementsAsCodeValue(codeGen, array);
      codeGen.escapeUnless(
          Condition.intLessOrEq(CodeValue.ZERO, i).and(Condition.intLessThan(i, size)));
    }
  }

  /** Throws {@code err} unless {@code v} is an array of non-negative integers. */
  public static void checkSizes(Value v, Err err) throws Err.BuiltinException {
    err.unless(v.isa(Core.ARRAY));
    if (!(v instanceof RValue)) {
      int size = v.numElements();
      for (int i = 0; i < size; i++) {
        err.unless(v.elementAsIntOrMinusOne(i) >= 0);
      }
    } else {
      BaseType baseType = v.baseType();
      if (baseType == Core.VARRAY) {
        throw new UnsupportedOperationException();
      }
      int size = baseType.size();
      for (int i = 0; i < size; i++) {
        Value e = v.peekElement(i).verifyInt(err);
        err.unless(Condition.numericLessOrEq(NumValue.ZERO, e));
      }
    }
  }

  /**
   * Returns {@code v} as an integer between or {@code min} and {@code max} (inclusive) or throws an
   * INVALID_ARGUMENT BuiltinException. {@code min} and {@code max} must be integers.
   */
  @RC.Out
  public static Value verifyBoundedInt(TState tstate, Value v, Value min, Value max)
      throws Err.BuiltinException {
    if (v instanceof RValue || min instanceof RValue || max instanceof RValue) {
      v = v.verifyInt(Err.INVALID_ARGUMENT);
      CodeGen codeGen = tstate.codeGen();
      codeGen.escapeUnless(Condition.numericLessOrEq(min, v));
      codeGen.escapeUnless(Condition.numericLessOrEq(v, max));
      return v;
    }
    return NumValue.verifyBoundedInt(v, NumValue.asInt(min), NumValue.asInt(max))
        .makeStorable(tstate);
  }

  /** Returns the sum of two integers; does not check for overflow. */
  @RC.Out
  public static Value addInts(TState tstate, Value v1, Value v2) {
    return IntOp.ADD.apply(tstate, v1, v2);
  }

  /** Returns the difference of two integers; does not check for overflow. */
  @RC.Out
  public static Value subtractInts(TState tstate, Value v1, Value v2) {
    return IntOp.SUBTRACT.apply(tstate, v1, v2);
  }

  /** Returns one more than the difference of two integers; does not check for overflow. */
  @RC.Out
  public static Value rangeSize(TState tstate, Value min, Value max) {
    return IntOp.RANGE_SIZE.apply(tstate, min, max);
  }

  /** Returns one less than the sum of two integers; does not check for overflow. */
  @RC.Out
  public static Value oneBasedOffset(TState tstate, Value v1, Value v2) {
    return IntOp.ONE_BASED_OFFSET.apply(tstate, v1, v2);
  }

  /** A simple binary operation over integers. */
  private enum IntOp {
    ADD {
      @Override
      int applyToInts(int i1, int i2) {
        return i1 + i2;
      }

      @Override
      CodeValue applyToCodeValues(CodeValue cv1, CodeValue cv2) {
        return Op.ADD_INTS.result(cv1, cv2);
      }
    },
    SUBTRACT {
      @Override
      int applyToInts(int i1, int i2) {
        return i1 - i2;
      }

      @Override
      CodeValue applyToCodeValues(CodeValue cv1, CodeValue cv2) {
        return Op.SUBTRACT_INTS.result(cv1, cv2);
      }
    },
    RANGE_SIZE {
      @Override
      int applyToInts(int min, int max) {
        return max - min + 1;
      }

      @Override
      CodeValue applyToCodeValues(CodeValue min, CodeValue max) {
        return Op.ADD_INTS.result(Op.SUBTRACT_INTS.result(max, min), CodeValue.ONE);
      }
    },
    ONE_BASED_OFFSET {
      @Override
      int applyToInts(int i1, int i2) {
        return i1 + i2 - 1;
      }

      @Override
      CodeValue applyToCodeValues(CodeValue cv1, CodeValue cv2) {
        return Op.SUBTRACT_INTS.result(Op.ADD_INTS.result(cv1, cv2), CodeValue.ONE);
      }
    };

    abstract int applyToInts(int i1, int i2);

    abstract CodeValue applyToCodeValues(CodeValue cv1, CodeValue cv2);

    @RC.Out
    Value apply(TState tstate, Value v1, Value v2) {
      if (v1 instanceof RValue || v2 instanceof RValue) {
        CodeGen codeGen = tstate.codeGen();
        v1 = codeGen.simplify(v1);
        v2 = codeGen.simplify(v2);
        if (v1 instanceof RValue || v2 instanceof RValue) {
          CodeValue cv1 = codeGen.asCodeValue(v1);
          CodeValue cv2 = codeGen.asCodeValue(v2);
          return codeGen.intToValue(applyToCodeValues(cv1, cv2));
        }
      }
      int i1 = NumValue.asInt(v1);
      int i2 = NumValue.asInt(v2);
      return NumValue.of(applyToInts(i1, i2), tstate);
    }
  }

  /**
   * If {@code sizes} is an Array of non-negative integers and their product is an int, returns it;
   * otherwise throws the specified error.
   */
  @RC.Out
  public static Value sizeFromSizes(TState tstate, Value sizes, Err err)
      throws Err.BuiltinException {
    err.unless(sizes.isa(Core.ARRAY));
    BaseType baseType = sizes.baseType();
    if (baseType == Core.VARRAY) {
      throw new UnsupportedOperationException();
    }
    int nDims = baseType.size();
    if (nDims == 0) {
      return NumValue.ONE;
    } else if (nDims == 1) {
      Value result = sizes.peekElement(0).verifyInt(err);
      err.when(Condition.numericLessThan(result, NumValue.ZERO));
      return result.makeStorable(tstate);
    }
    if (!(sizes instanceof RValue)) {
      int result = ArrayUtil.productAsInt(sizes::elementAsIntOrMinusOne, nDims);
      err.unless(result >= 0);
      return NumValue.of(result, tstate);
    }
    CodeGen codeGen = tstate.codeGen();
    int constPart = 1;
    List<CodeValue> elements = new ArrayList<>();
    for (int i = 0; i < nDims; i++) {
      Value element = codeGen.simplify(sizes.peekElement(i));
      if (!(element instanceof RValue)) {
        int asI = NumValue.asIntOrMinusOne(element);
        err.unless(asI >= 0);
        if (asI == 0 || constPart > 0) {
          constPart = ArrayUtil.multiplyExactOrMinusOne(constPart, asI);
        }
      } else {
        CodeValue cv = codeGen.verifyInt(element);
        codeGen.escapeWhen(Condition.intLessThan(cv, CodeValue.ZERO));
        elements.add(cv);
      }
    }
    if (elements.isEmpty() || constPart == 0) {
      err.unless(constPart >= 0);
      return NumValue.of(constPart, tstate);
    }
    Register result = codeGen.cb.newRegister(int.class);
    FutureBlock done = null;
    if (elements.size() > 2) {
      FutureBlock isZero = new FutureBlock();
      for (int i = 2; i < elements.size(); i++) {
        new TestBlock.IsEq(OpCodeType.INT, elements.get(i), CodeValue.ZERO)
            .setBranch(true, isZero)
            .addTo(codeGen.cb);
      }
      FutureBlock nonZero = codeGen.cb.swapNext(isZero);
      codeGen.emitSet(result, CodeValue.ZERO);
      done = codeGen.cb.swapNext(nonZero);
    }
    CodeValue product = elements.get(0);
    for (int i = 1; i < elements.size(); i++) {
      product = Op.MULTIPLY_INTS_EXACT.result(product, elements.get(i));
    }
    if (constPart != 1) {
      assert constPart > 1;
      product = Op.MULTIPLY_INTS_EXACT.result(product, CodeValue.of(constPart));
    }
    codeGen.emitSetCatchingArithmeticException(result, product);
    if (done != null) {
      codeGen.cb.mergeNext(done);
    }
    return codeGen.toValue(result);
  }

  /** All elements of {@code array} must be numbers; true if any of them is zero. */
  public static Condition containsZero(Value array) {
    assert array.baseType().isArray();
    BaseType baseType = array.baseType();
    if (baseType == Core.VARRAY) {
      throw new UnsupportedOperationException();
    }
    int nDims = baseType.size();
    Condition result = Condition.FALSE;
    for (int i = 0; i < nDims; i++) {
      Value s = array.peekElement(i);
      if (s instanceof RValue) {
        result = result.or(Condition.numericEq(s, NumValue.ZERO));
      } else if (NumValue.equals(s, 0)) {
        return Condition.TRUE;
      }
    }
    return result;
  }

  /**
   * Given an array of sizes and a key, errors unless the key is valid for a matrix with those
   * sizes.
   */
  public static void checkKey(TState tstate, Value key, Value sizes) throws Err.BuiltinException {
    if (!(key instanceof RValue || sizes instanceof RValue)) {
      int nDims = sizes.numElements();
      Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(nDims));
      for (int i = 0; i < nDims; i++) {
        int ki = key.elementAsIntOrMinusOne(i);
        int si = sizes.elementAsInt(i);
        Err.INVALID_ARGUMENT.unless(ki >= 1 && ki <= si);
      }
      return;
    }
    CodeGen codeGen = tstate.codeGen();
    BaseType keyType = key.baseType();
    BaseType sizesType = sizes.baseType();
    if (keyType == Core.VARRAY && sizesType == Core.VARRAY) {
      throw new UnsupportedOperationException();
    }
    int nDims;
    if (keyType.isCompositional()) {
      nDims = keyType.size();
      Err.INVALID_ARGUMENT.unless(sizes.isArrayOfLength(nDims));
    } else {
      nDims = sizesType.size();
      Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(nDims));
    }
    for (int i = 0; i < nDims; i++) {
      CodeValue ki = codeGen.verifyInt(key.peekElement(i));
      CodeValue si = codeGen.verifyInt(sizes.peekElement(i));
      codeGen.escapeUnless(Condition.intLessThan(CodeValue.ZERO, ki));
      codeGen.escapeUnless(Condition.intLessOrEq(ki, si));
    }
  }

  /**
   * Given an array of sizes and a key, returns the (zero-based) index of that key in a sequential
   * enumeration of a matrix with those sizes (as transient non-negative int). Errors if {@link
   * #checkKey} would error.
   */
  public static Value keyToIndex(TState tstate, Value key, Value sizes)
      throws Err.BuiltinException {
    if (!(key instanceof RValue || sizes instanceof RValue)) {
      int nDims = sizes.numElements();
      Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(nDims));
      int sum = 0;
      for (int i = 0; i < nDims; i++) {
        int ki = key.elementAsIntOrMinusOne(i);
        int si = sizes.elementAsInt(i);
        Err.INVALID_ARGUMENT.unless(ki >= 1 && ki <= si);
        try {
          sum = Math.addExact(Math.multiplyExact(sum, si), ki - 1);
        } catch (ArithmeticException e) {
          throw Err.INVALID_ARGUMENT.asException();
        }
      }
      return NumValue.of(sum, Allocator.TRANSIENT);
    }
    CodeGen codeGen = tstate.codeGen();
    BaseType keyType = key.baseType();
    BaseType sizesType = sizes.baseType();
    if (keyType == Core.VARRAY && sizesType == Core.VARRAY) {
      throw new UnsupportedOperationException();
    }
    int nDims;
    if (keyType.isCompositional()) {
      nDims = keyType.size();
      Err.INVALID_ARGUMENT.unless(sizes.isArrayOfLength(nDims));
    } else {
      nDims = sizesType.size();
      Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(nDims));
    }
    CodeValue sum = CodeValue.ZERO;
    for (int i = 0; i < nDims; i++) {
      CodeValue ki = codeGen.verifyInt(key.peekElement(i));
      CodeValue si = codeGen.verifyInt(sizes.peekElement(i));
      Err.INVALID_ARGUMENT.unless(Condition.intLessThan(CodeValue.ZERO, ki));
      Err.INVALID_ARGUMENT.unless(Condition.intLessOrEq(ki, si));
      ki = Op.SUBTRACT_INTS.result(ki, CodeValue.ONE);
      if (sum == CodeValue.ZERO) {
        sum = ki;
      } else {
        sum = Op.ADD_INTS_EXACT.result(Op.MULTIPLY_INTS_EXACT.result(sum, si), ki);
      }
    }
    return codeGen.toValue(codeGen.materializeCatchingArithmeticException(sum));
  }

  /**
   * Given an array of sizes and a (zero-based) index in the sequential enumeration of a matrix with
   * those sizes, returns the corresponding key (an array of int).
   */
  @RC.Out
  public static Value indexToKey(
      TState tstate, Value index, Value sizes, Supplier<Object> resultLayoutOrBaseType) {
    int nDims;
    BaseType baseType = sizes.baseType();
    if (baseType != Core.VARRAY) {
      nDims = baseType.size();
    } else if (!(sizes instanceof RValue)) {
      nDims = sizes.numElements();
    } else {
      Object frameLayoutOrBaseType = resultLayoutOrBaseType.get();
      if (frameLayoutOrBaseType instanceof BaseType) {
        nDims = ((BaseType) frameLayoutOrBaseType).size();
        tstate.codeGen().escapeUnless(sizes.isArrayOfLength(nDims));
      } else {
        throw new UnsupportedOperationException();
      }
    }
    if (nDims == 0) {
      return Core.EMPTY_ARRAY;
    }
    Object[] result = tstate.allocObjectArray(nDims);
    if (!(index instanceof RValue || sizes instanceof RValue)) {
      int t = NumValue.asInt(index);
      for (int i = nDims - 1; i > 0; i--) {
        int si = sizes.elementAsInt(i);
        result[i] = NumValue.of((t % si) + 1, tstate);
        t /= si;
      }
      result[0] = NumValue.of(t + 1, tstate);
    } else {
      CodeGen codeGen = tstate.codeGen();
      CodeValue t = codeGen.asCodeValue(index);
      for (int i = nDims - 1; i > 0; i--) {
        t = codeGen.materialize(t, int.class);
        CodeValue si = codeGen.asCodeValue(sizes.peekElement(i));
        result[i] =
            codeGen.intToValue(Op.ADD_INTS.result(Op.MOD_INTS.result(t, si), CodeValue.ONE));
        t = Op.DIV_INTS.result(t, si);
      }
      result[0] = codeGen.intToValue(Op.ADD_INTS.result(t, CodeValue.ONE));
    }
    return tstate.asArrayValue(result, nDims);
  }

  /** Adds one element at the end of the given array. */
  @RC.Out
  public static Value appendElement(
      TState tstate, @RC.In Value array, @RC.In Value newElement, FrameLayout resultLayout)
      throws Err.BuiltinException {
    return insertElements(tstate, array, true, resultLayout, newElement);
  }

  /**
   * A CopyEmitter that just discards the values, used to pre-test whether the source can be stored
   * in a given template.
   */
  private static final CopyEmitter TEST_STORE =
      new CopyEmitter() {
        @Override
        void setDstVar(CodeGen codeGen, Template t, CodeValue v) {
          // do nothing
        }
      };

  /** Adds elements at the beginning or end of the given array. */
  @RC.Out
  public static Value insertElements(
      TState tstate,
      @RC.In Value array,
      boolean atEnd,
      FrameLayout resultLayout,
      @RC.In Value... newElements)
      throws Err.BuiltinException {
    if (newElements.length == 0) {
      return array;
    }
    if (array.baseType().isCompositional()) {
      int size = array.baseType().size();
      int newSize = size + newElements.length;
      if (size == 0 || newSize <= 5) {
        Object[] newArray = tstate.allocObjectArray(newSize);
        int offset = atEnd ? 0 : newElements.length;
        for (int i = 0; i < size; i++) {
          newArray[i + offset] = array.element(i);
        }
        tstate.dropValue(array);
        System.arraycopy(newElements, 0, newArray, atEnd ? size : 0, newElements.length);
        return tstate.asArrayValue(newArray, newSize);
      }
    }
    Value numAdded = NumValue.of(newElements.length, Allocator.TRANSIENT);
    Value size = tstate.getArraySizeAndReserveForChange(array, numAdded, null);
    boolean preChecked = false;
    if (tstate.hasCodeGen() && resultLayout instanceof VArrayLayout vLayout) {
      // We'd like to expand the varray and then copy the new elements into it.  If it is possible
      // that one of the newElements might not fit into the varray, the emitted code will save
      // the state at the beginning of this step for a possible escape -- including the original
      // value of array, which will prevent us from expanding it in place.  To avoid that we
      // need to test that each newElement fits *before* we expand array.
      CodeGen codeGen = tstate.codeGen();
      Template elementTemplate = vLayout.template;
      for (int i = 0; i < newElements.length; i++) {
        Template newTemplate = RValue.toTemplate(newElements[i]);
        if (!newTemplate.toBuilder()
            .isSubsetOf(elementTemplate.toBuilder(), TemplateBuilder.TestOption.DROP_TO_BE_SET)) {
          // Try copying this element into the varray's template, but with an emitter that doesn't
          // actually store the values (but will escape if they don't fit).
          CopyPlan plan = CopyPlan.create(newTemplate, elementTemplate, true);
          plan = CopyOptimizer.toVArray(plan, vLayout);
          TEST_STORE.emit(codeGen, plan, codeGen.escapeLink());
        }
      }
      preChecked = true;
    }
    Value keepPrefix = atEnd ? size : NumValue.ZERO;
    array = ValueUtil.removeRange(tstate, array, keepPrefix, NumValue.ZERO, numAdded, resultLayout);
    CodeGen.EscapeState prevEscape = null;
    if (preChecked) {
      CodeGen codeGen = tstate.codeGen();
      prevEscape = codeGen.escapeState();
      codeGen.setNewEscape(codeGen::emitAssertionFailed);
    }
    for (int i = 0; i < newElements.length; i++) {
      Value pos = NumValue.of(i, Allocator.TRANSIENT);
      if (atEnd) {
        pos = ValueUtil.addInts(tstate, pos, size);
      }
      array = replaceElement(tstate, array, pos, 0, newElements[i]);
      if (atEnd) {
        tstate.dropValue(pos);
      }
    }
    tstate.dropValue(size);
    if (prevEscape != null) {
      tstate.codeGen().restore(prevEscape);
    }
    return array;
  }

  /**
   * Adds the elements of {@code array2} (which must not be a varray) at the beginning or end of
   * {@code array} (which may be).
   */
  @RC.Out
  public static Value insertAllElements(
      TState tstate,
      @RC.In Value array,
      boolean atEnd,
      FrameLayout resultLayout,
      @RC.In Value array2)
      throws Err.BuiltinException {
    int n = array2.baseType().size();
    if (n == 0) {
      return array;
    }
    Value[] elements = new Value[n];
    Arrays.setAll(elements, array2::element);
    tstate.dropValue(array2);
    return insertElements(tstate, array, atEnd, resultLayout, elements);
  }

  /** Returns the concatenation of two arrays. */
  @RC.Out
  public static Value appendArrays(
      TState tstate, @RC.In Value array1, @RC.In Value array2, FrameLayout resultLayout)
      throws Err.BuiltinException {
    assert array1.baseType().isArray() && array2.baseType().isArray();
    if (array1 == Core.EMPTY_ARRAY) {
      return array2;
    } else if (array2.baseType().isCompositional()) {
      return insertAllElements(tstate, array1, true, resultLayout, array2);
    } else if (array1.baseType().isCompositional()) {
      return insertAllElements(tstate, array2, false, resultLayout, array1);
    }
    // Both are varrays
    Value size2 = numElements(tstate, array2);
    Value size1 = tstate.getArraySizeAndReserveForChange(array1, size2, null);
    array1 = ValueUtil.removeRange(tstate, array1, size1, NumValue.ZERO, size2, resultLayout);
    VArrayLayout layout1 = (VArrayLayout) array1.layout();
    VArrayLayout layout2 = (VArrayLayout) array2.layout();
    if (layout2.template.equals(layout1.template)) {
      array1 = layout1.fastCopyRange(tstate, array1, size1, array2, NumValue.ZERO, size2);
    } else if (tstate.hasCodeGen()) {
      CodeGen codeGen = tstate.codeGen();
      Register size1Cv = codeGen.register(size1);
      Register size2Cv = codeGen.register(size2);
      VArrayLayout dstLayout = (VArrayLayout) resultLayout;
      CodeValue dst = codeGen.register(array1);
      if (dstLayout == layout1) {
        dst = layout1.emitEnsureUnshared(codeGen, dst);
      } else {
        CodeValue size = Op.ADD_INTS_EXACT.result(size1Cv, size2Cv);
        size = codeGen.materializeCatchingArithmeticException(size);
        CodeValue newDst = codeGen.materialize(dstLayout.emitAlloc(codeGen, size), Frame.class);
        dstLayout.emitCopyRange(
            codeGen, layout1, newDst, CodeValue.ZERO, dst, CodeValue.ZERO, size1Cv);
        dst = newDst;
      }
      dstLayout.emitCopyRange(
          codeGen, layout2, dst, size1Cv, codeGen.register(array2), CodeValue.ZERO, size2Cv);
      return CodeGen.asValue((Register) dst, resultLayout);
    } else {
      int n1 = NumValue.asInt(size1);
      int n2 = NumValue.asInt(size2);
      for (int i = 0; i < n2; i++) {
        array1 = array1.replaceElement(tstate, i + n1, array2.element(i));
      }
    }
    tstate.dropValue(array2);
    tstate.dropValue(size1);
    return array1;
  }

  /**
   * Returns a varray containing
   *
   * <ul>
   *   <li>the first {@code keepPrefix} elements of {@code array}, followed by
   *   <li>{@code addSize} uninitialized elements, followed by
   *   <li>the elements of {@code array} from {@code keepPrefix+removeSize} to its end.
   * </ul>
   *
   * <p>This method is used to implement replaceElement (with a range index) and concatenation.
   *
   * <p>If {@code array} is not a Frame and {@code resultLayout} is non-null, it will be used for
   * the result (evolving it if necessary).
   *
   * <p>Will only throw an exception during codeGen (if caller should just escape).
   */
  @RC.Out
  public static Value removeRange(
      TState tstate,
      @RC.In Value array,
      Value keepPrefix,
      Value removeSize,
      Value addSize,
      FrameLayout resultLayout)
      throws Err.BuiltinException {
    if (!tstate.hasCodeGen()) {
      return FrameLayout.removeRange(
          tstate,
          array,
          NumValue.asInt(keepPrefix),
          NumValue.asInt(removeSize),
          NumValue.asInt(addSize),
          resultLayout);
    } else {
      CodeGen codeGen = tstate.codeGen();
      return removeRange(
          codeGen,
          array,
          codeGen.asCodeValue(keepPrefix),
          codeGen.asCodeValue(removeSize),
          codeGen.asCodeValue(addSize),
          resultLayout);
    }
  }

  /**
   * Generate code to implement {@link #removeRange(TState, Value, Value, Value, Value,
   * FrameLayout)}.
   */
  @RC.Out
  public static Value removeRange(
      CodeGen codeGen,
      @RC.In Value array,
      CodeValue keepPrefix,
      CodeValue removeSize,
      CodeValue addSize,
      FrameLayout resultLayout)
      throws Err.BuiltinException {
    if (!(resultLayout instanceof VArrayLayout layout)) {
      throw Err.ESCAPE.asException();
    }
    FrameLayout srcLayout = array.layout();
    if (srcLayout != null) {
      if (srcLayout != layout) {
        srcLayout = srcLayout.latest();
        layout = (VArrayLayout) layout.latest();
        Err.ESCAPE.unless(srcLayout == layout);
      }
      return CodeGen.asValue(
          layout.emitRemoveRange(
              codeGen, codeGen.asCodeValue(array), keepPrefix, removeSize, addSize),
          layout);
    }
    // Fixed size array in, varray out
    int inSize = array.baseType().size();
    CodeValue sizeDelta = Op.SUBTRACT_INTS.result(addSize, removeSize);
    CodeValue newSize = Op.ADD_INTS.result(Const.of(inSize), sizeDelta);
    Register result = codeGen.cb.newRegister(resultLayout.frameClass.javaClass);
    codeGen.emitSet(result, layout.emitAlloc(codeGen, newSize));
    int stop = (keepPrefix instanceof Const) ? keepPrefix.iValue() : -1;
    CodeValue cvResume = Op.ADD_INTS.result(keepPrefix, removeSize);
    int resume = (cvResume instanceof Const) ? cvResume.iValue() : -1;
    for (int i = 0; i < inSize; i++) {
      CodeValue cv = CodeValue.of(i);
      FutureBlock skipped = null;
      CodeValue offset;
      if (i < stop) {
        offset = CodeValue.ZERO;
      } else if (resume >= 0 && i >= resume) {
        offset = sizeDelta;
      } else if (stop >= 0 && resume >= 0) {
        continue;
      } else {
        FutureBlock ready = null;
        offset = null;
        if (stop < 0) {
          FutureBlock checkResume = new FutureBlock();
          offset = codeGen.cb.newRegister(int.class);
          new TestBlock.IsLessThan(OpCodeType.INT, cv, keepPrefix)
              .setBranch(false, checkResume)
              .addTo(codeGen.cb);
          codeGen.emitSet((Register) offset, CodeValue.ZERO);
          ready = codeGen.cb.swapNext(checkResume);
        }
        skipped = new FutureBlock();
        new TestBlock.IsLessThan(OpCodeType.INT, cv, cvResume)
            .setBranch(true, skipped)
            .addTo(codeGen.cb);
        if (offset == null) {
          offset = sizeDelta;
        } else {
          codeGen.emitSet((Register) offset, sizeDelta);
          codeGen.cb.mergeNext(ready);
        }
      }
      layout.emitSetElement(
          codeGen, result, Op.ADD_INTS.result(cv, offset), RValue.toTemplate(array.peekElement(i)));
      if (skipped != null) {
        codeGen.cb.mergeNext(skipped);
      }
    }
    return codeGen.toValue(result);
  }
}
