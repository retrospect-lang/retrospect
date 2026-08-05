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

import org.retrolang.Vm.Access;
import org.retrolang.compiler.RetrospectParser.ArrayExtractContext;
import org.retrolang.compiler.RetrospectParser.AssertStatementContext;
import org.retrolang.compiler.RetrospectParser.AssignmentStatementContext;
import org.retrolang.compiler.RetrospectParser.BlockContext;
import org.retrolang.compiler.RetrospectParser.BreakStatementContext;
import org.retrolang.compiler.RetrospectParser.BreakStmtContext;
import org.retrolang.compiler.RetrospectParser.CollectVarContext;
import org.retrolang.compiler.RetrospectParser.ContinueStatementContext;
import org.retrolang.compiler.RetrospectParser.DiscardExtractContext;
import org.retrolang.compiler.RetrospectParser.EmitStatementContext;
import org.retrolang.compiler.RetrospectParser.ExpressionContext;
import org.retrolang.compiler.RetrospectParser.ExtractLhsContext;
import org.retrolang.compiler.RetrospectParser.ExtractStatementContext;
import org.retrolang.compiler.RetrospectParser.ExtractStructElementContext;
import org.retrolang.compiler.RetrospectParser.ForLoopContext;
import org.retrolang.compiler.RetrospectParser.ForStatementContext;
import org.retrolang.compiler.RetrospectParser.FunctionCallStatementContext;
import org.retrolang.compiler.RetrospectParser.IfStatementContext;
import org.retrolang.compiler.RetrospectParser.LowerIdContext;
import org.retrolang.compiler.RetrospectParser.ReturnStatementContext;
import org.retrolang.compiler.RetrospectParser.SimpleExtractContext;
import org.retrolang.compiler.RetrospectParser.StructExtractContext;
import org.retrolang.compiler.RetrospectParser.TraceStatementContext;

/// The first pass through a statement block does only two things:
///
/// - adds all locals that appear on the left hand side of an assignment statement (without
///   indexing) to the current scope, and links their lowerIds to the scope entries; and
/// - creates and initializes Loop and Break objects for any for loops or break statements within
///   the block, and recursively runs PassOne on the loop bodies.
class PassOne extends VisitorBase<Void> {

  /// Runs the first compilation pass over the given block.
  static void apply(Scope scope, BlockContext ctx) {
    new PassOne(scope).visitBlock(ctx);
  }

  private final Scope scope;

  private PassOne(Scope scope) {
    this.scope = scope;
  }

  @Override
  public Void visitBlock(BlockContext ctx) {
    ctx.statement().forEach(this::visit);
    return null;
  }

  @Override
  public Void visitAssignmentStatement(AssignmentStatementContext ctx) {
    if (!Compiler.readsFirst(ctx)) {
      visitLowerId(ctx.lowerId());
    }
    return null;
  }

  @Override
  public Void visitFunctionCallStatement(FunctionCallStatementContext ctx) {
    return null;
  }

  @Override
  public Void visitEmitStatement(EmitStatementContext ctx) {
    return null;
  }

  @Override
  public Void visitExtractStatement(ExtractStatementContext ctx) {
    visit(ctx.extractLhs());
    return null;
  }

  @Override
  public Void visitReturnStatement(ReturnStatementContext ctx) {
    if (scope.blockCompiler instanceof Loop) {
      throw error("'return' not allowed in loop (use 'break {return}')");
    }
    return null;
  }

  @Override
  public Void visitAssertStatement(AssertStatementContext ctx) {
    return null;
  }

  @Override
  public Void visitTraceStatement(TraceStatementContext ctx) {
    return null;
  }

  @Override
  public Void visitContinueStatement(ContinueStatementContext ctx) {
    if (!(scope.blockCompiler instanceof Loop)) {
      throw error("'continue' not in loop");
    }
    return null;
  }

  @Override
  public Void visitBreakStatement(BreakStatementContext ctx) {
    if (!(scope.blockCompiler instanceof Loop)) {
      throw error("'break' not in loop");
    }
    Loop loop = (Loop) scope.blockCompiler;
    // There doesn't seem to be any way to get an extra field (the link to a Loop.Break) without
    // this extra level of indirection (breakStatement -> breakStmt).  Bleah.
    BreakStmtContext brkCtx = ctx.breakStmt();
    assert brkCtx.loopBreak == null;
    // addBreak recursively calls PassOne on the break block
    brkCtx.loopBreak = loop.addBreak(brkCtx, scope);
    return null;
  }

  @Override
  public Void visitForStatement(ForStatementContext ctx) {
    ForLoopContext forLoop = ctx.forLoop();
    assert forLoop.loop == null;
    boolean isSequential = (forLoop.seq != null);
    ExpressionContext source = forLoop.expression();
    if (source == null && !isSequential) {
      throw error("'for' without collection must be sequential");
    }
    String loopName = String.format("loop@%s", forLoop.start.getLine());
    Loop loop = new Loop(scope, isSequential, source != null, loopName);
    forLoop.loop = loop;
    // We create a CollectedVar for each collected var, and (if the loop is sequential) a StateVar
    // for each sequential var.
    for (CollectVarContext collectVar : forLoop.collectVar()) {
      ExpressionContext expression = collectVar.expression();
      boolean firstWithExpression = (expression != null);
      // In 'collect { a, b =| sum; c, d =| save }`, the CollectedVars for 'a' and 'c' will have
      // firstWithExpression=true, while those for 'b' and 'd' will have firstWithExpression=false.
      for (LowerIdContext id : collectVar.ids) {
        loop.addCollectedVar(id, expression, firstWithExpression);
        firstWithExpression = false;
      }
    }
    // If a loop has no variable bound to the key, but at least one collected var with an
    // expression, then we don't know until runtime whether we'll be enumerating with or without
    // keys (it depends on the collectors).
    // In that case we need to add a closure var to record what we ended up with, so that the
    // nextState() method knows how to interpret its argument.
    if (forLoop.key == null && loop.mayHaveKeyedCollectors()) {
      // We need an Entry (to add to closureVars) but we don't need to put it in the map (since
      // it can't be referred to by name).
      loop.eKind = loop.scope.blockCompiler.newLocal("_eKind");
      loop.scope.addClosureVar(new Scope.Entry("_eKind", -1, loop.eKind));
    }
    for (LowerIdContext seqId : forLoop.seqIds) {
      loop.addStateVar(seqId, seqId.start.getText());
    }
    String[] stateNames = loop.stateNames();
    if (stateNames.length != 0) {
      loop.stateCompound =
          scope.blockCompiler.symbols.module.newCompoundType(
              loopName + "_state", stateNames, Access.PRIVATE);
    }
    // Scope entries created after this must be used in the body.
    loop.kvStart = loop.scope.nextIndex();
    if (forLoop.key != null) {
      loop.scope.addVars(forLoop.key);
    }
    if (forLoop.value != null) {
      loop.scope.addVars(forLoop.value);
    }
    // All of the scope entries created so far will be initialized before the body starts.
    loop.numDefinedAtStart = loop.scope.nextIndex();
    // Now that we've set up the loop scope, run PassOne on the loop body.
    apply(loop.scope, forLoop.block());
    if (loop.breaks.isEmpty() && !loop.hasSource) {
      throw error("Loop cannot exit");
    }
    return null;
  }

  @Override
  public Void visitIfStatement(IfStatementContext ctx) {
    visitBlock(ctx.ifBlock);
    ctx.elseIf().forEach(ei -> visitBlock(ei.block()));
    if (ctx.elseBlock != null) {
      visitBlock(ctx.elseBlock);
    }
    return null;
  }

  @Override
  public Void visitLowerId(LowerIdContext ctx) {
    assert ctx.entry == null;
    ctx.entry = scope.getEntryForWrite(ctx.start.getText(), ctx.start);
    return null;
  }

  @Override
  public Void visitSimpleExtract(SimpleExtractContext ctx) {
    visitLowerId(ctx.lowerId());
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
        visitLowerId(se.lowerId());
      } else {
        visit(lhs);
      }
    }
    return null;
  }
}
