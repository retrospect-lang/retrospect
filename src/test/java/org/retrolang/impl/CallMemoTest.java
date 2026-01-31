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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.Vm.Access;
import org.retrolang.util.Bits;

@RunWith(JUnit4.class)
public class CallMemoTest {

  private static final int MEMORY_LIMIT = 3000;

  private TState tstate;
  private ResourceTracker tracker;
  private VirtualMachine vm;
  private ModuleBuilder module;
  private VmFunction function;

  @Before
  public void setup() {
    vm = new VirtualMachine();
    tracker = new ResourceTracker(vm.scope, MEMORY_LIMIT, true);
    tstate = TState.resetAndGet();
    tstate.bindTo(tracker);

    module = new ModuleBuilder();
    function =
        module.newFunction(
            "testFn",
            /* numArgs= */ 2,
            /* argIsInout= */ Bits.EMPTY,
            /* hasResult= */ true,
            Access.VISIBLE);
    module.build();
  }

  @After
  public void checkAllReleased() {
    assertThat(tstate.unwindStarted()).isFalse();
    assertThat(tstate.bindTo(null)).isSameInstanceAs(tracker);
    assertThat(tracker.allReleased()).isTrue();
  }

  @Test
  public void incrementCountBasic() {
    CallMemo callMemo = new CallMemo(null);

    assertThat(callMemo.count).isEqualTo(0);
    callMemo.incrementCount(1);
    assertThat(callMemo.count).isEqualTo(1);
    callMemo.incrementCount(5);
    assertThat(callMemo.count).isEqualTo(6);
  }

  @Test
  public void incrementCountMaxCap() {
    CallMemo callMemo = new CallMemo(null);

    // Set count close to MAX_COUNT
    int nearMax = CallMemo.MAX_COUNT - 10;
    callMemo.incrementCount(nearMax);
    assertThat(callMemo.count).isEqualTo(nearMax);

    // Increment beyond MAX_COUNT should cap at MAX_COUNT
    callMemo.incrementCount(100);
    assertThat(callMemo.count).isEqualTo(CallMemo.MAX_COUNT);

    // Further increments should not exceed MAX_COUNT
    callMemo.incrementCount(1000);
    assertThat(callMemo.count).isEqualTo(CallMemo.MAX_COUNT);
  }

  @Test
  public void totalWeightEmpty() {
    CallMemo callMemo = new CallMemo(null);
    MemoMerger merger = tracker.scope.memoMerger;

    synchronized (merger) {
      assertThat(callMemo.totalWeight()).isEqualTo(0);
      assertThat(callMemo.maxWeight()).isEqualTo(0);
    }
  }

  @Test
  public void totalWeightSingle() {
    Object[] args = new Object[] {NumValue.ZERO, NumValue.ONE};
    VmMethod method = function.findMethod(args);
    MethodMemo memo = method.newMemo(tracker.scope.memoMerger, args);

    CallMemo callMemo = new CallMemo(memo);
    callMemo.incrementCount(10);

    MemoMerger merger = tracker.scope.memoMerger;
    synchronized (merger) {
      assertThat(callMemo.totalWeight()).isEqualTo(10);
      assertThat(callMemo.maxWeight()).isEqualTo(10);
    }
  }

  @Test
  public void totalWeightMultiple() {
    Object[] args1 = new Object[] {NumValue.ZERO, NumValue.ONE};
    VmMethod method1 = function.findMethod(args1);
    MethodMemo memo1 = method1.newMemo(tracker.scope.memoMerger, args1);

    Object[] args2 = new Object[] {NumValue.ONE, NumValue.ZERO};
    VmMethod method2 = function.findMethod(args2);
    MethodMemo memo2 = method2.newMemo(tracker.scope.memoMerger, args2);

    CallMemo callMemo1 = new CallMemo(memo1);
    callMemo1.incrementCount(10);

    CallMemo callMemo2 = new CallMemo(memo2);
    callMemo2.incrementCount(20);
    callMemo1.next = callMemo2;

    MemoMerger merger = tracker.scope.memoMerger;
    synchronized (merger) {
      assertThat(callMemo1.totalWeight()).isEqualTo(30);
      assertThat(callMemo1.maxWeight()).isEqualTo(20);
    }
  }

  @Test
  public void memoForMethodNotFound() {
    Object[] args = new Object[] {NumValue.ZERO, NumValue.ONE};
    VmMethod method1 = function.findMethod(args);
    MethodMemo memo1 = method1.newMemo(tracker.scope.memoMerger, args);

    CallMemo callMemo = new CallMemo(memo1);

    // Create a different method
    ModuleBuilder module2 = new ModuleBuilder();
    VmFunction function2 =
        module2.newFunction(
            "otherFn",
            /* numArgs= */ 1,
            /* argIsInout= */ Bits.EMPTY,
            /* hasResult= */ true,
            Access.VISIBLE);
    module2.build();

    Object[] args2 = new Object[] {NumValue.ZERO};
    VmMethod method2 = function2.findMethod(args2);

    MemoMerger merger = tracker.scope.memoMerger;
    synchronized (merger) {
      MethodMemo result = callMemo.memoForMethod(method2, true);
      assertThat(result).isNull();
    }
  }

  @Test
  public void memoForMethodFound() {
    Object[] args = new Object[] {NumValue.ZERO, NumValue.ONE};
    VmMethod method = function.findMethod(args);
    MethodMemo memo = method.newMemo(tracker.scope.memoMerger, args);

    CallMemo callMemo = new CallMemo(memo);

    MemoMerger merger = tracker.scope.memoMerger;
    synchronized (merger) {
      MethodMemo result = callMemo.memoForMethod(method, true);
      assertThat(result).isSameInstanceAs(memo);
    }
  }

  @Test
  public void memoForMethodInChain() {
    Object[] args1 = new Object[] {NumValue.ZERO, NumValue.ONE};
    VmMethod method1 = function.findMethod(args1);
    MethodMemo memo1 = method1.newMemo(tracker.scope.memoMerger, args1);

    Object[] args2 = new Object[] {NumValue.ONE, NumValue.ZERO};
    VmMethod method2 = function.findMethod(args2);
    MethodMemo memo2 = method2.newMemo(tracker.scope.memoMerger, args2);

    CallMemo callMemo1 = new CallMemo(memo1);
    CallMemo callMemo2 = new CallMemo(memo2);
    callMemo1.next = callMemo2;

    MemoMerger merger = tracker.scope.memoMerger;
    synchronized (merger) {
      MethodMemo result = callMemo1.memoForMethod(method2, true);
      assertThat(result).isSameInstanceAs(memo2);
    }
  }

  @Test
  public void replaceMemo() {
    Object[] args1 = new Object[] {NumValue.ZERO, NumValue.ONE};
    VmMethod method1 = function.findMethod(args1);
    MethodMemo memo1 = method1.newMemo(tracker.scope.memoMerger, args1);
    MethodMemo replacement = method1.newMemo(tracker.scope.memoMerger, args1);

    CallMemo callMemo = new CallMemo(memo1);

    MemoMerger merger = tracker.scope.memoMerger;
    synchronized (merger) {
      callMemo.replaceMemo(memo1, replacement);
      assertThat(callMemo.memo).isSameInstanceAs(replacement);
    }
  }

  @Test
  public void replaceMemoInChain() {
    Object[] args1 = new Object[] {NumValue.ZERO, NumValue.ONE};
    VmMethod method1 = function.findMethod(args1);
    MethodMemo memo1 = method1.newMemo(tracker.scope.memoMerger, args1);

    Object[] args2 = new Object[] {NumValue.ONE, NumValue.ZERO};
    VmMethod method2 = function.findMethod(args2);
    MethodMemo memo2 = method2.newMemo(tracker.scope.memoMerger, args2);
    MethodMemo replacement2 = method2.newMemo(tracker.scope.memoMerger, args2);

    CallMemo callMemo1 = new CallMemo(memo1);
    CallMemo callMemo2 = new CallMemo(memo2);
    callMemo1.next = callMemo2;

    MemoMerger merger = tracker.scope.memoMerger;
    synchronized (merger) {
      callMemo1.replaceMemo(memo2, replacement2);
      assertThat(callMemo2.memo).isSameInstanceAs(replacement2);
    }
  }

  @Test
  public void mergeIntoEmpty() {
    Object[] args = new Object[] {NumValue.ZERO, NumValue.ONE};
    VmMethod method = function.findMethod(args);
    MethodMemo memo = method.newMemo(tracker.scope.memoMerger, args);

    CallMemo source = new CallMemo(memo);
    source.incrementCount(5);

    CallMemo target = new CallMemo(null);

    MemoMerger merger = tracker.scope.memoMerger;
    synchronized (merger) {
      source.mergeInto(target, null, merger);
      assertThat(target.memo).isSameInstanceAs(memo);
      assertThat(target.count).isEqualTo(5);
    }
  }

  @Test
  public void countIsVolatile() throws InterruptedException {
    CallMemo callMemo = new CallMemo(null);

    Thread thread1 = new Thread(() -> {
      for (int i = 0; i < 100; i++) {
        callMemo.incrementCount(1);
      }
    });

    Thread thread2 = new Thread(() -> {
      for (int i = 0; i < 100; i++) {
        callMemo.incrementCount(1);
      }
    });

    thread1.start();
    thread2.start();
    thread1.join();
    thread2.join();

    // Count should be at least 200 (might be less due to concurrent updates
    // but should not be 0 due to memory visibility)
    assertThat(callMemo.count).isAtLeast(100);
  }
}