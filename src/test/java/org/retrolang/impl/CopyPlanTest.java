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
import org.retrolang.impl.Template.Constant;
import org.retrolang.impl.Template.NumVar;

@RunWith(JUnit4.class)
public class CopyPlanTest {

  /** A Constant template for the string {@code "x"}. */
  private static final Template stringX = Constant.of(StringValue.uncounted("x"));

  /** Returns a template for an array containing the given elements. */
  private static Template arrayOf(Template... elements) {
    return Template.Compound.of(Core.FixedArrayType.withSize(elements.length), elements);
  }

  /** Converts double quotes to single quotes, to make expected values easier to read. */
  private static String cleanString(CopyPlan plan) {
    return plan.toString().replace('"', '\'');
  }

  @Test
  public void basics() {
    // The simplest steps: SET_NUM, COPY_NUM, SET_REF, and COPY_REF
    Template src = arrayOf(Constant.of(NumValue.ZERO), NumVar.UINT8, stringX, Core.STRING.asRefVar);
    Template dst =
        arrayOf(
            NumVar.UINT8,
            NumVar.FLOAT64.withIndex(1),
            Core.STRING.asRefVar,
            Core.STRING.asRefVar.withIndex(1));
    assertThat(cleanString(CopyPlan.create(src, dst)))
        .isEqualTo("set(0, b0), copy(b0, d1), set('x', x0), copy(x0, x1)");
  }

  @Test
  public void taggedUnionSrc() {
    // Copy a tagged union src to various non-union dsts
    Template union =
        new Template.Union(
            NumVar.UINT8,
            null,
            NumVar.INT32.withIndex(1),
            arrayOf(NumVar.INT32.withIndex(1), NumVar.INT32.withIndex(2)),
            Core.FALSE.asTemplate,
            Core.TRUE.asTemplate,
            Core.STRING.asRefVar);
    // dst is constant 1: verify the tag and the int var used for numbers
    assertThat(cleanString(CopyPlan.create(union, Constant.of(NumValue.ONE))))
        .isEqualTo("verify(b0, 0), verify(i1, 1)");
    // dst is [b0, 0]: verify the tag, copy the first element to b0, and verify that the
    // second element was 0
    assertThat(
            cleanString(CopyPlan.create(union, arrayOf(NumVar.UINT8, Constant.of(NumValue.ZERO)))))
        .isEqualTo("verify(b0, 1), copy(i1, b0), verify(i2, 0)");
    // dst is None: always fails (the src can't have that value)
    assertThat(cleanString(CopyPlan.create(union, Core.NONE.asTemplate))).isEqualTo("FAIL");
    // dst is True: just verify the tag
    assertThat(cleanString(CopyPlan.create(union, Core.TRUE.asTemplate)))
        .isEqualTo("verify(b0, 3)");
    // dst is constant "x": verify the tag and the RefVar used for strings
    assertThat(cleanString(CopyPlan.create(union, stringX)))
        .isEqualTo("verify(b0, 4), verify(x0, 'x')");
  }

  @Test
  public void taggedUnionDst() {
    // Copy various non-union srcs to a tagged union dst
    Template union =
        new Template.Union(
            NumVar.UINT8,
            null,
            NumVar.INT32.withIndex(1),
            arrayOf(NumVar.INT32.withIndex(1), NumVar.INT32.withIndex(2)),
            Core.FALSE.asTemplate,
            Core.TRUE.asTemplate,
            Core.STRING.asRefVar);
    // src is constant 1: set the tag and the int var used for numbers
    assertThat(cleanString(CopyPlan.create(Constant.of(NumValue.ONE), union)))
        .isEqualTo("set(0, b0), set(1, i1)");
    // src is [b0, 0]: set the tag, copy b0 to the first element, and set the
    // second element to 0
    assertThat(
            cleanString(CopyPlan.create(arrayOf(NumVar.UINT8, Constant.of(NumValue.ZERO)), union)))
        .isEqualTo("set(1, b0), copy(b0, i1), set(0, i2)");
    // src is None: always fails (the dst can't have that value)
    assertThat(cleanString(CopyPlan.create(Core.NONE.asTemplate, union))).isEqualTo("FAIL");
    // src is True: just set the tag
    assertThat(cleanString(CopyPlan.create(Core.TRUE.asTemplate, union))).isEqualTo("set(3, b0)");
    // src is constant "x": set the tag and the RefVar used for strings
    assertThat(cleanString(CopyPlan.create(stringX, union))).isEqualTo("set(4, b0), set('x', x0)");
  }

  @Test
  public void untaggedUnionSrc() {
    // Copy an untagged union src to various non-union dsts
    // Start by creating a layout for a varray of bytes, to use as one of the choices
    Scope scope = new Scope();
    FrameLayout bytes = VArrayLayout.newFromBuilder(scope, NumVar.UINT8);
    Template union =
        new Template.Union(
            null,
            bytes.asRefVar,
            bytes.asRefVar,
            Core.FALSE.asTemplate,
            Core.TRUE.asTemplate,
            Core.STRING.asRefVar);
    // dst is constant 1: always fails (the src can't be a number)
    assertThat(cleanString(CopyPlan.create(union, Constant.of(NumValue.ONE)))).isEqualTo("FAIL");
    // dst is [b0, 0]: verify that src has the frame layout, and use FRAME_TO_COMPOUND to do the
    // actual decoding and checking
    assertThat(
            cleanString(CopyPlan.create(union, arrayOf(NumVar.UINT8, Constant.of(NumValue.ZERO)))))
        .isEqualTo("verifyType(x0, VArray), fromFrame(x0, [b0, 0])");
    // dst is True: verify that src is True
    assertThat(cleanString(CopyPlan.create(union, Core.TRUE.asTemplate)))
        .isEqualTo("verifyType(x0, True)");
    // dst is constant "x": verify that src has the required type, and then check its value
    assertThat(cleanString(CopyPlan.create(union, stringX)))
        .isEqualTo("verifyType(x0, String), verify(x0, 'x')");
  }

  @Test
  public void untaggedUnionDst() {
    // Copy various non-union srcs to an untagged union dst
    Scope scope = new Scope();
    FrameLayout bytes = VArrayLayout.newFromBuilder(scope, NumVar.UINT8);
    Template union =
        new Template.Union(
            null,
            bytes.asRefVar,
            bytes.asRefVar,
            Core.FALSE.asTemplate,
            Core.TRUE.asTemplate,
            Core.STRING.asRefVar);
    // src is constant 1: always fails (the dst can't represent a number)
    assertThat(cleanString(CopyPlan.create(Constant.of(NumValue.ONE), union))).isEqualTo("FAIL");
    // src is [b0, 0]: use COMPOUND_TO_FRAME to allocate and initialize a new frame
    assertThat(
            cleanString(CopyPlan.create(arrayOf(NumVar.UINT8, Constant.of(NumValue.ZERO)), union)))
        .isEqualTo("toFrame([b0, 0], x0)");
    // src is True: set x0 to a constant value
    assertThat(cleanString(CopyPlan.create(Core.TRUE.asTemplate, union)))
        .isEqualTo("set(True, x0)");
    // src is constant "x": set x0 to a constant value
    assertThat(cleanString(CopyPlan.create(stringX, union))).isEqualTo("set('x', x0)");
  }

  @Test
  public void taggedToSubset() {
    // Copy a tagged union src to tagged union dst that supports only a subset of src's choices
    Template src =
        new Template.Union(
            NumVar.UINT8,
            null,
            NumVar.INT32.withIndex(1),
            arrayOf(NumVar.INT32.withIndex(1), NumVar.INT32.withIndex(2)),
            Core.NONE.asTemplate,
            Core.ABSENT.asTemplate,
            Core.STRING.asRefVar);
    Template dst1 =
        new Template.Union(
            NumVar.UINT8,
            null,
            arrayOf(NumVar.UINT8.withIndex(1), NumVar.INT32.withIndex(2)),
            Core.NONE.asTemplate,
            Core.STRING.asRefVar);
    // A switch on the source's tag, that fails on some branches and sets dst's tag appropriately
    // on the others
    assertThat(cleanString(CopyPlan.create(src, dst1)))
        .isEqualTo(
            "b0⸨0:FAIL; 1:set(0, b0), copy(i1, b1), copy(i2, i2); 2:set(1, b0); 3:FAIL;"
                + " 4:set(2, b0), copy(x0, x0)⸩");
    // If the matching branches all have matching tags we can copy the tag
    Template dst2 =
        new Template.Union(
            NumVar.UINT8,
            null,
            NumVar.UINT8.withIndex(1),
            Core.FALSE.asTemplate,
            Core.TRUE.asTemplate,
            Core.ABSENT.asTemplate,
            Core.STRING.asRefVar);
    assertThat(cleanString(CopyPlan.create(src, dst2)))
        .isEqualTo("copy(b0, b0), b0⸨0:copy(i1, b1); 1:FAIL; 2:FAIL; 3:EMPTY; 4:copy(x0, x0)⸩");
    // If only one of src's branches can be represented by dst, just verify the tag's value
    Template dst3 =
        new Template.Union(NumVar.UINT8, null, Core.NONE.asTemplate, Core.UNDEF.asTemplate);
    assertThat(cleanString(CopyPlan.create(src, dst3))).isEqualTo("verify(b0, 2), set(0, b0)");
    // If none of src's branches can be represented by dst just fail
    Template dst4 =
        new Template.Union(
            NumVar.UINT8,
            null,
            Constant.of(Core.RANGE.uncountedOf(NumValue.ZERO, NumValue.ONE)),
            Core.UNDEF.asTemplate);
    assertThat(cleanString(CopyPlan.create(src, dst4))).isEqualTo("FAIL");
  }

  @Test
  public void boolToBool() {
    // Copying a simple union of enums to the same union can just copy the tag, no switch needed
    Template bool =
        new Template.Union(NumVar.UINT8, null, Core.FALSE.asTemplate, Core.TRUE.asTemplate);
    assertThat(cleanString(CopyPlan.create(bool, bool))).isEqualTo("copy(b0, b0)");
  }

  @Test
  public void boolToUntagged() {
    // Copying a simple union of enums to an untagged union that also includes a string
    Template bool =
        new Template.Union(NumVar.UINT8, null, Core.FALSE.asTemplate, Core.TRUE.asTemplate);
    Template dst =
        new Template.Union(
            null,
            Core.STRING.asRefVar,
            Core.FALSE.asTemplate,
            Core.TRUE.asTemplate,
            Core.STRING.asRefVar);
    assertThat(cleanString(CopyPlan.create(bool, dst)))
        .isEqualTo("b0⸨0:set(False, x0); 1:set(True, x0)⸩");
  }

  @Test
  public void untaggedToTagged() {
    // Copy an untagged union src to a tagged union dst switches on the type of a RefVar
    Scope scope = new Scope();
    FrameLayout bytes = VArrayLayout.newFromBuilder(scope, NumVar.UINT8);
    Template src =
        new Template.Union(
            null,
            bytes.asRefVar,
            bytes.asRefVar,
            Core.FALSE.asTemplate,
            Core.TRUE.asTemplate,
            Core.STRING.asRefVar);
    Template dst1 =
        new Template.Union(
            NumVar.UINT8,
            null,
            arrayOf(NumVar.UINT8.withIndex(1), NumVar.INT32.withIndex(2)),
            Core.FALSE.asTemplate,
            Core.STRING.asRefVar);
    assertThat(cleanString(CopyPlan.create(src, dst1)))
        .isEqualTo(
            "x0⸨Array:set(0, b0), fromFrame(x0, [b1, i2]); False:set(1, b0); True:FAIL;"
                + " String:set(2, b0), copy(x0, x0)⸩");
    // If only one of src's branches can be represented by dst, just verify the RefVar's type
    Template dst2 =
        new Template.Union(NumVar.UINT8, null, Core.FALSE.asTemplate, Core.NONE.asTemplate);
    assertThat(cleanString(CopyPlan.create(src, dst2)))
        .isEqualTo("verifyType(x0, False), set(0, b0)");
    // If none of src's branches can be represented by dst just fail
    Template dst3 =
        new Template.Union(NumVar.UINT8, null, Core.NONE.asTemplate, Core.ABSENT.asTemplate);
    assertThat(cleanString(CopyPlan.create(src, dst3))).isEqualTo("FAIL");
  }

  @Test
  public void untaggedToUntagged() {
    // Copying an untagged union src to an untagged union dst
    Scope scope = new Scope();
    FrameLayout bytes = VArrayLayout.newFromBuilder(scope, NumVar.UINT8);
    Template src =
        new Template.Union(
            null, bytes.asRefVar, bytes.asRefVar, Core.TRUE.asTemplate, Core.STRING.asRefVar);
    // If dst is not a superset, switch on the src RefVar type and copy as appropriate
    Template dst1 =
        new Template.Union(
            null, bytes.asRefVar, bytes.asRefVar, Core.FALSE.asTemplate, Core.STRING.asRefVar);
    assertThat(cleanString(CopyPlan.create(src, dst1)))
        .isEqualTo("x0⸨Array:copy(x0, x0); True:FAIL; String:copy(x0, x0)⸩");
    // If dst is a superset we don't need to switch
    Template dst2 =
        new Template.Union(
            null,
            bytes.asRefVar,
            bytes.asRefVar,
            Core.FALSE.asTemplate,
            Core.TRUE.asTemplate,
            Core.STRING.asRefVar);
    assertThat(cleanString(CopyPlan.create(src, dst2))).isEqualTo("copy(x0, x0)");
  }
}
