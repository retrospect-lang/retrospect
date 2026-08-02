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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.retrolang.util.SizeOf;

/// A MemoryHelper handles memory allocation and deallocation for a single thread; MemoryHelpers are
/// not thread-safe.
///
/// Before use a MemoryHelper must be bound to a ResourceTracker; allocations and deallocations will
/// update that tracker's totals.
///
/// When bound to a non-debug ResourceTracker, MemoryHelper does not notify the ResourceTracker
/// separately of each allocation and release; instead it aggregates the quantities and updates the
/// ResourceTracker only on calls to [#reserve] or [#checkAvailable], or when the ResourceTracker is
/// unbound. When bound to a debug ResourceTracker each allocation and deallocation is passed
/// through immediately.
///
/// The Allocator implementation also includes a very small optimization: the last-released byte[]
/// and Object[] are saved, and if the next allocation needs an array of the same size the released
/// one is re-used. This is just a placeholder for additional optimizations to be added in the
/// future.
///
/// MemoryHelper's [#reserve] method is similar to [#checkAvailable], except that if it succeeds the
/// specified amount of memory has been set aside and cannot be claimed by other threads sharing the
/// ResourceTracker. Subsequent calls to [#allocByteArray], [#allocObjectArray], or [#recordAlloc]
/// will automatically use the reserved memory until it has been exhausted, after which it reverts
/// to claiming additional memory from the ResourceTracker as needed.
public class MemoryHelper implements Allocator {

  /// The ResourceTracker to which this MemoryHelper is bound, or null if it is currently unbound.
  private ResourceTracker tracker;

  /// A cache of `tracker.debugTracker`, since we check it so frequently.
  private DebugTracker debugTracker;

  // These are accumulated values not yet transferred to the ResourceTracker.
  private int allocedObjs;
  private long allocedBytes;
  private int releasedObjs;
  private long releasedBytes;

  /// The amount we have reserved with the ResourceTracker. This amount of memory has already been
  /// set aside for this thread's use.
  private long reservedBytes;

  final ReleaseVisitor releaseVisitor = new ReleaseVisitor();

  /// Binds this MemoryHelper to the given ResourceTracker; any subsequent allocations or
  /// deallocations will update the ResourceTracker's totals. If `tracker` is null, leaves the
  /// MemoryHelper unbound.
  ///
  /// If this MemoryHelper was already bound to a ResourceTracker, first unbinds it and returns the
  /// previous value; otherwise returns null.
  @CanIgnoreReturnValue
  ResourceTracker bindTo(ResourceTracker tracker) {
    // Don't carry over allocations from any previous tracker.
    ResourceTracker prev = this.tracker;
    if (prev != null) {
      updateTracker(-1);
    }
    // At this point we should have flushed all changes to any previously-bound ResourceTracker,
    // so the new one should get a clean start.
    assert allocedBytes == 0 && allocedObjs == 0 && releasedBytes == 0 && releasedObjs == 0;
    this.tracker = tracker;
    this.debugTracker = (tracker == null) ? null : tracker.debugTracker;
    return prev;
  }

  /// Returns the ResourceTracker to which this MemoryHelper is bound, or null if it is currently
  /// unbound.
  public ResourceTracker tracker() {
    return tracker;
  }

  private void mustBeBound() {
    if (tracker == null) {
      throw new IllegalStateException();
    }
  }

  /// The minimum change in memory use that's worth reporting to the ResourceTracker; waiting until
  /// we've allocated or freed at least this much before reporting it reduces the frequency of
  /// thread synchronization.
  private static final long MIN_TRACKER_UPDATE = 4096;

  /// Returns true if this computation has exceeded the available memory.
  boolean isOverMemoryLimit() {
    long unreservedAlloc = Math.max(allocedBytes - reservedBytes, 0);
    if (Math.abs(unreservedAlloc - releasedBytes) >= MIN_TRACKER_UPDATE || debugTracker != null) {
      return updateTracker(0) < 0;
    } else {
      return false;
    }
  }

  /// Returns the amount of memory still available from the ResourceTracker (negative if the memory
  /// limit has been exceeded). Includes memory reserved by this MemoryHelper.
  ///
  /// Inherently racy, so intended primarily for testing purposes.
  long checkAvailable() {
    mustBeBound();
    return updateTracker(0) + reservedBytes;
  }

  /// Reserves the given number of bytes for upcoming allocations and returns true, or returns false
  /// if there is not enough available memory.
  public boolean tryReserve(long size) {
    assert size >= 0;
    mustBeBound();
    return (size + allocedBytes - releasedBytes < reservedBytes + MIN_TRACKER_UPDATE)
        || updateTracker(size) >= 0;
  }

  /// Reserves the given number of bytes for upcoming allocations or throws OUT\_OF\_MEMORY if there
  /// is not enough available memory. Should be called before making large allocations.
  public void reserve(long size) throws Err.BuiltinException {
    Err.OUT_OF_MEMORY.unless(tryReserve(size));
  }

  /// Reserves space for an upcoming array allocation or throws OUT\_OF\_MEMORY if there is not
  /// enough available memory.
  public void reserve(FrameLayout layout, int length) throws Err.BuiltinException {
    if (length != 0) {
      // length < 0 probably means we overflowed an int
      Err.OUT_OF_MEMORY.when(length < 0);
      reserve(layout == null ? CompoundValue.sizeOf(length) : layout.sizeOf(length));
    }
  }

  /// Visible only for [TState#staticDropReference]; must be called with an object whose refCount
  /// has just been reduced to zero.
  void dropped(RefCounted obj) {
    mustBeBound();
    releaseVisitor.dropped(obj);
  }

  /// Decrements the reference count of the given RefCounted, and releases it if the reference count
  /// is now zero. Does nothing if `obj` is null or uncounted.
  public void dropReference(@RC.In RefCounted obj) {
    mustBeBound();
    releaseVisitor.visitRefCounted(obj);
  }

  /// Releases the given byte array. Does nothing if `bytes` is null.
  public void dropReference(@RC.In byte[] bytes) {
    mustBeBound();
    releaseVisitor.visitByteArray(bytes);
  }

  /// Releases the given Object array. Drops a reference to each of its non-null elements. Does
  /// nothing if `objs` is null.
  public void dropReference(@RC.In Object[] objs) {
    mustBeBound();
    releaseVisitor.visitObjArray(objs);
  }

  /// Decrements the reference count of the given Value if it is RefCounted, and releases it if the
  /// reference count is now zero. Does nothing if `value` is not a RefCounted or is uncounted.
  public void dropValue(@RC.In Value value) {
    mustBeBound();
    if (value instanceof RefCounted rc) {
      releaseVisitor.visitRefCounted(rc);
    }
  }

  /// Releases the given object if it is reference counted.
  void dropAny(Object obj) {
    mustBeBound();
    releaseVisitor.visit(obj);
  }

  /// Sets the specified element of the given Object[] to null, calling [#dropReference] if it is a
  /// RefCounted, byte[], or Object[].
  public void clearElement(Object[] array, int index) {
    clearElements(array, index, index + 1);
  }

  /// Sets the specified elements of the given Object[] to null, calling [#dropReference] on each
  /// RefCounted, byte[], or Object[].
  ///
  /// @param start index of the first element to be cleared
  /// @param end one more than the index of the last element to be cleared
  public void clearElements(Object[] objs, int start, int end) {
    mustBeBound();
    releaseVisitor.clearElements(objs, start, end);
  }

  /// Sets the specified elements of the given Object[] (which should be null) to the given value.
  ///
  /// @param start index of the first element to be set
  /// @param end one more than the index of the last element to be set
  public static void fillElements(Object[] objs, int start, int end, Object newElement) {
    assert Arrays.stream(objs, start, end).allMatch(Objects::isNull);
    Arrays.fill(objs, start, end, newElement);
    RefCounted.addRef(newElement, end - start);
  }

  /// Returns true if the given RefVisitor is a ReleaseVisitor, i.e. we are dropping the objects
  /// visited.
  public static boolean isReleaser(RefVisitor visitor) {
    return visitor instanceof ReleaseVisitor;
  }

  @Override
  public String toString() {
    if (tracker == null) {
      return "unbound";
    }
    String s =
        String.format(
            "alloced=%s/%s, released=%s/%s (tracker: %s)",
            allocedBytes, allocedObjs, releasedBytes, releasedObjs, tracker);
    if (releaseVisitor.releaseDepth != 0 || releaseVisitor.toRelease != null) {
      s =
          String.format(
              "%s (depth=%s, toRelease=%s)",
              s,
              releaseVisitor.releaseDepth,
              (releaseVisitor.toRelease == null ? null : releaseVisitor.toRelease.size()));
    }
    return s;
  }

  // Implementation of Allocator

  // Placeholder for a real free list allocator.
  // Note that if freeObjs is non-null, its elements are all null.
  private byte[] freeBytes;
  private Object[] freeObjs;

  /// Limits the maximum size of array we'll save for possible reuse. Big arrays are less likely to
  /// be rapidly allocated and released, and it costs more to have them lying around unused.
  static final int MAX_FREE_LIST_BYTES = 16384;

  static final int MAX_FREE_LIST_PTRS = MAX_FREE_LIST_BYTES / SizeOf.PTR;

  /// Returns the size in bytes of a byte[] with the given length.
  private static long simpleByteArraySize(int length) {
    return SizeOf.array(length, SizeOf.BYTE);
  }

  /// Returns the size in bytes of an Object[] with the given length.
  private static long simpleObjectArraySize(int length) {
    return SizeOf.array(length, SizeOf.PTR);
  }

  /// Returns the number of bytes that will be allocated by a call to [#allocByteArray] on this
  /// MemoryHelper with its current binding.
  long byteArraySize(int length) {
    mustBeBound();
    return byteArraySize(length, debugTracker != null);
  }

  /// Returns the number of bytes that will be allocated by a call to [#allocByteArray] on a
  /// MemoryHelper bound to a ResourceTracker of the given type.
  static long byteArraySize(int length, boolean debug) {
    // We don't allocate empty arrays.
    if (length == 0) {
      return 0;
    }
    // In debug mode we allocate exactly the length you ask for, but in non-debug mode we may
    // round the size up.
    return simpleByteArraySize(debug ? length : chooseCapacityBytes(length));
  }

  /// Returns the number of bytes that will be allocated by a call to [#allocObjectArray] on this
  /// MemoryHelper with its current binding.
  long objectArraySize(int length) {
    mustBeBound();
    return objectArraySize(length, debugTracker != null);
  }

  /// Returns the number of bytes that will be allocated by a call to [#allocObjectArray] on a
  /// MemoryHelper bound to a ResourceTracker of the given type.
  static long objectArraySize(int length, boolean debug) {
    if (length == 0) {
      return 0;
    }
    return simpleObjectArraySize(debug ? length : chooseCapacityObjects(length));
  }

  @Override
  @RC.Out
  public byte[] allocByteArray(int length) {
    mustBeBound();
    if (length == 0) {
      return EMPTY_BYTES;
    } else if (debugTracker != null) {
      return tracker.allocByteArray(length, this);
    }
    length = chooseCapacityBytes(length);
    byte[] result;
    if (freeBytes != null && freeBytes.length == length) {
      result = freeBytes;
      freeBytes = null;
    } else {
      result = new byte[length];
    }
    allocedObjs++;
    allocedBytes += simpleByteArraySize(length);
    return result;
  }

  @Override
  @RC.Out
  public Object[] allocObjectArray(int length) {
    mustBeBound();
    if (length == 0) {
      return EMPTY_OBJECTS;
    } else if (debugTracker != null) {
      return tracker.allocObjectArray(length, this);
    }
    length = chooseCapacityObjects(length);
    Object[] result;
    if (freeObjs != null && freeObjs.length == length) {
      result = freeObjs;
      freeObjs = null;
    } else {
      result = new Object[length];
    }
    allocedObjs++;
    allocedBytes += simpleObjectArraySize(length);
    return result;
  }

  @Override
  public void recordAlloc(RefCounted obj, long size) {
    assert obj != null && SizeOf.isValidSize(size);
    mustBeBound();
    if (debugTracker != null) {
      tracker.recordAlloc(obj, size, this);
    } else {
      allocedObjs++;
      allocedBytes += size;
    }
  }

  @Override
  public boolean isCounted() {
    return true;
  }

  /// Returns the number of reserved bytes that can be used for an allocation of the given size, and
  /// updates {#link #reservedBytes} accordingly. Only intended for use by ResourceTracker.
  long useReservation(long size) {
    long result = Math.min(size, reservedBytes);
    reservedBytes -= result;
    return result;
  }

  /// Adjusts the given array size upwards to the next round-ish number.
  ///
  /// We try to reuse recently-deallocated arrays to reduce GC, but our odds of finding a free array
  /// of the desired size are limited if there are too many different sizes. We try to improve our
  /// chances by rounding the array size upwards to the nearest int that is (a) a multiple of unit
  /// (which must be a power of 2), and (b) of the binary form 1xx0\*.
  ///
  /// For example, if allocating an array with 4-byte elements the minUnit would be 2 (since Java
  /// memory allocations are always in 8-byte units), and the allowed sizes are 2, 4, 6, 8, 10, 12,
  /// 14, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, ...; all sizes up to 16K will be mapped to one of
  /// 48 allowed sizes (and all sizes up to 1M will be mapped to one of 72 sizes).
  ///
  /// The maximum overhead (extra storage used due to this policy) is less than 25%. The expected
  /// overhead is hard to predict (since it depends on the distribution of requested sizes), but
  /// with uniformly distributed sizes over 1 to 1 million the average overhead is a little over 9%.
  private static int chooseCapacity(int size, int unit) {
    assert size > 0 && Integer.bitCount(unit) == 1;
    int maxAdd = Math.max((Integer.highestOneBit(size) >> 2) - 1, unit - 1);
    int result = size + maxAdd;
    if (result < 0) {
      // If we're close enough to MAX_VALUE to cause overflow, just cap the size at the largest
      // multiple of unit that is less than or equal to MAX_VALUE, but never less than size.
      return Math.max(Integer.MAX_VALUE & -unit, size);
    } else {
      return result & ~maxAdd;
    }
  }

  /// Returns the size of byte[] to be used to satisfy a request of the given size. See
  /// [#chooseCapacity] for details of how the result is chosen.
  static int chooseCapacityBytes(int size) {
    return chooseCapacity(size, 8);
  }

  /// Returns the size of Object[] to be used to satisfy a request of the given size. See
  /// [#chooseCapacity] for details of how the result is chosen.
  static int chooseCapacityObjects(int size) {
    return chooseCapacity(size, 2);
  }

  /// Returns true if the given byte[] can reasonably be used to satisfy a request of the given
  /// size. This must
  ///
  /// - return false if `bytes.length` is less than `size`, and
  /// - return true if `bytes.length` is what [#chooseCapacityBytes] would return for `size`.
  ///
  /// May return true if `bytes.length` is larger than [#chooseCapacityBytes] would return but not
  /// too much larger (the current policy is that it should not be more than double `size`).
  static boolean isOkForSize(byte[] bytes, int size) {
    // Note that if size is less than 5, chooseCapacity will return 8 and we should never require
    // something smaller than that.  In all other cases chooseCapacity will be less than double the
    // given size.
    // And if bytes.length is 16 it's probably not worth replacing it with something that's only one
    // word shorter.
    return bytes.length >= size && bytes.length <= Math.max(size * 2, 16);
  }

  /// Returns true if the given Object[] can reasonably be used to satisfy a request of the given
  /// size. This must
  ///
  /// - return false if `objs.length` is less than `size`, and
  /// - return true if `objs.length` is what [#chooseCapacityObjects] would return for `size`.
  ///
  /// May return true if `objs.length` is larger than [#chooseCapacityObjects] would return but not
  /// too much larger (the current policy is that it should not be more than double `size`).
  static boolean isOkForSize(Object[] objs, int size) {
    // If objs has only 4 elements it doesn't seem worth replacing it with something that's only one
    // word shorter.
    return objs.length >= size && objs.length <= Math.max(size * 2, 4);
  }

  /// Returns a byte[] containing
  ///
  /// - the first `keepPrefix` elements of `bytes`, followed by
  /// - `addSize` uninitialized bytes, followed by
  /// - the elements of `bytes` from `keepPrefix+removeSize` to `size`.
  ///
  /// This method is applied to element arrays of a varray to implement [ValueUtil#removeRange].
  ///
  /// If `copy` is true, the result is a newly-allocated array and `bytes` is unchanged. If `copy`
  /// is false, `bytes` should be considered @RC.In; either it will be modified in place and
  /// returned, or (if a new array must be allocated for the result) it will be released.
  @RC.Out
  byte[] removeRange(
      byte[] bytes, int size, int keepPrefix, int removeSize, int addSize, boolean copy) {
    assert keepPrefix >= 0
        && removeSize >= 0
        && addSize >= 0
        && size >= keepPrefix + removeSize
        && (bytes == null ? size == 0 : size <= bytes.length);
    if (bytes == null) {
      return allocByteArray(addSize);
    }
    int moveFrom = keepPrefix + removeSize;
    int moveTo = keepPrefix + addSize;
    int moveLen = size - moveFrom;
    int newSize = moveTo + moveLen;
    if (!copy) {
      // If we're growing the contents but they can still fit in the same array, or we're shrinking
      // the contents but not to less than half the array size, then keep the array and just move
      // things around within it.
      if (removeSize <= addSize
          ? newSize <= bytes.length
          : bytes.length <= Math.max(newSize * 2, 16)) {
        if (moveLen != 0 && moveFrom != moveTo) {
          System.arraycopy(bytes, moveFrom, bytes, moveTo, moveLen);
        }
        return bytes;
      }
    }
    // We need a new array.  Allocate it and copy over the contents that we're keeping.
    byte[] result = allocByteArray(newSize);
    assert copy || result.length != bytes.length;
    System.arraycopy(bytes, 0, result, 0, keepPrefix);
    System.arraycopy(bytes, moveFrom, result, moveTo, moveLen);
    if (!copy) {
      dropReference(bytes);
    }
    return result;
  }

  /// Returns an Object[] containing
  ///
  /// - the first `keepPrefix` elements of `objs`, followed by
  /// - `addSize` nulls, followed by
  /// - the elements of `objs` from `keepPrefix+removeSize` to `size`.
  ///
  /// This method is applied to element arrays of a varray to implement [ValueUtil#removeRange].
  ///
  /// If `copy` is true, the result is a newly-allocated array and `objs` is unchanged. If `copy` is
  /// false, `objs` should be considered @RC.In; either it will be modified in place and returned,
  /// or (if a new array must be allocated for the result) it will be released.
  @RC.Out
  Object[] removeRange(
      Object[] objs, int size, int keepPrefix, int removeSize, int addSize, boolean copy) {
    assert keepPrefix >= 0
        && removeSize >= 0
        && addSize >= 0
        && size >= keepPrefix + removeSize
        && (objs == null ? size == 0 : size <= objs.length);
    if (objs == null) {
      assert copy;
      return allocObjectArray(addSize);
    }
    int moveFrom = keepPrefix + removeSize;
    int moveTo = keepPrefix + addSize;
    int moveLen = size - moveFrom;
    int newSize = moveTo + moveLen;
    if (!copy) {
      if (removeSize <= addSize
          ? newSize <= objs.length
          : objs.length <= Math.max(newSize * 2, 4)) {
        // We'll keep this array.
        clearElements(objs, keepPrefix, moveFrom);
        if (moveLen != 0 && moveFrom != moveTo) {
          System.arraycopy(objs, moveFrom, objs, moveTo, moveLen);
          // That will have left behind some entries that should be nulled
          // (*not* using clearElements(), since we don't want to drop their refcounts).
          if (moveFrom < moveTo) {
            Arrays.fill(objs, moveFrom, moveTo, null);
          } else {
            Arrays.fill(objs, newSize, size, null);
          }
        }
        return objs;
      }
    }
    // We need a new array
    Object[] result = allocObjectArray(newSize);
    assert copy || result.length != objs.length;
    System.arraycopy(objs, 0, result, 0, keepPrefix);
    System.arraycopy(objs, moveFrom, result, moveTo, moveLen);
    if (copy) {
      Arrays.stream(objs, 0, keepPrefix).forEach(RefCounted::addRef);
      Arrays.stream(objs, moveFrom, size).forEach(RefCounted::addRef);
    } else {
      // Null out the entries we copied since we don't want to drop them when we drop objs.
      Arrays.fill(objs, 0, keepPrefix, null);
      Arrays.fill(objs, moveFrom, size, null);
      dropReference(objs);
    }
    return result;
  }

  /// Copies `count` elements from `src` to `dst`, updating reference counts appropriately; the
  /// overwritten elements of `dst` should be null.
  static void copyRange(
      @RC.InOut Object[] dst, int dstStart, Object[] src, int srcStart, int count) {
    assert dst != src || dstStart + count <= srcStart || srcStart + count <= dstStart;
    int srcEnd = srcStart + count;
    for (int i = srcStart; i < srcEnd; i++) {
      assert dst[dstStart] == null;
      Object x = src[i];
      RefCounted.addRef(x);
      dst[dstStart++] = x;
    }
  }

  @Override
  public void adjustAlloc(RefCounted obj, long sizeDelta) {
    mustBeBound();
    if (debugTracker != null) {
      tracker.adjustAlloc(obj, sizeDelta);
    } else if (sizeDelta < 0) {
      releasedBytes -= sizeDelta;
    } else {
      allocedBytes += sizeDelta;
    }
  }

  /// Propagates our accumulated changes to the ResourceTracker and checks how much memory is still
  /// available, optionally also trying to reserve memory for future allocations. If `reserve` is
  /// negative then any existing reservation is released.
  ///
  /// Returns the number of available bytes remaining. If the result is non-negative then any
  /// requested reservation has been made; otherwise the reservation failed.
  @CanIgnoreReturnValue
  private long updateTracker(long reserve) {
    int allocedObjs = this.allocedObjs;
    long allocedBytes = this.allocedBytes;
    int releasedObjs = this.releasedObjs;
    long releasedBytes = this.releasedBytes;
    this.allocedObjs = 0;
    this.allocedBytes = 0;
    this.releasedObjs = 0;
    this.releasedBytes = 0;
    long unreserve;
    if (reserve < 0) {
      // Release any existing reservation.
      reserve = 0;
      unreserve = reservedBytes;
      reservedBytes = 0;
    } else {
      // Release any existing reservation that we have already consumed.
      unreserve = Math.min(reservedBytes, allocedBytes);
      reservedBytes -= unreserve;
      // Figure out how much we need to increase the remaining reservation.
      reserve = Math.max(reserve - reservedBytes, 0);
    }
    long result =
        tracker.update(allocedObjs, allocedBytes, releasedObjs, releasedBytes, unreserve, reserve);
    if (result >= 0) {
      this.reservedBytes += reserve;
    }
    return result;
  }

  /// An implementation of RefVisitor that decrements the refcount of each RefCounted it visits,
  /// releasing it if the refcount is now zero, and releases each byte[] or Object it visits.
  ///
  /// This is only separated from MemoryHelper in order to keep more things private; these methods
  /// and fields could just as easily be on MemoryHelper.
  private class ReleaseVisitor implements RefVisitor {

    /// Tracks how many times we've recursed in [#dropNow], to avoid causing a
    /// StackOverflowException.
    private int releaseDepth;

    /// Objects whose call to [#dropNow] has been deferred to avoid a stack overflow. Each element
    /// of `toRelease` is either a RefCounted with refCnt zero or an Object[].
    private List<Object> toRelease;

    /// The maximum recursion depth we allow in [#dropNow].
    static final int MAX_RELEASE_DEPTH = 48;

    /// The recursion depth at which we will begin to process previously-saved items from
    /// [#toRelease]. We want this to low enough that we don't immediately hit the stack limit
    /// again, but high enough that we're not accumulating objects unnecessarily.
    static final int DRAIN_DEPTH = MAX_RELEASE_DEPTH / 2;

    /// Visiting a RefCounted with a MemoryHelper decrements its refCount; if the refCount is now
    /// zero its refs are visited.
    @Override
    public void visitRefCounted(RefCounted obj) {
      if (obj != null && obj.dropRefInternal()) {
        dropped(obj);
      }
    }

    @Override
    public void visitByteArray(byte[] bytes) {
      if (bytes != null && bytes.length != 0) {
        long releasedSize = SizeOf.array(bytes);
        releasedBytes += releasedSize;
        releasedObjs++;
        if (debugTracker != null) {
          debugTracker.recordRelease(bytes, releasedSize);
        } else if (bytes.length < MAX_FREE_LIST_BYTES) {
          freeBytes = bytes;
        }
      }
    }

    @Override
    public void visitObjArray(Object[] objs) {
      if (objs != null && objs.length != 0) {
        dropped(objs);
      }
    }

    void clearElements(Object[] objs, int start, int end) {
      for (int i = start; i < end; i++) {
        Object e = objs[i];
        if (e != null) {
          objs[i] = null;
          visit(e);
        }
      }
    }

    /// Called with a RefCounted whose refCnt has just gone to zero, or an Object[] that has been
    /// released. Normally we just call [#dropNow], which visits each counted reference of the
    /// object (or element of the array) and then increments our released counters, but that can
    /// unbounded recursion and stack overflow, so we count our recursion depth and if it gets
    /// alarming we switch to just pushing the to-be-dropped objects onto an ArrayList (still a
    /// stack, but one that grow much deeper). Once our execution stack starts to get back to a more
    /// reasonable size we start popping objects from the external stack and processing them.
    private void dropped(Object arrayOrRefCounted) {
      if (releaseDepth >= MAX_RELEASE_DEPTH) {
        if (toRelease == null) {
          toRelease = new ArrayList<>();
        }
        toRelease.add(arrayOrRefCounted);
        return;
      }
      dropNow(arrayOrRefCounted);
      if (releaseDepth == 0) {
        assert (toRelease == null) || toRelease.isEmpty();
        // If we used an external stack we can release it now.
        toRelease = null;
        if (debugTracker != null) {
          // Allocations are getting sent to the ResourceTracker immediately; if we don't also send
          // releases promptly we'll end up with erroneous peak byte measurements.
          updateTracker(0);
        }
      } else if (releaseDepth == DRAIN_DEPTH && toRelease != null) {
        // We're far enough back up the stack to work through our backlog.
        while (!toRelease.isEmpty()) {
          dropNow(toRelease.removeLast());
        }
      }
    }

    /// Called with either a RefCounted with refCnt zero or a non-empty Object[]; visits each of the
    /// argument's elements and then declares it freed.
    private void dropNow(Object arrayOrRefCounted) {
      int prevDepth = releaseDepth++;
      try {
        long releasedSize;
        if (arrayOrRefCounted instanceof RefCounted rc) {
          // The RefCounted is responsible for enumerating its counted references.
          releasedSize = rc.visitRefs(this);
          assert SizeOf.isValidSize(releasedSize);
        } else {
          // All the elements of an Object[] are considered counted references.
          Object[] array = (Object[]) arrayOrRefCounted;
          clearElements(array, 0, array.length);
          if (array.length < MAX_FREE_LIST_PTRS && debugTracker == null) {
            freeObjs = array;
          }
          releasedSize = SizeOf.array(array);
        }
        releasedBytes += releasedSize;
        releasedObjs++;
        if (debugTracker != null) {
          debugTracker.recordRelease(arrayOrRefCounted, releasedSize);
        }
      } finally {
        assert releaseDepth == prevDepth + 1;
        releaseDepth = prevDepth;
      }
    }
  }
}
