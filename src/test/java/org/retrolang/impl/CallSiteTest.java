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

@RunWith(JUnit4.class)
public class CallSiteTest {

  private static final int MEMORY_LIMIT = 3000;

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
  public void basicCallSite() {
    CallSite callSite = new CallSite(0, 2);

    assertThat(callSite.cIndex).isEqualTo(0);
    assertThat(callSite.numResultsKept).isEqualTo(2);
    assertThat(callSite.vIndex).isEqualTo(-1); // default for tail call
  }

  @Test
  public void setValueMemoSize() {
    CallSite callSite = new CallSite(0, 1);

    callSite.setValueMemoSize(5);
    assertThat(callSite.vIndex).isGreaterThan(0);
  }

  @Test
  public void setIgnoredResultsAllKept() {
    CallSite callSite = new CallSite(0, 3);

    callSite.setIgnoredResults(i -> false); // All kept

    assertThat(callSite.numResultsKept).isEqualTo(3);
    assertThat(callSite.resultsMapping).isNull();
  }

  @Test
  public void setIgnoredResultsSomeIgnored() {
    CallSite callSite = new CallSite(0, 4);

    // Ignore indices 1 and 3
    callSite.setIgnoredResults(i -> i == 1 || i == 3);

    assertThat(callSite.numResultsKept).isEqualTo(2);
    assertThat(callSite.resultsMapping).isNotNull();
    assertThat(callSite.resultsMapping).hasLength(2);
    assertThat(callSite.resultsMapping[0]).isEqualTo(0);
    assertThat(callSite.resultsMapping[1]).isEqualTo(2);
  }

  @Test
  public void keptWithNoIgnored() {
    CallSite callSite = new CallSite(0, 3);

    Value v1 = NumValue.ZERO;
    Value v2 = NumValue.ONE;
    Value v3 = NumValue.of(2, Allocator.UNCOUNTED);

    Value[] values = new Value[] {v1, v2, v3};
    Value[] kept = callSite.kept(values);

    assertThat(kept).isSameInstanceAs(values);
  }

  @Test
  public void keptWithIgnored() {
    CallSite callSite = new CallSite(0, 4);
    callSite.setIgnoredResults(i -> i == 1 || i == 3);

    Value v0 = NumValue.ZERO;
    Value v1 = NumValue.ONE;
    Value v2 = NumValue.of(2, Allocator.UNCOUNTED);
    Value v3 = NumValue.of(3, Allocator.UNCOUNTED);

    Value[] values = new Value[] {v0, v1, v2, v3};
    Value[] kept = callSite.kept(values);

    assertThat(kept).hasLength(2);
    assertThat(kept[0]).isSameInstanceAs(v0);
    assertThat(kept[1]).isSameInstanceAs(v2);
  }

  @Test
  public void keptWithAllIgnoredExceptOne() {
    CallSite callSite = new CallSite(0, 3);
    callSite.setIgnoredResults(i -> i != 1);

    Value v0 = NumValue.ZERO;
    Value v1 = NumValue.ONE;
    Value v2 = NumValue.of(2, Allocator.UNCOUNTED);

    Value[] values = new Value[] {v0, v1, v2};
    Value[] kept = callSite.kept(values);

    assertThat(kept).hasLength(1);
    assertThat(kept[0]).isSameInstanceAs(v1);
  }

  @Test
  public void adjustResultsInfoNotDiscarded() {
    CallSite callSite = new CallSite(0, 2);

    // Test with a simple property
    ResultsInfo resultsInfo = new ResultsInfo() {
      @Override
      public <T> T property(int resultIndex, TProperty<T> property) {
        return property.defaultValue();
      }
    };

    ResultsInfo adjusted = callSite.adjustResultsInfo(resultsInfo);
    assertThat(adjusted).isSameInstanceAs(resultsInfo);
  }

  @Test
  public void adjustResultsInfoWithIgnored() {
    CallSite callSite = new CallSite(0, 3);
    callSite.setIgnoredResults(i -> i == 1); // Ignore middle result

    ResultsInfo resultsInfo = new ResultsInfo() {
      @Override
      public <T> T property(int resultIndex, TProperty<T> property) {
        if (property == ResultsInfo.IS_DISCARDED) {
          return property.defaultValue();
        }
        return property.defaultValue();
      }
    };

    ResultsInfo adjusted = callSite.adjustResultsInfo(resultsInfo);
    assertThat(adjusted).isNotSameInstanceAs(resultsInfo);

    // Check that ignored index returns TRUE for IS_DISCARDED
    Boolean isDiscarded = adjusted.property(1, ResultsInfo.IS_DISCARDED);
    assertThat(isDiscarded).isTrue();
  }

  @Test
  public void callSiteWithTailCall() {
    CallSite callSite = new CallSite(5, 1);

    // Tail call should have vIndex = -1
    assertThat(callSite.vIndex).isEqualTo(-1);

    // Can still set value memo size for non-tail call sites
    CallSite nonTailCall = new CallSite(6, 1);
    nonTailCall.setValueMemoSize(3);
    assertThat(nonTailCall.vIndex).isGreaterThan(0);
  }

  @Test
  public void multipleCallSites() {
    CallSite callSite1 = new CallSite(0, 2);
    CallSite callSite2 = new CallSite(1, 1);
    CallSite callSite3 = new CallSite(2, 3);

    assertThat(callSite1.cIndex).isEqualTo(0);
    assertThat(callSite2.cIndex).isEqualTo(1);
    assertThat(callSite3.cIndex).isEqualTo(2);
  }

  @Test
  public void keptWithEmptyArray() {
    CallSite callSite = new CallSite(0, 0);

    Value[] values = new Value[0];
    Value[] kept = callSite.kept(values);

    assertThat(kept).isSameInstanceAs(values);
  }

  @Test
  public void setIgnoredResultsChaining() {
    CallSite callSite = new CallSite(0, 5);

    // Ignore 0, 2, 4 (keep 1, 3)
    callSite.setIgnoredResults(i -> i % 2 == 0);

    assertThat(callSite.numResultsKept).isEqualTo(2);
    assertThat(callSite.resultsMapping).hasLength(2);
    assertThat(callSite.resultsMapping[0]).isEqualTo(1);
    assertThat(callSite.resultsMapping[1]).isEqualTo(3);
  }

  @Test
  public void callSiteIndependence() {
    CallSite cs1 = new CallSite(0, 2);
    CallSite cs2 = new CallSite(1, 2);

    cs1.setIgnoredResults(i -> i == 0);

    // cs2 should not be affected
    assertThat(cs2.resultsMapping).isNull();
    assertThat(cs2.numResultsKept).isEqualTo(2);
  }
}