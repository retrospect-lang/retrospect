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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.antlr.v4.runtime.Token;
import org.retrolang.Vm.InstructionBlock;
import org.retrolang.Vm.Local;
import org.retrolang.compiler.RetrospectParser.ArgContext;
import org.retrolang.compiler.RetrospectParser.ArrayElementContext;
import org.retrolang.compiler.RetrospectParser.ArrayExpressionContext;
import org.retrolang.compiler.RetrospectParser.ArrayExtractContext;
import org.retrolang.compiler.RetrospectParser.AssertStatementContext;
import org.retrolang.compiler.RetrospectParser.AssignmentStatementContext;
import org.retrolang.compiler.RetrospectParser.BlockContext;
import org.retrolang.compiler.RetrospectParser.BreakStatementContext;
import org.retrolang.compiler.RetrospectParser.ConjExpressionContext;
import org.retrolang.compiler.RetrospectParser.ContinueStatementContext;
import org.retrolang.compiler.RetrospectParser.DiscardExtractContext;
import org.retrolang.compiler.RetrospectParser.ElseIfContext;
import org.retrolang.compiler.RetrospectParser.EmitStatementContext;
import org.retrolang.compiler.RetrospectParser.ExpressionContext;
import org.retrolang.compiler.RetrospectParser.ExtractLhsContext;
import org.retrolang.compiler.RetrospectParser.ExtractStatementContext;
import org.retrolang.compiler.RetrospectParser.ExtractStructElementContext;
import org.retrolang.compiler.RetrospectParser.ForLoopContext;
import org.retrolang.compiler.RetrospectParser.ForStatementContext;
import org.retrolang.compiler.RetrospectParser.FunctionCallContext;
import org.retrolang.compiler.RetrospectParser.FunctionCallExpressionContext;
import org.retrolang.compiler.RetrospectParser.FunctionCallStatementContext;
import org.retrolang.compiler.RetrospectParser.FunctionNameContext;
import org.retrolang.compiler.RetrospectParser.HashExpressionContext;
import org.retrolang.compiler.RetrospectParser.IdRefExpressionContext;
import org.retrolang.compiler.RetrospectParser.IfStatementContext;
import org.retrolang.compiler.RetrospectParser.IndexContext;
import org.retrolang.compiler.RetrospectParser.IndexExpressionContext;
import org.retrolang.compiler.RetrospectParser.LambdaArrowContext;
import org.retrolang.compiler.RetrospectParser.LambdaExpressionContext;
import org.retrolang.compiler.RetrospectParser.LowerIdContext;
import org.retrolang.compiler.RetrospectParser.NumberExpressionContext;
import org.retrolang.compiler.RetrospectParser.OpExpressionContext;
import org.retrolang.compiler.RetrospectParser.ParamAnnotationContext;
import org.retrolang.compiler.RetrospectParser.ReturnStatementContext;
import org.retrolang.compiler.RetrospectParser.SimpleExtractContext;
import org.retrolang.compiler.RetrospectParser.StatementContext;
import org.retrolang.compiler.RetrospectParser.StringExpressionContext;
import org.retrolang.compiler.RetrospectParser.StructElementContext;
import org.retrolang.compiler.RetrospectParser.StructExpressionContext;
import org.retrolang.compiler.RetrospectParser.StructExtractContext;
import org.retrolang.compiler.RetrospectParser.TernaryExpressionContext;
import org.retrolang.compiler.RetrospectParser.TraceStatementContext;
import org.retrolang.compiler.RetrospectParser.TypeCheckExpressionContext;
import org.retrolang.compiler.RetrospectParser.TypeNameExpressionContext;
import org.retrolang.util.Bits;
import org.retrolang.util.Bits.Op;

/// The second pass through a statement block
///
/// 1. links each variable reference to an appropriate entry in the given scope, adding entries for
///    variables inherited from the scope's parent if necessary;
/// 2. checks for conflicting reads and writes to the same variable within a single statement;
/// 3. determines which variables are live at the beginning of each statement, and checks for
///    variables that are set but then never read or read when they could not have been set; and
/// 4. creates a subtask to run PassThree on each nested lambda or loop body.
///
/// PassTwo has two phases. The first visits the statements in order, creating scope entries and
/// checking for conflicting access or read-without-write; it also constructs a sequence of
/// LiveCheck objects. The second phase processes the LiveChecks in reverse order, determining
/// liveness, checking for write-without-read, and creating the PassThree subtasks.
///
/// Bits objects are used to represent sets of variables that are read, written, and live, indexed
/// by Scope.Entry.index. Note that read-only (e.g. inherited) variables have index < 0, and none of
/// these checks apply to them.
class PassTwo extends VisitorBase<Void> {
  /// Runs PassTwo on a block of statements.
  ///
  /// @param numDefinedAtStart variables in scope with indices less than this are assumed to have
  ///   been assigned a value before the block begins
  /// @param mustNotFallThrough if true, it is an error if the block ends without a `return`,
  ///   'break', or 'continue'
  static void apply(
      Scope scope, BlockContext block, int numDefinedAtStart, boolean mustNotFallThrough) {
    PassTwo passTwo = new PassTwo(scope, numDefinedAtStart);
    passTwo.forward(block);
    if (mustNotFallThrough && passTwo.reachable && !passTwo.reachableIsUncertain) {
      throw Compiler.error(block.stop, "Missing return statement");
    }
    passTwo.backward();
  }

  /// Runs PassTwo on a single expression; used for lambdas and expression-only method declarations.
  static void apply(Scope.ForMethod scope, ExpressionContext ctx, boolean argsMustBeUsed) {
    int numDefinedAtStart = scope.nextIndex();
    PassTwo passTwo = new PassTwo(scope, -1);
    passTwo.visit(ctx);
    passTwo.checkStatement();
    Bits liveAtStart = passTwo.backward();
    if (argsMustBeUsed) {
      checkArgsUsed(scope, liveAtStart, 0, numDefinedAtStart);
    }
  }

  /// Throws a CompileError if any variable with index in [start, end) is unused.
  private static void checkArgsUsed(Scope scope, Bits liveAtStart, int start, int end) {
    Bits notUsed = Op.DIFFERENCE.apply(Bits.forRange(start, end - 1), liveAtStart);
    if (!notUsed.isEmpty()) {
      for (Scope.Entry entry : scope.allEntries()) {
        if (notUsed.test(entry.index)) {
          throw Compiler.notUsed(entry.reference, entry.name);
        }
      }
    }
  }

  /// A LiveCheck will be called with the variables that are live after the current statement; it
  /// can check for errors, and should update live to reflect which variables are live before the
  /// current statement.
  interface LiveCheck {
    void check(Bits.Builder live);
  }

  private static final LiveCheck EMPTY_CHECK = live -> {};

  Scope scope;

  /// Each of the LiveChecks in this list will be executed, in reverse order, during the second
  /// phase of PassTwo.
  private final List<LiveCheck> liveChecks = new ArrayList<>();

  /// The (writable) variables in scope that have been assigned a value before this statement (on at
  /// least one reachable code path). Null if apply() was called with an expression (rather than a
  /// block).
  ///
  /// Any variable not included in this set cannot have a value, so any use of it is an error.
  private final Bits.Builder maybeDefined;

  /// The (writable) variables in scope that are read by the current statement.
  private final Bits.Builder varsRead = new Bits.Builder();

  /// The variables that are updated by the current statement (i.e. read and then written).
  ///
  /// A variable may only appear in both `varsRead` and `varsUpdated` if the statement ensures a
  /// strict ordering between them (due to the use of "`and`", "`or`", or "`?:`"). For example,
  ///
  /// `y = pop(x=) + size(x)`
  ///
  /// is not allowed (the read of `x` conflicts with the update of `x`), but
  ///
  /// `y = pop(x=) is Number and isEmpty(x)`
  ///
  /// is (the update will complete before the `isEmpty(x)` is started).
  private final Bits.Builder varsUpdated = new Bits.Builder();

  /// The variables that are written by the current statement on completion.
  ///
  /// `varsUpdated` and `varsAssigned` must be disjoint.
  private final Bits.Builder varsAssigned = new Bits.Builder();

  /// All variables references that have been added to varsAssigned.
  private final ArrayList<LowerIdContext> assignedAsList = new ArrayList<>();

  /// True if we're in a context where functions with inout args are allowed.
  private boolean updateAllowed;

  /// True if the next statement is reachable.
  private boolean reachable;

  /// If [#reachable] is true, `reachableIsUncertain` will be true if the next statement is only
  /// reachable by completion of a loop that might not complete (because it contains a break).
  /// Ignored if reachable is false.
  ///
  /// Put another way, these two booleans encode three possible states:
  ///
  /// - `reachable=false`: the next statement is not reachable
  /// - `reachable=true`, `reachableIsUncertain=false`: the next statement is reachable (assuming
  ///   that all conditionals could go either way)
  /// - `reachable=true`, `reachableIsUncertain=true`: the next statement might or might not be
  ///   reachable
  ///
  /// Some functions look like
  ///
  /// ```
  ///   function foo(x) {
  ///     for a in something {
  ///       if whatever {
  ///         break { return a }
  ///       }
  ///     }
  ///   }
  /// ```
  ///
  /// where the `break` will always be reached before the collection is exhausted. We don't want the
  /// compiler to report a "Missing return" error (forcing the author to add a `return` that would
  /// never be executed). Instead we let the code compile as-is, and throw the "Missing return"
  /// error at runtime if the loop ever completes.
  private boolean reachableIsUncertain;

  /// Creates a PassTwo visitor using the given scope. If `numDefinedAtStart` is negative it will
  /// skip checking for read-without-write (used when e.g. compiling lambdas, since there are no
  /// assignments); otherwise all variables with index less than numDefinedAtStart are assumed to
  /// have initial values.
  private PassTwo(Scope scope, int numDefinedAtStart) {
    this.scope = scope;
    if (numDefinedAtStart < 0) {
      this.maybeDefined = null;
    } else {
      this.maybeDefined = new Bits.Builder();
      Op.UNION.rangeInto(this.maybeDefined, 0, numDefinedAtStart - 1);
    }
  }

  /// Visits each statement in the given block.
  private void forward(BlockContext block) {
    reachable = true;
    reachableIsUncertain = false;
    for (StatementContext stmt : block.statement()) {
      // Previous steps should not have left anything behind in these fields.
      assert varsRead.isEmpty()
          && varsUpdated.isEmpty()
          && varsAssigned.isEmpty()
          && assignedAsList.isEmpty();
      if (!reachable) {
        throw Compiler.error(stmt.start, "Not reachable");
      }
      reachableIsUncertain = false;
      updateAllowed = true;
      visit(stmt);
    }
  }

  /// Runs all the LiveChecks that were created in a previous forward pass. Returns the variables
  /// that are live at the beginning of the block.
  @CanIgnoreReturnValue
  private Bits backward() {
    Bits.Builder live = new Bits.Builder();
    // If we fall through only the inout vars are live
    live.setAll(scope.inouts());
    backward(liveChecks, live);
    return live.build();
  }

  /// Runs the LiveChecks in the given list in reverse order.
  private static void backward(List<LiveCheck> liveChecks, Bits.Builder live) {
    Lists.reverse(liveChecks).forEach(c -> c.check(live));
  }

  /// Save any LiveChecks that have been created since a given start point as a stand-alone
  /// LiveCheck (this is used when processing conditional control paths).
  private LiveCheck saveChecks(int start) {
    int end = liveChecks.size();
    if (start == end) {
      return EMPTY_CHECK;
    } else if (start + 1 == end) {
      return liveChecks.remove(start);
    }
    ImmutableList<LiveCheck> saved = ImmutableList.copyOf(liveChecks.subList(start, end));
    liveChecks.subList(start, end).clear();
    return live -> backward(saved, live);
  }

  /// Called with a variable that is read by the current statement. Throws a CompileError if the
  /// variable is also updated by this statement or could not have been assigned to yet.
  private void addRead(LowerIdContext id, String name) {
    Scope.Entry entry = scope.getEntryForRead(name, id.start);
    if (entry == null) {
      throw Compiler.notInitialized(id.start, name);
    }
    addReadImpl(id.start, entry, maybeDefined);
  }

  /// Called with a variable that is read by the current statement. Throws a CompileError if the
  /// variable is also updated by this statement or could not have been assigned to yet.
  private void addRead(Token token, Scope.Entry entry) {
    addReadImpl(token, entry, maybeDefined);
  }

  /// Called with each variable in a child scope (lambda or loop) that inherits from this scope, at
  /// the point the child is instantiated.
  private void addReadForClosure(Scope.Entry childEntry) {
    // We don't check maybeDefined; the scope already did that when creating the inheritance link,
    // and when compiling loops we've already changed maybeDefined by the time this is called.
    addReadImpl(childEntry.reference, childEntry.fromParent, null);
  }

  private void addReadImpl(Token token, Scope.Entry entry, Bits.Builder defined) {
    int index = entry.index;
    // We don't care about liveness of read-only variables, and we don't need to check for
    // conflicting updates.
    if (index >= 0) {
      if (varsUpdated.test(index)) {
        throw Compiler.conflictingUpdate(token, entry.name);
      } else if (defined != null && !defined.test(index)) {
        throw Compiler.notInitialized(token, entry.name);
      }
      varsRead.set(index);
    }
  }

  /// Called with each variable that is updated (i.e. passed as an inout argument) by the current
  /// statement; adds it to both the updated and assigned sets. Throws a CompileError if the
  /// variable is read-only, is updated more than once, or is also read by this statement.
  private void addUpdate(LowerIdContext id, String name) {
    Scope.Entry entry = scope.getExistingEntryForWrite(id, name);
    int index = entry.index;
    if (!varsUpdated.set(index)) {
      throw Compiler.conflictingUpdate(id.start, entry.name);
    } else if (varsAssigned.test(index)) {
      throw Compiler.conflictingAssignment(id.start, entry.name);
    } else if (varsRead.test(index)) {
      throw Compiler.error(id.start, "Conflict with read of '%s'", entry.name);
    } else if (!maybeDefined.test(index)) {
      throw Compiler.notInitialized(id.start, entry.name);
    }
  }

  /// Called with each variable that appears on the left hand side of an assignment statement.
  /// Throws a CompileError if the variable is read-only or the assignment conflicts with another
  /// assignment or update, or if the assignment reads first but the variable has never been
  /// assigned to.
  ///
  /// Note that `addAssign` with `readsFirst` true is different from [#addUpdate]; the latter
  /// conflicts with reads, while the former doesn't. For example, " `size(a) > pop(a=)`" is an
  /// error (the read conflicts with the update), while "`a += a`" isn't (the assignment happens
  /// after all reads).
  private void addAssign(LowerIdContext id, boolean readsFirst) {
    Scope.Entry entry = id.entry;
    if (entry == null) {
      entry = scope.getExistingEntryForWrite(id, id.start.getText());
    }
    int index = entry.index;
    if (index < 0) {
      throw Compiler.cannotModify(id.start, entry.name);
    }
    if (!varsAssigned.set(index)) {
      throw Compiler.conflictingAssignment(id.start, entry.name);
    } else if (varsUpdated.test(index)) {
      throw Compiler.conflictingUpdate(id.start, entry.name);
    } else if (readsFirst) {
      if (!maybeDefined.test(index)) {
        throw Compiler.notInitialized(id.start, entry.name);
      }
      varsRead.set(index);
    }
    assignedAsList.add(id);
  }

  /// Called after all calls to [#addAssign], [#addRead] and [#addUpdate] for the current statement.
  /// Adds LiveChecks to
  ///
  /// - verify that all assigned-to variables are live after this statement;
  /// - clear liveness for any assigned-to variables that aren't read first; and
  /// - add all read or updated variables to the live set.
  private void checkStatement() {
    Op.UNION.into(varsRead, varsUpdated);
    Bits savedRead = varsRead.build();
    // liveChecks are done in reverse order, so adding this one first means it will be executed
    // after any added below.
    if (!savedRead.isEmpty()) {
      liveChecks.add(live -> Op.UNION.into(live, savedRead));
      varsRead.clearAll();
      varsUpdated.clearAll();
    }
    if (!assignedAsList.isEmpty()) {
      for (LowerIdContext id : assignedAsList) {
        liveChecks.add(live -> {
          if (!live.clear(id.entry.index)) {
            throw Compiler.error(id.start, "'%s' is not used after assignment", id.entry.name);
          }
        });
      }
      assignedAsList.clear();
      Op.UNION.into(maybeDefined, varsAssigned);
      varsAssigned.clearAll();
    }
  }

  private void visitIfNonNull(ExpressionContext ctx) {
    if (ctx != null) {
      visit(ctx);
    }
  }

  @Override
  public Void visitAssignmentStatement(AssignmentStatementContext ctx) {
    addAssign(ctx.lowerId(), Compiler.readsFirst(ctx));
    ctx.index().forEach(this::visitIndex);
    visit(ctx.expression());
    checkStatement();
    return null;
  }

  @Override
  public Void visitEmitStatement(EmitStatementContext ctx) {
    // An emit statement "x << y" is equivalent to the assignment statement
    // "x_rw = emitValue(x_ro, x_rw, y)".
    LowerIdContext id = ctx.lowerId();
    String name = id.start.getText();
    var unused = scope.getExistingEntryForWrite(id, Compiler.rwName(name));
    addAssign(id, true);
    addRead(id, Compiler.roName(name));
    visit(ctx.expression());
    checkStatement();
    return null;
  }

  @Override
  public Void visitFunctionCallStatement(FunctionCallStatementContext ctx) {
    visitFunctionCall(ctx.functionCall());
    checkStatement();
    return null;
  }

  @Override
  public Void visitFunctionCall(FunctionCallContext ctx) {
    for (ArgContext arg : ctx.arg()) {
      ExpressionContext expression = arg.expression();
      ParamAnnotationContext annotation = arg.paramAnnotation();
      if (annotation == null) {
        visit(expression);
        continue;
      } else if (arg.dist != null) {
        // "^x=" isn't allowed.
        throw Compiler.error(arg.start, "Conflicting annotations");
      } else if (!updateAllowed) {
        throw Compiler.error(arg.start, "Updates not allowed here");
      }
      boolean isCollector = Symbols.isCollector(annotation);
      // `foo(a.x[3]=)` is allowed, but `foo(a.x[3]<<)` isn't.
      if (!isCollector) {
        while (expression instanceof IndexExpressionContext) {
          IndexExpressionContext ie = (IndexExpressionContext) expression;
          visitIndex(ie.index());
          expression = ie.expression();
        }
      }
      LowerIdContext id = Compiler.asLowerId(expression);
      String name = id.start.getText();
      if (isCollector) {
        addRead(id, Compiler.roName(name));
        name = Compiler.rwName(name);
      }
      addUpdate(id, name);
    }
    return null;
  }

  @Override
  public Void visitExtractStatement(ExtractStatementContext ctx) {
    // Calls addAssigned() for each variable appearing on the LHS
    visit(ctx.extractLhs());
    visit(ctx.expression());
    checkStatement();
    return null;
  }

  @Override
  public Void visitSimpleExtract(SimpleExtractContext ctx) {
    addAssign(ctx.lowerId(), false);
    return null;
  }

  @Override
  public Void visitDiscardExtract(DiscardExtractContext ctx) {
    return null;
  }

  @Override
  public Void visitArrayExtract(ArrayExtractContext ctx) {
    ctx.extractLhs().forEach(this::visit);
    return null;
  }

  @Override
  public Void visitStructExtract(StructExtractContext ctx) {
    for (ExtractStructElementContext se : ctx.extractStructElement()) {
      ExtractLhsContext lhs = se.extractLhs();
      if (lhs == null) {
        addAssign(se.lowerId(), false);
      } else {
        visit(lhs);
      }
    }
    return null;
  }

  @Override
  public Void visitReturnStatement(ReturnStatementContext ctx) {
    ExpressionContext expr = ctx.expression();
    if (expr == null) {
      if (!scope.blockCompiler.isProcedure()) {
        throw error("Missing return value");
      }
    } else {
      if (scope.blockCompiler.isProcedure()) {
        throw error("Procedure cannot return a result");
      }
      visit(expr);
      checkStatement();
    }
    checkReturn();
    return null;
  }

  @Override
  public Void visitContinueStatement(ContinueStatementContext ctx) {
    checkReturn();
    return null;
  }

  /// After a `return` or `continue`, only the inouts are live and the next statement is not
  /// reachable.
  private void checkReturn() {
    Bits inouts = scope.inouts();
    liveChecks.add(live -> live.setAll(inouts));
    reachable = false;
  }

  @Override
  public Void visitAssertStatement(AssertStatementContext ctx) {
    visit(ctx.expression());
    ctx.lowerId().forEach(id -> visitLowerId(id, false));
    checkStatement();
    return null;
  }

  @Override
  public Void visitTraceStatement(TraceStatementContext ctx) {
    ctx.lowerId().forEach(id -> visitLowerId(id, false));
    checkStatement();
    return null;
  }

  @Override
  public Void visitIfStatement(IfStatementContext ctx) {
    Bits initiallyDefined = maybeDefined.build();
    // Incrementally construct a LiveCheck for the entire statement, working backwards.
    LiveCheck result = EMPTY_CHECK;
    if (ctx.elseBlock == null) {
      // An empty else block means maybeDefined (and reachable)
      // are unchanged, and the LiveCheck is empty.
    } else {
      int startChecks = liveChecks.size();
      forward(ctx.elseBlock);
      result = saveChecks(startChecks);
      if (!reachable) {
        maybeDefined.clearAll();
      }
    }
    for (ElseIfContext ei : Lists.reverse(ctx.elseIf())) {
      result = visitBranch(ei.expression(), ei.block(), initiallyDefined, result);
    }
    liveChecks.add(visitBranch(ctx.expression(), ctx.ifBlock, initiallyDefined, result));
    return null;
  }

  /// Runs PassTwo on one branch of a conditional, with the given test and block. Assumes that
  /// [#maybeDefined] and [#reachable] are the union of any previously visited branches, and unions
  /// this branch into them. Combines the resulting LiveCheck(s) with the given elseCheck.
  LiveCheck visitBranch(
      ExpressionContext test, BlockContext block, Bits initiallyDefined, LiveCheck elseCheck) {
    return addBranch(
        initiallyDefined,
        () -> {
          int startChecks = liveChecks.size();
          visit(test);
          checkStatement();
          int startBlock = liveChecks.size();
          forward(block);
          LiveCheck blockCheck = saveChecks(startBlock);
          liveChecks.add(live -> {
            Bits saved1 = live.build();
            blockCheck.check(live);
            if (elseCheck == EMPTY_CHECK) {
              Op.UNION.into(live, saved1);
            } else {
              Bits saved2 = live.build();
              live.setAll(saved1);
              elseCheck.check(live);
              Op.UNION.into(live, saved2);
            }
          });
          return saveChecks(startChecks);
        });
  }

  /// Process an alternative code path leading to the current point. Combines [#maybeDefined] and
  /// [#reachable] with the effects of running `branch` (which is expected to visit something)
  /// starting from `initiallyDefined`. Returns the result of `branch`.
  ///
  /// More precisely, saves the current values of [#maybeDefined] and [#reachable], then sets them
  /// to the given `initiallyDefined` and `true`, and runs `branch`. The resulting values of
  /// [#maybeDefined] and [#reachable] are combined with the original values.
  private LiveCheck addBranch(Bits initiallyDefined, Supplier<LiveCheck> branch) {
    boolean wasReachable = reachable;
    boolean wasUncertain = reachableIsUncertain;
    reachable = true;
    reachableIsUncertain = false;
    Bits wasDefined = maybeDefined.build();
    maybeDefined.setAll(initiallyDefined);
    // Running branch modifies reachable, reachableIsUncertain, and/or maybeDefined
    // (e.g. by calling forward() on an `if` or `break` block), after which we will
    // merge back their previously-saved values.
    LiveCheck result = branch.get();
    if (reachable) {
      Op.UNION.into(maybeDefined, wasDefined);
      // reachableIsUncertain should only be true if it's uncertain along every path,
      // i.e. it's uncertain on the new path and either there was no other path
      // (wasReachable is false) or it was uncertain on the old path.
      if (reachableIsUncertain && wasReachable) {
        reachableIsUncertain = wasUncertain;
      }
    } else {
      // A branch that ends with a return, continue, or break leaves
      // maybeDefined, reachable, and reachableIsUncertain as they
      // were before the call to addBranch().
      maybeDefined.setAll(wasDefined);
      reachable = wasReachable;
      reachableIsUncertain = wasUncertain;
    }
    return result;
  }

  /// Adds an inherited variable to the given live set.
  private static void addInherited(Scope.Entry childEntry, Bits.Builder live) {
    Scope.Entry entry = childEntry.fromParent;
    int index = entry.index;
    if (index >= 0) {
      live.set(index);
    }
  }

  @Override
  public Void visitBreakStatement(BreakStatementContext ctx) {
    Loop.Break brk = ctx.breakStmt().loopBreak;
    if (brk.block != null) {
      brk.scope.definedInParent = maybeDefined.build();
    }
    // This assumes that we'll have done PassTwo.forward() on the break body before the
    // loop body's LiveChecks are run.
    liveChecks.add(live -> {
      live.clearAll();
      brk.scope.forEachInherited(childEntry -> addInherited(childEntry, live));
    });
    reachable = false;
    return null;
  }

  @Override
  public Void visitForStatement(ForStatementContext ctx) {
    ForLoopContext forLoop = ctx.forLoop();
    visitIfNonNull(forLoop.expression());
    Loop loop = forLoop.loop;
    loop.scope.definedInParent = maybeDefined.build();
    // The first step is to evaluate the collector expressions and get the ro/rw values for
    // vars without a collector.
    // We don't specify how many times the collector in "a, b =| c" is evaluated, so don't let it
    // contain any inouts.
    updateAllowed = false;
    for (Loop.CollectedVar cv : loop.collectedVars) {
      if (cv.expression == null) {
        cv.outerRo = scope.getEntryForRead(cv.ro.name, cv.id.start);
        if (cv.outerRo == null) {
          throw Compiler.notInitialized(cv.id.start, cv.ro.name);
        }
        addRead(cv.id.start, cv.outerRo);
        // "output" is the _rw var outside the loop
        addRead(cv.id.start, cv.output);
      } else if (cv.firstWithExpression) {
        visit(cv.expression);
      }
    }
    for (Loop.StateVar sv : loop.stateVars) {
      addRead(sv.id.start, sv.outer);
    }
    checkStatement();

    // Now we're ready to run PassTwo forward on the loop body.
    // The backward (LiveCheck) phase will happen as part of the
    // LiveCheck we create for the loop statement.
    PassTwo passTwoForBody = new PassTwo(loop.scope, loop.numDefinedAtStart);
    passTwoForBody.forward(forLoop.block());

    loop.scope.forEachInherited(this::addReadForClosure);
    checkStatement();

    // Add the loop's outputs to maybeDefined to get definedAfterExit
    for (Loop.CollectedVar cv : loop.collectedVars) {
      addAssign(cv.id, false);
    }
    for (Loop.StateVar sv : loop.stateVars) {
      addAssign(sv.id, false);
    }
    checkStatement();
    Bits definedAfterExit = maybeDefined.build();

    // For sequential loops, definedOnBreak is the same as definedAfterExit (it includes all the
    // outputs).  For parallel loops, definedOnBreak excludes all the outputs (even if they were
    // defined before the loop started).
    Bits definedOnBreak;
    if (loop.isSequential) {
      definedOnBreak = definedAfterExit;
      if (!loop.hasSource) {
        // No fall through
        reachable = false;
        maybeDefined.clearAll();
      }
    } else {
      loop.collectedVars.forEach(cv -> maybeDefined.clear(cv.output.index));
      definedOnBreak = maybeDefined.build();
      maybeDefined.setAll(definedAfterExit);
    }
    // A loop without breaks must fall through, since there's no other way out
    reachableIsUncertain = !loop.breaks.isEmpty();
    // The post-loop assigned/reachable state is the union of the fall-through state (if there's
    // a collection) and all the breaks.
    LiveCheck[] checkBreaks =
        loop.breaks.stream().map(brk -> checkBreak(brk, definedOnBreak)).toArray(LiveCheck[]::new);

    // Add a final LiveCheck that unions the results of the breaks
    liveChecks.add(live -> {
      loop.liveAfter = live.build();
      if (checkBreaks.length != 0) {
        if (!loop.hasSource) {
          live.clearAll();
        }
        Bits.Builder scratch = new Bits.Builder();
        for (LiveCheck checkBreak : checkBreaks) {
          scratch.setAll(loop.liveAfter);
          checkBreak.check(scratch);
          Op.UNION.into(live, scratch);
        }
      }
      // Now we can LiveCheck the body
      Bits liveAtStartOfLoop = passTwoForBody.backward();
      // ... and make sure that any key/value variables were used.
      checkArgsUsed(loop.scope, liveAtStartOfLoop, loop.kvStart, loop.numDefinedAtStart);

      for (Loop.StateVar sv : loop.stateVars) {
        if (liveAtStartOfLoop.test(sv.inner.index)) {
          live.set(sv.outer.index);
        }
      }
      // Don't call this until after (a) loop.liveAfter has been set, and (b) each of the
      // LiveChecks
      // returned by checkBreak() has been called (we need to finishClosure on each break).
      loop.donePassTwo(forLoop);
    });
    return null;
  }

  /// Called with a `break` statement in a loop in the current block, and the variables that are
  /// defined in the scope outside the loop at the beginning of the break. Updates [#maybeDefined]
  /// and [#reachable] to reflect this additional code path to the instructions after the loop, and
  /// returns a LiveCheck for the break body.
  private LiveCheck checkBreak(Loop.Break brk, Bits definedOnBreak) {
    LiveCheck finishClosure = live -> brk.finishClosure(live.build());
    if (brk.block == null) {
      Op.UNION.into(maybeDefined, definedOnBreak);
      reachable = true;
      reachableIsUncertain = false;
      return finishClosure;
    }
    return addBranch(
        definedOnBreak,
        () -> {
          Scope prevScope = scope;
          scope = brk.scope;
          int startChecks = liveChecks.size();
          liveChecks.add(finishClosure);
          forward(brk.block);
          scope = prevScope;
          return saveChecks(startChecks);
        });
  }

  @Override
  public Void visitIndex(IndexContext ctx) {
    // In this pass we only care about subexpressions, so for e.g. `a.x` and `a_` there's
    // nothing to do.
    visitExprs(ctx.arrayElement());
    visitIfNonNull(ctx.expression());
    return null;
  }

  /// If `okIfNotFound` is false, we're in a context where `lowerId` must be the name of a variable
  /// defined in this scope (e.g. the "`a`" in "`trace a`" or " `foo({a})`").
  ///
  /// If `okIfNotFound` is true, we assume that if no variable is found then it's a zero-argument
  /// function call (PassThree will throw an error if that turns out to not be true).
  private void visitLowerId(LowerIdContext lowerId, boolean okIfNotFound) {
    // I don't think that there's currently any way we'll visit a LowerIdContext
    // more than once, but that might change?
    if (lowerId.entry == null) {
      String name = lowerId.start.getText();
      Scope.Entry entry = scope.getEntryForRead(name, lowerId.start);
      if (entry != null) {
        lowerId.entry = entry;
        addRead(lowerId.start, entry);
      } else if (!okIfNotFound) {
        throw Compiler.error(lowerId.start, "Unknown variable '%s'", name);
      }
    }
  }

  @Override
  public Void visitLambdaExpression(LambdaExpressionContext ctx) {
    if (scope.getHash() != null) {
      throw error("A lambda without an argument pattern may not contain another lambda");
    }
    LambdaArrowContext arrow = ctx.lambdaArrow();
    // Create the BlockCompiler that will be used for compiling the lambda body.  Most of the
    // compilation happens in PassThree, but we handle the arguments here (including
    // destructuring if necessary).
    Symbols symbols = scope.blockCompiler.symbols;
    InstructionBlock ib = symbols.module.newInstructionBlock(2, 1, symbols.source);
    BlockCompiler lambdaCompiler = new BlockCompiler(symbols, ib, scope);
    lambdaCompiler.setLineNumber(arrow.start);
    arrow.blockCompiler = lambdaCompiler;
    Scope.ForMethod innerScope = lambdaCompiler.scope;
    assert innerScope.parent == scope;
    if (maybeDefined != null) {
      innerScope.definedInParent = maybeDefined.build();
    }
    Local self = ib.addArg("_self");
    ExtractLhsContext param = ctx.extractLhs();
    if (param == null) {
      innerScope.setHash(ib.addArg("#"));
    } else if (param instanceof SimpleExtractContext) {
      String name = param.start.getText();
      innerScope.addArg(name, param.start, false);
    } else if (param instanceof DiscardExtractContext) {
      ib.addArg(null);
    } else {
      Local arg = ib.addArg("_a");
      lambdaCompiler.scope.extractCompoundParameter(param, arg, false);
    }
    ExpressionContext lambdaExpr = ctx.expression();
    // Run PassTwo on the lambda body.
    apply(innerScope, lambdaExpr, true);
    lambdaCompiler.scope.forEachInherited(this::addReadForClosure);
    String name =
        String.format("lambda@%s:%s", arrow.start.getLine(), arrow.start.getCharPositionInLine());
    lambdaCompiler.scope.constructType(name, symbols.vmLambda);
    // We can run pass three on the lambda body (and do the addMethod when done) asynchronously
    // from the rest of the compilation.
    symbols.addSubTask(() -> {
      lambdaCompiler.scope.emitInitFromSelf(self);
      ib.emitReturn(lambdaCompiler.compile(lambdaExpr));
      ib.done();
      ib.addMethod(symbols.vmAt, lambdaCompiler.scope.getType().argType(0, true), false);
    });
    return null;
  }

  @Override
  public Void visitHashExpression(HashExpressionContext ctx) {
    if (scope.getHash() == null) {
      throw error("'#' is not in a lambda with no argument pattern");
    }
    return null;
  }

  @Override
  public Void visitArrayExpression(ArrayExpressionContext ctx) {
    visitExprs(ctx.arrayElement());
    return null;
  }

  private void visitExprs(List<ArrayElementContext> elements) {
    if (elements != null) {
      elements.forEach(distExp -> visit(distExp.expression()));
    }
  }

  @Override
  public Void visitNumberExpression(NumberExpressionContext ctx) {
    return null;
  }

  @Override
  public Void visitIdRefExpression(IdRefExpressionContext ctx) {
    FunctionNameContext fnName = ctx.functionName();
    if (fnName.module == null) {
      // Might be a variable, but might be a zero-arg function
      visitLowerId(fnName.lowerId(), true);
    } else {
      throw new UnsupportedOperationException("finish package support!");
    }
    return null;
  }

  @Override
  public Void visitStringExpression(StringExpressionContext ctx) {
    return null;
  }

  @Override
  public Void visitTypeCheckExpression(TypeCheckExpressionContext ctx) {
    visit(ctx.expression());
    return null;
  }

  @Override
  public Void visitStructExpression(StructExpressionContext ctx) {
    for (StructElementContext element : ctx.structElement()) {
      ExpressionContext expr = element.expression();
      if (expr != null) {
        visit(expr);
      } else {
        visitLowerId(element.lowerId(), false);
      }
    }
    return null;
  }

  @Override
  public Void visitTypeNameExpression(TypeNameExpressionContext ctx) {
    visitIfNonNull(ctx.expression());
    return null;
  }

  @Override
  public Void visitOpExpression(OpExpressionContext ctx) {
    visitIfNonNull(ctx.left);
    visitIfNonNull(ctx.right);
    return null;
  }

  @Override
  public Void visitFunctionCallExpression(FunctionCallExpressionContext ctx) {
    visitFunctionCall(ctx.functionCall());
    return null;
  }

  @Override
  public Void visitIndexExpression(IndexExpressionContext ctx) {
    visit(ctx.expression());
    visitIndex(ctx.index());
    return null;
  }

  /// Returns the current contents of `builder`, and resets `builder` to `previous`.
  private static Bits saveAndReset(Bits.Builder builder, Bits previous) {
    Bits result = builder.build();
    builder.setAll(previous);
    return result;
  }

  @Override
  public Void visitConjExpression(ConjExpressionContext ctx) {
    // This is a little tricky because updates on one side of the conjunction won't conflict with
    // reads or updates on the other side -- the ordering is unambiguous.
    Bits readBefore = varsRead.build();
    Bits updatedBefore = varsUpdated.build();
    visit(ctx.left);
    Bits readAfter = saveAndReset(varsRead, readBefore);
    Bits updatedAfter = saveAndReset(varsUpdated, updatedBefore);
    visit(ctx.right);
    Op.UNION.into(varsRead, readAfter);
    Op.UNION.into(varsUpdated, updatedAfter);
    return null;
  }

  @Override
  public Void visitTernaryExpression(TernaryExpressionContext ctx) {
    // Updates or reads in the condition won't conflict with updates or reads in either branch,
    // nor will the branches conflict with each other.
    Bits readBefore = varsRead.build();
    Bits updatedBefore = varsUpdated.build();
    visit(ctx.cond);
    Bits readAfter = saveAndReset(varsRead, readBefore);
    Bits updatedAfter = saveAndReset(varsUpdated, updatedBefore);
    if (ctx.ifTrue != null) {
      visit(ctx.ifTrue);
      Op.UNION.into(varsRead, readAfter);
      Op.UNION.into(varsUpdated, updatedAfter);
      readAfter = saveAndReset(varsRead, readBefore);
      updatedAfter = saveAndReset(varsUpdated, updatedBefore);
    }
    visit(ctx.ifFalse);
    Op.UNION.into(varsRead, readAfter);
    Op.UNION.into(varsUpdated, updatedAfter);
    return null;
  }
}
