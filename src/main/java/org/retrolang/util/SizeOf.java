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

/// Static-only class with constants and methods useful for computing the size of various java
/// objects.
public class SizeOf {

  // Statics only
  private SizeOf() {}

  /// The number of bytes required for a pointer.
  public static final int PTR = 4;

  /// The number of bytes required for a boolean.
  public static final int BOOLEAN = 1;

  /// The number of bytes required for a byte.
  public static final int BYTE = 1;

  /// The number of bytes required for a short.
  public static final int SHORT = 2;

  /// The number of bytes required for a int.
  public static final int INT = 4;

  /// The number of bytes required for a long.
  public static final int LONG = 8;

  /// The number of bytes required for a float.
  public static final int FLOAT = 4;

  /// The number of bytes required for a double.
  public static final int DOUBLE = 8;

  /// The number of additional bytes stored with a non-array object.
  ///
  /// In some versions of Java this is now only 8.
  public static final int OBJECT_HEADER = 12;

  /// The number of additional bytes stored with an array object.
  ///
  /// In some versions of Java, for some types of arrays, this is now only 12.
  public static final int ARRAY_HEADER = 16;

  /// Rounds up to the nearest multiple of 8, the minimum unit of Java memory allocation.
  public static long roundedAllocSize(long size) {
    return (size + 7) & ~7;
  }

  /// Returns the number of bytes required for a java object, given the number of bytes required for
  /// its fields.
  ///
  /// Adds 12 bytes to fieldsSize for the header and then rounds up to the nearest multiple of 8.
  public static long object(long fieldsSize) {
    return roundedAllocSize(OBJECT_HEADER + fieldsSize);
  }

  /// Returns the number of bytes required for a Java array, given the number of elements and the
  /// size of each element.
  ///
  /// The elementSize should not be the size of an object referred to; those objects must be
  /// accounted for separately.
  ///
  /// Adds 16 bytes to the total data size for the header and then rounds up to the nearest multiple
  /// of 8.
  public static long array(long length, long elementSize) {
    // The length and size are taken separately to ensure the multiplication
    // is not done in ints and therefore potentially subject to overflow.
    return roundedAllocSize(ARRAY_HEADER + length * elementSize);
  }

  /// Returns the number of bytes required for a given java array.
  public static long array(Object[] array) {
    return (array == null) ? 0 : SizeOf.array(array.length, PTR);
  }

  /// Returns the number of bytes required for a given java array.
  public static long array(byte[] array) {
    return (array == null) ? 0 : SizeOf.array(array.length, BYTE);
  }

  /// Returns the number of bytes required for a given java array.
  public static long array(boolean[] array) {
    return (array == null) ? 0 : SizeOf.array(array.length, BOOLEAN);
  }

  /// Returns the number of bytes required for a given java array.
  public static long array(int[] array) {
    return (array == null) ? 0 : SizeOf.array(array.length, INT);
  }

  /// Returns the number of bytes required for a given java array.
  public static long array(long[] array) {
    return (array == null) ? 0 : SizeOf.array(array.length, LONG);
  }

  /// Returns the number of bytes required for a given java array.
  public static long array(float[] array) {
    return (array == null) ? 0 : SizeOf.array(array.length, FLOAT);
  }

  /// Returns the number of bytes required for a given java array.
  public static long array(double[] array) {
    return (array == null) ? 0 : SizeOf.array(array.length, DOUBLE);
  }

  /// Returns the number of bytes required to hold a given String.
  public static long string(String string) {
    return SizeOf.object(SizeOf.PTR + SizeOf.INT) + SizeOf.array(string.length(), 2);
  }

  /// Returns true if the argument is a valid size for a Java object.
  public static boolean isValidSize(long size) {
    // The Java VM currently allocates in units of 8 bytes.
    return size > 0 && (size & 7) == 0;
  }
}
