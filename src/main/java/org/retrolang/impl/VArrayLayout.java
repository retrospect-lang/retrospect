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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.ReturnBlock;
import org.retrolang.code.SetBlock;
import org.retrolang.code.TestBlock;
import org.retrolang.code.ValueInfo;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.TemplateBuilder.CompoundBase;
import org.retrolang.util.ArrayUtil;
import org.retrolang.util.Bits;
import org.retrolang.util.SizeOf;
import org.retrolang.util.StringUtil;

/**
 * Frames using this FrameLayout represent a Retrospect array using parallel Java arrays. Each
 * element of the Retrospect array is described by the template, with variable values supplied by
 * corresponding elements of the Java arrays.
 */
public class VArrayLayout extends FrameLayout {
  final Template template;

  /** Maps the index of each NumVar in {@link #template} to its NumEncoding. */
  private final NumEncoding[] nvEncodings;

  /**
   * The length of any pointer overflow array required, or zero if this layout's element arrays fit
   * in the chosen FrameClass.
   */
  final int overflowSize;

  /** A zero-length, immutable instance of this VArrayLayout. */
  final Frame empty;

  /**
   * The number of bytes required for an instance of this VArrayLayout, not counting the elements.
   */
  final long baseSize;

  /**
   * The number of additional bytes required for each element by an instance of this VArrayLayout.
   */
  final long perElementSize;

  /** One more than the maximum RefVar index in this layout's template. */
  final int numRV;

  /**
   * Either 1 (if numRV == 0 and all nvEncodings are UINT8), 8 (if nvEncodings contains at least one
   * FLOAT64) or 4 (otherwise).
   */
  final int maxVarSize;

  private final Choice numberChoice;

  /**
   * A {@link Frame.Replacement} that points to {@link #empty}; returned by {@link
   * Frame#getReplacement} for the empty instances of VArrayLayouts that have evolved to this.
   */
  final Frame.Replacement emptyReplacement;

  /**
   * An Op with arguments (TState, int size) that allocates and initializes a new instance. Built
   * lazily.
   */
  private Op allocOp;

  /**
   * An Op with arguments (TState, Frame, int start, int end) that clears all pointers associated
   * with elements between {@code start} (inclusive) and {@code end} (exclusive). Built lazily, and
   * only for layouts with at least one RefVar.
   */
  private Op clearElementsOp;

  /**
   * An Op with arguments (TState, Frame, int keepPrefix, int removeSize, int addSize) that
   * implements {@link #removeRange(TState, Frame, int, int, int)} for frames with this layout.
   * Built lazily.
   */
  private Op removeRangeOp;

  /**
   * An Op with arguments (Frame dst, int dstStart, Frame src, int srcStart, int count) that clears
   * all pointers associated with elements between {@code start} (inclusive) and {@code end}
   * (exclusive). Built lazily, and only for layouts with at least one RefVar.
   */
  private Op copyRangeOp;

  private static final VarHandle ALLOC_OP =
      Handle.forVar(MethodHandles.lookup(), VArrayLayout.class, "allocOp", Op.class);
  private static final VarHandle CLEAR_ELEMENTS_OP =
      Handle.forVar(MethodHandles.lookup(), VArrayLayout.class, "clearElementsOp", Op.class);
  private static final VarHandle REMOVE_RANGE_OP =
      Handle.forVar(MethodHandles.lookup(), VArrayLayout.class, "removeRangeOp", Op.class);
  private static final VarHandle COPY_RANGE_OP =
      Handle.forVar(MethodHandles.lookup(), VArrayLayout.class, "copyRangeOp", Op.class);

  /** Builds a template with the given builder, and creates a VArrayLayout using the result. */
  static VArrayLayout newFromBuilder(Scope scope, TemplateBuilder builder) {
    VarAllocator allocator = new VarAllocator();
    Template template = builder.build(allocator);
    VArrayLayout result = newWithAllocator(scope, template, allocator);
    scope.evolver.recordNewLayout(result);
    return result;
  }

  /**
   * Creates a VArrayLayout from a template and the allocator that was used to build it.
   *
   * <p>Should only be called directly by the Evolver; other callers should use {@link
   * #newFromBuilder}.
   */
  static VArrayLayout newWithAllocator(Scope scope, Template template, VarAllocator alloc) {
    int ptrSize = alloc.ptrSize();
    FrameClass frameClass = FrameClass.select(NUM_INTS, ptrSize);
    return new VArrayLayout(scope, frameClass, ptrSize, template, alloc.shared.nvEncodings());
  }

  /** Each varray Frame has a single int field, its length. */
  private static final int NUM_INTS = 1;

  /** The index of the length field (an int). */
  private static final int LENGTH_FIELD = 0;

  /** The byte offset of the length field (an int). */
  private static final int LENGTH_FIELD_OFFSET = 0;

  /** The index of the overflow field (a ptr). */
  private static final int OVERFLOW_FIELD = 0;

  private VArrayLayout(
      Scope scope,
      FrameClass frameClass,
      int ptrSize,
      Template template,
      NumEncoding[] nvEncodings) {
    super(scope, Core.VARRAY, frameClass, Math.min(ptrSize, frameClass.nPtrs));
    this.template = template;
    this.nvEncodings = nvEncodings;
    // Since ptrSize includes one array for each NumVar and one array for each RefVar,
    // (ptrSize - nvEncodings.length) is the number of RefVars.
    numRV = ptrSize - nvEncodings.length;
    overflowSize = (ptrSize <= frameClass.nPtrs) ? 0 : (ptrSize - (frameClass.nPtrs - 1));
    empty = frameClass.alloc(Allocator.UNCOUNTED);
    // Initialize the layout and length of our empty instance.  We can leave all the element arrays
    // null, since they'll never be accessed.
    empty.layoutOrReplacement = this;
    setNumElements(empty, 0);
    if (overflowSize != 0) {
      frameClass.setX(empty, OVERFLOW_FIELD, new Object[overflowSize]);
    }
    emptyReplacement = new Frame.Replacement(null, empty);
    baseSize =
        frameClass.byteSize
            + ptrSize * SizeOf.ARRAY_HEADER
            + (overflowSize == 0 ? 0 : SizeOf.array(overflowSize, SizeOf.PTR));
    long perElementSize = numRV * (long) SizeOf.PTR;
    int maxVarSize = (numRV == 0) ? 1 : 4;
    for (NumEncoding nvEncoding : nvEncodings) {
      perElementSize += nvEncoding.nBytes;
      maxVarSize = Math.max(maxVarSize, nvEncoding.nBytes);
    }
    this.perElementSize = perElementSize;
    this.maxVarSize = maxVarSize;
    this.numberChoice = Choice.forBaseType(Core.NUMBER, template);
  }

  @Override
  Template template() {
    return template;
  }

  @Override
  Template elementTemplate(int index) {
    return template;
  }

  @Override
  int numVarIndexLimit() {
    return nvEncodings.length;
  }

  @Override
  int refVarIndexLimit() {
    return numRV;
  }

  @Override
  FrameLayout addValueImpl(Value v) {
    return evolveTemplate(template.toBuilder().addElements(v));
  }

  @Override
  FrameLayout merge(CompoundBase compound) {
    return evolveTemplate(compound.mergeElementsInto(template.toBuilder()));
  }

  @Override
  FrameLayout evolveElement(int index, Value newElement) {
    return evolveTemplate(template.toBuilder().add(newElement));
  }

  @Override
  public FrameLayout evolveElements(int start, int end, Value newElement) {
    return (start < end) ? evolveElement(start, newElement) : this;
  }

  /** Caller is responsible for ensuring that newTemplate includes the current template. */
  FrameLayout evolveTemplate(TemplateBuilder newTemplate) {
    return (newTemplate == template) ? this : scope.evolver.evolve(this, newTemplate, true);
  }

  @Override
  long sizeOf(int numElements) {
    // This may overestimate a bit due to different allocation policies for different element sizes,
    // e.g. if numElements is 3 we'll allocate 8-element arrays for uint8 NumVars, but only
    // 4-element arrays for RefVars and int32 NumVars and 3-element arrays for float64 NumVars.
    // Since this is only used to make reservations I'm OK with small overestimates, but if we
    // really cared it wouldn't be hard to store a couple more sizes in the constructor and make
    // this slightly more complicated.
    return baseSize + perElementSize * TState.chooseCapacityBytes(numElements);
  }

  /**
   * Given a RefVar or NumVar in {@link #template}, returns the index used to identify the
   * corresponding Java array. NumVars will return indices between 0 (inclusive) and {@link
   * #numVarIndexLimit} (exclusive); RefVars will return indices between {@link #numVarIndexLimit}
   * (inclusive) and {@link #numVarIndexLimit}+{@link #numRV} (exclusive).
   */
  int varIndex(Object v) {
    return (v instanceof Template.NumVar nv)
        ? nv.index
        : ((Template.RefVar) v).index + numVarIndexLimit();
  }

  /**
   * Allocates a byte[] or Object[] of the appropriate size for the specified variable; see {@link
   * #varIndex} for the interpretation of {@code index}.
   */
  private Object allocElementArray(TState tstate, int index, int size) {
    if (index < numVarIndexLimit()) {
      return tstate.allocByteArray(size << nvEncodings[index].nBytesLog2);
    } else {
      return tstate.allocObjectArray(size);
    }
  }

  /** Returns a CodeValue equivalent to calling {@link #allocElementArray} with the given args. */
  private CodeValue emitAllocElementArray(CodeValue tstate, int index, CodeValue size) {
    Op op;
    if (index < numVarIndexLimit()) {
      op = TState.ALLOC_BYTE_ARRAY_OP;
      int shift = nvEncodings[index].nBytesLog2;
      if (shift != 0) {
        size = Op.SHIFT_LEFT_INT.result(size, CodeValue.of(shift));
      }
    } else {
      op = TState.ALLOC_OBJ_ARRAY_OP;
    }
    return op.result(tstate, size);
  }

  /**
   * Returns the index of the pointer field containing the first element array, i.e. 0 if there is
   * no overflow array, 1 if there is.
   */
  private int firstElementArray() {
    return (overflowSize == 0) ? 0 : 1;
  }

  @Override
  @RC.Out
  public Frame alloc(TState tstate, int size) {
    return alloc(tstate, size, null);
  }

  /**
   * Allocates a new Frame using this layout. If {@code omitElementArrays} is non-null, element
   * arrays with indices in that set will not be allocated and must be set by calls to {@link
   * #setElementArray}.
   */
  @RC.Out
  Frame alloc(TState tstate, int size, Bits omitElementArrays) {
    if (size == 0) {
      return empty;
    } else if (omitElementArrays == null) {
      // If we've already generated code for this layout, we might as well use it here.
      Op allocOp = allocOp(false);
      if (allocOp != null) {
        try {
          return (Frame) allocOp.mh.invokeExact(tstate, size);
        } catch (Throwable e) {
          throw new AssertionError(e);
        }
      }
    }
    Frame result = frameClass.alloc(tstate);
    result.layoutOrReplacement = this;
    setNumElements(result, size);
    // Each pointer field (except the first, if we're using an overflow array) contains an
    // element array.
    int start = firstElementArray();
    for (int i = start; i < nPtrs; i++) {
      if (omitElementArrays == null || !omitElementArrays.test(i - start)) {
        frameClass.setX(result, i, allocElementArray(tstate, i - start, size));
      }
    }
    if (overflowSize != 0) {
      Object[] overflow = tstate.allocObjectArray(overflowSize);
      frameClass.setX(result, OVERFLOW_FIELD, overflow);
      for (int i = 0; i < overflowSize; i++) {
        if (omitElementArrays == null || !omitElementArrays.test(i + nPtrs - 1)) {
          overflow[i] = allocElementArray(tstate, i + nPtrs - 1, size);
        }
      }
    }
    return result;
  }

  Op allocOp(boolean build) {
    Op op = (Op) ALLOC_OP.getAcquire(this);
    if (op != null || !build) {
      return op;
    }
    // Since there are currently no other places we want to synchronize on a FrameLayout, use it
    // to avoid races when constructing MethodHandles.  If we had some other reason to synchronize
    // on it we could just add a special-purpose lock object.
    synchronized (this) {
      if (this.allocOp != null) {
        return this.allocOp;
      }
      CodeBuilder cb = CodeGen.newCodeBuilder();
      Register tstate = cb.newArg(TState.class);
      Register size = cb.newArg(int.class);
      FutureBlock sizeNonZero = new FutureBlock();
      new TestBlock.IsEq(OpCodeType.INT, size, CodeValue.ZERO)
          .setBranch(false, sizeNonZero)
          .addTo(cb);
      new ReturnBlock(CodeValue.of(empty)).addTo(cb);
      cb.setNext(sizeNonZero);
      Register result = cb.newRegister(frameClass.javaClass);
      new SetBlock(result, frameClass.emitAlloc(tstate)).addTo(cb);
      Frame.SET_LAYOUT_OR_REPLACEMENT.block(result, CodeValue.of(this)).addTo(cb);
      frameClass.setIntField.get(LENGTH_FIELD).block(result, size).addTo(cb);
      int start = firstElementArray();
      for (int i = start; i < nPtrs; i++) {
        frameClass
            .setPtrField
            .get(i)
            .block(result, emitAllocElementArray(tstate, i - start, size))
            .addTo(cb);
      }
      if (overflowSize != 0) {
        Register overflow = cb.newRegister(Object[].class);
        new SetBlock(overflow, TState.ALLOC_OBJ_ARRAY_OP.result(tstate, CodeValue.of(overflowSize)))
            .addTo(cb);
        frameClass.setPtrField.get(OVERFLOW_FIELD).block(result, overflow).addTo(cb);
        for (int i = 0; i < overflowSize; i++) {
          RcOp.SET_OBJ_ARRAY_ELEMENT
              .block(overflow, CodeValue.of(i), emitAllocElementArray(tstate, i + nPtrs - 1, size))
              .addTo(cb);
        }
      }
      new ReturnBlock(result).addTo(cb);
      MethodHandle mh =
          cb.load("allocVArray_" + StringUtil.id(this), null, Frame.class, Handle.lookup);
      op = RcOp.forMethodHandle("alloc" + this, mh).resultIsRcOut().build();
      ALLOC_OP.setRelease(this, op);
      return op;
    }
  }

  public CodeValue emitAlloc(CodeGen codeGen, CodeValue size) {
    return allocOp(true).resultWithInfo(notSharedInfo, codeGen.tstateRegister(), size);
  }

  /**
   * Returns the byte[] or Object[] for the specified variable; see {@link #varIndex} for the
   * interpretation of {@code index}.
   */
  Object getElementArray(Frame f, int index) {
    // Skip over the overflow array, if present
    index += firstElementArray();
    if (index < nPtrs) {
      return frameClass.getX(f, index);
    } else {
      Object[] overflow = (Object[]) frameClass.getX(f, OVERFLOW_FIELD);
      return overflow[index - nPtrs];
    }
  }

  byte[] getElementArray(Frame f, Template.NumVar var) {
    assert nvEncodings[var.index] == var.encoding;
    return (byte[]) getElementArray(f, var.index);
  }

  Object[] getElementArray(Frame f, Template.RefVar var) {
    return (Object[]) getElementArray(f, var.index + numVarIndexLimit());
  }

  private CodeValue getElementArray(CodeValue frame, Template t) {
    int index = firstElementArray() + varIndex(t);
    if (index < nPtrs) {
      return frameClass.getPtrField.get(index).result(frame);
    } else {
      CodeValue overflow = frameClass.getPtrField.get(OVERFLOW_FIELD).result(frame);
      return Op.OBJ_ARRAY_ELEMENT.result(overflow, CodeValue.of(index - nPtrs));
    }
  }

  private CodeValue elementArray(CodeValue frame, Template t) {
    int index = firstElementArray() + varIndex(t);
    if (index < nPtrs) {
      return frameClass.getPtrField.get(index).result(frame);
    } else {
      CodeValue overflow = frameClass.getPtrField.get(OVERFLOW_FIELD).result(frame);
      return Op.OBJ_ARRAY_ELEMENT.result(overflow, CodeValue.of(index - nPtrs));
    }
  }

  /**
   * Sets the element array used for the specified variable; see {@link #varIndex} for the
   * interpretation of {@code index}.
   *
   * <p>Should only be used to complete the initialization of frames allocated by {@link
   * #alloc(TState, int, Bits)} with a non-empty {@code omitElementArrays}.
   */
  void setElementArray(Frame f, int index, Object array) {
    assert (index < numVarIndexLimit())
        ? ((byte[]) array).length >= numElements(f) * nvEncodings[index].nBytes
        : ((Object[]) array).length >= numElements(f);
    index += firstElementArray();
    if (index < nPtrs) {
      assert frameClass.getX(f, index) == null;
      frameClass.setX(f, index, array);
    } else {
      Object[] overflow = (Object[]) frameClass.getX(f, OVERFLOW_FIELD);
      assert overflow[index - nPtrs] == null;
      overflow[index - nPtrs] = array;
    }
  }

  /**
   * Clears some of the given frame's pointers to its element arrays; see {@link #varIndex} for the
   * interpretation of {@code indices}.
   *
   * <p>Used when a replacement frame is sharing some of the original frame's element arrays and we
   * are about to release the original frame; we need to ensure that the shared arrays are not
   * released along with it.
   */
  void clearElementArrays(Frame f, int[] indices) {
    for (int index : indices) {
      index += firstElementArray();
      if (index < nPtrs) {
        frameClass.setX(f, index, null);
      } else {
        Object[] overflow = (Object[]) frameClass.getX(f, OVERFLOW_FIELD);
        overflow[index - nPtrs] = null;
      }
    }
  }

  /**
   * Modifies a varray by removing some elements and inserting some number of TO_BE_SET elements in
   * their place. On return the array will contain
   *
   * <ul>
   *   <li>the first {@code keepPrefix} elements unchanged, followed by
   *   <li>{@code addSize} elements that are {@code TO_BE_SET}, followed by
   *   <li>the elements that previously started at {@code keepPrefix+removeSize} to the end.
   * </ul>
   *
   * <p>This method is used to implement replaceElement and concatenation.
   */
  @RC.Out
  Frame removeRange(TState tstate, @RC.In Frame f, int keepPrefix, int removeSize, int addSize) {
    assert f.layout() == this;
    // If we've already generated code for this layout, we might as well use it here.
    Op op = removeRangeOp(false);
    if (op != null) {
      try {
        return (Frame) op.mh.invokeExact(tstate, f, keepPrefix, removeSize, addSize);
      } catch (Throwable e) {
        throw new AssertionError(e);
      }
    }
    // If we haven't generated code just do it the slow way.
    Frame result;
    boolean copy;
    if (f.isNotShared()) {
      // Make the change in place
      result = f;
      copy = false;
    } else {
      result = frameClass.alloc(tstate);
      result.layoutOrReplacement = this;
      copy = true;
    }
    int size = numElements(f);
    int newSize = size + addSize - removeSize;
    assert newSize >= 0;
    setNumElements(result, newSize);
    int start = firstElementArray();
    for (int i = start; i < nPtrs; i++) {
      Object array = frameClass.getX(f, i);
      array =
          removeRangeFromElementArray(
              tstate, array, i - start, size, keepPrefix, removeSize, addSize, copy);
      frameClass.setX(result, i, array);
    }
    if (overflowSize != 0) {
      Object[] fOverflow = (Object[]) frameClass.getX(f, OVERFLOW_FIELD);
      Object[] resultOverflow;
      if (!copy) {
        resultOverflow = fOverflow;
      } else {
        resultOverflow = tstate.allocObjectArray(overflowSize);
        frameClass.setX(result, OVERFLOW_FIELD, resultOverflow);
      }
      for (int i = 0; i < overflowSize; i++) {
        Object array = fOverflow[i];
        array =
            removeRangeFromElementArray(
                tstate, array, i + nPtrs - 1, size, keepPrefix, removeSize, addSize, copy);
        resultOverflow[i] = array;
      }
    }
    if (copy) {
      tstate.dropReference(f);
    }
    return result;
  }

  Register emitRemoveRange(
      CodeGen codeGen,
      CodeValue frame,
      CodeValue keepPrefix,
      CodeValue removeSize,
      CodeValue addSize) {
    Register result = codeGen.cb.newRegister(Frame.class);
    codeGen.emitSet(
        result,
        removeRangeOp(true)
            .resultWithInfo(
                notSharedInfo, codeGen.tstateRegister(), frame, keepPrefix, removeSize, addSize));
    // Reusing an escape from before the call to removeRange would require keeping a reference
    // to frame, which would prevent us from ever making the update in place.
    codeGen.setPreferNewEscape();
    return result;
  }

  @Override
  @RC.Out
  Frame duplicate(TState tstate, Frame f) {
    assert f.layout() == this;
    int size = numElements(f);
    f.addRef();
    return removeRange(tstate, f, size, 0, 0);
  }

  @Override
  long bytesForDuplicate(Frame f) {
    assert f.layout() == this;
    return sizeOf(numElements(f));
  }

  private Object removeRangeFromElementArray(
      TState tstate,
      Object array,
      int index,
      int size,
      int keepPrefix,
      int removeSize,
      int addSize,
      boolean copy) {
    if (index < numVarIndexLimit()) {
      int bytesPerElementLog2 = nvEncodings[index].nBytesLog2;
      return tstate.removeRange(
          (byte[]) array,
          size << bytesPerElementLog2,
          keepPrefix << bytesPerElementLog2,
          removeSize << bytesPerElementLog2,
          addSize << bytesPerElementLog2,
          copy);
    } else {
      return tstate.removeRange((Object[]) array, size, keepPrefix, removeSize, addSize, copy);
    }
  }

  Op removeRangeOp(boolean build) {
    Op op = (Op) REMOVE_RANGE_OP.getAcquire(this);
    if (op != null || !build) {
      return op;
    }
    // See comment in allocOp() about this synchronization.
    synchronized (this) {
      if (this.removeRangeOp != null) {
        return this.removeRangeOp;
      }
      CodeBuilder cb = CodeGen.newCodeBuilder();
      Register tstate = cb.newArg(TState.class);
      Register frame = cb.newArg(Frame.class);
      Register keepPrefix = cb.newArg(int.class);
      Register removeSize = cb.newArg(int.class);
      Register addSize = cb.newArg(int.class);

      Register result = cb.newRegister(frameClass.javaClass);
      Register copy = cb.newRegister(int.class);
      {
        FutureBlock needCopy = new FutureBlock();
        Condition.isSharedTest(frame).setBranch(true, needCopy).addTo(cb);
        new SetBlock(result, frame).addTo(cb);
        new SetBlock(copy, CodeValue.ZERO).addTo(cb);
        FutureBlock done = cb.swapNext(needCopy);
        new SetBlock(result, frameClass.emitAlloc(tstate)).addTo(cb);
        Frame.SET_LAYOUT_OR_REPLACEMENT.block(result, CodeValue.of(this)).addTo(cb);
        new SetBlock(copy, CodeValue.ONE).addTo(cb);
        cb.mergeNext(done);
      }
      Register size = cb.newRegister(int.class);
      new SetBlock(size, frameClass.getIntField.get(LENGTH_FIELD).result(frame)).addTo(cb);
      Register newSize = cb.newRegister(int.class);
      new SetBlock(newSize, Op.SUBTRACT_INTS.result(Op.ADD_INTS.result(size, addSize), removeSize))
          .addTo(cb);
      frameClass.setIntField.get(LENGTH_FIELD).block(result, newSize).addTo(cb);
      int start = firstElementArray();
      for (int i = start; i < nPtrs; i++) {
        CodeValue array = frameClass.getPtrField.get(i).result(frame);
        array =
            emitRemoveRangeFromElementArray(
                tstate, array, i - start, size, keepPrefix, removeSize, addSize, copy);
        frameClass.setPtrField.get(i).block(result, array).addTo(cb);
      }
      if (overflowSize != 0) {
        Register fOverflow = cb.newRegister(Object[].class);
        new SetBlock(fOverflow, frameClass.getPtrField.get(OVERFLOW_FIELD).result(frame)).addTo(cb);
        Register resultOverflow = cb.newRegister(Object[].class);
        {
          FutureBlock copyRequested = new FutureBlock();
          new TestBlock.IsEq(OpCodeType.INT, copy, CodeValue.ZERO)
              .setBranch(false, copyRequested)
              .addTo(cb);
          new SetBlock(resultOverflow, fOverflow).addTo(cb);
          FutureBlock done = cb.swapNext(copyRequested);
          new SetBlock(
                  resultOverflow,
                  TState.ALLOC_OBJ_ARRAY_OP.result(tstate, CodeValue.of(overflowSize)))
              .addTo(cb);
          frameClass.setPtrField.get(OVERFLOW_FIELD).block(result, resultOverflow).addTo(cb);
          cb.mergeNext(done);
        }
        for (int i = 0; i < overflowSize; i++) {
          CodeValue array = Op.OBJ_ARRAY_ELEMENT.result(fOverflow, CodeValue.of(i));
          array =
              emitRemoveRangeFromElementArray(
                  tstate, array, i + nPtrs - 1, size, keepPrefix, removeSize, addSize, copy);
          RcOp.SET_OBJ_ARRAY_ELEMENT.block(resultOverflow, CodeValue.of(i), array).addTo(cb);
        }
      }
      {
        FutureBlock done = new FutureBlock();
        // if copy==0 is false, i.e. if copy
        new TestBlock.IsEq(OpCodeType.INT, copy, CodeValue.ZERO).setBranch(true, done).addTo(cb);
        TState.DROP_REFERENCE_OP.block(tstate, frame).addTo(cb);
        cb.mergeNext(done);
      }
      new ReturnBlock(result).addTo(cb);
      MethodHandle mh =
          cb.load("removeRangeVArray_" + StringUtil.id(this), null, Frame.class, Handle.lookup);
      op = RcOp.forMethodHandle("removeRange" + this, mh).resultIsRcOut().argIsRcIn(1).build();
      REMOVE_RANGE_OP.setRelease(this, op);
      return op;
    }
  }

  private CodeValue emitRemoveRangeFromElementArray(
      CodeValue tstate,
      CodeValue array,
      int index,
      CodeValue size,
      CodeValue keepPrefix,
      CodeValue removeSize,
      CodeValue addSize,
      CodeValue copy) {
    Op op;
    if (index < numVarIndexLimit()) {
      op = TState.REMOVE_RANGE_BYTES_OP;
      int shift = nvEncodings[index].nBytesLog2;
      if (shift != 0) {
        size = Op.SHIFT_LEFT_INT.result(size, CodeValue.of(shift));
        keepPrefix = Op.SHIFT_LEFT_INT.result(keepPrefix, CodeValue.of(shift));
        removeSize = Op.SHIFT_LEFT_INT.result(removeSize, CodeValue.of(shift));
        addSize = Op.SHIFT_LEFT_INT.result(addSize, CodeValue.of(shift));
      }
    } else {
      op = TState.REMOVE_RANGE_OBJS_OP;
    }
    return op.result(tstate, array, size, keepPrefix, removeSize, addSize, copy);
  }

  /**
   * Copies elements from an array with this layout into an array with the same layout (possibly the
   * same array).
   *
   * <p>This method is used to implement replaceElement and concatenation.
   */
  @RC.Out
  Value fastCopyRange(
      TState tstate, @RC.In Value dst, Value dstStart, Value src, Value srcStart, Value count)
      throws Err.BuiltinException {
    assert dst.layout() == this && ((VArrayLayout) src.layout()).template.equals(template);
    // We can treat src as an instance of this layout, even if it's a different layout with the
    // same template.
    if (tstate.hasCodeGen()) {
      // first need to copy if shared, then just call emitCopyRange
      CodeGen codeGen = tstate.codeGen();
      CodeValue dstCV = codeGen.asCodeValue(dst);
      Register unshared = emitEnsureUnsharedWithCheck(codeGen, dstCV);
      copyRangeOp(true)
          .block(
              unshared,
              codeGen.asCodeValue(dstStart),
              codeGen.asCodeValue(src),
              codeGen.asCodeValue(srcStart),
              codeGen.asCodeValue(count))
          .addTo(codeGen.cb);
      return (unshared == dstCV) ? dst : CodeGen.asValue(unshared, this);
    }
    Frame dstFrame = (Frame) dst;
    if (!dstFrame.isNotShared()) {
      tstate.reserve(sizeOf(numElements(dstFrame)));
      dstFrame = duplicate(tstate, dstFrame);
      tstate.dropValue(dst);
    }
    Frame srcFrame = (Frame) src;
    assert frameClass.javaClass.isInstance(srcFrame);
    int iDstStart = NumValue.asInt(dstStart);
    int iSrcStart = NumValue.asInt(srcStart);
    int iCount = NumValue.asInt(count);
    Op op = copyRangeOp(false);
    if (op != null) {
      try {
        op.mh.invokeExact(dstFrame, iDstStart, srcFrame, iSrcStart, iCount);
      } catch (Throwable e) {
        throw new AssertionError(e);
      }
      return dstFrame;
    }
    int start = firstElementArray();
    for (int i = start; i < nPtrs; i++) {
      Object dstArray = frameClass.getX(dstFrame, i);
      Object srcArray = frameClass.getX(srcFrame, i);
      copyRangeFromElementArray(i - start, dstArray, iDstStart, srcArray, iSrcStart, iCount);
    }
    if (overflowSize != 0) {
      Object[] dstOverflow = (Object[]) frameClass.getX(dstFrame, OVERFLOW_FIELD);
      Object[] srcOverflow = (Object[]) frameClass.getX(srcFrame, OVERFLOW_FIELD);
      for (int i = 0; i < overflowSize; i++) {
        Object dstArray = dstOverflow[i];
        Object srcArray = srcOverflow[i];
        copyRangeFromElementArray(i + nPtrs - 1, dstArray, iDstStart, srcArray, iSrcStart, iCount);
      }
    }
    return dstFrame;
  }

  private void copyRangeFromElementArray(
      int index, Object dstArray, int dstStart, Object srcArray, int srcStart, int count) {
    if (index < numVarIndexLimit()) {
      int bytesPerElementLog2 = nvEncodings[index].nBytesLog2;
      assert dstArray instanceof byte[] && srcArray instanceof byte[];
      System.arraycopy(
          srcArray,
          srcStart << bytesPerElementLog2,
          dstArray,
          dstStart << bytesPerElementLog2,
          count << bytesPerElementLog2);
    } else {
      MemoryHelper.copyRange((Object[]) dstArray, dstStart, (Object[]) srcArray, srcStart, count);
    }
  }

  void emitCopyRange(
      CodeGen codeGen,
      VArrayLayout srcLayout,
      CodeValue dst,
      CodeValue dstStart,
      CodeValue src,
      CodeValue srcStart,
      CodeValue count) {
    if (srcLayout.template.equals(template)) {
      copyRangeOp(true).block(dst, dstStart, src, srcStart, count).addTo(codeGen.cb);
      return;
    }
    CopyPlan plan = CopyPlan.create(srcLayout.template, template, false);
    if (plan.steps == null) {
      codeGen.escape();
      return;
    }
    plan = CopyOptimizer.optimize(plan, this, CopyOptimizer.Policy.NO_CONFLICTS);
    // first pass: everything except COPY_REF, FRAME_TO_COMPOUND, COMPOUND_TO_FRAME, and Switch
    for (CopyPlan.Step step : plan.steps) {
      if (step instanceof CopyPlan.Basic basic) {
        switch (basic.type) {
          case COPY_NUM:
            NumVar srcVar = (NumVar) basic.src;
            NumVar dstVar = (NumVar) basic.dst;
            CodeValue srcArray = elementArray(src, srcVar);
            CodeValue dstArray = elementArray(dst, dstVar);
            if (dstVar.encoding == srcVar.encoding) {
              emitCopyRangeFromElementArray(
                  codeGen.cb, dstVar.encoding, dstArray, dstStart, srcArray, srcStart, count);
            } else {
              throw new UnsupportedOperationException();
            }
            break;
          case COPY_REF:
          case SET_NUM:
          case SET_REF:
          case VERIFY_NUM:
          case VERIFY_REF:
          case VERIFY_REF_TYPE:
          case FRAME_TO_COMPOUND:
          case COMPOUND_TO_FRAME:
            throw new UnsupportedOperationException();
        }
      }
    }
  }

  private Op copyRangeOp(boolean build) {
    Op op = (Op) COPY_RANGE_OP.getAcquire(this);
    if (op != null || !build) {
      return op;
    }
    // See comment in allocOp() about this synchronization.
    synchronized (this) {
      if (this.copyRangeOp != null) {
        return this.copyRangeOp;
      }
      CodeBuilder cb = CodeGen.newCodeBuilder();
      Register dst = cb.newArg(Frame.class);
      Register dstStart = cb.newArg(int.class);
      Register src = cb.newArg(Frame.class);
      Register srcStart = cb.newArg(int.class);
      Register count = cb.newArg(int.class);
      int start = firstElementArray();
      for (int i = start; i < nPtrs; i++) {
        CodeValue srcArray = frameClass.getPtrField.get(i).result(src);
        CodeValue dstArray = frameClass.getPtrField.get(i).result(dst);
        emitCopyRangeFromElementArray(cb, i - start, dstArray, dstStart, srcArray, srcStart, count);
      }
      if (overflowSize != 0) {
        Register srcOverflow = cb.newRegister(Object[].class);
        new SetBlock(srcOverflow, frameClass.getPtrField.get(OVERFLOW_FIELD).result(src)).addTo(cb);
        Register dstOverflow = cb.newRegister(Object[].class);
        new SetBlock(dstOverflow, frameClass.getPtrField.get(OVERFLOW_FIELD).result(dst)).addTo(cb);
        for (int i = 0; i < overflowSize; i++) {
          CodeValue srcArray = Op.OBJ_ARRAY_ELEMENT.result(srcOverflow, CodeValue.of(i));
          CodeValue dstArray = Op.OBJ_ARRAY_ELEMENT.result(dstOverflow, CodeValue.of(i));
          emitCopyRangeFromElementArray(
              cb, i + nPtrs - 1, dstArray, dstStart, srcArray, srcStart, count);
        }
      }
      new ReturnBlock(null).addTo(cb);
      MethodHandle mh =
          cb.load("copyVArray_" + StringUtil.id(this), null, void.class, Handle.lookup);
      op = Op.forMethodHandle("copy" + this, mh).build();
      COPY_RANGE_OP.setRelease(this, op);
      return op;
    }
  }

  private static final Op SYSTEM_ARRAY_COPY_OP =
      Op.forMethod(
              System.class,
              "arraycopy",
              Object.class,
              int.class,
              Object.class,
              int.class,
              int.class)
          .build();

  private void emitCopyRangeFromElementArray(
      CodeBuilder cb,
      int index,
      CodeValue dstArray,
      CodeValue dstStart,
      CodeValue srcArray,
      CodeValue srcStart,
      CodeValue count) {
    if (index < numVarIndexLimit()) {
      emitCopyRangeFromElementArray(
          cb, nvEncodings[index], dstArray, dstStart, srcArray, srcStart, count);
    } else {
      TState.COPY_RANGE_OP.block(dstArray, dstStart, srcArray, srcStart, count).addTo(cb);
    }
  }

  private static void emitCopyRangeFromElementArray(
      CodeBuilder cb,
      NumEncoding encoding,
      CodeValue dstArray,
      CodeValue dstStart,
      CodeValue srcArray,
      CodeValue srcStart,
      CodeValue count) {
    int shift = encoding.nBytesLog2;
    if (shift != 0) {
      dstStart = Op.SHIFT_LEFT_INT.result(dstStart, CodeValue.of(shift));
      srcStart = Op.SHIFT_LEFT_INT.result(srcStart, CodeValue.of(shift));
      count = Op.SHIFT_LEFT_INT.result(count, CodeValue.of(shift));
    }
    SYSTEM_ARRAY_COPY_OP.block(srcArray, srcStart, dstArray, dstStart, count).addTo(cb);
  }

  @Override
  int numElements(Frame f) {
    return frameClass.getI(f, LENGTH_FIELD_OFFSET);
  }

  CodeValue numElements(CodeValue frame) {
    return frameClass.getIntField.get(LENGTH_FIELD).resultWithInfo(NON_NEGATIVE_INT, frame);
  }

  private static final ValueInfo NON_NEGATIVE_INT = ValueInfo.IntRange.of(0, Integer.MAX_VALUE);

  private void setNumElements(Frame f, int size) {
    frameClass.setI(f, LENGTH_FIELD_OFFSET, size);
  }

  @Override
  void clearElement(TState tstate, Frame f, int index) {
    clearElements(tstate, f, index, index + 1);
  }

  void clearElements(TState tstate, Frame f, int start, int end) {
    if (numRV == 0) {
      return;
    }
    int firstPtrElement = firstElementArray() + numVarIndexLimit();
    for (int i = firstPtrElement; i < nPtrs; i++) {
      Object[] elementArray = (Object[]) frameClass.getX(f, i);
      tstate.clearElements(elementArray, start, end);
    }
    if (overflowSize != 0) {
      Object[] overflow = (Object[]) frameClass.getX(f, OVERFLOW_FIELD);
      for (int i = Math.max(0, firstPtrElement - nPtrs); i < overflowSize; i++) {
        Object[] elementArray = (Object[]) overflow[i];
        tstate.clearElements(elementArray, start, end);
      }
    }
  }

  @Override
  void emitClearElement(CodeGen codeGen, CodeValue f, CodeValue index) {
    if (numRV != 0) {
      emitClearElements(codeGen, f, index, Op.ADD_INTS.result(index, CodeValue.ONE));
    }
  }

  /** Emits a call to this layout's {@link #clearElementsOp}, building it if necessary. */
  private void emitClearElements(CodeGen codeGen, CodeValue f, CodeValue start, CodeValue end) {
    clearElementsOp(true).block(codeGen.tstateRegister(), f, start, end).addTo(codeGen.cb);
  }

  /**
   * Returns this layout's {@link #clearElementsOp}; if {@code build} is true and the op is null,
   * builds it first.
   */
  private Op clearElementsOp(boolean build) {
    assert numRV != 0;
    Op op = (Op) CLEAR_ELEMENTS_OP.getAcquire(this);
    return (op == null && build) ? buildClearElements() : op;
  }

  /** Builds and stores this layout's {@link #clearElementsOp}. */
  synchronized Op buildClearElements() {
    if (this.clearElementsOp != null) {
      // Another thread beat us to it.
      return this.clearElementsOp;
    }
    CodeBuilder cb = CodeGen.newCodeBuilder();
    Register tstate = cb.newArg(TState.class);
    Register frame = cb.newArg(Frame.class);
    Register start = cb.newArg(int.class);
    Register end = cb.newArg(int.class);
    FutureBlock rangeEmpty = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, start, end)
        .setBranch(true, rangeEmpty)
        .addTo(cb);
    int firstPtrElement = firstElementArray() + numVarIndexLimit();
    for (int i = firstPtrElement; i < nPtrs; i++) {
      CodeValue elementArray = frameClass.getPtrField.get(i).result(frame);
      TState.CLEAR_ARRAY_ELEMENTS_OP.block(tstate, elementArray, start, end).addTo(cb);
    }
    if (overflowSize != 0) {
      Register overflow = cb.newRegister(Object[].class);
      new SetBlock(overflow, frameClass.getPtrField.get(OVERFLOW_FIELD).result(frame)).addTo(cb);
      for (int i = Math.max(0, firstPtrElement - nPtrs); i < overflowSize; i++) {
        CodeValue elementArray = Op.OBJ_ARRAY_ELEMENT.result(overflow, CodeValue.of(i));
        TState.CLEAR_ARRAY_ELEMENTS_OP.block(tstate, elementArray, start, end).addTo(cb);
      }
    }
    cb.mergeNext(rangeEmpty);
    new ReturnBlock(null).addTo(cb);
    MethodHandle mh =
        cb.load("clearVArray_" + StringUtil.id(this), null, void.class, Handle.lookup);
    Op result = Op.forMethodHandle("clear" + this, mh).build();
    CLEAR_ELEMENTS_OP.setRelease(this, result);
    return result;
  }

  @Override
  boolean setElement(TState tstate, Frame f, int index, Value newElement) {
    if (newElement == Core.TO_BE_SET
        || template.setValue(tstate, newElement, asVarSink(f, index))) {
      return true;
    }
    clearElement(tstate, f, index);
    return false;
  }

  @Override
  void emitSetElement(CodeGen codeGen, CodeValue f, CodeValue pos, Template newElement) {
    emitSetElement(codeGen, f, pos, newElement, codeGen.escapeLink());
  }

  /**
   * Implements {@link #emitSetElement(CodeGen, CodeValue, CodeValue, Template)}; the emitted blocks
   * will branch to {@code onFail} if the {@code newElement} cannot be stored with evolving the
   * layout.
   */
  void emitSetElement(
      CodeGen codeGen, CodeValue f, CodeValue pos, Template newElement, FutureBlock onFail) {
    CopyEmitter emitter =
        new CopyEmitter() {
          @Override
          void setDstVar(CodeGen codeGen, Template t, CodeValue v) {
            setCodeValue(codeGen, t, f, pos, v);
          }
        };
    CopyPlan plan = CopyPlan.create(newElement, template, true);
    plan = CopyOptimizer.optimize(plan, this, CopyOptimizer.Policy.NO_CONFLICTS);
    emitter.emit(codeGen, plan, onFail);
  }

  @Override
  public boolean setElements(TState tstate, Frame f, int start, int end, Value newElement) {
    assert start < end && newElement != Core.TO_BE_SET;
    Template.VarSink sink =
        new Template.VarSink() {
          @Override
          public void setB(int index, int value) {
            assert value >= 0 && value < 256;
            byte[] bytes = (byte[]) getElementArray(f, index);
            Arrays.fill(bytes, start, end, (byte) value);
          }

          @Override
          public void setI(int index, int value) {
            // TODO: maybe optimize this?
            byte[] bytes = (byte[]) getElementArray(f, index);
            for (int i = start; i < end; i++) {
              ArrayUtil.bytesSetI(bytes, i, value);
            }
          }

          @Override
          public void setD(int index, double value) {
            // TODO: maybe optimize this?
            byte[] bytes = (byte[]) getElementArray(f, index);
            for (int i = start; i < end; i++) {
              ArrayUtil.bytesSetD(bytes, i, value);
            }
          }

          @Override
          public void setValue(int index, @RC.In Value value) {
            Object[] array = (Object[]) getElementArray(f, index + numVarIndexLimit());
            assert IntStream.range(start, end).allMatch(i -> array[i] == null);
            Arrays.fill(array, start, end, value);
            RefCounted.addRef(value, end - start - 1);
          }
        };
    if (!template.setValue(tstate, newElement, sink)) {
      clearElements(tstate, f, start, end);
      return false;
    }
    return true;
  }

  @Override
  CopyEmitter copyFrom(Register frame, int index) {
    return copyFrom(frame, CodeValue.of(index));
  }

  /** Returns a CopyEmitter who source is the specified element of a frame with this layout. */
  CopyEmitter copyFrom(Register frame, CodeValue index) {
    return new CopyEmitter() {
      @Override
      CodeValue getSrcVar(CodeGen codeGen, Template t) {
        return codeValue(t, frame, index);
      }
    };
  }

  /**
   * Set all elements of {@code f} with indices between {@code start} and {@code end} to {@code
   * newElement}
   */
  public void emitSetElements(
      CodeGen codeGen, CodeValue f, CodeValue start, CodeValue end, Template newElement) {
    CopyEmitter emitter =
        new CopyEmitter() {
          @Override
          void setDstVar(CodeGen codeGen, Template t, CodeValue v) {
            Op op;
            if (t instanceof NumVar nv) {
              op =
                  switch (nv.encoding) {
                    case UINT8 -> CodeGen.BYTES_FILL_B;
                    case INT32 -> CodeGen.BYTES_FILL_I;
                    case FLOAT64 -> CodeGen.BYTES_FILL_D;
                    default -> throw new AssertionError();
                  };
            } else {
              op = TState.FILL_ARRAY_ELEMENTS_OP;
            }
            op.block(elementArray(f, t), start, end, v).addTo(codeGen.cb);
          }
        };
    CopyPlan plan = CopyPlan.create(newElement, template, true);
    plan = CopyOptimizer.optimize(plan, this, CopyOptimizer.Policy.UNANIMOUS_PROMOTION_ONLY);
    emitter.emit(codeGen, plan, codeGen.escapeLink());
  }

  /**
   * Given a NumVar or RefVar in this layout's template, a CodeValue for a Frame with this layout,
   * and a CodeValue for an element index return a CodeValue for the NumVar or RefVar's value.
   */
  CodeValue codeValue(Template t, CodeValue frame, CodeValue pos) {
    CodeValue elementArray = getElementArray(frame, t);
    if (t instanceof NumVar nv) {
      return switch (nv.encoding) {
        case UINT8 -> Op.UINT8_ARRAY_ELEMENT.result(elementArray, pos);
        case INT32 ->
            CodeGen.INT_FROM_BYTES_OP.result(
                elementArray, Op.SHIFT_LEFT_INT.result(pos, CodeValue.TWO));
        case FLOAT64 ->
            CodeGen.DOUBLE_FROM_BYTES_OP.result(
                elementArray, Op.SHIFT_LEFT_INT.result(pos, CodeValue.THREE));
      };
    } else {
      return Value.FROM_ARRAY_OP.result(elementArray, pos);
    }
  }

  /**
   * Given a NumVar or RefVar in this layout's template, a CodeValue for a Frame with this layout,
   * and a CodeValue for an element index, stores {@code v} as the new value.
   */
  void setCodeValue(CodeGen codeGen, Template t, CodeValue frame, CodeValue pos, CodeValue v) {
    CodeValue elementArray = getElementArray(frame, t);
    if (t instanceof NumVar nv) {
      setNumericArrayElement(codeGen, elementArray, nv.encoding, pos, v);
    } else {
      RcOp.SET_OBJ_ARRAY_ELEMENT.block(elementArray, pos, v).addTo(codeGen.cb);
    }
  }

  /** Sets one element of a numeric array with the given encoding. */
  private static void setNumericArrayElement(
      CodeGen codeGen, CodeValue elementArray, NumEncoding encoding, CodeValue pos, CodeValue v) {
    Op op;
    switch (encoding) {
      case UINT8 -> {
        op = Op.SET_UINT8_ARRAY_ELEMENT;
      }
      case INT32 -> {
        op = CodeGen.SET_BYTES_FROM_INT_OP;
        pos = Op.SHIFT_LEFT_INT.result(pos, CodeValue.TWO);
      }
      case FLOAT64 -> {
        op = CodeGen.SET_BYTES_FROM_DOUBLE_OP;
        pos = Op.SHIFT_LEFT_INT.result(pos, CodeValue.THREE);
      }
      default -> throw new AssertionError();
    }
    op.block(elementArray, pos, v).addTo(codeGen.cb);
  }

  @Override
  boolean equals(Frame f, Value other) {
    int size = numElements(f);
    if (other.baseType().isArray() && other.numElements() == size) {
      return Value.equalElements(f, other, size);
    }
    return false;
  }

  @Override
  @RC.Out
  Value element(Frame f, int i) {
    return template.getValue(TState.get(), asVarSource(f, i, false));
  }

  @Override
  Value peekElement(Frame f, int i, boolean mayBeUninitialized) {
    return template.peekValue(asVarSource(f, i, mayBeUninitialized));
  }

  /**
   * Returns the number of bytes that should be reserved for an update to {@code f} that will change
   * its size to {@code newSize}.
   */
  long reservationForChange(Frame f, int newSize) {
    if (f == null || !f.isNotShared()) {
      return sizeOf(newSize);
    }
    int size = numElements(f);
    if (size >= newSize) {
      return 0;
    }
    long result = Math.max(newSize * (long) maxVarSize, (newSize - size) * perElementSize);
    // The actual requirement may be less (e.g. we may not need any additional allocations if the
    // element arrays are already big enough) or more (because we round array sizes up).  For
    // large sizes (the only ones we care about) it will be at most 25% more than our calculation
    // (see {@link MemoryHelper#chooseCapacity}), so let's be pessimistic (we risk getting a
    // premature OOM when you're close to the limit and try to do a large allocation).
    result += result / 4;
    return result;
  }

  @Override
  String toString(Frame f) {
    return StringUtil.joinElements("[", "]", numElements(f), i -> peekElement(f, i, true));
  }

  Template.VarSource asVarSource(Frame f, int pos, boolean mayBeUninitialized) {
    assert f.layout() == this;
    return new Template.VarSource() {
      @Override
      public int getB(int index) {
        byte[] array = (byte[]) getElementArray(f, index);
        return ArrayUtil.bytesGetB(array, pos);
      }

      @Override
      public int getI(int index) {
        byte[] array = (byte[]) getElementArray(f, index);
        return ArrayUtil.bytesGetI(array, pos);
      }

      @Override
      public double getD(int index) {
        byte[] array = (byte[]) getElementArray(f, index);
        return ArrayUtil.bytesGetD(array, pos);
      }

      @Override
      public Value getValue(int index) {
        Object[] array = (Object[]) getElementArray(f, index + numVarIndexLimit());
        return Value.fromArray(array, pos);
      }

      @Override
      public boolean numVarsMayBeUninitialized() {
        return mayBeUninitialized;
      }
    };
  }

  Template.VarSink asVarSink(Frame f, int pos) {
    assert f.layoutOrReplacement == this;
    return new Template.VarSink() {
      @Override
      public void setB(int index, int value) {
        byte[] array = (byte[]) getElementArray(f, index);
        ArrayUtil.bytesSetB(array, pos, value);
      }

      @Override
      public void setI(int index, int value) {
        byte[] array = (byte[]) getElementArray(f, index);
        ArrayUtil.bytesSetI(array, pos, value);
      }

      @Override
      public void setD(int index, double value) {
        byte[] array = (byte[]) getElementArray(f, index);
        ArrayUtil.bytesSetD(array, pos, value);
      }

      @Override
      public void setValue(int index, @RC.In Value value) {
        Object[] array = (Object[]) getElementArray(f, index + numVarIndexLimit());
        assert array[pos] == null;
        array[pos] = value;
      }
    };
  }

  /**
   * VArrayLayout's VarAllocator assigns sequential indices from separate pools for RefVars and
   * NumVars. Related VarAllocators (i.e. ones that were created by a chain of duplicate() calls)
   * will not assign NumVars with different encodings to the same index.
   */
  static class VarAllocator implements TemplateBuilder.VarAllocator {
    // Related VarAllocators will share an instance of VarAllocatorShared.
    private final VarAllocatorShared shared;

    /** The minimum index at which the next NumVar of each encoding could be allocated. */
    private final int[] nextNumVar;

    /** The index of the next refvar. */
    private int nextX;

    VarAllocator() {
      shared = new VarAllocatorShared();
      nextNumVar = new int[Template.NUM_ENCODINGS];
    }

    VarAllocator(VarAllocator toDuplicate) {
      shared = toDuplicate.shared;
      nextNumVar = Arrays.copyOf(toDuplicate.nextNumVar, Template.NUM_ENCODINGS);
      nextX = toDuplicate.nextX;
    }

    /** Returns the minimum nPtrs required for a Frame to hold these vars. */
    int ptrSize() {
      return nextX + shared.nvEncodings.size();
    }

    @Override
    public boolean dropToBeSetFromUnions() {
      return true;
    }

    @Override
    @SuppressWarnings("EnumOrdinal")
    public NumVar allocNumVar(NumVar forEncoding) {
      int encodingIndex = forEncoding.encoding.ordinal();
      int result = shared.allocNumVar(forEncoding.encoding, nextNumVar[encodingIndex]);
      nextNumVar[encodingIndex] = result + 1;
      return forEncoding.withIndex(result);
    }

    @Override
    public int allocRefVar() {
      return nextX++;
    }

    @Override
    public VarAllocator duplicate() {
      return new VarAllocator(this);
    }

    @Override
    public void resetTo(TemplateBuilder.VarAllocator otherAllocator) {
      VarAllocator other = (VarAllocator) otherAllocator;
      assert other.shared == shared;
      System.arraycopy(other.nextNumVar, 0, nextNumVar, 0, Template.NUM_ENCODINGS);
      nextX = other.nextX;
    }

    @Override
    public void union(TemplateBuilder.VarAllocator otherAllocator) {
      VarAllocator other = (VarAllocator) otherAllocator;
      assert other.shared == shared;
      for (int i = 0; i < Template.NUM_ENCODINGS; i++) {
        nextNumVar[i] = Math.max(nextNumVar[i], other.nextNumVar[i]);
      }
      this.nextX = Math.max(this.nextX, other.nextX);
    }
  }

  /** Keeps track of which encoding each NumVar index has. */
  private static class VarAllocatorShared {
    /**
     * If we have allocated a NumVar with index {@code i}, {@code nvEncodings.get(i)} is the
     * encoding of that NumVar.
     */
    private final List<NumEncoding> nvEncodings = new ArrayList<>();

    /**
     * Returns the first index starting from {@code next} that can be used for a NumVar with the
     * specified encoding.
     */
    int allocNumVar(NumEncoding encoding, int next) {
      int size = nvEncodings.size();
      for (int i = next; i < size; i++) {
        if (nvEncodings.get(i) == encoding) {
          return i;
        }
      }
      nvEncodings.add(encoding);
      return size;
    }

    /** Returns the NumEncoding of each used NumVar index. */
    NumEncoding[] nvEncodings() {
      return nvEncodings.toArray(NumEncoding[]::new);
    }
  }

  /**
   * A Choice locates the Template that is used for array elements with a given baseType (since it
   * actually checks sort order, if the baseType is an array type then it will locate any element
   * that is an array).
   *
   * <p>There three flavors of Choice:
   *
   * <ul>
   *   <li>NEVER: the array cannot hold elements of baseType
   *   <li>ALWAYS: the array can only elements of baseType (i.e. template.baseType().sortOrder ==
   *       baseType.sortOrder)
   *   <li>all other Choices: template is a Union, and template.choice(index).baseType().sortOrder
   *       == baseType.sortOrder
   * </ul>
   */
  private static class Choice {
    static final Choice NEVER = new Choice(-1);
    static final Choice ALWAYS = new Choice(-1);

    // Because we expect this to be very common, we'll avoid allocating a new one each time.
    // (Choice is currently only used for numbers; since SORT_ORDER_NUM is 1, Numbers will always be
    // the first choice in any union.)
    private static final Choice CHOICE_0 = new Choice(0);

    /** The index of this choice in a Union template. -1 if the layout template is not a Union. */
    final int index;

    private Choice(int index) {
      this.index = index;
    }

    /** True if this sort order is one choice in a Union. */
    boolean isInUnion() {
      return index >= 0;
    }

    /**
     * Returns the template used for values with this sort order, or null if this is {@link #NEVER}.
     */
    Template template(Template t) {
      return isInUnion() ? ((Template.Union) t).choice(index) : (this == ALWAYS ? t : null);
    }

    /** Returns the Choice for the given BaseType, given the layout's template. */
    static Choice forBaseType(BaseType baseType, Template t) {
      if (t instanceof Template.Union union) {
        int i = union.indexOf(baseType.sortOrder);
        if (i < 0) {
          return NEVER;
        } else {
          assert (i == 0) || baseType != Core.NUMBER;
          return (i == 0) ? CHOICE_0 : new Choice(i);
        }
      } else if (t == Template.EMPTY) {
        return NEVER;
      } else {
        return (t.baseType().sortOrder == baseType.sortOrder) ? ALWAYS : NEVER;
      }
    }
  }
}
