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

package org.retrolang.code;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.function.IntFunction;
import org.retrolang.code.Block.NonTerminal;
import org.retrolang.code.Block.Split;
import org.retrolang.code.Loop.BackRef;

/// A static-only class that implements the "duplicate simple blocks to enable skipping a test"
/// optimization.
///
/// [TestBlock#runForwardProp] already implements an optimization that checks each inlink to see if
/// it has enough information to enable skipping the test. For example, given the blocks created by
/// code like
///
/// ```
/// if someTest() {
///   true1()
///   nextTest = true
/// } else {
///   false1()
///   nextTest = false
/// }
/// if nextTest {
///   true2()
/// } else {
///   false2()
/// }
/// ```
///
/// the TestBlock optimization would transform the block graph to the equivalent of
///
/// ```
/// if someTest() {
///   true1()
///   nextTest = true
///   true2()
/// } else {
///   false1()
///   nextTest = false
///   false2()
/// }
/// ```
///
/// (and if `nextTest` were otherwise unused would drop those assignments as well).
///
/// This class extends that optimization to apply in some cases where there are intervening
/// assignments, e.g. transforming
///
/// ```
/// if someTest() {
///   true1()
///   nextTest = true
/// } else {
///   false1()
///   nextTest = false
/// }
/// someVar1 = someVar2
/// someVar3 = 42
/// if nextTest {
///   true2()
/// } else {
///   false2()
/// }
/// ```
///
/// into
///
/// ```
/// if someTest() {
///   true1()
///   nextTest = true
///   someVar1 = someVar2
///   someVar3 = 42
///   true2()
/// } else {
///   false1()
///   nextTest = false
///   someVar1 = someVar2
///   someVar3 = 42
///   false2()
/// }
/// ```
///
/// Note that this required duplicating two blocks (the assignments to `someVar1` and `someVar3`);
/// to avoid having our optimizations increase the complexity of the code we only consider applying
/// it when the blocks that would have to be duplicated are sufficiently simple (e.g. a register
/// copy or setting a register to a constant).
///
/// In addition to simple assignments, the other type of block considered simple is a [BackRef].
/// This enables us to optimize some common while-loop patterns, e.g.
///
/// ```
/// done = 0
/// loopStart:
///   if done != 0 { goto loopDone }
///   loopBody()
///   if exitCondition() {
///     done = 1
///   }
///   goBack loopStart
/// loopDone:
/// ```
///
/// can skip the test by duplicating the BackRef:
///
/// ```
/// done = 0
/// loopStart:
///   if done != 0 {
/// isDone:
///     goto loopDone
///   }
///   loopBody()
///   if exitCondition() {
///     done = 1
///     goBack isDone
///   }
///   goBack loopStart
/// loopDone:
/// ```
///
/// which after other optimizations can just be
///
/// ```
/// loopStart:
///   loopBody()
///   if not exitCondition() {
///     goBack loopStart
///   }
/// ```
///
/// There are two points where we check for whether this optimization is applicable:
///
/// - After a TestBlock is added to the block graph, we call [#checkTestInlinks] to look backward
///   through the test's inlinks.
/// - When we encounter a simple block with multiple inlinks during forward propagation, we call
///   [#lookAheadForTestBlock] to see if there is an upcoming test that one or more of those inlinks
///   could skip.
///
/// While the duplicates created by the first application of this optimization are necessary (they
/// have different outlinks -- one before the TestBlock, the other after), repeated application of
/// this and/or other optimizations may result in duplicates that have the same outlink and hence
/// are completely redundant. We address this by periodically checking for this situation and
/// calling [CodeBuilder#mergeMatching] to clean it up.
class SimpleDuplicator {
  // Statics only
  private SimpleDuplicator() {}

  /// An arbitrary limit on the number of simple blocks we are willing to duplicate in order to skip
  /// a test. This limits both the number of additional byte codes we might end up emitting and the
  /// time we might spend checking for potential duplications.
  private static final int MAX_DUPLICATED_BLOCKS = 8;

  /// Called with a newly-added TestBlock. Checks the inlinks of each of its inlink sources, and
  /// applies the duplication optimization where appropriate. (Since TestBlock already handles cases
  /// where its inlink has enough info to skip the test, here we start by looking one link further
  /// back.)
  static void checkTestInlinks(Block test) {
    test.forEachInLink(inLink -> checkInlinksForDuplicateAndSkip(inLink.origin, 0));
  }

  /// Given a simple block with multiple inlinks, checks to see if separating one of those inlinks
  /// and duplicating one or more simple blocks would allow it to skip an upcoming test. If so,
  /// modifies the block graph accordingly.
  static void lookAheadForTestBlock(NonTerminal b) {
    assert b.hasMultipleInlinks() && b.isSimple();
    // This check is only applicable if b is followed by zero or more other simple blocks and then
    // a test.
    if (couldDuplicateToTestBlock(b)) {
      b.forEachInLink(
          inLink -> {
            // Skip if this is the only inlink -- we must have removed all the others, so we can
            // just leave this one to be handled normally.
            if (inLink.hasSibling()) {
              checkForDuplicateAndSkip(inLink);
            }
          });
    }
  }

  /// If {code b} is followed by fewer than MAX\_DUPLICATED\_BLOCKS additional simple blocks and
  /// then a TestBlock, returns true; otherwise returns false.
  private static boolean couldDuplicateToTestBlock(NonTerminal b) {
    for (int i = 1; i <= MAX_DUPLICATED_BLOCKS && b.isSimple(); i++) {
      // Splits can't be simple, so we don't need to consider a possible alternate link.
      assert !(b instanceof Split);
      LinkTarget next = b.next.target();
      if (next instanceof TestBlock) {
        return true;
      } else if (!(next instanceof NonTerminal)) {
        return false;
      }
      b = (NonTerminal) next;
    }
    return false;
  }

  /// Called with a block that links through a sequence of `numSimple` simple blocks to a test.
  ///
  /// - If `b` is not simple we're done.
  /// - If `b` is simple and has multiple inlinks, check each of those inlinks to see if it can skip
  ///   the test.
  /// - If `b` is simple and has a single inlink there's no point in checking -- if we could skip
  ///   the test from its inlink we would have been able to skip it from its outlink.
  ///
  /// In either case if `b` is simple we can also try the same check on each of its inlinks, until
  /// we hit [#MAX_DUPLICATED_BLOCKS].
  private static void checkInlinksForDuplicateAndSkip(NonTerminal b, int numSimple) {
    // Walk backwards until we hit a not-simple block or hit our limit (in either case we're done)
    // or hit a simple block with multiple inlinks (in which case we've got something to check).
    // We need to check hasInLink() because walking backwards through an under-construction block
    // graph may encounter not-yet-added BackRefs.
    while (b.hasInLink() && numSimple < MAX_DUPLICATED_BLOCKS && b.isSimple()) {
      ++numSimple;
      if (b.hasMultipleInlinks()) {
        recursiveCheckInlinks(b, numSimple);
        break;
      }
      b = b.inLink.origin;
    }
  }

  /// Called with a block that is simple, has multiple inlinks, and is followed by `numSimple-1`
  /// additional simple blocks and then a TestBlock. Checks each of the inlinks to see if
  /// duplicating `b` and the following simple blocks would enable it to skip the test.
  private static void recursiveCheckInlinks(Block b, int numSimple) {
    b.forEachInLink(
        inLink -> {
          // The inLink.hasSibling() check will let us bail if we get to the last inlink and we have
          // removed all the others -- in that case there's no point in considering duplication.
          if (inLink.hasSibling() && checkForDuplicateAndSkip(inLink)) {
            // we redirected that link
            assert inLink.target() != b;
            b.cb().setForwardPropNeeded(b);
          } else {
            // We couldn't redirect it, but maybe one of *its* inlinks?
            checkInlinksForDuplicateAndSkip(inLink.origin, numSimple);
          }
        });
  }

  /// Called with one of the inlinks to a block that is simple, has multiple inlinks, and is
  /// followed by zero or more additional simple blocks and then a TestBlock. If separating the
  /// given link from its siblings (by duplicating the simple blocks) would allow us to skip the
  /// test, does so and returns true; otherwise returns false.
  @CanIgnoreReturnValue
  private static boolean checkForDuplicateAndSkip(Link link) {
    // Step through the sequence of blocks before the TestBlock and infer what we can about the
    // register values when we reach it.
    NonTerminal b = (NonTerminal) link.targetBlock();
    // The register values on entry to b
    IntFunction<ValueInfo> infos = link.info.registers();
    // If we have passed a BackRef, the value of infos at that time
    IntFunction<ValueInfo> backRefInfos = null;
    while (!(b instanceof TestBlock)) {
      if (b instanceof BackRef) {
        if (backRefInfos != null) {
          // Two BackRefs before the TestBlock?  Seems unlikely; for now we'll just skip this
          // optimization rather than deal with the extra complexity.
          return false;
        }
        backRefInfos = infos;
      }
      assert b.isSimple();
      infos = b.simpleInfos(infos);
      b = (NonTerminal) b.next.targetBlock();
    }
    Link outLink = ((TestBlock) b).chooseOutlink(infos);
    if (outLink == null) {
      return false;
    }
    // Duplicating those blocks would enable us to skip the test, so let's do it.
    duplicateToTestBlock(link, outLink.target(), backRefInfos);
    return true;
  }

  /// Duplicates the sequence of simple blocks beginning with `inLink.target` and ending at the
  /// first TestBlock. `inLink` will be reset to point to the duplicates, and the final duplicated
  /// block will link to `end` instead of the TestBlock.
  ///
  /// If one of the duplicated blocks is a BackRef, its infos will be set from `backRefInfos`.
  private static void duplicateToTestBlock(
      Link inLink, LinkTarget end, IntFunction<ValueInfo> backRefInfos) {
    // The next block to be duplicated
    NonTerminal toDup = (NonTerminal) inLink.detach();
    CodeBuilder cb = toDup.cb();
    // If we duplicate a backRef, save the duplicate here
    BackRef backRef = null;
    boolean firstDup = true;
    while (!(toDup instanceof TestBlock)) {
      // Duplicate toDup and set inLink to point to the duplicate
      NonTerminal dup = toDup.duplicate();
      if (firstDup) {
        // We only need to setForwardProp() on the first duplicate
        cb.setForwardPropNeeded(dup);
        firstDup = false;
      }
      if (dup instanceof BackRef) {
        assert backRef == null;
        backRef = (BackRef) dup;
      }
      inLink.setTarget(dup);
      // Advance toDup and inLink for the next iteration
      toDup = (NonTerminal) toDup.next.target();
      inLink = dup.next;
    }
    // The last duplicate should link to end (one of the TestBlock's outlinks), not the TestBlock.
    inLink.setTarget(end);
    ++cb.numSkipped;
    assert (backRef != null) == (backRefInfos != null);
    if (backRef != null) {
      // BackRefs need their outlink info fixed ASAP, they can't wait for propagation
      backRef.fixDuplicateInfo(cb, backRefInfos);
    }
    // Before returning, see if our last duplicate matches any of the other blocks that already
    // linked to end, and if so merge them (which may in turn lead to merging other duplicates).
    Link finalInlink = inLink;
    Link match =
        end.findInlink(link -> link != finalInlink && finalInlink.origin.isDuplicate(link.origin));
    if (match != null) {
      cb.mergeMatching(match.origin, finalInlink.origin);
    }
  }
}
