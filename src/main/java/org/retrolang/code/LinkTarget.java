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

import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/// The target of a Link. LinkTarget has two subclasses: Block and FutureBlock.
public abstract class LinkTarget {
  /// Null if this is not the target of any Links. Otherwise `inLink` is one of the Links with
  /// `this` as its target, and any other such links are reachable as its siblings.
  Link inLink;

  /// Returns one of this target's inlinks, or null if it has none.
  public Link firstInlink() {
    return inLink;
  }

  /// Returns true if this target has at least one inlink.
  public boolean hasInLink() {
    return inLink != null;
  }

  /// Returns true if this target has more than one inlink.
  public boolean hasMultipleInlinks() {
    return inLink != null && inLink.hasSibling();
  }

  /// Changes all inlinks to this target to point to the given target; on return [#hasInLink] will
  /// be false.
  void moveAllInLinks(LinkTarget other) {
    assert other != null;
    if (other != this && inLink != null) {
      inLink.moveWithAllSiblings(other);
      assert inLink == null;
    }
  }

  /// Returns true if the given predicate returns true for at least one of this target's inlinks.
  public boolean anyInlink(Predicate<Link> test) {
    return findInlink(test) != null;
  }

  /// Returns true if the given predicate returns true for all of this target's inlinks.
  public boolean allInlinks(Predicate<Link> test) {
    return !anyInlink(test.negate());
  }

  /// If the given predicate returns true for one of this target's inlinks, returns it; otherwise
  /// returns null.
  public @Nullable Link findInlink(Predicate<Link> test) {
    Link x = inLink;
    if (x != null) {
      do {
        if (test.test(x)) {
          return x;
        }
        x = x.sibling;
      } while (x != inLink);
    }
    return null;
  }

  /// Calls `consumer.accept()` with each of this target's inlinks.
  ///
  /// The consumer may [Link#detach] (and otherwise modify) the given link, but must not make any
  /// other changes to this target's links.
  public void forEachInLink(Consumer<Link> consumer) {
    for (Link link = inLink; link != null; ) {
      // Find the next link before we potentially detach this one
      Link next = link.sibling;
      // Do this check before calling accept(), since detaching can change inLink
      if (next == inLink) {
        next = null;
      }
      consumer.accept(link);
      link = next;
    }
  }

  /// Sets `result` to the union of the LinkInfos from each of this target's inLinks.
  void unionInlinkInfo(LinkInfo result, LinkInfo.Unioner unioner, Loop containingLoop) {
    result.setFrom(inLink.info);
    unioner.start(result, containingLoop);
    for (Link x = inLink.sibling; x != inLink; x = x.sibling) {
      unioner.add(x.info);
    }
    unioner.finish();
  }

  /// Gets the info for the given register from each of this target's inLinks and unions the
  /// results; returns null if any of those calls returns null. If `resolveRegisters` is true, will
  /// not return a Register as an info.
  @Nullable ValueInfo getInLinkInfo(
      int index, ValueInfo.BinaryOps binaryOps, boolean resolveRegisters) {
    ValueInfo result = inLink.info.register(index);
    if (resolveRegisters) {
      result = inLink.info.resolve(result);
    }
    // This isn't as clever as LinkInfo.unionRegisters(); should it be?
    for (Link inLink2 = inLink.sibling;
        inLink2 != inLink && result != null;
        inLink2 = inLink2.sibling) {
      ValueInfo result2 = inLink2.info.register(index);
      if (result2 == result) {
        continue;
      } else if (result2 == null) {
        return null;
      } else if (result instanceof Register) {
        // We can't call union() with a register, so we need to resolve them after all.
        // It's easiest to just start over with resolveRegisters true
        assert !resolveRegisters;
        return getInLinkInfo(index, binaryOps, true);
      }
      result = binaryOps.union(result, inLink2.info.resolve(result2));
    }
    return result;
  }
}
