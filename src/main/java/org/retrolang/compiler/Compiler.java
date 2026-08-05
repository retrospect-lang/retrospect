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

import com.google.errorprone.annotations.FormatMethod;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.retrolang.Vm;
import org.retrolang.Vm.InstructionBlock;
import org.retrolang.Vm.ModuleBuilder;
import org.retrolang.compiler.RetrospectParser.AssignmentStatementContext;
import org.retrolang.compiler.RetrospectParser.ExpressionContext;
import org.retrolang.compiler.RetrospectParser.ExtractStructElementContext;
import org.retrolang.compiler.RetrospectParser.FunctionNameContext;
import org.retrolang.compiler.RetrospectParser.IdRefExpressionContext;
import org.retrolang.compiler.RetrospectParser.LowerIdContext;
import org.retrolang.compiler.RetrospectParser.StructElementContext;
import org.retrolang.compiler.RetrospectParser.UnitContext;
import org.retrolang.util.StringUtil;

/// Parses Retrospect source code and calls the appropriate methods on a VirtualMachine.
public final class Compiler {

  // Static methods only
  private Compiler() {}

  /// Compiles a Retrospect program into the given Vm.ModuleBuilder.
  ///
  /// A valid program consists of an optional "main program" (a block of statements, which should
  /// terminate with a return statement), followed by zero or more declarations for types,
  /// functions, and methods.
  ///
  /// @param input the program text
  /// @param source an optional identifier for the source of the program, e.g. a filename; this will
  ///   be included in the stack traces that accompany errors and traces
  /// @param vm the VirtualMachine instance with which to compile the program
  /// @param module all types, functions, and methods defined by the program will be created in this
  ///   module; must have been created by the given VirtualMachine
  /// @param args the names of Retrospect variables whose values will be provided at runtime
  /// @return an InstructionBlock to execute the main program (with one parameter for each element
  ///   of args), or null if no main program was present
  public static InstructionBlock compile(
      CharStream input, Object source, Vm.VirtualMachine vm, ModuleBuilder module, String... args) {
    // Parse the input, then create a Symbols instance to hold the global state and manage the
    // threads used for compilation.
    return Symbols.compile(vm, module, source, parse(input), args);
  }

  /// Parses a Retrospect program.
  public static UnitContext parse(CharStream input) {
    // Throw CompileErrors in response to parsing errors.
    BaseErrorListener errorListener =
        new BaseErrorListener() {
          @Override
          public void syntaxError(
              Recognizer<?, ?> recognizer,
              Object offendingSymbol,
              int lineNum,
              int charPositionInLine,
              String msg,
              RecognitionException e) {
            throw new CompileError(msg, lineNum, charPositionInLine);
          }
        };
    RetrospectLexer lexer = new RetrospectLexer(input);
    lexer.removeErrorListeners();
    lexer.addErrorListener(errorListener);
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    // Set a ThreadLocal so that atBreak() can examine the token stream.
    assert threadTokens.get() == null;
    threadTokens.set(tokens);
    try {
      RetrospectParser parser = new RetrospectParser(tokens);
      parser.removeErrorListeners();
      parser.addErrorListener(errorListener);
      return parser.unit();
    } finally {
      threadTokens.set(null);
    }
  }

  /// A ThreadLocal to enable atBreak() to get access to the token stream.
  private static final ThreadLocal<CommonTokenStream> threadTokens = new ThreadLocal<>();

  /// This is an ANTLR "semantic predicate", magically invoked from the separator rule.
  ///
  /// Returns true if we can consider this an implicit end of a statement, i.e.
  ///
  /// - the previous token was a new line, or
  /// - we are at the end of the file, or
  /// - the next token is "`}`".
  static boolean atBreak(ParserRuleContext ctx) {
    Token next = ctx.getStart();
    int nextType = next.getType();
    if (nextType == TokenType.RIGHT_CURLY || nextType == Token.EOF) {
      return true;
    }
    int prev = next.getTokenIndex() - 1;
    int prevType = threadTokens.get().get(prev).getType();
    return prevType == TokenType.NEW_LINE;
  }

  /// Returns a new CompileError referring to the given token.
  static CompileError error(Token token, String msg) {
    int lineNum;
    int charPositionInLine;
    if (token != null) {
      lineNum = token.getLine();
      charPositionInLine = token.getCharPositionInLine();
    } else {
      // Shouldn't happen, but 0:0 is less useless than a NullPointerException.
      lineNum = 0;
      charPositionInLine = 0;
    }
    return new CompileError(msg, lineNum, charPositionInLine);
  }

  /// Returns a new CompileError referring to the given token.
  @FormatMethod
  static CompileError error(Token token, String fmt, Object... fmtArgs) {
    return error(token, String.format(fmt, fmtArgs));
  }

  /// Returns a new "Cannot modify '%s'" CompileError.
  static CompileError cannotModify(Token id, String name) {
    return error(id, "Cannot modify '%s'", name);
  }

  /// Returns a new "Conflicting assignment to '%s'" CompileError.
  static CompileError conflictingAssignment(Token id, String name) {
    return error(id, "Conflicting assignment to '%s'", name);
  }

  /// Returns a new "Conflicting update to '%s'" CompileError.
  static CompileError conflictingUpdate(Token id, String name) {
    return error(id, "Conflicting update to '%s'", name);
  }

  /// Returns a new "'%s' is not used" CompileError.
  static CompileError notUsed(Token id, String name) {
    return error(id, "'%s' is not used", name);
  }

  /// Returns a new "No initialization of '%s'" CompileError.
  static CompileError notInitialized(Token id, String name) {
    return error(id, "No initialization of '%s'", name);
  }

  /// Returns a new "Distribute (^) not allowed here" CompileError.
  static CompileError cannotDistribute(Token id) {
    return error(id, "Distribute (^) not allowed here");
  }

  /// Retrospect struct keys can be either lowerIds or STRINGs. Given the corresponding parser nodes
  /// (exactly one of which must be non-null) returns the corresponding Java string.
  private static String getKey(ParserRuleContext id, TerminalNode string) {
    if (id != null) {
      assert string == null;
      return id.getText();
    } else {
      return StringUtil.unescape(string.getText());
    }
  }

  /// Returns the key for this structElement node.
  static String getKey(StructElementContext entry) {
    return getKey(entry.lowerId(), entry.STRING());
  }

  /// Returns the key for this extractStructElement node.
  static String getKey(ExtractStructElementContext entry) {
    return getKey(entry.lowerId(), entry.STRING());
  }

  /// Returns true if this assignmentStatement reads the left hand side variable before writing it,
  /// i.e. it has indices (e.g. `a.x = 42`) or it is a compound assignment (e.g. `a += 2`).
  static boolean readsFirst(AssignmentStatementContext ctx) {
    return (ctx.index(0) != null) || (ctx.assignOp().start.getType() != TokenType.EQUALS);
  }

  /// Given an expression that is expected to be a variable name, returns the lowerId node or throws
  /// an appropriate CompileError.
  static LowerIdContext asLowerId(ExpressionContext expression) {
    if (expression instanceof IdRefExpressionContext) {
      // The grammar parses all variable references as zero-argument function calls, since it
      // can't tell the difference.  Confirm that there was no module identifier ("sum" can be
      // a variable name, but "Core.sum" can't be).
      FunctionNameContext fnName = ((IdRefExpressionContext) expression).functionName();
      if (fnName.module == null) {
        return fnName.lowerId();
      }
    }
    throw error(expression.start, "Variable name expected");
  }

  /// Given the name of a collector variable, returns the name of the corresponding "\_ro"
  /// (read-only) Local.
  static String roName(String varName) {
    return varName + "_ro";
  }

  /// Given the name of a collector variable, returns the name of the corresponding "\_rw"
  /// (read-write) Local.
  static String rwName(String varName) {
    return varName + "_rw";
  }
}
