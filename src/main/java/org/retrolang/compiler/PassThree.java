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

import java.util.List;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.retrolang.Vm;
import org.retrolang.Vm.BranchTarget;
import org.retrolang.Vm.Expr;
import org.retrolang.Vm.Local;
import org.retrolang.compiler.Loop.CollectedVar;
import org.retrolang.compiler.RetrospectParser.AssertStatementContext;
import org.retrolang.compiler.RetrospectParser.AssignmentStatementContext;
import org.retrolang.compiler.RetrospectParser.BlockContext;
import org.retrolang.compiler.RetrospectParser.BreakStatementContext;
import org.retrolang.compiler.RetrospectParser.ContinueStatementContext;
import org.retrolang.compiler.RetrospectParser.ElseIfContext;
import org.retrolang.compiler.RetrospectParser.EmitStatementContext;
import org.retrolang.compiler.RetrospectParser.ExpressionContext;
import org.retrolang.compiler.RetrospectParser.ExtractLhsContext;
import org.retrolang.compiler.RetrospectParser.ExtractStatementContext;
import org.retrolang.compiler.RetrospectParser.ForLoopContext;
import org.retrolang.compiler.RetrospectParser.ForStatementContext;
import org.retrolang.compiler.RetrospectParser.FunctionCallStatementContext;
import org.retrolang.compiler.RetrospectParser.IfStatementContext;
import org.retrolang.compiler.RetrospectParser.IndexContext;
import org.retrolang.compiler.RetrospectParser.LowerIdContext;
import org.retrolang.compiler.RetrospectParser.ReturnStatementContext;
import org.retrolang.compiler.RetrospectParser.SimpleExtractContext;
import org.retrolang.compiler.RetrospectParser.StatementContext;
import org.retrolang.compiler.RetrospectParser.TraceStatementContext;
import org.retrolang.util.Bits;
import org.retrolang.util.StringUtil;

/**
 * The final pass emits instructions. Most of the work is done by BlockCompiler and
 * ExpressionCompiler.
 */
class PassThree extends VisitorBase<Void> {

  /** Emits instructions for the given statements using the given BlockCompiler. */
  static void apply(BlockCompiler blockCompiler, BlockContext block) {
    new PassThree(blockCompiler).visitBlock(block);
  }

  final BlockCompiler blockCompiler;

  PassThree(BlockCompiler blockCompiler) {
    this.blockCompiler = blockCompiler;
  }

  private Vm.Module vmCore() {
    return blockCompiler.symbols.vmCore;
  }

  @Override
  public Void visitBlock(BlockContext ctx) {
    if (ctx != null) {
      for (StatementContext stmt : ctx.statement()) {
        blockCompiler.resetTmps();
        blockCompiler.setLineNumber(stmt.start);
        visit(stmt);
      }
    }
    blockCompiler.resetTmps();
    return null;
  }

  private void emitCompoundAssign(Local lhs, int assignType, Expr rhs) {
    // "&=" doesn't use binaryUpdate because "&" operates on a whole collection rather than
    // distributing across it.
    if (assignType == TokenType.CONCAT_EQUALS) {
      blockCompiler.ib.emitCall(lhs, blockCompiler.symbols.vmConcatUpdate, lhs, rhs);
      return;
    }
    String fnName = Symbols.FUNCTION_NAME_FROM_ASSIGNMENT_TOKEN.get(assignType);
    Vm.Function fn = vmCore().lookupFunction(fnName, 2);
    blockCompiler.ib.emitCall(
        lhs, blockCompiler.symbols.vmBinaryUpdate, lhs, fn.asLambdaExpr(), rhs);
  }

  @Override
  public Void visitAssignmentStatement(AssignmentStatementContext ctx) {
    LowerIdContext id = ctx.lowerId();
    Local lhs = id.entry.local;
    ExpressionContext expr = ctx.expression();
    if (!Compiler.readsFirst(ctx)) {
      // Simple assignment -- no indices, not a compound assignment
      blockCompiler.compileSet(lhs, expr);
      return null;
    }
    Expr rhs = blockCompiler.compile(expr);
    List<IndexContext> indices = ctx.index();
    int numIndices = (indices == null) ? 0 : indices.size();
    int assignType = ctx.assignOp().start.getType();
    if (numIndices == 0) {
      // Compound assignment, but no indices; all we need is a call instruction
      // (e.g. "a += 1" emits "binaryUpdate(a=, [x, y] -> x+y, 1)")
      emitCompoundAssign(lhs, assignType, rhs);
      return null;
    }
    // The rest of this method handles the case of one or more indices on the left hand side,
    // e.g. "a_.elements[i] = x" has 3 indexing steps ("_", ".elements", and "[i]").
    // - All but the last will require a call to blockCompiler.startUpdate()
    //   (the last one also requires a startUpdate for a compound assignment, but with a regular
    //   assignment it can use replaceElement).
    // - After we've performed the innermost update, we have to make the corresponding calls
    //   to blockCompiler.finishUpdate() in the reverse order from the startUpdate()s.

    // Stop one step from the end if this is a regular (non-compound) assignment.
    int end = numIndices - ((assignType == TokenType.EQUALS) ? 1 : 0);
    // Save the updaters returned by startUpdate()
    Local[] updaters = (end == 0) ? null : new Local[end];
    Local current = lhs;
    Local tmp = (end == 0) ? lhs : blockCompiler.newTmp();
    for (int i = 0; i < numIndices; i++) {
      // Each iteration calls startUpdate(current, index), saving the previous
      // value in tmp (and making that current for the next iteration) and saving
      // the updater in updaters[i].
      IndexContext indexExpr = indices.get(i);
      Expr index = null;
      // We don't allow distributing on the LHS, so if we find any we'll error.
      // Note that checkDistributed() returns null for the "_" (uncompounding) index
      // and EMPTY for any other kind of index.
      Bits distributed = BlockCompiler.checkDistributed(indexExpr);
      if (distributed != null) {
        if (!distributed.isEmpty()) {
          throw Compiler.cannotDistribute(indexExpr.start);
        }
        int tmpState = blockCompiler.saveTmpState();
        index = blockCompiler.compileIndex(indexExpr, distributed);
        blockCompiler.resetTmps(tmpState);
      }
      if (i < end) {
        Local updater = blockCompiler.newTmp();
        updaters[i] = updater;
        blockCompiler.startUpdate(tmp, updater, current, index);
      } else if (index != null) {
        Vm.Function replace = vmCore().lookupFunction("replaceElement", 3);
        blockCompiler.ib.emitCall(tmp, replace, current, index, rhs);
      } else {
        // There's no replaceElement equivalent for "a_ = 3"; instead we use
        // startUpdate (which calls the module's updateCompound()) and discard
        // the first (previous value) result, and then immediately call the
        // updater it returned.
        blockCompiler.startUpdate(null, tmp, current, null);
        blockCompiler.finishUpdate(tmp, tmp, rhs);
      }
      current = tmp;
    }
    if (assignType != TokenType.EQUALS) {
      emitCompoundAssign(current, assignType, rhs);
    }
    for (int i = end - 1; i >= 0; i--) {
      blockCompiler.finishUpdate((i == 0) ? lhs : current, updaters[i], current);
    }
    return null;
  }

  @Override
  public Void visitExtractStatement(ExtractStatementContext ctx) {
    ExtractLhsContext lhs = ctx.extractLhs();
    ExpressionContext expr = ctx.expression();
    if (lhs instanceof SimpleExtractContext simple) {
      // Simple assignment; handle it specially to avoid first storing the rhs in a temp.
      // The grammar is ambiguous so e.g. "a = 3" could end up here or
      // in visitAssignmentStatement, but we generate the same code either way.
      blockCompiler.compileSet(simple.lowerId().entry.local, expr);
    } else {
      // Compile the rhs (usually into a temp) and then let blockCompiler.extract() do the
      // actual extraction.
      blockCompiler.extract(lhs, blockCompiler.compile(expr));
    }
    return null;
  }

  @Override
  public Void visitEmitStatement(EmitStatementContext ctx) {
    LowerIdContext id = ctx.lowerId();
    Local state = id.entry.local; // That was set by PassTwo to the _rw Local
    String roName = Compiler.roName(id.start.getText());
    Local ro = blockCompiler.scope.getExistingEntry(roName).local;
    Expr emitted = blockCompiler.compile(ctx.expression());
    String fnName = (ctx.dist != null) ? "emitAll" : "emitValue";
    blockCompiler.ib.emitCall(state, vmCore().lookupFunction(fnName, 3), ro, state, emitted);
    return null;
  }

  /** Returns the Locals corresponding to each of the given ids. */
  private static Local[] getLocals(List<LowerIdContext> ids) {
    return (ids == null)
        ? new Local[0]
        : ids.stream().map(id -> id.entry.local).toArray(Local[]::new);
  }

  @Override
  public Void visitAssertStatement(AssertStatementContext ctx) {
    BranchTarget done = blockCompiler.ib.newTarget();
    ExpressionContext expr = ctx.expression();
    blockCompiler.compileTest(done, true, expr);
    TerminalNode msgNode = ctx.STRING();
    String msg = (msgNode == null) ? "Assertion failed" : StringUtil.unescape(msgNode.getText());
    blockCompiler.ib.emitError(msg, getLocals(ctx.lowerId()));
    blockCompiler.ib.defineTarget(done);
    return null;
  }

  @Override
  public Void visitTraceStatement(TraceStatementContext ctx) {
    TerminalNode msgNode = ctx.STRING();
    String msg = (msgNode == null) ? null : StringUtil.unescape(msgNode.getText());
    blockCompiler.ib.emitTrace(msg, getLocals(ctx.lowerId()));
    return null;
  }

  @Override
  public Void visitReturnStatement(ReturnStatementContext ctx) {
    ExpressionContext expr = ctx.expression();
    // blockCompiler.compileReturn() adds any inOut parameters as additional results.
    blockCompiler.compileReturn(expr == null ? null : blockCompiler.compile(expr));
    return null;
  }

  @Override
  public Void visitIfStatement(IfStatementContext ctx) {
    BranchTarget elseBlock = blockCompiler.ib.newTarget();
    blockCompiler.compileTest(elseBlock, false, ctx.expression());
    visitBlock(ctx.ifBlock);
    List<ElseIfContext> elseIfs = ctx.elseIf();
    if (ctx.elseBlock == null && elseIfs.isEmpty()) {
      blockCompiler.ib.defineTarget(elseBlock);
      return null;
    }
    BranchTarget done = blockCompiler.ib.newTarget();
    for (ElseIfContext elseIf : elseIfs) {
      blockCompiler.ib.emitBranch(done);
      blockCompiler.ib.defineTarget(elseBlock);
      elseBlock = blockCompiler.ib.newTarget();
      blockCompiler.compileTest(elseBlock, false, elseIf.expression());
      visitBlock(elseIf.block());
    }
    if (ctx.elseBlock != null) {
      blockCompiler.ib.emitBranch(done);
      blockCompiler.ib.defineTarget(elseBlock);
      visitBlock(ctx.elseBlock);
    } else {
      blockCompiler.ib.defineTarget(elseBlock);
    }
    blockCompiler.ib.defineTarget(done);
    return null;
  }

  @Override
  public Void visitFunctionCallStatement(FunctionCallStatementContext ctx) {
    blockCompiler.expressionCompiler.compileFunctionCallStatement(ctx.functionCall());
    return null;
  }

  @Override
  public Void visitBreakStatement(BreakStatementContext ctx) {
    ctx.breakStmt().loopBreak.passThree();
    return null;
  }

  @Override
  public Void visitContinueStatement(ContinueStatementContext ctx) {
    Loop loop = (Loop) blockCompiler;
    loop.ib.emitBranch(loop.continueTarget);
    return null;
  }

  @Override
  public Void visitForStatement(ForStatementContext ctx) {
    ForLoopContext forLoop = ctx.forLoop();
    Loop loop = forLoop.loop;
    int tmpState = blockCompiler.saveTmpState();
    ExpressionContext srcExpr = forLoop.expression();
    Expr source = (srcExpr == null) ? null : blockCompiler.compile(srcExpr);
    // We emit a call to loopHelper to setup each collected var, but for unbounded (no source
    // collection loops) we use a simpler version of the function.
    Vm.Function loopHelper = vmCore().lookupFunction("loopHelper", (srcExpr == null) ? 1 : 4);
    // The loop state has one element for each collected var followed by one element for each
    // sequential var.
    int cvSize = loop.collectedVars.size();
    int stateSize = cvSize + loop.stateVars.size();
    Local[] allRo = new Local[cvSize];
    Local[] allRw = new Local[stateSize];
    Expr eKind =
        vmCore().lookupSingleton((forLoop.key == null) ? "EnumerateValues" : "EnumerateWithKeys");
    for (CollectedVar cv : loop.collectedVars) {
      if (cv.expression == null) {
        // Collected vars without an expression just reference the _rw and _ro vars that
        // should already be defined in the scope outside the loop.  We call verifyEV to
        // ensure that the collector wasn't keyed.
        Local ro = cv.outerRo.local;
        allRo[cv.index] = ro;
        allRw[cv.index] = cv.output.local;
        blockCompiler.ib.emitCall(new Local[0], vmCore().lookupFunction("verifyEV", 1), ro);
        continue;
      } else if (!cv.firstWithExpression) {
        // If we're sharing the collector expression from a previous var, out setup has
        // been taken care of already (see the "for (int i = cv.index + 1; ..." loop below).
        continue;
      }
      Local ro = blockCompiler.newLocal(cv.ro.name);
      blockCompiler.compileSet(ro, cv.expression);
      Local rw = blockCompiler.newTmp();
      if (source == null) {
        blockCompiler.ib.emitCall(new Local[] {ro, rw}, loopHelper, ro);
      } else {
        Expr eKindIn = eKind;
        if (!(eKind instanceof Local)) {
          eKind = blockCompiler.newTmp();
        }
        blockCompiler.ib.emitCall(
            new Local[] {ro, rw, (Local) eKind},
            loopHelper,
            ro,
            source,
            eKindIn,
            loop.isSequential ? blockCompiler.symbols.vmFalse : blockCompiler.symbols.vmTrue);
      }
      allRo[cv.index] = ro;
      allRw[cv.index] = rw;
      // If this collector is shared by multiple collectedVars, they can all use the same
      // _ro var and we can get _rw values with a simpler alternative to loopHelper()
      for (int i = cv.index + 1; i < loop.collectedVars.size(); i++) {
        CollectedVar cv2 = loop.collectedVars.get(i);
        if (cv2.expression == null || cv2.firstWithExpression) {
          break;
        }
        Local rw2 = blockCompiler.newTmp();
        blockCompiler.ib.emitCall(
            rw2,
            vmCore().lookupFunction("anotherRw", 2),
            ro,
            (source == null) ? blockCompiler.symbols.vmAbsent : source);
        allRo[i] = ro;
        allRw[i] = rw2;
      }
    }
    // The loop's closure includes inherited variables, which will be filled in automatically
    // by the newClosureExprs() call, plus the _ro variables and the possibly the eKind,
    // which we need to fill in manually.
    Expr[] closureExprs = loop.scope.newClosureExprs();
    int nextClosureExpr = 0;
    for (CollectedVar cv : loop.collectedVars) {
      if (cv.expression == null || cv.firstWithExpression) {
        assert closureExprs[nextClosureExpr] == null;
        closureExprs[nextClosureExpr++] = allRo[cv.index];
      }
    }
    if (loop.eKind != null) {
      assert closureExprs[nextClosureExpr] == null && eKind instanceof Local;
      closureExprs[nextClosureExpr++] = eKind;
    }
    assert nextClosureExpr == loop.scope.firstInherited;
    // Unless the loop state is trivial (possible, but rare) we need to populate it with
    // the _rw vars (already set above) and any sequential vars.
    Expr initialState;
    if (stateSize == 0) {
      assert loop.stateCompound == null;
      initialState = blockCompiler.symbols.vmAbsent;
    } else {
      for (int i = cvSize; i < stateSize; i++) {
        allRw[i] = loop.stateVars.get(i - cvSize).outer.local;
      }
      initialState = loop.stateCompound.make(allRw);
    }
    // Now we're ready to emit the call to enumerate(), iterate(), or iterateUnbounded().
    blockCompiler.resetTmps(tmpState);
    Local finalState = blockCompiler.newTmp();
    Expr loopClosure = loop.scope.getClosure(closureExprs);
    if (source == null) {
      blockCompiler.ib.emitCall(
          finalState, vmCore().lookupFunction("iterateUnbounded", 2), loopClosure, initialState);
    } else {
      blockCompiler.ib.emitCall(
          finalState,
          vmCore().lookupFunction(loop.isSequential ? "iterate" : "enumerate", 4),
          source,
          eKind,
          loopClosure,
          initialState);
    }
    // Now examine the result to see if it was a LoopExit (i.e. a break) or a regular
    // return (in which case it will be the final state).
    BranchTarget done = null;
    int nBreaks = loop.breaks.size();
    if (nBreaks != 0) {
      // "done" will be the label we end up at after normal exit or completing a
      // (non-terminal) break body
      done = blockCompiler.ib.newTarget();
      BranchTarget noBreak = null;
      if (source != null) {
        noBreak = blockCompiler.ib.newTarget();
        blockCompiler.ib.emitTypeCheckBranch(
            noBreak, finalState, vmCore().lookupType("LoopExit"), false);
      }
      // If there's only one break and it gets no other information back,
      // we won't need to look at the result any further; otherwise it has
      // information we'll need
      if (nBreaks != 1 || loop.breaks.get(0).scope.compound != null) {
        blockCompiler.ib.emitCall(
            finalState, vmCore().lookupFunction("loopExitState", 1), finalState);
      }
      for (int i = 0; i < nBreaks; i++) {
        Loop.Break brk = loop.breaks.get(i);
        BranchTarget next = null;
        // For all breaks except the last, test if the result came from this break
        if (i != nBreaks - 1) {
          next = blockCompiler.ib.newTarget();
          blockCompiler.ib.emitTypeCheckBranch(next, finalState, brk.scope.getType(), false);
        }
        // If the break result includes any values, extract them.
        brk.scope.emitInitFromSelf(finalState);
        if (loop.isSequential) {
          // The final values of collected vars are accessible after a break in a sequential loop.
          getFinalResults(loop, brk.live, allRo);
        }
        if (brk.block != null) {
          visitBlock(brk.block);
        }
        // If this is the last break and there's no non-break exit we can skip the
        // branch-around-branch.
        if (next != null || source != null) {
          blockCompiler.ib.emitBranch(done);
          blockCompiler.maybeDefineTarget(next);
        }
      }
      blockCompiler.maybeDefineTarget(noBreak);
    }
    // If the loop exits normally, extract collector and sequential var final values from the
    // result.
    if (source != null && loop.stateCompound != null) {
      Local[] statesOut = new Local[stateSize];
      for (Loop.CollectedVar cv : loop.collectedVars) {
        statesOut[cv.index] = cv.output.local;
      }
      for (int i = cvSize; i < stateSize; i++) {
        statesOut[i] = loop.stateVars.get(i - cvSize).outer.local;
      }
      blockCompiler.ib.emitCall(statesOut, loop.stateCompound.extract(), finalState);
      getFinalResults(loop, loop.liveAfter, allRo);
    }

    blockCompiler.maybeDefineTarget(done);
    return null;
  }

  /** Emits finalStateHelper calls for all live collected vars. */
  private void getFinalResults(Loop loop, Bits live, Local[] allRo) {
    Vm.Function helper = vmCore().lookupFunction("finalStateHelper", 2);
    for (CollectedVar cv : loop.collectedVars) {
      if (cv.expression != null && live.test(cv.output.index)) {
        blockCompiler.ib.emitCall(cv.output.local, helper, allRo[cv.index], cv.output.local);
      }
    }
  }
}
