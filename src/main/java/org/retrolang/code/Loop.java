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
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.retrolang.code.CodeBuilder.Phase;
import org.retrolang.util.Bits;

/**
 * A Loop provides a controlled way to override the usual limitation that blocks can only branch
 * forward.
 */
public final class Loop {
  /**
   * A Loop is not itself a Block, but it has some associated processing to be done during forward
   * and reverse propagation.
   */
  final Processor processor = new Processor();

  /** The registers whose values may be updated from one loop iteration to the next. */
  private Bits registers;

  /**
   * The union of this Loop's initial registers and those of all its parents.
   *
   * <p>Note that for simplicity we don't currently update these when a loop's registers are reduced
   * by optimization; if there were some benefit that could be done with a modest additional hassle.
   */
  final Bits allRegisters;

  /**
   * Each Loop contains one or more BackRef blocks; these are the only blocks that are allowed to
   * violate the forward-linking constraint.
   */
  final List<BackRef> backRefs = new ArrayList<>();

  /**
   * All Links whose origin is not in this loop, and whose target is.
   *
   * <p>Initially these will be the links that pointed to {@link CodeBuilder#nextBlock} when the
   * loop was created, but subsequent optimizations may add or remove entries.
   */
  private final List<Link> entries = new ArrayList<>();

  /** All Links whose origin is in this loop, and whose target is not. */
  private final List<Link> exits = new ArrayList<>();

  /**
   * This Loop's position in {@code cb().loops}. Loops are never removed from the list or reordered,
   * so the index can be final.
   */
  final int index;

  /** The loop that this loop is immediately nested in, or null if this is a top-level loop. */
  private Loop nestedIn;

  /**
   * All zones directly or indirectly contained in this loop are sequential in {@code cb().zones}.
   * The first is {@code processor.zone}; the last is {@code lastZone}, or null if this loop has not
   * yet been completed.
   */
  private Zone lastZone;

  /** The union of LinkInfos from this Loop's entries, with the updated registers set to ANY. */
  private LinkInfo entryInfo = new LinkInfo();

  /**
   * The live sets of blocks contained in a loop are incomplete; they only include registers that
   * are live without traversing a BackRef. (In particular, a BackRef's live set contains only the
   * loop's updated registers, and not any other registers set before the loop was started that may
   * be referenced in the loop body or after the loop exit).
   *
   * <p>{@code extraLive} is the set of additional registers that are live at the start of the loop
   * (i.e. at the target of the BackRef(s)); these should be added to the live set of any block in
   * the loop.
   */
  private Bits extraLive;

  /**
   * Creates a new Loop, and a new Zone for blocks contained in it. The loop's BackRef will link to
   * the next block added to {@code cb}.
   */
  Loop(CodeBuilder cb, int index, Bits registers) {
    this.index = index;
    this.registers = registers;
    this.nestedIn = cb.currentLoop();
    this.allRegisters =
        (nestedIn == null) ? registers : Bits.Op.UNION.apply(registers, nestedIn.allRegisters);
    // Create the first zone in the loop's body; the loop's processor will be ordered before any
    // blocks contained in that zone.
    processor.setZone(cb.newZone(this));
    processor.setOrder(Integer.MIN_VALUE);
    BackRef backRef = new BackRef();
    backRefs.add(backRef);

    // This is essentially the same analysis that Processor.runForwardProp() does over our
    // Loop's entries, but we need to do it before the Loop has been set up.
    LinkInfo backRefInfo = backRef.next.info;

    // Start with what is known on loop entry
    cb.nextBlock.unionInlinkInfo(backRefInfo, cb.unioner, nestedIn);
    // Make sure that no registers are saved as a copy of a register we'll be updating
    backRef.next.info.ensureNoDependencies(registers, cb);
    // The updated registers' values will be changed by the loop body, so for now
    // we know nothing about them.
    registers.forEach(r -> backRefInfo.updateInfo(r, ValueInfo.ANY));
    backRef.next.setTarget(cb.nextBlock);
  }

  /** The first zone contained in this loop. */
  Zone firstZone() {
    return processor.zone();
  }

  /** This loop's position in {@code cb().loops}. */
  public int index() {
    return index;
  }

  /** Returns the registers that are updated by this Loop. */
  public Bits registers() {
    return registers;
  }

  /** Returns true if {@link #complete} or {@link #disable} has been called on this loop. */
  boolean isCompleted() {
    return lastZone != null;
  }

  /**
   * Returns the loop that this loop is immediately nested in, or null if this is a top-level loop.
   */
  public Loop nestedIn() {
    return nestedIn;
  }

  /** Returns the Links that go from outside this Loop to inside it. */
  public Stream<Link> entries() {
    return entries.stream();
  }

  public int numEntries() {
    return entries.size();
  }

  public Link entry(int i) {
    return entries.get(i);
  }

  public int numExits() {
    return exits.size();
  }

  public Link exit(int i) {
    return exits.get(i);
  }

  /**
   * Should be called once on each Loop, when the Loop body is complete; adds a {@code BackRef}
   * block that branches back to the first block in the Loop.
   */
  public void complete() {
    CodeBuilder cb = processor.cb();
    Preconditions.checkState(cb.currentLoop() == this);
    assert backRefs.size() == 1 && !isCompleted();
    cb.addAsNext(backRefs.get(0));
    // addAsNext() may trigger analysis and optimization, which could conclude that the BackRef was
    // actually unreachable or unneeded and remove it...  at which point this is no longer a loop.
    // If we call makeEmpty(), nestedIn will be cleared, so save it first
    Loop nestedIn = this.nestedIn;
    if (backRefs.isEmpty()) {
      makeEmpty();
    } else {
      lastZone = cb.currentZone();
      assert lastZone.containingLoop == this;
    }
    // Blocks added after this will be in a new zone in our parent loop.
    cb.newZone(nestedIn);
  }

  /**
   * Should be called if the Loop's back reference is determined to be unreachable (i.e. you are
   * never going to call {@link #complete}, so this isn't actually a loop).
   */
  public void disable() {
    CodeBuilder cb = processor.cb();
    Preconditions.checkState(cb.currentLoop() == this);
    assert backRefs.size() == 1 && backRefs.get(0).index() < 0 && !isCompleted();
    BackRef backRef = backRefs.get(0);
    backRefs.clear();
    LinkTarget target = backRef.next.detach();
    if (target instanceof Block block) {
      cb.removedInlink(block);
    }
    makeEmpty();
    cb.runForwardProp();
  }

  boolean isDisabled() {
    return entries.isEmpty();
  }

  /**
   * Called when a Loop is determined to not actually be a loop, because none of its BackRefs are
   * reachable. Moves all Zones and contained Loops to this Loop's parent.
   */
  void makeEmpty() {
    CodeBuilder cb = processor.cb();
    int lastZoneIndex = lastZoneIndex();
    for (int i = firstZone().index; i <= lastZoneIndex; i++) {
      cb.zone(i).promoteFrom(this);
    }
    assert IntStream.range(0, cb.numLoops()).noneMatch(i -> cb.loop(i).nestedIn == this);
    // Set lastZone so that zoneIndexInRange is always false.
    lastZone = cb.zone(firstZone().index - 1);
    entries.clear();
    exits.clear();
    nestedIn = null;
    entryInfo = null;
  }

  /**
   * If this loop is directly or indirectly contained in {@code from}, updates the loop that was
   * directly contained in {@code from} to instead be in {@code from}'s parent (which may be null).
   * If the promotion has already happened (i.e. this loop is contained in {@code from.nestedIn} but
   * not in {@code from}), does nothing.
   */
  void promoteFrom(Loop from) {
    Loop newParent = from.nestedIn;
    for (Loop loop = this; loop != newParent; loop = loop.nestedIn) {
      if (loop.nestedIn == from) {
        loop.nestedIn = newParent;
        break;
      }
    }
  }

  private int lastZoneIndex() {
    // If this Loop has not yet been completed, its lastZone is taken to be the one before the
    // finalZone (the finalZone is not contained in any Loops).
    return isCompleted() ? lastZone.index : processor.cb().finalZone.index - 1;
  }

  /** Returns true if the Zone with the given index is part of this loop's body. */
  boolean zoneIndexInRange(int zi) {
    return zi >= firstZone().index && zi <= lastZoneIndex();
  }

  /** Returns true if the given Zone is part of this loop's body. */
  public boolean contains(Zone z) {
    boolean result = zoneIndexInRange(z.index);
    // That check should be equivalent to a loop containment check (but avoids the need for a loop).
    assert result == this.contains(z.containingLoop);
    return result;
  }

  /**
   * Returns true if {@code other} is this Loop, or is nested (directly or indirectly) in this Loop.
   *
   * <p>Only used for assertions.
   */
  boolean contains(Loop other) {
    // Look through other's parent chain until we this or hit null.
    for (; other != this; other = other.nestedIn) {
      if (other == null) {
        return false;
      }
    }
    return true;
  }

  private static void update(List<Link> links, Link link, boolean add) {
    if (add) {
      assert !links.contains(link);
      links.add(link);
    } else {
      CodeBuilder.assertTrue(links.remove(link));
    }
  }

  void updateEntry(Link link, boolean add) {
    update(entries, link, add);
  }

  void updateExit(Link link, boolean add) {
    update(exits, link, add);
  }

  boolean consistencyCheck() {
    return entries.stream()
            .allMatch(link -> !contains(link.origin.zone()) && contains(link.targetBlock().zone()))
        && exits.stream()
            .allMatch(link -> contains(link.origin.zone()) && !contains(link.targetBlock().zone()));
  }

  /**
   * If any of our registers aren't actually updated by any of the blocks in this loop we can remove
   * them from our set.
   */
  private void checkForUnmodifiedRegisters() {
    // We could use a Bits.Builder with initializeForIntersection to do this, but since there's
    // almost always only a single BackRef, and even when there's more than one the intersection
    // is usually empty, this is probably cheaper.
    Bits unmodified = backRefs.get(0).unmodified;
    for (int i = 1; i < backRefs.size(); i++) {
      unmodified = Bits.Op.INTERSECTION.apply(unmodified, backRefs.get(i).unmodified);
    }
    if (!unmodified.isEmpty()) {
      registers = Bits.Op.DIFFERENCE.apply(registers, unmodified);
    }
  }

  /**
   * If any of this loop's backRefs no longer link backwards, remove them (they are no longer
   * needed, since their inlinks can just go directly to the next block without violating the
   * forward-linking-only rule). If the loop no longer has any backrefs, remove it completely.
   */
  void removeNonBackRefs() {
    for (int i = 0; i < backRefs.size(); i++) {
      BackRef backRef = backRefs.get(i);
      Block target = backRef.next.targetBlock();
      if (backRef.compareTo(target) < 0) {
        // This BackRef no longer points backward, so we can eliminate it
        processor.cb().removeNoOp(backRef);
        // That should have (indirectly) removed it from the backRefs list, so decrement the loop
        // counter to avoid skipping the next one.
        assert !backRefs.contains(backRef);
        --i;
      }
    }
    if (backRefs.isEmpty()) {
      makeEmpty();
    }
  }

  /**
   * This method checks for three potential optimizations related to a loop's entry links:
   *
   * <ul>
   *   <li>If the target of an entry link is a BackRef, the link can be changed to skip the BackRef
   *       (since the link origin is before the loop it can always link to the BackRef's target
   *       directly).
   *   <li>If all of a block's inlinks are entry links (i.e. it has no inlinks from within the loop)
   *       the block can be moved to before the loop.
   *   <li>If the loop is nested in another loop and none of its entry links come from within the
   *       containing loop, it can be moved to before its containing loop.
   * </ul>
   *
   * <p>(Moving a block or a loop from within a loop to outside it may enable other optimizations.)
   */
  void optimizeEntries() {
    for (int i = 0; i < entries.size(); i++) {
      Link entry = entries.get(i);
      Block origin = entry.origin;
      Block target = entry.targetBlock();
      assert !contains(origin.zone()) && contains(target.zone());
      if (target instanceof BackRef backRef) {
        // There's no need for an entry link to go to a BackRef; it should be able to go directly to
        // the BackRef's target.  If this was the BackRef's only inlink we can drop it, and if it
        // was the loop's only BackRef the loop is no longer a loop.
        entry.detach();
        entry.setTarget(backRef.next.target());
        ++processor.cb().numSkipped;
        if (!backRef.hasInLink()) {
          processor.cb().remove(backRef);
          if (backRefs.isEmpty()) {
            makeEmpty();
            break;
          }
        }
        // We have changed or reordered the entries list, so restart from the beginning of it.
        i = -1;
        continue;
      }
      if (target.hasMultipleInlinks()) {
        if (target.anyInlink(link -> contains(link.origin.zone()))) {
          // (The usual case) The target of this entry has at least one inlink from a block in
          // the loop, so we leave it as-is.
          continue;
        }
        // Set origin to the latest block that links to target; when we move it outside the loop we
        // have to constrain its new zone/order to be after this.
        for (Link link = target.inLink; link != null; link = link.nextInlink()) {
          if (link.origin.compareTo(origin) > 0) {
            origin = link.origin;
          }
        }
      }
      // The target of this entry has no inlinks from within the loop, so we can hoist it out of the
      // loop (meaning its inlink will no longer be an entry, but its outlinks probably will be).
      fixZone(target, origin, 1);
      assert !entries.contains(entry);
      // We have changed or reordered the entries list, so restart from the beginning of it.
      i = -1;
    }
    while (nestedIn != null
        && entries.stream().noneMatch(link -> nestedIn.contains(link.origin.zone()))) {
      // This entire loop can be moved before its parent
      Loop prevNestedIn = nestedIn;
      nestedIn = prevNestedIn.nestedIn;
      int shift = prevNestedIn.firstZone().index - firstZone().index;
      processor.cb().reorderZones(firstZone().index, lastZone.index, shift);
      assert lastZone.index + 1 == prevNestedIn.firstZone().index;
      // All of my entries used to also be entries to my ex-parent, but no longer are
      removeIf(prevNestedIn.entries, link -> contains(link.targetBlock().zone()), entries.size());
      // Some of my exits may now be entries to my ex-parent
      for (Link exit : exits) {
        if (prevNestedIn.contains(exit.targetBlock().zone())) {
          prevNestedIn.updateEntry(exit, true);
        }
      }
      ++processor.cb().numOutOfLoop;
    }
    assert consistencyCheck();
  }

  /**
   * This method checks for two potential optimizations related to a loop's exit links:
   *
   * <ul>
   *   <li>If all of a block's outlinks are exit links the block can be moved after the loop.
   *   <li>If the loop is nested in another loop and all of its exit links also exit the containing
   *       loop, it can be moved to after its containing loop.
   * </ul>
   */
  void optimizeExits() {
    for (int i = 0; i < exits.size(); i++) {
      Link exit = exits.get(i);
      Block origin = exit.origin;
      Block target = exit.targetBlock();
      assert contains(origin.zone()) && !contains(target.zone());
      // If the origin isn't a Split then it can always be moved after; if it is a Split, both
      // outlinks have to be exits.
      if (origin instanceof Block.Split split) {
        Link otherLink = (split.next == exit ? split.alternate : split.next);
        if (contains(otherLink.targetBlock().zone())) {
          continue;
        }
        Block block = otherLink.targetBlock();
        if (block.compareTo(target) < 0) {
          target = otherLink.targetBlock();
        }
      }
      if (origin instanceof BackRef br && br.loop() == this) {
        // If one of our BackRefs is an exit it's no longer a BackRef
        origin.cb().removeNoOp(origin);
        if (backRefs.isEmpty()) {
          // That was the last BackRef, so this is no longer a loop
          return;
        }
      } else {
        fixZone(origin, target, -1);
      }
      assert !exits.contains(exit);
      // We have changed or reordered the entries list, so restart from the beginning of it.
      i = -1;
    }
    while (nestedIn != null
        && exits.stream().noneMatch(link -> nestedIn.contains(link.targetBlock().zone()))) {
      // This entire loop can be moved after its parent
      Loop prevNestedIn = nestedIn;
      nestedIn = prevNestedIn.nestedIn;
      int shift = prevNestedIn.lastZone.index - lastZone.index;
      processor.cb().reorderZones(firstZone().index, lastZone.index, shift);
      assert prevNestedIn.lastZone.index + 1 == firstZone().index;
      // All of my exits used to also be exits from my ex-parent, but no longer are
      removeIf(prevNestedIn.exits, link -> contains(link.origin.zone()), exits.size());
      // Some of my entries may now be exits from my ex-parent
      for (Link entry : entries) {
        if (prevNestedIn.contains(entry.origin.zone())) {
          prevNestedIn.updateExit(entry, true);
        }
      }
      ++processor.cb().numOutOfLoop;
    }
    assert consistencyCheck();
  }

  /**
   * Calls {@code links.removeIf(toRemove)}, and asserts that {@code expectedCount} elements were
   * removed.
   */
  private static void removeIf(List<Link> links, Predicate<Link> toRemove, int expectedCount) {
    int prevSize = links.size();
    links.removeIf(toRemove);
    assert links.size() == prevSize - expectedCount;
  }

  /**
   * Adjust the zone & order of {@code toFix}, to move it out of the loop it is currently in while
   * preserving the validity of its link to (if delta == -1) or from (if delta == 1) {@code linked}.
   */
  private static void fixZone(Block toFix, Block linked, int delta) {
    ++toFix.cb().numOutOfLoop;
    // Our first choice is to move it to the same zone as linked...
    Zone newZone = linked.zone();
    // ... but we don't want to move it into any loops that it wasn't already in
    Loop linkedLoop = newZone.containingLoop;
    if (linkedLoop != null && !linkedLoop.contains(toFix.zone())) {
      // Find the outermost loop containing linked but not nestedIn
      while (linkedLoop.nestedIn != null && !linkedLoop.nestedIn.contains(toFix.zone())) {
        linkedLoop = linkedLoop.nestedIn;
      }
      // We need a zone that's (directly) contained in linkedLoop's parent, and is between
      // toFix.zone and linked.zone.  See if there's already such a zone.
      CodeBuilder cb = newZone.cb;
      for (int i = newZone.index; ; ) {
        i += delta;
        newZone = cb.zone(i);
        if (newZone == toFix.zone()) {
          // There wasn't one, so we need to create one.  Put it immediately before or after
          // linkedLoop.
          int newIndex = (delta < 0) ? linkedLoop.firstZone().index : linkedLoop.lastZone.index + 1;
          newZone = cb.newZone(linkedLoop.nestedIn, newIndex);
          break;
        } else if (newZone.containingLoop == linkedLoop.nestedIn) {
          // This will do
          break;
        }
      }
    }
    toFix.setZone(newZone);
    toFix.setOrder(linked.order() + delta);
  }

  void clearExtraLive() {
    extraLive = null;
  }

  /**
   * Returns the registers that are live in the loop body but not updated by the loop (these
   * registers must be set before the loop is entered, and are used either in the loop body or after
   * it exits). These may be missing from {@link Block#live} for blocks contained in the loop.
   */
  public Bits extraLive() {
    assert extraLive != null;
    return extraLive;
  }

  /** Returns a string listing this Loop's registers. */
  private String printRegisters(CodeBuilder.PrintOptions options) {
    CodeBuilder cb = processor.cb();
    return registers.stream()
        .mapToObj(i -> cb.register(i).toString(options))
        .collect(Collectors.joining(", "));
  }

  @Override
  public String toString() {
    return "loop" + index;
  }

  /**
   * A Loop's Processor implements the forward and reverse propagation steps that are done before
   * (for forward) and after (for reverse) the loop body is processed.
   */
  class Processor extends Zone.Ordered {
    /**
     * Before we process any of the blocks in this loop, compute its {@link #entryInfo} and use that
     * to set the info on each BackRef link.
     */
    @Override
    void runForwardProp(boolean incremental) {
      if (isDisabled()) {
        return;
      }
      assert consistencyCheck();
      CodeBuilder cb = cb();
      // Recompute the union of our entry infos.
      entryInfo.setFrom(entries.get(0).info);
      // Make sure that no registers are saved as a copy of a register we'll be updating
      entryInfo.ensureNoDependencies(registers, cb);
      // The updated registers' infos will be overwritten from the BackRefs
      registers.forEach(ri -> entryInfo.updateInfo(ri, ValueInfo.ANY));
      // Now merge in infos from any other entries
      cb.unioner.start(entryInfo, nestedIn);
      for (int i = 1; i < entries.size(); i++) {
        cb.unioner.add(entries.get(i).info);
      }
      cb.unioner.finish();
      // Now update the info for each BackRef
      for (BackRef backRef : backRefs) {
        cb.scratchInfo.swapContents(backRef.next.info);
        backRef.next.info.setFrom(entryInfo);
        // Overwrite the infos for updated registers from the previous backRef info
        registers.forEach(ri -> backRef.next.info.updateInfo(ri, cb.scratchInfo.register(ri)));
        cb.linkInfoUpdated(backRef.next, cb.scratchInfo, incremental);
      }
    }

    /**
     * Computes the set of registers that are live at the start of the loop. If any of the loop's
     * updated registers are missing from this set, they can be removed from the updated set (which
     * will cause us to recompute liveness within the loop body, since that was done assuming that
     * all of the updated registers were live at the BackRefs).
     *
     * <p>If all of the loop's updated registers are live at the start of the loop, we compute
     * {@link #extraLive} as the additional registers that are live at the start; these will be
     * added to the live set of every block in the loop.
     */
    @Override
    void runReverseProp(boolean incremental) {
      // Union the live sets from each of the blocks reachable from a BackRef (i.e. the beginning(s)
      // of the loop).
      Bits.Builder scratch = cb().scratchBits;
      scratch.clearAll();
      for (BackRef backRef : backRefs) {
        Bits.Op.UNION.into(scratch, backRef.next.targetBlock().live);
      }
      if (scratch.testAll(registers)) {
        // The usual case: all of the loop's registers are live, so we just compute extraLive as
        // the added ones.
        Bits.Op.DIFFERENCE.into(scratch, registers);
        extraLive = scratch.build();
        return;
      }
      // At least one of the loop's registers isn't live, so we reduce the set of updated registers
      // and re-run reverse propagation for the loop body.
      Bits.Op.INTERSECTION.into(scratch, registers);
      Bits newRegisters = scratch.build();
      assert !newRegisters.equals(registers);
      registers = newRegisters;
      // We've modified registers, so re-compute liveness from the BackRefs and then do this again
      backRefs.forEach(cb()::setReversePropNeeded);
      cb().setReversePropNeeded(this);
    }
  }

  /**
   * Each Loop has one or more BackRef blocks at the end of the loop body, linking back to its
   * beginning (these are the only blocks that are allowed to violate the forward-linking-only
   * rule).
   *
   * <p>Each Loop initially has a single BackRef, but some optimization steps can split the control
   * flow to introduce additional BackRefs.
   */
  public class BackRef extends Block.NonTerminal {
    // The subset of registers that were unmodified when we reached this BackRef
    private Bits unmodified = Bits.EMPTY;

    public Loop loop() {
      return Loop.this;
    }

    @Override
    boolean isSimple() {
      return true;
    }

    @Override
    boolean isDuplicate(Block other) {
      return other instanceof BackRef backRef && backRef.loop() == loop();
    }

    @Override
    IntFunction<ValueInfo> simpleInfos(IntFunction<ValueInfo> fromInlink) {
      // If this is one of the loop's registers, use the value from the end of the loop; otherwise
      // use the value from the entries.
      return i ->
          registers.test(i)
              ? LinkInfo.resolve(fromInlink.apply(i), fromInlink)
              : next.info.register(i);
    }

    @Override
    BackRef duplicateImpl() {
      BackRef result = new BackRef();
      result.next.info.setFrom(next.info);
      backRefs.add(result);
      return result;
    }

    /**
     * Called on a new duplicate BackRef to adjust its outlink infos.
     *
     * <p>For other block types we can just rely on forward propagation to ensure that the block's
     * outlink infos are updated before they're read, but a BackRef's target will be visited before
     * the BackRef so we can't wait for that.
     */
    void fixDuplicateInfo(CodeBuilder cb, IntFunction<ValueInfo> inlinkInfos) {
      for (int r : registers) {
        ValueInfo info = inlinkInfos.apply(r);
        if (info instanceof Register) {
          info = inlinkInfos.apply(((Register) info).index);
        }
        // Any change to the register info should narrow it.
        assert cb.binaryOps.containsAll(next.info.register(r), info);
        next.info.updateInfo(r, info);
      }
      cb.setForwardPropNeeded(next.targetBlock());
    }

    @Override
    protected void removed() {
      CodeBuilder.assertTrue(backRefs.remove(this));
      // If all of a Loop's BackRefs become unreachable, it's no longer a loop.
      // (The isCompleted() check is because if this happens during the call to complete() or
      // disable() we let them handle it; the phase check is because during finalization all
      // BackRefs are removed to simplify code generation.)
      if (backRefs.isEmpty() && isCompleted() && cb().phase() != Phase.FINALIZING) {
        makeEmpty();
      }
    }

    @Override
    void runForwardProp(boolean incremental) {
      if (next.target() instanceof Block) {
        Block other = next.targetBlock();
        if (compareTo(other) <= 0) {
          // This is no longer a backref, so we can just get rid of it
          cb().removeNoOp(this);
          return;
        }
      }
      if (hasMultipleInlinks()) {
        SimpleDuplicator.lookAheadForTestBlock(this);
      }
      // If we can strengthen any of our loop register infos, we'll restart propagation from the
      // start of the loop
      boolean changed = false;
      // The loop's registers are the only infos we use from our inlinks
      for (int r : registers) {
        if (!anyInlink(link -> link.info.isModifiedInLoop(r))) {
          unmodified = unmodified.set(r);
        } else {
          ValueInfo info = getInLinkInfo(r, true);
          if (!info.isCloseEnoughTo(next.info.register(r))) {
            changed = true;
            next.info.updateInfo(r, info);
          }
        }
      }
      if (changed && next.target() instanceof Block) {
        cb().setForwardPropNeeded(next.targetBlock());
      }
      if (!unmodified.isEmpty()) {
        checkForUnmodifiedRegisters();
      }
    }

    @Override
    protected PropagationResult updateLive() {
      // BackRefs only report the Loop's registers as live, which is sufficient for analysis within
      // the loop; after analysis of the loop body is complete this will be combined with the Loop's
      // extraLive.
      live = registers;
      return PropagationResult.DONE;
    }

    @Override
    public boolean isNoOp() {
      // BackRef blocks only exist to maintain the forward-linking constraint, and can be
      // removed before code generation.
      return true;
    }

    @Override
    public String toString(CodeBuilder.PrintOptions options) {
      return String.format(
          "back%s %s with %s", loop().index, next.toString(options), printRegisters(options));
    }

    @Override
    public String linksToString(CodeBuilder.PrintOptions options) {
      // toString(boolean) already included our link
      return "";
    }
  }
}
