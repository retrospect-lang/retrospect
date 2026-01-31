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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class FutureValueTest {

  private static final int MEMORY_LIMIT = 10000;

  private TState tstate;
  private ResourceTracker tracker;

  @Before
  public void setup() {
    tracker = new ResourceTracker(new Scope(), MEMORY_LIMIT, true);
    tstate = TState.resetAndGet();
    tstate.bindTo(tracker);
  }

  @After
  public void checkAllReleased() {
    assertThat(tstate.unwindStarted()).isFalse();
    assertThat(tstate.bindTo(null)).isSameInstanceAs(tracker);
    assertThat(tracker.allReleased()).isTrue();
  }

  @Test
  public void testFutureBasic() {
    FutureValue future = FutureValue.testFuture(tstate);

    assertThat(future).isNotNull();
    assertThat(future.result).isNull();

    Value result = new StringValue(tstate, "test result");
    FutureValue.setTestFuture(future, result);

    assertThat(future.result).isNotNull();
    tstate.dropValue(future);
  }

  @Test
  public void testFutureWithWait() throws InterruptedException {
    FutureValue future = FutureValue.testFuture(tstate);

    AtomicBoolean completed = new AtomicBoolean(false);
    Thread waiter = new Thread(() -> {
      try {
        TState localTstate = TState.resetAndGet();
        localTstate.bindTo(tracker);
        FutureValue.waitFor(future);
        completed.set(true);
        localTstate.bindTo(null);
      } catch (Err.BuiltinException e) {
        // Ignore
      }
    });

    waiter.start();
    Thread.sleep(100); // Give waiter time to block

    assertThat(completed.get()).isFalse();

    Value result = new StringValue(tstate, "delayed result");
    FutureValue.setTestFuture(future, result);

    waiter.join(1000);
    assertThat(completed.get()).isTrue();

    tstate.dropValue(future);
  }

  @Test
  public void future1Execution() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    FutureValue future = FutureValue.future1(
        tstate,
        tstate1 -> {
          latch.countDown();
          return new StringValue(tstate1, "async result");
        });

    assertThat(future).isNotNull();

    // Wait for execution to complete
    assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

    // Wait for the result to be available
    FutureValue.waitFor(future);
    assertThat(future.result).isNotNull();

    tstate.dropValue(future);
  }

  @Test
  public void future1WithError() throws InterruptedException {
    FutureValue future = FutureValue.future1(
        tstate,
        tstate1 -> {
          throw new RuntimeException("Test error");
        });

    assertThat(future).isNotNull();

    // Wait a bit for execution
    Thread.sleep(200);

    // The future should have an error result
    assertThat(future.result).isNotNull();

    tstate.dropValue(future);
  }

  @Test
  public void setTestFutureMultipleTimes() {
    FutureValue future = FutureValue.testFuture(tstate);

    Value result1 = new StringValue(tstate, "first");
    FutureValue.setTestFuture(future, result1);
    assertThat(future.result).isSameInstanceAs(result1);

    // Setting again should update the result
    Value result2 = new StringValue(tstate, "second");
    FutureValue.setTestFuture(future, result2);
    assertThat(future.result).isSameInstanceAs(result2);

    tstate.dropValue(future);
    tstate.dropValue(result1);
  }

  @Test
  public void futureValueIsValue() {
    FutureValue future = FutureValue.testFuture(tstate);

    assertThat(future).isInstanceOf(Value.class);

    tstate.dropValue(future);
  }

  @Test
  public void waitForCompletedFuture() {
    FutureValue future = FutureValue.testFuture(tstate);
    Value result = new StringValue(tstate, "immediate");
    FutureValue.setTestFuture(future, result);

    // Waiting for an already-completed future should return immediately
    FutureValue.waitFor(future);

    assertThat(future.result).isNotNull();
    tstate.dropValue(future);
  }

  @Test
  public void future1ReferenceCounting() throws InterruptedException {
    FutureValue future = FutureValue.future1(
        tstate,
        tstate1 -> {
          return NumValue.ZERO;
        });

    assertThat(future).isNotNull();

    // Wait for completion
    FutureValue.waitFor(future);

    // Should be properly reference counted
    tstate.dropValue(future);
  }

  @Test
  public void concurrentWaitersOnSameFuture() throws InterruptedException {
    FutureValue future = FutureValue.testFuture(tstate);

    int numWaiters = 5;
    CountDownLatch allStarted = new CountDownLatch(numWaiters);
    CountDownLatch allCompleted = new CountDownLatch(numWaiters);

    for (int i = 0; i < numWaiters; i++) {
      new Thread(() -> {
        try {
          TState localTstate = TState.resetAndGet();
          localTstate.bindTo(tracker);
          allStarted.countDown();
          FutureValue.waitFor(future);
          allCompleted.countDown();
          localTstate.bindTo(null);
        } catch (Err.BuiltinException e) {
          // Ignore
        }
      }).start();
    }

    assertThat(allStarted.await(1, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(100); // Give threads time to block

    Value result = new StringValue(tstate, "shared result");
    FutureValue.setTestFuture(future, result);

    assertThat(allCompleted.await(2, TimeUnit.SECONDS)).isTrue();

    tstate.dropValue(future);
  }

  @Test
  public void dropFutureBeforeCompletion() throws InterruptedException {
    CountDownLatch executionStarted = new CountDownLatch(1);
    CountDownLatch canComplete = new CountDownLatch(1);

    FutureValue future = FutureValue.future1(
        tstate,
        tstate1 -> {
          executionStarted.countDown();
          try {
            canComplete.await(2, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            // Ignore
          }
          return new StringValue(tstate1, "late result");
        });

    assertThat(executionStarted.await(1, TimeUnit.SECONDS)).isTrue();

    // Drop the future before it completes
    tstate.dropValue(future);

    // Allow the background thread to complete
    canComplete.countDown();
    Thread.sleep(200);

    // The late result should be discarded (no memory leak)
  }
}