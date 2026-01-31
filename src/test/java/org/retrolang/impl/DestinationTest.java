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

import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DestinationTest {

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
  public void trivialDestinationNoValues() {
    Destination dest = Destination.fromTemplates(ImmutableList.of());

    assertThat(dest).isNotNull();
    assertThat(dest.size()).isEqualTo(0);
  }

  @Test
  public void destinationFromTemplates() {
    ImmutableList<Template> templates = ImmutableList.of(
        Template.NumVar.INT32,
        Core.STRING.asRefVar);

    Destination dest = Destination.fromTemplates(templates);

    assertThat(dest).isNotNull();
    assertThat(dest.size()).isEqualTo(2);
  }

  @Test
  public void destinationCreateChild() {
    ImmutableList<Template> templates = ImmutableList.of(
        Template.NumVar.INT32,
        Core.STRING.asRefVar,
        Template.NumVar.DOUBLE);

    Destination parent = Destination.fromTemplates(templates);
    Destination child = parent.createChild(2);

    assertThat(child).isNotNull();
    assertThat(child.size()).isEqualTo(2);
  }

  @Test
  public void destinationSingleValue() {
    ImmutableList<Template> templates = ImmutableList.of(Template.NumVar.INT32);
    Destination dest = Destination.fromTemplates(templates);

    assertThat(dest.size()).isEqualTo(1);
  }

  @Test
  public void destinationMultipleValues() {
    ImmutableList<Template> templates = ImmutableList.of(
        Template.NumVar.INT32,
        Template.NumVar.DOUBLE,
        Core.STRING.asRefVar,
        Core.NONE.asTemplate);

    Destination dest = Destination.fromTemplates(templates);

    assertThat(dest.size()).isEqualTo(4);
  }

  @Test
  public void destinationWithNumericTypes() {
    ImmutableList<Template> templates = ImmutableList.of(
        Template.NumVar.INT8,
        Template.NumVar.INT32,
        Template.NumVar.DOUBLE);

    Destination dest = Destination.fromTemplates(templates);

    assertThat(dest.size()).isEqualTo(3);
  }

  @Test
  public void destinationWithReferenceTypes() {
    ImmutableList<Template> templates = ImmutableList.of(
        Core.STRING.asRefVar,
        Core.NONE.asTemplate);

    Destination dest = Destination.fromTemplates(templates);

    assertThat(dest.size()).isEqualTo(2);
  }

  @Test
  public void destinationChildPrefixSize() {
    ImmutableList<Template> templates = ImmutableList.of(
        Template.NumVar.INT32,
        Template.NumVar.DOUBLE,
        Core.STRING.asRefVar);

    Destination parent = Destination.fromTemplates(templates);

    Destination child0 = parent.createChild(0);
    assertThat(child0.size()).isEqualTo(0);

    Destination child1 = parent.createChild(1);
    assertThat(child1.size()).isEqualTo(1);

    Destination child3 = parent.createChild(3);
    assertThat(child3.size()).isEqualTo(3);
  }

  @Test
  public void destinationIndependence() {
    ImmutableList<Template> templates1 = ImmutableList.of(Template.NumVar.INT32);
    ImmutableList<Template> templates2 = ImmutableList.of(Template.NumVar.DOUBLE);

    Destination dest1 = Destination.fromTemplates(templates1);
    Destination dest2 = Destination.fromTemplates(templates2);

    assertThat(dest1.size()).isEqualTo(1);
    assertThat(dest2.size()).isEqualTo(1);
  }
}