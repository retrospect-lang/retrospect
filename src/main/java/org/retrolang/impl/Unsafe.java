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

import java.lang.reflect.Field;
import org.retrolang.code.CodeValue;
import org.retrolang.code.Emitter;
import org.retrolang.code.Op;
import org.retrolang.util.SizeOf;

/**
 * A statics-only class providing a simpler API for the parts of sun.misc.Unsafe that we use.
 *
 * <p>THESE METHODS ARE DANGEROUS: the caller is assumed to have done all appropriate checks before
 * calling them.
 */
class Unsafe {

  private static final sun.misc.Unsafe UNSAFE;
  private static final CodeValue UNSAFE_AS_CODE_VALUE;

  static {
    // They don't make it easy to get to the actual Unsafe instance.
    try {
      Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      UNSAFE = (sun.misc.Unsafe) f.get(null);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    // Sanity check some assumptions made by the rest of the code.
    assert sun.misc.Unsafe.ARRAY_BYTE_INDEX_SCALE == SizeOf.BYTE;
    assert sun.misc.Unsafe.ARRAY_INT_INDEX_SCALE == SizeOf.INT;
    assert sun.misc.Unsafe.ARRAY_LONG_INDEX_SCALE == SizeOf.LONG;
    assert sun.misc.Unsafe.ARRAY_DOUBLE_INDEX_SCALE == SizeOf.DOUBLE;
    assert sun.misc.Unsafe.ARRAY_OBJECT_INDEX_SCALE == SizeOf.PTR;
    UNSAFE_AS_CODE_VALUE = CodeValue.of(UNSAFE);
  }

  /** Returns the offset of the specified field from the beginning of the object. */
  static long fieldOffset(Class<?> cls, String fieldName) {
    try {
      return UNSAFE.objectFieldOffset(cls.getDeclaredField(fieldName));
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("Shouldn't happen", e);
    }
  }

  /** Returns the offset of the specified field from the beginning of the object. */
  static long fieldOffset(Field field) {
    return UNSAFE.objectFieldOffset(field);
  }

  /** Returns the contents of a byte field at the specified offset. */
  static byte getByte(Object o, long offset) {
    return UNSAFE.getByte(o, offset);
  }

  /** Sets the contents of a byte field at the specified offset. */
  static void putByte(Object o, long offset, byte x) {
    UNSAFE.putByte(o, offset, x);
  }

  /** Returns the contents of an int field at the specified offset. */
  static int getInt(Object o, long offset) {
    return UNSAFE.getInt(o, offset);
  }

  /** Sets the contents of an int field at the specified offset. */
  static void putInt(Object o, long offset, int x) {
    UNSAFE.putInt(o, offset, x);
  }

  /** Returns the contents of a double field at the specified offset. */
  static double getDouble(Object o, long offset) {
    return UNSAFE.getDouble(o, offset);
  }

  /** Sets the contents of a double field at the specified offset. */
  static void putDouble(Object o, long offset, double x) {
    UNSAFE.putDouble(o, offset, x);
  }

  /** Returns the contents of a pointer field at the specified offset. */
  static Object getObject(Object o, long offset) {
    return UNSAFE.getObject(o, offset);
  }

  /** Sets the contents of a pointer field at the specified offset. */
  static void putObject(Object o, long offset, Object x) {
    UNSAFE.putObject(o, offset, x);
  }

  /**
   * Atomically sets the contents of a pointer field at the specified offset if it currently has the
   * specified value.
   */
  static boolean compareAndSwapObject(Object o, long offset, Object expected, Object x) {
    return UNSAFE.compareAndSwapObject(o, offset, expected, x);
  }

  static void emitGetByte(Emitter emitter, CodeValue obj, long offset) {
    GET_BYTE.emit(emitter, UNSAFE_AS_CODE_VALUE, obj, CodeValue.of(offset));
  }

  static void emitPutByte(Emitter emitter, CodeValue obj, long offset, CodeValue x) {
    PUT_BYTE.emit(emitter, UNSAFE_AS_CODE_VALUE, obj, CodeValue.of(offset), x);
  }

  static void emitGetDouble(Emitter emitter, CodeValue obj, long offset) {
    GET_DOUBLE.emit(emitter, UNSAFE_AS_CODE_VALUE, obj, CodeValue.of(offset));
  }

  static void emitPutDouble(Emitter emitter, CodeValue obj, long offset, CodeValue x) {
    PUT_DOUBLE.emit(emitter, UNSAFE_AS_CODE_VALUE, obj, CodeValue.of(offset), x);
  }

  private static final Op GET_BYTE =
      Op.forMethod(sun.misc.Unsafe.class, "getByte", Object.class, long.class).build();

  private static final Op PUT_BYTE =
      Op.forMethod(sun.misc.Unsafe.class, "putByte", Object.class, long.class, byte.class).build();

  private static final Op GET_DOUBLE =
      Op.forMethod(sun.misc.Unsafe.class, "getDouble", Object.class, long.class).build();

  private static final Op PUT_DOUBLE =
      Op.forMethod(sun.misc.Unsafe.class, "putDouble", Object.class, long.class, double.class)
          .build();

  private Unsafe() {}
}
