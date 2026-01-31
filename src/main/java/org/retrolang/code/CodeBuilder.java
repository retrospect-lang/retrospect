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

import static com.google.common.flogger.LazyArgs.lazy;

import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.flogger.FluentLogger;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.objectweb.asm.Opcodes;
import org.retrolang.code.Block.Initial;
import org.retrolang.code.Block.NonTerminal;
import org.retrolang.code.Block.Split;
import org.retrolang.code.Block.Terminal;
import org.retrolang.code.Loop.BackRef;
import org.retrolang.code.ValueInfo.BinaryOps;
import org.retrolang.util.Bits;

/**
 * A CodeBuilder is used to assemble the bytecode for a single Java method. The lifecycle of a
 * CodeBuilder is <nl>
 * <li>Create a {@link Register} for each of the method's arguments.
 * <li>Create Registers for local variables and {@link Block}s for the execution steps; link the
 *     Blocks together (in a directed graph) to reflect the control flow.
 * <li>Call {@link #load} to optimize the Block graph, emit the bytecode, and load the result,
 *     returning a MethodHandle to call the new method.
 * <li>Call {@link #debugInfo} to get a {@link DebugInfo} object that can be used to interpret any
 *     exceptions thrown during execution of the new method. </nl>
 */
public class CodeBuilder {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public final DebugInfo debugInfo = new DebugInfo();

  /**
   * A placeholder for the next Block to be added. Like other FutureBlocks, {@code nextBlock} is not
   * in {@link #blocks} and has no index.
   */
  protected FutureBlock nextBlock = new FutureBlock();

  /**
   * All blocks in the current graph ({@code blocks.get(i).index == i}).
   *
   * <p>No-longer-reachable blocks will be removed from {@code blocks}, which may cause the indices
   * of other blocks to change.
   */
  private final List<Block> blocks = new ArrayList<>();

  /**
   * All blocks in the graph are assigned to one of these zones ({@code zones.get(i).index == i}).
   * Zones may be reordered (with corresponding changes to their indices) by some graph changes.
   *
   * <p>{@link #finalZone} is always the last element of {@code zones}. New blocks added to the
   * graph are added to the second-last element of {@code zones} if they are not Terminals, and to
   * {@link #finalZone} if they are.
   */
  private final List<Zone> zones = new ArrayList<>();

  /**
   * A zone that is always the last element of {@link #zones}, and is not contained in any Loop.
   * Terminals are always added to this zone.
   */
  final Zone finalZone;

  /**
   * New blocks are created with sequentially increasing values of {@link Zone.Ordered#order}, using
   * this counter; this helps ensure the forward-linking-only constraint.
   */
  private int nextOrder = 0;

  /**
   * All registers that have been created ({@code registers.get(i).index == i}). Registers are never
   * deleted or reordered.
   */
  private final List<Register> registers = new ArrayList<>();

  /**
   * All loops that have been created ({@code loops.get(i).index == i}). Loops are never deleted or
   * reordered.
   */
  private final List<Loop> loops = new ArrayList<>();

  /** The first {@code numArgs} elements of {@link #registers} represent the method's arguments. */
  private int numArgs;

  /**
   * The policies for combining {@link ValueInfo}s. The base {@link BinaryOps} is sufficient for the
   * standard {@link ValueInfo} classes, but if you define additional {@link ValueInfo}s you'll need
   * to define an appropriate {@link BinaryOps} subclass.
   */
  public final BinaryOps binaryOps;

  /**
   * If true, CodeBuilder will add additional information to {@link #debugInfo} that is only likely
   * to be useful if you are testing or debugging CodeBuilder.
   */
  public boolean verbose;

  /**
   * While building and optimizing the block graph the CodeBuilder uses forward propagation (of link
   * infos) and backward propagation (of register liveness).
   */
  enum Propagation {
    OFF,
    FORWARD,
    REVERSE
  }

  /**
   * Tracks whether we are currently propagating, and if so in which direction. We propagate forward
   * during building.
   */
  private Propagation propagation = Propagation.FORWARD;

  /**
   * Used during forward propagation to keep track of Blocks that need to be processed. If a Block
   * is in this queue then all of its inlinks must have updated infos.
   */
  private final PriorityQueue<Zone.Ordered> forwardPropQueue = new PriorityQueue<>();

  /**
   * Used during reverse propagation to keep track of Blocks that need to be processed. If a Block
   * is in this queue then all of its outlinks must already have non-null live sets.
   */
  private final PriorityQueue<Zone.Ordered> reversePropQueue =
      new PriorityQueue<>(Comparator.reverseOrder());

  /**
   * When running forward or reverse propagation, these fields record the latest (forward) or
   * earliest (reverse) Ordered that has been processed so far. This enables us to determine whether
   * the prior state is assumed to be valid (incremental processing) or not.
   */
  private Zone incrementalUpToZone;

  private int incrementalUpToOrder;

  /** Scratch state for computing LinkInfo unions. */
  final LinkInfo.Unioner unioner;

  /**
   * A Bits.Builder that is reused to reduce object allocation (see e.g. the default implementation
   * of {@link Block#updateLive}).
   */
  final Bits.Builder scratchBits = new Bits.Builder();

  final Bits.Builder scratchBits2 = new Bits.Builder();

  /** Scratch state for temporarily saving LinkInfo contents. */
  final LinkInfo scratchInfo = new LinkInfo();

  /** Scratch state for temporarily saving LinkInfo contents. */
  final LinkInfo scratchInfo2 = new LinkInfo();

  /**
   * This value will be set as the {@link Block#src} of each block added until the next call to
   * {@link #setNextSrc}.
   */
  private Object nextSrc;

  /** Each CodeBuilder goes through four phases. */
  public enum Phase {
    BUILDING,
    OPTIMIZING,
    FINALIZING,
    EMITTING
  }

  /** The CodeBuilder's current phase. */
  private Phase phase = Phase.BUILDING;

  /** Creates a new CodeBuilder that will use the given BinaryOps to combine ValueInfos. */
  public CodeBuilder(BinaryOps binaryOps) {
    this.binaryOps = binaryOps;
    this.unioner = new LinkInfo.Unioner(this);
    Zone firstZone = new Zone(this, null, 0);
    zones.add(firstZone);
    finalZone = new Zone(this, null, 1);
    zones.add(finalZone);
    Initial initial = new Initial();
    initial.setZone(firstZone);
    initial.setOrder(nextOrder++);
    add(initial);
    assert initialBlock() == initial;
    initial.next.setTarget(nextBlock);
  }

  /** Returns the zone with the given index. */
  final Zone zone(int i) {
    Zone result = zones.get(i);
    assert result.index == i;
    return result;
  }

  /** Returns the zone to which new non-Terminal blocks will be added. */
  final Zone currentZone() {
    // finalZone is always the last zone, and it's only for Terminals; for non-terminals we use
    // the one before that.
    return zones.get(zones.size() - 2);
  }

  /**
   * Adds a new Zone, contained in the given Loop. The result will become the {@link #currentZone}.
   */
  @CanIgnoreReturnValue
  final Zone newZone(Loop containingLoop) {
    // Add it before the finalZone.
    return newZone(containingLoop, zones.size() - 1);
  }

  /** Adds a new Zone, contained in the given Loop and with the given index */
  Zone newZone(Loop containingLoop, int index) {
    Zone result = new Zone(this, containingLoop, index);
    zones.add(index, result);
    // All zones after the newly-created one need to have their indices adjusted.
    reindexZones(index + 1, zones.size() - 1);
    // Make sure that this zone's position is consistent with its containingLoop.
    assert loops.stream()
        .allMatch(loop -> loop.contains(containingLoop) == loop.zoneIndexInRange(index));
    return result;
  }

  /**
   * Called after additions to or reordering of the {@link #zones} list; sets the {@link Zone#index}
   * of each zone from {@code first} to {@code last} (inclusive) to match its current position in
   * {@link #zones}.
   */
  private void reindexZones(int first, int last) {
    for (int i = first; i <= last; i++) {
      zones.get(i).index = i;
    }
  }

  /**
   * Returns the innermost Loop to which new non-Terminal blocks will be added, or null if we are
   * not currently in a loop.
   */
  final Loop currentLoop() {
    return currentZone().containingLoop;
  }

  /** Returns the CodeBuilder's current phase. */
  public final Phase phase() {
    return phase;
  }

  /** Returns the number of registers created with {@link #newArg} or {@link #newRegister}. */
  public final int numRegisters() {
    return registers.size();
  }

  /** Returns the number of registers created with {@link #newArg}. */
  public final int numArgs() {
    return numArgs;
  }

  /** Returns the register with the given index. */
  public final Register register(int i) {
    Register result = registers.get(i);
    assert result.index == i;
    return result;
  }

  /**
   * Returns the signature of the MethodHandle that will be returned by {@link #load}. Should only
   * be called after all args have been added.
   */
  public MethodType methodType(Class<?> returnType) {
    Class<?>[] argTypes =
        IntStream.range(0, numArgs).mapToObj(i -> register(i).type()).toArray(Class[]::new);
    return MethodType.methodType(returnType, argTypes);
  }

  /** Returns the number of blocks currently in the CodeBuilder's block graph. */
  public final int numBlocks() {
    return blocks.size();
  }

  /** Returns the block with the given index. */
  protected final Block block(int i) {
    Block result = blocks.get(i);
    assert result.index() == i;
    return result;
  }

  /**
   * Returns true if there is at least one Link to {@link #nextBlock}. A new block can only be added
   * when {@code nextIsReachable()} returns true.
   */
  public final boolean nextIsReachable() {
    return nextBlock.hasInLink();
  }

  /**
   * Sets {@link #nextBlock} to the given FutureBlock. Should only be called when {@link
   * #nextIsReachable} would return false. The caller must not reuse {@code newNext} after this
   * call.
   */
  public final void setNext(FutureBlock newNext) {
    assert !nextIsReachable();
    // We could instead do mergeNext(links), but this is simpler.
    nextBlock = newNext;
  }

  /** Moves all links from {@code other} to {@link #nextBlock}. */
  public final void mergeNext(FutureBlock other) {
    other.moveAllInLinks(nextBlock);
  }

  /**
   * Moves all links {@link #nextBlock} to {@code links}; after calling, {@code nextIsReachable()}
   * will return false.
   */
  public final void branchTo(FutureBlock links) {
    nextBlock.moveAllInLinks(links);
  }

  /**
   * Sets {@link #nextBlock} to the given FutureBlock, and returns the previous {@link #nextBlock}.
   */
  public final FutureBlock swapNext(FutureBlock other) {
    FutureBlock prevNext = nextBlock;
    nextBlock = other;
    return prevNext;
  }

  /**
   * Returns what is currently known about the value of the specified register when executing the
   * next block to be added. Will not return another Register.
   */
  public final ValueInfo nextInfoResolved(int index) {
    return nextBlock.getInLinkInfo(index, binaryOps, true);
  }

  /** Appends a newly-created block to {@link #blocks} and sets its index. */
  void add(Block b) {
    assert !b.hasInLink() && b.index() < 0;
    b.setIndex(blocks.size());
    blocks.add(b);
    blockRenumbered(b, -1);
  }

  /**
   * Appends the given block to {@link #blocks} and moves the links from {@link #nextBlock} to it.
   * If the block has an unset outlink, it will be linked to {@link #nextBlock}.
   */
  void addAsNext(Block block) {
    Preconditions.checkState(nextIsReachable());
    block.src = nextSrc;
    assert block.zone() == null;
    if (block instanceof Terminal) {
      block.setZone(finalZone);
      // We leave all Terminals with order 0; since none of them have outlinks, and all their
      // inlinks are from other zones, their order doesn't actually matter.
    } else {
      block.setZone(currentZone());
      block.setOrder(nextOrder++);
    }
    add(block);
    nextBlock.moveAllInLinks(block);
    block.setNext(nextBlock);
    setForwardPropNeeded(block);
    runForwardProp();
    if (block instanceof TestBlock) {
      SimpleDuplicator.checkTestInlinks(block);
      // If that ended up moving links around there may be some propagation needed
      runForwardProp();
    }
  }

  /**
   * Called immediately before ({@code add} is false) or after ({@code add} is true) the target of a
   * link is changed; updates Loop entry and exit lists as needed.
   */
  static void updateLoopTransitions(Link link, boolean add) {
    if (link.target() instanceof Block) {
      updateLoopTransitions(link, link.origin.zone(), link.targetBlock().zone(), add);
    }
  }

  /**
   * Assumes that {@code link} goes from {@code originZone} to {@code targetZone} (if it doesn't
   * already, we're about to change it so that it will) and determines which (if any) loops should
   * have it on their entry or exit lists. If {@code add} is false, removes it; otherwise adds it.
   */
  static void updateLoopTransitions(Link link, Zone originZone, Zone targetZone, boolean add) {
    // If this link is to or from a block that hasn't yet been added, it can't be on any loops yet.
    if (originZone == null || targetZone == null) {
      return;
    }
    Loop originLoop = originZone.containingLoop;
    Loop targetLoop = targetZone.containingLoop;
    if (originLoop == targetLoop) {
      // This link doesn't cross any loop boundaries
      return;
    }
    // Advance both originLoop and targetLoop up the loop hierarchy until we find a loop that
    // contains them both, or reach null.
    while (originLoop != null && !originLoop.contains(targetZone)) {
      originLoop.updateExit(link, add);
      originLoop = originLoop.nestedIn();
    }
    while (targetLoop != originLoop) {
      targetLoop.updateEntry(link, add);
      // Adding or removing a loop entry triggers forward propagation to update the backRef infos
      if (targetLoop.isCompleted()) {
        originZone.cb.setForwardPropNeeded(targetLoop);
      }
      targetLoop = targetLoop.nestedIn();
    }
  }

  /**
   * Moves zones start through end (inclusive) either later (if shift is positive) or earlier (if
   * shift is negative) in the zones list and updates the indices of all affected zones.
   */
  void reorderZones(int start, int end, int shift) {
    assert end >= start - 1;
    if (shift == 0 || end < start) {
      return;
    }
    // Copy the zones being moved into a temporary array and remove them from the list...
    List<Zone> range = zones.subList(start, end + 1);
    Zone[] saved = range.toArray(Zone[]::new);
    range.clear();
    // ... then add them back in at their new location.
    zones.addAll(start + shift, Arrays.asList(saved));
    // Now figure out which zones need their index fixed
    int firstRenumber = start + Math.min(shift, 0);
    int lastRenumber = end + Math.max(shift, 0);
    // All zones before firstRenumber and after lastRenumber should still have the correct index
    assert IntStream.range(0, zones.size())
        .allMatch(i -> (zones.get(i).index == i) == (i < firstRenumber || i > lastRenumber));
    reindexZones(firstRenumber, lastRenumber);
  }

  /**
   * Called with a block that no longer has any inlinks; removes it from {@link #blocks} and
   * detaches all of its outlinks.
   */
  void remove(Block b) {
    int index = b.index();
    assert !b.hasInLink() && (index < 0 || blocks.get(index) == b);
    if (index < 0) {
      return;
    }
    b.setIndex(-1);
    blockRenumbered(b, index);
    // We want to keep the blocks list dense, so if necessary move the previously-last element into
    // the space this leaves.
    int lastIndex = blocks.size() - 1;
    if (index != lastIndex) {
      Block toMove = blocks.get(lastIndex);
      blocks.set(index, toMove);
      toMove.setIndex(index);
      blockRenumbered(toMove, lastIndex);
    }
    blocks.remove(lastIndex);
    ++numDropped;
    if (b instanceof NonTerminal nonTerminal) {
      detach(nonTerminal.next);
      if (b instanceof Split split) {
        detach(split.alternate);
      }
    }
    b.removed();
  }

  /**
   * Called with a block that has been determined to be a no-op; moves its inlinks to the target of
   * its (next) outlink and then removes it.
   */
  void removeNoOp(Block block) {
    Link nextLink = ((NonTerminal) block).next;
    block.moveAllInLinks(nextLink.target());
    remove(block);
  }

  /**
   * Called with an outlink of a just-removed block; detaches the link, and if it had pointed to a
   * block that is no longer reachable deletes that block.
   */
  private void detach(Link link) {
    LinkTarget target = link.detach();
    if (target instanceof Block block) {
      removedInlink(block);
    }
  }

  /** We've just removed an inlink to the given block. If it is no longer reachable, delete it. */
  void removedInlink(Block block) {
    if (!block.hasInLink()) {
      remove(block);
    } else {
      setForwardPropNeeded(block);
    }
  }

  /**
   * Detaches {@code toMove} and sets its target to {@code newTarget}. If {@code toMove.src} is
   * simple and any of {@code newTarget}'s previous inlinks are duplicates of it, calls {@link
   * #mergeMatching} to combine them.
   */
  void moveLink(Link toMove, LinkTarget newTarget) {
    toMove.detach();
    if (toMove.origin.isSimple()) {
      // Before just pointing this block's next at newTarget, see if there's a duplicate
      // block already pointing to it; if so, we only need to keep one of them.
      Link match = newTarget.findInlink(inLink -> toMove.origin.isDuplicate(inLink.origin));
      if (match != null) {
        // We postponed calling setTarget() to make looking for a duplicate simpler, but we need to
        // do it before calling mergeMatching().
        toMove.setTarget(newTarget);
        setForwardPropNeeded(mergeMatching(toMove.origin, match.origin));
        return;
      }
    }
    toMove.setTarget(newTarget);
    if (newTarget instanceof Block block) {
      setForwardPropNeeded(block);
    }
  }

  /**
   * Given two simple blocks that are duplicates and link to the same next block, drops one of them
   * and redirects its inlinks to the other. Returns the survivor.
   */
  @CanIgnoreReturnValue
  NonTerminal mergeMatching(NonTerminal b1, NonTerminal b2) {
    assert b1 != b2 && b1.isDuplicate(b2) && b1.next.target() == b2.next.target();
    // We want to remove the earlier one and move its inlinks to the later one (to ensure that we
    // maintain the link ordering constraint).
    NonTerminal drop;
    NonTerminal keep;
    if (b1.compareTo(b2) <= 0) {
      drop = b1;
      keep = b2;
    } else {
      drop = b2;
      keep = b1;
    }
    drop.forEachInLink(link -> moveLink(link, keep));
    // This is the one place a LinkInfo may change in an inconsistent fashion, since we've added
    // a new way to reach an existing block.  It's safe, since we're swapping one block for an
    // identical one and they both linked to the same next block, but it might fail the
    // consistency check when keep.next.info is updated.
    if (!(keep instanceof BackRef)) {
      // For anything except a BackRef we can just invalidate the LinkInfo and let it be recomputed,
      // which disables the consistency check.
      keep.next.info.clear(false);
    } else {
      // BackRefs are tricky, since setForwardPropNeeded on the backRef doesn't guarantee that its
      // LinkInfo will be recomputed before it is needed by the backRef's target.  So instead we
      // manually union their LinkInfos, which should preserve all the invariants.
      unioner.start(keep.next.info, keep.containingLoop());
      unioner.add(drop.next.info);
      unioner.finish();
    }
    remove(drop);
    return keep;
  }

  /**
   * Starts a loop. The next block added after this will be the block that can be branched back to;
   * after adding all the blocks in the loop body, call {@link Loop#complete}. The loop body may
   * update the registers in {@code registers} to carry state from one iteration to the next; any
   * other registers set in the loop are assumed to be temporaries.
   */
  public Loop startLoop(Bits registers) {
    Loop loop = new Loop(this, loops.size(), registers);
    loops.add(loop);
    return loop;
  }

  /** Starts a loop that updates the given registers. */
  public Loop startLoop(Register... registers) {
    scratchBits.clearAll();
    for (Register r : registers) {
      scratchBits.set(r.index);
    }
    return startLoop(scratchBits.build());
  }

  /** Returns the number of Loops that have been created by this CodeBuilder. */
  public int numLoops() {
    return loops.size();
  }

  /** Returns the Loop with the given index. */
  public Loop loop(int i) {
    return loops.get(i);
  }

  /**
   * Adds {@code newBlock} so that it is immediately followed by {@code nextBlock}. The caller is
   * responsible for adding at least one inlink to {@code newBlock} after this method returns.
   */
  protected void insertBefore(NonTerminal newBlock, Block nextBlock) {
    assert !(newBlock instanceof Split);
    newBlock.setZone(nextBlock.zone());
    newBlock.setOrder(nextBlock.order() - 1);
    add(newBlock);
    newBlock.next.setTarget(nextBlock);
  }

  /** Sets the {@link Block#src} of each block added until the next call to {@code setNextSrc()}. */
  public void setNextSrc(Object src) {
    nextSrc = src;
  }

  /** The value passed in the most recent call to {@link #setNextSrc}. */
  public Object nextSrc() {
    return nextSrc;
  }

  /** Creates a new Register with the given type. */
  public Register newRegister(Class<?> type) {
    Register result = new Register(registers.size(), type);
    registers.add(result);
    return result;
  }

  /**
   * Adds an argument with the given type. All calls to {@code newArg()} must be made before adding
   * blocks to the block graph or creating non-argument registers.
   */
  public Register newArg(Class<?> type) {
    Preconditions.checkState(blocks.size() == 1 && numArgs == registers.size());
    Register result = newRegister(type);
    ++numArgs;
    initialBlock().addArg(result, ValueInfo.ANY);
    return result;
  }

  /** Returns the {@link Initial} that is the root of the block graph. */
  Initial initialBlock() {
    return (Initial) blocks.get(0);
  }

  /** Returns a string representation of the given Block. */
  protected String printBlock(Block b, PrintOptions options) {
    return b.toString(options) + b.linksToString(options);
  }

  /**
   * A PrintOptions is used by the toString() methods of Blocks, Links, and CodeValues. The primary
   * implementation of PrintOptions is Sequencer, which tries to create a maximally readable string
   * representation of a complete block graph, but PrintOptions.DEFAULT can be used when we are not
   * in that context.
   */
  public interface PrintOptions {
    /**
     * Returns true if Registers should be identified by their JVM local index, rather than their
     * register index.
     */
    boolean useJvmLocals();

    /**
     * Returns true if the target of the given link will be printed immediately after its origin;
     * {@link Block#linksToString} implementations can elide such links to make their output less
     * verbose.
     */
    boolean isLinkToNextBlock(Link link);

    /** Returns the identifier to be used when printing a link to the given block. */
    String blockId(Block block);

    /** Returns true if the specified register is live at the start of the current block. */
    default boolean isLive(int index) {
      return true;
    }

    /**
     * Returns a PrintOptions that delegates {@link #isLive} to {@code liveRegisters}, and all other
     * methods to {@code parent}.
     */
    static PrintOptions withLive(PrintOptions parent, IntPredicate liveRegisters) {
      return new PrintOptions() {
        @Override
        public boolean useJvmLocals() {
          return parent.useJvmLocals();
        }

        @Override
        public boolean isLinkToNextBlock(Link link) {
          return parent.isLinkToNextBlock(link);
        }

        @Override
        public String blockId(Block block) {
          return parent.blockId(block);
        }

        @Override
        public boolean isLive(int index) {
          return liveRegisters.test(index);
        }
      };
    }

    PrintOptions DEFAULT =
        new PrintOptions() {
          @Override
          public boolean useJvmLocals() {
            return false;
          }

          @Override
          public boolean isLinkToNextBlock(Link link) {
            return false;
          }

          @Override
          public String blockId(Block block) {
            // Make it obvious that this is just the block index, not the position chosen by a
            // Sequencer
            return "#" + block.index();
          }
        };
  }

  /**
   * If the object passed to {@link #setNextSrc} implements {@code Printable}, its rendering by
   * {@link #printBlocks} can depend on the PrintOptions.
   */
  public interface Printable {
    String toString(PrintOptions options);
  }

  private static final String SRC_PAD = " ".repeat(55);

  /**
   * Returns a string representation of this CodeBuilder's block graph, with the blocks ordered by
   * the given Sequencer.
   */
  String printBlocks(Sequencer sequencer) {
    StringBuilder sb = new StringBuilder();
    Object prevSrc = null;
    String blockNumFormat = "%" + DebugInfo.numDigits(numBlocks() - 1) + "d";
    // The Initial block is uninteresting, so we omit it
    assert sequencer.orderedBlock(0) instanceof Initial;
    for (int i = 1; i < numBlocks(); i++) {
      int finalI = i;
      Block b = sequencer.orderedBlock(i);
      // Marking each block that is branched to (with a "=" prefix if it is the target of a backward
      // branch, or "-" if it is the target only of forward branches) helps with readability.
      String branchMark = " ";
      if (!b.hasInLink()) {
        // Something weird is happening -- we skipped the initial block, and it should be the only
        // one with no inlinks.
        branchMark = "?";
      } else if (b.anyInlink(
          link -> link.origin.index() < 0 || sequencer.position(link.origin) > finalI)) {
        branchMark = "=";
      } else if (b.hasMultipleInlinks() || !sequencer.isLinkToNextBlock(b.firstInlink())) {
        branchMark = "-";
      }
      sb.append(branchMark).append(String.format(blockNumFormat, i)).append(": ");
      String s = printBlock(b, sequencer);
      sb.append(s);
      // If we have src information for this block, and it differs from the previous block,
      // include it as a inline comment (aligned in the column determined by SRC_PAD).
      if (b.src != null && b.src != prevSrc) {
        if (s.length() < CodeBuilder.SRC_PAD.length()) {
          sb.append(CodeBuilder.SRC_PAD, s.length(), CodeBuilder.SRC_PAD.length());
        }
        sb.append(" // ");
        if (b.src instanceof Printable pSrc) {
          PrintOptions options = sequencer;
          if (b.live != null) {
            options = PrintOptions.withLive(options, ri -> b.isLive(ri, null));
          }
          sb.append(pSrc.toString(options));
        } else {
          sb.append(b.src);
        }
      }
      prevSrc = b.src;
      sb.append("\n");
    }
    return sb.toString();
  }

  /** Returns a string representation of this CodeBuilder's block graph. */
  protected String printBlocks() {
    return printBlocks(new Sequencer(blocks, false));
  }

  /** Called after optimization is complete, just before emitting. */
  protected void finalizeBlocks(RegisterAssigner assigner) {
    // Note that this will remove BackRefs, breaking the usual no-back-links-without-a-BackRef rule,
    // but we only do this immediately before emitting blocks (after all optimization passes are
    // completed) so we're OK.
    removeNoOps(Block::isNoOp);
  }

  /** Calls {@link #removeNoOp} on each block identified by the given predicate. */
  protected void removeNoOps(Predicate<Block> toRemove) {
    for (int i = 0; i < blocks.size(); ) {
      Block block = blocks.get(i);
      if (toRemove.test(block)) {
        removeNoOp(block);
      } else {
        ++i;
      }
    }
  }

  /**
   * Adds the given Block to the forward-propagation queue if forward propagation is in process;
   * does nothing if we are not currently running forward propagation.
   */
  void setForwardPropNeeded(Block block) {
    assert block.index() >= 0;
    if (propagation == Propagation.FORWARD && !block.setIsForwardQueued(true)) {
      forwardPropQueue.add(block);
    }
  }

  /**
   * Adds the given Loop's processor to the forward-propagation queue if forward propagation is in
   * process; does nothing if we are not currently running forward propagation.
   */
  void setForwardPropNeeded(Loop loop) {
    if (propagation == Propagation.FORWARD
        && !loop.isDisabled()
        && !loop.processor.setIsForwardQueued(true)) {
      forwardPropQueue.add(loop.processor);
    }
  }

  void linkInfoChanged(Link link) {
    if (link.target() instanceof Block) {
      setForwardPropNeeded(link.targetBlock());
      // If this is an entry link to one or more loops, they also need forward prop
      Zone originZone = link.origin.zone();
      Loop targetLoop = link.targetBlock().zone().containingLoop;
      while (targetLoop != null && targetLoop.isCompleted() && !targetLoop.contains(originZone)) {
        setForwardPropNeeded(targetLoop);
        targetLoop = targetLoop.nestedIn();
      }
    }
  }

  /**
   * {@code link}'s info has changed; it was previously {@code prev}. Determine whether the link's
   * target now needs forward propagation.
   */
  void linkInfoUpdated(Link link, LinkInfo prev, boolean incremental) {
    if (link.info.sameContents(prev)) {
      // Even though they're equal, restoring the older version might reduce memory use
      // by not keeping extra copies.
      link.info.swapContents(prev);
      if (incremental) {
        return;
      }
    } else {
      assert link.info.isConsistentChange(
          prev, link.target() instanceof Block ? link.targetBlock() : null, binaryOps);
      // They're different, but they might not be different enough to matter
      if (incremental && link.info.isCloseEnoughTo(prev)) {
        return;
      }
    }
    linkInfoChanged(link);
  }

  /**
   * If we're running with assertions enabled, verify that the argument is true.
   *
   * <p>Used instead of an {@code assert} statement when we always want to evaluate the argument
   * (because it has a side effect).
   */
  public static void assertTrue(boolean check) {
    assert check;
  }

  /**
   * Runs {@link Zone.Ordered#runForwardProp} on each item in the forward-propagation queue, until
   * the queue is drained.
   */
  void runForwardProp() {
    assert propagation == Propagation.FORWARD;
    for (; ; ) {
      Zone.Ordered b = forwardPropQueue.poll();
      if (b == null) {
        return;
      }
      assertTrue(b.setIsForwardQueued(false));
      // If this is a block that was deleted while it was queued we just ignore it
      if (b instanceof Block block && block.index() < 0) {
        continue;
      }
      b.runForwardProp(checkIncremental(b, true));
    }
  }

  private boolean checkIncremental(Zone.Ordered b, boolean forward) {
    if (incrementalUpToZone != null) {
      int cmp = b.compareTo(incrementalUpToZone, incrementalUpToOrder);
      // If we're re-processing an already processed block we can do it incrementally
      if (forward ? cmp < 0 : cmp > 0) {
        return true;
      }
    }
    // This is our first time processing this block (in this iteration), so do it non-incrementally
    // and update our "last processed" threshold
    incrementalUpToZone = b.zone();
    incrementalUpToOrder = b.order();
    return false;
  }

  /** Adds the given Block (or Loop.Processor) to the reverse-propagation queue. */
  void setReversePropNeeded(Zone.Ordered zo) {
    assert propagation == Propagation.REVERSE;
    if (!zo.setIsReverseQueued(true)) {
      reversePropQueue.add(zo);
    }
  }

  /**
   * Removes the given Block or Loop.Processor from the forward- and/or reverse-propagation queue.
   */
  void cancelProp(Zone.Ordered zo) {
    if (zo.setIsForwardQueued(false)) {
      forwardPropQueue.remove(zo);
    }
    if (zo.setIsReverseQueued(false)) {
      reversePropQueue.remove(zo);
    }
  }

  /**
   * Runs {@link Zone.Ordered#runReverseProp} on each item in the reverse-propagation queue, until
   * the queue is drained.
   */
  private void runReverseProp() {
    assert propagation == Propagation.REVERSE;
    for (; ; ) {
      Zone.Ordered b = reversePropQueue.poll();
      if (b == null) {
        return;
      }
      assertTrue(b.setIsReverseQueued(false));
      // If this is a block that was deleted while it was queued we just ignore it
      if (b instanceof Block block && block.index() < 0) {
        continue;
      }
      b.runReverseProp(checkIncremental(b, false));
    }
  }

  /** Returns the head of the reverse-propagation queue, or null if the queue is empty. */
  Zone.Ordered peekReverseProp() {
    return reversePropQueue.peek();
  }

  int numSkipped;
  int numDropped;
  int numMoved;
  int numOutOfLoop;
  int numDuped;

  private int numChanges() {
    return numSkipped + numDropped + numMoved + numOutOfLoop + numDuped;
  }

  private void logChanges(String where) {
    logger.atFine().log(
        "%s: %s skipped, %s dropped, %s moved, %s outOfLoop, %s duplicated",
        where, numSkipped, numDropped, numMoved, numOutOfLoop, numDuped);
  }

  /**
   * Optimizes the block graph. Alternately applies backward and forward propagation until the graph
   * stops changing.
   */
  void optimizeBlocks() {
    logChanges("Starting optimization");
    for (; ; ) {
      int prevNumChanges = numChanges();
      for (Loop loop : loops) {
        // We should recompute extraLive before using it again
        loop.clearExtraLive();
        loop.removeNonBackRefs();
        loop.optimizeEntries();
        loop.optimizeExits();
      }
      assert reversePropQueue.isEmpty() && propagation == Propagation.OFF;
      propagation = Propagation.REVERSE;
      incrementalUpToZone = null;
      // Start reverse propagation from Terminals and BackRefs, since those are the only blocks that
      // don't need liveness from their outlinks.
      for (Block b : blocks) {
        if (b instanceof Terminal || b instanceof BackRef) {
          setReversePropNeeded(b);
        }
      }
      runReverseProp();
      propagation = Propagation.OFF;
      if (numChanges() == prevNumChanges) {
        break;
      }
      assert forwardPropQueue.isEmpty() && propagation == Propagation.OFF;
      propagation = Propagation.FORWARD;
      incrementalUpToZone = null;
      for (Block b : blocks) {
        // Start forward propagation from the Initial block and from the blocks that are targets of
        // BackRefs.
        // (We only need the blocks that are *only* reachable from a BackRef, but it's easier to
        // just add them all.)
        if (b instanceof Initial || b instanceof BackRef) {
          setForwardPropNeeded(((NonTerminal) b).next.targetBlock());
        }
      }
      runForwardProp();
      propagation = Propagation.OFF;
      logChanges("Completed pass");
    }
    if (verbose) {
      debugInfo.postOptimization = printBlocks();
    }
  }

  /** Update {@code b.live}; for use by subclasses which otherwise would not have access. */
  protected static void setLive(Block b, Bits live) {
    b.live = live;
  }

  /**
   * Update the live state of each block to include the {@link Loop#extraLive()} of each enclosing
   * loop. Should only be done once all optimization is complete.
   */
  private void finalizeLive() {
    for (Block b : blocks) {
      Loop loop = b.zone().containingLoop;
      if (loop != null) {
        scratchBits.clearAll();
        b.getLive(scratchBits, null);
        b.live = scratchBits.build();
      }
    }
  }

  /**
   * Called when a Block is added ({@code prevIndex < 0}), removed ({@code b.index() < 0}), or
   * renumbered. Enables a subclass to maintain additional per-block information using {@link
   * Block#index()}.
   */
  protected void blockRenumbered(Block b, int prevIndex) {}

  /**
   * Called after the block graph is complete; optimizes the graph, emits the resulting sequence of
   * bytecode, and loads them as a new MethodHandle.
   *
   * @param methodName will appear in any stack traces thrown while executing the constructed method
   * @param sourceFileName if non-null, will appear in any stack traces thrown while executing the
   *     constructed method
   * @param returnType the return type of the constructed method; must match all ReturnBlocks in the
   *     graph
   * @param lookup a {@link MethodHandles.Lookup} for the package in which the constructed method
   *     will be loaded, and which it will have access to
   */
  public MethodHandle load(
      String methodName, String sourceFileName, Class<?> returnType, MethodHandles.Lookup lookup) {
    // Forward propagation was enabled while we were building the graph.
    assert propagation == Propagation.FORWARD;
    propagation = Propagation.OFF;
    phase = Phase.OPTIMIZING;
    optimizeBlocks();
    phase = Phase.FINALIZING;
    finalizeLive();
    // The RegisterAssigner's construction of alias sets may cause some SetBlocks to become no-ops,
    // which will cause finalizeBlocks() to remove them.
    RegisterAssigner assigner = new RegisterAssigner(this);
    finalizeBlocks(assigner);
    phase = Phase.EMITTING;
    int numLocals = assigner.assignJavaLocalNumbers();
    Sequencer sequencer = new Sequencer(blocks, true);
    debugInfo.blocks = printBlocks(sequencer);
    logger.atFine().log("Final blocks:\n%s", debugInfo.blocks);
    Emitter emitter = new Emitter(this, returnType, numLocals, sequencer);
    try {
      return emitter.emit(methodName, sourceFileName, lookup);
    } finally {
      logger.atFine().log(
          "Generated code:\n%s",
          lazy(
              () ->
                  DebugInfo.printConstants(debugInfo.constants, lookup)
                      + "\n"
                      + DebugInfo.printClassBytes(debugInfo.classBytes)));
    }
  }

  /**
   * Represents the 5 kinds of values the JVM distinguishes on the stack and in local variables:
   * ints, longs, floats, doubles, and object pointers. Note that booleans, bytes, shorts, and chars
   * are stored as ints.
   */
  public enum OpCodeType {
    INT("i", int.class, Opcodes.INTEGER, Opcodes.ILOAD, Opcodes.ISTORE, Opcodes.IRETURN, 0),
    LONG(
        "l",
        long.class,
        Opcodes.LONG,
        Opcodes.LLOAD,
        Opcodes.LSTORE,
        Opcodes.LRETURN,
        Opcodes.LCMP),
    FLOAT(
        "f",
        float.class,
        Opcodes.FLOAT,
        Opcodes.FLOAD,
        Opcodes.FSTORE,
        Opcodes.FRETURN,
        Opcodes.FCMPL),
    DOUBLE(
        "d",
        double.class,
        Opcodes.DOUBLE,
        Opcodes.DLOAD,
        Opcodes.DSTORE,
        Opcodes.DRETURN,
        Opcodes.DCMPL),
    OBJ("x", Object.class, null, Opcodes.ALOAD, Opcodes.ASTORE, Opcodes.ARETURN, 0);

    /** The (lower-case) single-letter prefix used for registers of this type. */
    public final String prefix;

    /** The upper-case equivalent of {@link #prefix}. */
    public final String upperPrefix;

    /** The corresponding Java Class. */
    public final Class<?> javaType;

    /**
     * The number of stack entries or local variable indices occupied by a value of this type. 2 for
     * LONG and DOUBLE, 1 for everything else.
     */
    public final int size;

    /** The encoding used to represent this type in stack maps, or null for OBJ. */
    public final Integer frameType;

    /** The JVM opcode used to push a local variable of this type onto the stack. */
    public final int loadVarOpcode;

    /**
     * The JVM opcode used to pop a value of this type from the top of the stack and store it in a
     * local variable.
     */
    public final int storeVarOpcode;

    /** The JVM opcode used to return a value of this type from the currently-executing method. */
    public final int returnOpcode;

    /**
     * For LONG, FLOAT, and DOUBLE, the opcode that pops two values of that type from the stack,
     * compares them, and pushes -1, 0, or 1.
     *
     * <p>Null for INT (where there are instead opcodes that combine a comparison with a branch; see
     * {@link ConditionalBranch}) and OBJ (which only has opcodes that combine equality comparison
     * with branching; again, see {@link ConditionalBranch}).
     *
     * <p>(Pedants may note that for floats and doubles the JVM actually provides two opcodes that
     * do this comparison, differing in their treatment of NaNs. We use FCMPL for floats and DCMPL
     * for doubles, which means that if either (or both) of the arguments to a {@link
     * TestBlock.IsLessThan} is NaN the next (success) link will be taken.)
     */
    public final int compareOpcode;

    OpCodeType(
        String prefix,
        Class<?> javaType,
        Integer frameType,
        int loadVarOpcode,
        int storeVarOpcode,
        int returnOpcode,
        int compareOpcode) {
      this.prefix = prefix;
      this.upperPrefix = Ascii.toUpperCase(prefix);
      this.javaType = javaType;
      this.frameType = frameType;
      this.size = localSize(javaType);
      this.loadVarOpcode = loadVarOpcode;
      this.storeVarOpcode = storeVarOpcode;
      this.returnOpcode = returnOpcode;
      this.compareOpcode = compareOpcode;
    }

    static OpCodeType forType(Class<?> type) {
      assert type != void.class;
      if (!type.isPrimitive()) {
        return OBJ;
      } else if (type == long.class) {
        return LONG;
      } else if (type == float.class) {
        return FLOAT;
      } else if (type == double.class) {
        return DOUBLE;
      } else {
        assert isStoredAsInt(type);
        return INT;
      }
    }

    /**
     * Returns the number of stack entries or local variable indices occupied by a value of the
     * given type: 2 for long and double, 1 for everything else.
     */
    static int localSize(Class<?> type) {
      assert type != void.class;
      return (type == double.class || type == long.class) ? 2 : 1;
    }

    /**
     * Returns true if the given type is one of the primitive types that the JVM represents using an
     * int (i.e. int, boolean, byte, short, or char).
     */
    static boolean isStoredAsInt(Class<?> type) {
      return type == int.class
          || type == boolean.class
          || type == byte.class
          || type == short.class
          || type == char.class;
    }
  }
}
