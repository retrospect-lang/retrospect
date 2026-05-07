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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import org.retrolang.code.CodeValue.Const;
import org.retrolang.code.ValueInfo.BinaryOps;
import org.retrolang.util.Bits;
import org.retrolang.util.SmallIntMap;
import org.retrolang.util.SmallIntMap.EntryUpdater;
import org.retrolang.util.SmallIntMapBase;

/**
 * A LinkInfo represents what the CodeBuilder is able to infer about the state of execution at the
 * beginning of a Block. LinkInfos are mutable.
 *
 * <p>LinkInfos currently hold two types of information:
 *
 * <ul>
 *   <li>A ValueInfo for each register that has been assigned a value.
 *   <li>A Bits identifying which loop registers have been modified since the start of each
 *       enclosing loop.
 * </ul>
 *
 * The former is used to simplify CodeValues and to skip redundant TestBlocks; the latter is used to
 * identify loop registers that don't actually change in the loop body.
 *
 * <p>A newly created LinkInfo is invalid. Most LinkInfos are made valid by copying information into
 * them from other LinkInfos (with {@link #setFrom} or {@link #swapContents}), or a LinkInfo can be
 * made empty-but-valid using {@code clear(true)}.
 */
public class LinkInfo {
  /**
   * What is known about the value of each register that has been assigned a value.
   *
   * <p>We use an unshared, mutable object for this since it is changed by almost every block; there
   * would be little opportunity to share these maps between LinkInfos.
   */
  private SmallIntMap.Builder<ValueInfo> registers = new SmallIntMap.Builder<>();

  /**
   * Loop registers that have been modified since the start of the current loop iteration, in any
   * enclosing loop. Empty if this link's origin is not in a loop.
   *
   * <p>We use an immutable object here because these change relatively rarely, so being able to
   * share them (rather than making defensive copies) can save memory.
   *
   * <p>If null the LinkInfo is invalid, and many operations on it will throw an exception.
   */
  private Bits loopModified;

  /** Returns true if this LinkInfo has been initialized. */
  public boolean isValid() {
    return loopModified != null;
  }

  /** Returns the ValueInfo for the register with the given index. */
  public ValueInfo register(int i) {
    assert isValid();
    return registers.get(i);
  }

  /**
   * If {@code info} is a Register, returns the corresponding entry from {@link #registers};
   * otherwise just returns {@code info}.
   */
  public ValueInfo resolve(ValueInfo info) {
    assert isValid();
    return resolve(info, registers);
  }

  /**
   * If {@code info} is a Register, returns the corresponding entry from {@code infos}; otherwise
   * just returns {@code info}.
   */
  public static ValueInfo resolve(ValueInfo info, IntFunction<ValueInfo> infos) {
    if (info instanceof Register) {
      info = infos.apply(((Register) info).index);
      assert info != null && !(info instanceof Register);
    }
    return info;
  }

  /** Returns the info for all registers. */
  public SmallIntMapBase<ValueInfo> registers() {
    assert isValid();
    return registers;
  }

  /** Sets the info for the given register. */
  public void updateInfo(Register register, ValueInfo info) {
    updateInfo(register.index, info);
  }

  /** Sets the info for the register with the given index. */
  void updateInfo(int registerIndex, ValueInfo info) {
    assert isValid();
    // Ensure that we're not going to create a consistencyCheck() violation
    assert !isInvalidCopy(registerIndex, info);
    registers.put(registerIndex, info);
  }

  /**
   * Returns true if the specified loop register may have been modified by the time this link is
   * reached.
   */
  boolean isModifiedInLoop(int register) {
    assert isValid();
    return loopModified.test(register);
  }

  /** Initializes this LinkInfo to be a copy of another. */
  void setFrom(LinkInfo other) {
    registers.setFrom(other.registers);
    loopModified = other.loopModified;
  }

  /** Returns true if this LinkInfo has the same contents as {@code other}. */
  boolean sameContents(LinkInfo other) {
    if (!isValid()) {
      return !other.isValid();
    }
    return other.isValid()
        && registers.equals(other.registers)
        && loopModified.equals(other.loopModified);
  }

  /**
   * Returns true if this LinkInfo is "close enough" to {@code other}, as defined by {@link
   * ValueInfo#isCloseEnoughTo}.
   */
  boolean isCloseEnoughTo(LinkInfo other) {
    return isValid()
        && other.isValid()
        && registers.matches(other.registers, ValueInfo::isCloseEnoughTo)
        && loopModified.equals(other.loopModified);
  }

  /**
   * Resets this LinkInfo to its initial (no information) state. If {@code valid} is true the
   * LinkInfo will be valid; otherwise it will be invalid.
   */
  void clear(boolean valid) {
    registers.clear();
    loopModified = valid ? Bits.EMPTY : null;
  }

  /** Exchanges the contents of two LinkInfos. */
  void swapContents(LinkInfo other) {
    SmallIntMap.Builder<ValueInfo> tRegisters = this.registers;
    Bits tLoopModified = this.loopModified;
    this.registers = other.registers;
    this.loopModified = other.loopModified;
    other.registers = tRegisters;
    other.loopModified = tLoopModified;
  }

  /**
   * If {@code lhs} is one of the updated registers for {@code containingLoop} or one of its
   * parents, records that it has been modified.
   */
  public void modified(Register lhs, Loop containingLoop) {
    assert isValid();
    if (containingLoop != null && containingLoop.allRegisters.test(lhs.index)) {
      loopModified = loopModified.set(lhs.index);
    }
  }

  /**
   * Updates this LinkInfo to reflect the assignment to {@code lhs} of a value described by {@code
   * rhs}.
   */
  public void updateForAssignment(Register lhs, ValueInfo rhs, CodeBuilder cb) {
    assert isValid();
    // Assignments of a register to itself should already have been optimized away.
    assert lhs != rhs;
    // First make sure that no other registers are represented as copies of the one
    // we're about to change
    ValueInfo prev = registers.get(lhs.index);
    if (prev == null || prev instanceof Register) {
      // The easy case -- no other register should be marked as a copy of this one
    } else {
      // lhs already had a value, so someone might have copied it; find any copies and fix them.
      registers.updateEntries(eliminateDependencies(lhs, cb));
    }
    // Now updating lhs should not change anything else.
    updateInfo(lhs, rhs);
  }

  /**
   * Resolves any registers that are saved as copies of an element of {@code modifiedRegisters};
   * used before entering a loop that may change those registers.
   */
  void ensureNoDependencies(Bits modifiedRegisters, CodeBuilder cb) {
    assert isValid();
    registers.updateEntries(eliminateDependencies(modifiedRegisters, cb));
  }

  /**
   * Returns true if info is a copy but
   *
   * <ul>
   *   <li>the copied register's info is missing, NONE, or is another copy; or
   *   <li>the copied register's index is {@code index}; or
   *   <li>some other register's info is the register with index {@code index}.
   * </ul>
   *
   * Used only for assertions.
   */
  private boolean isInvalidCopy(int index, ValueInfo info) {
    if (info instanceof Register r) {
      if (r.index == index) {
        // We're about to set a register to be a copy of itself
        return true;
      }
      ValueInfo regInfo = register(r.index);
      if (regInfo == null || regInfo instanceof Register || regInfo == CodeValue.NONE) {
        // info is a copy of a copy, of NONE, or of a missing register
        return true;
      } else if (index >= 0
          && registers
              .streamValues()
              .anyMatch(rInfo -> (rInfo instanceof Register r2 && r2.index == index))) {
        // Some other register is a copy of this one, so we can't make this a copy
        return true;
      }
    }
    return false;
  }

  /**
   * Returns an EntryUpdater that replaces any entries that are copies of {@code lhs}. The first one
   * encountered will be replaced with the current info for {@code lhs}; any subsequent ones will be
   * replaced with references to the first one. This should be done before the value of {@code lhs}
   * is changed; it preserves as much information about the values of other registers as possible.
   */
  private EntryUpdater<ValueInfo> eliminateDependencies(Register lhs, CodeBuilder cb) {
    return new EntryUpdater<ValueInfo>() {
      /**
       * After we've found a first occurrence of lhs, this will be set to the register that was a
       * copy of it (and now has its info); any additional occurrences will just refer to it.
       */
      Register newCopy;

      @Override
      public ValueInfo apply(int key, ValueInfo info) {
        if (info != lhs) {
          // Anything except a reference to lhs is left unchanged
          return info;
        } else if (newCopy == null) {
          // This is the first reference we've found to lhs; returns its info, but remember
          // which register this is.
          newCopy = cb.register(key);
          return register(lhs.index);
        } else {
          // We've already seen at least one other occurrence of lhs; make the extras just refer
          // to the first one.
          return newCopy;
        }
      }
    };
  }

  /**
   * Like {@link #eliminateDependencies(Register, CodeBuilder)}, but for a set of registers.
   * Applying this updater is equivalent to applying a separate updater for each element in {@code
   * registers}.
   */
  private EntryUpdater<ValueInfo> eliminateDependencies(Bits registers, CodeBuilder cb) {
    assert !registers.isEmpty();
    int size = registers.count();
    if (size == 1) {
      // We can use the simpler version
      return eliminateDependencies(cb.register(registers.min()), cb);
    }
    // We need track the replacement of each register separately.
    Register[] newCopies = new Register[size];
    return (key, info) -> {
      if (!(info instanceof Register r)) {
        return info;
      }
      int index = r.index;
      if (!registers.test(index)) {
        return info;
      }
      // This is the easiest way to map each element of registers to a distinct index
      // in 0 .. size-1
      int i = registers.countGreaterThanOrEq(index + 1);
      Register newCopy = newCopies[i];
      if (newCopy == null) {
        // Unless this is also one of the registers being eliminated, any further references
        // to index should refer to this register.
        if (!registers.test(key)) {
          newCopies[i] = cb.register(key);
        }
        return register(index);
      } else {
        return newCopy;
      }
    };
  }

  /**
   * Verify that an update to a LinkInfo is consistent, i.e. that we have not lost any information
   * that might previously have been used to make an optimization. Used only for assertions.
   *
   * @param prev A copy of this LinkInfo's previous state
   * @param target If non-null, the block that will be executed with this LinkInfo
   */
  boolean isConsistentChange(LinkInfo prev, Block target, BinaryOps binaryOps) {
    assert isValid();
    if (!prev.isValid()) {
      assert prev.registers.isEmpty();
      // We had no information before, so nothing to check.
      return true;
    }
    // If we have information about which registers are live, restrict our checks to them; registers
    // that we've decided are not live may have had some of their SetBlocks removed which could
    // appear as an inconsistent update.
    IntPredicate liveCheck;
    if (target != null && target.live != null) {
      // Restrict our checks to live registers
      liveCheck = r -> target.isLive(r, null);
    } else {
      // Check all registers in prev
      liveCheck = r -> true;
    }
    // There's only one thread but an AtomicBoolean is the easiest way to get information
    // out of a forEachEntry().
    AtomicBoolean isValid = new AtomicBoolean(true);
    prev.registers.forEachEntry(
        (r, prevInfo) -> {
          if (liveCheck.test(r)) {
            ValueInfo info = register(r);
            if (info != null) {
              if (info.equals(prevInfo)) {
                return;
              }
              if (prevInfo instanceof Register prevReg && liveCheck.test(prevReg.index)) {
                ValueInfo otherInfo = register(prevReg.index);
                if ((info instanceof Const || info instanceof Register) && info.equals(otherInfo)) {
                  // Not explicitly a copy, but provably equal to the copied register
                  return;
                } else if (otherInfo instanceof Register otherReg && otherReg.index == r) {
                  return;
                }
              } else if (binaryOps.containsAll(prev.resolve(prevInfo), resolve(info))) {
                return;
              }
            }
            isValid.setPlain(false);
          }
        });
    return isValid.getPlain();
  }

  @Override
  public String toString() {
    if (loopModified == null) {
      return "(invalid)";
    }
    String s = "reg" + registers;
    if (loopModified.isEmpty()) {
      return s;
    }
    return s + " modified" + loopModified;
  }

  /**
   * A Unioner is used to combine a set of LinkInfos that correspond to multiple ways of reaching a
   * single point in the block graph; the result is a LinkInfo representing what can be known when
   * any of those paths could have been taken.
   *
   * <p>To use a Unioner, call {@link #start} with a LinkInfo that has been initialized with the
   * information corresponding to the first link; this LinkInfo will be modified to hold the final
   * result. Then call {@link #add} with each additional LinkInfo (these will not be modified by the
   * operation). Finally call {@link #finish}, which will return the same LinkInfo passed to {@link
   * #start} after ensuring that it now represents the resulting union.
   *
   * <p>After calling {@link #finish} a Unioner can be used for a new union operation.
   */
  public static class Unioner {
    public final CodeBuilder cb;

    /**
     * The LinkInfo that was passed to {@link #start}. The {@link #registers} of this LinkInfo are
     * updated after each call to {@link #add}, but its {@link LinkInfo#loopModified} isn't set
     * until the call to {@link #finish}.
     */
    private LinkInfo result;

    /** The Loop that was passed to {@link #start}. */
    private Loop containingLoop;

    /**
     * If {@link #containingLoop} is non-null, will contain the union of the {@link
     * LinkInfo#loopModified} fields from each LinkInfo we've seen so far. Not used if {@link
     * #containingLoop} is null.
     */
    private final Bits.Builder loopModified = new Bits.Builder();

    /**
     * Used during a call to {@link #add} to save a copy of {@code result.registers} before we start
     * modifying it.
     */
    private final SmallIntMap.Builder<ValueInfo> savedRegisters = new SmallIntMap.Builder<>();

    /**
     * Used during a call to {@link #add} to track cases where a register is assigned different
     * values but both are "simple" (i.e. a constant or register copy); if another register is
     * assigned the same pair of values we can insert a register copy.
     *
     * <p>This is really just a set (keys and values are identical), but making it a HashMap lets us
     * use {@link HashMap#putIfAbsent}.
     */
    private final Map<SimplePair, SimplePair> pairs = new HashMap<>();

    /**
     * Used during a call to {@link #add} to keep track of whether a new register copy has been
     * inserted.
     */
    private boolean addedCopy;

    public Unioner(CodeBuilder cb) {
      this.cb = cb;
    }

    /**
     * The first step in computing a union; {@code result} should contain the information from the
     * first link, and will be modified (by the time {link #finish} returns) to contain its union
     * with the information from the added links.
     */
    public void start(LinkInfo result, Loop containingLoop) {
      assert result.isValid();
      this.result = result;
      this.containingLoop = containingLoop;
      if (containingLoop != null) {
        loopModified.setAll(result.loopModified);
      }
    }

    /**
     * Adds the information from another LinkInfo.
     *
     * <p>A simple pairwise union of the register infos (after resolving copies) would be correct,
     * but might lose useful information about copying. For example, computing
     *
     * <pre>
     *    { r1:intRange(0, 2), r2:r1 }  union  { r1:intRange(1, 5), r2:r1 }
     * </pre>
     *
     * as
     *
     * <pre>
     *    { r1:intRange(0, 5), r2:intRange(0, 5) }
     * </pre>
     *
     * would not be wrong, but it would have lost the potentially valuable information that r1 and
     * r2 have the same value; we'd rather get
     *
     * <pre>
     *    { r1:intRange(0, 5), r2:r1 }
     * </pre>
     *
     * ... and indeed, it's pretty easy to do so by just keeping a register copy if it's identical
     * in the two LinkInfos.
     *
     * <p>There are many less obvious cases where we would also like to preserve the information
     * that two registers are the same; for example
     *
     * <pre>
     *    { r1:intRange(0, 2), r2:r1 }  union  { r1:r2, r2:intRange(1, 5) }
     * </pre>
     *
     * should also be
     *
     * <pre>
     *    { r1:intRange(0, 5), r2:r1 }
     * </pre>
     *
     * or (equivalently)
     *
     * <pre>
     *    { r1:r2, r2:intRange(0, 5) }
     * </pre>
     *
     * <p>Another less-obvious case:
     *
     * <pre>
     *    { r1:intRange(0, 2), r2:r1, r3:r1 } union { r1:intRange(1, 5), r2:intRange(1, 5), r3:r2 }
     * </pre>
     *
     * should be
     *
     * <pre>
     *    { r1:intRange(0, 5), r2:intRange(0, 5), r3:r2 }
     * </pre>
     *
     * (or the equivalent version with {@code r2:r3} and {@code r3:intRange(0, 5)} — but I'll stop
     * listing the equivalent variants). Although the first branch didn't explicitly say that r3 was
     * a copy of r2, that follows from them both being copies of r1. Note that even though r1 and r2
     * have the same range of possible values, in the second branch there is no evidence that they
     * are equal so the union must assume that they might not be.
     *
     * <p>Somewhat similar:
     *
     * <pre>
     *    { r1:intRange(0, 2), r2:r1 }  union  { r1:3, r2:3 }
     * </pre>
     *
     * should be
     *
     * <pre>
     *    { r1:intRange(0, 3), r2:r1 }
     * </pre>
     *
     * Again, the second branch doesn't explicitly say that r1 is a copy of r2, but if they both
     * have the same constant value (in this case, 3) it follows that they're equal.
     *
     * <p>One last pattern we want to catch:
     *
     * <pre>
     *    { r1:3, r2:3 }  union  { r1:4, r2:4 }
     * </pre>
     *
     * should be
     *
     * <pre>
     *    { r1:intRange(3, 4), r2:r1 }
     * </pre>
     *
     * In this case (which only occurs when a pair of registers gets assigned the same simple value
     * in each branch) we introduce a register copy where there was none before.
     */
    public void add(LinkInfo other) {
      assert other.isValid() && other != result;
      if (containingLoop != null) {
        Bits.Op.UNION.into(loopModified, other.loopModified);
      }
      pairs.clear();
      // True if we've replaced a non-Register in result with a Register, which could cause a
      // violation of the "no copy of a copy" rule.
      addedCopy = false;
      // Once we've started making changes to result we can no longer reliably resolve any
      // register copies, so we save a copy first.
      savedRegisters.setFrom(result.registers);
      // innerJoin will drop registers that only have a ValueInfo on one of the branches, which is
      // appropriate since such a register cannot be used.  On the other hand, a register assigned
      // NONE on one of the branches *can* be used, with the expectation that it will only be used
      // if the branch that assigned something other than NONE is taken.
      result.registers.innerJoin(
          other.registers,
          (k, info1, info2) -> {
            assert info1 != null && info2 != null && info1 != info2;
            // First handle the easy cases, where we don't need to worry about resolving registers
            if (info2 == CodeValue.NONE || info1 == ValueInfo.ANY) {
              return info1;
            } else if (info1 == CodeValue.NONE || info2 == ValueInfo.ANY) {
              return info2;
            }
            ValueInfo resolved1 = info1;
            if (info1 instanceof Register r1) {
              // We need to resolve info1 using the savedRegisters, not the ones we may already have
              // started modifying.
              resolved1 = savedRegisters.get(r1.index);
              if (resolved1 instanceof Const) {
                // There's no reason to keep a reference to a Const
                info1 = resolved1;
              }
            }
            ValueInfo resolved2 = info2;
            if (info2 instanceof Register r2) {
              resolved2 = other.register(r2.index);
              if (resolved2 instanceof Const) {
                info2 = resolved2;
              }
            }
            if (info1 instanceof Register r1) {
              // Even though info2 isn't r1, any of these would allow us to conclude that this
              // register is a copy of r1:
              // * other says that both this register and r1 are the same constant
              // * other says that both this register and r1 are copies of the same register
              // * other says that r1 is a copy of this register
              ValueInfo otherR1 = other.register(r1.index);
              if (otherR1 instanceof Register otherR1Reg) {
                if (otherR1 == info2 || otherR1Reg.index == k) {
                  return r1;
                }
              } else if (otherR1 instanceof Const && otherR1.equals(info2)) {
                return r1;
              }
              if (info2 instanceof Register r2) {
                // One last try: if result says that both this register and r2 are copies of the
                // same register we can keep "copy of r2" -- this is the reversed version of the
                //     if (otherR1 == info2 ...)
                // test above.
                if (savedRegisters.get(r2.index) == r1) {
                  addedCopy = true;
                  return r2;
                }
              }
            } else if (info1 instanceof Const) {
              // innerJoin() did an == check but not equals()
              if (info1.equals(info2)) {
                return info1;
              } else if (info2 instanceof Register r2) {
                // If savedRegisters has the same constant for rk and r2, we can keep k as a copy of
                // r2 -- this is the reversed version of the
                //     if (otherR1 instanceof Const && otherR1.equals(info2)) ...
                // test above.
                if (info1.equals(savedRegisters.get(r2.index))) {
                  addedCopy = true;
                  return r2;
                }
              }
            }
            if (isSimple(info1) && isSimple(info2)) {
              // If there is a register rx such that savedRegisters.get(rx).equals(info1) and
              // other.registers.get(rx).equals(info2), then we can use {@code rx} as the result of
              // the merge.
              // To do this we build a hashmap indexed by the (obj1, obj2) pair.
              // If this is the first we've seen this particular pair of constants we'll let them
              // merge, but if we've seen them before we'll use a reference to the previous
              // occurrence as the result.
              SimplePair pair = new SimplePair(info1, info2, k);
              SimplePair prev = pairs.putIfAbsent(pair, pair);
              if (prev != null) {
                addedCopy = true;
                return cb.register(prev.registerIndex);
              }
            }
            // Everything else is handled by BinaryOps.union()
            return cb.binaryOps.union(resolved1, resolved2);
          });
      // We're almost done, but if the first pass replaced any constants by registers there's a
      // chance that we've ended up with a copy-of-a-copy; we have to make sure those are cleaned
      // up before we return.
      if (addedCopy) {
        result.registers.updateValues(
            info -> {
              if (info instanceof Register r) {
                ValueInfo rInfo = result.registers.get(r.index);
                if (rInfo instanceof Register) {
                  return rInfo;
                }
              }
              return info;
            });
      }
      // By this point all copies should be valid.
      assert result.registers.streamValues().noneMatch(info -> result.isInvalidCopy(-1, info));
    }

    /**
     * Returns true if ValueInfo is a constant or a register copy. Two registers that have the same
     * simple info must be equal.
     */
    private static boolean isSimple(ValueInfo info) {
      return info instanceof Const || info instanceof Register;
    }

    /**
     * Modifies the LinkInfo that was passed to {@link #start} to contain the accumulated
     * information and returns it.
     */
    @CanIgnoreReturnValue
    public LinkInfo finish() {
      // At this point registers is correct; all we need to do is set loopModified
      if (containingLoop == null) {
        result.loopModified = Bits.EMPTY;
      } else {
        Bits.Op.INTERSECTION.into(loopModified, containingLoop.allRegisters);
        result.loopModified = loopModified.build();
      }
      LinkInfo result = this.result;
      this.result = null;
      return result;
    }
  }

  /**
   * An entry in the {@link Unioner#pairs} table. To reduce object allocation we use the same object
   * as both key and value; {@link #info1} and {@link #info2} constitute the key, and {@link
   * #registerIndex} is the value.
   */
  private static class SimplePair {
    final Object info1;
    final Object info2;
    final int registerIndex;

    SimplePair(ValueInfo info1, ValueInfo info2, int registerIndex) {
      // To be conservative we only want to equate two constants if they're identical, not just
      // equals() (otherwise we might be changing the behavior of the code we're building).  The
      // easiest way to do that is to unwrap Consts and compare their values with ==.
      this.info1 = (info1 instanceof Const c) ? c.value : info1;
      this.info2 = (info2 instanceof Const c) ? c.value : info2;
      this.registerIndex = registerIndex;
    }

    @Override
    public int hashCode() {
      // When used as the key, registerIndex is ignored
      return System.identityHashCode(info1) * 31 + System.identityHashCode(info2);
    }

    @Override
    public boolean equals(Object obj) {
      // Note that we use == rather than equals() when comparing info1 and info2.
      return (obj instanceof SimplePair pair) && info1 == pair.info1 && info2 == pair.info2;
    }

    @Override
    public String toString() {
      return String.format("%d: (%s, %s)", registerIndex, info1, info2);
    }
  }
}
