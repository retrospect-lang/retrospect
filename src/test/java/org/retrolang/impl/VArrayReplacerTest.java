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
import org.retrolang.util.Bits;

@RunWith(JUnit4.class)
public class VArrayReplacerTest {

  /** A Constant template for the string {@code "x"}. */
  private static final Template stringX = Constant.of(StringValue.uncounted("x"));

  /** Returns a template for an array containing the given elements. */
  private static Template arrayOf(Template... elements) {
    return Template.Compound.of(Core.FixedArrayType.withSize(elements.length), elements);
  }

  /** Converts double quotes to single quotes, to make expected values easier to read. */
  private static String cleanString(Object replacer) {
    return replacer.toString().replace('"', '\'');
  }

  Scope scope = new Scope();

  /**
   * Creates src and dst VArrayLayouts from the given templates, constructs a replacer to convert
   * instances of src to instances of dst, and then verifies that replacer and its associated
   * sharedArrayElements have the expected values.
   *
   * <p>Note that since the templates are only used as builders, the indices of their NumVars and
   * RefVars are ignored.
   */
  private void runTest(
      Template src, Template dst, String expected, Integer... sharedArrayElements) {
    VArrayLayout srcLayout = VArrayLayout.newFromBuilder(scope, src.toBuilder());
    VArrayLayout dstLayout = VArrayLayout.newFromBuilder(scope, dst.toBuilder());
    VArrayReplacer replacer = VArrayReplacer.create(srcLayout, dstLayout);
    assertThat(cleanString(replacer)).isEqualTo("OptimizedReplacer" + expected);
    Bits.Builder bits = new Bits.Builder();
    for (int e : sharedArrayElements) {
      bits.set(e);
    }
    assertThat(replacer.sharedInSrc).isEqualTo(bits.build());
  }

  @Test
  public void one() {
    // Evolve an array of int to an array of union(int, None): can share the int array
    // and bulk set the new tag.
    Template src = NumVar.INT32;
    Template dst = new Template.Union(NumVar.UINT8, null, NumVar.INT32, Core.NONE.asTemplate);
    runTest(src, dst, "{shares: [copy(i0, i1)], batchOps: [set(0, b0)], perRow: EMPTY}", 0);
  }

  @Test
  public void two() {
    // Evolve an array of int to an array of union(float, None): have to bulk copy the
    // numbers since we're changing their encoding.
    Template src = NumVar.INT32;
    Template dst = new Template.Union(NumVar.UINT8, null, NumVar.FLOAT64, Core.NONE.asTemplate);
    runTest(src, dst, "{shares: [], batchOps: [set(0, b0), copy(i0, d1)], perRow: EMPTY}");
  }

  @Test
  public void three() {
    // Evolve an array of union(int, None) to an array of union(int, None, Absent): we can share
    // both of the source arrays, since the new tag value follows the old ones.
    Template src = new Template.Union(NumVar.UINT8, null, NumVar.INT32, Core.NONE.asTemplate);
    Template dst =
        new Template.Union(
            NumVar.UINT8, null, NumVar.INT32, Core.NONE.asTemplate, Core.ABSENT.asTemplate);
    runTest(src, dst, "{shares: [copy(i1, i1), copy(b0, b0)], batchOps: [], perRow: EMPTY}", 0, 1);
  }

  @Test
  public void four() {
    // Evolve an array of union(int, None) to an array of union(int, False, None): we can share
    // the int array, but we need a per-row switch to determine the new tag.
    Template src = new Template.Union(NumVar.UINT8, null, NumVar.INT32, Core.NONE.asTemplate);
    Template dst =
        new Template.Union(
            NumVar.UINT8, null, NumVar.INT32, Core.FALSE.asTemplate, Core.NONE.asTemplate);
    runTest(
        src,
        dst,
        "{shares: [copy(i1, i1)], batchOps: [], perRow: b0⸨0:set(0, b0); 1:set(2, b0)⸩}",
        1);
  }

  @Test
  public void five() {
    // Evolve an array of union(i1, [0, i1]) to an array of union(i1, [0, i1], None):
    // sharing the int array works for all union choices.
    Template src =
        new Template.Union(
            NumVar.UINT8, null, NumVar.INT32, arrayOf(Constant.of(NumValue.ZERO), NumVar.INT32));
    Template dst =
        new Template.Union(
            NumVar.UINT8,
            null,
            NumVar.INT32,
            arrayOf(Constant.of(NumValue.ZERO), NumVar.INT32),
            Core.NONE.asTemplate);
    runTest(src, dst, "{shares: [copy(i1, i1), copy(b0, b0)], batchOps: [], perRow: EMPTY}", 0, 1);
  }

  @Test
  public void six() {
    // Evolve an array of union(i1, [0, i1]) to an array of union(i1, [b2, i1], None):
    // sharing the int array still works for all union choices, and we set the byte array values
    // when needed.
    Template src =
        new Template.Union(
            NumVar.UINT8, null, NumVar.INT32, arrayOf(Constant.of(NumValue.ZERO), NumVar.INT32));
    Template dst =
        new Template.Union(
            NumVar.UINT8,
            null,
            NumVar.INT32,
            arrayOf(NumVar.UINT8, NumVar.INT32),
            Core.NONE.asTemplate);
    runTest(
        src,
        dst,
        "{shares: [copy(i1, i1), copy(b0, b0)], batchOps: [], perRow: b0⸨0:EMPTY; 1:set(0, b2)⸩}",
        0,
        1);
  }

  @Test
  public void seven() {
    // Evolve an array of union(i1, [0, i1]) to an array of union(i1, [i1, i2], None):
    // we can share src i1 with dst i2, and set dst i1 conditionally.
    Template src =
        new Template.Union(
            NumVar.UINT8, null, NumVar.INT32, arrayOf(Constant.of(NumValue.ZERO), NumVar.INT32));
    Template dst =
        new Template.Union(
            NumVar.UINT8,
            null,
            NumVar.INT32,
            arrayOf(NumVar.INT32, NumVar.INT32),
            Core.NONE.asTemplate);
    runTest(
        src,
        dst,
        "{shares: [copy(i1, i2), copy(b0, b0)], batchOps: [], "
            + "perRow: b0⸨0:copy(i1, i1); 1:set(0, i1)⸩}",
        0,
        1);
  }

  @Test
  public void eight() {
    // Evolve an array of union(i1, [b2, i1]) to an array of union(i1, [i1, d2], None):
    // we can only share the tag, and most set both i1 and d2 conditionally.
    Template src =
        new Template.Union(NumVar.UINT8, null, NumVar.INT32, arrayOf(NumVar.UINT8, NumVar.INT32));
    Template dst =
        new Template.Union(
            NumVar.UINT8,
            null,
            NumVar.INT32,
            arrayOf(NumVar.INT32, NumVar.FLOAT64),
            Core.NONE.asTemplate);
    runTest(
        src,
        dst,
        "{shares: [copy(b0, b0)], batchOps: [], "
            + "perRow: b0⸨0:copy(i1, i1); 1:copy(b2, i1), copy(i1, d2)⸩}",
        0);
  }

  @Test
  public void nine() {
    // Evolve an array with an untagged union(byteArray, string) to an untagged union that includes
    // None: sharing the element array is safe
    VArrayLayout bytes = VArrayLayout.newFromBuilder(scope, NumVar.UINT8);
    Template src = new Template.Union(null, bytes.asRefVar, bytes.asRefVar, Core.STRING.asRefVar);
    Template dst =
        new Template.Union(
            null, bytes.asRefVar, bytes.asRefVar, Core.NONE.asTemplate, Core.STRING.asRefVar);
    runTest(src, dst, "{shares: [copy(x0, x0)], batchOps: [], perRow: EMPTY}", 0);
  }

  @Test
  public void ten() {
    // Evolve an array with an untagged union(byteArray, string) to a tagged union: sharing the
    // element array is safe, but we need to set the tag conditionally
    VArrayLayout bytes = VArrayLayout.newFromBuilder(scope, NumVar.UINT8);
    Template src = new Template.Union(null, bytes.asRefVar, bytes.asRefVar, Core.STRING.asRefVar);
    Template dst =
        new Template.Union(NumVar.UINT8, null, NumVar.INT32, bytes.asRefVar, Core.STRING.asRefVar);
    runTest(
        src,
        dst,
        "{shares: [copy(x0, x0)], batchOps: [], perRow: x0⸨Array:set(1, b0); String:set(2, b0)⸩}",
        0);
  }

  @Test
  public void eleven() {
    // Evolve an array with an untagged union(byteArray, None, string) to a tagged union: sharing
    // the element array is *not* safe, because the destination layout expects x0 to be null in the
    // None branch.
    VArrayLayout bytes = VArrayLayout.newFromBuilder(scope, NumVar.UINT8);
    Template src =
        new Template.Union(
            null, bytes.asRefVar, bytes.asRefVar, Core.NONE.asTemplate, Core.STRING.asRefVar);
    Template dst =
        new Template.Union(
            NumVar.UINT8,
            null,
            NumVar.INT32,
            bytes.asRefVar,
            Core.NONE.asTemplate,
            Core.STRING.asRefVar);
    runTest(
        src,
        dst,
        "{shares: [], batchOps: [], perRow: x0⸨Array:set(1, b0), copy(x0, x0); None:set(2, b0);"
            + " String:set(3, b0), copy(x0, x0)⸩}");
  }

  @Test
  public void twelve() {
    // Evolve an array of ["x", String] pairs to an array of [String, String] pairs.
    Template src = arrayOf(stringX, Core.STRING.asRefVar);
    Template dst = arrayOf(Core.STRING.asRefVar, Core.STRING.asRefVar.withIndex(1));
    runTest(src, dst, "{shares: [copy(x0, x1)], batchOps: [set('x', x0)], perRow: EMPTY}", 0);
  }

  @Test
  public void thirteen() {
    // Evolve an array of
    //     union([[], x0:string], loopExit(x0:string))
    // (i.e. a tagged union containing either a pair or a loopExit, where the first element of the
    // pair is always an empty array), to
    //     union([x0:byteArray, x1:string], loopExit(x0:string))
    // (i.e. the same union, but with the first element of the pair now a varray of bytes).
    VArrayLayout bytes = VArrayLayout.newFromBuilder(scope, NumVar.UINT8);
    Template loopExit = Template.Compound.of(Core.LOOP_EXIT, Core.STRING.asRefVar);
    Template src =
        new Template.Union(
            NumVar.UINT8,
            null,
            arrayOf(Core.EMPTY_ARRAY.asTemplate, Core.STRING.asRefVar),
            loopExit);
    Template dst =
        new Template.Union(
            NumVar.UINT8, null, arrayOf(bytes.asRefVar, Core.STRING.asRefVar), loopExit);
    runTest(
        src,
        dst,
        "{shares: [copy(b0, b0)], batchOps: [], perRow: b0⸨0:set([], x0), copy(x0, x1); 1:copy(x0,"
            + " x0)⸩}",
        0);
  }
}
