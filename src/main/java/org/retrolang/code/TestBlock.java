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
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeValue.Const;
import org.retrolang.code.ValueInfo.IntRange;
import org.retrolang.code.ValueInfo.SmallIntSet;
import org.retrolang.util.Bits;

/**
 * A TestBlock is a Split that just evaluates some condition and chooses one of the outlinks; it has
 * no other effect. Each subclass of TestBlock has either one or two CodeValues that determine the
 * outcome.
 */
public abstract class TestBlock extends Block.Split {

  protected CodeValue value1;
  protected CodeValue value2;

  /**
   * Creates a TestBlock on the given values. {@code value2} may be null, if this TestBlock only
   * uses one value; {@code value1} may not be null. The values may not have side effects.
   */
  protected TestBlock(CodeValue value1, CodeValue value2) {
    Preconditions.checkArgument(!value1.hasSideEffect());
    Preconditions.checkArgument(value2 == null || !value2.hasSideEffect());
    this.value1 = value1;
    this.value2 = value2;
  }

  /**
   * Set one branch of a (newly-created) TestBlock block. Must be called at least once before
   * calling {@link Block#addTo}. If only one branch has been set before calling {@link
   * Block#addTo}, the other will be linked to the following block.
   */
  @CanIgnoreReturnValue
  public TestBlock setBranch(boolean ifTrue, FutureBlock links) {
    (ifTrue ? next : alternate).setTarget(links);
    return this;
  }

  /**
   * Returns a TestBlock that checks if the given CodeValue is zero (intended for use with Java
   * booleans, where a zero value means false).
   */
  public static TestBlock isFalse(CodeValue cv) {
    return new IsEq(OpCodeType.INT, CodeValue.ZERO, cv);
  }

  @Override
  public int numInputs() {
    return (value2 == null) ? 1 : 2;
  }

  @Override
  public CodeValue input(int index) {
    assert index == 0 || (index == 1 && value2 != null);
    return (index == 0) ? value1 : value2;
  }

  @Override
  public void setInput(int index, CodeValue input) {
    assert index == 0 || (index == 1 && value2 != null);
    assert input != null;
    if (index == 0) {
      value1 = input;
    } else {
      value2 = input;
    }
  }

  /**
   * Returns True if this test will always succeed given values consistent with these ValueInfos,
   * False if it will always fail, and null otherwise.
   *
   * <p>Neither info will be a {@link Register}.
   */
  protected abstract Boolean test(ValueInfo info1, ValueInfo info2);

  /**
   * Called to implement {@link Split#updateInfo}; should update {@code next.info} and/or {@code
   * alternate.info} with any additional information that can be inferred from the TestBlock
   * succeeding or failing.
   *
   * @param info1 what is known about {@link #value1} before the block is executed
   * @param info2 what is known about {@link #value2} before the block is executed (or null for
   *     unary TestBlocks)
   */
  protected abstract void updateInfos(ValueInfo info1, ValueInfo info2);

  /**
   * Emits bytecode to push this TestBlock's values on the stack and then returns the appropriate
   * ConditionalBranch.
   */
  protected abstract ConditionalBranch emitTest(Emitter emitter);

  /**
   * For binary TestBlocks, returns true if this test will always succeed given identical values or
   * false if it will always fail. Not called on unary TestBlocks.
   */
  protected boolean isReflexive() {
    // Must be overridden by binary TestBlocks
    throw new AssertionError();
  }

  /**
   * Called on binary TestBlocks after each of the values has been independently simplified; may
   * perform additional simplification using both values. Not called on unary TestBlocks.
   */
  protected void binarySimplify() {}

  @Override
  void runForwardProp(boolean incremental) {
    // Before we build the merged inlink info, see if any of our inlinks can just skip the test
    forEachInLink(this::checkInlink);
    if (!hasInLink()) {
      // All inlinks skipped the test, so it can be removed
      cb().remove(this);
    } else {
      super.runForwardProp(incremental);
    }
  }

  @Override
  protected PropagationResult updateInfo() {
    simplifyInputs(next.info.registers());
    if (value2 != null) {
      binarySimplify();
    }
    if (value1 instanceof Register || value2 instanceof Register) {
      // If at least one of the values is a register, we may be able to infer something about its
      // value on one or both of the outlinks.
      ValueInfo info1 = value1.info(next.info.registers());
      ValueInfo info2 = (value2 == null) ? null : value2.info(next.info.registers());
      assert info1 != CodeValue.NONE && info2 != CodeValue.NONE;
      updateInfos(info1, info2);
    }
    return PropagationResult.DONE;
  }

  @Override
  protected PropagationResult updateLive() {
    // If our next and alternate links lead to the same block, we can just drop this test
    if (next.targetBlock() == alternate.targetBlock()) {
      return PropagationResult.REMOVE;
    } else if (tryMoveSetAfterTest(cb())) {
      // We'll recompute liveness first for the set block, then come back to this test
      return PropagationResult.SKIP;
    }
    return super.updateLive();
  }

  /**
   * Checks whether this TestBlock is immediately preceded by a SetBlock that sets a register only
   * used on one branch of the test; if so, we can move the set after the test.
   */
  private boolean tryMoveSetAfterTest(CodeBuilder cb) {
    // This optimization only applies to TestBlocks that have a single inlink, where that inlink is
    // from a SetBlock
    if (hasMultipleInlinks() || !(inLink.origin instanceof SetBlock)) {
      return false;
    }
    // Identify the registers for which we'd want to move a setBlock.  Start
    // with those that are live on one of our outlinks but not the other.
    Bits.Builder moveCandidates = cb.scratchBits;
    moveCandidates.clearAll();
    next.getLive(moveCandidates);
    cb.scratchBits2.clearAll();
    alternate.getLive(cb.scratchBits2);
    Bits.Op.SYMM_DIFF.into(moveCandidates, cb.scratchBits2);
    // Then remove any that are used by our test
    value1.getLive(false, moveCandidates);
    if (value2 != null) {
      value2.getLive(false, moveCandidates);
    }
    // Now look at the SetBlock(s) immediately before this test to see if any of them can be
    // moved.
    SetBlock setBlock = (SetBlock) inLink.origin;
    while (!moveCandidates.isEmpty() && !setBlock.hasSideEffect()) {
      if (!moveCandidates.clear(setBlock.lhs.index)) {
        // The register set by this SetBlock isn't one of our candidates, so we can't move it.
        // But it's still possible that we can move a SetBlock from before it, as long as the
        // register that SetBlock sets isn't used by this one.
        if (setBlock.hasMultipleInlinks() || !(setBlock.inLink.origin instanceof SetBlock)) {
          break;
        }
        setBlock.input(0).getLive(false, moveCandidates);
        setBlock = (SetBlock) setBlock.inLink.origin;
        // TODO: should we bound how far back we look?
        continue;
      }
      // This SetBlock can be moved.  Unlink it from its old position
      Block afterSet = setBlock.next.targetBlock();
      setBlock.moveAllInLinks(afterSet);
      setBlock.next.detach();
      // Remove register information that was invalidated by this reordering,
      // to avoid being misled by it later.
      for (Block b = afterSet; b instanceof SetBlock sb; ) {
        sb.next.info.updateInfo(setBlock.lhs, null);
        setBlock.next.info.updateInfo(sb.lhs, null);
        b = sb.next.targetBlock();
      }
      next.info.updateInfo(setBlock.lhs, null);
      alternate.info.updateInfo(setBlock.lhs, null);
      // If it's already queued, changing its zone/order might leave the priority queue in an
      // inconsistent state.
      cb.cancelProp(setBlock);
      // Re-link it on the appropriate test branch
      boolean onNext = next.isLive(setBlock.lhs);
      assert alternate.isLive(setBlock.lhs) != onNext;
      Link branch = onNext ? next : alternate;
      Block branchDst = (Block) branch.detach();
      // But first we have to figure out what zone to put it in
      // We can pick the TestBlock's zone, the following block's zone, or anything in between;
      // we pick one that minimizes loop containment
      Zone newZone = chooseZone(zone(), branchDst.zone());
      int newOrder = (newZone == branchDst.zone()) ? branchDst.order() - 1 : order() + 1;
      setBlock.setZone(newZone);
      setBlock.setOrder(newOrder);
      // We may have to tweak the order of this TestBlock (and possibly some blocks we skipped over)
      // to maintain the link ordering requirement
      for (Block b = this; b.zone() == newZone && b.order() == newOrder; b = b.inLink.origin) {
        b.setOrder(--newOrder);
        if (b == afterSet) {
          break;
        }
        assert !b.hasMultipleInlinks();
      }
      branch.setTarget(setBlock);
      setBlock.next.setTarget(branchDst);
      // Since the SetBlock's outlink has changed we need to recompute its live set
      cb.setReversePropNeeded(setBlock);
      // That will probably end up causing us to re-run updateLive() for this block as well, but
      // force it to ensure that we check to see if there's another SetBlock that can be moved.
      cb.setReversePropNeeded(this);
      ++cb.numMoved;
      return true;
    }
    return false;
  }

  private static Zone chooseZone(Zone z1, Zone z2) {
    if (z1 == z2) {
      return z1;
    }
    assert z1.index < z2.index;
    if (z1.containingLoop == null || z1.containingLoop.contains(z2)) {
      return z1;
    } else if (z2.containingLoop == null || z2.containingLoop.contains(z1)) {
      return z2;
    }
    for (int i = z1.index + 1; i < z2.index; i++) {
      Zone z = z1.cb.zone(i);
      if (z.containingLoop == null
          || (z.containingLoop.contains(z1) && z.containingLoop.contains(z2))) {
        return z;
      }
    }
    throw new AssertionError();
  }

  @Override
  public SubstitutionOutcome trySubstitute(Register register, CodeValue value) {
    // We can only substitute if the register appears once in one of our values, and is not
    // live on either of our branches.
    if (next.isLive(register) || alternate.isLive(register)) {
      return SubstitutionOutcome.NO;
    }
    SubstitutionOutcome v1check = value1.couldSubstitute(register, value);
    SubstitutionOutcome result =
        (value2 == null || v1check == SubstitutionOutcome.NO)
            ? v1check
            : v1check.combine(value2.couldSubstitute(register, value));
    if (result == SubstitutionOutcome.YES) {
      if (v1check == SubstitutionOutcome.YES) {
        value1 = value1.substitute(register, value);
      } else {
        value2 = value2.substitute(register, value);
      }
    }
    return result;
  }

  @Override
  public final Block emit(Emitter emitter) {
    return emitter.conditionalBranch(emitTest(emitter), next, alternate);
  }

  @Override
  public String linksToString(CodeBuilder.PrintOptions options) {
    if (options.isLinkToNextBlock(next)) {
      return String.format("; F:%s", alternate.toString(options));
    } else if (options.isLinkToNextBlock(alternate)) {
      return String.format("; T:%s", next.toString(options));
    } else {
      return String.format("; T:%s, F:%s", next.toString(options), alternate.toString(options));
    }
  }

  /**
   * See if this test's result is known given the info associated with the given inlink. If so, move
   * the link to skip the test.
   */
  private void checkInlink(Link link) {
    assert link.target() == this;
    Link outLink = chooseOutlink(link.info.registers());
    if (outLink != null) {
      cb().moveLink(link, outLink.target());
    }
  }

  /**
   * Returns {@link #next} or {@link #alternate} if the outcome of this TestBlock is determined for
   * an inlink with the given infos, otherwise null.
   */
  @Nullable Link chooseOutlink(IntFunction<ValueInfo> infos) {
    CodeValue v1 = value1.simplify(infos);
    ValueInfo info2 = null;
    if (value2 != null) {
      CodeValue v2 = value2.simplify(infos);
      if (v1.equals(v2)) {
        return isReflexive() ? next : alternate;
      }
      info2 = v2.info(infos);
    }
    ValueInfo info1 = v1.info(infos);
    assert info1 != CodeValue.NONE && info2 != CodeValue.NONE;
    Boolean testResult = test(info1, info2);
    if (testResult != null) {
      return testResult ? next : alternate;
    }
    return null;
  }

  /** A TestBlock that does the equivalent of a Java == comparison. */
  public static class IsEq extends TestBlock {
    public final OpCodeType opCodeType;

    /** Creates an IsEq block comparing the given values after casting them to the given type. */
    public IsEq(OpCodeType opCodeType, CodeValue value1, CodeValue value2) {
      super(value1, value2);
      Preconditions.checkArgument(value2 != null);
      this.opCodeType = opCodeType;
    }

    @Override
    protected @Nullable Boolean test(ValueInfo info1, ValueInfo info2) {
      if (info1 instanceof Const c1) {
        if (!info2.containsConst(c1)) {
          return false;
        } else if (info2 instanceof Const) {
          return true;
        }
      } else if (info2 instanceof Const c2) {
        if (!info1.containsConst(c2)) {
          return false;
        }
      } else if (info1 != info2 && !cb().binaryOps.mightIntersect(info1, info2)) {
        return false;
      }
      return null;
    }

    @Override
    protected boolean isReflexive() {
      return true;
    }

    @Override
    protected void binarySimplify() {
      // We only try to do this simplification for integer comparisons.
      if (opCodeType != OpCodeType.INT) {
        return;
      }
      // If one of the values is a constant and the other is an addition or subtraction of a
      // constant, we can rearrange to get a simpler equality (giving both simpler code and
      // potentially some info propagation).
      if (value2 instanceof Const) {
        // The code below assumes that value2 is the constant, so we don't need to do anything here
      } else if (value1 instanceof Const && value2 instanceof Op.Result) {
        // Swap them
        CodeValue temp = value1;
        value1 = value2;
        value2 = temp;
      } else {
        return;
      }
      while (value1 instanceof Op.Result) {
        Op.Result opResult = (Op.Result) value1;
        if (opResult.op == Op.ADD_INTS || opResult.op == Op.SUBTRACT_INTS) {
          CodeValue arg0 = opResult.args.get(0);
          CodeValue arg1 = opResult.args.get(0);
          if (arg0 instanceof Const) {
            // The test is arg0 + arg1 == value2 or arg0 - arg1 == value2, where arg0 and value2
            // are constants.
            // If it was arg0 + arg1 == value2, transform it to arg1 == value2 - arg0;
            // if it was arg0 - arg1 == value2, transform it to arg1 == arg0 - value2.
            // Either way we can simplify the right hand side to an integer constant.
            int i1 = arg0.iValue();
            int i2 = value2.iValue();
            value2 = CodeValue.of(opResult.op == Op.SUBTRACT_INTS ? i1 - i2 : i2 - i1);
            value1 = arg1;
            // If arg1 (the new value1) was another addition or subtraction we may be able to do
            // that again.
            continue;
          } else if (arg1 instanceof Const) {
            // The test is arg0 + arg1 == value2 or arg0 - arg1 == value2, where arg1 and value2
            // are constants.
            // If it was arg0 + arg1 == value2, transform it to arg0 == value2 - arg1;
            // if it was arg0 - arg1 == value2, transform it to arg0 == value2 + arg1.
            // Either way we can simplify the right hand side to an integer constant.
            int i1 = arg1.iValue();
            int i2 = value2.iValue();
            value2 = CodeValue.of(opResult.op == Op.SUBTRACT_INTS ? i1 + i2 : i2 - i1);
            value1 = arg0;
            // If arg0 (the new value1) was another addition or subtraction we may be able to do
            // that again.
            continue;
          }
        }
        break;
      }
    }

    @Override
    protected void updateInfos(ValueInfo info1, ValueInfo info2) {
      if (info1 instanceof Const c1) {
        if (value2 instanceof Register r2) {
          updateForEqConst(r2, info2, c1);
        }
      } else if (info2 instanceof Const c2) {
        if (value1 instanceof Register r1) {
          updateForEqConst(r1, info1, c2);
        }
      } else {
        // Both infos are non-Const
        ValueInfo intersected = cb().binaryOps.intersection(info1, info2);
        if (value1 instanceof Register r1 && intersected != info1) {
          next.info.updateInfo(r1, intersected);
        }
        if (value2 instanceof Register r2 && intersected != info2) {
          next.info.updateInfo(r2, intersected);
        }
      }
    }

    /**
     * This TestBlock is comparing {@code r} (currently {@code info}) with {@code value}; update our
     * outlink infos accordingly.
     */
    private void updateForEqConst(Register r, ValueInfo info, Const value) {
      // If the test succeeds, we know exactly what r is.
      next.info.updateInfo(r, value);
      // If it fails, we may or may not be able to represent the additional information.
      ValueInfo ifFalse = info.removeConst(value);
      if (ifFalse != info) {
        alternate.info.updateInfo(r, ifFalse);
      }
    }

    @Override
    public ConditionalBranch emitTest(Emitter emitter) {
      switch (opCodeType) {
        case INT:
          return emitter.intCompare(
              value1, value2, ConditionalBranch.IF_ICMPEQ, ConditionalBranch.IF_ICMPEQ);
        case LONG:
        case FLOAT:
        case DOUBLE:
          // For these types we first use the type-specific comparison instruction (LCMP, FCMPL, or
          // DCMPL) which pushes an int, and then use IFEQ to see if the int is zero.
          value1.push(emitter, opCodeType.javaType);
          value2.push(emitter, opCodeType.javaType);
          emitter.mv.visitInsn(opCodeType.compareOpcode);
          return ConditionalBranch.IFEQ;
        case OBJ:
          // Comparing a pointer with null is slightly simpler than comparing it with anything else
          if (value1.equals(CodeValue.NULL)) {
            value2.push(emitter, Object.class);
            return ConditionalBranch.IFNULL;
          } else if (value2.equals(CodeValue.NULL)) {
            value1.push(emitter, Object.class);
            return ConditionalBranch.IFNULL;
          } else {
            value1.push(emitter, Object.class);
            value2.push(emitter, Object.class);
            return ConditionalBranch.IF_ACMPEQ;
          }
      }
      throw new AssertionError();
    }

    @Override
    public String toString(CodeBuilder.PrintOptions options) {
      return String.format("test %s == %s", value1.toString(options), value2.toString(options));
    }
  }

  /** A TestBlock that does the equivalent of a Java < comparison. */
  public static class IsLessThan extends TestBlock {
    final OpCodeType opCodeType;

    public IsLessThan(OpCodeType opCodeType, CodeValue value1, CodeValue value2) {
      super(value1, value2);
      Preconditions.checkArgument(opCodeType != OpCodeType.OBJ && value2 != null);
      this.opCodeType = opCodeType;
    }

    @Override
    protected @Nullable Boolean test(ValueInfo info1, ValueInfo info2) {
      Number max1 = info1.maximum();
      Number min2 = info2.minimum();
      if (max1 != null && min2 != null && max1.doubleValue() < min2.doubleValue()) {
        return true;
      }
      Number min1 = info1.minimum();
      Number max2 = info2.maximum();
      if (min1 != null && max2 != null && min1.doubleValue() >= max2.doubleValue()) {
        return false;
      }
      return null;
    }

    @Override
    protected boolean isReflexive() {
      return false;
    }

    @Override
    protected void updateInfos(ValueInfo info1, ValueInfo info2) {
      // We only add infos for integral types
      if (opCodeType != OpCodeType.INT && opCodeType != OpCodeType.LONG) {
        return;
      }
      if (value1 instanceof Register) {
        Number max2 = info2.maximum();
        if (max2 != null) {
          // If we took the true branch (value1 < value2), value1 must be less than
          // the maximum possible value of value2
          ValueInfo info = cb().binaryOps.intersection(info1, IntRange.lessThan(max2, false));
          if (info != info1) {
            next.info.updateInfo(((Register) value1), info);
          }
        }
        Number min2 = info2.minimum();
        if (min2 != null) {
          // If we took the false branch (value1 >= value2), value1 must be greater than or equal to
          // the minimum possible value of value2
          ValueInfo info = cb().binaryOps.intersection(info1, IntRange.greaterThan(min2, true));
          if (info != info1) {
            alternate.info.updateInfo(((Register) value1), info);
          }
        }
      }
      if (value2 instanceof Register) {
        Number min1 = info1.minimum();
        if (min1 != null) {
          // If we took the true branch (value1 < value2), value2 must be greater than
          // the minimum possible value of value1
          ValueInfo info = cb().binaryOps.intersection(info2, IntRange.greaterThan(min1, false));
          if (info != info2) {
            next.info.updateInfo(((Register) value2), info);
          }
        }
        Number max1 = info1.maximum();
        if (max1 != null) {
          // If we took the false branch (value1 >= value2), value2 must be less than or equal to
          // the maximum possible value of value1
          ValueInfo info = cb().binaryOps.intersection(info2, IntRange.lessThan(max1, true));
          if (info != info2) {
            alternate.info.updateInfo(((Register) value2), info);
          }
        }
      }
    }

    @Override
    public ConditionalBranch emitTest(Emitter emitter) {
      if (opCodeType == OpCodeType.INT) {
        return emitter.intCompare(
            value1, value2, ConditionalBranch.IF_ICMPLT, ConditionalBranch.IF_ICMPGT);
      } else {
        // For non-int types we first use the type-specific comparison instruction (LCMP, FCMPL, or
        // DCMPL) which pushes an int, and then use IFLT to see if the int is negative.
        value1.push(emitter, opCodeType.javaType);
        value2.push(emitter, opCodeType.javaType);
        emitter.mv.visitInsn(opCodeType.compareOpcode);
        return ConditionalBranch.IFLT;
      }
    }

    @Override
    public String toString(CodeBuilder.PrintOptions options) {
      return String.format("test %s < %s", value1.toString(options), value2.toString(options));
    }
  }

  /** A TestBlock that checks if an int's value is in 0..255. */
  public static class IsUint8 extends TestBlock {
    public IsUint8(CodeValue value) {
      super(value, null);
    }

    @Override
    public ConditionalBranch emitTest(Emitter emitter) {
      value1.push(emitter, int.class);
      emitter.pushI(~255);
      emitter.mv.visitInsn(Opcodes.IAND);
      return ConditionalBranch.IFEQ;
    }

    @Override
    protected Boolean test(ValueInfo info, ValueInfo info2) {
      assert info2 == null;
      Number min = info.minimum();
      Boolean result = true;
      if (min == null) {
        result = null;
      } else {
        long iMin = IntRange.bound(min, false, true);
        if (iMin > 255) {
          // Definitely not a uint8
          return false;
        } else if (iMin < 0) {
          // Maybe not a uint8
          result = null;
        }
      }
      Number max = info.maximum();
      if (max == null) {
        result = null;
      } else {
        long iMax = IntRange.bound(max, true, true);
        if (iMax < 0) {
          // Definitely not a uint8
          return false;
        } else if (iMax > 255) {
          // Maybe not a uint8
          result = null;
        }
      }
      return result;
    }

    @Override
    protected void updateInfos(ValueInfo info1, ValueInfo info2) {
      assert info2 == null;
      if (value1 instanceof Register) {
        ValueInfo info = cb().binaryOps.intersection(IntRange.UINT8, info1);
        next.info.updateInfo(((Register) value1), info);
      }
    }

    @Override
    public String toString(CodeBuilder.PrintOptions options) {
      return String.format("test %s isUint8", value1.toString(options));
    }
  }

  /** A TestBlock that checks if a small int's value is in a given set. */
  public static class TagCheck extends TestBlock {

    /** The tested value is assumed to be between zero (inclusive) and {@code limit} (exclusive). */
    final int limit;

    /** The possible values if the test passes. */
    final ValueInfo trueInfo;

    /** The possible values if the test fails. */
    final ValueInfo falseInfo;

    /**
     * The intersection of {@link #trueInfo} with what is currently known about the tested value.
     */
    private ValueInfo ifTrueLatest;

    /**
     * The intersection of {@link #falseInfo} with what is currently known about the tested value.
     */
    private ValueInfo ifFalseLatest;

    /**
     * Creates a test that succeeds if {@code value1} (which is assumed to be in {@code 0..limit-1})
     * has one of the values in {@code trueBits}.
     */
    public TagCheck(CodeValue value1, int limit, Bits trueBits) {
      super(value1, null);
      Preconditions.checkArgument(trueBits.max() < limit && limit <= SmallIntSet.LIMIT + 1);
      this.limit = limit;
      this.trueInfo = asInfo(trueBits);
      this.falseInfo = asInfo(Bits.Op.DIFFERENCE.apply(Bits.forRange(0, limit - 1), trueBits));
    }

    private static ValueInfo asInfo(Bits bits) {
      return bits.count() == 1 ? CodeValue.of(bits.min()) : new SmallIntSet(bits);
    }

    @Override
    protected Boolean test(ValueInfo info1, ValueInfo info2) {
      assert info2 == null;
      if (cb().binaryOps.containsAll(trueInfo, info1)) {
        return true;
      } else if (cb().binaryOps.containsAll(falseInfo, info1)) {
        return false;
      }
      assert !(info1 instanceof Const);
      return null;
    }

    @Override
    protected void updateInfos(ValueInfo info1, ValueInfo info2) {
      assert info2 == null;
      ifTrueLatest = cb().binaryOps.intersection(info1, trueInfo);
      ifFalseLatest = cb().binaryOps.intersection(info1, falseInfo);
      // If either of those was NONE, we would have returned non-null from test() and the block
      // would have been removed.
      assert ifTrueLatest != CodeValue.NONE && ifFalseLatest != CodeValue.NONE;
      next.info.updateInfo(((Register) value1), ifTrueLatest);
      alternate.info.updateInfo(((Register) value1), ifFalseLatest);
    }

    @Override
    public ConditionalBranch emitTest(Emitter emitter) {
      // If either the true set or the false set is a singleton, we just need an equality check.
      if (ifTrueLatest instanceof Const c) {
        value1.push(emitter, int.class);
        return emitter.pushForCompare(ConditionalBranch.IF_ICMPEQ, c.iValue());
      } else if (ifFalseLatest instanceof Const c) {
        value1.push(emitter, int.class);
        return emitter.pushForCompare(ConditionalBranch.IF_ICMPNE, c.iValue());
      }
      // Otherwise they should be both be SmallIntSets
      Bits ifTrueBits = ((SmallIntSet) ifTrueLatest).bits;
      int trueMin = ifTrueBits.min();
      int trueMax = ifTrueBits.max();
      Bits ifFalseBits = ((SmallIntSet) ifFalseLatest).bits;
      int falseMin = ifFalseBits.min();
      int falseMax = ifFalseBits.max();
      // If all elements of one set are less than all elements of the other set we can use a single
      // inequality check.
      if (trueMax < falseMin) {
        value1.push(emitter, int.class);
        return emitter.pushForCompare(ConditionalBranch.IF_ICMPLE, trueMax);
      } else if (trueMin > falseMax) {
        value1.push(emitter, int.class);
        return emitter.pushForCompare(ConditionalBranch.IF_ICMPGE, trueMin);
      }
      // The fallback is to use multiple == checks to see if tag is in the smaller set.  I expect
      // that it is rare for the smaller set to have more than 2 elements, but if it did we might
      // be better off with a shift & and test (when max < 64) or even a call to Bits.test()
      Bits toTest;
      Link link;
      if (ifTrueBits.count() <= ifFalseBits.count()) {
        toTest = ifTrueBits;
        link = next;
      } else {
        toTest = ifFalseBits;
        link = alternate;
      }
      int last = toTest.max();
      Label dst = emitter.jumpTo(link.targetBlock());
      for (int i : toTest) {
        value1.push(emitter, int.class);
        ConditionalBranch cb = emitter.pushForCompare(ConditionalBranch.IF_ICMPEQ, i);
        if (i == last) {
          return (link == next) ? cb : cb.invert();
        }
        emitter.mv.visitJumpInsn(cb.opcode, dst);
      }
      throw new AssertionError();
    }

    @Override
    public String toString(CodeBuilder.PrintOptions options) {
      return String.format("test %s %s (< %s)", value1.toString(options), trueInfo, limit);
    }
  }
}
