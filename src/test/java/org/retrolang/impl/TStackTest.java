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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.impl.BaseType.SimpleStackEntryType;

@RunWith(JUnit4.class)
public class TStackTest {

  /** A SimpleStackEntryType for a function call that saves one local. */
  private static final SimpleStackEntryType CALL = new SimpleStackEntryType("myCall", "saved");

  /**
   * Builds a simple call stack for two levels of nested function ending in an undefined variable.
   */
  @Test
  public void simple() {
    // Set up a ResourceTracker and TState
    Scope scope = new Scope();
    int memoryLimit = 3000;
    ResourceTracker tracker = new ResourceTracker(scope, memoryLimit, false);
    TState tstate = TState.getOrCreate();
    tstate.bindTo(tracker);
    tstate.setStackRest(TStack.BASE);
    // Emulate
    doCall(
        tstate,
        "call1",
        () -> {
          // This call has no error (or trace), so no stack entry should be created.
          doCall(tstate, null, () -> {});
          // This call will cause us to unwind.
          doCall(
              tstate,
              "call2",
              () -> {
                Err.UNDEFINED_VAR.pushUnwind(tstate, "x");
                assertThat(tstate.unwindStarted()).isTrue();
              });
          // We should still be unwinding.
          assertThat(tstate.unwindStarted()).isTrue();
        });
    // When we get to the bottom, take the stack and verify that it has the expected entries.
    TStack tstack = tstate.takeUnwind();
    assertThat(tstack.stringStream().toArray())
        .asList()
        .containsExactly(
            "⟦Undefined var ∥ varName=\"x\"⟧",
            "⟦myCall ∥ saved=\"call2\"⟧",
            "⟦myCall ∥ saved=\"call1\"⟧",
            "⟦StackBase⟧");
    // Drop it, and make sure that everyone did the reference counting properly.
    tstate.dropReference(tstack);
    tstate.bindTo(null);
    assertThat(tracker.allReleased()).isTrue();
  }

  /**
   * Implements the proper stack handling for a function call, as described at {@link
   * TState#startCall}.
   *
   * <p>If {@code save} is null, the body is expected to complete without requiring that a stack
   * entry be created. Otherwise a stack entry should be requested, and we'll create one that wraps
   * the value of {@code save}.
   */
  private static void doCall(TState tstate, String save, Runnable body) {
    assertThat(tstate.unwindStarted()).isFalse();
    TStack prev = tstate.beforeCall();
    body.run();
    if (tstate.callEntryNeeded()) {
      assertThat(save).isNotNull();
      tstate.afterCall(
          prev, CompoundValue.of(tstate, CALL, i -> new StringValue(tstate, save)), null, null);
    } else {
      assertThat(save).isNull();
      tstate.afterCall(prev);
    }
  }
}
