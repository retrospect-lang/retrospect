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
import org.retrolang.code.Block.NonTerminal;
import org.retrolang.code.Block.Split;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeBuilder.Phase;
import org.retrolang.util.Bits;

/// Assigns a Java local index to each register. We could just assign distinct indices to each
/// register, but I assume that using fewer indices will reduce the JVM resources required to run
/// the code.
///
/// If two registers are assigned the same index I refer to them as "aliased".
///
/// - If registers r1 and r2 are live at the same time and may have different values, they must not
///   be aliased (since storing one would modify the other).
/// - On the other hand, if there are simple SetBlocks "r1 = r2" and it is valid to alias r1 and r2,
///   doing so will allow us to optimize away those blocks (they would be setting a Java local to
///   itself).
///
/// We choose the assignment in two stages:
///
/// - Identify maximal sets of registers that we can alias, where each member of the set is assigned
///   to another member of the set at least once (this is done by the constructor).
/// - Assign a Java local index to each alias set, ensuring no two alias sets get the same local
///   index unless they're never live at the same time (this is done by [#assignJavaLocalNumbers]).
///
/// Aliasing introduced by the first stage enables us to drop some SetBlocks; aliasing introduced by
/// the second stage doesn't reduce the code size but may enable the JVM to run it more efficiently
/// (by minimizing the size of its local variable array).
///
/// To find a relatively optimal partition in the first stage we first do a pass through the blocks
/// constructing two two-dimensional bitmaps:
///
/// - `registerConflicts[r1,r2]`: if `registerConflicts[r1,r2]` is true or
///   `registerConflicts[r2,r1]` is true, r1 and r2 must not be aliases
/// - `preferAlias[r1,r2]`: if `preferAlias[r1,r2]`is true, aliasing r1 and r2 would enable us to
///   delete at least one SetBlock
///
/// (Since we don't have a two-dimensional bitmap type we actually use a `Bits.Builder[]`.)
///
/// We then consider each register `r` in sequence, and expand `r` to a maximal set of registers
/// that are transitively connected in `preferAlias` and no two of which are directly connected in
/// `registerConflicts`.
///
/// For the second stage we choose the smallest local index that has not already been assigned to
/// any register that conflicts with any in the alias set.
public class RegisterAssigner {
  final CodeBuilder cb;

  /// Indexed twice by [Register#index]; if set, registers must not alias. Does not need to be
  /// symmetric since we'll test in both directions.
  ///
  /// During construction we union all the conflicts for an alias set into
  /// `registerConflicts[r.jvmLocal()]`, and then only use the combined values.
  private final Bits.Builder[] registerConflicts;

  /// The alias sets found by the constructor.
  ///
  /// If register r is in a non-trivial alias set (i.e. there is at least one r2 such that `r2 != r`
  /// and `r2.jvmLocal() == r.jvmLocal()`) then `aliasSet[r.jvmLocal()]` is the set of all registers
  /// in the alias set.
  ///
  /// If r's alias set contains only r, then `r.jvmLocal() == r` and `aliasSet[r]` is null.
  private final Bits[] aliasSet;

  /// True if [#assignJavaLocalNumbers] has been called.
  private boolean completed;

  /// Initializes a new RegisterAssigner from the (optimized) block list and does the first stage of
  /// analysis.
  ///
  /// After the constructor completes, calling [Register#jvmLocal()] on two registers will return
  /// the same value if and only if the registers are in the same alias set, but the final local
  /// numbers have not yet been determined. At this point [#addConflicts] can still be called to
  /// augment the conflict sets, as long as it doesn't conflict with the already-chosen alias sets.
  ///
  /// Calling [#assignJavaLocalNumbers] will complete register assignment so that
  /// [Register#jvmLocal()] returns the final Java local index for each register.
  RegisterAssigner(CodeBuilder cb) {
    assert cb.phase() == Phase.FINALIZING;
    this.cb = cb;
    registerConflicts = new Bits.Builder[cb.numRegisters()];
    // No arg can alias another, since the JVM puts each one in a separate local at the start of
    // method execution.
    for (int i = 0; i < cb.numArgs(); i++) {
      Bits.Builder builder = getBuilder(registerConflicts, i);
      // Each arg conflicts with all args...
      builder.setToRange(0, cb.numArgs() - 1);
      // ... except itself.
      builder.clear(i);
    }
    aliasSet = new Bits[cb.numRegisters()];
    initializeConflictsAndFindAliases();
  }

  /// Returns the specified element of a `Bits.Builder[]`, initializing it to a new Bits.Builder if
  /// was null.
  private static Bits.Builder getBuilder(Bits.Builder[] builders, int index) {
    Bits.Builder result = builders[index];
    if (result == null) {
      result = new Bits.Builder();
      builders[index] = result;
    }
    return result;
  }

  /// Adds a requirement that `r` must not alias any of the registers in `conflicts`. If called
  /// after the initial alias sets have been chosen (i.e. after the constructor returns),
  /// `conflicts` must not include any registers in `r`'s alias set.
  public void addConflicts(Register r, Bits.Builder conflicts) {
    Preconditions.checkState(!completed, "Must be called before assignJavaLocalNumbers()");
    int index;
    if (r.hasJvmLocal()) {
      // This is a post-constructor call; you shouldn't be adding conflicts that invalidate
      // any alias sets that we've already chosen.
      index = r.jvmLocal();
      Bits aliases = aliasSet[index];
      Preconditions.checkState(aliases == null || !conflicts.testAny(aliases));
    } else {
      // During construction we haven't yet set jvmLocal values
      index = r.index;
    }
    Bits.Op.UNION.into(getBuilder(registerConflicts, index), conflicts);
  }

  /// This does the substantive part of the constructor's initialization.
  private void initializeConflictsAndFindAliases() {
    // Indexed twice by Register.index; if set, it would reduce code size if registers were aliased.
    // Updated symmetrically since we don't know which direction we'll propagate in.
    Bits.Builder[] preferAlias = new Bits.Builder[cb.numRegisters()];
    Bits.Builder conflictsBuilder = new Bits.Builder();
    for (int i = 0; i < cb.numBlocks(); i++) {
      Block block = cb.block(i);
      block.forEachModifiedRegister(lhs -> {
        // We're changing the value of lhs, so it can't alias any of the other currently live
        // registers.
        // This assumes that no Terminal blocks modify registers, which I think is a safe
        // assumption (since the local variable array is discarded after a method terminates,
        // what would be the point of modifying it?)
        if (block.mayReadAfterWrite()) {
          conflictsBuilder.setAll(block.live());
        } else {
          conflictsBuilder.setAll(((NonTerminal) block).next.targetBlock().live());
          if (block instanceof Split split) {
            Bits.Op.UNION.into(conflictsBuilder, split.alternate.targetBlock().live());
          }
        }
        // Remove any "conflict" between a register and itself.
        conflictsBuilder.clear(lhs.index);
        // If we're setting one register to the value of another, the lhs *could* alias the rhs,
        // and in fact we'd like it to (so that we could remove this assignment completely).
        if (block instanceof SetBlock) {
          CodeValue rhs = block.input(0);
          // We can't alias an int and a double, since different byte codes must be used to load
          // and
          // store a local depending on its type.
          if (rhs instanceof Register register && typesCouldAlias(lhs.type(), rhs.type())) {
            // We should have removed any no-op assignments before we got here
            assert rhs != lhs;
            int rhsIndex = register.index;
            getBuilder(preferAlias, lhs.index).set(rhsIndex);
            getBuilder(preferAlias, rhsIndex).set(lhs.index);
            // This doesn't constitute a conflict, since they have the same value.
            conflictsBuilder.clear(rhsIndex);
          }
        }
        addConflicts(lhs, conflictsBuilder);
      });
    }

    // Scratch space for searching the set of possible aliases and then finding an available local.
    // The ones we've decided to include:
    Bits.Builder alias = new Bits.Builder();
    // The ones we've already checked, or are checking on this cycle:
    Bits.Builder allAliasCandidates = new Bits.Builder();
    // The ones we will look at next cycle:
    Bits.Builder nextAliasCandidates = new Bits.Builder();
    for (int i = 0; i < registerConflicts.length; i++) {
      Register register = cb.register(i);
      if (register.hasJvmLocal()) {
        // This was already included in a previous register's alias set
        assert aliasSet[register.jvmLocal()].test(i);
        continue;
      }
      Bits.Builder conflicts = registerConflicts[i];
      if (conflicts == null) {
        // There are no assignments to this register, so it must be completely unused
        continue;
      }
      register.setJvmLocal(i);
      Bits.Builder currentAliasCandidates = preferAlias[i];
      if (currentAliasCandidates == null) {
        // Leave it in an alias set by itself
        continue;
      }
      // If there are registers that we thought would be good to have alias this one, filter the
      // set to those that actually could alias it and extend it through transitive closure.
      // Since each alias candidate we accept might bring along some more candidates, we have two
      // loops here.  The inner loop considers each candidate in currentAliasCandidates, and updates
      // conflicts and nextAliasCandidates whenever it chooses one; the outer loop reruns the
      // inner one with any new candidates discovered in the previous iteration, until there are
      // no more.

      // Initialize the alias set to contain only the register we're starting with.
      alias.clearAll();
      alias.set(i);
      // allAliasCandidates is the union of all the candidates we've considered, so we don't go
      // in circles.
      allAliasCandidates.setAll(alias);
      // True if alias contains anything other than i
      boolean haveAliases = false;
      for (; ; ) {
        // Don't revisit candidates that we've already checked.
        Bits.Op.DIFFERENCE.into(currentAliasCandidates, allAliasCandidates);
        // Registers that conflict with this one aren't candidates.
        Bits.Op.DIFFERENCE.into(currentAliasCandidates, conflicts);
        if (currentAliasCandidates.isEmpty()) {
          break;
        }
        // We don't want to modify currentAliasCandidates while iterating over it, so collect
        // additional candidates in nextAliasCandidates.
        nextAliasCandidates.clearAll();
        for (int candidate : currentAliasCandidates) {
          // If we've already assigned this candidate, or it conflicts with one we just chose,
          // it's not really a candidate.
          if (cb.register(candidate).hasJvmLocal() || conflicts.test(candidate)) {
            continue;
          }
          // If it conflicts with the register we're assigning, or with any of the other alias
          // candidates we've already accepted, it's not really a candidate.
          // (We already removed conflicts from currentAliasCandidates, but they're stored
          // asymmetrically so we also have to check this direction.)
          Bits.Builder candidateConflicts = registerConflicts[candidate];
          if (candidateConflicts.testAny(alias)) {
            continue;
          }
          // We're committing to this candidate, so its conflicts are now ours...
          CodeBuilder.assertTrue(alias.set(candidate));
          haveAliases = true;
          Bits.Op.UNION.into(conflicts, candidateConflicts);
          // ... and if it has preferred aliases we haven't considered, we'll consider them the
          // next time around the outer loop
          Bits.Builder moreAliases = preferAlias[candidate];
          if (moreAliases != null) {
            Bits.Op.UNION.into(nextAliasCandidates, moreAliases);
          }
        }
        Bits.Op.UNION.into(allAliasCandidates, currentAliasCandidates);
        // Now that we're done with the inner loop, we can reset currentAliasCandidates from
        // nextAliasCandidates and try again
        currentAliasCandidates.setAll(nextAliasCandidates);
      }
      // We now an expanded set of aliases and a correspondingly expanded set of conflicts; they
      // shouldn't overlap!
      assert !conflicts.testAny(alias);
      // Does our alias set contain more than just the register we started with?
      assert haveAliases == (alias.build().count() != 1);
      if (haveAliases) {
        Bits finalAlias = alias.build();
        assert finalAlias.min() == i;
        aliasSet[i] = finalAlias;
        // If we alias registers with different types (e.g. Object and String) we adjust them all
        // to the most general type (e.g. Object).  That may mean we add some casts, but that's
        // better than a verifier error.
        Class<?> combinedType = register.type();
        for (int j : finalAlias) {
          if (i != j) {
            Register r = cb.register(j);
            // All of the registers in this alias set will have the same jvmLocal value
            r.setJvmLocal(i);
            combinedType = combineTypes(combinedType, r.type());
          }
        }
        if (!combinedType.isPrimitive()) {
          for (int j : finalAlias) {
            cb.register(j).setType(combinedType);
          }
        }
      }
    }
  }

  /// Two registers of different types can only be aliased if neither is primitive.
  private static boolean typesCouldAlias(Class<?> x, Class<?> y) {
    return (x == y) || !(x.isPrimitive() || y.isPrimitive());
  }

  /// If we alias two Registers with different (non-primitive) types we use the least common
  /// supertype. If a block depends on the register having the subtype, [`?`][Emitter#push(Register,
  /// Class<>)] will automatically insert a cast when it is loaded.
  ///
  /// ("Least common supertype" isn't actually well-defined in the presence of interfaces, but we
  /// keep it simple and only consider superclasses. If x or y is an interface and neither contains
  /// the other, we just return Object.)
  private static Class<?> combineTypes(Class<?> x, Class<?> y) {
    if (x == y) {
      return x;
    }
    // We shouldn't end up here with unmatched primitives.
    assert !(x.isPrimitive() || y.isPrimitive());
    Class<?> xAncestor = x;
    Class<?> yAncestor = y;
    for (; ; ) {
      if (xAncestor.isAssignableFrom(y)) {
        return xAncestor;
      } else if (yAncestor.isAssignableFrom(x)) {
        return yAncestor;
      }
      // Sooner or later one of these will hit Object, so the loop can't go on forever.
      xAncestor = xAncestor.getSuperclass();
      yAncestor = yAncestor.getSuperclass();
      if (xAncestor == null || yAncestor == null) {
        // If either of them was an interface, getSuperClass() will return null.
        return Object.class;
      }
    }
  }

  /// Assigns a Java local index to each register, consistent with the previously-recorded conflicts
  /// and alias sets. Returns the total number of Java locals.
  ///
  /// One additional bit of complexity: the JVM decrees that longs and doubles actually require a
  /// pair of consecutive local indices.
  int assignJavaLocalNumbers() {
    // Maps each local index to the set of registers that have been assigned to it so far; a
    // register can be assigned to this local only if none of its conflicts are in this set.
    List<Bits.Builder> localAssignments = new ArrayList<>();
    // Maps each local index to the union of the conflicts of the registers assigned to it; a
    // register can be assigned to this local only if it's not in this set.
    List<Bits.Builder> localConflicts = new ArrayList<>();
    for (int i = 0; i < registerConflicts.length; i++) {
      Register register = cb.register(i);
      if (!register.hasJvmLocal()) {
        // If the first stage decided that this register was unused we should skip it again.
        continue;
      } else if (register.jvmLocal() != i) {
        // If this isn't the canonical register in its alias set, just copy the assignment we chose
        // for the canonical
        assert register.jvmLocal() < i;
        Register canonical = cb.register(register.jvmLocal());
        register.setJvmLocal(canonical.jvmLocal());
        continue;
      }
      // Find the first available local
      int localSize = OpCodeType.localSize(register.type());
      Bits.Builder conflicts = registerConflicts[i];
      Bits alias = this.aliasSet[i];
      if (alias == null) {
        alias = Bits.of(i);
      }
      int localNum;
      if (i < cb.numArgs()) {
        // args must be assigned sequentially, with no aliasing
        localNum = localAssignments.size();
      } else {
        // This is not a very sophisticated algorithm; if it turns out to be a bottleneck we could
        // probably do better.
        for (localNum = 0; localNum < localAssignments.size(); localNum++) {
          if (!(localAssignments.get(localNum).testAny(conflicts)
              || localConflicts.get(localNum).testAny(alias))) {
            // None of the registers already assigned to this local conflict with our set, but if
            // this is a long or double we also need to check the next slot
            if (localSize == 2
                && (localNum + 1 < localAssignments.size())
                && (localAssignments.get(localNum + 1).testAny(conflicts)
                    || localConflicts.get(localNum + 1).testAny(alias))) {
              // Won't work, and we've already tested the next slot so we might as well skip it
              localNum++;
              continue;
            }
            // This localNum will work
            break;
          }
        }
      }
      register.setJvmLocal(localNum);
      // Extend the localAssignments/localConflicts lists if necessary...
      while (localAssignments.size() < localNum + localSize) {
        localAssignments.add(new Bits.Builder());
        localConflicts.add(new Bits.Builder());
      }
      // ... and then update them
      Bits.Op.UNION.into(localAssignments.get(localNum), alias);
      Bits.Op.UNION.into(localConflicts.get(localNum), conflicts);
      if (localSize == 2) {
        Bits.Op.UNION.into(localAssignments.get(localNum + 1), alias);
        Bits.Op.UNION.into(localConflicts.get(localNum + 1), conflicts);
      }
    }
    completed = true;
    return localConflicts.size();
  }
}
