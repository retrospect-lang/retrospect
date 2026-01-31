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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.objectweb.asm.Opcodes;
import org.retrolang.code.*;
import org.retrolang.code.Block.NonTerminal;
import org.retrolang.code.Block.Split;
import org.retrolang.code.Block.Terminal;
import org.retrolang.code.CodeValue.Const;
import org.retrolang.code.Loop.BackRef;
import org.retrolang.code.Op.OpEmit;
import org.retrolang.code.Op.Result;
import org.retrolang.code.TestBlock.IsEq;
import org.retrolang.code.ValueInfo.BinaryOps;
import org.retrolang.util.Bits;
import org.retrolang.util.SmallIntMap;

/**
 * A specialized subclass of CodeBuilder that does an additional analysis pass after optimizations
 * and the first step of register assignment are complete, before the second step of register
 * assignment.
 *
 * <p>See docs/rc_code_builder.md for details.
 */
public class RcCodeBuilder extends CodeBuilder {

  /**
   * A BlockExtra for each Block that existed when our analysis started, ordered by the natural
   * order of the corresponding blocks.
   */
  private BlockExtra[] blockExtras;

  /**
   * An array with the same elements as {@link #blockExtras}, but indexed by {@link Block#index()}.
   * May have nulls at the end if blocks have been deleted.
   */
  private BlockExtra[] blockExtraByBlockIndex;

  /** A LoopExtra for each Loop, indexed by {@link Loop#index()}. */
  private LoopExtra[] loopExtraByLoopIndex;

  /**
   * Each register that may contain a ref-counted object (i.e. whose type is a subclass or
   * superclass of RefCounted, or an array) is assigned a distinct "tracked register index" which we
   * use in place of the register index to keep Bits and SmallIntMaps more compact.
   *
   * <p>This array maps {@link Register#index} to the corresponding tracked register index, or -1
   * for registers that are never live or cannot contain a ref-counted object (including all numeric
   * registers).
   */
  private int[] registerToTri;

  /**
   * Maps each valid "tracked register index" to the corresponding {@link Register#index}; see
   * {@link #registerToTri} for what that means.
   */
  private int[] triToRegister;

  RcCodeBuilder(BinaryOps binaryOps) {
    super(binaryOps);
  }

  /** Returns the BlockExtra corresponding to the given block. */
  BlockExtra blockExtra(Block b) {
    return blockExtraByBlockIndex[b.index()];
  }

  /** Returns the LoopExtra corresponding to the given loop. */
  LoopExtra loopExtra(Loop loop) {
    return loopExtraByLoopIndex[loop.index()];
  }

  /** Returns the TRI for the given register, or -1 if {@code r} is not tracked. */
  private int registerToTri(Register r) {
    return registerToTri[r.index];
  }

  /** Returns the register with the given TRI. */
  private Register triToRegister(int tri) {
    return register(triToRegister[tri]);
  }

  /** If the register with index {@code ri} is tracked, adds its TRI to {@code builder}. */
  private void setTriFromRegisterIndex(Bits.Builder builder, int ri) {
    int tri = registerToTri[ri];
    if (tri >= 0) {
      builder.set(tri);
    }
  }

  /** Given a set of register indices, adds the corresponding TRIs to {@code builder}. */
  private void setTriFromRegisterIndices(Bits.Builder builder, Bits registers) {
    registers.forEach(ri -> setTriFromRegisterIndex(builder, ri));
  }

  /** Given a set of TRIs, adds the corresponding register indices to {@code builder}. */
  private void setRegisterIndicesFromTris(Bits.Builder builder, Bits tris) {
    tris.forEach(tri -> builder.set(triToRegister[tri]));
  }

  @Override
  protected void finalizeBlocks(RegisterAssigner assigner) {
    // Remove all no-ops *except* BackRefs (i.e. RegisterInfoBlocks, and SetBlocks that have been
    // made redundant by clever register assignment)
    removeNoOps(b -> !(b instanceof BackRef) && b.isNoOp());

    // Do all the work
    analyzeRefCounts(assigner);
    // System.out.format("Refcount fixups complete:\n%s", printBlocks());

    // Remove the BackRefs that are the only remaining no-op blocks
    removeNoOps(b -> (b instanceof BackRef));
  }

  @Override
  protected void blockRenumbered(Block b, int prevIndex) {
    // If blocks are removed while we're part way through our analysis we need to keep our
    // parallel array in sync.
    if (blockExtraByBlockIndex != null && !(b instanceof RefFixBlock)) {
      BlockExtra extra = blockExtraByBlockIndex[prevIndex];
      assert extra != null;
      blockExtraByBlockIndex[prevIndex] = null;
      if (b.index() >= 0) {
        // This block has been moved into the hole left by another block's removal
        assert blockExtraByBlockIndex[b.index()] == null;
        blockExtraByBlockIndex[b.index()] = extra;
      }
    }
  }

  @Override
  protected String printBlock(Block b, PrintOptions options) {
    // If we've analyzed this block, annotate it with the results of our analysis
    String s = super.printBlock(b, options);
    if (blockExtras == null || b instanceof RefFixBlock || options.useJvmLocals()) {
      return s;
    }
    // s = String.format("%s (%s:%s)", s, b.zone(), b.order());
    BlockExtra extra = blockExtra(b);
    if (extra.initialDependencies != null) {
      s = String.format("d%s %s", depsToString(extra.initialDependencies, options), s);
    }
    if (extra.initialCounted != null) {
      s = String.format("c%s %s", trsToString(extra.initialCounted, options), s);
    }
    if (extra.mustBeCounted != null && !extra.mustBeCounted.isEmpty()) {
      s = String.format("%s (mbc %s)", s, trsToString(extra.mustBeCounted, options));
    }
    if (extra.noDeps != null && !extra.noDeps.isEmpty()) {
      s = String.format("%s (nodeps %s)", s, depsToString(extra.noDeps, options));
    }
    return s;
  }

  /** Run the full analysis. */
  private void analyzeRefCounts(RegisterAssigner assigner) {
    // First create a BlockExtra for each block, and store them in the blockExtraByBlockIndex array.
    int n = numBlocks();
    blockExtraByBlockIndex = new BlockExtra[n];
    for (int i = 0; i < n; i++) {
      Block b = block(i);
      // During later parts of the analysis we may insert blocks between existing blocks;
      // to make that easy we multiply the initial Block.order values by 2 to leave enough room for
      // our insertions.
      b.expandOrder();
      blockExtraByBlockIndex[i] = new BlockExtra(b);
    }
    // Make a copy of that array, and sort it to match the block ordering.
    blockExtras = Arrays.copyOf(blockExtraByBlockIndex, n);
    Arrays.sort(blockExtras, (x1, x2) -> x1.block.compareTo(x2.block));

    // Create a LoopExtra for each Loop
    loopExtraByLoopIndex = new LoopExtra[numLoops()];
    Arrays.setAll(loopExtraByLoopIndex, i -> new LoopExtra());

    // We may add new references to the TState register (via calls to dropRef) during the
    // EMITTING phase, which could cause problems if the second stage of register assignment
    // had aliased it with another register.  The easy way to avoid that is to just tell the
    // assigner that the TState register conflicts with every other register.
    Bits.Builder allRegisters = new Bits.Builder();
    allRegisters.setToRange(0, numRegisters() - 1);
    allRegisters.clear(CodeGen.TSTATE_REGISTER_INDEX);
    assigner.addConflicts(register(CodeGen.TSTATE_REGISTER_INDEX), allRegisters);

    // All the analysis happens in 3 passes over the blocks.

    new SetupPass().run();
    new BackwardPass().run();
    new ForwardPass(assigner).run();
  }

  /** Returns true if a register with the given type may store ref-counted objects. */
  private static boolean isCountable(Class<?> type) {
    if (type.isPrimitive()) {
      return false;
    } else if (type == Object.class || type == byte[].class || type == Object[].class) {
      // Object is the only superclass of RefCounted.
      // byte[] and Object[] are (potentially) counted, even though they don't have a reference
      // count field.
      return true;
    } else if (type == Value.class) {
      // Value is the only interface we use that a RefCounted subclass might implement.
      return true;
    } else {
      // True for RefCounted and its subclasses, false for everything else.
      return RefCounted.class.isAssignableFrom(type);
    }
  }

  /**
   * The first pass over the blocks
   *
   * <ul>
   *   <li>identifies the tracked registers and assigns a TRI to each one;
   *   <li>computes {@link BlockExtra#assignedTrsAfter} and {@link BlockExtra#liveTrs} for each
   *       block; and
   *   <li>computes {@link LoopExtra#loopTrs} for each loop.
   * </ul>
   */
  private class SetupPass {
    /** Scratch for use by {@link #run} and {@link #process}. */
    private final Bits.Builder scratch = new Bits.Builder();

    /**
     * Scratch for use by {@link #assignedTrsAfter} and {@link #liveTrs} (both are called from
     * {@link #process}).
     */
    private final Bits.Builder scratch2 = new Bits.Builder();

    void run() {
      // Assign a TRI to each tracked register that hasn't been optimized away.
      registerToTri = new int[numRegisters()];
      int numTracked = 0;
      for (int i = 0; i < registerToTri.length; i++) {
        Register register = register(i);
        if (!register.hasJvmLocal() || !isCountable(register.type())) {
          // Optimized away or not ref-counted
          registerToTri[i] = -1;
        } else {
          // If the register assigner aliased this register to another one, use its TRI; otherwise
          // allocate a new TRI.
          int canonical = register.jvmLocal();
          if (canonical != i) {
            // Aliases always have lower indices, so we should already have assigned this one.
            assert canonical < i && registerToTri[canonical] >= 0;
            registerToTri[i] = registerToTri[canonical];
          } else {
            registerToTri[i] = numTracked++;
          }
        }
      }

      // Build the reverse mapping.
      triToRegister = new int[numTracked];
      // Running this loop backwards is an easy way to ensure that we end up with the canonical
      // representative when a tracked register has aliases.
      for (int i = numRegisters() - 1; i >= 0; i--) {
        if (registerToTri[i] >= 0) {
          triToRegister[registerToTri[i]] = i;
        }
      }

      // Compute loopTrs (the TRIs for the loop's tracked registers) for each loop.
      for (int i = 0; i < loopExtraByLoopIndex.length; i++) {
        Loop loop = loop(i);
        scratch.clearAll();
        setTriFromRegisterIndices(scratch, loop.registers());
        loopExtraByLoopIndex[i].loopTrs = scratch.build();
      }

      // Initialize BlockExtras, in order.
      Arrays.stream(blockExtras).forEach(this::process);
    }

    /**
     * Computes {@link BlockExtra#assignedTrsAfter} and {@link BlockExtra#liveTrs} for the given
     * block. Assumes that all of the block's inlinks (other than BackRefs) have already been
     * processed.
     */
    private void process(BlockExtra extra) {
      Block b = extra.block;
      // Start with all the registers that were assigned before this block was executed...
      if (b.hasInLink()) {
        // If a block has multiple inlinks, only registers that are assigned on every inlink count.
        scratch.initializeForIntersection();
        b.forEachInLink(
            link -> Bits.Op.INTERSECTION.into(scratch, assignedTrsAfter(link.origin, false)));
      } else {
        assert b instanceof Block.Initial;
        scratch.clearAll();
      }
      // ... and add any registers that are modified by this block.
      b.forEachModifiedRegister(r -> setTriFromRegisterIndex(scratch, r.index));
      extra.assignedTrsAfter = scratch.build();

      // Convert the block's live set to TRIs; include the extraLive registers for any enclosing
      // loops.
      Loop loop = b.containingLoop();
      scratch.setAll(loop == null ? Bits.EMPTY : liveTrs(loop));
      setTriFromRegisterIndices(scratch, b.live());
      extra.liveTrs = scratch.build();
    }

    /**
     * Returns {@link BlockExtra#assignedTrsAfter} for the given block, unless it is a BackRef.
     *
     * <p>If {@code src} is a BackRef its {@code assignedTrsAfter} has not yet been computed, so
     * instead we return the {@link LoopExtra#assignedTrsBefore} for the loop (computing it if
     * necessary).
     */
    private Bits assignedTrsAfter(Block src, boolean recursive) {
      BlockExtra srcExtra = blockExtra(src);
      if (srcExtra.assignedTrsAfter != null) {
        // We've already computed this block's assignedTrsAfter
        return srcExtra.assignedTrsAfter;
      }
      // If one of our inlinks hasn't been visited yet, the inLink's src must be a BackRef *and* all
      // of the loop's entry sources must have already been visited.
      Loop loop = ((BackRef) src).loop();
      LoopExtra loopExtra = loopExtra(loop);
      // If this is the first BackRef we've seen for this loop we'll need to compute its
      // assignedTrsBefore
      if (loopExtra.assignedTrsBefore == null) {
        // I don't think that we will ever end up here on a recursive call (one of a loop's
        // entries is a backRef from an enclosing loop?), but if we do we can't use scratch2
        // without messing up our caller, so make a new Bits.Builder.
        Bits.Builder builder = recursive ? new Bits.Builder() : scratch2;
        builder.initializeForIntersection();
        loop.entries()
            .forEach(
                entry -> Bits.Op.INTERSECTION.into(builder, assignedTrsAfter(entry.origin, true)));
        loopExtra.assignedTrsBefore = builder.build();
      }
      return loopExtra.assignedTrsBefore;
    }

    /**
     * If {@code loop} is null, returns EMPTY; otherwise returns the TRIs for the {@link
     * Loop#extraLive()} of {@code loop} and any enclosing loops.
     */
    private Bits liveTrs(Loop loop) {
      if (loop == null) {
        return Bits.EMPTY;
      }
      LoopExtra loopExtra = loopExtra(loop);
      if (loopExtra.liveTrs == null) {
        // Make the recursive call before we start using scratch2
        Bits fromEnclosing = liveTrs(loop.nestedIn());
        scratch2.setAll(fromEnclosing);
        setTriFromRegisterIndices(scratch2, loop.extraLive());
        loopExtra.liveTrs = scratch2.build();
      }
      return loopExtra.liveTrs;
    }
  }

  /** Returns {@code bits} unless it is empty, otherwise null. */
  private static Bits dropEmpty(Bits bits) {
    return bits.isEmpty() ? null : bits;
  }

  /**
   * The second pass over the blocks computes {@link BlockExtra#dropped}, {@link
   * BlockExtra#mustBeCounted}, and {@link BlockExtra#noDeps} for each block.
   */
  private class BackwardPass {
    // The BlockExtra for the block we are currently processing
    private BlockExtra extra;

    // Scratch state maintained during the processing of a block; always reset at the start of
    // process().
    private final Bits.Builder liveTrsBuilder = new Bits.Builder();
    private final Bits.Builder droppedBuilder = new Bits.Builder();
    private final Bits.Builder mustBeCountedBuilder = new Bits.Builder();
    private final SmallIntMap.Builder<Bits> noDepsBuilder = new SmallIntMap.Builder<>();

    // Scratch state used within a single method.
    private final Bits.Builder scratch = new Bits.Builder();
    private final Bits.Builder scratch2 = new Bits.Builder();

    void run() {
      // This pass processes blocks in reverse order.
      for (int i = blockExtras.length - 1; i >= 0; --i) {
        process(blockExtras[i]);
      }
    }

    /**
     * Computes {@link BlockExtra#dropped}, {@link BlockExtra#mustBeCounted}, and {@link
     * BlockExtra#noDeps} for the given block. Assumes that all of the block's outlinks have already
     * been processed, unless it is a BackRef.
     */
    private void process(BlockExtra extra) {
      this.extra = extra;
      Block b = extra.block;
      if (b instanceof BackRef) {
        processBackRef();
        return;
      }
      // Initialize liveTrsScratch to the registers that are live after this block,
      // mustBeCountedScratch to the registers that must be counted after this block, and
      // noDepsBuilder to the noDeps map after this block.
      if (b instanceof Terminal) {
        liveTrsBuilder.clearAll();
        mustBeCountedBuilder.clearAll();
        noDepsBuilder.clear();
        extra.dropped = extra.liveTrs;
      } else {
        BlockExtra nextExtra = blockExtra(((NonTerminal) b).next.targetBlock());
        liveTrsBuilder.setAll(nextExtra.liveTrs);
        mustBeCountedBuilder.setAll(nextExtra.mustBeCounted);
        noDepsBuilder.setFrom(nextExtra.noDeps);
        if (b instanceof Split split) {
          BlockExtra alternateExtra = blockExtra(split.alternate.targetBlock());
          Bits.Op.UNION.into(liveTrsBuilder, alternateExtra.liveTrs);
          // combineNoDeps updates both noDepsBuilder and mustBeCountedScratch
          combineNoDeps(alternateExtra.noDeps, alternateExtra.mustBeCounted);
        }
      }
      // If the last thing this block does is modify registers (i.e. it's a SetBlock or similar),
      // make the corresponding updates first (since we're propagating information backwards).
      // If register modifications may happen before some reads (i.e. StackEntryBuilder) we'll
      // do this later.
      if (!b.mayReadAfterWrite()) {
        b.forEachModifiedRegister(this::updateNoDepsForWrite);
      }

      // Now look at this block's inputs, checking for Ops with RC.In arguments.  This is a little
      // tricky since we need to know what's live at the time the Op is called, but what's live may
      // change during execution of the expressions.  For example, if the first argument to mutateOp
      // is RC.In and the arguments to compareOp are not, and assuming that there are no
      // subsequent uses of x, then in
      //    y = compareOp(x, mutateOp(x, z))
      // the final use of x is *not* RC.In (since the mutateOp call will happen first), while in
      //    y = mutateOp(x, compareOp(x, z))
      // the final use of x *is* RC.In (the compareOp will have already returned before mutateOp is
      // called).
      //
      // Tracked registers whose last use is as an RC.In arg will be added to droppedBuilder (their
      // reference will be dropped by the emitted code).
      droppedBuilder.clearAll();
      if (b instanceof StackEntryBlock seb) {
        // StackEntryBlocks get special handling; in addition to updating liveTrsBuilder and
        // droppedBuilder, we construct a list of tracked inputs that are live after the SEB; those
        // registers will need an addRef.
        ImmutableList.Builder<Register> addRef = new ImmutableList.Builder<>();
        // scratch will contain the TRIs of registers we've already seen.
        // (The block's inputs may contain duplicates, but StackEntryBlock.emit() will only save
        // each register once.)
        scratch.clearAll();
        int numInputs = b.numInputs();
        for (int i = 0; i < numInputs; i++) {
          // All of a StackEntryBlock's inputs are registers.
          Register r = (Register) b.input(i);
          int tri = registerToTri(r);
          if (tri < 0 || !scratch.set(tri)) {
            continue;
          }
          // Any tracked register saved by the StackEntryBlock should be added to the live set,
          // and if it isn't live following this block we can add it to the "doesn't need an addRef"
          // set.
          if (liveTrsBuilder.set(tri)) {
            // Last reference to this register, so we'll store without an addRef (thereby dropping
            // its refCount).
            droppedBuilder.set(tri);
          } else {
            // Not the last reference, so we need an addRef
            addRef.add(r);
          }
        }
        seb.setAddRef(addRef.build());
        if (!droppedBuilder.isEmpty()) {
          addNoDeps(droppedBuilder);
        }
      } else {
        // Handles all block types except StackEntryBlock.
        // If any of our input values are registers, those registers are live until the end.
        int numInputs = b.numInputs();
        for (int i = 0; i < numInputs; i++) {
          if (b.input(i) instanceof Register r) {
            setTriFromRegisterIndex(liveTrsBuilder, r.index);
          }
        }
        // Now process each of the Op.Result inputs, in the reverse of the order they'll be
        // evaluated.
        for (int i = numInputs - 1; i >= 0; --i) {
          if (b.input(i) instanceof Op.Result opResult) {
            b.setInput(i, processInput(opResult));
          }
        }
      }
      // If this is a write-before-read block we do these updates last (see comment above).
      if (b.mayReadAfterWrite()) {
        b.forEachModifiedRegister(this::updateNoDepsForWrite);
      }

      // We're done processing this block.  At this point our liveTrs should match the ones we
      // previously computed, and we can save the other info we've derived.
      assert extra.liveTrs.equals(liveTrsBuilder.build());
      extra.dropped = droppedBuilder.build();
      assert !mustBeCountedBuilder.testAny(extra.dropped);
      Bits.Op.UNION.into(mustBeCountedBuilder, extra.dropped);
      extra.mustBeCounted = mustBeCountedBuilder.build();
      // We only need to keep noDeps entries for registers that are live not in mustBeCounted
      Bits.Op.DIFFERENCE.into(liveTrsBuilder, extra.mustBeCounted);
      cleanUpNoDeps(liveTrsBuilder.build());
      extra.noDeps = buildNoDeps();
    }

    /**
     * The current block writes to the given register; update noDepsBuilder and mustBeCountedBuilder
     * accordingly.
     */
    private void updateNoDepsForWrite(Register lhs) {
      int tri = registerToTri(lhs);
      if (tri < 0) {
        return;
      }
      liveTrsBuilder.clear(tri);
      // If the modified register had a previous value, no register still live after the write is
      // allowed to depend on it.
      // We *could* just call noDepsBuilder.put(tri, liveTrsScratch.build()), but we choose to be
      // more selective:
      //  * if (as is often the case) this is the first assignment to tri, we don't need to put
      //    anything in noDeps (since nothing could have been dependent on it)
      //  * we don't need to put r in the noDeps value if r is already in mustBeCounted
      Bits noDepsEntry = null;
      Block b = extra.block;
      if (b.anyInlink(link -> blockExtra(link.origin).assignedTrsAfter.test(tri))) {
        scratch.setAll(liveTrsBuilder);
        Bits.Op.DIFFERENCE.into(scratch, mustBeCountedBuilder);
        // We don't need to record that this register shouldn't depend on itself
        scratch.clear(tri);
        // There's no point in storing an empty set in noDeps
        noDepsEntry = dropEmpty(scratch.build());
      }
      noDepsBuilder.put(tri, noDepsEntry);

      // If the register being set by this instruction was previously in mustBeCounted we can take
      // it out (that applies to the value we just set, not to any previously-set value).
      // But if it was previously in mustBeCounted *and* it is just being copied from another
      // register, then add the rhs to mustBeCounted (in the hopes that we can just take its
      // refcount).
      if (mustBeCountedBuilder.clear(tri) && b instanceof SetBlock) {
        CodeValue rhs = b.input(0);
        if (rhs instanceof Register r) {
          mustBeCountedBuilder.set(registerToTri(r));
        }
      }
    }

    /**
     * Update noDepsBuilder to reflect the constraints introduced by an operation that drops the
     * final refCount on the tracked registers in {@code dropped} (which is either {@link
     * #droppedBuilder} or a subset of it).
     */
    void addNoDeps(Bits.Builder dropped) {
      // dropped should already be included in droppedBuilder (which may contain other registers,
      // if other @RC.In ops have already been processed by this block).
      assert droppedBuilder.testAll(dropped);
      // The registers that must not depend on the dropped registers: live after this point, and
      // not already in mustBeCounted (or about to be added to it).
      scratch.setAll(liveTrsBuilder);
      Bits.Op.DIFFERENCE.into(scratch, mustBeCountedBuilder);
      Bits.Op.DIFFERENCE.into(scratch, droppedBuilder);
      Bits noDep = scratch.build();
      if (!noDep.isEmpty()) {
        for (int tri : dropped) {
          // There might be a left-over entry for this register from later uses of it, but if
          // so it should be safe to overwrite with the new entry.
          noDepsBuilder.put(tri, noDep);
        }
      }
    }

    /**
     * We can't process a BackRef using the same logic, since its next block hasn't yet been
     * processed. Instead we look at the exit link(s) from its loop, which will have all been
     * processed.
     */
    private void processBackRef() {
      Loop loop = ((BackRef) extra.block).loop();
      LoopExtra loopExtra = processLoop(loop);
      extra.dropped = Bits.EMPTY;
      extra.mustBeCounted = loopExtra.mustBeCounted;
      extra.noDeps = loopExtra.noDeps;
    }

    /**
     * Ensures that {@link LoopExtra#mustBeCounted} and {@link LoopExtra#noDeps} have been computed
     * for this loop.
     */
    private LoopExtra processLoop(Loop loop) {
      LoopExtra loopExtra = loopExtra(loop);
      // The first time we encounter a backRef for a loop we'll compute its mustBeCounted and
      // noDeps; if there are additional backRefs we can just reuse the previously-computed values.
      if (loopExtra.mustBeCounted == null) {
        if (loop.numExits() == 0) {
          // An infinite loop?  I don't think this should ever happen, but we can handle it.
          loopExtra.mustBeCounted = loopExtra.loopTrs;
          loopExtra.noDeps = SmallIntMap.empty();
        } else {
          // If there is a single loop exit we just use its mbc and noDeps; if there is more than
          // one, we combine them using the same algorithm we use for Split blocks.
          for (int i = 0; i < loop.numExits(); i++) {
            BlockExtra exit = blockExtra(loop.exit(i).targetBlock());
            if (i == 0) {
              mustBeCountedBuilder.setAll(exit.mustBeCounted);
              noDepsBuilder.setFrom(exit.noDeps);
            } else {
              combineNoDeps(exit.noDeps, exit.mustBeCounted);
            }
          }
          // The loop state registers must always be counted.
          Bits.Op.UNION.into(mustBeCountedBuilder, loopExtra.loopTrs);
          loopExtra.mustBeCounted = mustBeCountedBuilder.build();
          cleanUpNoDeps(Bits.Op.DIFFERENCE.apply(loopExtra.liveTrs, loopExtra.mustBeCounted));
          loopExtra.noDeps = buildLoopNoDeps(loop);
        }
      }
      return loopExtra;
    }

    /**
     * Does a final clean-up of {@link #noDepsBuilder} by eliminating registers that are no longer
     * live and redundancy with {@code mustBeCounted} (registers in mustBeCounted can't depend on
     * any other register, so needn't be in any noDeps value).
     */
    private void cleanUpNoDeps(Bits worthKeeping) {
      noDepsBuilder.updateEntries((k, v) -> dropEmpty(Bits.Op.INTERSECTION.apply(v, worthKeeping)));
    }

    /**
     * Returns a SmallIntMap built from {@link #noDepsBuilder} to be used as {@code
     * blockExtra(b).noDeps}.
     */
    private SmallIntMap<Bits> buildNoDeps() {
      // Since (we assume) noDeps will often be unchanged from one block to the next, we try to
      // avoid creating multiple copies of the same SmallIntMap by checking whether we can reuse
      // our successor's value before creating our own.
      Block b = extra.block;
      if (!noDepsBuilder.isEmpty() && b instanceof NonTerminal nonTerminal) {
        SmallIntMap<Bits> fromNext = blockExtra(nonTerminal.next.targetBlock()).noDeps;
        if (noDepsBuilder.equals(fromNext)) {
          return fromNext;
        }
        if (b instanceof Split split) {
          SmallIntMap<Bits> fromAlternate = blockExtra(split.alternate.targetBlock()).noDeps;
          if (noDepsBuilder.equals(fromAlternate)) {
            return fromAlternate;
          }
        }
      }
      return noDepsBuilder.build();
    }

    /**
     * Returns a SmallIntMap built from {@link #noDepsBuilder} to be used as {@code
     * loopExtra(loop).noDeps}.
     */
    private SmallIntMap<Bits> buildLoopNoDeps(Loop loop) {
      // This is the same idea as buildNoDeps, trying to reuse an existing SmallIntMap before
      // building another, but this time we check the loop exits rather than the block outlinks.
      if (!noDepsBuilder.isEmpty()) {
        for (int i = 0; i < loop.numExits(); i++) {
          SmallIntMap<Bits> exitNoDeps = blockExtra(loop.exit(i).targetBlock()).noDeps;
          if (noDepsBuilder.equals(exitNoDeps)) {
            return exitNoDeps;
          }
        }
      }
      return noDepsBuilder.build();
    }

    /**
     * Updates {@link #liveTrsBuilder}, {@link #mustBeCountedBuilder}, and {@link #noDepsBuilder} to
     * reflect the code that will be emitted by the given {@link Op.Result} (including the code
     * emitted for each of its arguments).
     *
     * <p>If there are no calls to Ops with @RC.In args, this just adds any tracked registers to
     * {@link #liveTrsBuilder}. If there are @RC.In args there are additional steps, depending on
     * the type of CodeValue provided for that argument:
     *
     * <ul>
     *   <li>If it is a constant, no update is needed (constants are always uncounted).
     *   <li>If it is a register and this the last use of the register, we add the register to
     *       {@link #mustBeCountedBuilder} and update {@link #noDepsBuilder} to ensure that no
     *       register that is live after this point depends on the @RC.In register.
     *   <li>If it is a register and this is not the last use of the register, we set the
     *       corresponding bit in the RcOpResult's argNeedsAddRef so that the emitted code will
     *       include an addRef to that register.
     *   <li>If it is a nested Op.Result for an @RC.Out Op, no update is needed.
     *   <li>If it is a nested Op.Result for an Op that is not @RC.Out, we set the corresponding bit
     *       in the RcOpResult's argNeedsAddRef.
     * </ul>
     *
     * <p>(There is one case that isn't covered above: the result of an @RC.Out Op passed as a
     * non-@RC.In argument. Correct code for that case would require saving that argument until
     * after the enclosing Op returns and then calling dropRef, but that's a little tricky and it's
     * easier to just prohibit that combination. Since @RC.Out Ops are always marked as having a
     * side effect, {@link Op.Result#couldSubstitute} will not introduce these.)
     */
    private CodeValue processInput(CodeValue input) {
      if (!(input instanceof Op.Result opResult)) {
        return input;
      }
      // First pass through the args: mark all Register args as live, and (if this is an RcOp)
      // figure out which args need an addRef
      Bits argIsRcIn = (opResult.op instanceof RcOp rcOp) ? rcOp.argIsRcIn : Bits.EMPTY;
      // scratch is the indices of @RC.In args that need an addRef before the Op
      // (because they're passed a register that will be used again)
      scratch.clearAll();
      // scratch2 is the TRIs of registers that are passed as @RC.In args without an addRef (because
      // it's their last use); these will be added to the block's dropped set
      scratch2.clearAll();

      boolean needsRecursion = false;

      for (int i = opResult.args.size() - 1; i >= 0; --i) {
        CodeValue arg = opResult.args.get(i);
        // If it's an @RC.In arg, start by assuming that it will need an addRef
        boolean needsAddRef = argIsRcIn.test(i);
        if (arg instanceof Register r) {
          int tri = registerToTri(r);
          // We update live for all Register args, @RC.In or not
          if (tri < 0) {
            // Not a tracked register
            assert !needsAddRef;
            continue;
          } else if (!liveTrsBuilder.set(tri)) {
            // It's still live after this call (so we need an addRef, if it's @RC.In)
          } else if (needsAddRef
              && IntStream.range(0, i).noneMatch(j -> opResult.args.get(j) == r)) {
            // It's an @RC.In arg *and* it's only used once; we don't need an addRef.
            // (We don't need to check args > i, since if it appeared there we would have already
            // set liveTrsBuilder and the "still live" check above would have caught it.)
            assertTrue(scratch2.set(tri));
            needsAddRef = false;
          }
        } else if (arg instanceof Op.Result argOpResult) {
          needsRecursion = true;
          // If the result of an @RC.Out Op is passed as an @RC.In arg, we don't need the addRef;
          // otherwise we do
          if (argOpResult.op instanceof RcOp rcOp && rcOp.resultIsRcOut) {
            assert needsAddRef;
            needsAddRef = false;
          }
        } else {
          assert arg instanceof Const;
          // Constants don't need addRefs or dropRefs
          continue;
        }
        if (needsAddRef) {
          scratch.set(i);
        }
      }
      Bits needAddRef = scratch.build();
      if (!scratch2.isEmpty()) {
        // There's at least one register being passed @RC.In without an addRef; those registers
        // should be added to this block's dropped set, and we create noDeps entries to ensure
        // that none of the registers that are still live at this point can be dependent on a
        // register we're dropping.
        assert !droppedBuilder.testAny(scratch2);
        Bits.Op.UNION.into(droppedBuilder, scratch2);
        addNoDeps(scratch2);
      }
      Op.Result result = opResult;
      if (needsRecursion) {
        // Don't recurse until after we're done with scratch and scratch2!
        result = (Op.Result) opResult.simplify(this::processInput, null);
      }
      // If addRefs are needed, replace the Op.Result with an RcOpResult that will do them.
      return needAddRef.isEmpty() ? result : new RcOpResult(result, needAddRef);
    }

    /**
     * Merges the given noDeps map and mustBeCounted bits into {@link #noDepsBuilder} and {@link
     * #mustBeCountedBuilder}.
     *
     * <p>mustBeCounted is easy: we just intersect the sets.
     *
     * <p>noDeps is trickier: we want noDeps(r1) to include r2 if:
     *
     * <ul>
     *   <li>noDeps(r1) included r2 on both branches, or
     *   <li>noDeps(r1) included r2 on one of the branches, and mustBeCounted contained r2 on the
     *       other
     * </ul>
     */
    private void combineNoDeps(SmallIntMap<Bits> noDeps, Bits mbc) {
      scratch.setAll(mustBeCountedBuilder);
      Bits.Op.SYMM_DIFF.into(scratch, mbc);
      Bits extra = scratch.build();
      if (extra.isEmpty()) {
        // Easy case: if the MBCs are equal, a simple intersection inner join will give us the
        // updated noDeps
        noDepsBuilder.innerJoin(
            noDeps, (k, b1, b2) -> dropEmpty(Bits.Op.INTERSECTION.apply(b1, b2)));
      } else {
        Bits.Op.INTERSECTION.into(mustBeCountedBuilder, mbc);
        noDepsBuilder.outerJoin(
            noDeps,
            (k, b1, b2) -> {
              Bits result;
              if (b1 != null && b2 != null) {
                // This computes ((b1 | extra) & b2) | (b1 & extra)
                scratch.setAll(b1);
                Bits.Op.UNION.into(scratch, extra);
                Bits.Op.INTERSECTION.into(scratch, b2);
                scratch2.setAll(extra);
                Bits.Op.INTERSECTION.into(scratch2, b1);
                Bits.Op.UNION.into(scratch2, scratch);
                result = scratch2.build();
              } else {
                // Missing values are treated as empty sets, so the formula above
                // simplifies to just intersecting the non-empty value with extra
                result = Bits.Op.INTERSECTION.apply(extra, (b1 != null) ? b1 : b2);
              }
              assert !mustBeCountedBuilder.testAny(result);
              return dropEmpty(result);
            });
      }
    }
  }

  /**
   * The third (and final) pass over the blocks computes {@link BlockExtra#initialDependencies},
   * {@link BlockExtra#nextDependencies}, {@link BlockExtra#alternateDependencies}, {@link
   * BlockExtra#initialCounted}, and {@link BlockExtra#countedAfter} for each block.
   */
  private class ForwardPass {
    final RegisterAssigner assigner;

    // Scratch state used during the processing of a block; always reset at the start of
    // process().
    private final Bits.Builder scratchBits = new Bits.Builder();
    private final SmallIntMap.Builder<Bits> scratchDeps = new SmallIntMap.Builder<>();

    // We need an InlinkExtra object corresponding to each of the current block's inlinks; once
    // created they're saved here and reused for subsequent blocks.
    private final List<InlinkExtra> linkExtras = new ArrayList<>();
    private int numInlinks = 0;

    /** RefFixBlocks that have been inserted before the current block. */
    private final List<RefFixBlock> refFixes = new ArrayList<>();

    ForwardPass(RegisterAssigner assigner) {
      this.assigner = assigner;
    }

    void run() {
      // This pass processes blocks in order.
      Arrays.stream(blockExtras).forEach(this::process);
    }

    /**
     * Increment {@link #numInlinks} and initialize the next element of {@link #linkExtras} to save
     * our analysis of {@code link}. If {@code link}'s origin has not yet been analyzed (i.e. if it
     * is a BackRef), use the loop entry information to initialize it.
     */
    private void addInlink(Link link) {
      if (linkExtras.size() <= numInlinks) {
        linkExtras.add(new InlinkExtra());
      }
      InlinkExtra linkExtra = linkExtras.get(numInlinks++);
      linkExtra.link = link;
      BlockExtra srcExtra = blockExtra(link.origin);
      if (srcExtra.nextDependencies == null) {
        fixLoopBackRefs(srcExtra);
      }
    }

    /**
     * Since this is a forward pass, if we encounter an inlink from a block that we have not yet
     * analyzed it must be a BackRef. We can initialize it by merging information from loop entries
     * (we can't encounter a BackRef until we're inside the loop, which means that we must have
     * already processed all the entry sources).
     */
    private void fixLoopBackRefs(BlockExtra extra) {
      BackRef backRef = (BackRef) extra.block;
      Loop loop = backRef.loop();
      LoopExtra loopExtra = loopExtra(loop);
      if (loopExtra.entryDeps == null) {
        // We only need to do this the first time we encounter a BackRef for a given loop; if there
        // is more than one BackRef they can reuse the results.
        // (We use the BackwardPass results from this backRef, but all of a loop's backRefs will
        // have the same liveCrs, MBC, and noDeps values -- see BackwarPass.processBackRef)
        loopExtra.entryDeps = mergeDeps(extra, loop.numEntries(), loop::entry);
        loopExtra.entryCounted = getCounted(extra.liveTrs, loopExtra.entryDeps);
      }
      extra.nextDependencies = loopExtra.entryDeps;
      extra.countedAfter = loopExtra.entryCounted;
    }

    /**
     * Determine the registers that will be counted at the beginning of a block or loop iteration,
     * given the registers that are live and the known dependencies.
     */
    private Bits getCounted(Bits liveCrs, SmallIntMap<Bits> dependencies) {
      if (dependencies.isEmpty()) {
        // If there are no dependencies, all live registers must be counted.
        return liveCrs;
      }
      // Start by assuming that all live registers are counted.
      scratchBits.setAll(liveCrs);
      // Remove any that are dependent, but add the registers that they're dependent on.
      dependencies.forEachEntry(
          (tri, deps) -> {
            // Sanity check: none of the registers in a dependency can itself be dependent
            assert deps.filter(dependencies::containsKey).isEmpty();
            assertTrue(scratchBits.clear(tri));
            Bits.Op.UNION.into(scratchBits, deps);
          });
      return scratchBits.build();
    }

    /**
     * Computes {@link BlockExtra#initialDependencies}, {@link BlockExtra#nextDependencies}, {@link
     * BlockExtra#alternateDependencies}, {@link BlockExtra#initialCounted}, and {@link
     * BlockExtra#countedAfter} for the given block. Assumes that all of the block's inlinks have
     * already been processed, except those that come from BackRefs.
     */
    private void process(BlockExtra extra) {
      Block b = extra.block;
      // Load this block's inlinks into linkExtras
      numInlinks = 0;
      b.forEachInLink(this::addInlink);
      if (numInlinks == 0) {
        assert b instanceof Block.Initial;
        // All pointer args are passed RC.In.  Since tracked register indices are assigned in order
        // to registers and arguments are the lowest-numbered registers, to figure out which tracked
        // registers are live at this point we just need to find the highest-numbered argument with
        // a TRI.
        int lastArgRci = -1;
        for (int i = numArgs() - 1; i >= 0 && lastArgRci < 0; --i) {
          lastArgRci = registerToTri[i];
        }
        extra.countedAfter = Bits.forRange(0, lastArgRci);
        extra.nextDependencies = SmallIntMap.empty();
      } else {
        // First get the counted/dependency state before starting the block from its inlinks.
        extra.initialDependencies = mergeDeps(extra, numInlinks, i -> linkExtras.get(i).link);
        extra.initialCounted = getCounted(extra.liveTrs, extra.initialDependencies);
        // Recompute the block's live set, including
        // - the original live set
        // - the TState register (x0), in case we add a call to dropRef
        // - each register that a live register depends on
        scratchBits.setAll(b.live());
        scratchBits.set(CodeGen.TSTATE_REGISTER_INDEX);
        // initialCounted already includes all the registers that other registers depend on; we just
        // need to convert it from tracked register indices back to register indices.
        setRegisterIndicesFromTris(
            scratchBits, Bits.Op.DIFFERENCE.apply(extra.initialCounted, extra.liveTrs));
        // Save the revised live set.
        setLive(b, scratchBits.build());
        // Insert RefFixBlocks as needed on our inlinks to match the initialCounted that we've
        // settled on.
        fixInlinks(extra);
        // Now that we've got the state when the block starts we can determine the state when it
        // ends.  We start by removing any registers that are passed @RC.In (without an addRef) from
        // counted.
        extra.countedAfter = Bits.Op.DIFFERENCE.apply(extra.initialCounted, extra.dropped);
        extra.nextDependencies = extra.initialDependencies;
        // Tracked registers that are modified by this block must be added to countedAfter or
        // nextDependencies.
        b.forEachModifiedRegister(r -> updateForRegisterMod(extra, r));
        // For blocks with a single outlink, we're done, but Splits (2 outlinks) and Terminals
        // (0 outlinks) need a little extra.
        if (b instanceof Split) {
          extra.alternateDependencies = extra.nextDependencies;
          if (b instanceof TestBlock) {
            // In most cases the next and alternate links from a TestBlock have the same
            // information; the exception is an object identity test of a register with a constant
            // (usually null or a singleton).  If that succeeds, we know that the register must be
            // uncounted (so we may be able to skip subsequent calls to addRef or dropRef).
            Register uncountedOnNext = uncountedOnNext(b);
            if (uncountedOnNext != null) {
              int tri = registerToTri(uncountedOnNext);
              if (tri >= 0) {
                // Dependency on an empty set means uncounted
                scratchDeps.setFrom(extra.nextDependencies);
                scratchDeps.put(tri, Bits.EMPTY);
                extra.nextDependencies = scratchDeps.build();
              }
            }
          } else if (b instanceof SetBlock.WithCatch) {
            // If we take the alternate link from a SetBlock.WithCatch, nothing has been changed.
            // We should probably have an alternate countedAfter as well; I suspect that the only
            // reason we get away without that is that we only use WithCatch to set untracked
            // registers (catching ArithmeticException).
            assert extra.nextDependencies.equals(extra.initialDependencies);
            assert extra.countedAfter.equals(extra.initialCounted);
          } else {
            assert b instanceof ExlinedCall;
            // The next and alternate links from an ExlinedCall leave the same registers assigned.
          }
        } else if (b instanceof Block.Terminal) {
          if (!extra.countedAfter.isEmpty()) {
            assert b instanceof ReturnBlock;
            CodeValue rhs = b.input(0);
            // If we want to generate code for methods that return countable types this will have
            // to be a little more sophisticated
            assert !isCountable(rhs.type());
            // Drop all the countedAfter registers once we've computed the return value
            for (int i : extra.countedAfter) {
              rhs =
                  PUSH_AND_DROP_REF.result(
                      rhs, register(CodeGen.TSTATE_REGISTER_INDEX), triToRegister(i));
            }
            b.setInput(0, rhs);
            extra.countedAfter = Bits.EMPTY;
          }
        }
      }
    }

    /**
     * {@code r} is a register that is modified by the given block; update {@code
     * extra.countedAfter} and/or {@code extra.nextDependencies} as appropriate.
     */
    private void updateForRegisterMod(BlockExtra extra, Register r) {
      NonTerminal b = (NonTerminal) extra.block;
      int tri = registerToTri(r);
      if (b instanceof SetBlock || b instanceof SetBlock.WithCatch) {
        if (tri >= 0) {
          updateForSet(extra, r);
        }
      } else if (b instanceof ExlinedCall || b instanceof StackEntryBlock) {
        // The outputs of ExlinedCall and StackEntryBlock are always counted
        extra.countedAfter = extra.countedAfter.set(tri);
        if (extra.nextDependencies.containsKey(tri)) {
          scratchDeps.setFrom(extra.nextDependencies);
          scratchDeps.put(tri, null);
          extra.nextDependencies = scratchDeps.build();
        }
      } else {
        throw new AssertionError();
      }
      // The RegisterAssigner already knows that the register we're modifying (r) must not alias any
      // registers that are live after this block, but it's possible for dependencies to cause an
      // otherwise-unused register to remain live longer than previously believed.  We need to be
      // sure that the RegisterAssigner doesn't alias one of these registers with r; that would
      // cause us to lose its value before the eventual call to dropRef.

      // These are the registers that we need to be sure aren't aliased with r.
      scratchBits.setAll(extra.countedAfter);
      if (tri >= 0) {
        // We don't need to worry about aliasing r with itself.
        scratchBits.clear(tri);
      }
      // We don't need to tell it to not alias with a register that was already known to be live.
      BlockExtra nextExtra = blockExtra(b.next.targetBlock());
      Bits.Op.DIFFERENCE.into(scratchBits, nextExtra.liveTrs);
      if (b instanceof Split split) {
        BlockExtra alternateExtra = blockExtra(split.alternate.targetBlock());
        Bits.Op.DIFFERENCE.into(scratchBits, alternateExtra.liveTrs);
      }
      Bits extraConflicts = scratchBits.build();
      // We only need to do this if we actually have new constraints.
      if (!extraConflicts.isEmpty()) {
        // Convert our extraConflicts set (which is TRIs) to the register indices that
        // RegisterAssigner needs.
        scratchBits.clearAll();
        setRegisterIndicesFromTris(scratchBits, extraConflicts);
        assigner.addConflicts(r, scratchBits);
      }
    }

    /**
     * Update {@code extra.countedAfter} and/or {@code extra.nextDependencies} for a set block whose
     * lhs is a tracked register.
     */
    private void updateForSet(BlockExtra extra, Register lhs) {
      Block b = extra.block;
      assert b instanceof SetBlock || b instanceof SetBlock.WithCatch;
      // No one else should have modified nextDependencies yet
      assert extra.nextDependencies == extra.initialDependencies;
      CodeValue rhs = b.input(0);
      int tri = registerToTri(lhs);
      if (extra.countedAfter.test(tri)) {
        // We've got something like `x = op(x)` where x was counted and op is not @RC.In; after
        // evaluating `op(x)` but before overwriting x we need a call to `dropRef(x)`
        b.setInput(0, PUSH_AND_DROP_REF.result(rhs, register(CodeGen.TSTATE_REGISTER_INDEX), lhs));
      }
      // We will set nextDependencies[lhs] to newDeps
      Bits newDeps;
      if (rhs instanceof Const) {
        newDeps = Bits.EMPTY;
      } else if (rhs instanceof Register r) {
        int rhsTri = registerToTri(r);
        Bits rhsDeps = extra.initialDependencies.get(rhsTri);
        if (rhsDeps != null) {
          // `x = y`, where y is dependent: x will have the same dependencies as y
          newDeps = rhsDeps;
        } else {
          // `x = y`, where y is counted: we have two options here.
          // The obvious one is to make x dependent on y.
          // The other option is to make x counted, and y dependent on x.
          // Either option is valid; we try to guess which will lead to fewer refcount operations
          // later.
          boolean transfer;
          if (extra.isLoopCounted(rhsTri)) {
            // If we've chosen to make y counted throughout this loop, keep it counted.
            transfer = false;
          } else {
            // Otherwise, if we need x to be counted *or* we need it to not depend on y, make it
            // counted; otherwise leave y counted.
            BlockExtra nextExtra = blockExtra(((NonTerminal) b).next.targetBlock());
            transfer = nextExtra.mustBeCounted.test(tri) || nextExtra.excludedByNoDeps(tri, rhsTri);
          }
          if (transfer) {
            // Make the rhs depend on the lhs
            scratchDeps.setFrom(extra.initialDependencies);
            scratchDeps.put(rhsTri, Bits.of(tri));
            scratchDeps.put(tri, null);
            extra.nextDependencies = scratchDeps.build();
            assert extra.countedAfter.test(rhsTri);
            extra.countedAfter = extra.countedAfter.clear(rhsTri).set(tri);
            return;
          }
          newDeps = Bits.of(rhsTri);
        }
      } else {
        Op.Result opResult = (Op.Result) rhs;
        if (opResult.op instanceof RcOp rcOp && rcOp.resultIsRcOut) {
          // rhs is a call to an @RC.Out op, so its result is counted
          newDeps = null;
        } else {
          // The result will not be counted, so assume that it is dependent on every tracked
          // argument
          scratchBits.clearAll();
          getDependencies(extra, opResult, scratchBits);
          newDeps = scratchBits.build();
        }
      }
      if (newDeps == null) {
        extra.countedAfter = extra.countedAfter.set(tri);
      } else {
        extra.countedAfter = extra.countedAfter.clear(tri);
      }
      if (!Objects.equals(extra.initialDependencies.get(tri), newDeps)) {
        scratchDeps.setFrom(extra.initialDependencies);
        scratchDeps.put(tri, newDeps);
        extra.nextDependencies = scratchDeps.build();
      }
    }

    /**
     * This is a call to a non-@RC.Out op; construct a dependency set by finding all the arguments
     * (possibly nested) that are tracked registers, and adding either the register (if it is
     * counted) or its dependencies to {@code deps}.
     */
    private void getDependencies(BlockExtra extra, Op.Result opResult, Bits.Builder deps) {
      // I don't think we can end up here with an op that has @RC.In or @RC.Out args;
      // if we do we'll need to think harder.
      Preconditions.checkState(!(opResult.op instanceof RcOp));
      for (CodeValue arg : opResult.args) {
        if (arg instanceof Const || !isCountable(arg.type())) {
          // skip it
        } else if (arg instanceof Register r) {
          int argTri = registerToTri(r);
          Bits argDeps = extra.initialDependencies.get(argTri);
          if (argDeps != null) {
            Bits.Op.UNION.into(deps, argDeps);
          } else {
            deps.set(argTri);
          }
        } else {
          getDependencies(extra, (Op.Result) arg, deps);
        }
      }
    }

    /**
     * If any of this block's inlinks have different assumptions about which registers are counted,
     * insert RefFixBlocks as needed to addRef/dropRef before reaching this block.
     */
    private void fixInlinks(BlockExtra extra) {
      refFixes.clear();
      for (int i = 0; i < numInlinks; i++) {
        InlinkExtra linkExtra = linkExtras.get(i);
        linkExtra.getRefFixes(extra.initialCounted, scratchBits);
        if (linkExtra.addRef.isEmpty() && linkExtra.dropRef.isEmpty()) {
          // The usual case: no addRefs or dropRefs needed.
          continue;
        }
        // We're going to change this link's target to a RefFixBlock in front of extra.block
        Link link = linkExtra.link;
        assert link.target() == extra.block;
        link.detach();
        // Before we create a new RefFixBlock, see if we've already created one with the same
        // addRef/dropRef sets (it's not unusual for multiple inlinks to be able to share a
        // RefFixBlock).
        for (RefFixBlock prev : refFixes) {
          if (prev.dropRef.equals(linkExtra.dropRef) && prev.addRef.equals(linkExtra.addRef)) {
            link.setTarget(prev);
            break;
          }
        }
        if (link.target() == null) {
          // We didn't find a matching one, so create it
          RefFixBlock fixup = new RefFixBlock(linkExtra.addRef, linkExtra.dropRef);
          insertBefore(fixup, extra.block);
          fixup.setLive(scratchBits);
          link.setTarget(fixup);
          refFixes.add(fixup);
        }
      }
    }

    /**
     * Combines the dependency maps from one or more inlinks to {@code extra.block}.
     *
     * <p>If a dependency entry is null on any inlink (i.e. the register is counted), it will be
     * null in the result. Otherwise it will be the union of the incoming dependency entries, unless
     * {@link BlockExtra#filterDependency} returns null.
     */
    SmallIntMap<Bits> mergeDeps(BlockExtra extra, int numLinks, IntFunction<Link> links) {
      assert numLinks > 0;
      // First pass: ensure that if any of the loops come from a BackRef, we've computed their
      // dependencies (this may reset scratch state, so do it before we start our main loop).
      for (int i = 0; i < numLinks; i++) {
        Link link = links.apply(i);
        BlockExtra srcExtra = blockExtra(link.origin);
        if (srcExtra.nextDependencies == null) {
          // Only possible if this is a link from a BackRef
          fixLoopBackRefs(srcExtra);
        }
      }
      // Second pass: construct the merged dependency map in scratchDeps, and the intersection
      // of the incoming countedAfter sets in scratchBits
      for (int i = 0; i < numLinks; i++) {
        Link link = links.apply(i);
        BlockExtra srcExtra = blockExtra(link.origin);
        SmallIntMap<Bits> linkDeps = srcExtra.dependencies(link);
        if (i == 0) {
          // First inlink.
          scratchDeps.setFrom(linkDeps);
          // Clear any dependencies that are for no-longer-live registers or that are for
          // registers marked as mustBeCounted.  Doing this ASAP (rather than waiting until
          // we have unioned the dependencies from other links) can save us a little work.
          scratchDeps.updateEntries(extra::filterDependency);
          scratchBits.setAll(srcExtra.countedAfter);
        } else {
          // A merged dependency set is only valid if all of its elements were counted on every
          // inlink.
          Bits.Op.INTERSECTION.into(scratchBits, srcExtra.countedAfter);
          // We can use innerJoin() to combine the dependency sets.
          scratchDeps.innerJoin(
              linkDeps,
              (tri, prev, toAdd) -> combineDependencies(extra, tri, prev, toAdd, scratchBits));
        }
      }
      if (scratchDeps.isEmpty()) {
        return SmallIntMap.empty();
      }
      // If the dependency map we've constructed is equal to that of one of our inLinks, reuse the
      // inlink one rather than creating a copy.  I think the memory saved here is worth the cost
      // of the check.
      for (int i = 0; i < numLinks; i++) {
        Link link = links.apply(i);
        BlockExtra srcExtra = blockExtra(link.origin);
        SmallIntMap<Bits> linkDeps = srcExtra.dependencies(link);
        if (linkDeps.equals(scratchDeps)) {
          return linkDeps;
        }
      }
      return scratchDeps.build();
    }

    /**
     * Combine dependency sets {@code prev} and {@code toAdd} for {@code tri}. Both must be
     * contained in {@code counted} to be valid. {@code prev} has already been checked by {@link
     * BlockExtra#filterDependency} but {@code toAdd} has not.
     */
    private static Bits combineDependencies(
        BlockExtra extra, int tri, Bits prev, Bits toAdd, Bits.Builder counted) {
      if (!(counted.testAll(prev) && counted.testAll(toAdd))) {
        // These can't be combined
        return null;
      } else if (prev.testAll(toAdd)) {
        // In many cases the dependency sets on converging inlinks will be identical.
        return counted.testAll(prev) ? prev : null;
      } else {
        // If we're expanding the deps set, we need to make sure that the expanded version is still
        // consistent with our constraints.
        return extra.filterDependency(tri, Bits.Op.UNION.apply(prev, toAdd));
      }
    }
  }

  private class InlinkExtra {
    Link link;

    /** TRIs of the registers that need an addRef when traversing this link. */
    Bits addRef;

    /** TRIs of the registers that need a dropRef when traversing this link. */
    Bits dropRef;

    /**
     * Compares {@link BlockExtra#countedAfter} from this link's source with {@code counted}; if
     * they are different, determines which registers need an addRef or dropRef. Sets {@link
     * #addRef} and {@link #dropRef} accordingly.
     *
     * @param scratch may be overwritten
     */
    void getRefFixes(Bits counted, Bits.Builder scratch) {
      BlockExtra srcExtra = blockExtra(link.origin);
      if (counted.equals(srcExtra.countedAfter)) {
        addRef = Bits.EMPTY;
        dropRef = Bits.EMPTY;
        return;
      }
      // We don't need to addRef/dropRef any register that is known to contain a constant
      SmallIntMap<Bits> linkDeps = srcExtra.dependencies(link);
      IntPredicate skip = i -> Bits.EMPTY.equals(linkDeps.get(i));
      dropRef = diff(srcExtra.countedAfter, counted, skip, scratch);
      addRef = diff(counted, srcExtra.countedAfter, skip, scratch);
      if (dropRef.isEmpty() || addRef.isEmpty()) {
        return;
      }
      // Another potential optimization: it's possible that addRef contains r1 and dropRef contains
      // r2 where r1 is known (from the link info) to be equal to r2.  If so, the addRef/dropRef
      // would be redundant and we can remove them both.
      scratch.clearAll();
      // Hypothetically there could be two registers in each set that all have the same canonical,
      // in which case we'd only remove one from each, but that seems unlikely enough that I don't
      // think it's worth doing something more complicated.
      dropRef.forEach(i -> scratch.set(canonicalize(i)));
      addRef.forEach(
          i -> {
            int canonical = canonicalize(i);
            if (scratch.clear(canonical)) {
              addRef = addRef.clear(i);
              // Searching dropRef again to find the matching register isn't free, but this isn't
              // expected to be common (and the benefit of simplifying the refCount updates is much
              // greater than this cost).
              int fromDropRef =
                  dropRef.stream().filter(j -> canonicalize(j) == canonical).findFirst().getAsInt();
              dropRef = dropRef.clear(fromDropRef);
            }
          });
    }

    /**
     * If the given register is known to be equal to some other register, return the canonical
     * register's tracked register index; otherwise return {@code tri}.
     */
    private int canonicalize(int tri) {
      ValueInfo info = link.info.register(triToRegister[tri]);
      return (info instanceof Register r) ? registerToTri(r) : tri;
    }
  }

  /**
   * Computes {@code DIFFERENCE(left, right)}, but omits any elements for which {@code skip} returns
   * true.
   */
  private static Bits diff(Bits left, Bits right, IntPredicate skip, Bits.Builder scratch) {
    scratch.setAll(left);
    Bits.Op.DIFFERENCE.into(scratch, right);
    // Maybe add a Bits.Builder.filter() method?
    scratch.forEach(
        i -> {
          if (skip.test(i)) {
            scratch.clear(i);
          }
        });
    return scratch.build();
  }

  /**
   * RcCodeBuilder creates one BlockExtra for each block in the block graph, to save the results of
   * its analysis. They are created at the beginning of {@link #analyzeRefCounts} and kept until
   * analysis is complete, with each pass adding more information to them.
   */
  private class BlockExtra {
    final Block block;

    /**
     * Tracked registers that have a value after execution of this block.
     *
     * <p>Computed by {@link SetupPass}.
     */
    Bits assignedTrsAfter;

    /**
     * Tracked registers that are live before execution of this block.
     *
     * <p>Computed by {@link SetupPass}.
     */
    Bits liveTrs;

    /**
     * Tracked registers whose reference count will be decremented by this block, by passing them as
     * an {@code @RC.In} argument without a corresponding call to {@link RefCounted#addRef}. Must
     * not include registers that are still live after this block completes.
     *
     * <p>Computed by {@link ForwardPass}.
     */
    Bits dropped;

    /**
     * Tracked registers that are passed as an {@code @RC.In} argument without a corresponding call
     * to {@link RefCounted#addRef} by this block or a following block; we will not allow these to
     * be dependent on any other register (since we know we need to addRef these eventually, we
     * might as well do that ASAP and potentially dropRef the register it depends on sooner).
     *
     * <p>Computed by {@link BackwardPass}.
     */
    Bits mustBeCounted;

    /**
     * If {@code noDeps.get(r1).test(r2)}, we will not allow {@code r2} to depend on {@code r1}
     * (where {@code r1} and {@code r2} are TRIs). Set if {@code r2} is still live after {@code r1}
     * is modified.
     *
     * <p>Computed by {@link BackwardPass}.
     */
    SmallIntMap<Bits> noDeps;

    /**
     * If {@code initialDependencies.get(r)} is non-null, {@code r}'s reference is not counted on
     * entry to this block because we know that it is reachable from at least one of the TRIs in
     * that Bits (if the Bits is empty, {@code r}'s value is known to be uncounted). If {@code
     * initialDependencies.get(r)} is null and {@code r} is live then the reference from {@code r}
     * is counted.
     *
     * <p>Computed by {@link ForwardPass}.
     */
    SmallIntMap<Bits> initialDependencies;

    /**
     * Interpreted in the same way as {@link #initialDependencies}, but applies to the state when
     * this block's next link is reached. Null for Terminals.
     *
     * <p>Computed by {@link ForwardPass}.
     */
    SmallIntMap<Bits> nextDependencies;

    /**
     * Interpreted in the same way as {@link #initialDependencies}, but applies to the state when
     * this block's alternate link is reached. Null for blocks that are not Splits.
     *
     * <p>Computed by {@link ForwardPass}.
     */
    SmallIntMap<Bits> alternateDependencies;

    /**
     * Tracked registers that will be counted on entry to this block.
     *
     * <p>Computed by {@link ForwardPass}.
     */
    Bits initialCounted;

    /**
     * Tracked registers that will be counted when this block completes.
     *
     * <p>Computed by {@link ForwardPass}.
     */
    Bits countedAfter;

    BlockExtra(Block block) {
      this.block = block;
    }

    /**
     * {@code outlink} must be this block's next link or alternate link; returns {@link
     * #nextDependencies} or {@link #alternateDependencies} as appropriate.
     */
    SmallIntMap<Bits> dependencies(Link outlink) {
      if (outlink == ((NonTerminal) block).next) {
        return nextDependencies;
      } else {
        assert outlink == ((Split) block).alternate;
        return alternateDependencies;
      }
    }

    /**
     * Given a dependency from the previous block (i.e. an entry in the previous block's {@link
     * #nextDependencies} or {@link #alternateDependencies}), returns it unchanged if it is still
     * valid, or null if it should be dropped.
     *
     * @param tri the key in the dependencies SmallIntMap
     * @param deps the value in the dependencies SmallIntMap
     */
    Bits filterDependency(int tri, Bits deps) {
      if (!liveTrs.test(tri)) {
        // Registers that are no longer live should not have a dependency entry
        return null;
      } else if (block instanceof BackRef backRef && loopExtra(backRef.loop()).loopTrs.test(tri)) {
        // Loop registers are always counted at the beginning of the loop
        return null;
      } else if (deps.isEmpty()) {
        // Uncounted values remain valid
        return deps;
      } else if (mustBeCounted.test(tri)) {
        // This register will need to be counted, so we might as well addRef it now
        return null;
      } else {
        // If any of the registers we depend on are ones we shouldn't depend on (according to
        // noDeps), we'll need an addRef; otherwise we can keep the dependency.
        return deps.stream().anyMatch(dep -> excludedByNoDeps(tri, dep)) ? null : deps;
      }
    }

    /** Returns true if {@link #noDeps} says that {@code tri} should not depend on {@code dep}. */
    boolean excludedByNoDeps(int tri, int dep) {
      Bits noDep = noDeps.get(dep);
      return noDep != null && noDep.test(tri);
    }

    /**
     * Returns true if there is a loop containing this block that for which we've chosen to make the
     * given register counted.
     */
    boolean isLoopCounted(int tri) {
      for (Loop loop = block.containingLoop(); loop != null; loop = loop.nestedIn()) {
        if (loopExtra(loop).entryCounted.test(tri)) {
          return true;
        }
      }
      return false;
    }
  }

  private static class LoopExtra {
    /**
     * The tracked registers that are updated by this loop.
     *
     * <p>Computed by {@link SetupPass}.
     */
    Bits loopTrs;

    /**
     * The tracked registers that have a value on all entry links of this loop.
     *
     * <p>Computed by {@link SetupPass}.
     */
    Bits assignedTrsBefore;

    /**
     * Tracked registers that are live throughout the execution of this loop; must be included in
     * {@link BlockExtra#liveTrs} for each block contained in the loop.
     *
     * <p>Computed by {@link SetupPass}.
     */
    Bits liveTrs;

    /**
     * The tracked registers that must be counted on exit from this loop, computed by merging {@link
     * BlockExtra#mustBeCounted} from each exit link target and adding {@link #loopTrs}.
     *
     * <p>Computed by {@link BackwardPass}.
     */
    Bits mustBeCounted;

    /**
     * Prohibited dependency relations on exit from this loop, computed by merging {@link
     * BlockExtra#noDeps} from each exit link target.
     *
     * <p>Computed by {@link BackwardPass}.
     */
    SmallIntMap<Bits> noDeps;

    /**
     * The dependency state on entry to this loop, computed by merging the {@link
     * BlockExtra#dependencies} from each entry link source.
     *
     * <p>Computed by {@link ForwardPass}.
     */
    SmallIntMap<Bits> entryDeps;

    /**
     * The tracked registers that are counted on entry to this loop, computed by merging the {@link
     * BlockExtra#countedAfter} from each entry link source.
     *
     * <p>Computed by {@link ForwardPass}.
     */
    Bits entryCounted;
  }

  /**
   * A Block that calls addRef or dropRef on one or more registers. RefFixBlocks are inserted during
   * the final stage of analysis to adjust reference counts.
   */
  private class RefFixBlock extends NonTerminal {
    /** {@link #emit} will addRef each of these registers. */
    private final Bits addRef;

    /** {@link #emit} will dropRef each of these registers. */
    private final Bits dropRef;

    RefFixBlock(Bits addRef, Bits dropRef) {
      // addRef and dropRef should be disjoint, and not both empty
      assert !addRef.testAny(dropRef);
      assert !(addRef.isEmpty() && dropRef.isEmpty());
      this.addRef = addRef;
      this.dropRef = dropRef;
    }

    /**
     * Initialize {@link Block#live}. Normally this would be done by the CodeBuilder's optimization
     * phase, but we're inserting these blocks after optimization is complete so we need to do it
     * ourselves.
     */
    void setLive(Bits.Builder scratch) {
      // Start with the live set for the following block.
      Bits live = next.targetBlock().live();
      // Registers that we dropRef won't be in the following block's live set, but should be in
      // ours.
      if (!dropRef.isEmpty()) {
        scratch.setAll(live);
        setRegisterIndicesFromTris(scratch, dropRef);
        live = scratch.build();
      }
      CodeBuilder.setLive(this, live);
    }

    @Override
    public Block emit(Emitter emitter) {
      addRef.forEach(tri -> emitAddRef(triToRegister(tri), emitter));
      dropRef.forEach(tri -> emitDropRef(triToRegister(tri), emitter));
      return next.targetBlock();
    }

    @Override
    public String toString(CodeBuilder.PrintOptions options) {
      StringBuilder sb = new StringBuilder();
      if (!addRef.isEmpty()) {
        sb.append("addRef").append(trsToString(addRef, options));
      }
      if (!dropRef.isEmpty()) {
        sb.append(addRef.isEmpty() ? "" : " ")
            .append("dropRef")
            .append(trsToString(dropRef, options));
      }
      return sb.toString();
    }
  }

  /**
   * If the given block is a {@link IsEq} comparison of a pointer-valued register with a constant
   * value (probably null or a Singleton), return the register; otherwise return null.
   *
   * <p>Used by {@link ForwardPass#process} to recognize a case when subsequent addRefs or dropRefs
   * would be no-ops (and hence can be optimized away).
   */
  private static Register uncountedOnNext(Block block) {
    if (block instanceof IsEq isEq) {
      if (isEq.opCodeType == OpCodeType.OBJ) {
        CodeValue v0 = isEq.input(0);
        CodeValue v1 = isEq.input(1);
        if (v0 instanceof Register r0 && v1 instanceof Const) {
          return r0;
        } else if (v1 instanceof Register r1 && v0 instanceof Const) {
          return r1;
        }
      }
    }
    return null;
  }

  /** Emit a call to {@link RefCounted#addRef(Object)} with the given register. */
  void emitAddRef(Register r, Emitter emitter) {
    // We shouldn't be here if r is known to be an array (addRef() will error).
    assert !r.type().isArray();
    RefCounted.ADD_REF_OP.emit(emitter, r);
  }

  /**
   * Emit a call to dropRef() with the given register.
   *
   * <p>If the register type is known we may be able to choose one of {@link
   * MemoryHelper#dropReference(RefCounted)}, {@link MemoryHelper#dropValue(Value)}, {@link
   * MemoryHelper#dropReference(byte[])}, or {@link MemoryHelper#dropReference(Object[])}; otherwise
   * we just call {@link MemoryHelper#dropAny(Object)} and sort it out at runtime.
   */
  void emitDropRef(Register r, Emitter emitter) {
    Op op;
    if (RefCounted.class.isAssignableFrom(r.type())) {
      op = TState.DROP_REFERENCE_OP;
    } else if (Value.class.isAssignableFrom(r.type())) {
      op = TState.DROP_VALUE_OP;
    } else if (byte[].class.equals(r.type())) {
      op = TState.DROP_BYTE_ARRAY_OP;
    } else if (Object[].class.equals(r.type())) {
      op = TState.DROP_OBJ_ARRAY_OP;
    } else {
      op = TState.DROP_ANY_OP;
    }
    op.emit(emitter, register(CodeGen.TSTATE_REGISTER_INDEX), r);
  }

  /** A custom {@link OpEmit} used to implement {@link #PUSH_AND_DROP_REF}. */
  private static final OpEmit PUSH_AND_DROP_EMITTER =
      new OpEmit() {
        @Override
        public void emit(Emitter emitter, Result result) {
          throw new AssertionError();
        }

        @Override
        public void emit(Emitter emitter, Result result, Class<?> type) {
          result.pushArg(emitter, 0, type);
          TState.DROP_REFERENCE_OP.emit(emitter, result.args.get(1), result.args.get(2));
        }
      };

  /**
   * A three-argument Op (result, tstate, toDrop) that pushes {@code result} on the stack, calls
   * {@code tstate.dropReference(toDrop)}, and then returns {@code result}; used to ensure that
   * computation done for {@code result} is completed before the reference is dropped.
   */
  private static final Op PUSH_AND_DROP_REF =
      Op.simple(
              "pushAndDropRef",
              PUSH_AND_DROP_EMITTER,
              Object.class,
              Object.class,
              TState.class,
              Object.class)
          .build();

  /** Returns a string description of the register with the given tri. */
  private String triToString(int tri, PrintOptions options) {
    return triToRegister(tri).toString(options);
  }

  /** Returns a string description of the given set of tracked registers. */
  private String trsToString(Bits bits, PrintOptions options) {
    return bits.stream()
        .mapToObj(i -> triToString(i, options))
        .collect(Collectors.joining(", ", "{", "}"));
  }

  /**
   * Returns a string description of a SmallIntMap from tracked register to sets of tracked
   * registers.
   */
  private String depsToString(SmallIntMap<Bits> deps, PrintOptions options) {
    return deps.toString(k -> triToString(k, options), v -> trsToString(v, options));
  }

  /**
   * Emits instructions to call {@link RefCounted#addRef(Object)} with the object on the top of the
   * stack, leaving the stack unchanged.
   */
  private static void emitAddRefTopOfStack(Emitter emitter) {
    int stackDepth = emitter.currentStackDepth();
    emitter.mv.visitInsn(Opcodes.DUP);
    emitter.addToStackDepth(1);
    RefCounted.ADD_REF_TOP_OF_STACK.push(emitter, void.class);
    emitter.setStackDepth(stackDepth);
  }

  /**
   * A subclass of {@link Op.Result} that will addRef one or more of its arguments before the call.
   */
  static class RcOpResult extends Op.Result {
    final Bits argNeedsAddRef;

    RcOpResult(Op.Result original, Bits argNeedsAddRef) {
      super(original.op, original.args, null);
      assert !(original instanceof RcOpResult) && !argNeedsAddRef.isEmpty();
      this.argNeedsAddRef = argNeedsAddRef;
    }

    @Override
    public void pushArg(Emitter emitter, int i, Class<?> type) {
      super.pushArg(emitter, i, type);
      if (argNeedsAddRef.test(i)) {
        emitAddRefTopOfStack(emitter);
      }
    }

    @Override
    public String toString(CodeBuilder.PrintOptions options) {
      StringBuilder sb = new StringBuilder();
      sb.append(op.name).append("(");
      for (int i = 0; i < args.size(); i++) {
        String arg = args.get(i).toString(options);
        if (argNeedsAddRef.test(i)) {
          arg = "addRef(" + arg + ")";
        }
        sb.append(i == 0 ? "" : ", ").append(arg);
      }
      sb.append(")");
      return sb.toString();
    }
  }
}
