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
import static com.google.common.truth.Truth.assertWithMessage;
import static org.retrolang.impl.Value.addRef;

import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.impl.Template.NumVar;

@RunWith(JUnit4.class)
public class FrameTest {

  private static final int MEMORY_LIMIT = 3000;

  TState tstate;
  ResourceTracker tracker;

  @Before
  public void setup() {
    tracker = new ResourceTracker(new Scope(), MEMORY_LIMIT, true);
    tstate = TState.resetAndGet();
    tstate.bindTo(tracker);
  }

  /** Call this once at the end of each test. */
  private void dropAndCheckAllReleased(Value... values) {
    Arrays.stream(values).forEach(tstate::dropValue);
    // Doing this in an @After method had the drawback that any real error during a test got buried
    // in a lot of spurious errors about objects not released.
    assertThat(tstate.unwindStarted()).isFalse();
    assertThat(tstate.bindTo(null)).isSameInstanceAs(tracker);
    assertThat(tracker.allReleased()).isTrue();
  }

  /**
   * Verifies that {@code layout.toString()} matches {@code expected}, after removing the
   * "@(hexString)" UIDs.
   */
  private static void assertLayoutMatches(FrameLayout layout, String expected) {
    String s = layout.toString();
    String withoutIds = s.replaceAll("@[0-9a-f]+", "");
    assertWithMessage(s).that(withoutIds).isEqualTo(expected);
  }

  /** Returns a template for an array containing the given elements. */
  private static Template arrayOf(Template... elements) {
    return Template.Compound.of(Core.FixedArrayType.withSize(elements.length), elements);
  }

  @Test
  public void recordRecursive() {
    // Start with a record that can store a pair, either element of which is NONE or a pair of NONEs
    Value v1 = tstate.compound(Core.FixedArrayType.withSize(2), Core.NONE, Core.NONE);
    Value v2 = tstate.compound(Core.FixedArrayType.withSize(2), addRef(v1), addRef(v1));
    TemplateBuilder t = Template.EMPTY.add(v1).add(v2);
    RecordLayout layout1 = RecordLayout.newFromBuilder(tracker.scope, t);
    // This record only needs 2 bytes
    assertLayoutMatches(layout1, "*[b0⸨0:[None, None]; 1:None⸩, b1⸨0:[None, None]; 1:None⸩]");
    // Create and initialize an instance
    Frame f1 = layout1.alloc(tstate, 2);
    assertThat(layout1.setElement(tstate, f1, 0, Core.NONE)).isTrue();
    assertThat(layout1.setElement(tstate, f1, 1, v1)).isTrue();
    assertThat(f1.toString()).isEqualTo("[None, [None, None]]");
    // Create and initialize another instance, then use replaceElement to modify it
    Frame f2 = layout1.alloc(tstate, 2);
    assertThat(layout1.setElement(tstate, f2, 0, Core.NONE)).isTrue();
    assertThat(layout1.setElement(tstate, f2, 1, Core.NONE)).isTrue();
    // Since this replacement fits within the layout it can be done in-place
    assertThat(f2.replaceElement(tstate, 0, addRef(v1))).isSameInstanceAs(f2);
    assertThat(f2.toString()).isEqualTo("[[None, None], None]");
    // This replacement cannot be done in-place, since f1 isn't None or [None, None], so we will be
    // forced to evolve the layout and create a new Frame.
    Frame f3 = (Frame) f2.replaceElement(tstate, 1, addRef(f1));
    assertThat(f3.toString()).isEqualTo("[[None, None], [None, [None, None]]]");
    // Now the second element has evolved to an untagged union of a record or None
    assertLayoutMatches(f3.layout(), "*[b0⸨0:[None, None]; 1:None⸩, ⸨x0:*; None⸩]");
    // Since f1's layout has evolved, we need to get its replacement before passing it
    // as an argument to replaceElement().
    f1 = Frame.latest(f1);
    // Another replacement that forces a second layout change.  The addRef(f3) keeps f3 a valid
    // pointer to its original value.
    Frame f4 = (Frame) addRef(f3).replaceElement(tstate, 0, addRef(f1));
    FrameLayout layout2 = f4.layout();
    // Now our record just contains two pointers, each an untagged union of a record or none
    assertLayoutMatches(layout2, "*[⸨x0:*; None⸩, ⸨x1:*; None⸩]");
    // The layout of f4 is a (two-step) evolution of layout1.
    assertThat(layout1.latest()).isSameInstanceAs(layout2);
    // That means that both refVars in layout2 have it as their layout (the layout is recursive), so
    // we can nest instances of this layout.
    Frame f5 = layout2.alloc(tstate, 2);
    assertThat(layout2.setElement(tstate, f5, 0, f3)).isTrue();
    assertThat(layout2.setElement(tstate, f5, 1, f4)).isTrue();
    assertThat(f5.toString())
        .isEqualTo(
            "[[[None, None], [None, [None, None]]], [[None, [None, None]], [None, [None, None]]]]");
    // The untagged union allows us to replace either element with None in-place
    assertThat(f5.replaceElement(tstate, 1, Core.NONE)).isSameInstanceAs(f5);
    assertThat(f5.toString()).isEqualTo("[[[None, None], [None, [None, None]]], None]");
    dropAndCheckAllReleased(v1, v2, f1, f3, f4, f5);
  }

  @Test
  public void recordOverflowBytes() {
    Value half = NumValue.of(0.5, tstate);
    Value quarter = NumValue.of(0.25, tstate);
    Value eighth = NumValue.of(0.125, tstate);
    // The largest Frame currently defined has 11 ints, so 6 doubles should force us to use overflow
    int numDoubles = 6;
    Value v1 =
        tstate.compound(
            Core.FixedArrayType.withSize(numDoubles),
            addRef(half),
            addRef(quarter),
            addRef(eighth),
            addRef(half),
            addRef(quarter),
            addRef(eighth));
    TemplateBuilder t = Template.EMPTY.add(v1);
    RecordLayout layout1 = RecordLayout.newFromBuilder(tracker.scope, t);
    assertThat(layout1.byteOverflowSize).isEqualTo(24);
    assertThat(layout1.ptrOverflowSize).isEqualTo(0);
    Frame f1 = layout1.alloc(tstate, numDoubles);
    for (int i = 0; i < numDoubles; i++) {
      assertThat(layout1.setElement(tstate, f1, i, v1.peekElement(i))).isTrue();
    }
    assertThat(f1.toString()).isEqualTo("[0.5, 0.25, 0.125, 0.5, 0.25, 0.125]");
    dropAndCheckAllReleased(half, quarter, eighth, v1, f1);
  }

  @Test
  public void recordOverflowPtrs() {
    Value x = new StringValue(tstate, "x");
    Value y = new StringValue(tstate, "y");
    Value z = new StringValue(tstate, "z");
    // The largest Frame currently defined has 10 ptrs.
    int numStrings = 11;
    Value v1 =
        tstate.compound(
            Core.FixedArrayType.withSize(numStrings),
            addRef(x),
            addRef(y),
            addRef(z),
            addRef(z),
            addRef(y),
            addRef(z),
            addRef(y),
            addRef(z),
            addRef(z),
            addRef(y),
            addRef(x));
    TemplateBuilder t = Template.EMPTY.add(v1);
    RecordLayout layout1 = RecordLayout.newFromBuilder(tracker.scope, t);
    // Although there's a Frame class with 10 ptrs (Frame3i10x), the most efficient choice for 11
    // pointers is Frame1i8x (since we have 2 fewer wasted int fields).  Once we include the pointer
    // to the overflow array we have 4 overflow pointers.
    assertThat(layout1.byteOverflowSize).isEqualTo(0);
    assertThat(layout1.ptrOverflowSize).isEqualTo(4);
    Frame f1 = layout1.alloc(tstate, 11);
    for (int i = 0; i < numStrings; i++) {
      assertThat(layout1.setElement(tstate, f1, i, v1.peekElement(i))).isTrue();
    }
    assertThat(f1.toString().replace('"', '\''))
        .isEqualTo("['x', 'y', 'z', 'z', 'y', 'z', 'y', 'z', 'z', 'y', 'x']");
    dropAndCheckAllReleased(x, y, z, v1, f1);
  }

  @Test
  public void recordOverflowBoth() {
    Value half = NumValue.of(0.5, tstate);
    Value quarter = NumValue.of(0.25, tstate);
    Value eighth = NumValue.of(0.125, tstate);
    int numDoubles = 6;
    Value v1 =
        tstate.compound(
            Core.FixedArrayType.withSize(numDoubles),
            addRef(half),
            addRef(quarter),
            addRef(eighth),
            addRef(half),
            addRef(quarter),
            addRef(eighth));
    Value x = new StringValue(tstate, "x");
    Value y = new StringValue(tstate, "y");
    Value z = new StringValue(tstate, "z");
    int numStrings = 11;
    Value v2 =
        tstate.compound(
            Core.FixedArrayType.withSize(numStrings),
            addRef(x),
            addRef(y),
            addRef(z),
            addRef(z),
            addRef(y),
            addRef(z),
            addRef(y),
            addRef(z),
            addRef(z),
            addRef(y),
            addRef(x));
    Value v3 = tstate.compound(Core.FixedArrayType.withSize(2), addRef(v1), addRef(v2));
    TemplateBuilder t = Template.EMPTY.add(v3);
    RecordLayout layout1 = RecordLayout.newFromBuilder(tracker.scope, t);
    // Frame11i2x needs both its pointer fields for overflow arrays, so all the pointer fields are
    // in the overflow.
    assertThat(layout1.byteOverflowSize).isEqualTo(24);
    assertThat(layout1.ptrOverflowSize).isEqualTo(7);
    Frame f1 = layout1.alloc(tstate, 11);
    assertThat(layout1.setElement(tstate, f1, 0, v1)).isTrue();
    assertThat(layout1.setElement(tstate, f1, 1, v2)).isTrue();
    assertThat(f1.toString().replace('"', '\''))
        .isEqualTo(
            "[[0.5, 0.25, 0.125, 0.5, 0.25, 0.125], ['x', 'y', 'z', 'z', 'y', 'z', 'y', 'z', 'z',"
                + " 'y', 'x']]");
    dropAndCheckAllReleased(half, quarter, eighth, x, y, z, v1, v2, v3, f1);
  }

  @Test
  public void varrayRecursive() {
    // Start with a varying-length array of ints
    Value v1 = tstate.compound(Core.FixedArrayType.withSize(2), NumValue.ONE, NumValue.ZERO);
    Value v2 = tstate.compound(Core.FixedArrayType.withSize(1), NumValue.NEGATIVE_ONE);
    VArrayLayout layout1 =
        (VArrayLayout) ((Template.RefVar.ForFrame) Template.EMPTY.add(v1).add(v2)).frameLayout();
    assertLayoutMatches(layout1, "*[]i0");
    // Create and initialize an instance
    Frame f1 = layout1.alloc(tstate, 3);
    assertThat(layout1.setElement(tstate, f1, 0, NumValue.ONE)).isTrue();
    assertThat(layout1.setElement(tstate, f1, 1, NumValue.ZERO)).isTrue();
    assertThat(layout1.setElement(tstate, f1, 2, NumValue.NEGATIVE_ONE)).isTrue();
    assertThat(f1.toString()).isEqualTo("[1, 0, -1]");
    // Use replaceElement to embed f1 in (a copy of) itself
    Frame f2 = (Frame) addRef(f1).replaceElement(tstate, 1, addRef(f1));
    assertThat(f2.toString()).isEqualTo("[1, [1, 0, -1], -1]");
    // In order to do that we had to evolve the varray layout to contain a union of an int or a
    // pointer to another varray.
    FrameLayout layout2 = f2.layout();
    assertLayoutMatches(layout2, "*[]b0⸨0:i1; 1:x0:*[]⸩");
    // ... and the nested varray has the same layout
    assertThat(f2.peekElement(1).layout().latest()).isSameInstanceAs(layout2);
    dropAndCheckAllReleased(v1, v2, f1, f2);
  }

  @Test
  public void recordToVArrayCast() {
    Value x = new StringValue(tstate, "x");
    Value xx = tstate.compound(Core.FixedArrayType.withSize(2), addRef(x), addRef(x));
    Value xxx = tstate.compound(Core.FixedArrayType.withSize(3), addRef(x), addRef(x), addRef(x));
    RecordLayout layout1 =
        RecordLayout.newFromBuilder(tracker.scope, TemplateBuilder.newBuilder(xx));
    Value f = layout1.cast(tstate, xxx);
    FrameLayout layout2 = f.layout();
    assertLayoutMatches(layout2, "*[]x0:String");
    assertThat(layout1.latest()).isSameInstanceAs(layout2);
    dropAndCheckAllReleased(x, xx, xxx, f);
  }

  @Test
  public void recordToVArrayMerge() {
    RecordLayout layout1 =
        RecordLayout.newFromBuilder(
            tracker.scope, arrayOf(Core.NONE.asTemplate, Core.STRING.asRefVar).toBuilder());
    FrameLayout layout2 = layout1.merge((Template.Compound) arrayOf(NumVar.INT32));
    assertLayoutMatches(layout2, "*[]b0⸨0:i1; 1:None; 2:x0:String⸩");
    assertThat(layout1.latest()).isSameInstanceAs(layout2);
  }

  @Test
  public void untaggedUnion() {
    // Create a simple RecordLayout and initialize an instance of it (to [7, "x"])
    Value x = new StringValue(tstate, "x");
    RecordLayout layout1 =
        RecordLayout.newFromBuilder(
            tracker.scope, arrayOf(NumVar.INT32, Core.STRING.asRefVar).toBuilder());
    Frame f1 = layout1.alloc(tstate, 2);
    assertThat(layout1.setElement(tstate, f1, 0, NumValue.of(7, Allocator.UNCOUNTED))).isTrue();
    assertThat(layout1.setElement(tstate, f1, 1, x)).isTrue();

    // Create layout for an array whose elements are an untagged union of None, String, and our
    // RecordLayout
    VArrayLayout layout2 =
        VArrayLayout.newFromBuilder(
            tracker.scope,
            new Template.Union(
                null,
                layout1.asRefVar,
                layout1.asRefVar,
                Core.NONE.asTemplate,
                Core.STRING.asRefVar));

    // Initialize an instance of that layout
    Frame f2 = layout2.alloc(tstate, 4);
    assertThat(layout2.setElement(tstate, f2, 0, x)).isTrue();
    assertThat(layout2.setElement(tstate, f2, 1, Core.NONE)).isTrue();
    assertThat(layout2.setElement(tstate, f2, 2, x)).isTrue();
    assertThat(layout2.setElement(tstate, f2, 3, f1)).isTrue();
    assertThat(f2.toString().replace('"', '\'')).isEqualTo("['x', None, 'x', [7, 'x']]");
    dropAndCheckAllReleased(x, f1, f2);
  }
}
