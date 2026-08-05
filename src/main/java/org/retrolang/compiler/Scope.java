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

package org.retrolang.compiler;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.Nullable;
import org.retrolang.Vm;
import org.retrolang.Vm.Access;
import org.retrolang.Vm.Compound;
import org.retrolang.Vm.Expr;
import org.retrolang.Vm.Local;
import org.retrolang.Vm.Singleton;
import org.retrolang.compiler.RetrospectParser.ArrayExtractContext;
import org.retrolang.compiler.RetrospectParser.DiscardExtractContext;
import org.retrolang.compiler.RetrospectParser.ExtractLhsContext;
import org.retrolang.compiler.RetrospectParser.ExtractStructElementContext;
import org.retrolang.compiler.RetrospectParser.LowerIdContext;
import org.retrolang.compiler.RetrospectParser.SimpleExtractContext;
import org.retrolang.compiler.RetrospectParser.StructExtractContext;
import org.retrolang.util.Bits;

/// A Scope's primary function is to map variable names to the corresponding Locals, but it has
/// additional functionality to support the creation of closure types:
///
/// - variables that are read but not written may be marked as inherited from a parent scope;
/// - a new closure type can be defined for the scope, either a compound (if there is at least one
///   inherited variable) or singleton (if there are none); and
/// - instances of the closure type can be constructed in the parent scope (capturing the current
///   values of the inherited variables) and then destructured in the child scope (initializing the
///   corresponding variables there).
///
/// When initializing the child scope, the instance of the closure type is sometimes referred to as
/// a "self" value.
///
/// There are two concrete subclasses: [ForMethod] (used for method declarations, lambdas, and loop
/// bodies) and [ForBreak] (used when compiling the block associated with a break statement).
abstract class Scope {

  /// Maps each variable visible in this scope to the corresponding Entry.
  private final Map<String, Entry> entries = new HashMap<>();

  /// If `parent` is non-null, this Scope may contain entries that are inherited from `parent`, i.e.
  /// Entries whose `fromParent` field points to an Entry in `parent.entries`.
  final Scope parent;

  /// If [#parent] is non-null, which of the parent's entries have been assigned a value at the time
  /// this scope is instantiated?
  Bits definedInParent;

  /// The BlockCompiler that allocates the variables in this scope, and that will be used to compile
  /// the associated code.
  final BlockCompiler blockCompiler;

  /// A list of the Entries that will be initialized from a self value.
  List<Entry> closureVars;

  /// closureVars with `index >= firstInherited` have a non-null `fromParent` link and will be
  /// initialized automatically; those with `index < firstInherited` will have values explicitly
  /// provided to [#getClosure].
  int firstInherited;

  /// If [#constructType] has been called on this Scope, exactly one of `singleton` and `compound`
  /// will be non-null.
  Singleton singleton;

  Compound compound;

  private Scope(Scope parent, BlockCompiler blockCompiler) {
    this.parent = parent;
    this.blockCompiler = blockCompiler;
  }

  private Symbols symbols() {
    return blockCompiler.symbols;
  }

  /// An Entry holds all the relevant information for a variable in a Scope.
  static class Entry {
    final String name;

    /// If non-null, an Entry with the same name in the parent Scope.
    final Entry fromParent;

    /// If this is an inherited entry (`index` is negative and `fromParent` is non-null), this is
    /// the reference that caused the entry to be created.
    final Token reference;

    /// Read-only variables have `index` -1. Writable variables are assigned distinct indices
    /// starting from zero, so that it is easy to represent sets of them with a Bits.
    final int index;

    /// The Local (allocated in the Scope's BlockCompiler) that holds the value of this variable.
    final Local local;

    Entry(String name, int index, Local local) {
      this.name = name;
      this.fromParent = null;
      this.reference = null;
      this.index = index;
      this.local = local;
    }

    Entry(String name, Entry fromParent, Token reference, int index, Local local) {
      this.name = name;
      this.fromParent = fromParent;
      this.reference = reference;
      this.index = index;
      this.local = local;
    }
  }

  /// Returns an unordered collection of all entries in this scope.
  Collection<Entry> allEntries() {
    return entries.values();
  }

  /// Calls the given consumer with each entry whose value will be inherited from the parent scope
  /// when creating an instance of this Scope's closure type.
  void forEachInherited(Consumer<Entry> consumer) {
    if (closureVars != null) {
      closureVars.subList(firstInherited, closureVars.size()).forEach(consumer);
    }
  }

  /// Creates a new closure type for this scope, with the given name and supertypes. Should not be
  /// called more than once. The type will have PRIVATE access.
  ///
  /// After calling `constructType()`, use [#getType], [#getClosure], and [#emitInitFromSelf] to
  /// emit instructions using the closure type.
  void constructType(String name, Vm.Type... supers) {
    assert singleton == null && compound == null;
    if (closureVars == null) {
      singleton = symbols().module.newSingleton(name, Access.PRIVATE, supers);
    } else {
      String[] elementNames = closureVars.stream().map(e -> e.name).toArray(String[]::new);
      compound = symbols().module.newCompoundType(name, elementNames, Access.PRIVATE, supers);
    }
  }

  /// May be called in place of [#constructType] if there are no closure vars and a distinct type is
  /// not needed; this Scope will use None as its closure type.
  void useNone() {
    assert singleton == null && compound == null && closureVars == null;
    singleton = symbols().vmNone;
  }

  /// Returns this scope's closure type. Should only be called after [#constructType] or [#useNone].
  Vm.Type getType() {
    return (compound != null) ? compound.asType() : singleton.asType();
  }

  /// Returns an array suitable for passing to [#getClosure]. Should only be called after
  /// [#constructType] or [#useNone].
  Expr @Nullable [] newClosureExprs() {
    if (singleton != null) {
      return null;
    }
    Expr[] result = new Expr[closureVars.size()];
    for (int i = firstInherited; i < result.length; i++) {
      result[i] = closureVars.get(i).fromParent.local;
    }
    return result;
  }

  /// Returns an Expr for an instance of this Scope's closure type, for use in the parent scope.
  /// Should only be called after [#constructType] or [#useNone].
  ///
  /// If the `exprs` is null all closureVars must have a non-null `fromParent` link (i.e.
  /// [#firstInherited] must be zero).
  ///
  /// If the `exprs` is non-null it must have been returned by a previous call to
  /// [#newClosureExprs], and the non-inherited entries (those with index less than
  /// [#firstInherited]) must have been initialized by the caller.
  Expr getClosure(Expr[] exprs) {
    if (exprs == null) {
      if (singleton != null) {
        return singleton;
      }
      assert firstInherited == 0;
      exprs = newClosureExprs();
    }
    // This will throw a NullPointerException if the caller hasn't initialized all non-inherited
    // entries.
    return compound.make(exprs);
  }

  /// Emits instructions to initialize this scope's closure variables from the given argument.
  ///
  /// Intended for use as the first step in a method body. Should only be called after
  /// [#constructType] or [#useNone].
  void emitInitFromSelf(Local selfArg) {
    if (compound != null) {
      blockCompiler.ib.emitCall(
          closureVars.stream().map(e -> e.local).toArray(Local[]::new),
          compound.extract(),
          selfArg);
    }
  }

  /// Returns the Entry for the given name. If there is no such entry in this scope, but there is an
  /// entry with the same name in a parent scope, inherits it. Returns null if no suitable entry can
  /// be found. Throws a "not initialized" CompilerError if the name is found in a parent scope but
  /// has not yet been assigned a value there.
  ///
  /// @param token used for locating error messages
  Entry getEntryForRead(String name, Token token) {
    return getEntryForReadUpto(name, token, null);
  }

  /// Returns the Entry for the given name. If the entry is read-only, throws an appropriate
  /// CompileError. If there is no such entry in this scope, creates one.
  ///
  /// @param token used for locating error messages
  abstract Entry getEntryForWrite(String name, Token token);

  /// Returns a set containing the indices of inout vars, i.e. all writable entries in this scope
  /// whose value will be implicitly returned by a "return" statement
  abstract Bits inouts();

  /// Returns the Entry for the given name. If there is no such entry in this scope, but there is an
  /// entry with the same name in a parent scope, inherits it.
  ///
  /// If `stop` is non-null, will not return `stop` or any entry inherited directly or indirectly
  /// from it. Returns null if no suitable entry can be found.
  ///
  /// @param token used for locating error messages
  @Nullable Entry getEntryForReadUpto(String name, Token token, Entry stop) {
    Entry entry = entries.get(name);
    if (entry != null) {
      if (stop != null) {
        // Make sure that entry isn't stop, or inherited from stop.
        for (Entry e = entry; e != null; e = e.fromParent) {
          if (e == stop) {
            return null;
          }
        }
      }
      return entry;
    } else if (parent == null) {
      // If stop is non-null, it's an Entry with the same name in one of our ancestors and we should
      // have found it before we got to the top.
      assert stop == null;
      return null;
    }
    Entry fromParent = parent.getEntryForReadUpto(name, token, stop);
    if (fromParent == null) {
      return null;
    } else if (fromParent.index >= 0
        && definedInParent != null
        && !definedInParent.test(fromParent.index)) {
      throw Compiler.notInitialized(token, name);
    }
    Entry result = new Entry(name, fromParent, token, -1, blockCompiler.newLocal(name));
    entries.put(name, result);
    addClosureVar(result);
    return result;
  }

  /// Returns the entry with the given name, which must exist.
  Entry getExistingEntry(String name) {
    // The only way entries.get() could miss at this point is if we're in a break scope and the
    // entry is outside the loop
    for (Scope scope = this; ; scope = ((ForBreak) scope).outsideLoop) {
      Entry result = scope.entries.get(name);
      if (result != null) {
        return result;
      }
    }
  }

  /// Ensures that there is a writable variable with the given name in this scope, and links to it.
  /// Throws an appropriate CompileError if there is no such variable or it is read-only.
  Entry getExistingEntryForWrite(LowerIdContext id, String name) {
    assert id.entry == null;
    // Using getEntryForRead() (instead of getEntry()) gives a (potentially) more enlightening
    // message if you e.g. forget to include an updated variable in a loop's sequential list.
    Entry result = getEntryForRead(name, id.start);
    if (result == null) {
      throw Compiler.notInitialized(id.start, name);
    } else if (result.index < 0) {
      throw Compiler.cannotModify(id.start, name);
    }
    id.entry = result;
    return result;
  }

  /// Adds a newly-created Entry to [#closureVars]. Note that any non-inherited closureVars (i.e.
  /// those whose value will be provided explicitly to [#getClosure]) must be added before any
  /// inherited closureVars.
  void addClosureVar(Entry entry) {
    if (closureVars == null) {
      closureVars = new ArrayList<>();
    }
    if (entry.fromParent == null) {
      assert firstInherited == closureVars.size();
      firstInherited++;
    }
    closureVars.add(entry);
  }

  /// If this is the scope of a lambda expression with no left-hand side (e.g. `-> #+1`), returns
  /// the Local used to store the argument; otherwise return null.
  Local getHash() {
    // Overridden in ForMethod.
    return null;
  }

  /// The Scope.ForMethod subclass is used for all scopes except those associated with break
  /// statements.
  static class ForMethod extends Scope {
    /// The value to return from [#getHash].
    private Local hash;

    /// The indices of this scope's inout vars, i.e. all writable entries whose value will be
    /// implicitly returned by a "return" statement. Null if there are no inout vars.
    private Bits.Builder inouts;

    /// The number of writable Entries created in this scope.
    private int nextIndex;

    ForMethod(Scope parent, BlockCompiler blockCompiler) {
      super(parent, blockCompiler);
    }

    @Override
    Bits inouts() {
      return (inouts == null) ? Bits.EMPTY : inouts.build();
    }

    /// Returns the number of writable Entries created so far in this scope.
    int nextIndex() {
      return nextIndex;
    }

    /// Creates a new writable Entry in this scope.
    Entry addMutable(String name, Token token, boolean isInOut) {
      return addEntry(name, token, null, nextIndex++, isInOut, null);
    }

    /// Like [#addMutable], but the Local is created with `addArg()` rather than `newLocal()`.
    @CanIgnoreReturnValue
    Entry addArg(String name, Token token, boolean isInOut) {
      Local arg = blockCompiler.ib.addArg(name);
      return addEntry(name, token, null, nextIndex++, isInOut, arg);
    }

    /// Creates a new writable Entry in this scope that will be initialized from a corresponding
    /// Entry in the parent scope. Used for sequential vars in loops.
    Entry addSequentialState(String name, Token token, Scope.Entry fromParent) {
      return addEntry(name, token, fromParent, nextIndex++, true, null);
    }

    /// Creates a read-only Entry in this scope that is not inherited from any parent. If local is
    /// null a new Local will be allocated with the given name; otherwise this is a synonym for an
    /// existing Local.
    Entry addImmutable(String name, Token token, Local local) {
      return addEntry(name, token, null, -1, false, local);
    }

    /// All of the methods for creating new Entries just redirect to here with appropriate args.
    ///
    /// @param token used only for locating error messages
    /// @param fromParent if non-null, an Entry with the same name in the parent Scope
    /// @param index if negative, this entry is read-only; otherwise should be [#nextIndex]++
    /// @param isInOut if true, index must be non-negative
    /// @param local if null, a new Local will be created
    private Entry addEntry(
        String name, Token token, Scope.Entry fromParent, int index, boolean isInOut, Local local) {
      if (local == null) {
        local = blockCompiler.newLocal(name);
      }
      Entry entry = new Entry(name, fromParent, token, index, local);
      if (((Scope) this).entries.put(name, entry) != null) {
        throw Compiler.error(token, "Duplicate parameter name '%s'", name);
      }
      if (isInOut) {
        if (inouts == null) {
          inouts = new Bits.Builder();
        }
        inouts.set(index);
      }
      return entry;
    }

    @Override
    Entry getEntryForWrite(String name, Token token) {
      Map<String, Entry> entries = ((Scope) this).entries;
      Entry entry = entries.get(name);
      if (entry == null) {
        entry = new Entry(name, nextIndex++, blockCompiler.newLocal(name));
        entries.put(name, entry);
      } else if (entry.index < 0) {
        throw Compiler.cannotModify(token, name);
      }
      return entry;
    }

    @Override
    Local getHash() {
      return hash;
    }

    /// Sets the value to be returned by [#getHash]. Should be called at most once.
    void setHash(Local hash) {
      assert this.hash == null;
      this.hash = hash;
    }

    /// Calls extractCompound() with an extractLeaf function that creates new locals in this scope.
    ///
    /// (This combination of setting up the scope at the same we're emitting code to extract into it
    /// is used only to handle compound method or lambda arguments; consider being less clever and
    /// doing those steps separately.)
    @CanIgnoreReturnValue
    Expr extractCompoundParameter(ExtractLhsContext lhs, Expr rhs, boolean isInOut) {
      return blockCompiler.extractCompound(
          lhs, rhs, isInOut, leaf -> extractLeafParameter(leaf, isInOut));
    }

    private @Nullable Local extractLeafParameter(LowerIdContext lowerId, boolean isInOut) {
      if (lowerId != null) {
        String name = lowerId.getText();
        return addMutable(name, lowerId.start, isInOut).local;
      }
      return isInOut ? blockCompiler.newLocal(blockCompiler.newAnonymousArgName()) : null;
    }

    /// Creates a new writable entry for each of the variables that will be set by extracting into
    /// the given extractLhs.
    void addVars(ExtractLhsContext lhs) {
      switch (lhs) {
        case SimpleExtractContext simple -> addVar(simple.lowerId());
        case DiscardExtractContext unused -> {}
        case ArrayExtractContext array -> array.extractLhs().forEach(this::addVars);
        case StructExtractContext struct -> {
          for (ExtractStructElementContext se : struct.extractStructElement()) {
            ExtractLhsContext element = se.extractLhs();
            if (element == null) {
              addVar(se.lowerId());
            } else {
              addVars(element);
            }
          }
        }
        default -> throw new AssertionError();
      }
    }

    /// Creates a new writable entry for the given variable.
    private void addVar(LowerIdContext id) {
      assert id.entry == null;
      id.entry = addMutable(id.start.getText(), id.start, false);
    }
  }

  /// A Scope subclass used for compiling `break` blocks. ForBreak scopes only contain inherited
  /// entries; any assignment in a `break` block creates an entry in the scope outside the loop, not
  /// in the ForBreak scope.
  ///
  /// Note that while the parent scope is usually that of the break's loop, it may instead be
  /// another ForBreak scope from a nested loop, e.g. in
  ///
  /// ```
  ///   // outer scope
  ///   for xk: x in xc {
  ///     // for_x scope
  ///     for yk: y in yc {
  ///       // for_y scope
  ///       if x == y {
  ///         break {
  ///           // break_y scope
  ///           break {
  ///             // break_x scope
  ///             return [xk, yk]
  ///           }
  ///         }
  ///       }
  ///     }
  ///   }
  /// ```
  ///
  /// the break\_x scope has break\_y as its parent (and so can read anything written in for\_y or
  /// for\_x) but any variables written in break\_x would be created in the outer scope.
  static class ForBreak extends Scope {
    final Scope outsideLoop;

    /// Creates a ForBreak scope; outsideLoop should be the parent of the loop's scope.
    ForBreak(Scope parent, Scope outsideLoop) {
      super(parent, outsideLoop.blockCompiler);
      this.outsideLoop = outsideLoop;
    }

    @Override
    Entry getEntryForReadUpto(String name, Token token, Entry stop) {
      // In a ForBreak scope you can read vars from both inside the loop and outside the loop.
      // If there's an ambiguity (the same name for two different vars) that's an error.
      Entry fromOutside = outsideLoop.getEntryForReadUpto(name, token, stop);
      assert fromOutside != null || stop == null;
      Entry fromInside = super.getEntryForReadUpto(name, token, fromOutside);
      if (fromInside == null) {
        return fromOutside;
      } else if (fromOutside == null) {
        return fromInside;
      }
      throw Compiler.error(token, "Ambiguous variable reference '%s'", name);
    }

    @Override
    Entry getEntryForWrite(String name, Token token) {
      // Any vars written in a break block are written to the scope outside the loop.
      return outsideLoop.getEntryForWrite(name, token);
    }

    @Override
    Bits inouts() {
      // A return in a break block returns from outside the loop.
      return outsideLoop.inouts();
    }
  }
}
