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

import static org.retrolang.impl.Value.addRef;

import com.google.common.annotations.VisibleForTesting;
import java.util.function.ObjIntConsumer;
import org.retrolang.code.Block;
import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.impl.BaseType.StackEntryType;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.RefVar;
import org.retrolang.impl.TemplateBuilder.CompoundBase;
import org.retrolang.util.ArrayUtil;
import org.retrolang.util.Bits;
import org.retrolang.util.SizeOf;

/**
 * Frames using this FrameLayout represent the compound value described by the template, with
 * variable values supplied by the Frame's fields.
 *
 * <p>When possible we choose a FrameClass that allows each of the template's NumVars and RefVars to
 * be stored directly in the frame. But since it's possible that there may not be a FrameClass with
 * as many int or pointer fields as our template requires, we have to be prepared to allocate a
 * byte[] and/or Object[] to hold the overflow (and since any overflow arrays will require slots in
 * the frame, they have to be considered when assigning slots).
 */
public class RecordLayout extends FrameLayout {
  public final Template.Compound template;

  /**
   * The value of {@code alloc.ptrSize()} after our VarAllocator finished visiting each top-level
   * element.
   *
   * <p>For each refVar {@code r} in {@code template.element(i)}, {@code r.index < ptrEnd[i]} and
   * {@code r.index >= (i == 0) ? 0 : ptrEnd[i - 1]}.
   *
   * <p>Null if {@link #baseType} is a StackEntryType (those templates use a special allocation
   * process).
   */
  private final int[] ptrEnd;

  /** The number of ints required to store this layout's non-pointer fields. */
  private final int intSize;

  /**
   * The length (in bytes) of any non-pointer overflow array required, or zero if this layout's
   * non-pointer fields fit in the chosen FrameClass.
   */
  @VisibleForTesting final int byteOverflowSize;

  /**
   * The length of any pointer overflow array required, or zero if this layout's pointer fields fit
   * in the chosen FrameClass.
   */
  @VisibleForTesting final int ptrOverflowSize;

  /** The number of overflow arrays for bytes and/or pointers: 0, 1, or 2. */
  private final int numOverflow;

  /**
   * The total number of bytes required for each instance of this RecordLayout, counting the Frame
   * and any required overflow arrays.
   */
  final int totalBytes;

  /** Builds a template with the given builder, and creates a RecordLayout using the result. */
  static RecordLayout newFromBuilder(Scope scope, TemplateBuilder builder) {
    VarAllocator allocator = VarAllocator.newForRecordLayout();
    Template.Compound template = (Template.Compound) builder.build(allocator);
    RecordLayout result = newWithAllocator(scope, template, allocator);
    scope.evolver.recordNewLayout(result);
    return result;
  }

  /**
   * Creates a RecordLayout from a template and the allocator that was used to build it.
   *
   * <p>Should only be called directly by the Evolver; other callers should use {@link
   * #newFromBuilder}.
   */
  static RecordLayout newWithAllocator(
      Scope scope, Template.Compound template, VarAllocator alloc) {
    FrameClass frameClass = FrameClass.select(alloc.intSize(), alloc.ptrSize());
    int[] ptrEnd = new int[template.baseType.size()];
    int end = 0;
    for (int i = 0; i < ptrEnd.length; i++) {
      end = 1 + maxRefVarIndex(template.element(i), end);
      ptrEnd[i] = end;
    }
    assert end == alloc.ptrSize();
    return new RecordLayout(scope, frameClass, template, alloc, ptrEnd);
  }

  /**
   * Creates a RecordLayout for a StackEntryType. A frame with this layout cannot be used as a
   * Retrospect-language value, will never be evolved, and does not support modification.
   */
  static RecordLayout newForStackEntry(Template.Compound template, VarAllocator alloc) {
    assert template.baseType instanceof StackEntryType;
    FrameClass frameClass = FrameClass.select(alloc.intSize(), alloc.ptrSize());
    return new RecordLayout(null, frameClass, template, alloc, null);
  }

  private RecordLayout(
      Scope scope,
      FrameClass frameClass,
      Template.Compound template,
      VarAllocator alloc,
      int[] ptrEnd) {
    super(scope, template.baseType, frameClass, ptrsUsed(frameClass, alloc));
    this.template = template;
    this.ptrEnd = ptrEnd;
    intSize = alloc.intSize();
    byteOverflowSize = Math.max(0, (intSize - frameClass.nInts) * SizeOf.INT);
    int hasByteOverflow = (byteOverflowSize != 0) ? 1 : 0;
    // If we need a byte overflow array, that's another pointer.
    int ptrOverflow = Math.max(0, alloc.ptrSize() + hasByteOverflow - frameClass.nPtrs);
    int hasPtrOverflow = (ptrOverflow != 0) ? 1 : 0;
    // If we need a pointer overflow array, that's another pointer.
    ptrOverflowSize = ptrOverflow + hasPtrOverflow;
    numOverflow = hasByteOverflow + hasPtrOverflow;
    // We will allocate overflow arrays first, and should never need to put them in an overflow
    // array.
    assert numOverflow <= frameClass.nPtrs;
    long totalBytes =
        frameClass.byteSize
            + (ptrOverflowSize == 0
                ? 0
                : SizeOf.array(MemoryHelper.chooseCapacityObjects(ptrOverflowSize), SizeOf.PTR))
            + (byteOverflowSize == 0
                ? 0
                : SizeOf.array(MemoryHelper.chooseCapacityBytes(byteOverflowSize), SizeOf.BYTE));
    this.totalBytes = Math.toIntExact(totalBytes);
  }

  /** Determine how many of this FrameClass's pointer fields we'll actually use. */
  private static int ptrsUsed(FrameClass frameClass, VarAllocator alloc) {
    boolean hasByteOverflow = (alloc.intSize() > frameClass.nInts);
    int totalPtrs = alloc.ptrSize() + (hasByteOverflow ? 1 : 0);
    return Math.min(totalPtrs, frameClass.nPtrs);
  }

  /** If we have a byte overflow array, it's always stored in the first pointer field. */
  private static final int BYTE_OVERFLOW_INDEX = 0;

  /**
   * If we have a pointer overflow array, it's stored in the first pointer field if there is no byte
   * overflow array, or the second pointer field if there is.
   */
  private int ptrOverflowIndex() {
    return (byteOverflowSize == 0) ? 0 : 1;
  }

  @Override
  Template template() {
    return template;
  }

  @Override
  Template elementTemplate(int index) {
    return template.element(index);
  }

  @Override
  int numVarIndexLimit() {
    return intSize * SizeOf.INT;
  }

  @Override
  int refVarIndexLimit() {
    return ptrEnd[ptrEnd.length - 1];
  }

  @Override
  FrameLayout addValueImpl(Value v) {
    if (v.baseType() == template.baseType) {
      return evolveTemplate(template.toBuilder().addImpl(v));
    } else {
      return evolveToVArray(Template.EMPTY.addElements(v));
    }
  }

  @Override
  FrameLayout merge(CompoundBase compound) {
    if (compound.baseType == template.baseType) {
      return evolveTemplate(template.toBuilder().mergeImpl(compound));
    } else {
      return evolveToVArray(compound.mergeElementsInto(Template.EMPTY));
    }
  }

  @Override
  FrameLayout evolveElement(int index, Value newElement) {
    return evolveTemplate(template.addToElement(index, newElement));
  }

  @Override
  public FrameLayout evolveElements(int start, int end, Value newElement) {
    CompoundBase newCompound = template;
    for (int i = start; i < end; i++) {
      newCompound = newCompound.addToElement(i, newElement);
    }
    return evolveTemplate(newCompound);
  }

  private FrameLayout evolveTemplate(TemplateBuilder newTemplate) {
    return (newTemplate == template) ? this : scope.evolver.evolve(this, newTemplate, false);
  }

  /**
   * Evolves this RecordLayout to a new VArrayLayout that can store the elements of this layout's
   * template and the given element.
   */
  FrameLayout evolveToVArray(TemplateBuilder element) {
    return scope.evolver.evolve(this, element, true);
  }

  @Override
  long sizeOf(int numElements) {
    return totalBytes;
  }

  static final Op ALLOC_OP =
      RcOp.forRcMethod(RecordLayout.class, "alloc", TState.class, int.class).build();

  @Override
  @RC.Out
  public Frame alloc(TState tstate, int size) {
    Frame result = frameClass.alloc(tstate);
    result.layoutOrReplacement = this;
    if (byteOverflowSize != 0) {
      frameClass.setX(result, BYTE_OVERFLOW_INDEX, tstate.allocByteArray(byteOverflowSize));
    }
    if (ptrOverflowSize != 0) {
      frameClass.setX(result, ptrOverflowIndex(), tstate.allocObjectArray(ptrOverflowSize));
    }
    return result;
  }

  /** Returns a CodeValue that calls {@link #alloc} on this RecordLayout. */
  CodeValue emitAlloc(CodeGen codeGen) {
    return emitAlloc(codeGen.cb);
  }

  /** Returns a CodeValue that calls {@link #alloc} on this RecordLayout. */
  CodeValue emitAlloc(CodeBuilder cb) {
    // The size argument is ignored, so we just pass zero.
    return ALLOC_OP.result(CodeValue.of(this), CodeGen.tstateRegister(cb), CodeValue.ZERO);
  }

  @Override
  @RC.Out
  Frame duplicate(TState tstate, Frame f) {
    assert f.layout() == this;
    Frame result = alloc(tstate, 0);
    if (intSize != 0) {
      // Unsafe.copyMemory() only seems to work for primitive arrays, not for arbitrary classes, so
      // do it the slow way.  This could probably be optimized.
      int end = Math.min(intSize, frameClass.nInts) * SizeOf.INT;
      for (int i = 0; i < end; i += SizeOf.INT) {
        frameClass.setI(result, i, frameClass.getI(f, i));
      }
      if (byteOverflowSize != 0) {
        byte[] oldOverflow = frameClass.getBytes(f, BYTE_OVERFLOW_INDEX);
        byte[] newOverflow = frameClass.getBytes(result, BYTE_OVERFLOW_INDEX);
        System.arraycopy(oldOverflow, 0, newOverflow, 0, byteOverflowSize);
      }
    }
    copyPtrs(f, result, 0, refVarIndexLimit());
    return result;
  }

  @Override
  long bytesForDuplicate(Frame f) {
    assert f.layout() == this;
    return totalBytes;
  }

  @Override
  int numElements(Frame f) {
    return baseType().size();
  }

  @Override
  void clearElement(TState tstate, Frame f, int index) {
    clearElements(tstate, f, index, index + 1);
  }

  private int ptrStart(int i) {
    return (i == 0) ? 0 : ptrEnd[i - 1];
  }

  private void clearElements(TState tstate, Frame f, int start, int end) {
    int pStart = ptrStart(start);
    int pEnd = ptrEnd[end - 1];
    if (pStart == pEnd) {
      return;
    }
    pStart += numOverflow;
    pEnd += numOverflow;
    if (pEnd > frameClass.nPtrs) {
      Object[] ptrOverflow = (Object[]) frameClass.getX(f, ptrOverflowIndex());
      int oStart = Math.max(0, pStart - frameClass.nPtrs);
      int oEnd = pEnd - frameClass.nPtrs;
      tstate.clearElements(ptrOverflow, oStart, oEnd);
      pEnd = frameClass.nPtrs;
    }
    for (int i = pStart; i < pEnd; i++) {
      tstate.dropValue((Value) frameClass.takeX(f, i));
    }
  }

  @Override
  void emitClearElement(CodeGen codeGen, CodeValue f, CodeValue index) {
    if (index instanceof CodeValue.Const) {
      int i = index.iValue();
      emitClearElements(codeGen, f, i, i + 1);
    } else if (refVarIndexLimit() != 0) {
      CodeValue iReg = codeGen.materialize(index, int.class);
      FutureBlock done = new FutureBlock();
      codeGen.emitSwitch(
          iReg,
          Bits.fromPredicate(ptrEnd.length - 1, i -> ptrStart(i) != ptrEnd[i]),
          i -> {
            emitClearElements(codeGen, f, i, i + 1);
            codeGen.cb.branchTo(done);
          });
      codeGen.cb.mergeNext(done);
    }
  }

  private void emitClearElements(CodeGen codeGen, CodeValue f, int start, int end) {
    int pStart = ptrStart(start);
    int pEnd = ptrEnd[end - 1];
    if (pStart == pEnd) {
      return;
    }
    pStart += numOverflow;
    pEnd += numOverflow;
    if (pEnd > frameClass.nPtrs) {
      CodeValue ptrOverflow = frameClass.getPtrField.get(ptrOverflowIndex()).result(f);
      int oStart = Math.max(0, pStart - frameClass.nPtrs);
      int oEnd = pEnd - frameClass.nPtrs;
      TState.CLEAR_ARRAY_ELEMENTS_OP
          .block(codeGen.tstateRegister(), ptrOverflow, CodeValue.of(oStart), CodeValue.of(oEnd))
          .addTo(codeGen.cb);
      pEnd = frameClass.nPtrs;
    }
    for (int i = pStart; i < pEnd; i++) {
      TState.DROP_VALUE_OP
          .block(codeGen.tstateRegister(), frameClass.takePtrField.get(i).result(f))
          .addTo(codeGen.cb);
    }
  }

  @Override
  boolean setElement(TState tstate, Frame f, int index, Value newElement) {
    if (newElement == Core.TO_BE_SET
        || template.element(index).setValue(tstate, newElement, asVarSink(f))) {
      return true;
    }
    clearElement(tstate, f, index);
    return false;
  }

  @Override
  void emitSetElement(CodeGen codeGen, CodeValue f, CodeValue index, Template newElement) {
    if (index instanceof CodeValue.Const) {
      // Can we end up here?  if so, we should do the simple thing
      throw new UnsupportedOperationException();
    }
    ObjIntConsumer<Template> emitElement = emitSetElement(codeGen, f, codeGen.escapeLink());
    FutureBlock done = new FutureBlock();
    codeGen.emitSwitch(
        index,
        0,
        baseType().size() - 1,
        i -> {
          emitElement.accept(newElement, i);
          codeGen.cb.branchTo(done);
        });
    codeGen.cb.setNext(done);
  }

  /**
   * Returns a consumer that given an index and newElement template, emits blocks to set that
   * element in the given Frame. Lets us reuse the CopyEmitter, but is that really worth the extra
   * level of indirection?
   */
  ObjIntConsumer<Template> emitSetElement(CodeGen codeGen, CodeValue f, FutureBlock onFail) {
    CopyEmitter emitter =
        new CopyEmitter() {
          @Override
          void setDstVar(CodeGen codeGen, Template t, CodeValue v) {
            setCodeValue(t, f, v).addTo(codeGen.cb);
          }
        };
    return (Template newElement, int i) -> {
      CopyPlan plan = CopyPlan.create(newElement, template.element(i), true);
      plan =
          CopyOptimizer.optimize(
              plan, RecordLayout.this, CopyOptimizer.Policy.NUM_VARS_ARE_BYTE_OFFSETS);
      emitter.emit(codeGen, plan, onFail);
    };
  }

  @Override
  public boolean setElements(TState tstate, Frame f, int start, int end, Value newElement) {
    assert start < end && newElement != Core.TO_BE_SET;
    Template.VarSink sink = asVarSink(f);
    for (int i = start; i < end; i++) {
      if (!template.element(i).setValue(tstate, newElement, sink)) {
        clearElements(tstate, f, start, i);
        return false;
      }
    }
    return true;
  }

  @Override
  CopyEmitter copyFrom(Register frame, int index) {
    return new CopyEmitter() {
      @Override
      CodeValue getSrcVar(CodeGen codeGen, Template t) {
        return codeValue(t, frame);
      }
    };
  }

  /**
   * Given a NumVar or RefVar in this layout's template and a CodeValue for a Frame with this
   * layout, return a CodeValue for the NumVar or RefVar's value.
   */
  private CodeValue codeValue(Template t, CodeValue frame) {
    if (t instanceof NumVar numVar) {
      int pos = numVar.index;
      int overflowPos = pos - frameClass.nInts * 4;
      if (overflowPos < 0) {
        return switch (numVar.encoding) {
          case UINT8 -> frameClass.getByteField.get(pos).result(frame);
          case INT32 -> frameClass.getIntField.get(pos / SizeOf.INT).result(frame);
          case FLOAT64 -> frameClass.getDoubleField.get((pos - 4) / SizeOf.DOUBLE).result(frame);
        };
      } else {
        CodeValue byteOverflow = frameClass.getPtrField.get(BYTE_OVERFLOW_INDEX).result(frame);
        CodeValue offset = CodeValue.of(overflowPos);
        return switch (numVar.encoding) {
          case UINT8 -> Op.UINT8_ARRAY_ELEMENT.result(byteOverflow, offset);
          case INT32 -> CodeGen.INT_FROM_BYTES_OP.result(byteOverflow, offset);
          case FLOAT64 -> CodeGen.DOUBLE_FROM_BYTES_OP.result(byteOverflow, offset);
        };
      }
    } else {
      RefVar refVar = (RefVar) t;
      int index = refVar.index + numOverflow;
      if (index < frameClass.nPtrs) {
        return frameClass.getValueField.get(index).result(frame);
      } else {
        CodeValue ptrOverflow = frameClass.getPtrField.get(ptrOverflowIndex()).result(frame);
        return Value.FROM_ARRAY_OP.result(ptrOverflow, CodeValue.of(index - frameClass.nPtrs));
      }
    }
  }

  @Override
  boolean equals(Frame f, Value other) {
    return template.baseType.equalValues(f, other);
  }

  @Override
  @RC.Out
  Value element(Frame f, int i) {
    return template.element(i).getValue(TState.get(), asVarSource(f));
  }

  @Override
  Value peekElement(Frame f, int i) {
    return template.element(i).peekValue(asVarSource(f));
  }

  /** Copy the specified range of pointers from one instance of this RecordLayout to another. */
  private void copyPtrs(Frame fSrc, Frame fDst, int start, int end) {
    if (start == end) {
      return;
    }
    start += numOverflow;
    end += numOverflow;
    if (end > frameClass.nPtrs) {
      // Some of these pointers are in the overflow arrays.
      Object[] srcOverflow = (Object[]) frameClass.getX(fSrc, ptrOverflowIndex());
      Object[] dstOverflow = (Object[]) frameClass.getX(fDst, ptrOverflowIndex());
      copyPtrs(
          srcOverflow, dstOverflow, Math.max(0, start - frameClass.nPtrs), end - frameClass.nPtrs);
      end = frameClass.nPtrs;
    }
    for (int i = start; i < end; i++) {
      assert frameClass.getX(fDst, i) == null;
      Value v = frameClass.getValue(fSrc, i);
      if (v != null) {
        frameClass.setX(fDst, i, addRef(v));
      }
    }
  }

  /** Copy Values from one array to another, incrementing refcounts appropriately. */
  private static void copyPtrs(Object[] src, Object[] dst, int start, int end) {
    for (int i = start; i < end; i++) {
      assert dst[i] == null;
      Value v = Value.fromArray(src, i);
      if (v != null) {
        dst[i] = addRef(v);
      }
    }
  }

  /**
   * Given a NumVar or RefVar from this RecordLayout's template, returns a Block that will set that
   * field in the given frame to {@code v}.
   */
  Block setCodeValue(Template t, CodeValue frame, CodeValue v) {
    if (t instanceof NumVar) {
      NumVar numVar = (NumVar) t;
      int pos = numVar.index;
      int overflowPos = pos - frameClass.nInts * 4;
      if (overflowPos < 0) {
        Op op =
            switch (numVar.encoding) {
              case UINT8 -> frameClass.setByteField.get(pos);
              case INT32 -> frameClass.setIntField.get(pos / SizeOf.INT);
              case FLOAT64 -> frameClass.setDoubleField.get((pos - 4) / SizeOf.DOUBLE);
            };
        return op.block(frame, v);
      }
      Op op =
          switch (numVar.encoding) {
            case UINT8 -> Op.SET_UINT8_ARRAY_ELEMENT;
            case INT32 -> CodeGen.SET_BYTES_FROM_INT_OP;
            case FLOAT64 -> CodeGen.SET_BYTES_FROM_DOUBLE_OP;
          };
      CodeValue byteOverflow = frameClass.getPtrField.get(BYTE_OVERFLOW_INDEX).result(frame);
      return op.block(byteOverflow, CodeValue.of(overflowPos), v);
    } else {
      RefVar refVar = (RefVar) t;
      int index = refVar.index + numOverflow;
      if (index < frameClass.nPtrs) {
        return frameClass.setPtrField.get(index).block(frame, v);
      } else {
        CodeValue ptrOverflow = frameClass.getPtrField.get(ptrOverflowIndex()).result(frame);
        int overflowIndex = index - frameClass.nPtrs;
        return RcOp.SET_OBJ_ARRAY_ELEMENT.block(ptrOverflow, CodeValue.of(overflowIndex), v);
      }
    }
  }

  @Override
  String toString(Frame f) {
    return String.valueOf(template.peekValue(asVarSource(f)));
  }

  Template.VarSource asVarSource(Frame f) {
    assert f.layout() == this;
    return new Template.VarSource() {
      @Override
      public int getB(int pos) {
        int overflowPos = pos - frameClass.nInts * SizeOf.INT;
        if (overflowPos < 0) {
          return frameClass.getB(f, pos);
        } else {
          byte[] overflow = frameClass.getBytes(f, BYTE_OVERFLOW_INDEX);
          return ArrayUtil.bytesGetB(overflow, overflowPos);
        }
      }

      @Override
      public int getI(int pos) {
        int overflowPos = pos - frameClass.nInts * SizeOf.INT;
        if (overflowPos < 0) {
          return frameClass.getI(f, pos);
        } else {
          byte[] overflow = frameClass.getBytes(f, BYTE_OVERFLOW_INDEX);
          return ArrayUtil.bytesGetIAtOffset(overflow, overflowPos);
        }
      }

      @Override
      public double getD(int pos) {
        int overflowPos = pos - frameClass.nInts * SizeOf.INT;
        if (overflowPos < 0) {
          return frameClass.getD(f, pos);
        } else {
          byte[] overflow = frameClass.getBytes(f, BYTE_OVERFLOW_INDEX);
          return ArrayUtil.bytesGetDAtOffset(overflow, overflowPos);
        }
      }

      @Override
      public Value getValue(int index) {
        index += numOverflow;
        if (index < frameClass.nPtrs) {
          return frameClass.getValue(f, index);
        } else {
          Object[] ptrOverflow = (Object[]) frameClass.getX(f, ptrOverflowIndex());
          return Value.fromArray(ptrOverflow, index - frameClass.nPtrs);
        }
      }
    };
  }

  Template.VarSink asVarSink(Frame f) {
    assert f.layoutOrReplacement == this;
    return new Template.VarSink() {
      @Override
      public void setB(int pos, int value) {
        int overflowPos = pos - frameClass.nInts * SizeOf.INT;
        if (overflowPos < 0) {
          frameClass.setB(f, pos, value);
        } else {
          byte[] overflow = frameClass.getBytes(f, BYTE_OVERFLOW_INDEX);
          ArrayUtil.bytesSetB(overflow, overflowPos, value);
        }
      }

      @Override
      public void setI(int pos, int value) {
        int overflowPos = pos - frameClass.nInts * SizeOf.INT;
        if (overflowPos < 0) {
          frameClass.setI(f, pos, value);
        } else {
          byte[] overflow = frameClass.getBytes(f, BYTE_OVERFLOW_INDEX);
          ArrayUtil.bytesSetIAtOffset(overflow, overflowPos, value);
        }
      }

      @Override
      public void setD(int pos, double value) {
        int overflowPos = pos - frameClass.nInts * SizeOf.INT;
        if (overflowPos < 0) {
          frameClass.setD(f, pos, value);
        } else {
          byte[] overflow = frameClass.getBytes(f, BYTE_OVERFLOW_INDEX);
          ArrayUtil.bytesSetDAtOffset(overflow, overflowPos, value);
        }
      }

      @Override
      public void setValue(int index, @RC.In Value value) {
        index += numOverflow;
        if (index < frameClass.nPtrs) {
          assert frameClass.getX(f, index) == null;
          frameClass.setX(f, index, value);
        } else {
          Object[] ptrOverflow = (Object[]) frameClass.getX(f, ptrOverflowIndex());
          assert ptrOverflow[index - frameClass.nPtrs] == null;
          ptrOverflow[index - frameClass.nPtrs] = value;
        }
      }
    };
  }

  /**
   * Returns the largest index of any RefVar in the given template, or {@code start - 1} if there is
   * none. Assumes that the RefVar indices were assigned by our VarAllocator (i.e. that they are in
   * increasing order) and that they are all greater than or equal to {@code start}.
   */
  private static int maxRefVarIndex(Template template, int start) {
    if (template instanceof Template.Compound compound) {
      // Since we assume that RefVar indices were assigned in increasing order, we can look
      // backwards through the elements until we find one.
      for (int i = compound.baseType.size() - 1; i >= 0; i--) {
        int result = maxRefVarIndex(compound.element(i), start);
        if (result >= start) {
          return result;
        }
      }
      return start - 1;
    } else if (template instanceof Template.RefVar rv) {
      assert rv.index >= start;
      return rv.index;
    } else if (template instanceof Template.Union union) {
      if (union.untagged != null) {
        return maxRefVarIndex(union.untagged, start);
      }
      // Since each choice assigns RefVar indices independently, we have to check all of them and
      // pick the largest.
      int max = start - 1;
      for (int i = 0; i < union.numChoices(); i++) {
        max = Math.max(max, maxRefVarIndex(union.choice(i), start));
      }
      return max;
    } else {
      assert template instanceof Template.Empty
          || template instanceof Template.Constant
          || template instanceof Template.NumVar;
      return start - 1;
    }
  }

  /**
   * RecordLayout's VarAllocator assigns sequential indices for RefVars, but NumVar indices are
   * offsets into a block of bytes.
   */
  static class VarAllocator implements TemplateBuilder.VarAllocator {
    /**
     * The offset at which the next uint8 var could be assigned, or -1 if {@link #setUpgradeSubInts}
     * has been called.
     */
    private int nextB;

    /** The offset at which the next int32 var could be assigned. */
    private int nextI;

    /** The offset at which the next float64 var could be assigned. */
    private int nextD;

    // The not-yet-allocated bytes are the union of
    //   - the rest of the int containing nextB (if nextB >= 0), i.e. [nextB .. (nextB & ~3) + 3]
    //   - the int at nextI, i.e. [nextI .. nextI + 3]
    //   - everything from nextD onward, i.e. [nextD ..]
    //
    // Those ranges may or may not overlap.

    /** The index of the next refvar. */
    private int nextX;

    private boolean dropToBeSetFromUnions = false;

    private VarAllocator(int dAlignment) {
      assert dAlignment == 0 || dAlignment == 4;
      nextD = dAlignment;
    }

    private VarAllocator(VarAllocator toDuplicate) {
      nextB = toDuplicate.nextB;
      nextI = toDuplicate.nextI;
      nextD = toDuplicate.nextD;
      nextX = toDuplicate.nextX;
      dropToBeSetFromUnions = toDuplicate.dropToBeSetFromUnions;
    }

    /** Returns a new VarAllocator that will allocate doubles at offsets 0, 8, 16, etc. */
    static VarAllocator newWithAlignedDoubles() {
      return new VarAllocator(0);
    }

    /** Returns a new VarAllocator that will allocate doubles at offsets 4, 12, 20, etc. */
    static VarAllocator newWithOffsetDoubles() {
      return new VarAllocator(4);
    }

    /**
     * Returns a new VarAllocator appropriate for use with RecordLayout frames.
     *
     * <p>With the current layout of Frame objects (12-byte Java object header and two 4-byte
     * fields, refCnt and layout) the first byte we allocate is going to be 4-byte aligned but not
     * 8-byte aligned, so the offset will give us properly-aligned doubles.
     */
    static VarAllocator newForRecordLayout() {
      return newWithOffsetDoubles();
    }

    /**
     * Should only be called on a newly-allocated VarAllocator; marks this allocator so that any
     * call to allocate a NumVar with encoding smaller than an INT32 will allocate an INT32. Returns
     * this.
     */
    VarAllocator setUpgradeSubInts() {
      assert nextB == 0 && nextI == 0 && (nextD & ~4) == 0 && nextX == 0;
      nextB = -1;
      return this;
    }

    /**
     * Should only be called on a newly-allocated VarAllocator; indicates that this allocator should
     * not create templates containing {@link Core#TO_BE_SET}. Returns this.
     */
    VarAllocator setDropToBeSetFromUnions() {
      assert nextB <= 0 && nextI == 0 && (nextD & ~4) == 0 && nextX == 0;
      dropToBeSetFromUnions = true;
      return this;
    }

    @Override
    public boolean dropToBeSetFromUnions() {
      return dropToBeSetFromUnions;
    }

    /**
     * Returns true if nextB, nextI, and nextD are related as expected. Used only for assertions.
     */
    private boolean invariant() {
      // nextB <= nextI <= nextD
      // nextI and nextD are int-aligned
      // if nextB != nextI, nextB is not int-aligned
      // if nextI != nextD, nextI does not have the same double-alignment as nextD
      return nextI >= nextB
          && nextD >= nextI
          && (nextI & 3) == 0
          && (nextD & 3) == 0
          && (nextB == nextI || (nextB & 3) != 0)
          && (nextI == nextD || (nextI & 7) != (nextD & 7));
    }

    private boolean upgradeSubInts() {
      return nextB < 0;
    }

    /**
     * Returns the minimum number of bytes required to hold the numeric vars, rounded up to the
     * nearest multiple of 4.
     */
    int byteSize() {
      // If nextD is only greater than nextI because of alignment requirements, we can use nextI;
      // otherwise we have to use nextD.
      int byteSize = (nextI + 4 == nextD) ? nextI : nextD;
      assert (byteSize & 3) == 0;
      return byteSize;
    }

    /** Returns the minimum nInts required for a Frame to hold these vars. */
    int intSize() {
      return byteSize() / SizeOf.INT;
    }

    /** Returns the minimum nPtrs required for a Frame to hold these vars. */
    int ptrSize() {
      return nextX;
    }

    @Override
    public NumVar allocNumVar(NumVar forEncoding) {
      assert invariant();
      if (forEncoding.encoding == NumEncoding.UINT8 && upgradeSubInts()) {
        forEncoding = NumVar.INT32;
      }
      // In addition to advancing the offset for the type we're allocating, we may have to advance
      // the offsets of other types to avoid overlap.
      int offset;
      switch (forEncoding.encoding) {
        case UINT8:
          assert !upgradeSubInts();
          offset = nextB;
          if (nextI == offset) {
            if (nextI == nextD) {
              nextI += 4;
              nextD += 8;
            } else {
              nextI = nextD;
            }
          }
          nextB = ((nextB & 3) != 3) ? nextB + 1 : nextI;
          break;
        case INT32:
          offset = nextI;
          if (nextI == nextD) {
            nextI += 4;
            nextD += 8;
          } else {
            nextI = nextD;
          }
          if (nextB == offset) {
            assert !upgradeSubInts();
            nextB = nextI;
          }
          break;
        case FLOAT64:
          offset = nextD;
          nextD += 8;
          if (nextI == offset) {
            nextI = nextD;
          }
          if (nextB == offset) {
            assert !upgradeSubInts();
            nextB = nextD;
          }
          break;
        default:
          throw new AssertionError();
      }
      assert invariant();
      return forEncoding.withIndex(offset);
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
      this.nextB = other.nextB;
      this.nextI = other.nextI;
      this.nextD = other.nextD;
      this.nextX = other.nextX;
    }

    @Override
    public void union(TemplateBuilder.VarAllocator otherAllocator) {
      VarAllocator other = (VarAllocator) otherAllocator;
      int newNextD = Math.max(this.nextD, other.nextD);
      int newNextI;
      if (this.nextI == other.nextI || this.nextI >= other.nextD) {
        newNextI = this.nextI;
      } else if (other.nextI >= this.nextD) {
        newNextI = other.nextI;
      } else {
        newNextI = newNextD;
      }
      assert upgradeSubInts() == other.upgradeSubInts();
      if (!upgradeSubInts()) {
        int nextBint = this.nextB & ~3;
        int otherNextBint = other.nextB & ~3;
        if (nextBint == otherNextBint) {
          this.nextB = Math.max(this.nextB, other.nextB);
        } else if (nextBint == other.nextI || nextBint >= other.nextD) {
          // keep it
        } else if (otherNextBint == this.nextI || otherNextBint >= this.nextD) {
          this.nextB = other.nextB;
        } else {
          this.nextB = newNextI;
        }
      }
      this.nextI = newNextI;
      this.nextD = newNextD;
      this.nextX = Math.max(this.nextX, other.nextX);
      assert invariant();
    }

    @Override
    public String toString() {
      // This is only intended to be useful for tests
      if (upgradeSubInts()) {
        return String.format("%si-%sd-%sx", nextI, nextD, nextX);
      } else {
        return String.format("%sb-%si-%sd-%sx", nextB, nextI, nextD, nextX);
      }
    }
  }
}
