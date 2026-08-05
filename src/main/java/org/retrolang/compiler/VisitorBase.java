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
import com.google.errorprone.annotations.FormatMethod;
import java.util.function.Function;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.retrolang.compiler.RetrospectParser.ParenExpressionContext;

/// A base class for ANTLR visitors that provides two useful functions:
///
/// - It disables the default "do nothing" behavior for node types that haven't been overridden,
///   since I found it was never what I wanted and just made it take longer to find bugs. Visiting a
///   node that doesn't have an explicit visit\* method will throw an AssertionError.
/// - It provides error() methods that automatically fill in the node currently being visited as the
///   location of the error.
class VisitorBase<T> extends RetrospectBaseVisitor<T> {

  /// The node currently being visited.
  private ParseTree currentNode;

  @Override
  protected final T defaultResult() {
    // defaultResult() is called by all the default visitXXX() methods defined on
    // RetrospectBaseVisitor.  Our intent is to override all of the methods that might
    // be called, so we should never get here.
    throw new AssertionError();
  }

  @Override
  public final T visit(ParseTree tree) {
    return visitWithCurrentNode(tree, super::visit);
  }

  /// Calls `visitor` with the given node, binding [#currentNode] for the duration of the call.
  ///
  /// Assumes that if the function throws an exception, this Visitor will not be used again (no
  /// attempt is made to restore the correct currentNode state).
  @CanIgnoreReturnValue
  T visitWithCurrentNode(ParseTree node, Function<ParseTree, T> visitor) {
    ParseTree prevNode = currentNode;
    currentNode = node;
    T result = visitor.apply(node);
    currentNode = prevNode;
    return result;
  }

  @Override
  public final T visitParenExpression(ParenExpressionContext ctx) {
    // Parentheses don't change the interpretation of the parenthesized expression.
    return visit(ctx.expression());
  }

  /// Returns the first token of the current node.
  Token currentToken() {
    return ((ParserRuleContext) currentNode).start;
  }

  /// Returns a [CompileError] pointing at the current node.
  CompileError error(String msg) {
    return Compiler.error(currentToken(), msg);
  }

  /// Returns a [CompileError] pointing at the current node.
  @FormatMethod
  CompileError error(String fmt, Object... fmtArgs) {
    return Compiler.error(currentToken(), fmt, fmtArgs);
  }
}
