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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.retrolang.Vm;
import org.retrolang.util.Bits;

@RunWith(JUnitParamsRunner.class)
public class MemoMergerTest {
  private static final int MEMORY_LIMIT = 3000;

  TState tstate;
  ResourceTracker tracker;

  // These will be non-null if enableMultiThreads(true) has been called.
  ExecutorService threadPool;
  Phaser inProgress;

  @Before
  public void setup() {
    tracker = new ResourceTracker(new Scope(), MEMORY_LIMIT, true);
    tstate = TState.resetAndGet();
    tstate.bindTo(tracker);
    // Ensure that the core module is initialized before we try to call e.g. Core.size.fn()
    var unused = Core.core();
  }

  /** Uses multiple threads for this test if concurrent is true. */
  private void enableMultiThreadsIf(boolean concurrent) {
    if (concurrent) {
      enableMultiThreads(7);
    }
  }

  /** Sets up this test to use a thread pool of the specified size. */
  private void enableMultiThreads(int poolSize) {
    inProgress = new Phaser(1);
    threadPool =
        Executors.newFixedThreadPool(
            poolSize,
            runnable ->
                Executors.defaultThreadFactory()
                    .newThread(
                        () -> {
                          TState.resetAndGet().bindTo(tracker);
                          runnable.run();
                        }));
  }

  /**
   * If we're running multi-threaded, wait for all queued tasks to complete and then shut down the
   * thread pool.
   */
  private void shutdownMultiThreads() {
    if (threadPool != null) {
      inProgress.awaitAdvance(inProgress.getPhase());
      threadPool.shutdown();
      threadPool = null;
    }
  }

  /**
   * If we're running single-threaded just run {@code doCall}. If we're running multi-threaded,
   * queue it for concurrent execution after incrementing the "number of calls in progress" counter.
   */
  private void startCall(Runnable doCall) {
    if (threadPool == null) {
      doCall.run();
    } else {
      inProgress.register();
      var unused = threadPool.submit(doCall);
    }
  }

  /**
   * If we're running multi-threaded, decrement the "number of calls in progress" counter; the
   * awaitAdvance() in shutdownMultiThreads() waits for it to get to zero.
   */
  private void endCall() {
    if (threadPool != null) {
      inProgress.arrive();
    }
  }

  private static class MockImpl implements MethodImpl {
    final String name;

    public MockImpl(String name) {
      this.name = name;
    }

    @Override
    public void execute(TState tstate, ResultsInfo results, MethodMemo mMemo, Object[] args) {
      throw new AssertionError();
    }

    @Override
    public void emit(CodeGen codeGen, ResultsInfo results, MethodMemo mMemo, Object[] args) {
      throw new AssertionError();
    }

    @Override
    public String toString() {
      return name;
    }
  }

  /**
   * A helper class to emulate the pattern of memo construction resulting from a call graph of n
   * distinct methods, where the method at level i calls the method at level i+1 a fixed number of
   * times.
   *
   * <p>To use this class:
   *
   * <ul>
   *   <li>Create a new {@code NestedCaller}, specifying the maximum depth and the number of calls
   *       at each level.
   *   <li>Call {@code makeRootMemo()} to get a new root MethodMemo.
   *   <li>Call {@code simulateExecution(0, rootMemo)} to emulate execution of all the nested calls.
   *   <li>Call {@code checkSummary(...)} to verify the results.
   * </ul>
   */
  class NestedCaller {
    /** The depth of the call graph. */
    final int maxDepth;

    /**
     * A distinct method is created for each level. These are all defined as methods of the size()
     * function, but that's irrelevant since none of them are actually executed through normal VM
     * operation.
     */
    final VmMethod[] methods;

    /**
     * We need a separate CallSite for each of the calls from level i to level i+1. It's easiest to
     * just reuse the same CallSites at each level, although in practice separate methods would have
     * separate CallSites.
     */
    final CallSite[] callSites;

    /**
     * A lambda that returns the args for each call at level n; called for 0 <= i < maxDepth. The
     * length of the returned array determines the number of nested calls.
     */
    final IntFunction<Object[][]> nestedCallArgs;

    NestedCaller(int maxDepth, IntFunction<Object[][]> nestedCallArgs) {
      this.maxDepth = maxDepth;
      this.nestedCallArgs = nestedCallArgs;
      this.methods = new VmMethod[maxDepth + 1];
      int maxNestedCalls = 0;
      for (int i = 0; i < maxDepth; i++) {
        int numCalls = nestedCallArgs.apply(i).length;
        assertThat(numCalls).isGreaterThan(0);
        maxNestedCalls = Math.max(maxNestedCalls, numCalls);
        methods[i] = methodForLevel(i, 1, numCalls);
      }
      // The base level methods are given weight 2, all other methods had weight 1.
      methods[maxDepth] = methodForLevel(maxDepth, 2, 0);
      this.callSites = new CallSite[maxNestedCalls];
      Arrays.setAll(callSites, i -> new CallSite(i, i, 1));
    }

    private VmMethod methodForLevel(int level, int baseWeight, int numCalls) {
      MethodMemo.Factory factory = MethodMemo.Factory.create(1, 1, numCalls, numCalls);
      return new VmMethod(
          Core.size.fn(), null, false, new MockImpl("method" + level), baseWeight, factory);
    }

    /** Returns a MethodMemo suitable for calling the root method. */
    MethodMemo makeRootMemo() {
      MemoMerger merger = tracker.scope.memoMerger;
      synchronized (merger) {
        MethodMemo result = methods[0].newMemo(merger, ARGS_NONE);
        if (!result.isExlined()) {
          result.setExlined(null);
        }
        return result;
      }
    }

    /**
     * When running a test in multi-threaded mode, we emulate each top-level nested call this many
     * times in parallel (with the same MethodMemo and args); this shouldn't change the final
     * weights, but increases the chances that we'll stumble over a race condition.
     */
    private static final int NUM_CONCURRENT = 11;

    /**
     * Simulates a call to the method at the specified depth, including all of its nested calls. If
     * multi-threaded execution is enabled the calls will be done in parallel and may not have
     * completed when this method returns.
     */
    void simulateExecution(int depth, MethodMemo mm) {
      if (depth < maxDepth) {
        Object[][] args = nestedCallArgs.apply(depth);
        for (int i = 0; i < args.length; i++) {
          CallSite callSite = callSites[i];
          // A Runnable to emulate the i-th nested call, by getting the corresponding MethodMemo and
          // then recursively simulating its execution.
          Runnable doCall =
              () -> {
                TState tstate = TState.get();
                MethodMemo nested =
                    mm.memoForCall(tstate, callSite, methods[depth + 1], args[callSite.cIndex]);
                simulateExecution(depth + 1, nested);
              };
          // If this is a multi-threaded test *and* we're at level 0, start the call NUM_CONCURRENT
          // times; otherwise just start it once (it would work to do it more often at lower levels,
          // except that in deep trees we'd end up with a ridiculous number of queued tasks).
          for (int j = 0; j < (depth == 0 && threadPool != null ? NUM_CONCURRENT : 1); j++) {
            startCall(doCall);
          }
        }
      }
      endCall();
    }

    /**
     * Returns a string summarizing the MethodMemos created by previous calls to {@link
     * #simulateExecution}.
     *
     * <p>The summary has an entry for each level, of the form "h/x{w,...}" where "h" is the number
     * of heavy MethodMemos, "x" is the number of exlined MethodMemos, and the "w"s are weights
     * (e.g. "0/1{3, 8}" would indicate that at that level there were no heavy MethodMemos, there
     * was one exlined MethodMemo, and all MethodMemos had a weight of 3 or 8).
     */
    String summarize(MethodMemo root) {
      MemoMerger merger = tracker.scope.memoMerger;
      synchronized (merger) {
        // A place to record the weights that we see at each level
        Bits.Builder[] weights = new Bits.Builder[methods.length];
        Arrays.setAll(weights, i -> new Bits.Builder());
        getWeights(root, 0, weights);
        // Now get the PerMethod objects corresponding to our methods.
        assertThat(merger.allPerMethods()).hasSize(maxDepth);
        MemoMerger.PerMethod[] perMethods = new MemoMerger.PerMethod[maxDepth];
        Arrays.setAll(perMethods, i -> merger.perMethod(methods[i]));
        // That should have just retrieved existing PerMethod objects, not created new ones.
        assertThat(merger.allPerMethods()).hasSize(maxDepth);
        // Now we've got all we need to assemble the summary.
        return IntStream.range(0, maxDepth)
            .mapToObj(
                i ->
                    String.format(
                        "%s/%s%s",
                        perMethods[i].heavy.size(),
                        perMethods[i].exlined.size(),
                        weights[i].build()))
            .collect(Collectors.joining(", "));
      }
    }

    /**
     * Walks the MethodMemo tree starting from {@code mm}, and records the weights seen at each
     * level.
     *
     * <p>This makes no attempt to avoid re-entering shared MethodMemos, and would not work if we
     * were emulating recursive calls.
     */
    private void getWeights(MethodMemo mm, int level, Bits.Builder[] weights) {
      assertThat(mm.method()).isSameInstanceAs(methods[level]);
      weights[level].set(mm.currentWeight());
      mm.forEachChild((cIndex, nested) -> getWeights(nested, level + 1, weights));
    }

    /**
     * Summarizes the results of this test and compares it with the expected result.
     *
     * <p>If the test was run sequentially the results are deterministic and should match exactly.
     *
     * <p>If the test was run multi-threaded the results are less predictable; we may exline a
     * method at an upper level, then exline at a lower level in a way that makes the upper exlining
     * unnecessary. We should still end up exlining at the same lowest level as in the sequential
     * case, but the levels above that may vary.
     */
    void checkSummary(MethodMemo root, boolean concurrent, String expected) {
      String summary = summarize(root);
      if (!concurrent) {
        assertThat(summary).isEqualTo(expected);
      } else {
        // Strip off the part of expected before the last exlined level, and just make sure that we
        // end with that.
        Matcher matcher = LAST_EXLINED.matcher(expected);
        assertThat(matcher.find()).isTrue();
        String expectedEnd = matcher.group(1);
        assertThat(summary).endsWith(expectedEnd);
        // If that passed, print out the full summary to enable some manual sanity-checking.
        System.out.printf("Pass: '%s' ends with '%s'\n", summary, expectedEnd);
      }
    }
  }

  /**
   * Matches the tail of the summary beginning with the last (deepest) level that includes at least
   * one exlined MethodMemo.
   */
  private static final Pattern LAST_EXLINED = Pattern.compile(".*(\\b\\d+/[1-9].*)");

  private static final Object[] ARGS_NONE = new Object[] {Core.NONE};
  private static final Object[] ARGS_TRUE = new Object[] {Core.TRUE};
  private static final Object[] ARGS_FALSE = new Object[] {Core.FALSE};

  /** A single nested call, with argument None. */
  private static final Object[][] ONE_NONE = new Object[][] {ARGS_NONE};

  /** Two nested calls, both with argument None. */
  private static final Object[][] TWO_NONES = new Object[][] {ARGS_NONE, ARGS_NONE};

  /** Three nested calls, all with argument None. */
  private static final Object[][] THREE_NONES = new Object[][] {ARGS_NONE, ARGS_NONE, ARGS_NONE};

  /** Two nested calls, one passing True and the other False. */
  private static final Object[][] TRUE_FALSE = new Object[][] {ARGS_TRUE, ARGS_FALSE};

  /**
   * Creates a simple five-level call graph, with each method calling the next method twice (so the
   * fifth method is called 32 times).
   *
   * <p>This is (just) enough to make the level 1 methodMemos heavy, and because there are two of
   * them they'll be merged & exlined.
   */
  @Test
  @Parameters({"false", "true"})
  public void twoCalls(boolean concurrent) {
    enableMultiThreadsIf(concurrent);
    NestedCaller caller = new NestedCaller(5, i -> TWO_NONES);
    MethodMemo root = caller.makeRootMemo();
    caller.simulateExecution(0, root);
    shutdownMultiThreads();
    // The "0/1" on the second (level 1) entry indicates that a shared (exlined) MethodMemo was
    // created for that method.  The numbers in braces are the weights of MethodMemos for the
    // corresponding methods.
    caller.checkSummary(root, concurrent, "0/0{1}, 0/1{3}, 0/0{23}, 0/0{11}, 0/0{5}");
  }

  /**
   * Creates a five-level call graph, with each method calling the next method three times.
   *
   * <p>This is enough to make the level 2 methodMemos heavy.
   */
  @Test
  @Parameters({"false", "true"})
  public void threeCalls(boolean concurrent) {
    enableMultiThreadsIf(concurrent);
    NestedCaller caller = new NestedCaller(5, i -> THREE_NONES);
    MethodMemo root = caller.makeRootMemo();
    caller.simulateExecution(0, root);
    shutdownMultiThreads();
    caller.checkSummary(root, concurrent, "0/0{1}, 0/0{10}, 0/1{3}, 0/0{22}, 0/0{7}");
  }

  /**
   * Creates a ten-level call graph, with each method calling the next method twice.
   *
   * <p>This is enough to cause exlining at levels 2 and 6.
   */
  @Test
  @Parameters({"false", "true"})
  public void deepTwoCalls(boolean concurrent) {
    enableMultiThreadsIf(concurrent);
    NestedCaller caller = new NestedCaller(10, i -> TWO_NONES);
    MethodMemo root = caller.makeRootMemo();
    caller.simulateExecution(0, root);
    shutdownMultiThreads();
    caller.checkSummary(
        root,
        concurrent,
        "0/0{1}, 0/0{7}, 0/1{3}, 0/0{31}, 0/0{15}, 0/0{7}, 0/1{3}, 0/0{23}, 0/0{11}, 0/0{5}");
  }

  /**
   * Creates a 42-level call graph, with the root method calling level 1 twice, and each method
   * after that calling the next method once.
   *
   * <p>Since there's no benefit to exlining a method that's only called from a single call site,
   * level 1 is the only level that will be exlined, even though the six levels below it exceed the
   * heavy threshold.
   */
  @Test
  @Parameters({"false", "true"})
  public void oneCall(boolean concurrent) {
    enableMultiThreadsIf(concurrent);
    NestedCaller caller = new NestedCaller(42, i -> (i == 0) ? TWO_NONES : ONE_NONE);
    MethodMemo root = caller.makeRootMemo();
    caller.simulateExecution(0, root);
    shutdownMultiThreads();
    caller.checkSummary(
        root,
        concurrent,
        "0/0{1}, 0/1{3}, 1/0{38}, 1/0{38}, 1/0{38}, 1/0{38}, 1/0{38}, 1/0{37}, 0/0{36}, 0/0{35},"
            + " 0/0{34}, 0/0{33}, 0/0{32}, 0/0{31}, 0/0{30}, 0/0{29}, 0/0{28}, 0/0{27}, 0/0{26},"
            + " 0/0{25}, 0/0{24}, 0/0{23}, 0/0{22}, 0/0{21}, 0/0{20}, 0/0{19}, 0/0{18}, 0/0{17},"
            + " 0/0{16}, 0/0{15}, 0/0{14}, 0/0{13}, 0/0{12}, 0/0{11}, 0/0{10}, 0/0{9}, 0/0{8},"
            + " 0/0{7}, 0/0{6}, 0/0{5}, 0/0{4}, 0/0{3}");
  }

  /**
   * Creates a ten-level call graph, with each method calling the next method twice but with
   * distinct args.
   *
   * <p>As in {@link #deepTwoCalls} we get exlining at levels 2 and 6, but this time we exline two
   * methods for each, one for each arg pattern.
   */
  @Test
  @Parameters({"false", "true"})
  public void deepTwoCallsDifferentArgs(boolean concurrent) {
    enableMultiThreadsIf(concurrent);
    NestedCaller caller = new NestedCaller(10, i -> TRUE_FALSE);
    MethodMemo root = caller.makeRootMemo();
    caller.simulateExecution(0, root);
    shutdownMultiThreads();
    caller.checkSummary(
        root,
        concurrent,
        "0/0{1}, 0/0{7}, 0/2{3}, 0/0{31}, 0/0{15}, 0/0{7}, 0/2{3}, 0/0{23}, 0/0{11}, 0/0{5}");
  }

  /** Creates an uncounted array of NumValue objects representing the given ints. */
  private static final Object[] intArray(int... args) {
    return Arrays.stream(args).mapToObj(arg -> NumValue.of(arg, Allocator.UNCOUNTED)).toArray();
  }

  /** Emulates a simple recursive factorial function. */
  @Test
  public void factorial() throws VmFunction.MultipleMethodsException {
    // function factorial(n) = (n < 3) ? n : n * factorial(n - 1)

    // In order to create a new function we'll need our own module.
    ModuleBuilder module = new ModuleBuilder("test");
    VmFunction function = module.newFunction("factorial", 1, i -> false, true, Vm.Access.PRIVATE);
    // We don't need to provide an exec method since we'll never actually try to execute this method
    VmMethod myMethod =
        new VmMethod(
            function,
            null,
            false,
            new MockImpl("method"),
            /* baseWeight= */ 1,
            MethodMemo.Factory.create(1, 1, 4, 4));

    // We will also need the methods for lessThan(Number, Number) and subtract(Number, Number)
    // (we would also need multiply(Number, Number) except that we stop the test as soon as we
    // get to factorial(2) and never bother to emulate the returns).
    var unusedCore = Core.core();
    VmMethod method0 = Core.lessThan.fn().findMethod(NumValue.ZERO, NumValue.ZERO);
    VmMethod method1 = Core.subtract.fn().findMethod(NumValue.ZERO, NumValue.ZERO);

    CallSite[] callSites = new CallSite[4];
    Arrays.setAll(callSites, i -> new CallSite(i, i, 1));

    int topLevelArg = 25;
    // The arg value at which our top level call becomes heavy
    // (i.e. factorial(14) calling factorial(13))
    int thresholdArg = 14;

    // Create a trivial MethodMemo in which to make the top-level call
    MethodMemo root = MethodMemo.Factory.create(0, 1, 1, 1).newMemo(/* perMethod= */ null);
    root.setExlined(null);
    MethodMemo current = root.memoForCall(tstate, callSites[0], myMethod, intArray(topLevelArg));
    MemoMerger.PerMethod perMethod = current.perMethod;
    List<MethodMemo> memos = new ArrayList<>();
    memos.add(current);
    // Emulate the recursive calls
    for (int i = topLevelArg; ; i--) {
      // The call to lessThan
      var unused = current.memoForCall(tstate, callSites[0], method0, intArray(i, 3));
      if (i < 3) {
        break;
      }
      // The call to subtract
      var unused2 = current.memoForCall(tstate, callSites[1], method1, intArray(i, 1));
      // The nested call
      MethodMemo nested = current.memoForCall(tstate, callSites[2], myMethod, intArray(i - 1));
      memos.add(nested);
      if (i > thresholdArg) {
        // All MethodMemos so far are lightweight
        assertThat(perMethod.exlined).isEmpty();
        assertThat(perMethod.heavy).isEmpty();
      } else if (i == thresholdArg) {
        // Now the top-level MethodMemo is heavy
        assertThat(perMethod.exlined).isEmpty();
        assertThat(perMethod.heavy).containsExactly(memos.get(0));
      } else {
        if (i == thresholdArg - 1) {
          // This is the call that triggered all the merging, whose final outcome is that current
          // and nested have both been merged into the top-level MethodMemo (along with all of the
          // other intervening ones).
          assertThat(nested.isForwarded()).isTrue();
          assertThat(current.isForwarded()).isTrue();
          synchronized (tracker.scope.memoMerger) {
            current = current.resolve();
            nested = nested.resolve();
          }
        }
        // On subsequent calls (after the merge step) memoForCall just returns the exlined
        // MethodMemo immediately
        assertThat(nested).isSameInstanceAs(current);
        assertThat(perMethod.heavy).isEmpty();
        assertThat(perMethod.exlined).containsExactly(current);
        // Exlined MethodMemos have a constant weight
        assertThat(current.currentWeight()).isEqualTo(MethodMemo.EXLINE_CALL_WEIGHT);
        // But if we recompute its weight from its elements we get the base weight, the two calls to
        // builtins, and the exlined call.
        synchronized (tracker.scope.memoMerger) {
          assertThat(current.computeWeight()).isEqualTo(1 + 1 + 1 + MethodMemo.EXLINE_CALL_WEIGHT);
        }
      }
      current = nested;
    }
  }
}
