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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.Nullable;
import org.retrolang.Vm.BranchTarget;
import org.retrolang.Vm.Compound;
import org.retrolang.Vm.Expr;
import org.retrolang.Vm.InstructionBlock;
import org.retrolang.Vm.Local;
import org.retrolang.compiler.RetrospectParser.ArrayElementContext;
import org.retrolang.compiler.RetrospectParser.ArrayExtractContext;
import org.retrolang.compiler.RetrospectParser.DiscardExtractContext;
import org.retrolang.compiler.RetrospectParser.ExpressionContext;
import org.retrolang.compiler.RetrospectParser.ExtractLhsContext;
import org.retrolang.compiler.RetrospectParser.ExtractStructElementContext;
import org.retrolang.compiler.RetrospectParser.IndexContext;
import org.retrolang.compiler.RetrospectParser.IndexExpressionContext;
import org.retrolang.compiler.RetrospectParser.LowerIdContext;
import org.retrolang.compiler.RetrospectParser.SimpleExtractContext;
import org.retrolang.compiler.RetrospectParser.StructExtractContext;
import org.retrolang.util.Bits;

/// A BlockCompiler holds the state associated with compiling a method body, lambda body, or main
/// program. Each InstructionBlock created by the compiler has an associated BlockCompiler.
///
/// Loop bodies are compiled using [Loop], a subclass of BlockCompiler.
class BlockCompiler {
  final Symbols symbols;

  /// The InstructionBlock to which we will emit instructions.
  final InstructionBlock ib;

  /// A Scope that will have an entry for each variable read or written in this block.
  final Scope.ForMethod scope;

  /// True when compiling the method body for a procedure; false when compiling the body of a
  /// function or the main program. Not used when compiling a lambda or loop body. Set by
  /// [#setResults].
  boolean isProcedure;

  /// Expressions for the current values of all inout args, preceded by an empty slot for the
  /// primary return value if isProcedure is false. Not used when compiling a lambda or loop body.
  /// Set by [#setResults].
  private Expr[] results;

  /// A simple counter used to generate distinct names for args that do not have a name in the
  /// source code.
  private int nextAnonymousArgIndex;

  /// All locals that have been created to store the results of intermediate expressions. Those with
  /// indices less than `nextTmp` are currently in use; the rest are available.
  private final List<Local> tmps = new ArrayList<>();

  private int nextTmp;

  /// An instance of ExpressionCompiler that will be used for compiling expressions in this block.
  final ExpressionCompiler expressionCompiler = new ExpressionCompiler(this);

  /// Creates a new BlockCompiler that will emit instructions in the given InstructionBlock. If
  /// parent is non-null, read-only references to its variables are allowed.
  BlockCompiler(Symbols symbols, InstructionBlock ib, Scope parent) {
    this.symbols = symbols;
    this.ib = ib;
    this.scope = new Scope.ForMethod(parent, this);
  }

  /// Must be called exactly once on any BlockCompiler that may encounter a return statement (i.e.
  /// when compiling a method body or main program). The length of results must match the
  /// `numResults()` of the function if this is a method body, or be one if this is a main program.
  /// The first element of results must be null if return statements are expected to provide a
  /// value; any remaining elements should be expressions for the returned values of inout
  /// parameters.
  void setResults(Expr... results) {
    this.results = results;
    // Test this now, because results[0] will be overwritten by compileReturn()
    isProcedure = (results.length == 0) || (results[0] != null);
  }

  /// Returns true if return statements are not expected to provide a result.
  boolean isProcedure() {
    return isProcedure;
  }

  /// Sets the source position associated with any instructions emitted until the next call to
  /// `setLineNumber()`.
  void setLineNumber(Token token) {
    ib.setLineNumber(token.getLine(), token.getCharPositionInLine());
  }

  /// Returns a new Local with the given name.
  Local newLocal(String name) {
    return ib.newLocal(name);
  }

  /// Returns an arbitrary name suitable for an anonymous argument (e.g. an arg that will be
  /// destructured, or an inout "\_" arg).
  String newAnonymousArgName() {
    return "_a" + nextAnonymousArgIndex++;
  }

  /// Returns an available Local for temporary use.
  Local newTmp() {
    if (nextTmp < tmps.size()) {
      return tmps.get(nextTmp++);
    }
    assert nextTmp == tmps.size();
    Local result = newLocal("_t" + nextTmp);
    tmps.add(result);
    nextTmp++;
    return result;
  }

  /// Returns a value representing the currently in-use temporaries. The only useful thing to do
  /// with the result is to later pass it to [#resetTmps].
  int saveTmpState() {
    return nextTmp;
  }

  /// Resets temporaries-in-use state, so that all allocated temporaries are considered available.
  void resetTmps() {
    nextTmp = 0;
  }

  /// Resets temporaries-in-use state to a state previously returned by [#saveTmpState].
  void resetTmps(int savedTmpState) {
    assert savedTmpState >= 0 && savedTmpState <= nextTmp;
    nextTmp = savedTmpState;
  }

  /// If target is non-null, defines it.
  void maybeDefineTarget(BranchTarget target) {
    if (target != null) {
      ib.defineTarget(target);
    }
  }

  /// Returns an Expr with the given expression's value, after emitting instructions as necessary.
  /// The result may refer to temps that are now marked as in-use; caller is responsible for saving
  /// temp state before calling `compile()` and restoring it after the result is no longer needed.
  Expr compile(ExpressionContext expr) {
    return expressionCompiler.visit(expr);
  }

  /// Emits instructions to store the given expression's value into a specific Local.
  void compileSet(Local lhs, ExpressionContext expr) {
    new ExpressionCompiler.Setter(this, lhs).compileSet(expr);
  }

  /// Emits instructions to branch if the given expression's value is True or False.
  void compileTest(BranchTarget branchTarget, boolean branchIfTrue, ExpressionContext expr) {
    new ExpressionCompiler.Tester(this, branchTarget, branchIfTrue).compileTest(expr);
  }

  /// Given a list of expressions, returns a Bits that contains `i` if the `i`-th expression was
  /// preceded by "`^`".
  static Bits checkDistributed(List<ArrayElementContext> exprs) {
    if (exprs == null) {
      return Bits.EMPTY;
    } else {
      return Bits.fromPredicate(exprs.size() - 1, i -> exprs.get(i).dist != null);
    }
  }

  /// Given an index returns a Bits indicating which parts of it, if any, are distributed:
  ///
  /// - If given an array index (`[]`), returns one bit per array element.
  /// - If given a string index (`.`), returns EMPTY (these can't be distributed).
  /// - If given a general index (`@`), returns a Bits that will contain 0 if the `@` is followed by
  ///   `^`.
  /// - If given an uncompounding (`_`), returns null (there is no index).
  static @Nullable Bits checkDistributed(IndexContext index) {
    int tokenType = index.start.getType();
    if (tokenType == TokenType.LEFT_SQUARE) {
      return checkDistributed(index.arrayElement());
    } else if (tokenType == TokenType.UNDERSCORE) {
      return null;
    } else {
      assert tokenType == TokenType.DOT || tokenType == TokenType.AT;
      // DOT cannot be distributed, but we don't need to treat it separately.
      return Bits.fromBooleans(index.dist != null);
    }
  }

  /// Returns an Expr with the value to use as an index, after emitting instructions as necessary.
  /// `distributed` must have been returned by a previous call to `checkDistributed(index)`. The
  /// result may refer to temps that are now marked as in-use; caller is responsible for saving temp
  /// state before calling `compileIndex()` and restoring it after the result is no longer needed.
  Expr compileIndex(IndexContext index, Bits distributed) {
    int tokenType = index.start.getType();
    if (tokenType == TokenType.LEFT_SQUARE) {
      return expressionCompiler.visitWithCurrentNode(
          index,
          unused -> {
            return expressionCompiler.compileExpressions(index.arrayElement(), distributed);
          });
    } else if (tokenType == TokenType.AT) {
      return compile(index.expression());
    } else {
      assert tokenType == TokenType.DOT && distributed.isEmpty();
      return symbols.vm.asExpr(index.lowerId().getText());
    }
  }

  /// Called when an inout function arg is an indexExpression (e.g. `pop(a[i]=)`). Emits code to get
  /// the value of the indexExpression into a local, and returns a Runnable that emits code to put
  /// the (presumably changed) value of the local back into the original base var.
  ///
  /// The Runnable may contain references to newly-allocated tmps so don't [#resetTmps] to a
  /// previously-saved state until after it has been called.
  Runnable compileIndexedInout(IndexExpressionContext ie, Local local) {
    IndexContext index = ie.index();
    ExpressionContext expr = ie.expression();
    // A distributed index is not allowed here.
    Bits distributed = checkDistributed(index);
    if (distributed != null && !distributed.isEmpty()) {
      throw Compiler.cannotDistribute(index.start);
    }
    // The indexed expression may itself be indexed (e.g. "pop(a_.stacks[i]=)"), in which case
    // we need to recurse.
    Runnable innerFinish;
    Local base;
    if (expr instanceof IndexExpressionContext) {
      innerFinish = compileIndexedInout((IndexExpressionContext) expr, local);
      base = local;
    } else {
      innerFinish = null;
      base = Compiler.asLowerId(expr).entry.local;
    }
    // Any temps used in the index expression will be free once we call startUpdate.
    int tmpState = saveTmpState();
    Expr indexExpr = (distributed == null) ? null : compileIndex(index, distributed);
    resetTmps(tmpState);
    Local updater = newTmp();
    startUpdate(local, updater, base, indexExpr);
    return () -> {
      finishUpdate(base, updater, local);
      if (innerFinish != null) {
        innerFinish.run();
      }
    };
  }

  /// If `index` is non-null, emits a call to `startUpdate(obj, index)` storing the results in
  /// `elementOut` and `updaterOut`.
  ///
  /// If `index` is null, emits a call to `updateCompound(obj)`, storing the results in `elementOut`
  /// and `updaterOut`.
  void startUpdate(Local elementOut, Local updaterOut, Expr obj, Expr index) {
    Local[] outputs = new Local[] {elementOut, updaterOut};
    if (index == null) {
      ib.emitCall(outputs, symbols.module.updateCompound(), obj);
    } else {
      ib.emitCall(outputs, symbols.vmCore.lookupFunction("startUpdate", 2), obj, index);
    }
  }

  /// Emits a call to `get(updater, newElement)`, storing the result in `updatedOut`.
  void finishUpdate(Local updatedOut, Expr updater, Expr newElement) {
    ib.emitCall(updatedOut, symbols.vmAt, updater, newElement);
  }

  /// Emits a return instruction. The given Expr should be null if [#isProcedure] is true. The
  /// current values of any inout args will be included.
  void compileReturn(Expr result) {
    assert isProcedure == (result == null);
    if (!isProcedure) {
      results[0] = result;
    }
    ib.emitReturn(results);
  }

  /// Emits instructions to extract `rhs` into `lhs`.
  void extract(ExtractLhsContext lhs, Expr rhs) {
    if (lhs instanceof SimpleExtractContext) {
      // Not really extracting, just storing.
      Local local = ((SimpleExtractContext) lhs).lowerId().entry.local;
      ib.emitSet(local, rhs);
    } else if (lhs instanceof DiscardExtractContext) {
      // Nothing to do
    } else {
      // For actual extractions (array or struct) all the work is done by extractCompound.
      var unused = extractCompound(lhs, rhs, false, id -> (id == null) ? null : id.entry.local);
    }
  }

  /// Emits instructions to extract `rhs` into `lhs`.
  ///
  /// `extractLeaf` will be called with each name or "\_" found in `lhs`, and should return the
  /// Local into which the corresponding value is to be stored or null if it should be discarded
  /// (the argument to `extractLeaf` will be null for "\_").
  ///
  /// If `needResult` is true, the results returned by `extractLeaf` will be reassembled into an
  /// Expr and returned; otherwise the result is unspecified.
  ///
  /// Should only be called with an ArrayExtractContext or StructExtractContext; assumes that
  /// SimpleExtractContext and DiscardExtractContext have already been handled by caller.
  @Nullable Expr extractCompound(
      ExtractLhsContext lhs,
      Expr rhs,
      boolean needResult,
      Function<LowerIdContext, Local> extractLeaf) {
    Compound compound;
    ExtractElement[] extracts;
    // We will allocate temps if there are nested compounds.
    int savedTmpState = saveTmpState();
    if (lhs instanceof ArrayExtractContext) {
      extracts =
          ((ArrayExtractContext) lhs)
              .extractLhs().stream()
                  .map(e -> extractElement(e, extractLeaf))
                  .toArray(ExtractElement[]::new);
      compound = symbols.vm.arrayOfSize(extracts.length);
    } else {
      extracts =
          ((StructExtractContext) lhs)
              .extractStructElement().stream()
                  .map(se -> extractStructEntry(se, extractLeaf))
                  .toArray(ExtractElement[]::new);
      // The keys must be sorted for VirtualMachine.structWithKeys()
      Arrays.sort(extracts, Comparator.comparing(e -> e.key));
      compound =
          symbols.vm.structWithKeys(Arrays.stream(extracts).map(e -> e.key).toArray(String[]::new));
    }
    // Emit the call to the appropriate extract function.
    Local[] outputs = Arrays.stream(extracts).map(e -> e.local).toArray(Local[]::new);
    ib.emitCall(outputs, compound.extract(), rhs);
    // If there were nested compounds, recursively extract each of them.
    // Also assemble the result expression if required.
    Expr[] results = needResult ? new Expr[extracts.length] : null;
    for (int i = 0; i < extracts.length; i++) {
      ExtractElement extract = extracts[i];
      Expr result;
      if (extract.lhs == null) {
        result = extract.local;
      } else {
        result = extractCompound(extract.lhs, extract.local, needResult, extractLeaf);
      }
      if (results != null) {
        results[i] = result;
      }
    }
    resetTmps(savedTmpState);
    return (results == null) ? null : compound.make(results);
  }

  /// When the lhs of an extract is a compound (array or struct) we create an ExtractElement for
  /// each of its elements to hold either the Local returned by extractLeaf (if the element is a
  /// leaf) or a temporary that will get the element's value and the extractLhsContext for the next
  /// step (if the element is not a leaf).
  ///
  /// In addition, when extracting a struct each ExtractElement holds the corresponding key.
  private static class ExtractElement {
    final Local local;
    final ExtractLhsContext lhs; // if non-null, another extract step is required
    String key; // only used for dicts

    ExtractElement(Local local, ExtractLhsContext lhs) {
      this.local = local;
      this.lhs = lhs;
    }
  }

  /// Creates a new ExtractElement for an extractLhs.
  private ExtractElement extractElement(
      ExtractLhsContext lhs, Function<LowerIdContext, Local> extractLeaf) {
    if (lhs instanceof SimpleExtractContext) {
      LowerIdContext leaf = ((SimpleExtractContext) lhs).lowerId();
      return new ExtractElement(extractLeaf.apply(leaf), null);
    } else if (lhs instanceof DiscardExtractContext) {
      return new ExtractElement(extractLeaf.apply(null), null);
    } else {
      // Not a leaf, so we need to allocate a tmp and save the rest for later.
      return new ExtractElement(newTmp(), lhs);
    }
  }

  /// Creates a new ExtractElement for an extractStructElement.
  private ExtractElement extractStructEntry(
      ExtractStructElementContext entry, Function<LowerIdContext, Local> extractLeaf) {
    ExtractElement result;
    ExtractLhsContext lhs = entry.extractLhs();
    if (lhs == null) {
      // A "shorthand property name", i.e. 'x' meaning 'x: x'
      LowerIdContext key = entry.lowerId();
      assert key != null;
      result = new ExtractElement(extractLeaf.apply(key), null);
      result.key = key.getText();
    } else {
      result = extractElement(lhs, extractLeaf);
      result.key = Compiler.getKey(entry);
    }
    return result;
  }
}
