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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import org.jspecify.annotations.Nullable;
import org.retrolang.code.CodeBuilder.PrintOptions;

/**
 * A Sequencer assigns a sequential ordering to a block graph, trying to maximize the number of
 * blocks whose next block (or, for TestBlocks, their alternate block) follows them in sequence.
 * This makes for a more readable listing of blocks, and when used to order emitting minimizes the
 * number of branches.
 */
class Sequencer implements PrintOptions {

  /** The sequencing policy; see the constructor for details. */
  private final boolean forEmit;

  /** The sequence of blocks constructed by the Sequencer. */
  private final List<Block> orderedBlocks;

  /** Maps block.index() to the block's position in {@link #orderedBlocks}. */
  private final int[] blockPos;

  /** Returns the position assigned to the given block. */
  int position(Block b) {
    return blockPos[b.index()];
  }

  /** Returns the block assigned the given position. */
  Block orderedBlock(int i) {
    return orderedBlocks.get(i);
  }

  /** Returns true if the given block has already been assigned a position. */
  private boolean isDone(Block b) {
    return position(b) >= 0;
  }

  /**
   * Sequences the given set of Blocks. If {@code forEmit} is false, we prioritize keeping blocks in
   * their assigned order; if {@code forEmit} is true we prioritize falling through whenever
   * possible (to minimize the number of branches we emit).
   */
  Sequencer(List<Block> blocks, boolean forEmit) {
    this.forEmit = forEmit;
    orderedBlocks = new ArrayList<>(blocks.size());
    blockPos = new int[blocks.size()];
    Arrays.fill(blockPos, -1);
    // Blocks that have been seen as outlinks of already-ordered blocks but not yet sequenced
    PriorityQueue<Block> pending = new PriorityQueue<>();
    for (Block b : blocks) {
      // If all of a block's inlinks come from blocks that sort later than it (i.e. BackRefs),
      // add it to our pending queue -- otherwise it may never be seen.  (We can't just check
      // "instanceof BackRef", since the finalization phase removes them.)
      if (b.allInlinks(link -> link.origin.zone() == null || link.origin.compareTo(b) >= 0)) {
        pending.add(b);
      }
    }
    // Start with the first block (which is always a Block.Initial)
    for (Block b = blocks.get(0); b != null; ) {
      // Unless we've already sequenced this block, add it to orderedBlocks
      if (!isDone(b)) {
        blockPos[b.index()] = orderedBlocks.size();
        orderedBlocks.add(b);
        b = chooseNext(b, pending);
        if (b != null) {
          continue;
        }
      }
      // If b had already been sequenced, or was a Terminal, pick one off the pending queue
      b = pending.poll();
    }
    assert orderedBlocks.size() == blocks.size();
  }

  /**
   * If the target of the given link is a not-yet-sequenced block, returns it; otherwise returns
   * null.
   */
  private @Nullable Block target(Link link) {
    if (link.target() instanceof Block b && !isDone(b)) {
      return b;
    }
    return null;
  }

  /**
   * Checks the targets of each of b's outlinks; each one that is a not-yet-serialized block will
   * either be pushed on pending or returned.
   */
  private @Nullable Block chooseNext(Block b, PriorityQueue<Block> pending) {
    if (!(b instanceof Block.NonTerminal nonTerminal)) {
      return null;
    }
    Block next = target(nonTerminal.next);
    if (next != null && hasPendingInlink(next)) {
      // If next has inlinks from not-yet-sequenced blocks, we'd prefer to sequence them first.
      pending.add(next);
      next = null;
    }
    Block alternate = (b instanceof Block.Split split) ? target(split.alternate) : null;
    if (alternate == null) {
      return next;
    } else if (!(b instanceof TestBlock) || hasPendingInlink(alternate)) {
      // If this is a SetBlock.WithCatch we're not going to prioritize falling through to the
      // alternate branch.  Or (like the test of next above) if alternate has inlinks from
      // not-yet-sequenced blocks, we'd prefer to sequence them first.
      pending.add(alternate);
      return next;
    } else if (next == null) {
      // If the next branch is already sequenced (or not yet constructed) we might as well
      // fall through to the alternate.
      return alternate;
    } else if (next.compareTo(alternate) < 0) {
      // Otherwise choose one to fall through to and queue the other.
      pending.add(alternate);
      return next;
    } else {
      pending.add(next);
      return alternate;
    }
  }

  /** Returns true the given block has at least one inlink that has not yet been sequenced. */
  private boolean hasPendingInlink(Block b) {
    // We can skip the test if b only has a single inlink, since that must be from the block we just
    // sequenced.
    // If we run this (for debugging) while building the block graph we may encounter links from
    // BackRefs that have not yet been added.
    return b.hasMultipleInlinks()
        && !b.allInlinks(link -> link.origin.index() < 0 || isDone(link.origin));
  }

  @Override
  public boolean useJvmLocals() {
    return forEmit;
  }

  @Override
  public boolean isLinkToNextBlock(Link link) {
    return link.target() instanceof Block
        && link.origin.index() >= 0
        && blockPos[link.origin.index()] + 1 == blockPos[link.targetBlock().index()];
  }

  @Override
  public String blockId(Block block) {
    return String.valueOf(position(block));
  }
}
