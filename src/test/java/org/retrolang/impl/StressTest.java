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

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import junitparams.JUnitParamsRunner;
import org.antlr.v4.runtime.CharStreams;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.retrolang.Vm;
import org.retrolang.compiler.Compiler;

@RunWith(JUnitParamsRunner.class)
public class StressTest {

  /**
   * A Retrospect script (copied from future.r8t) that starts a bunch of threads that are
   * independently evolving the same layout and replacing the same frame.
   *
   * <p>(One small change from the version in future.r8t: this has a parameter ({@code index}) that
   * is used in place of the constant 1 in that script. If {@code index} is greater than 255 that
   * will force an upgrade to int32 rather than uint8.)
   */
  private static final String CODE =
      """
      others = [index, "a", True, "b", False, None]
      x = newMatrix([8], [])
      x[1] = [0]
      y = others | z -> future(-> replaceElement(x[1], [1], z))
                 | f -> future(-> waitFor(f)[1])
                 | save
      return waitFor(^y) | save
      """;

  /**
   * The expected result of {@link #CODE}. This is a format string with one parameter, the value of
   * {@code index}.
   */
  @SuppressWarnings("InlineFormatString")
  private static final String EXPECTED = "[%s, \"a\", True, \"b\", False, None]";

  /**
   * The result of compiling {@link #CODE}.
   *
   * <p>We're cheating a little here by executing code in one VirtualMachine after compiling it in
   * another. That isn't documented to work, although it does currently. At some point we'll have a
   * fully worked-out story about how to do this properly.
   *
   * <p>We could also just recompile the code anew in each VirtualMachine, but we'd rather use as
   * many cores as possible for the execution that we're testing.
   */
  Vm.InstructionBlock insts;

  /** Records the minimum and maximum values from a sequence of longs. */
  static class MinMax {
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;

    void add(long v) {
      min = Math.min(min, v);
      max = Math.max(max, v);
    }

    @Override
    public String toString() {
      return min + "‥" + max;
    }
  }

  /**
   * Records the range of allocedBytes, allocedObjs, and peakBytes from a sequence of
   * ResourceTrackers. Verifies that {@link ResourceTracker#allReleased} is true for each.
   */
  static class TrackerChecker {
    final MinMax allocedBytes = new MinMax();
    final MinMax allocedObjs = new MinMax();
    final MinMax peakBytes = new MinMax();

    /**
     * The call to applyToArgs() may return before the last RThreads have shut down, which means
     * that tracker.allReleased() may return false if we check it immediately. VirtualMachineTest
     * handles this by first calling awaitQuiescence() on the ForkJoinPool, but we can't do that
     * here since other trials will still be running.
     *
     * <p>So if tracker.allReleased() fails we add the tracker to a queue and let another thread
     * re-check it after a short delay. (We could just save all the failing trackers and re-check
     * them after the test finishes, but the ResourceTracker has a link to the Scope, which has a
     * link to the heavy MethodMemos, so we would risk running out of memory when running with very
     * high numbers of trials.)
     *
     * <p>After all trials have been completed we insert a non-ResourceTracker into the queue to
     * notify the queueDrainer that it should exit -- that's why the element type is Object rather
     * than ResourceTracker.
     */
    final ArrayBlockingQueue<Object> awaitingAllReleased = new ArrayBlockingQueue<>(256);

    /** The thread that will wait for queued ResourceTrackers to return true from allReleased(). */
    final Thread queueDrainer = new Thread(this::drainQueue);

    /** The number of times the {@link #queueDrainer} has had to wait for a ResourceTracker. */
    int numSleeps;

    TrackerChecker() {
      queueDrainer.start();
    }

    /** Shuts down the {@link #queueDrainer}. */
    void shutdown() throws InterruptedException {
      // Enqueue the "we're done" flag value and then wait for the drainer to complete.
      awaitingAllReleased.put(false);
      queueDrainer.join();
    }

    void check(ResourceTracker tracker) {
      synchronized (this) {
        allocedBytes.add(tracker.allocedBytes());
        allocedObjs.add(tracker.allocedObjs());
        peakBytes.add(tracker.peakBytes());
      }
      if (!tracker.allReleased()) {
        try {
          // The queue has a fixed size, so if the drainer falls behind there's a small chance that
          // this could block (which would slow down the test but not cause any other problems).
          awaitingAllReleased.put(tracker);
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
      }
    }

    private void drainQueue() {
      try {
        for (; ; ) {
          Object next = awaitingAllReleased.take();
          if (!(next instanceof ResourceTracker tracker)) {
            // This is the end-of-queue marker
            return;
          }
          // Wait 100ms at a time for the ResourceTracker to finish cleanup.
          for (int i = 0; !tracker.allReleased(); i++) {
            // If after 10s it still isn't done something else is probably wrong.
            assertThat(i).isLessThan(100);
            ++numSleeps;
            Thread.sleep(100);
          }
        }
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public String toString() {
      return String.format(
          "allocated=%s/%s, peak=%s\n(numSleeps=%s)",
          allocedBytes, allocedObjs, peakBytes, numSleeps);
    }
  }

  @Before
  public void setup() {
    VirtualMachine vm = new VirtualMachine();
    Vm.ModuleBuilder module = vm.newModule("(input)");
    insts = Compiler.compile(CharStreams.fromString(CODE), "stress", vm, module, "index");
    module.build();
  }

  /**
   * Runs {@link #insts} with the given value of {@code index}, and verifies that it returns the
   * expected result. Returns the ResourceTracker it used.
   */
  private ResourceTracker runOnce(int index) {
    // Create a new VirtualMachine for each invocation to ensure that FrameLayout evolution from
    // one invocation doesn't affect another.
    ResourceTracker tracker = new VirtualMachine().newResourceTracker(200_000, 16, false);
    Vm.Value result;
    try {
      result = insts.applyToArgs(tracker, tracker.asValue(index));
    } catch (Vm.RuntimeError e) {
      throw new AssertionError(e);
    }
    assertThat(result.toString()).isEqualTo(String.format(EXPECTED, index));
    result.close();
    return tracker;
  }

  /** Runs trials repeatedly until we've done enough. */
  private void runMany(AtomicInteger nextTrial, int numTrials, TrackerChecker trackerChecker) {
    for (; ; ) {
      int index = nextTrial.getAndIncrement();
      if (index >= numTrials) {
        break;
      }
      trackerChecker.check(runOnce(index));
    }
  }

  @Test
  public void runMany() throws InterruptedException {
    // Call runOnce thousands of times, to maximize the chances of finding a bug if there is one.
    int numTrials = 32768; // * 64;
    // Run a few at once to make this as fast as possible, but only a few because we want lots
    // of parallelism within each execution to maximize the chance of hitting a race condition.
    int numTopLevel = 3;
    AtomicInteger nextTrial = new AtomicInteger();
    TrackerChecker trackerChecker = new TrackerChecker();
    Thread[] threads = new Thread[numTopLevel];
    for (int i = 0; i < numTopLevel; i++) {
      Thread t = new Thread(() -> runMany(nextTrial, numTrials, trackerChecker));
      t.start();
      threads[i] = t;
    }
    for (Thread t : threads) {
      t.join();
    }
    trackerChecker.shutdown();
    // If we made it this far without failing any assertions the test has passed.
    // Print the observed ranges for the memory measures printed by VirtualMachineTest so that we
    // can set them properly in future.r8t
    System.out.println(trackerChecker);
  }
}
