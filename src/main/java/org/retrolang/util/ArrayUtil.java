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

package org.retrolang.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/// A static-only class that provides some convenience methods for working with arrays.
public class ArrayUtil {

  private ArrayUtil() {}

  /// Returns the hashCode of the first `length` elements of `objs`. Equivalent to
  /// `Arrays.hashCode(Arrays.copyOf(objs, length))`, except that `length` must be less than or
  /// equal to `objs.length`.
  public static int hashCode(Object[] objs, int length) {
    int result = 1;
    for (int i = 0; i < length; i++) {
      result = result * 31 + Objects.hashCode(objs[i]);
    }
    return result;
  }

  /// A VarHandle that allows us to access a byte[] as an int[].
  public static final VarHandle BYTES_AS_INTS =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());

  /// A VarHandle that allows us to access a byte[] as a double[].
  public static final VarHandle BYTES_AS_DOUBLES =
      MethodHandles.byteArrayViewVarHandle(double[].class, ByteOrder.nativeOrder());

  /// A VarHandle to access the elements of an Object[].
  public static final VarHandle OBJECT_ARRAY_ELEMENT =
      MethodHandles.arrayElementVarHandle(Object[].class);

  /// Interprets the byte at the specified offset as a uint8.
  public static int bytesGetB(byte[] array, int pos) {
    return array[pos] & 255;
  }

  /// Interprets the bytes beginning at the specified index as an int.
  public static int bytesGetI(byte[] array, int index) {
    return bytesGetIAtOffset(array, index * SizeOf.INT);
  }

  /// Interprets the bytes beginning at the specified offset as an int.
  public static int bytesGetIAtOffset(byte[] array, int pos) {
    return (int) BYTES_AS_INTS.get(array, pos);
  }

  /// Interprets the bytes beginning at the specified index as a double.
  public static double bytesGetD(byte[] array, int index) {
    return bytesGetDAtOffset(array, index * SizeOf.DOUBLE);
  }

  /// Interprets the bytes beginning at the specified byte offset as a double.
  public static double bytesGetDAtOffset(byte[] array, int pos) {
    return (double) BYTES_AS_DOUBLES.get(array, pos);
  }

  /// Stores a uint8 at the specified offset.
  public static void bytesSetB(byte[] array, int pos, int value) {
    assert value >= 0 && value < 256;
    array[pos] = (byte) value;
  }

  /// Stores an int beginning at the specified index.
  public static void bytesSetI(byte[] array, int index, int value) {
    bytesSetIAtOffset(array, index * SizeOf.INT, value);
  }

  /// Stores an int beginning at the specified offset.
  public static void bytesSetIAtOffset(byte[] array, int pos, int value) {
    BYTES_AS_INTS.set(array, pos, value);
  }

  /// Stores a double beginning at the specified offset.
  public static void bytesSetD(byte[] array, int index, double value) {
    bytesSetDAtOffset(array, index * SizeOf.DOUBLE, value);
  }

  /// Stores a double beginning at the specified offset.
  public static void bytesSetDAtOffset(byte[] array, int pos, double value) {
    BYTES_AS_DOUBLES.set(array, pos, value);
  }

  public static void bytesFillB(byte[] array, int start, int end, int value) {
    assert value >= 0 && value < 256;
    Arrays.fill(array, start, end, (byte) value);
  }

  public static void bytesFillI(byte[] array, int start, int end, int value) {
    for (int i = start; i < end; i++) {
      bytesSetI(array, i, value);
    }
  }

  public static void bytesFillD(byte[] array, int start, int end, double value) {
    for (int i = start; i < end; i++) {
      bytesSetD(array, i, value);
    }
  }

  /// Given the sequence of integers obtained by calling `elements` with 0 through length-1,
  ///
  /// - If any of them are negative, returns -1.
  /// - Otherwise, if any of them are zero returns zero.
  /// - Otherwise computes and returns their product, or -1 if the product overflows an int.
  public static int productAsInt(IntUnaryOperator elements, int length) {
    int result = 1;
    for (int i = 0; i < length; i++) {
      int element = elements.applyAsInt(i);
      if (element < 0) {
        return -1;
      } else if (element == 0 || result > 0) {
        result = multiplyExactOrMinusOne(result, element);
      }
    }
    return result;
  }

  /// Returns `x * y`, or -1 if the result overflows an int.
  public static int multiplyExactOrMinusOne(int x, int y) {
    try {
      return Math.multiplyExact(x, y);
    } catch (ArithmeticException unused) {
      return -1;
    }
  }
}
