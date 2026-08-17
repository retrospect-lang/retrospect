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

import com.google.common.base.Ascii;
import org.retrolang.util.SizeOf;

/// A NumEncoding determines how a NumVar's possible values are represented by a fixed-length
/// sequence of bytes.
public enum NumEncoding {
  UINT8("b", SizeOf.BYTE),
  INT32("i", SizeOf.INT),
  FLOAT64("d", SizeOf.DOUBLE);

  /// A single-letter prefix ("b", "i", or "d") used for NumVars with this encoding.
  final String prefix;

  /// The upper-case version of [#prefix].
  final String upperPrefix;

  /// The number of bytes required for a NumVar with this encoding.
  final int nBytes;

  /// The shift required to convert a number of elements with this encoding into a number of bytes;
  /// 0 for UINT8, 2 for INT, and 3 for DOUBLE.
  final int nBytesLog2;

  NumEncoding(String prefix, int nBytes) {
    assert Integer.bitCount(nBytes) == 1;
    this.prefix = prefix;
    this.upperPrefix = Ascii.toUpperCase(prefix);
    this.nBytes = nBytes;
    this.nBytesLog2 = Integer.numberOfTrailingZeros(nBytes);
  }

  public static boolean isUint8(int i) {
    return i >= 0 && i <= 255;
  }

  public boolean contains(Object x) {
    if (!(x instanceof Number num)) {
      return false;
    } else if (this == FLOAT64) {
      return true;
    }
    int i;
    if (num instanceof Double || num instanceof Float) {
      double d = num.doubleValue();
      i = (int) d;
      if (d != i) {
        return false;
      }
    } else {
      i = num.intValue();
    }
    return (this == INT32) || isUint8(i);
  }
}
