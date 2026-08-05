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

import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.jspecify.annotations.Nullable;
import org.retrolang.code.CodeBuilder.PrintOptions;
import org.retrolang.util.Bits;
import org.retrolang.util.StringUtil;

/// A Link is a directed connection between two Blocks (the Link's [#origin], which is fixed, and
/// its [#target], which can be changed). During construction of the block graph a Link's target may
/// be set to a FutureBlock, which serves as a placeholder until the target block is available.
///
/// Each block has a small, fixed number of outlinks (0, 1, or 2), but may have an unbounded number
/// of inlinks. The Block (or FutureBlock) has an [LinkTarget#inLink] field pointing to one of the
/// inlinks; in order to find the others, all Links with the same target are connected in a circular
/// list via their [#sibling] field.
///
/// Each Link has an [#info] field used to save what that is known about the register state when the
/// executing code crosses that link (i.e. when it has completed the link's source block, before
/// beginning its target block).
public class Link {
  /// Links are created and owned by their origin blocks.
  public final Block.NonTerminal origin;

  /// The target will eventually be a Block, but may be null or a FutureBlock while building the
  /// block graph.
  private LinkTarget target;

  /// All Links with the same non-null target are connected in a circular list by their `sibling`
  /// fields. Links with a null target are in a list by themselves (i.e. `this.sibling==this`).
  Link sibling;

  /// The state of execution when this Link is reached.
  public final LinkInfo info = new LinkInfo();

  /// Creates a new Link with the given origin; target is initially null.
  public Link(Block.NonTerminal origin) {
    this.origin = origin;
    this.sibling = this;
  }

  /// The target of this Link.
  public LinkTarget target() {
    return target;
  }

  /// The target of this Link, which must not be a FutureBlock.
  public Block targetBlock() {
    return (Block) target;
  }

  /// True if the target of this Link is non-null, and there is at least one other Link with the
  /// same target.
  public boolean hasSibling() {
    return sibling != this;
  }

  /// To loop through the inlinks of a LinkTarget, use
  ///
  /// ```
  /// for (Link inLink = target.firstInlink();
  ///      inLink != null;
  ///      inLink = inLink.nextInlink()) { ... }
  /// ```
  ///
  /// ... but note that this will not work if you want to be able to detach inLink in the loop body;
  /// in that case you should use [LinkTarget#forEachInLink].
  public @Nullable Link nextInlink() {
    return (sibling == target.inLink) ? null : sibling;
  }

  /// Sets the target of this Link; should only be called when target is currently null.
  public void setTarget(LinkTarget target) {
    Preconditions.checkArgument(this.target == null && target != null);
    assert linkValid(origin, target);
    this.target = target;
    addToInlinks(target);
    CodeBuilder.updateLoopTransitions(this, true);
  }

  /// Returns true if a link is allowed from `origin` to `target`. [Loop.BackRef]s are the only
  /// blocks that can link to a block before them.
  static boolean linkValid(Block origin, LinkTarget target) {
    return origin instanceof Loop.BackRef
        || target instanceof FutureBlock
        || origin.compareTo((Block) target) < 0;
  }

  /// Changes the target of this Link and any siblings; the previous target will no longer have any
  /// inlinks.
  void moveWithAllSiblings(LinkTarget target) {
    assert target != null && target != this.target;
    LinkTarget prev = this.target;
    prev.forEachInLink(
        x -> {
          CodeBuilder.updateLoopTransitions(x, false);
          x.target = target;
          CodeBuilder.updateLoopTransitions(x, true);
        });
    prev.inLink = null;
    addToInlinks(target);
  }

  /// Adds this link, and all of its siblings, to the inlinks of `target`.
  private void addToInlinks(LinkTarget target) {
    // If the new target previously had no inlinks it should link back to us; otherwise we (and
    // our siblings) need to be spliced into its existing inlinks.
    Link existing = target.inLink;
    if (existing == null) {
      target.inLink = this;
    } else {
      Link t = sibling;
      this.sibling = existing.sibling;
      existing.sibling = t;
    }
  }

  /// Resets the target of this Link to null and returns the previous target. Links that were
  /// siblings of this Link are unaffected (but are no longer siblings).
  @CanIgnoreReturnValue
  public @Nullable LinkTarget detach() {
    if (target == null) {
      assert !hasSibling();
      return null;
    }
    CodeBuilder.updateLoopTransitions(this, false);
    LinkTarget result = target;
    target = null;
    // Make sure that our old target no longer points to us.
    if (result.inLink == this) {
      result.inLink = hasSibling() ? sibling : null;
    }
    // Remove us from the circular list with our siblings, which requires fully traversing it to
    // find the one that points to us.
    Link prev = this;
    while (prev.sibling != this) {
      prev = prev.sibling;
    }
    prev.sibling = sibling;
    sibling = this;
    return result;
  }

  /// Sets the bits in `builder` that correspond to registers for which [#isLive] would return true.
  public void getLive(Bits.Builder builder) {
    targetBlock().getLive(builder, origin.zone());
  }

  /// Returns true if the given register is live at this link's target, as seen from its origin; see
  /// [Block#isLive] for what that means.
  public boolean isLive(Register r) {
    return targetBlock().isLive(r.index, origin.zone());
  }

  public String toString(PrintOptions options) {
    if (target == null) {
      return "→ null";
    } else if (target instanceof Block) {
      return "→ " + options.blockId((Block) target);
    } else {
      return "→ @" + StringUtil.id(target);
    }
  }

  @Override
  public String toString() {
    return toString(PrintOptions.DEFAULT);
  }
}
