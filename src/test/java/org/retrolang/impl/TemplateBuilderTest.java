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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.impl.Template.Constant;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.RefVar;
import org.retrolang.impl.TemplateBuilder.CompoundBase;
import org.retrolang.impl.TemplateBuilder.CompoundBuilder;
import org.retrolang.impl.TemplateBuilder.UnionBuilder;
import org.retrolang.util.SizeOf;

@RunWith(JUnit4.class)
public class TemplateBuilderTest {

  /** Returns a Compound template for an array containing the given elements. */
  private static Template arrayOf(Template... elements) {
    return Template.Compound.of(Core.FixedArrayType.withSize(elements.length), elements);
  }

  /** Returns a Constant template for an int. */
  private static Template constant(int i) {
    return Constant.of(NumValue.of(i, Allocator.UNCOUNTED));
  }

  /** Returns a Constant template for an int. */
  private static Template constant(double d) {
    return Constant.of(NumValue.of(d, Allocator.UNCOUNTED));
  }

  private static final int MEMORY_LIMIT = 3000;

  Scope scope;
  TState tstate;
  ResourceTracker tracker;

  @Before
  public void setup() {
    scope = new Scope();
    scope.evolver.enableDebugging();
    tracker = new ResourceTracker(scope, MEMORY_LIMIT, true);
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

  private static void assertTemplateMatches(Object layoutOrTemplate, String expected) {
    String s = layoutOrTemplate.toString();
    String withoutIds = s.replaceAll("@[0-9a-f]+", "");
    assertWithMessage(s).that(withoutIds).isEqualTo(expected);
  }

  /** Builds a template using VArrayLayout.VarAllocator. */
  private Template toArrayTemplate(TemplateBuilder builder) {
    return builder.build(new VArrayLayout.VarAllocator());
  }

  /** Builds a template using RecordLayout.VarAllocator. */
  private Template toRecordTemplate(TemplateBuilder builder) {
    return builder.build(RecordLayout.VarAllocator.newForRecordLayout());
  }

  /**
   * Since we don't have real Frames yet, this provides a Value with a specified layout(), and
   * delegates element access to another Value.
   */
  private static class FakeValue extends RefCounted implements Value {
    private static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + 2 * SizeOf.PTR);

    final Value v;
    final FrameLayout layout;

    FakeValue(Allocator allocator, Value v, FrameLayout layout) {
      this.v = v;
      this.layout = layout;
      allocator.recordAlloc(this, OBJ_SIZE);
    }

    @Override
    public BaseType baseType() {
      return v.baseType();
    }

    @Override
    public FrameLayout layout() {
      return layout;
    }

    @Override
    @RC.Out
    public Value element(int index) {
      return v.element(index);
    }

    @Override
    public Value peekElement(int index) {
      return v.peekElement(index);
    }

    @Override
    long visitRefs(RefVisitor visitor) {
      visitor.visit(v);
      return OBJ_SIZE;
    }
  }

  /**
   * Verifies that t1 and t2 are equivalent, i.e. that they're {@code equals()} if FrameLayouts with
   * equivalent templates are considered {@code equals()}.
   *
   * <p>(We don't want to define {@code equals()} on FrameLayouts that way, since FrameLayouts will
   * be mutable in the future.)
   */
  private static void assertEquiv(Template t1, Template t2) {
    if (!t1.equals(t2)) {
      if (t1 instanceof Template.Compound c1) {
        Template.Compound c2 = (Template.Compound) t2;
        assertThat(c1.baseType).isEqualTo(c2.baseType);
        for (int i = 0; i < c1.baseType.size(); i++) {
          assertEquiv(c1.element(i), c2.element(i));
        }
      } else if (t1 instanceof Template.Union u1) {
        Template.Union u2 = (Template.Union) t2;
        assertThat(u1.tag).isEqualTo(u2.tag);
        assertEquiv(u1.untagged, u2.untagged);
        assertThat(u1.numChoices()).isEqualTo(u2.numChoices());
        for (int i = 0; i < u1.numChoices(); i++) {
          assertEquiv((Template) u1.choiceBuilder(i), (Template) u2.choiceBuilder(i));
        }
      } else {
        RefVar v1 = (RefVar) t1;
        RefVar v2 = (RefVar) t2;
        assertThat(v1.index).isEqualTo(v2.index);
        assertThat(v1.baseType).isSameInstanceAs(v2.baseType);
        if (v1.frameLayout() instanceof RecordLayout) {
          RecordLayout layout1 = (RecordLayout) v1.frameLayout();
          RecordLayout layout2 = (RecordLayout) v2.frameLayout();
          assertEquiv(layout1.template, layout2.template);
        } else {
          VArrayLayout layout1 = (VArrayLayout) v1.frameLayout();
          VArrayLayout layout2 = (VArrayLayout) v2.frameLayout();
          assertEquiv(layout1.template, layout2.template);
        }
      }
    }
  }

  /**
   * Adds the three given values to an empty TemplateBuilder, then builds it with VarAllocators for
   * both VArrayLayout and RecordLayout, and verifies that the result templates match the given
   * strings.
   *
   * <p>Also tests
   *
   * <ul>
   *   <li>that adding the values in a different order returns equivalent results;
   *   <li>that building each of the resulting templates with the same type of VarAllocator returns
   *       the template unchanged;
   *   <li>that building with one type of VarAllocator and then building the result with the second
   *       type of VarAllocator returns an equivalent result to just building with the second type
   *       of VarAllocator; and
   *   <li>that building templates with different pairs of the given values and then merging the
   *       resulting templates returns equivalent results.
   * </ul>
   *
   * <p>Returns the resulting array template.
   */
  @CanIgnoreReturnValue
  private Template runTest(
      Value v1, Value v2, Value v3, String expectedArray, String expectedRecord) {
    TemplateBuilder b1 = Template.EMPTY.add(v1).add(v2).add(v3);
    Template array = toArrayTemplate(b1);
    assertTemplateMatches(array, expectedArray);
    Template record = toRecordTemplate(b1);
    assertTemplateMatches(record, expectedRecord);

    TemplateBuilder b2 = Template.EMPTY.add(v3).add(v2).add(v1);
    assertEquiv(toArrayTemplate(b2), array);
    assertEquiv(toRecordTemplate(b2), record);

    assertThat(toArrayTemplate(array.toBuilder())).isSameInstanceAs(array);
    assertThat(toRecordTemplate(record.toBuilder())).isSameInstanceAs(record);

    assertEquiv(toArrayTemplate(record.toBuilder()), array);
    assertEquiv(toRecordTemplate(array.toBuilder()), record);

    Template t12 = toRecordTemplate(Template.EMPTY.add(v1).add(v2));
    Template t23 = toRecordTemplate(Template.EMPTY.add(v2).add(v3));
    Template t31 = toRecordTemplate(Template.EMPTY.add(v3).add(v1));
    Template merged = toRecordTemplate(t23.toBuilder().merge(t31.toBuilder()));
    assertEquiv(record, merged);
    assertEquiv(record, toRecordTemplate(t12.toBuilder().merge(t23.toBuilder())));
    assertEquiv(record, toRecordTemplate(t12.toBuilder().merge(Template.EMPTY.add(v3))));
    assertEquiv(record, toRecordTemplate(t31.toBuilder().add(v2)));
    assertEquiv(record, toRecordTemplate(t31.toBuilder().merge(Template.EMPTY.add(v2))));
    assertEquiv(record, toRecordTemplate(Template.EMPTY.add(v2).merge(t31.toBuilder())));

    return array;
  }

  @Test
  public void numbers() {
    // Our values are
    //     [None, 3.14, 300]
    //     [300, 300, []]
    //     [None, 1, None]
    Value v1 =
        tstate.compound(
            Core.FixedArrayType.withSize(3),
            Core.NONE,
            NumValue.of(3.14, tstate),
            NumValue.of(300, tstate));
    Value v2 =
        tstate.compound(
            Core.FixedArrayType.withSize(3),
            NumValue.of(300, tstate),
            NumValue.of(300, tstate),
            Core.EMPTY_ARRAY);
    Value v3 = tstate.compound(Core.FixedArrayType.withSize(3), Core.NONE, NumValue.ONE, Core.NONE);
    runTest(
        v1,
        v2,
        v3,
        "[b0⸨0:i1; 1:None⸩, d2, b3⸨0:i4; 1:[]; 2:None⸩]",
        "[b0⸨0:i4; 1:None⸩, d12, b1⸨0:i8; 1:[]; 2:None⸩]");
    dropAndCheckAllReleased(v1, v2, v3);
  }

  @Test
  public void taggedUnions() {
    // Our values are
    //     3.14
    //     [-10, 4000]
    //     0..1000
    Value v1 = NumValue.of(3.14, tstate);
    Value v2 =
        tstate.compound(
            Core.FixedArrayType.withSize(2), NumValue.of(-10, tstate), NumValue.of(4000, tstate));
    Value v3 = tstate.compound(Core.RANGE, NumValue.ZERO, NumValue.of(1000, tstate));
    runTest(
        v1, v2, v3, "b0⸨0:d1; 1:[i2, i3]; 2:Range(0, i2)⸩", "b0⸨0:d4; 1:[i4, i8]; 2:Range(0, i4)⸩");
    dropAndCheckAllReleased(v1, v2, v3);
  }

  @Test
  public void unTaggedUnion() {
    // Our values are
    //     "wat"
    //     Absent
    //     (an empty VArray of int32)
    VArrayLayout array = VArrayLayout.newFromBuilder(tracker.scope, NumVar.INT32);
    Value v1 = new StringValue(tstate, "wat");
    Value v2 = Core.ABSENT;
    Value v3 = new FakeValue(tstate, Core.EMPTY_ARRAY, array);
    runTest(v1, v2, v3, "⸨x0:*[]; Absent; x0:String⸩", "⸨x0:*[]; Absent; x0:String⸩");
    dropAndCheckAllReleased(v1, v2, v3);
  }

  private static Value sv(String s) {
    return StringValue.uncounted(s);
  }

  @Test
  public void strings() {
    // Our values are
    //     ["a", "b", "c"]
    //     ["a", "a", None]
    //     ["a", 0]
    // Note that these strings are uncounted (as if they appeared textually in a program), so can be
    // used in constant templates.
    Value v1 = Core.FixedArrayType.withSize(3).uncountedOf(sv("a"), sv("b"), sv("c"));
    Value v2 = Core.FixedArrayType.withSize(3).uncountedOf(sv("a"), sv("a"), Core.NONE);
    Value v3 =
        tstate.compound(
            Core.FixedArrayType.withSize(2), new StringValue(tstate, "a"), NumValue.ZERO);

    // Just combine v1 and v2 first (since they have the same length).
    TemplateBuilder b = Template.EMPTY.add(v1).add(v2);
    assertTemplateMatches(toArrayTemplate(b), "[\"a\", x0:String, b0⸨0:None; 1:\"c\"⸩]");

    // The result is a reference to a variable-length array, each element of which is a tagged union
    // of 0, None, or a String
    Template arrayElement = runTest(v1, v2, v3, "x0:*[]", "x0:*[]");
    assertTemplateMatches(((RefVar) arrayElement).frameLayout(), "*[]b0⸨0:0; 1:None; 2:x0:String⸩");
    dropAndCheckAllReleased(v1, v2, v3);
  }

  @Test
  public void arrays() {
    Template t = arrayOf(NumVar.UINT8, NumVar.UINT8);
    // A reference to a record containing a pair of uint8s
    RefVar refvar = RecordLayout.newFromBuilder(tracker.scope, t.toBuilder()).asRefVar;
    assertTemplateMatches(refvar.frameLayout(), "*[b0, b1]");

    // Our values are
    //     [0, 1]
    //     [1, 4000]
    //     [None]
    Value v1 = Core.FixedArrayType.withSize(2).uncountedOf(NumValue.ZERO, NumValue.ONE);
    Value v2 =
        Core.FixedArrayType.withSize(2)
            .uncountedOf(NumValue.ONE, NumValue.of(4000, Allocator.UNCOUNTED));
    Value v3 = tstate.compound(Core.FixedArrayType.withSize(1), Core.NONE);

    // Adding [0, 1] (which fits in its layout) to refvar doesn't change it
    assertThat(refvar.toBuilder().add(v1)).isSameInstanceAs(refvar);

    // Adding [0, 4000] (which doesn't fit in its layout) to refvar changes its layout to a record
    // containing a pair of a uint8 and an int32
    RefVar b2 = (RefVar) refvar.toBuilder().add(v2);
    assertTemplateMatches(b2.frameLayout(), "*[b0, i4]");

    // Allocate a (fake) instance of this new RecordLayout, with contents [0, 1]
    Value fakeV1 = new FakeValue(tstate, v1, b2.frameLayout());

    // Starting with a constant [1, 4000] and adding the fake instance gives us a refvar with
    // the same RecordLayout
    TemplateBuilder b3 = Template.Constant.of(v2).toBuilder().add(fakeV1);
    assertThat(b3).isEqualTo(b2);

    // Combining v1, v2, and v3 results in a variable-length array, each element of which
    // is a tagged union of an int32 or None.
    Template arrayElement = runTest(v1, v2, v3, "x0:*[]", "x0:*[]");
    assertTemplateMatches(((RefVar) arrayElement).frameLayout(), "*[]b0⸨0:i1; 1:None⸩");
    dropAndCheckAllReleased(v1, v2, v3, fakeV1);
  }

  @Test
  public void recursive() {
    // Create a record that can store a pair, either element of which is NONE or a pair of NONEs.
    BaseType array2 = Core.FixedArrayType.withSize(2);
    Value v1 = tstate.compound(array2, Core.NONE, Core.NONE);
    Value v2 = tstate.compound(array2, addRef(v1), addRef(v1));
    TemplateBuilder t = Template.EMPTY.add(v1).add(v2);
    RecordLayout layout1 = RecordLayout.newFromBuilder(tracker.scope, t);
    assertTemplateMatches(layout1, "*[b0⸨0:[None, None]; 1:None⸩, b1⸨0:[None, None]; 1:None⸩]");
    // Evolve that layout so that either element can be reference to one of these records.
    Template t2 = arrayOf(layout1.asRefVar, layout1.asRefVar);
    RecordLayout layout2 = (RecordLayout) layout1.merge((Template.Compound) t2);
    assertTemplateMatches(layout2, "*[⸨x0:*; None⸩, ⸨x1:*; None⸩]");
    // Verify that the embedded refvars use the same layout.
    RefVar x0 = layout2.asRefVar;
    RefVar x1 = x0.withIndex(1);
    Template u0 = new Template.Union(null, x0, x0, Core.NONE.asTemplate);
    Template u1 = new Template.Union(null, x1, x1, Core.NONE.asTemplate);
    assertThat(layout2.template).isEqualTo(arrayOf(u0, u1));
    // Dumping active frame layouts also shows the recursion
    assertThat(scope.evolver.getSummary()).isEqualTo("$0 = *[⸨x0:$0; None⸩, ⸨x1:$0; None⸩]");
    dropAndCheckAllReleased(v1, v2);
  }

  @Test
  public void insertFrame() {
    // Start with a TemplateBuilder for an array of 5 ints
    TemplateBuilder[] ints = new TemplateBuilder[5];
    Arrays.fill(ints, NumVar.INT32);
    CompoundBase c1 = new CompoundBuilder(Core.FixedArrayType.withSize(ints.length), ints);
    // Enable a couple of them to also be None or Absent
    c1 = c1.addToElement(0, Core.NONE);
    c1 = c1.addToElement(2, Core.ABSENT);
    // Total weight is 5 (for the ints) plus 2 (for the tags)
    assertThat(c1.totalWeight()).isEqualTo(7);
    // Start another TemplateBuilder for an array of 3 strings
    TemplateBuilder[] strings = new TemplateBuilder[3];
    Arrays.fill(strings, Core.STRING.asRefVar);
    CompoundBase c2 = new CompoundBuilder(Core.FixedArrayType.withSize(strings.length), strings);
    // Again, enable a couple of them to be None or Absent
    c2 = c2.addToElement(0, Core.NONE);
    c2 = c2.addToElement(2, Core.ABSENT);
    // Total weight is 3 (for the string pointers) plus 2 (for the tags)
    assertThat(c2.totalWeight()).isEqualTo(5);
    // Wrap the second array in a LoopExit (so that it has a different sortOrder, and can be an
    // alternative to the first in a union).
    CompoundBase c2a = new CompoundBuilder(Core.LOOP_EXIT, c2);
    // That doesn't change its total weight
    assertThat(c2a.totalWeight()).isEqualTo(5);
    // Create a union with those two choices
    UnionBuilder u = new UnionBuilder(c1, c2a);
    // Its weight is 7 (the numbers in the first choice) plus 3 (the string pointers in the second
    // choices) plus 1 (the tag).
    assertThat(u.totalWeight()).isEqualTo(11);
    // ... and embed that in an array with a couple of doubles
    TemplateBuilder c3 =
        new CompoundBuilder(Core.FixedArrayType.withSize(3), NumVar.FLOAT64, NumVar.FLOAT64, u);
    // Total weight is 2 (the doubles) plus 11 (the union)
    assertThat(c3.totalWeight()).isEqualTo(13);
    assertThat(c3.toString())
        .isEqualTo(
            "[d0, d0, ⸨[⸨i0; None⸩, i0, ⸨i0; Absent⸩, i0, i0]; "
                + "LoopExit([⸨None; x0:String⸩, x0:String, ⸨Absent; x0:String⸩])⸩]");
    // Now look for a subexpression with totalWeight >= 5 and create a new FrameLayout for it.
    c3 = c3.insertFrameLayout(5);
    // It chose the first array (c1), and because it was relatively long it used a varray
    assertTemplateMatches(
        c3, "[d0, d0, ⸨x0:*[]; LoopExit([⸨None; x0:String⸩, x0:String, ⸨Absent; x0:String⸩])⸩]");
    // The varray's template is a tagged union of int or one of the two singletons
    assertTemplateMatches(
        ((RefVar) u.choiceBuilder(0)).frameLayout(), "*[]b0⸨0:i1; 1:None; 2:Absent⸩");
    // Replacing that subexpression with a pointer reduces c3's size to 8
    assertThat(c3.totalWeight()).isEqualTo(8);
    // We should be able to do it again
    c3 = c3.insertFrameLayout(5);
    // This time we replaced the LoopExit element...
    assertTemplateMatches(c3, "[d0, d0, ⸨x0:*[]; LoopExit(x0:*[])⸩]");
    // ... with a record (because it was a relatively short array) containing 3 pointers (two of
    // which are untagged unions)
    assertTemplateMatches(
        ((RefVar) c2a.elementBuilder(0)).frameLayout(), "*[]⸨None; Absent; x0:String⸩");
    // Now c3's weight is down to 4 (2 doubles and pointers)
    assertThat(c3.totalWeight()).isEqualTo(4);
    // Getting a summary of all active frame layouts returns the same two layouts
    assertThat(scope.evolver.getSummary())
        .isEqualTo(
            """
            $0 = *[]b0⸨0:i1; 1:None; 2:Absent⸩
            $1 = *[]⸨None; Absent; x0:String⸩\
            """);
  }

  boolean isSubset(Template t1, Template t2) {
    return t1.toBuilder().isSubsetOf(t2.toBuilder());
  }

  @Test
  public void isSubset() {
    assertThat(isSubset(arrayOf(constant(3), NumVar.UINT8), arrayOf(NumVar.UINT8, NumVar.INT32)))
        .isTrue();
    assertThat(isSubset(arrayOf(constant(3.1), NumVar.UINT8), arrayOf(NumVar.INT32, NumVar.INT32)))
        .isFalse();
    assertThat(isSubset(arrayOf(constant(3), NumVar.INT32), arrayOf(NumVar.UINT8, NumVar.UINT8)))
        .isFalse();

    // A non-union may be a subset of union
    assertThat(
            isSubset(
                NumVar.INT32,
                new Template.Union(
                    NumVar.UINT8,
                    null,
                    NumVar.FLOAT64,
                    arrayOf(NumVar.UINT8),
                    Core.FALSE.asTemplate)))
        .isTrue();
    assertThat(
            isSubset(
                Core.TRUE.asTemplate,
                new Template.Union(
                    NumVar.UINT8,
                    null,
                    NumVar.INT32,
                    arrayOf(NumVar.INT32),
                    Core.FALSE.asTemplate)))
        .isFalse();

    // A union may be a subset of union
    assertThat(
            isSubset(
                new Template.Union(
                    NumVar.UINT8,
                    null,
                    NumVar.FLOAT64,
                    arrayOf(NumVar.UINT8),
                    Core.FALSE.asTemplate),
                new Template.Union(
                    NumVar.UINT8,
                    null,
                    NumVar.FLOAT64,
                    arrayOf(NumVar.UINT8),
                    Core.FALSE.asTemplate,
                    Core.TRUE.asTemplate)))
        .isTrue();
    assertThat(
            isSubset(
                new Template.Union(
                    NumVar.UINT8,
                    null,
                    NumVar.FLOAT64,
                    arrayOf(NumVar.UINT8),
                    Core.FALSE.asTemplate,
                    Core.TRUE.asTemplate),
                new Template.Union(
                    NumVar.UINT8,
                    null,
                    NumVar.FLOAT64,
                    arrayOf(NumVar.UINT8),
                    Core.FALSE.asTemplate)))
        .isFalse();

    // A compound with appropriate elements can be a subset of a RecordLayout refvar
    RecordLayout recordLayout =
        RecordLayout.newFromBuilder(tracker.scope, arrayOf(NumVar.UINT8, NumVar.INT32).toBuilder());
    assertThat(isSubset(arrayOf(constant(3), NumVar.UINT8), recordLayout.asRefVar)).isTrue();
    assertThat(isSubset(arrayOf(constant(300), NumVar.UINT8), recordLayout.asRefVar)).isFalse();

    // A RecordLayout refvar is never a subset of a compound...
    assertThat(isSubset(recordLayout.asRefVar, arrayOf(NumVar.INT32, NumVar.INT32))).isFalse();

    // ... or another RecordLayout (even one with the same template)
    RecordLayout recordLayout2 =
        RecordLayout.newFromBuilder(tracker.scope, arrayOf(NumVar.UINT8, NumVar.INT32).toBuilder());
    assertThat(isSubset(recordLayout.asRefVar, recordLayout2.asRefVar)).isFalse();
    assertThat(isSubset(recordLayout.asRefVar, recordLayout.asRefVar.withIndex(1))).isTrue();
  }

  boolean isSubsetAfterUpgrade(Template t1, Template t2) {
    return t1.toBuilder().isSubsetOf(t2.toBuilder(), TemplateBuilder.TestOption.UPGRADE_SUB_INTS);
  }

  @Test
  public void isSubsetAfterUpgrade() {
    // An upgraded uint8 can store any int32
    assertThat(
            isSubsetAfterUpgrade(
                arrayOf(constant(-300), NumVar.INT32), arrayOf(NumVar.UINT8, NumVar.UINT8)))
        .isTrue();

    // ... but not a float64
    assertThat(isSubsetAfterUpgrade(constant(3.1), NumVar.UINT8)).isFalse();

    // UPGRADE_SUB_INTS doesn't apply to the template inside a RefVar
    RecordLayout recordLayout =
        RecordLayout.newFromBuilder(tracker.scope, arrayOf(NumVar.UINT8).toBuilder());
    assertThat(isSubsetAfterUpgrade(constant(-300), recordLayout.asRefVar)).isFalse();
  }
}
