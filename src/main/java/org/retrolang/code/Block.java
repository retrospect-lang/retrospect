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
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.retrolang.code.CodeBuilder.PrintOptions;
import org.retrolang.code.Loop.BackRef;
import org.retrolang.code.TestBlock.IsEq;
import org.retrolang.util.Bits;

/**
 * A Block represents one step in the program being constructed; when the program is emitted each
 * Block will contribute a sequence of bytecodes (from its {@link #emit} method). Blocks are
 * connected to other Blocks by {@link Link}s, forming a directed graph; each Link represents a
 * possible flow of control. There are subclass of Block with zero Links ({@link Terminal}), one
 * Link ({@link NonTerminal}), and two Links ({@link Split}).
 */
public abstract class Block extends Zone.Ordered {

  /** The index of this block in the {@link CodeBuilder#blocks} list. */
  private int index = -1;

  /**
   * If non-null, the registers that are live at the start of this block. A register is live if its
   * contents may be read by at least one possible execution path.
   *
   * <p>Note that for blocks inside a loop this may be incomplete until the CodeBuilder's FINALIZING
   * phase; see {@link Loop#extraLive()} for details.
   */
  Bits live;

  /**
   * Optional information about the source code that produced this block; does not affect code
   * generation, but is included when printing to assist with debugging.
   *
   * <p>If this object implements {@link CodeBuilder.Printable} its rendering as a string may depend
   * on the state of the CodeBuilder.
   */
  Object src;

  /** Add this (newly-created) block to the CodeBuilder's blocks list. */
  public void addTo(CodeBuilder cb) {
    cb.addAsNext(this);
  }

  /**
   * Returns the number of expressions associated with this block.
   *
   * <p>For example, a {@link SetBlock} has a single input for the right hand side; a {@link
   * ReturnBlock} block has zero or one inputs (depending on whether the method being compiled has a
   * void return type or not), and an {@link IsEq} test has two inputs.
   *
   * <p>The default implementation returns zero.
   */
  public int numInputs() {
    return 0;
  }

  /**
   * Returns one of the expressions associated with this block; {@code index} must be less than
   * {@link #numInputs}.
   */
  public CodeValue input(int index) {
    throw new AssertionError();
  }

  /**
   * Updates one of the expressions associated with this block; {@code index} must be less than
   * {@link #numInputs}.
   */
  public void setInput(int index, CodeValue input) {
    throw new AssertionError();
  }

  /** Simplifies each of the expressions associated with this block. */
  protected void simplifyInputs(IntFunction<ValueInfo> registerInfo) {
    for (int i = numInputs() - 1; i >= 0; i--) {
      setInput(i, input(i).simplify(registerInfo));
    }
  }

  /**
   * Emits the bytecode to implement this block. If the emitted bytecode expects to fall through to
   * another block, returns that block; otherwise returns null.
   */
  @CanIgnoreReturnValue
  public Block emit(Emitter emitter) {
    // The only case in which it's valid to not override the emit() method is for Blocks that are
    // always no-ops.
    assert isNoOp();
    return ((NonTerminal) this).next.targetBlock();
  }

  /**
   * If this block modifies register values, calls the consumer with each of the registers it can
   * modify. The default implementation does nothing.
   */
  public void forEachModifiedRegister(Consumer<Register> consumer) {}

  /**
   * True if this block may read at least one of the registers in its inputs after modifying a
   * register in its outputs. Not used if this block does not modify register values.
   *
   * <p>The default implementation returns false.
   */
  public boolean mayReadAfterWrite() {
    return false;
  }

  /** Null if this Block is not in any Loop; otherwise the innermost Loop that contains it. */
  public final Loop containingLoop() {
    return zone().containingLoop;
  }

  /**
   * Returns a printable description of the links from this block, suitable for appending to the
   * result of {@link #toString(CodeBuilder.PrintOptions)}.
   */
  public abstract String linksToString(CodeBuilder.PrintOptions options);

  /** Returns a printable description of this block. */
  public abstract String toString(CodeBuilder.PrintOptions options);

  @Override
  public String toString() {
    return toString(PrintOptions.DEFAULT) + linksToString(PrintOptions.DEFAULT);
  }

  /**
   * The index of this block in the {@link CodeBuilder#blocks} list; negative if this block has not
   * yet been inserted or has been removed.
   */
  public final int index() {
    return index;
  }

  /** Enables CodeBuilder to update {@link #index}; not for general use. */
  final void setIndex(int index) {
    this.index = index;
  }

  @Override
  void setZone(Zone newZone) {
    // If changing the zone changes the block's containing loop,
    // we must update the loop entry & exit lists accordingly.
    Loop prevLoop = (zone() == null) ? null : containingLoop();
    if (prevLoop != newZone.containingLoop) {
      // Check each inlink to see whether it used to cross a loop boundary, and whether it will
      // cross a loop boundary after this change.
      forEachInLink(
          link -> {
            CodeBuilder.updateLoopTransitions(link, link.origin.zone(), zone(), false);
            CodeBuilder.updateLoopTransitions(link, link.origin.zone(), newZone, true);
          });
      // Check each outlink to see whether it used to cross a loop boundary, and whether it will
      // cross a loop boundary after this change.
      if (this instanceof NonTerminal nonTerminal) {
        updateOutLinkTransition(nonTerminal.next, newZone);
        if (this instanceof Split split) {
          updateOutLinkTransition(split.alternate, newZone);
        }
      }
    }
    super.setZone(newZone);
  }

  /**
   * We are about to move this Block to {@code newZone}, and {@code link} is one of its outlinks;
   * add or remove {@code link} from loop entry & exit lists as appropriate.
   */
  private void updateOutLinkTransition(Link link, Zone newZone) {
    // If link isn't yet attached there's nothing to do.
    if (link.target() instanceof Block) {
      Zone dstZone = link.targetBlock().zone();
      CodeBuilder.updateLoopTransitions(link, zone(), dstZone, false);
      CodeBuilder.updateLoopTransitions(link, newZone, dstZone, true);
    }
  }

  /**
   * If this block's next or alternate link is unset, sets it to {@code links}. If this block has no
   * unset outlinks, does nothing. Throws an exception if both are unset.
   */
  void setNext(FutureBlock links) {}

  /** Called when a block is removed; the default implementation does nothing. */
  protected void removed() {}

  /** Should return true if calling {@link #emit} will emit no bytecode. */
  public boolean isNoOp() {
    return false;
  }

  /**
   * The result of the {@link NonTerminal#updateInfo} (forward propagation) and {@link #updateLive}
   * (reverse propagation) methods; one of
   *
   * <ul>
   *   <li>DONE: This block's outlink {@link Link#info} (for forward propagation) or {@link #live}
   *       (for reverse propagation) has been updated.
   *   <li>REMOVE: This block is no longer needed (e.g. it sets a register that no one reads) and
   *       can be removed from the graph. Any remaining inlinks will be redirected to the target of
   *       its (next) outlink.
   *   <li>SKIP: Skips any further steps in propagation for this block, and continues propagation
   *       with the next block on the queue.
   * </ul>
   */
  public enum PropagationResult {
    DONE,
    REMOVE,
    SKIP
  }

  /**
   * Called by {@link #runReverseProp} to set {@link #live} to the registers that are live at the
   * beginning of this block's execution. The live sets of this block's outlinks have already been
   * set. If the block is now a no-op (e.g. because it sets a register that is not live on its
   * outlink) it can instead return REMOVE.
   *
   * <p>The default implementation unions the live sets from each outlink, removes any register that
   * is set by the block, and adds any registers referenced by the block's inputs. It always returns
   * DONE.
   */
  PropagationResult updateLive() {
    Bits.Builder live = cb().scratchBits;
    live.clearAll();
    if (this instanceof NonTerminal nonTerminal) {
      nonTerminal.next.getLive(live);
      if (this instanceof Split split) {
        split.alternate.getLive(live);
      }
    }
    forEachModifiedRegister(lhs -> live.clear(lhs.index));
    int numInputs = numInputs();
    for (int i = 0; i < numInputs; i++) {
      input(i).getLive(true, live);
    }
    this.live = live.build();
    return PropagationResult.DONE;
  }

  /** Returns the registers that are live at the beginning of this block's execution. */
  public Bits live() {
    // During BUILDING and OPTIMIZING the live set is incomplete (it doesn't include the extraLive
    // of our containing loops).
    assert zone().cb.phase().compareTo(CodeBuilder.Phase.FINALIZING) >= 0;
    return live;
  }

  /**
   * Sets the bits in {@code builder} that correspond to registers for which {@link #isLive} would
   * return true.
   */
  public void getLive(Bits.Builder builder, Zone originZone) {
    Bits.Op.UNION.into(builder, live);
    for (Loop loop = containingLoop();
        loop != null && (originZone == null || !loop.contains(originZone));
        loop = loop.nestedIn()) {
      Bits.Op.UNION.into(builder, loop.extraLive());
    }
  }

  /**
   * Called when the given register has been set to the given value immediately before this block.
   * If this block has a single occurrence of {@code register} in its input values, and the register
   * is not live on any of this block's outlinks, it may replace the occurrence of {@code register}
   * with {@code value} and return {@link SubstitutionOutcome#YES}. If this block has no occurrences
   * of {@code register} in its input values, has no side effects that might change the value of
   * {@code value}, and has only a single outlink, may return {@link
   * SubstitutionOutcome#KEEP_TRYING}. Otherwise must return {@link SubstitutionOutcome#NO}.
   */
  protected SubstitutionOutcome trySubstitute(Register register, CodeValue value) {
    // Putting a default method here is handy for block types (such as Initial) where it will never
    // be called anyway.
    // Enumerate the block types that currently are expected to fall through to here in case we
    // forget to add this method to a new subclass.
    assert this instanceof Loop.BackRef
        || this instanceof SetBlock.WithCatch
        || this instanceof ThrowBlock;
    return SubstitutionOutcome.NO;
  }

  /** The possible return values of {@link #trySubstitute} and {@link CodeValue#couldSubstitute}. */
  protected enum SubstitutionOutcome {
    /**
     * If returned from {@link #trySubstitute} indicates that there was only one use of the register
     * and it has now been replaced, so the SetBlock for the register can be removed.
     *
     * <p>If returned from {@link CodeValue#couldSubstitute} indicates that there is exactly one
     * occurrence of the register in this CodeValue and it could be safely replaced by the given
     * value.
     *
     * <p>(Note that we don't consider substitution if the register occurs more than once, because
     * we don't know how expensive it is to compute the value (which is always an Op.Result). Even
     * for simple cases, duplicating the sequence of byte codes to compute value will almost always
     * result in as much or more code than storing the result and retrieving it.)
     */
    YES,

    /**
     * Indicates that substitution is not feasible, e.g. because there are multiple uses of the
     * register or because an intervening side effect might change the evaluation of value.
     */
    NO,

    /**
     * If returned from {@link #trySubstitute} indicates that this block does not use register, but
     * does not do anything that might affect its value and is a non-Split NonTerminal, so the
     * caller may consider calling {@link #trySubstitute} on the following block.
     *
     * <p>If returned from {@link CodeValue#couldSubstitute} indicates that there are no occurrences
     * of register in this CodeValue and no calls to side-effecting Ops.
     */
    KEEP_TRYING;

    /**
     * If either argument is KEEP_TRYING, returns the other argument; otherwise returns NO. Used to
     * combine the results of {@link CodeValue#couldSubstitute} on two separate values. (Note that
     * {@code YES.and(YES)} is {@code NO}, since that would indicate that there are two uses of the
     * register.)
     */
    public SubstitutionOutcome combine(SubstitutionOutcome other) {
      if (this == KEEP_TRYING) {
        return other;
      } else if (other == KEEP_TRYING) {
        return this;
      } else {
        return NO;
      }
    }
  }

  /**
   * Returns true if the specified register is live at the beginning of this block's execution.
   *
   * <p>If {@code originZone} is non-null and this block and {@code originZone} are contained in the
   * same loop, may return false for registers that are unchanged in that loop even if they are
   * live. (This enables us to determine the result without having done a global analysis of the
   * program.)
   */
  boolean isLive(int registerIndex, Zone originZone) {
    if (live.test(registerIndex)) {
      return true;
    }
    for (Loop loop = containingLoop();
        loop != null && (originZone == null || !loop.contains(originZone));
        loop = loop.nestedIn()) {
      if (loop.extraLive().test(registerIndex)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns a function that, given a register index, returns the union of the infos for that
   * register across all of this block's inlinks. May return a Register as an info.
   */
  IntFunction<ValueInfo> inlinkRegisterInfosUnresolved() {
    if (!inLink.hasSibling()) {
      // If we've only got one inlink no union is needed
      return inLink.info.registers();
    } else {
      // Do the union lazily, just for the registers that are requested
      return i -> getInLinkInfo(i, false);
    }
  }

  /**
   * Gets the info for the given register from each of this blocks's inLinks and unions the results;
   * returns null if any of those calls returns null. If {@code resolveRegisters} is true, will not
   * return a Register as an info.
   */
  ValueInfo getInLinkInfo(int index, boolean resolveRegisters) {
    return super.getInLinkInfo(index, cb().binaryOps, resolveRegisters);
  }

  @Override
  final void runReverseProp(boolean incremental) {
    // If this block was deleted while it was queued we just ignore it
    if (index < 0) {
      return;
    }
    Bits prevLive = live;
    live = null;
    switch (updateLive()) {
      case DONE:
        if (this.live.equals(prevLive)) {
          // If they're equal but not identical it doesn't matter which one we keep, but the older
          // version might have more pointers to it than the new one, so restore it in the hopes
          // that we can save a little memory.
          this.live = prevLive;
          if (incremental) {
            return;
          }
        }
        checkLiveInlinks();
        break;
      case REMOVE:
        if (hasInLink()) {
          checkLiveInlinks();
          cb().removeNoOp(this);
        } else {
          cb().remove(this);
        }
        break;
      case SKIP:
        break;
    }
  }

  /**
   * Queue the blocks immediately preceding this one for their own checkLive() call, except that if
   * one of those blocks is a BackRef (i.e. we're at the start of a loop) queue the Loop processor
   * instead of the BackRef.
   */
  private void checkLiveInlinks() {
    forEachInLink(
        link -> {
          Zone.Ordered toQueue = link.origin;
          if (toQueue instanceof BackRef) {
            toQueue = ((BackRef) toQueue).loop().processor;
          }
          cb().setReversePropNeeded(toQueue);
        });
  }

  /** A subclass for Blocks with no outlinks (i.e. {@link ReturnBlock} or {@link ThrowBlock}). */
  public abstract static class Terminal extends Block {
    @Override
    public String linksToString(CodeBuilder.PrintOptions options) {
      return "";
    }
  }

  /** A subclass for Blocks with at least one outlink. */
  public abstract static class NonTerminal extends Block {
    /**
     * A Link to the block following this one.
     *
     * <p>If this is a {@link Split}, {@code next} will be followed in the "normal" case, e.g. if
     * the test succeeds (for a {@link TestBlock}) or the expressions doesn't throw an exception
     * (for a {@link SetBlock.WithCatch}).
     */
    public final Link next = new Link(this);

    @Override
    void setNext(FutureBlock links) {
      if (next.target() == null) {
        next.setTarget(links);
      }
    }

    /**
     * Called by the default implementation of {@link #runForwardProp} after the LinkInfo on this
     * block's outlink(s) has been initialized to match those of its inlinks. Should change the
     * outlink info to reflect the results of executing this block; may also use that info to
     * simplify CodeValues and/or redirect some or all of the block's inlinks.
     *
     * <p>The default implementation throws an AssertionError; subclasses must override either this
     * method or {@link #runForwardProp}.
     */
    protected PropagationResult updateInfo() {
      throw new AssertionError();
    }

    /**
     * A convenience method for {@link #updateInfo} implementations that want to replace this block
     * with another (presumably simpler) block. Initializes {@code newBlock}'s zone, order, inlinks,
     * and outlinks, and adds it to the graph. Returns {@link PropagationResult#REMOVE}, which is
     * what @link #updateInfo} should return after calling this.
     */
    protected final PropagationResult replaceWith(NonTerminal newBlock) {
      newBlock.setZone(zone());
      newBlock.setOrder(order());
      newBlock.next.setTarget(next.target());
      if (newBlock instanceof Split split) {
        split.alternate.setTarget(((Split) this).alternate.target());
      }
      cb().add(newBlock);
      moveAllInLinks(newBlock);
      cb().setForwardPropNeeded(newBlock);
      return PropagationResult.REMOVE;
    }

    /**
     * Returns true if this block is simple enough to be considered for duplication by {@link
     * SimpleDuplicator}. Blocks that return true must not be Splits, and should emit few or no byte
     * codes.
     */
    boolean isSimple() {
      return false;
    }

    /**
     * Returns a new block with the same behavior as this; only called on blocks that return true
     * from {@link #isSimple}. The result will have been fully initialized and added to the
     * CodeBuilder with the same zone and order as this block, but not yet linked to any other
     * blocks.
     */
    final NonTerminal duplicate() {
      assert isSimple();
      NonTerminal result = duplicateImpl();
      // It's OK for the duplicate to have the same zone and order as the original, as long as
      // neither of them links to the other.
      result.setZone(zone());
      result.setOrder(order());
      result.src = src;
      cb().add(result);
      ++cb().numDuped;
      return result;
    }

    /**
     * Returns a new block with the same behavior as this; only called on blocks that return true
     * from {@link #isSimple}.
     *
     * <p>This method is only responsible for creating the new block and initializing its
     * subclass-specific fields; generic fields such as {@code next} and {@code live} will be
     * initialized by the caller.
     */
    NonTerminal duplicateImpl() {
      throw new AssertionError();
    }

    /**
     * Returns true if the given block is equivalent to this; only called on blocks that return true
     * from {@link #isSimple}.
     */
    boolean isDuplicate(Block other) {
      throw new AssertionError();
    }

    /**
     * Given info for register values before this block is executed, returns info for their values
     * after it is executed; should match the behavior of {@link #updateInfo} but may be called
     * speculatively. Only called on blocks that return true from {@link #isSimple}.
     */
    IntFunction<ValueInfo> simpleInfos(IntFunction<ValueInfo> fromInlink) {
      throw new AssertionError();
    }

    /**
     * Initializes the outlink info to match the inlink info, then calls {@link #updateInfo} to make
     * whatever adjustments are appropriate for this Block. If the resulting infos are changed or
     * {@code incremental} is false, marks the outlink target(s) as needing propagation.
     */
    @Override
    void runForwardProp(boolean incremental) {
      assert !(this instanceof Loop.BackRef);
      if (hasMultipleInlinks() && isSimple()) {
        // Merging infos from multiple inlinks can hide the possibility that one of them could
        // skip an upcoming test; let SimpleDuplicator check for that before we go any further
        // (if it decides to redirect one or more of our inlinks we'll just continue with the
        // ones that are left -- it never takes the last one).
        SimpleDuplicator.lookAheadForTestBlock(this);
      }
      CodeBuilder cb = cb();
      next.info.swapContents(cb.scratchInfo);
      unionInlinkInfo(next.info, cb.unioner, containingLoop());
      Link alternate = (this instanceof Split split) ? split.alternate : null;
      if (alternate != null) {
        alternate.info.swapContents(cb.scratchInfo2);
        alternate.info.setFrom(next.info);
      }
      switch (updateInfo()) {
        case DONE:
          cb.linkInfoUpdated(next, cb.scratchInfo, incremental);
          if (alternate != null) {
            cb.linkInfoUpdated(alternate, cb.scratchInfo2, incremental);
          }
          break;
        case REMOVE:
          cb.removeNoOp(this);
          break;
        case SKIP:
          if (index() >= 0) {
            // If this block hasn't been deleted its outlink info(s) should have been cleared; that
            // ensures that when we return to it we won't be confused about whether or not those
            // infos have changed.
            assert !next.info.isValid() && (alternate == null || !alternate.info.isValid());
          }
          break;
      }
    }

    @Override
    public String linksToString(CodeBuilder.PrintOptions options) {
      // Subclasses with more than one outlink must override this implementation
      assert !(this instanceof Split);
      return options.isLinkToNextBlock(next) ? ";" : ("; " + next.toString(options));
    }
  }

  /**
   * A Split block has two outlinks, "next" and "alternate"; the emitted code will choose one or the
   * other path.
   *
   * <p>The primary subclass of Split is {@link TestBlock}, which just chooses {@code next} if a
   * specified condition is true and {@code alternate} otherwise.
   */
  public abstract static class Split extends NonTerminal {
    /**
     * A Link to an alternative block.
     *
     * <p>This link will be followed if the test fails (for a {@link TestBlock}) or the expression
     * throws an exception (for a {@link SetBlock.WithCatch}).
     */
    public final Link alternate = new Link(this);

    @Override
    void setNext(FutureBlock links) {
      if (next.target() == null) {
        Preconditions.checkState(alternate.target() != null);
        next.setTarget(links);
      } else if (alternate.target() == null) {
        alternate.setTarget(links);
      }
    }
  }

  /** A trivial NonTerminal that serves as the root of the Block graph. */
  public static class Initial extends NonTerminal {
    Initial() {
      // For most blocks, runForwardProp() ensures that the outlink info is properly initialized
      // from the inlink infos, but the initial block has no inlinks and never runs forward
      // propagation so we need to initialize it ourselves.
      next.info.clear(true);
    }

    /** Updates the info on our outlink to reflect the argument's initial value. */
    void addArg(Register arg, ValueInfo info) {
      next.info.updateInfo(arg, info);
    }

    @Override
    public void forEachModifiedRegister(Consumer<Register> consumer) {
      // We treat the Initial block as setting the values of the arguments
      CodeBuilder cb = cb();
      IntStream.range(0, cb.numArgs()).forEach(i -> consumer.accept(cb.register(i)));
    }

    @Override
    public String toString(CodeBuilder.PrintOptions options) {
      return "initial";
    }
  }
}
