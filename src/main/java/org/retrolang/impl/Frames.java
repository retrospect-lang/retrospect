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

import com.google.common.collect.ImmutableList;

/// A statics-only class that defines all the available subclasses of Frame. Each subclass defines a
/// different combination of int and pointer fields as reflected in its name, e.g. Frame5i2x has 5
/// int fields and 2 pointer fields.
///
/// The particular configurations defined here were somewhat arbitrarily chosen; when Retrospect is
/// in production it might be worth gathering data to enable a more informed choice.
class Frames {
  // Currently Frame contributes 8 bytes (refCnt + layout) and the Java object header is 12 bytes,
  // so we aim to have these all use an odd number of 4-byte words to make full use of an object
  // that will be rounded up to a multiple of 8 bytes.

  // The JVM currently seems to preferentially allocate non-pointers before pointers (subject to
  // alignment constraints), so to ensure that pointers & non-pointers are contiguous and to
  // simplify the logic that ensures doubles are 8-byte aligned, we always allocate an odd number
  // of ints followed by an even number of pointers.  To simplify some of the unsafe access logic
  // the number of pointers must always be non-zero.

  // To verify object config, download the latest version of jol-cli-*-full.jar
  // from http://central.maven.org/maven2/org/openjdk/jol/jol-cli/
  // and then run e.g.
  // java -Djdk.attach.allowAttachSelf -jar jol-cli-0.9-full.jar \
  //     internals -cp bin 'org.retrolang.impl.Frames$Frame4b4i2d6x'
  // More details: http://openjdk.java.net/projects/code-tools/jol/

  static final ImmutableList<FrameClass> CLASSES =
      ImmutableList.of(
          new FrameClass(Frame1i2x.class),
          new FrameClass(Frame3i2x.class),
          new FrameClass(Frame1i4x.class),
          new FrameClass(Frame5i2x.class),
          new FrameClass(Frame3i4x.class),
          new FrameClass(Frame7i2x.class),
          new FrameClass(Frame5i4x.class),
          new FrameClass(Frame1i8x.class),
          new FrameClass(Frame7i6x.class),
          new FrameClass(Frame3i10x.class));

  static {
    // Sanity check the indices.
    for (int i = 0; i < CLASSES.size(); i++) {
      assert CLASSES.get(i).index == i;
    }
  }

  static class Frame1i2x extends Frame {
    int i0;
    Object x0, x1;

    @Override
    final int frameClassIndex() {
      return 0;
    }
  }

  static class Frame3i2x extends Frame {
    int i0, i1, i2;
    Object x0, x1;

    @Override
    final int frameClassIndex() {
      return 1;
    }
  }

  static class Frame1i4x extends Frame {
    int i0;
    Object x0, x1, x2, x3;

    @Override
    final int frameClassIndex() {
      return 2;
    }
  }

  static class Frame5i2x extends Frame {
    int i0, i1, i2, i3, i4;
    Object x0, x1;

    @Override
    final int frameClassIndex() {
      return 3;
    }
  }

  static class Frame3i4x extends Frame {
    int i0, i1, i2;
    Object x0, x1, x2, x3;

    @Override
    final int frameClassIndex() {
      return 4;
    }
  }

  static class Frame7i2x extends Frame {
    int i0, i1, i2, i3, i4, i5, i6;
    Object x0, x1;

    @Override
    final int frameClassIndex() {
      return 5;
    }
  }

  static class Frame5i4x extends Frame {
    int i0, i1, i2, i3, i4;
    Object x0, x1, x2, x3;

    @Override
    final int frameClassIndex() {
      return 6;
    }
  }

  static class Frame1i8x extends Frame {
    int i0;
    Object x0, x1, x2, x3, x4, x5, x6, x7;

    @Override
    final int frameClassIndex() {
      return 7;
    }
  }

  // For no apparent reason, the int fields in Frame11i2x are laid out differently on x86 than on
  // ARM (x86 lays them out in order, ARM lays them out i10, i1, i2, ..., i9, i0 ?!).
  // This seems to be the result of changes in JDK 15
  // (https://bugs.openjdk.java.net/browse/JDK-8237767).
  // Getting a wonky layout order doesn't affect the correctness of the rest of the system, but
  // it can make for confusion when debugging so I've just disabled this configuration for now.

  // static class Frame11i2x extends Frame {
  //   int i0, i1, i2, i3, i4, i5, i6, i7, i8, i9, i10;
  //   Object x0, x1;
  //
  //   @Override
  //   final int frameClassIndex() {
  //     return 8;
  //   }
  // }

  static class Frame7i6x extends Frame {
    int i0, i1, i2, i3, i4, i5, i6;
    Object x0, x1, x2, x3, x4, x5;

    @Override
    final int frameClassIndex() {
      return 8;
    }
  }

  static class Frame3i10x extends Frame {
    int i0, i1, i2;
    Object x0, x1, x2, x3, x4, x5, x6, x7, x8, x9;

    @Override
    final int frameClassIndex() {
      return 9;
    }
  }

  private Frames() {}
}
