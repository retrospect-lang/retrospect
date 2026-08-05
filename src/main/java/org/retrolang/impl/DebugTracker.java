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

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.retrolang.util.SizeOf;

/// Used by a debug ResourceTracker to save information about each live object and do additional
/// checks. This is much too expensive to do during regular operation, but helpful when trying to
/// find bugs in the reference-counting logic.
class DebugTracker {

  /// Maps each live tracked object (RefCounted, byte[], or Object[]) to a record of its allocation.
  @GuardedBy("this")
  private final IdentityHashMap<Object, AllocationInfo> live = new IdentityHashMap<>();

  /// The total number of objects allocated so far.
  @GuardedBy("this")
  private int counter;

  /// True if any of our checks have failed.
  @GuardedBy("this")
  private boolean errored;

  private static class AllocationInfo {

    /// A simple identifier for this object.
    final int id;

    /// The size declared at object creation time, or as of the last call to
    /// [Allocator#adjustAlloc].
    long size;

    /// The stack captured at the time of allocation.
    final Throwable throwable;

    AllocationInfo(int counter, long size) {
      this.id = counter;
      this.size = size;
      this.throwable = new Throwable();
    }

    @Override
    public String toString() {
      // Make it easier to interpret these in the debugger
      StringWriter writer = new StringWriter();
      PrintWriter printer = new PrintWriter(writer);
      printer.format("#%d (%d bytes): ", id, size);
      throwable.printStackTrace(printer);
      return writer.toString();
    }
  }

  /// Returns `obj.toString())` preceded with the name of its class.
  private static String toString(Object obj) {
    return String.format("(%s) %s", obj.getClass().getSimpleName(), obj);
  }

  @GuardedBy("this")
  @FormatMethod
  private void logError(String fmt, Object... args) {
    System.err.print("\n** " + String.format(fmt, args) + "\n");
    errored = true;
  }

  /// Records the allocation of a new object with the given size.
  synchronized void recordAlloc(Object obj, long size) {
    assert obj != null && SizeOf.isValidSize(size);
    AllocationInfo prev = live.putIfAbsent(obj, new AllocationInfo(counter, size));
    if (prev == null) {
      counter++;
    } else {
      logError("duplicate alloc for #%s: %s", prev.id, toString(obj));
    }
  }

  /// Records the release of a previously-allocated object with the given size.
  synchronized void recordRelease(Object obj, long size) {
    assert obj != null && SizeOf.isValidSize(size);
    AllocationInfo cs = live.remove(obj);
    if (cs == null) {
      logError("release without alloc: %s", toString(obj));
    } else if (cs.size != size) {
      logError(
          "released #%s with size %s, allocated as %s: %s", cs.id, size, cs.size, toString(obj));
    }
  }

  /// Records a change in the size of a previously-allocated object with the given size.
  synchronized void adjustAlloc(Object obj, long sizeDelta) {
    AllocationInfo entry = live.get(obj);
    if (entry == null) {
      logError("no alloc for %s", toString(obj));
    } else {
      entry.size += sizeDelta;
      if (entry.size <= 0) {
        logError("negative size for %s", toString(obj));
      } else {
        assert SizeOf.isValidSize(entry.size);
      }
    }
  }

  /// Returns true if any errors have been detected by this DebugTracker.
  synchronized boolean errored() {
    return errored;
  }

  /// Returns true if all allocated objects have been released. Any unallocated objects are
  /// considered to be errors.
  synchronized boolean allReleased() {
    if (live.isEmpty()) {
      return true;
    }
    for (Map.Entry<Object, AllocationInfo> entry : live.entrySet()) {
      logError("no release for %s", entryToString(entry));
      entry.getValue().throwable.printStackTrace();
    }
    return false;
  }

  private static String entryToString(Map.Entry<Object, AllocationInfo> entry) {
    return String.format("#%s (%s)", entry.getValue().id, toString(entry.getKey()));
  }

  private static class ObjectSet {
    private final IdentityHashMap<Object, Boolean> elements = new IdentityHashMap<>();

    /// Adds `obj` to this set, and returns true if it had not previously been added.
    boolean addIfNew(Object obj) {
      return elements.putIfAbsent(obj, true) == null;
    }
  }

  /// Used by [#getSizes(Object, int, ObjectSet, List)] to visit all the direct and indirectly
  /// reachable objects from a given root and sum their sizes.
  static class Visitor implements RefVisitor {
    /// All objects that have had their size added to [#sum].
    final ObjectSet visited = new ObjectSet();

    /// The sum of the sizes of the objects we have visited.
    long sum;

    /// The immediate children of the first object we visited.
    List<Object> topLevel = new ArrayList<>();

    /// The number of links followed to reach the current object.
    int level;

    @Override
    public void visitRefCounted(RefCounted obj) {
      if (obj != null && obj.isRefCounted() && visited.addIfNew(obj)) {
        if (level++ == 1) {
          topLevel.add(obj);
        }
        // This is not the same as `sum += obj.visitRefs(this)` !
        long size = obj.visitRefs(this);
        sum += size;
        --level;
      }
    }

    @Override
    public void visitByteArray(byte[] bytes) {
      if (bytes != null && bytes.length != 0 && visited.addIfNew(bytes)) {
        sum += SizeOf.array(bytes);
      }
    }

    @Override
    public void visitObjArray(Object[] objs) {
      if (objs != null && objs.length != 0 && visited.addIfNew(objs)) {
        if (level++ == 1) {
          topLevel.add(objs);
        }
        for (Object obj : objs) {
          visit(obj);
        }
        sum += SizeOf.array(objs);
        --level;
      }
    }
  }

  /// Result type for [#getSizes].
  record Size(Object obj, long totalSize) {}

  /// Returns a list containing counted objects reachable from obj and larger than threshold, sorted
  /// by totalSize.
  static List<Size> getSizes(Object obj, int threshold) {
    List<Size> result = new ArrayList<>();
    getSizes(obj, threshold, new ObjectSet(), result);
    result.sort((s1, s2) -> Long.compare(s2.totalSize, s1.totalSize));
    return result;
  }

  /// Compute the size of `obj`; if it is over `threshold` add it to `result` and then recursively
  /// check each of its immediate children.
  private static void getSizes(Object obj, int threshold, ObjectSet checked, List<Size> result) {
    Visitor visitor = new Visitor();
    visitor.visit(obj);
    if (visitor.sum >= threshold) {
      result.add(new Size(obj, visitor.sum));
      for (Object child : visitor.topLevel) {
        if (checked.addIfNew(child)) {
          getSizes(child, threshold, checked, result);
        }
      }
    }
  }

  /// Print the 100 largest objects found by [#getSizes].
  static void printLargest(Object obj, int threshold) {
    List<Size> result = getSizes(obj, threshold);
    if (result.size() > 100) {
      result.subList(100, result.size()).clear();
    }
    for (Size size : result) {
      String s = size.obj.toString();
      System.out.format(
          "%d bytes: %s\n", size.totalSize, s.length() <= 100 ? s : s.substring(0, 97) + " ...");
    }
  }
}
