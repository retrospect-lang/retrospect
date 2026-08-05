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

import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import org.retrolang.impl.Evolver.Evolution;

/// A Coordinator enables operations that must involve all of the Java threads currently executing
/// on behalf of a single ResourceTracker.
///
/// Currently the only such operation is the clearing of replaced Frames, but I expect there to be
/// more in the future.
class Coordinator {

  private static final int INITIAL_MAX_THREADS = 4;

  /// The number of Java threads currently executing on behalf of this ResourceTracker.
  @GuardedBy("this")
  private int numActive;

  /// The TStates of the threads currently executing on behalf of this ResourceTracker (the first
  /// [#numActive] entries are non-null).
  @GuardedBy("this")
  private TState[] activeThreads = new TState[INITIAL_MAX_THREADS];

  /// A clock-like value that is incremented each time a new FrameReplacement is created.
  ///
  /// Note that in a sufficiently long-running VM this value may wrap around, so care must be taken
  /// when comparing clocks: `(c1 - c2 > 0)` can be used to determine if `c1` happened after `c2`,
  /// but `c1 > c2` cannot (e.g. if `c2` is Integer.MAX\_VALUE and `c1 = c2 + 1`, the former will
  /// return true but the latter will return false).
  ///
  /// (Even comparing the difference would break down if the gap between `c1` and `c2` became too
  /// large, but we take other steps to ensure that it can't.)
  @GuardedBy("this")
  private int replacementClock;

  /// Parallel to [#activeThreads]; saves the value of [#replacementClock] the last time the
  /// corresponding thread synchronized with this Coordinator. These values are also stored in
  /// [TState#lastSync] so that [#syncClock] can quickly check whether a sync is needed.
  ///
  /// `replacementClock - lastSync[i]` is the number of Frames that have been replaced since active
  /// thread `i` last synchronized, and is a lower bound on the size of [#pendingClear] (none of the
  /// Frames replaced since our last sync can be cleared until we sync). Since [#pendingClear] is an
  /// ArrayDeque, its maximum size is Integer.MAX\_VALUE (actually slightly less with the current
  /// implementation), so no thread can ever fall so far behind that `replacementClock -
  /// lastSync[i]` becomes negative (before that could happen the attempt to grow `pendingClear`
  /// would throw an IllegalStateException, but long before that could happen we'd have run out of
  /// Java memory (since each queue entry is at last a Frame plus a Frame.Replacement, with a
  /// minimum size of 56 bytes, we'd have at least 128GB of junk), and before that could happen we
  /// should have interrupted the computation with an OUT\_OF\_MEMORY error since those Frames and
  /// Frame.Replacements are being counted against the ResourceTracker.
  @GuardedBy("this")
  private int[] lastSync = new int[INITIAL_MAX_THREADS];

  /// Contains Frames that have been replaced but not yet cleared.
  ///
  /// If [#numActive] is zero then `pendingClear` is empty. Otherwise `pendingClear.size()` is equal
  /// to the maximum of `replacementClock - lastSync[i]` for `0 <= i < numActive`.
  @GuardedBy("this")
  private final Queue<Frame> pendingClear = new ArrayDeque<>();

  static final VarHandle REPLACEMENT_CLOCK =
      Handle.forVar(MethodHandles.lookup(), Coordinator.class, "replacementClock", int.class);

  /// Returns the index in [#activeThreads] of the given TState; should not be called if `tstate` is
  /// not active in this ResourceTracker.
  @GuardedBy("this")
  private int threadIndex(TState tstate) {
    // TODO: linear search will become suboptimal with large numbers of active threads
    // (maybe > 16?); consider doing something a little more sophisticated.
    int index = 0;
    while (activeThreads[index] != tstate) {
      ++index;
    }
    assert index < numActive;
    return index;
  }

  /// Adds the given TState to this ResourceTracker's set of active threads.
  synchronized void addActiveThread(TState tstate) {
    assert Arrays.stream(activeThreads).noneMatch(t -> t == tstate);
    if (activeThreads.length == numActive) {
      activeThreads = Arrays.copyOf(activeThreads, 2 * numActive);
      lastSync = Arrays.copyOf(lastSync, 2 * numActive);
    }
    lastSync[numActive] = replacementClock;
    tstate.lastSync = replacementClock;
    activeThreads[numActive++] = tstate;
  }

  /// Removes the given TState from this ResourceTracker's set of active threads.
  synchronized void removeActiveThread(TState tstate) {
    int i = threadIndex(tstate);
    --numActive;
    if (i < numActive) {
      activeThreads[i] = activeThreads[numActive];
      lastSync[i] = lastSync[numActive];
    }
    activeThreads[numActive] = null;
    // If there were pending clears waiting only for this thread to sync, do them now.
    doPending(tstate, tstate.lastSync);
  }

  /// Synchronizes the given TState, clearing any replaced frames that were waiting for it. Does a
  /// fast check to avoid unneeded synchronization if this TState is already caught up.
  ///
  /// Should be called periodically by all active TStates.
  void syncClock(TState tstate) {
    // replacementClock is only updated by replace(), which does a non-volatile write.   Because
    // it's non-volatile that write may be delayed in getting to memory, but must land before
    // replace() releases its lock.  If another thread is in replace() we're OK with seeing the old
    // value of replacementClock (we might skip syncing now if we'd previously been up-to-date, but
    // we'll get it on the next sync) or the new value (which would cause us to wait for the lock).
    if (tstate.lastSync != (int) REPLACEMENT_CLOCK.getVolatile(this)) {
      synchronized (this) {
        int threadIndex = threadIndex(tstate);
        int lastSync = tstate.lastSync;
        assert this.lastSync[threadIndex] == lastSync;
        tstate.lastSync = replacementClock;
        this.lastSync[threadIndex] = replacementClock;
        doPending(tstate, lastSync);
      }
    }
  }

  /// Called after one of this ResourceTracker's threads has synchronized, with its previous
  /// `lastSync` value. Clears any frame replacements that were waiting only for the given thread.
  @GuardedBy("this")
  private void doPending(TState tstate, int lastSync) {
    if (lastSync != replacementClock && pendingClear.size() == (replacementClock - lastSync)) {
      // tstate's lastSync value has already updated (or it has been removed if no longer active),
      // so we can compute the new size of pendingClear by looking for the most out-of-date among
      // the remaining active threads.
      int newPendingSize = 0;
      for (int i = 0; i < numActive; i++) {
        int framesSinceLastSync = replacementClock - this.lastSync[i];
        assert framesSinceLastSync >= 0;
        newPendingSize = Math.max(newPendingSize, framesSinceLastSync);
      }
      assert newPendingSize <= pendingClear.size();
      while (pendingClear.size() > newPendingSize) {
        Frame toClear = pendingClear.remove();
        ((Frame.Replacement) toClear.layoutOrReplacement)
            .clearOriginal(toClear, tstate.releaseVisitor);
      }
    }
  }

  /// Called with a Frame whose layout has evolved; returns the [Frame.Replacement] that points to
  /// its current replacement.
  synchronized Frame.Replacement replace(TState tstate, Frame old) {
    // Waiting to construct the replacement until after we've acquired the Coordinator lock
    // increases the chances of lock contention but avoids a lot of complexity (and possible
    // wasted work) if two threads race to replace the same object (especially with shared arrays).
    Frame.LayoutOrReplacement layoutOrReplacement = old.layoutOrReplacement;
    if (layoutOrReplacement instanceof Frame.Replacement) {
      // Someone has already replaced this Frame; follow the replacement link(s) and see if the
      // most recent replacement is up-to-date.
      for (; ; ) {
        Frame.Replacement result = (Frame.Replacement) layoutOrReplacement;
        old = result.newFrame;
        layoutOrReplacement = old.layoutOrReplacement;
        if (layoutOrReplacement instanceof Frame.Replacement) {
          // Keep following the replacement links
        } else if (((FrameLayout) layoutOrReplacement).hasEvolved()) {
          // The last one in the chain still isn't up-to-date
          break;
        } else {
          // No more replacement to be done
          return result;
        }
      }
    }
    // TODO: should we try to bound the size of pendingClear?  If it gets too large we're
    // tying down a lot of memory with replaced-but-not-cleared Frames.  That could theoretically
    // happen if we had one or more threads doing a lot of replacements and another thread (active
    // in the same ResourceTracker) that was never calling syncWithCoordinator().  The best response
    // would be to suspend all other threads and just wait for the laggard to sync, but it isn't
    // safe to block here (replace() might be called with other locks held, potentially leading to
    // a deadlock).
    Evolution evolution = ((FrameLayout) layoutOrReplacement).evolution();
    // Note that this call to Evolution.replace() might cause this thread to re-enter
    // Coordinator.replace()
    Frame newFrame = evolution.replace(tstate, old);
    Frame.Replacement result = new Frame.Replacement(evolution, newFrame);
    // If numActive == 1 it's tempting to just clear the old frame now rather than queueing it for
    // later, but since frame replacement can be triggered by many operations (e.g. peeking at an
    // element of a compound) that would greatly increase the risk of unexpectedly invalidating a
    // Value that the caller has in a local.  Instead we'll wait until the next call to syncClock(),
    // when we know it's safe.
    ++replacementClock;
    pendingClear.add(old);
    old.setReplacement(result);
    // The Replacement object isn't separately refcounted, so just add its size to the size of the
    // old object; when the old Frame's refcount goes to zero we'll call Replacement.visitRefs(),
    // which includes this increment in the size it reports.
    tstate.adjustAlloc(old, Frame.Replacement.OBJ_SIZE);
    return result;
  }
}
