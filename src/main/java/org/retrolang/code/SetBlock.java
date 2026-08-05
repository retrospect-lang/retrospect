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
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeValue.Const;

/// A block that sets the value of a register.
public class SetBlock extends Block.NonTerminal {
  public final Register lhs;
  private CodeValue rhs;

  /// A cache of `rhs.sideEffect()`.
  private boolean hasSideEffect;

  /// Creates a SetBlock that sets `lhs` to `rhs`. If `lhs` is null, evaluates the `rhs` and
  /// discards any result.
  public SetBlock(Register lhs, CodeValue rhs) {
    // The rhs must be storable in the register.
    Preconditions.checkArgument(lhs == null || rhs.isCompatible(lhs.type()));
    this.lhs = lhs;
    this.rhs = rhs;
    this.hasSideEffect = rhs.hasSideEffect();
    // A null lhs only makes sense if the expression has a side effect.
    Preconditions.checkArgument(lhs != null || hasSideEffect);
  }

  /// Returns true if executing this SetBlock may have a side effect in addition to setting the
  /// value of [#lhs].
  boolean hasSideEffect() {
    return hasSideEffect;
  }

  @Override
  public int numInputs() {
    return 1;
  }

  @Override
  public CodeValue input(int index) {
    assert index == 0;
    return rhs;
  }

  @Override
  public void setInput(int index, CodeValue input) {
    assert index == 0;
    rhs = input;
  }

  @Override
  boolean isSimple() {
    // If a subclass wants to be simple it will have to override this
    return getClass() == SetBlock.class && (rhs instanceof Const || rhs instanceof Register);
  }

  @Override
  boolean isDuplicate(Block other) {
    // Don't match subclasses
    if (other.getClass() == SetBlock.class) {
      SetBlock otherSetBlock = (SetBlock) other;
      return lhs == otherSetBlock.lhs && rhs.equals(otherSetBlock.rhs);
    }
    return false;
  }

  @Override
  IntFunction<ValueInfo> simpleInfos(IntFunction<ValueInfo> fromInlink) {
    return i -> {
      if (i != lhs.index) {
        ValueInfo prev = fromInlink.apply(i);
        // Usually the info for a register other than our lhs remains valid, but
        // if the info was "copy of <lhs>" before this SetBlock we have to resolve it.
        return (prev != lhs) ? prev : fromInlink.apply(lhs.index);
      } else if (rhs instanceof Const) {
        return (CodeValue.Const) rhs;
      } else {
        Register reg = (Register) rhs;
        ValueInfo regInfo = fromInlink.apply(reg.index);
        if (regInfo == lhs) {
          return fromInlink.apply(lhs.index);
        } else if (regInfo instanceof Const || regInfo instanceof Register) {
          return regInfo;
        } else {
          return reg;
        }
      }
    };
  }

  @Override
  SetBlock duplicateImpl() {
    assert !(rhs instanceof Op.Result);
    return new SetBlock(lhs, rhs);
  }

  @Override
  protected PropagationResult updateInfo() {
    CodeValue origRhs = rhs;
    simplifyInputs(next.info.registers());
    // Simplification shouldn't add a side effect to a previously side-effect-free value
    assert hasSideEffect || !rhs.hasSideEffect();
    if (hasSideEffect) {
      hasSideEffect = rhs.hasSideEffect();
    }
    if (lhs == null) {
      // If there's no side effect and no lhs to update this is no-op.
      return hasSideEffect ? PropagationResult.DONE : PropagationResult.REMOVE;
    } else if (rhs == lhs) {
      // Assigning a register to itself is a no-op.
      return PropagationResult.REMOVE;
    } else if (next.hasSibling() && isSimple() && !rhs.equals(origRhs)) {
      // If we've simplified our rhs it's possible that this has become a duplicate of another
      // block with the same outlink
      for (Link nextSibling = next.sibling;
          nextSibling != next;
          nextSibling = nextSibling.sibling) {
        if (isDuplicate(nextSibling.origin)) {
          NonTerminal survivor = cb().mergeMatching(this, nextSibling.origin);
          cb().setForwardPropNeeded(survivor);
          // If this block was the survivor, we'll restart updateInfo() with our info recomputed
          // to include the new inlink; if the other block survived this block will have been
          // deleted.
          return PropagationResult.SKIP;
        }
      }
    }
    assert rhs.isCompatible(lhs.type());
    ValueInfo info = rhs.info(next.info.registers());
    // If we're just copying one register to another it's generally more useful to remember that
    // we copied it rather than just copying its info.
    if (rhs instanceof Register && !(info instanceof Const || info instanceof Register)) {
      info = (Register) rhs;
    }
    next.info.updateForAssignment(lhs, info, cb());
    next.info.modified(lhs, containingLoop());
    return PropagationResult.DONE;
  }

  @Override
  protected PropagationResult updateLive() {
    if (!hasSideEffect && !next.isLive(lhs)) {
      // Assigning a non-side-effecting expression to a register that is never used is a no-op.
      return PropagationResult.REMOVE;
    } else if (tryToEliminateBySubstituting()) {
      // If we were able to substitute the expression for the only occurrence of the register,
      // the set is no longer needed.
      return PropagationResult.REMOVE;
    }
    return super.updateLive();
  }

  /// A SetBlock whose lhs is only used once immediately after the assignment can usually be
  /// eliminated by just substituting the rhs for the lhs. Calls [Block#trySubstitute] on the
  /// following block(s) to see if we can apply that optimization here.
  private boolean tryToEliminateBySubstituting() {
    // If the rhs is a constant or a register any possible substitution will already have happened
    // due to simplification, so we only need to handle SetBlocks where the rhs is an Op.Result.
    // And we don't want to do any reordering if there are side effects involved.
    if (hasSideEffect || !(rhs instanceof Op.Result)) {
      return false;
    }
    // Look at the next block and see if we can substitute into it.
    Block toCheck = next.targetBlock();
    // If it has other inlinks we can't.
    while (!toCheck.hasMultipleInlinks()) {
      switch (toCheck.trySubstitute(lhs, rhs)) {
        case YES:
          cb().setReversePropNeeded(toCheck);
          return true;
        case NO:
          return false;
        case KEEP_TRYING:
          // There were no occurrences of our lhs in that block, but it was simple enough that we
          // can check the following block too.
          assert !(toCheck instanceof Split);
          toCheck = ((NonTerminal) toCheck).next.targetBlock();
      }
    }
    return false;
  }

  @Override
  protected SubstitutionOutcome trySubstitute(Register register, CodeValue value) {
    // Is the register that we would be substituting used after this block?
    // (If we're assigning to the same register, that's a no.)
    boolean usedAfter = (lhs != register) && next.isLive(register);
    if (usedAfter && hasSideEffect) {
      // If the register is used after this block, and our rhs has a side effect, we definitely
      // can't do a substitution.
      return SubstitutionOutcome.NO;
    }
    SubstitutionOutcome result = rhs.couldSubstitute(register, value);
    if (result == SubstitutionOutcome.YES) {
      if (usedAfter) {
        // We can't substitute here if it's also used later
        return SubstitutionOutcome.NO;
      }
      rhs = rhs.substitute(register, value);
      // That shouldn't have changed the type of our rhs
      assert lhs == null || rhs.isCompatible(lhs.type());
    }
    return result;
  }

  @Override
  public void forEachModifiedRegister(Consumer<Register> consumer) {
    if (lhs != null) {
      consumer.accept(lhs);
    }
  }

  @Override
  public boolean isNoOp() {
    if (lhs == null || !(rhs instanceof Register)) {
      return false;
    }
    boolean result =
        (lhs == rhs) || (lhs.hasJvmLocal() && lhs.jvmLocal() == ((Register) rhs).jvmLocal());
    assert !(result && hasSideEffect);
    return result;
  }

  @Override
  public Block emit(Emitter emitter) {
    if (lhs == null) {
      // We need to pass a type to push(), so see what type the rhs (which must be an Op.Result)
      // returns.
      Class<?> resultType = rhs.type();
      rhs.push(emitter, resultType);
      if (resultType != void.class) {
        // It returned a result, but we don't want it.
        emitter.mv.visitInsn(OpCodeType.localSize(resultType) == 1 ? Opcodes.POP : Opcodes.POP2);
      }
    } else {
      rhs.push(emitter, lhs.type());
      emitter.store(lhs);
    }
    return next.targetBlock();
  }

  @Override
  public String toString(CodeBuilder.PrintOptions options) {
    String s = rhs.toString(options);
    return (lhs == null) ? s : (lhs.toString(options) + " ← " + s);
  }

  /// A Split block that either sets the value of a register and follows the next link, or (if the
  /// rhs throws an exception) follows the alternate link.
  ///
  /// Note that (like a regular SetBlock), if the register is not used the block may be dropped (so
  /// the rhs will not be evaluated, and the alternate link will never be followed).
  public static class WithCatch extends Block.Split {
    public final Register lhs;
    private CodeValue rhs;

    public final Class<?> exception;

    /// Creates a SetBlock.WithCatch that sets `lhs` to `rhs`. If the evaluation of `rhs` throws
    /// `exception`, `lhs` will not be set and execution will continue at `onThrow`.
    public WithCatch(Register lhs, CodeValue rhs, Class<?> exception, FutureBlock onThrow) {
      // TODO: It wouldn't be hard to support lhs == null.
      // We don't currently try to support combining side effects with exceptions.
      Preconditions.checkArgument(lhs != null && !rhs.hasSideEffect());
      this.lhs = lhs;
      this.rhs = rhs;
      this.exception = exception;
      alternate.setTarget(onThrow);
    }

    @Override
    public int numInputs() {
      return 1;
    }

    @Override
    public CodeValue input(int index) {
      assert index == 0;
      return rhs;
    }

    @Override
    public void setInput(int index, CodeValue input) {
      assert index == 0;
      rhs = input;
    }

    @Override
    protected PropagationResult updateInfo() {
      CodeValue prevRhs = rhs;
      simplifyInputs(next.info.registers());
      if (rhs instanceof CodeValue.Thrown) {
        // The rhs will always throw.  Unlikely, but perhaps possible.
        moveAllInLinks(alternate.target());
        return PropagationResult.REMOVE;
      }
      assert rhs.isCompatible(lhs.type());
      if (rhs != prevRhs && !rhs.canThrow()) {
        // The rhs simplified to something that no longer calls anything that could throw;
        // replace this with a regular SetBlock.
        return replaceWith(new SetBlock(lhs, rhs));
      }
      ValueInfo info = rhs.info(next.info.registers());
      next.info.updateForAssignment(lhs, info, cb());
      next.info.modified(lhs, containingLoop());
      // alternate.info is unchanged from our inlinks
      return PropagationResult.DONE;
    }

    @Override
    protected PropagationResult updateLive() {
      if (!next.isLive(lhs)) {
        // lhs is unused, so delete it all
        return PropagationResult.REMOVE;
      }
      return super.updateLive();
    }

    @Override
    public void forEachModifiedRegister(Consumer<Register> consumer) {
      consumer.accept(lhs);
    }

    @Override
    public Block emit(Emitter emitter) {
      Label before = emitter.jumpTo(this);
      Label after = new Label();
      Label handler = emitter.exceptionHandler(alternate.targetBlock());
      emitter.mv.visitTryCatchBlock(
          before, after, handler, org.objectweb.asm.Type.getInternalName(exception));
      rhs.push(emitter, lhs.type());
      emitter.mv.visitLabel(after);
      emitter.store(lhs);
      return next.targetBlock();
    }

    @Override
    public String toString(CodeBuilder.PrintOptions options) {
      return String.format("%s ← %s", lhs.toString(options), rhs.toString(options));
    }

    @Override
    public String linksToString(CodeBuilder.PrintOptions options) {
      if (options.isLinkToNextBlock(next)) {
        return String.format("; %s:%s", exception.getSimpleName(), alternate.toString(options));
      } else {
        return String.format(
            "; %s, %s:%s",
            next.toString(options), exception.getSimpleName(), alternate.toString(options));
      }
    }
  }
}
