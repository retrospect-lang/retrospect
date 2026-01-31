package org.retrolang.impl;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Function;

/** Records a simple log of code generation events for debugging and testing. */
class CodeGenDebugging {
  /**
   * The event log. Most events are a single character, which makes it cryptic but easier to scan
   * and compare when there are many similar events. Digits in the log are references to
   * CodeGenTargets or CodeGenLinks that will be appended at the end of the log.
   */
  private StringBuilder log;

  /**
   * Maps each CodeGenTarget or CodeGenLink that has been previously referenced to the id that was
   * assigned to it.
   */
  private IdentityHashMap<Object, Integer> refMap;

  /** A readable(ish) record of the CodeGenTargets and CodeGenLinks that have been referenced. */
  private StringBuilder refs;

  /**
   * We create a CallTracker for each referenced CodeGenTarget, and use it to notice (and log) when
   * there have been intervening calls to that target.
   */
  private List<CallTracker> callTrackers;

  /** The most recently referenced CodeGenLink. */
  private CodeGenLink link;

  /** The most recently referenced CodeGenTarget. */
  private CodeGenTarget target;

  CodeGenDebugging() {
    initialize();
  }

  /** Clears all of the recorded state. */
  private void initialize() {
    log = new StringBuilder();
    refMap = new IdentityHashMap<>();
    refs = new StringBuilder();
    callTrackers = new ArrayList<>();
    link = null;
    target = null;
  }

  /**
   * Appends a record to the log. If any tracked targets have been called since the log was last
   * updated their counts will be noted first.
   */
  synchronized void append(String s) {
    checkTrackers();
    log.append(s);
  }

  /** Appends a record to the log that is associated with the given CodeGenLink. */
  synchronized void append(CodeGenLink link, String s) {
    checkTrackers();
    if (link != this.link && link != null) {
      appendId(link, CodeGenDebugging::formatLink);
      this.link = link;
    }
    log.append(s);
  }

  /** Appends a record to the log that is associated with the given CodeGenTarget. */
  synchronized void append(CodeGenTarget target, String s) {
    checkTrackers();
    if (target != this.target && target != null) {
      // The description of a target includes the id of its link, which we expect to already have
      // been referenced.
      appendId(target, t -> String.format("%s (%s)", t.name, refMap.get(t.link)));
      this.target = target;
    }
    log.append(s);
    if (s.equals("s")) {
      // Starting a target always increments its call count, so we don't need to report that call
      // on the next append()
      incrementTracker(target);
    }
  }

  /**
   * Appends an id for {@code obj} to the log. If this is the first reference to {@code obj},
   * assigns a new id and records it in {@link #refs} and {@link #refMap}; otherwise just appends
   * the previously-assigned id.
   */
  private <T> void appendId(T obj, Function<T, String> format) {
    int nextId = refMap.size();
    int id =
        refMap.computeIfAbsent(
            obj,
            k -> {
              refs.append(String.format("\n%3d: %s", nextId, format.apply(obj)));
              if (obj instanceof CodeGenTarget target) {
                // Create a CallTracker, so that if this target's call count increases between
                // records we can note that.
                int initCallCount = target.callCount();
                callTrackers.add(new CallTracker(target, nextId, initCallCount));
                // If there were calls to this target before we first saw it (unexpected but
                // possible) we'll note that here.
                if (initCallCount != 0) {
                  refs.append(String.format(" +:%d", initCallCount));
                }
              }
              return nextId;
            });
    log.append(id);
  }

  /** Returns a description of a CodeGenLink. */
  private static String formatLink(CodeGenLink link) {
    String code = link.kind.code;
    MethodMemo mm = link.mm;
    if (mm.perMethod == null) {
      return code + " (top level)";
    }
    StringBuilder result = new StringBuilder();
    result.append(code).append(" ").append(mm.perMethod.method);
    while (mm != null && mm.extra() instanceof CallSite callSite) {
      result.append(" //");
      if (callSite.duringCallEntryType != null) {
        result.append(" ").append(callSite.duringCallEntryType);
      }
      mm = mm.parent();
    }
    return result.toString();
  }

  /**
   * If any of the tracked targets have been called since our previous log entry, note that with the
   * number of calls.
   */
  private void checkTrackers() {
    boolean first = true;
    for (CallTracker tracker : callTrackers) {
      int newCalls = tracker.update();
      if (newCalls != 0) {
        log.append(first ? "(" : ",").append(tracker.id).append(":").append(newCalls);
        first = false;
      }
    }
    if (!first) {
      log.append(")");
    }
  }

  /** Increment the expected call count of the tracker for the given target. */
  private void incrementTracker(CodeGenTarget target) {
    // Linear search might look bad, but we're already doing a full scan of the trackers before
    // writing each record.  We're only doing this for debugging and testing.
    for (CallTracker tracker : callTrackers) {
      if (tracker.target == target) {
        tracker.last++;
        return;
      }
    }
    throw new AssertionError();
  }

  /** Returns a full dump of the final log and clears all state. */
  synchronized String takeLog() {
    checkTrackers();
    String result = log.toString() + refs.toString();
    initialize();
    return result;
  }

  /**
   * A CallTracker saves the last observed call count for a CodeGenTarget, so that we can determine
   * whether there have been additional calls.
   */
  private static class CallTracker {
    final CodeGenTarget target;
    final int id;
    int last;

    CallTracker(CodeGenTarget target, int id, int last) {
      this.target = target;
      this.id = id;
      this.last = last;
    }

    /**
     * Updates the CallTracker's state to match {@link CodeGenTarget#callCount} and returns the
     * increase, if any.
     */
    int update() {
      int prev = last;
      last = target.callCount();
      return last - prev;
    }
  }
}
