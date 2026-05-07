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

import org.retrolang.util.SizeOf;

/**
 * An Allocator provides methods to allocate byte and Object arrays, and to record the allocation of
 * RefCounted objects. Methods that create {@link RefCounted}s should take an Allocator as an
 * argument and call the appropriate methods to reflect their memory use.
 *
 * <p>Allocators that limit memory use will not throw exceptions even when their limit is exceeded;
 * it is the caller's responsibility to call {@link MemoryHelper#checkAvailable} at appropriate
 * points.
 */
public interface Allocator {

  /**
   * Returns a byte[] of at least the given length and records its memory use.
   *
   * <p>The caller should not assume that the returned byte[] has been zero-filled.
   */
  @RC.Out
  byte[] allocByteArray(int length);

  /** Returns a null-filled Object[] of at least the given length and records its memory use. */
  @RC.Out
  Object[] allocObjectArray(int length);

  /**
   * Records the allocation of a new RefCounted. {@link SizeOf#isValidSize} should return true for
   * the given size.
   */
  void recordAlloc(RefCounted obj, long size);

  /**
   * Records a change in memory use of a previously-allocated object.
   *
   * <p>{@code sizeDelta} is the change in the number of bytes, positive if {@code obj} is now
   * larger; {@link SizeOf#isValidSize} should be true of the new size.
   */
  void adjustAlloc(RefCounted obj, long sizeDelta);

  /** Returns true if objects created with this allocator are counted. */
  boolean isCounted();

  static final byte[] EMPTY_BYTES = new byte[0];
  static final Object[] EMPTY_OBJECTS = new Object[0];

  /**
   * Returns a trivial implementation of Allocator that has no limits, and whose {@link
   * #recordAlloc} method calls {@link RefCounted#setUncounted} or {@link RefCounted#setTransient}.
   */
  private static Allocator trivialAllocator(boolean createTransient) {
    return new Allocator() {
      @Override
      @RC.Out
      public byte[] allocByteArray(int length) {
        return (length == 0) ? EMPTY_BYTES : new byte[length];
      }

      @Override
      @RC.Out
      public Object[] allocObjectArray(int length) {
        return (length == 0) ? EMPTY_OBJECTS : new Object[length];
      }

      @Override
      public void recordAlloc(RefCounted obj, long size) {
        assert SizeOf.isValidSize(size);
        if (createTransient) {
          obj.setTransient();
        } else {
          obj.setUncounted();
        }
      }

      @Override
      public void adjustAlloc(RefCounted obj, long sizeDelta) {}

      @Override
      public boolean isCounted() {
        return false;
      }
    };
  }

  /**
   * A trivial implementation of Allocator that has no limits, and whose {@link #recordAlloc} method
   * calls {@link RefCounted#setUncounted}.
   */
  public Allocator UNCOUNTED = trivialAllocator(false);

  /** Like UNCOUNTED, except that {@link #recordAlloc} calls {@link RefCounted#setTransient}. */
  public Allocator TRANSIENT = trivialAllocator(true);
}
