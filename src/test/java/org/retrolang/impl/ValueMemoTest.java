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
import static org.retrolang.impl.Value.addRef;

import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ValueMemoTest {

  private static final int MEMORY_LIMIT = 3000;

  TState tstate;
  ResourceTracker tracker;

  @Before
  public void setup() {
    tracker = new ResourceTracker(new Scope(), MEMORY_LIMIT, true);
    tstate = TState.resetAndGet();
    tstate.bindTo(tracker);
  }

  /// Call this once at the end of each test.
  private void dropAndCheckAllReleased(Value... values) {
    Arrays.stream(values).forEach(tstate::dropValue);
    // Doing this in an @After method had the drawback that any real error during a test got buried
    // in a lot of spurious errors about objects not released.
    assertThat(tstate.unwindStarted()).isFalse();
    assertThat(tstate.bindTo(null)).isSameInstanceAs(tracker);
    assertThat(tracker.allReleased()).isTrue();
  }

  @Test
  public void numbers() {
    ValueMemo vm = ValueMemo.withSize(3);
    for (int i = 0; i < 3; i++) {
      assertThat(vm.harmonize(tstate, i, Core.NONE)).isSameInstanceAs(Core.NONE);
    }
    Value i0 = NumValue.of(300, tstate);
    Value d0 = NumValue.of(3.14, tstate);
    // Harmonizing numbers doesn't change them
    assertThat(vm.harmonize(tstate, 0, i0)).isSameInstanceAs(i0);
    assertThat(vm.harmonize(tstate, 1, d0)).isSameInstanceAs(d0);
    assertThat(vm.harmonize(tstate, 1, i0)).isSameInstanceAs(i0);
    // But the ValueMemo remembers where it has seen doubles
    assertThat(vm.result(0, TProperty.COERCES_TO_FLOAT)).isFalse();
    assertThat(vm.result(1, TProperty.COERCES_TO_FLOAT)).isTrue();
    assertThat(vm.result(2, TProperty.COERCES_TO_FLOAT)).isFalse();
    dropAndCheckAllReleased(i0, d0);
  }

  @Test
  public void arrays() {
    ValueMemo vm = ValueMemo.withSize(1);
    Value v1 = tstate.compound(Core.FixedArrayType.withSize(2), Core.TRUE, Core.EMPTY_ARRAY);
    Value v2 = tstate.compound(Core.FixedArrayType.withSize(2), Core.FALSE, addRef(v1));
    assertThat(vm.harmonize(tstate, 0, v1)).isSameInstanceAs(v1);
    Value v2x = vm.harmonize(tstate, 0, addRef(v2));
    assertThat(v2x).isEqualTo(v2);
    FrameLayout layout = v2x.peekElement(1).layout();
    assertThat(layout).isInstanceOf(VArrayLayout.class);
    assertThat(vm.result(TProperty.ARRAY_LAYOUT)).isNull();
    // When this ValueMemo harmonizes a 2-element array, and the second element is an array,
    // it will use this layout.
    assertThat(vm.result(TProperty.ARRAY_LAYOUT.elementOf(Core.FixedArrayType.withSize(2), 1)))
        .isSameInstanceAs(layout);
    dropAndCheckAllReleased(v1, v2, v2x);
  }
}
