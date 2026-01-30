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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle.AccessMode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.retrolang.code.CodeValue;
import org.retrolang.code.Op;
import org.retrolang.code.Op.OpEmit;
import org.retrolang.code.Register;
import org.retrolang.impl.Frame.Replacement;
import org.retrolang.impl.RcOp.RcOpBuilder;
import org.retrolang.util.SizeOf;

/**
 * Each subclass of Frame has a corresponding instance of FrameClass that provides information and
 * access methods.
 */
class FrameClass {
  /** The class described by this FrameClass instance. */
  final Class<? extends Frame> javaClass;

  /** A short, readable name for the class; used only for output. */
  final String name;

  /**
   * A distinct, sequential index is assigned to each Frame subclass; this gives us a fast way to
   * get from a Frame to its FrameClass, and might also be useful if we want to e.g. index into an
   * array.
   */
  final int index;

  /** A Supplier that allocates a new instance of the class. */
  final Supplier<?> alloc;

  /**
   * The number of generic pointer (Object) fields in this subclass. Currently constrained to be
   * even and at least two.
   */
  final int nPtrs;

  /**
   * The number of generic int fields in this subclass. These fields may be used to store any
   * non-pointer data (bytes, ints, or doubles). Currently constrained to be odd.
   */
  final int nInts;

  /**
   * The offset (in bytes) from the start of the object to the first pointer field. All pointer
   * fields must be contiguous and each occupies SizeOf.PTR bytes.
   */
  final int ptrBase;

  /**
   * The offset (in bytes) from the start of the object to the first int field. All int fields must
   * be contiguous and each occupies SizeOf.INT bytes.
   */
  final int intBase;

  /** The total size in bytes of an instance of this class. */
  final int byteSize;

  /** The expected name of an int field. */
  private static final Pattern INT_FIELD_NAME = Pattern.compile("i\\d+");

  /** The expected name of an Object field. */
  private static final Pattern PTR_FIELD_NAME = Pattern.compile("x\\d+");

  /** Ignore weird fields added by coverage analysis. */
  private static final Pattern IGNORED_FIELD_NAME = Pattern.compile("__.*__");

  /**
   * An Op to return the value of each int field from an instance of this subclass; length is {@code
   * nInts}.
   *
   * <p>Applying {@code getIntField.get(i)} to {@code f} is equivalent to calling {@code getI(f,
   * i)}.
   */
  final ImmutableList<Op> getIntField;

  /**
   * An Op to set the value of each int field in an instance of this subclass; length is {@code
   * nInts}.
   *
   * <p>Applying {@code setIntField.get(i)} to {@code f} and {@code v} is equivalent to calling
   * {@code setI(f, i, v)}.
   */
  final ImmutableList<Op> setIntField;

  /**
   * An Op to return the value of each Object field from an instance of this subclass; length is
   * {@code nPtrs}.
   *
   * <p>Applying {@code getPtrField.get(i)} to {@code f} is equivalent to calling {@code getX(f,
   * i)}.
   */
  final ImmutableList<Op> getPtrField;

  /**
   * An Op to return the value of each Object field from an instance of this subclass as a Value;
   * length is {@code nPtrs}.
   *
   * <p>Applying {@code getValueField.get(i)} to {@code f} is equivalent to calling {@code
   * getValue(f, i)}.
   */
  final ImmutableList<Op> getValueField;

  /**
   * An Op to set the value of each Object field in an instance of this subclass; length is {@code
   * nPtrs}.
   *
   * <p>Applying {@code setPtrField.get(i)} to {@code f} and {@code v} is equivalent to calling
   * {@code setX(f, i, v)}.
   */
  final ImmutableList<Op> setPtrField;

  /**
   * An Op to return the value of each Object field in an instance of this subclass and set it to
   * null; length is {@code nPtrs}.
   *
   * <p>Applying {@code takePtrField.get(i)} to {@code f} is equivalent to calling {@code takeX(f,
   * i)}.
   */
  final ImmutableList<Op> takePtrField;

  /**
   * An Op to return each byte in an instance of this subclass; length is {@code 4 * nInts}.
   *
   * <p>Applying {@code getByteField.get(i)} to {@code f} is equivalent to calling {@code getB(f,
   * i)}.
   */
  final ImmutableList<Op> getByteField;

  /**
   * An Op to set each byte in an instance of this subclass; length is {@code 4 * nInts}.
   *
   * <p>Applying {@code setByteField.get(i)} to {@code f} and {@code v} is equivalent to calling
   * {@code setB(f, i, v)}.
   */
  final ImmutableList<Op> setByteField;

  /**
   * An Op to return each double in an instance of this subclass; length is {@code nInts / 2}.
   *
   * <p>Applying {@code getDoubleField.get(i)} to {@code f} is equivalent to calling {@code getD(f,
   * i)}.
   */
  final ImmutableList<Op> getDoubleField;

  /**
   * An Op to set each double in an instance of this subclass; length is {@code nInts / 2}.
   *
   * <p>Applying {@code setDoubleField.get(i)} to {@code f} and {@code v} is equivalent to calling
   * {@code getD(f, i, v)}.
   */
  final ImmutableList<Op> setDoubleField;

  FrameClass(Class<? extends Frame> javaClass) {
    Preconditions.checkArgument(javaClass.getSuperclass() == Frame.class);
    this.javaClass = javaClass;
    this.name = Handle.simpleName(javaClass);
    MethodHandle allocMh = Handle.forConstructor(javaClass);
    this.alloc = MethodHandleProxies.asInterfaceInstance(Supplier.class, allocMh);
    this.index = ((Frame) alloc.get()).frameClassIndex();
    // Sanity-check all of the Frame subclass's fields, and make sure that all our assumptions hold.
    int nInts = 0;
    int nPtrs = 0;
    int intBase;
    int ptrBase;
    try {
      // The first (in layout order) int field should be named "i0",
      // and the first pointer field should be named "x0"
      intBase = offset(javaClass.getDeclaredField("i0"));
      ptrBase = offset(javaClass.getDeclaredField("x0"));
    } catch (NoSuchFieldException e) {
      throw new AssertionError(e);
    }
    // The first int field is expected to *not* be 8-byte-aligned.
    // See RecordLayout.VarAllocator.newForRecordLayout().
    Preconditions.checkState((intBase & 7) == 4);

    // intFields.get(i) will have offset intBase + 4 * i
    // ptrFields.get(i) will have offset ptrBase + 4 * i
    List<Field> intFields = new ArrayList<>();
    List<Field> ptrFields = new ArrayList<>();
    // All of the fields should be named "i42" or "x42" (for some value of 42), and they should be
    // laid out in sequence.  This is made a little complicated because the results of
    // getDeclaredFields() aren't in any helpful order.
    // (The JVM doesn't promise any particular layout order, but it mostly does the obvious thing.
    // Ensuring that fields are laid out in the expected order helps make debugging less confusing.)
    for (Field field : javaClass.getDeclaredFields()) {
      String name = field.getName();
      if (IGNORED_FIELD_NAME.matcher(name).matches()) {
        continue;
      }
      int offset = offset(field);
      if (field.getType() == int.class) {
        Preconditions.checkArgument(INT_FIELD_NAME.matcher(name).matches());
        int index = Integer.parseInt(name.substring(1));
        Preconditions.checkArgument(offset == intBase + index * SizeOf.INT);
        ++nInts;
        add(intFields, index, field);
      } else {
        Preconditions.checkArgument(field.getType() == Object.class);
        Preconditions.checkArgument(PTR_FIELD_NAME.matcher(name).matches());
        int index = Integer.parseInt(name.substring(1));
        Preconditions.checkArgument(offset == ptrBase + index * SizeOf.PTR);
        ++nPtrs;
        add(ptrFields, index, field);
      }
    }
    // There should be an odd number of int fields and an even (but non-zero) number of pointer
    // fields.
    Preconditions.checkArgument((nInts & 1) == 1 && nPtrs != 0 && (nPtrs & 1) == 0);
    // The int fields should all be contiguous, as should the pointer fields.
    Preconditions.checkArgument(intFields.size() == nInts);
    Preconditions.checkArgument(ptrFields.size() == nPtrs);
    this.nPtrs = nPtrs;
    this.nInts = nInts;
    this.ptrBase = ptrBase;
    this.intBase = intBase;
    // Compute the object size two different ways and confirm that we get the same answer.
    int byteSize =
        Math.toIntExact(
            SizeOf.object(Frame.BASE_SIZE + nPtrs * (long) SizeOf.PTR + nInts * (long) SizeOf.INT));
    Preconditions.checkArgument(
        byteSize == Math.max(intBase + nInts * SizeOf.INT, ptrBase + nPtrs * SizeOf.PTR));
    this.byteSize = byteSize;

    ImmutableList.Builder<Op> getIntField = ImmutableList.builder();
    ImmutableList.Builder<Op> setIntField = ImmutableList.builder();
    for (Field f : intFields) {
      Op get = Op.getField(javaClass, f.getName(), int.class);
      getIntField.add(get);
      setIntField.add(get.setter().build());
    }
    this.getIntField = getIntField.build();
    this.setIntField = setIntField.build();

    ImmutableList.Builder<Op> getPtrField = ImmutableList.builder();
    ImmutableList.Builder<Op> getValueField = ImmutableList.builder();
    ImmutableList.Builder<Op> setPtrField = ImmutableList.builder();
    ImmutableList.Builder<Op> takePtrField = ImmutableList.builder();
    for (int i = 0; i < nPtrs; i++) {
      Field f = ptrFields.get(i);
      Op get = Op.getField(javaClass, f.getName(), Object.class);
      getPtrField.add(get);
      Op set = new RcOpBuilder(get.setter().build()).argIsRcIn(1).build();
      setPtrField.add(set);
      Op take = new RcOpBuilder(get.taker().build()).resultIsRcOut().build();
      takePtrField.add(take);

      // A MethodHandle Object <- (Object, Frame) that checks whether its first arg is a
      // replaced frame, and if so returns the replacement and updates this pointer field in the
      // second arg
      MethodHandle mh = MethodHandles.insertArguments(CHECK_FOR_REPLACEMENT, 0, this, i);
      // The same MethodHandle, but with type Object <- (Object, javaClass)
      mh = mh.asType(mh.type().changeParameterType(1, javaClass));
      // A MethodHandle Object <- (javaClass) that retrieves the pointer field of its arg, checks
      // if it is a replaced frame, and if so returns the replacement and updates the field
      MethodHandle getValueMH =
          MethodHandles.foldArguments(mh, Handle.forVar(f).toMethodHandle(AccessMode.GET));
      getValueField.add(Op.forMethodHandle(get.name + ".getValue", getValueMH).build());
    }
    this.getPtrField = getPtrField.build();
    this.getValueField = getValueField.build();
    this.setPtrField = setPtrField.build();
    this.takePtrField = takePtrField.build();

    ImmutableList.Builder<Op> getByteField = ImmutableList.builder();
    ImmutableList.Builder<Op> setByteField = ImmutableList.builder();
    for (int i = 0; i < 4 * nInts; i++) {
      String fName = name + ".b" + i;
      int offset = i + intBase;
      OpEmit emitGet =
          (emitter, opResult) -> Unsafe.emitGetByte(emitter, opResult.args.get(0), offset);
      getByteField.add(Op.simple(fName, emitGet, int.class, javaClass).build());
      OpEmit emitSet =
          (emitter, opResult) ->
              Unsafe.emitPutByte(emitter, opResult.args.get(0), offset, opResult.args.get(1));
      setByteField.add(
          Op.simple(fName + ".set", emitSet, void.class, javaClass, int.class).build());
    }
    this.getByteField = getByteField.build();
    this.setByteField = setByteField.build();

    ImmutableList.Builder<Op> getDoubleField = ImmutableList.builder();
    ImmutableList.Builder<Op> setDoubleField = ImmutableList.builder();
    for (int i = 0; i < nInts / 2; i++) {
      String fName = name + ".d" + i;
      int offset = SizeOf.DOUBLE * i + 4 + intBase;
      OpEmit emitGet =
          (emitter, opResult) -> Unsafe.emitGetDouble(emitter, opResult.args.get(0), offset);
      getDoubleField.add(Op.simple(fName, emitGet, double.class, javaClass).build());
      OpEmit emitSet =
          (emitter, opResult) ->
              Unsafe.emitPutDouble(emitter, opResult.args.get(0), offset, opResult.args.get(1));
      setDoubleField.add(
          Op.simple(fName + ".set", emitSet, void.class, javaClass, double.class).build());
    }
    this.getDoubleField = getDoubleField.build();
    this.setDoubleField = setDoubleField.build();
  }

  private static int offset(Field field) {
    try {
      return Math.toIntExact(Unsafe.fieldOffset(field));
    } catch (RuntimeException e) {
      throw new AssertionError("Can't get offset of " + field, e);
    }
  }

  /**
   * Sets the specified element of {@code list} (which must be null) to {@code element}. If {@code
   * list}'s size is less than or equal to {@code index}, first pads it with nulls.
   */
  private static <T> void add(List<T> list, int index, T element) {
    if (list.size() > index) {
      Preconditions.checkState(list.set(index, element) == null);
    } else {
      list.addAll(list.size(), Collections.nCopies(index - list.size(), null));
      list.add(element);
    }
  }

  /**
   * Chooses an optimal FrameClass to use for a layout that requires the specified number of ints
   * and pointers.
   */
  static FrameClass select(int nInts, int nPtrs) {
    // Find the FrameClass that requires the least total memory, taking any required overflow
    // arrays into account.
    // This uses the simplest possible approach: just try them all and pick the one with the lowest
    // cost.
    // Better would be to precompute the answer for (say) all combinations with nInts < 6 and
    // nPtrs < 6.
    FrameClass bestClass = null;
    long lowestCost = Integer.MAX_VALUE;
    for (FrameClass c : Frames.CLASSES) {
      long cost = c.byteSize;
      // That's a lower bound on our cost.
      if (cost >= lowestCost) {
        continue;
      }
      // If this class doesn't have enough int fields or pointer fields we'll need to use overflow
      // arrays, which will add to its cost.
      int ptrOverflow = nPtrs - c.nPtrs;
      int intOverflow = nInts - c.nInts;
      if (intOverflow > 0) {
        // Never put all our ints in the overflow.  This ensures that varrays can access their
        // length field directly, but is redundant given that we currently always allocate at least
        // one int field in each FrameClass.
        if (c.nInts == 0) {
          continue;
        }
        // An int overflow array is another pointer to keep track of.
        ptrOverflow++;
        // Add the extra memory to the cost, plus a penalty for the indirections required on each
        // access.
        cost += SizeOf.array(intOverflow, SizeOf.INT) + 2L * intOverflow;
      }
      if (ptrOverflow > 0) {
        // We need some fields to store the overflow arrays in.
        // Another test that can never fail with our current policy.
        if (c.nPtrs < 2) {
          continue;
        }
        // The pointer overflow array is itself another pointer.
        ptrOverflow++;
        cost += SizeOf.array(ptrOverflow, SizeOf.PTR) + 2L * ptrOverflow;
      }
      if (cost < lowestCost) {
        bestClass = c;
        lowestCost = cost;
      }
    }
    assert bestClass != null;
    return bestClass;
  }

  private static final Op ALLOC_OP =
      RcOp.forRcMethod(FrameClass.class, "alloc", Allocator.class).build();

  /** Returns a new instance of this subclass, updating memory counters accordingly. */
  Frame alloc(Allocator allocator) {
    // TODO: investigate adding a free list to MemoryHelper, as we currently do for arrays
    Frame result = (Frame) alloc.get();
    allocator.recordAlloc(result, byteSize);
    return result;
  }

  /** Returns a CodeValue that calls {@link #alloc(Allocator)} on this FrameClass. */
  CodeValue emitAlloc(Register tstate) {
    return ALLOC_OP.result(CodeValue.of(this), tstate);
  }

  /**
   * Multiple methods in this class use Unsafe to access an instance of this FrameClass. Before
   * doing so they should always call checkUnsafe with the instance (to ensure that it is actually
   * an instance of this FrameClass) and whatever other tests are necessary to confirm the safety of
   * the access. These tests should never fail, but we want to make sure that a bug somewhere else
   * won't cause these methods to do something terrible.
   */
  private void checkUnsafe(Object f, boolean safetyCheck) {
    Preconditions.checkArgument(javaClass.isInstance(f) && safetyCheck);
  }

  /** Returns true if given a valid byte offset into this FrameClass's non-pointer region. */
  private boolean isValidByteOffset(long offset) {
    return offset >= 0 && offset < nInts * (long) SizeOf.INT;
  }

  /**
   * Returns true if given a valid byte offset for an int in this FrameClass's non-pointer region.
   */
  private boolean isValidIntOffset(long offset) {
    return isValidByteOffset(offset) && (offset & 3) == 0;
  }

  /**
   * Returns true if given a valid byte offset for a double in this FrameClass's non-pointer region.
   */
  private boolean isValidDoubleOffset(long offset) {
    // Our constructor verified that intBase is 4 mod 8, so this check ensures that intBase + offset
    // is double-aligned.  Because nInts is always odd we don't have to worry about the double
    // extending past the end of the valid bytes.
    return isValidByteOffset(offset) && (offset & 7) == 4;
  }

  /** Returns true if given a valid index for an entry in this FrameClass's pointer region. */
  private boolean isValidPtrIndex(int index) {
    return index >= 0 && index < nPtrs;
  }

  /**
   * Returns the offset (from the beginning of an instance) of the pointer field with the given
   * index.
   */
  private int ptrOffset(int index) {
    return ptrBase + index * SizeOf.PTR;
  }

  /**
   * Implements {@link RefCounted#visitRefs} for a Frame using this FrameClass.
   *
   * @param nPtrs the number of pointers in the Frame's pointer region, including those for any
   *     overflow arrays but not including pointers in the overflow arrays.
   */
  long visitRefs(Frame f, RefVisitor visitor, int nPtrs) {
    if (nPtrs > 0) {
      // Make sure that a bug elsewhere won't cause us to do something bad with Unsafe below.
      checkUnsafe(f, nPtrs <= this.nPtrs);
      boolean releasing = MemoryHelper.isReleaser(visitor);
      for (int i = 0; i < nPtrs; i++) {
        long offset = ptrOffset(i);
        Object obj = Unsafe.getObject(f, offset);
        if (obj != null) {
          if (releasing) {
            // Not really necessary as long as we're immediately dropping all
            // released Frames, but in anticipation of adding a free list to TState.
            Unsafe.putObject(f, offset, null);
          }
          visitor.visit(obj);
        }
      }
    }
    return byteSize;
  }

  /**
   * Returns the unsigned byte at the specified offset in the given object, which must be an
   * instance of this FrameClass.
   */
  int getB(Object f, int offset) {
    checkUnsafe(f, isValidByteOffset(offset));
    return Unsafe.getByte(f, intBase + offset) & 255;
  }

  /**
   * Sets the byte at the specified offset in the given object, which must be an instance of this
   * FrameClass.
   */
  void setB(Object f, long offset, int value) {
    assert value >= 0 && value < 256;
    checkUnsafe(f, isValidByteOffset(offset));
    Unsafe.putByte(f, intBase + offset, (byte) value);
  }

  /**
   * Returns the int at the specified offset in the given object, which must be an instance of this
   * FrameClass.
   */
  int getI(Object f, int offset) {
    checkUnsafe(f, isValidIntOffset(offset));
    return Unsafe.getInt(f, intBase + offset);
  }

  /**
   * Sets the int at the specified offset in the given object, which must be an instance of this
   * FrameClass.
   */
  void setI(Object f, long offset, int value) {
    checkUnsafe(f, isValidIntOffset(offset));
    Unsafe.putInt(f, intBase + offset, value);
  }

  /**
   * Returns the double at the specified offset in the given object, which must be an instance of
   * this FrameClass.
   */
  double getD(Object f, int offset) {
    checkUnsafe(f, isValidDoubleOffset(offset));
    return Unsafe.getDouble(f, intBase + offset);
  }

  /**
   * Sets the double at the specified offset in the given object, which must be an instance of this
   * FrameClass.
   */
  void setD(Object f, long offset, double value) {
    checkUnsafe(f, isValidDoubleOffset(offset));
    Unsafe.putDouble(f, intBase + offset, value);
  }

  /**
   * Returns the specified pointer from the given object, which must be an instance of this
   * FrameClass.
   */
  Object getX(Frame f, int index) {
    checkUnsafe(f, isValidPtrIndex(index));
    return Unsafe.getObject(f, ptrOffset(index));
  }

  /** Returns the same value as {@link #getX}, but cast to a byte[]. */
  byte[] getBytes(Frame f, int index) {
    return (byte[]) getX(f, index);
  }

  /** Returns the same value as {@link #getX}, but cast to a Value. */
  Value getValue(Frame f, int index) {
    return (Value) checkForReplacement(index, getX(f, index), f);
  }

  /**
   * A MethodHandle of type `Object <- (FrameClass, int, Object, Frame)` that calls {@link
   * #checkForReplacement}.
   */
  private static final MethodHandle CHECK_FOR_REPLACEMENT =
      Handle.forMethod(
          FrameClass.class, "checkForReplacement", int.class, Object.class, Frame.class);

  /**
   * Checks the value we've just read from the specified pointer field to see if it's a Frame that
   * has evolved. If so, returns its replacement and tries to update the field in {@code f}.
   */
  Object checkForReplacement(int index, Object v, Frame f) {
    if (v instanceof Frame vf) {
      Replacement replacement = Frame.getReplacement(vf);
      if (replacement != null) {
        Frame result = replacement.newFrame;
        if (replacement.isComplete()) {
          // This frame's replacement is visible to everyone, so we can update
          // the source to point to the replacement.
          TState tstate = TState.get();
          if (tstate == null || tstate.tracker() == null) {
            // This thread isn't bound to a ResourceTracker (probably it's just trying to toString()
            // a Value it encountered somewhere), so it's not allowed to modify anything.
            return result;
          }
          // Since this is a rarely-taken code path, I'm OK taking a little time to
          // be paranoid before calling Unsafe.
          checkUnsafe(f, isValidPtrIndex(index));
          if (Unsafe.compareAndSwapObject(f, ptrOffset(index), vf, result)) {
            result.addRef();
            tstate.dropReference(vf);
          }
        }
        return result;
      }
    }
    return v;
  }

  /**
   * Sets the specified pointer in the given object, which must be an instance of this FrameClass.
   */
  void setX(Frame f, int index, Object value) {
    checkUnsafe(f, isValidPtrIndex(index));
    Unsafe.putObject(f, ptrOffset(index), value);
  }

  /**
   * Nulls the specified pointer in the given object (which must be an instance of this FrameClass)
   * and returns its previous value.
   *
   * <p>(The read/write is not atomic, so if another thread were performing the same action both
   * might see the same non-null value; in practice this method is only used when we know the object
   * is unshared.)
   */
  @RC.Out
  Object takeX(Frame f, int index) {
    checkUnsafe(f, isValidPtrIndex(index));
    long offset = ptrOffset(index);
    Object result = Unsafe.getObject(f, offset);
    Unsafe.putObject(f, offset, null);
    return result;
  }

  @Override
  public String toString() {
    return name;
  }
}
