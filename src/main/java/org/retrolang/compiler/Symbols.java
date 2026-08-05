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
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountedCompleter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.Nullable;
import org.retrolang.Vm;
import org.retrolang.Vm.Expr;
import org.retrolang.Vm.InstructionBlock;
import org.retrolang.Vm.Local;
import org.retrolang.compiler.RetrospectParser.ArgMethodPredicateContext;
import org.retrolang.compiler.RetrospectParser.ArrayExtractContext;
import org.retrolang.compiler.RetrospectParser.BlockContext;
import org.retrolang.compiler.RetrospectParser.ConjMethodPredicateContext;
import org.retrolang.compiler.RetrospectParser.DeclContext;
import org.retrolang.compiler.RetrospectParser.DiscardExtractContext;
import org.retrolang.compiler.RetrospectParser.ExpressionContext;
import org.retrolang.compiler.RetrospectParser.ExtractLhsContext;
import org.retrolang.compiler.RetrospectParser.FunctionDeclContext;
import org.retrolang.compiler.RetrospectParser.FunctionNameContext;
import org.retrolang.compiler.RetrospectParser.LeafTypeDeclContext;
import org.retrolang.compiler.RetrospectParser.LowerIdContext;
import org.retrolang.compiler.RetrospectParser.MethodDeclContext;
import org.retrolang.compiler.RetrospectParser.MethodDefContext;
import org.retrolang.compiler.RetrospectParser.MethodPredicateContext;
import org.retrolang.compiler.RetrospectParser.ParamAnnotationContext;
import org.retrolang.compiler.RetrospectParser.ParamContext;
import org.retrolang.compiler.RetrospectParser.ParamListContext;
import org.retrolang.compiler.RetrospectParser.ParenMethodPredicateContext;
import org.retrolang.compiler.RetrospectParser.SimpleExtractContext;
import org.retrolang.compiler.RetrospectParser.StructExtractContext;
import org.retrolang.compiler.RetrospectParser.TypeListContext;
import org.retrolang.compiler.RetrospectParser.TypeNameContext;
import org.retrolang.compiler.RetrospectParser.UnionTypeDeclContext;
import org.retrolang.compiler.RetrospectParser.UnitContext;
import org.retrolang.util.Bits;
import org.retrolang.util.Pair;

/// A Symbols instance holds the global context while compiling a Retrospect program, including
///
/// - arguments passed to compile() (the VirtualMachine, ModuleBuilder, etc.);
/// - a bunch of commonly-used VM objects;
/// - the types and functions defined by the program; and
/// - the base ForkJoinTask tracking all the subtasks.
class Symbols {
  final Vm.VirtualMachine vm;
  final Vm.ModuleBuilder module;
  final Object source;

  final Vm.Module vmCore;
  final Vm.Singleton vmTrue;
  final Vm.Singleton vmFalse;
  final Vm.Singleton vmAbsent;
  final Vm.Singleton vmNone;
  final Vm.Type vmInteger;
  final Vm.Type vmLambda;
  final Vm.Function vmAt;
  final Vm.Function vmNot;
  final Vm.Function vmPipe;
  final Vm.Function vmBinaryUpdate;
  final Vm.Function vmConcatUpdate;

  /// We create separate ForkJoinTasks for each method declaration (since they can be compiled
  /// independently) and add them all as children of this base task so that we can easily wait until
  /// they're all done.
  private final CountedCompleter<Void> baseTask =
      new CountedCompleter<Void>() {
        @Override
        public void compute() {
          // The base task doesn't do anything itself; it just waits for all its children to
          // complete.
          tryComplete();
        }
      };

  /// This map will have an entry for each of the "singleton", "compound", or "type" declarations in
  /// the program, keyed by its name. It is linked to preserve the original ordering.
  ///
  /// Access to this map is unsynchronized; it is only mutated during the initial (single-threaded)
  /// stage of computation, and once we start creating subtasks it must be considered immutable.
  private final Map<String, TypeDecl> types = new LinkedHashMap<>();

  /// This map will have an entry for each of the function declarations in the program, with a key
  /// of "name:numArgs".
  ///
  /// Access to this map is unsynchronized; it is only mutated during the initial (single-threaded)
  /// stage of computation, and once we start creating subtasks it must be considered immutable.
  private final Map<String, FunctionDecl> functions = new HashMap<>();

  /// Creates a Symbols instance for a compilation, does the initial compilation steps, and waits
  /// for any asynchronous steps to complete.
  ///
  /// Returns an InstructionBlock for the main program, or null if there was no main program.
  static InstructionBlock compile(
      Vm.VirtualMachine vm,
      Vm.ModuleBuilder module,
      Object source,
      UnitContext unit,
      String[] argNames) {
    Symbols symbols = new Symbols(vm, module, source);
    InstructionBlock result = symbols.doCompile(unit, argNames);
    // Wait for the subtasks to complete.
    symbols.baseTask.invoke();
    return result;
  }

  private Symbols(Vm.VirtualMachine vm, Vm.ModuleBuilder module, Object source) {
    this.vm = vm;
    this.module = module;
    this.source = source;
    this.vmCore = vm.core();
    this.vmTrue = vmCore.lookupSingleton("True");
    this.vmFalse = vmCore.lookupSingleton("False");
    this.vmAbsent = vmCore.lookupSingleton("Absent");
    this.vmNone = vmCore.lookupSingleton("None");
    this.vmInteger = vmCore.lookupType("Integer");
    this.vmLambda = vmCore.lookupType("Lambda");
    this.vmAt = vmCore.lookupFunction("at", 2);
    this.vmNot = vmCore.lookupFunction("not", 1);
    this.vmPipe = vmCore.lookupFunction("pipe", 2);
    this.vmBinaryUpdate = vmCore.lookupFunction("binaryUpdate", 3);
    this.vmConcatUpdate = vmCore.lookupFunction("concatUpdate", 2);
  }

  /// Compiles a unit. Processes all the type and function declarations first, then creates subtasks
  /// for each of the method declarations (including those implicit in lambda expressions and loop
  /// bodies), and then compiles the main program itself.
  private @Nullable InstructionBlock doCompile(UnitContext unit, String[] argNames) {
    // Step one: scan all the declarations, saving functions and types into the corresponding
    // hashMaps and methods into a list for later.
    List<MethodDeclContext> methods = new ArrayList<>();
    for (DeclContext decl : unit.decl()) {
      if (decl instanceof FunctionDeclContext) {
        FunctionDecl functionDecl = new FunctionDecl((FunctionDeclContext) decl, this);
        String key = functionDecl.name + ":" + functionDecl.numArgs;
        FunctionDecl prev = functions.put(key, functionDecl);
        if (prev != null) {
          throw Compiler.error(
              functionDecl.parsed.start, "Duplicate function definition for '%s'", key);
        }
      } else if (decl instanceof MethodDeclContext) {
        methods.add((MethodDeclContext) decl);
      } else {
        TypeDecl typeDecl;
        if (decl instanceof LeafTypeDeclContext) {
          typeDecl = new LeafTypeDecl((LeafTypeDeclContext) decl);
        } else {
          typeDecl = new UnionTypeDecl((UnionTypeDeclContext) decl);
        }
        TypeDecl prev = types.put(typeDecl.name, typeDecl);
        if (prev != null) {
          throw Compiler.error(decl.start, "Duplicate type definition for '%s'", typeDecl.name);
        }
      }
    }
    // Step two: create the types declared in this program.
    // We wait to do this until they've all been added to the types table so that we can catch
    // forward references.
    for (TypeDecl typeDecl : types.values()) {
      typeDecl.create(this);
    }
    // Step three: create subtasks to compile each of the method declarations.
    // Starting here we're no longer single-threaded, so there must be no further updates to the
    // type and function tables.
    functions.values().forEach(fd -> fd.compileMethod(this));
    methods.forEach(this::addMethodSubtask);
    // Step four: if there was a main program, compile it.
    // (This runs in parallel with the subtasks created in step three.)
    BlockContext block = unit.block();
    if (block.statement().isEmpty()) {
      return null;
    }
    InstructionBlock mainBlock = module.newInstructionBlock(argNames.length, 1, source);
    BlockCompiler blockCompiler = new BlockCompiler(this, mainBlock, null);
    for (String argName : argNames) {
      blockCompiler.scope.addArg(argName, null, /* isInOut= */ false);
    }
    blockCompiler.setResults((Expr) null);
    PassOne.apply(blockCompiler.scope, block);
    PassTwo.apply(blockCompiler.scope, block, argNames.length, true);
    PassThree.apply(blockCompiler, block);
    if (mainBlock.nextInstructionIsReachable()) {
      blockCompiler.setLineNumber(block.stop);
      mainBlock.emitError("Missing return statement");
    }
    mainBlock.done();
    return mainBlock;
  }

  private void addMethodSubtask(MethodDeclContext method) {
    List<ParamContext> params = params(method.paramList());
    // To figure out the arity of the function, we need to count collector args twice.
    int numCollector = (int) params.stream().filter(p -> isCollector(p.paramAnnotation())).count();
    Vm.Function function = getFunction(method.name, params.size() + numCollector);
    addSubTask(
        () ->
            compileMethodDecl(
                function,
                params,
                method.methodPredicate(),
                method.isDefault != null,
                method.methodDef()));
  }

  /// A map from binary operator (e.g. "+") to the corresponding Retrospect Core function name (e.g.
  /// "add").
  static final ImmutableMap<String, String> BIN_OP_FUNCTION_NAMES =
      ImmutableMap.<String, String>builder()
          .put("+", "add")
          .put("-", "subtract")
          .put("*", "multiply")
          .put("/", "divide")
          .put("%", "modulo")
          .put("**", "exponent")
          .put("&", "concat")
          .put("|", "pipe")
          .put("==", "equal")
          .put("<", "lessThan")
          .put("..", "range")
          .put("++", "rangeWithSize")
          .buildOrThrow();

  /// A map with the same entries as BIN\_OP\_FUNCTION\_NAMES, but keyed by token type instead of
  /// string.
  static final ImmutableMap<Integer, String> FUNCTION_NAME_FROM_BINOP_TOKEN;

  /// A map from compound assignment operator (e.g. "+=") to the corresponding function name (e.g.
  /// "add"), keyed by token type. Note that this includes all tokens that look like assignment
  /// operators, even if they're not (e.g. "<=" maps to "lessThan"); those entries will never be
  /// looked up (since the grammar doesn't recognize them as an assignOp) so they're harmless.
  static final ImmutableMap<Integer, String> FUNCTION_NAME_FROM_ASSIGNMENT_TOKEN;

  static {
    // Construct FUNCTION_NAME_FROM_BINOP_TOKEN and FUNCTION_NAME_FROM_ASSIGNMENT_TOKEN
    // from BIN_OP_FUNCTION_NAMES.
    ImmutableMap.Builder<Integer, String> opBuilder = ImmutableMap.builder();
    ImmutableMap.Builder<Integer, String> assignmentBuilder = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : BIN_OP_FUNCTION_NAMES.entrySet()) {
      int opTokenType = TokenType.of("'" + entry.getKey() + "'");
      opBuilder.put(opTokenType, entry.getValue());
      Integer assignmentTokenType = TokenType.MAP.get("'" + entry.getKey() + "='");
      if (assignmentTokenType != null) {
        // "|=" is a special case; rather than using "pipe" it uses "reversedAt"
        String fnName = entry.getKey().equals("|") ? "reversedAt" : entry.getValue();
        assignmentBuilder.put(assignmentTokenType, fnName);
      }
    }
    FUNCTION_NAME_FROM_BINOP_TOKEN = opBuilder.buildOrThrow();
    FUNCTION_NAME_FROM_ASSIGNMENT_TOKEN = assignmentBuilder.buildOrThrow();
  }

  /// Adds an asynchronous compilation step that must be completed before the compilation returns.
  void addSubTask(Runnable runnable) {
    baseTask.addToPendingCount(1);
    new CountedCompleter<Void>(baseTask) {
      @Override
      public void compute() {
        runnable.run();
        tryComplete();
      }
    }.fork();
  }

  /// Compiles a method predicate expression into a Vm.MethodPredicate.
  ///
  /// @param positive if false, the result should be negated
  /// @param ambiguousTokenType if TokenType.KEYWORD\_AND or TokenType.KEYWORD\_OR, the predicate
  ///   may not be a conjunction using that keyword (since the expression would be ambiguous)
  /// @param paramNames the method's parameter names; some or all entries may be null if the
  ///   corresponding parameter does not have a name
  private Vm.MethodPredicate getPredicate(
      MethodPredicateContext ctx, boolean positive, int ambiguousTokenType, String[] paramNames) {
    while (ctx instanceof ParenMethodPredicateContext) {
      ParenMethodPredicateContext pCtx = (ParenMethodPredicateContext) ctx;
      if (pCtx.negation != null) {
        positive = !positive;
      }
      ctx = pCtx.methodPredicate();
      ambiguousTokenType = 0;
    }
    if (ctx instanceof ArgMethodPredicateContext) {
      ArgMethodPredicateContext arg = (ArgMethodPredicateContext) ctx;
      Vm.Type type = getType(arg.typeName());
      if (type == vmInteger) {
        throw Compiler.error(ctx.start, "Integer may not be used as a method predicate");
      }
      String id = arg.lowerId().getText();
      if (arg.negation != null) {
        positive = !positive;
      }
      for (int i = 0; i < paramNames.length; i++) {
        if (id.equals(paramNames[i])) {
          return type.argType(i, positive);
        }
      }
      throw Compiler.error(ctx.start, "'%s' is not an argument of this method", id);
    } else {
      ConjMethodPredicateContext conj = (ConjMethodPredicateContext) ctx;
      int keyword = conj.op.getType();
      if (keyword == ambiguousTokenType) {
        throw Compiler.error(ctx.start, "Ambiguous method predicate, needs parentheses");
      }
      boolean isAnd = (keyword == TokenType.KEYWORD_AND);
      assert isAnd || (keyword == TokenType.KEYWORD_OR);
      int otherConj = isAnd ? TokenType.KEYWORD_OR : TokenType.KEYWORD_AND;
      Vm.MethodPredicate left = getPredicate(conj.left, positive, otherConj, paramNames);
      Vm.MethodPredicate right = getPredicate(conj.right, positive, otherConj, paramNames);
      return (isAnd == positive) ? left.and(right) : left.or(right);
    }
  }

  /// Compiles a method, found either as part of a `function` declaration or in a standalone
  /// `method` declaration. Handles both simple expressions (`methodDef` begins with "`=`") and
  /// blocks (`methodDef` begins with "`{`").
  private void compileMethodDecl(
      Vm.Function function,
      List<ParamContext> params,
      MethodPredicateContext predicateCtx,
      boolean isDefault,
      MethodDefContext methodDef) {
    InstructionBlock ib =
        module.newInstructionBlock(function.numArgs(), function.numResults(), source);
    BlockCompiler blockCompiler = new BlockCompiler(this, ib, null);
    Pair<String[], Vm.MethodPredicate> paramInfo =
        processParams(params, function, blockCompiler, methodDef.start);
    // The methodPredicate may be inline in the parameters, or as a separate expression; if both
    // are present, combine them with 'and'.
    Vm.MethodPredicate predicate = paramInfo.y;
    if (predicateCtx != null) {
      predicate = getPredicate(predicateCtx, true, 0, paramInfo.x);
      if (paramInfo.y != null) {
        predicate = predicate.and(paramInfo.y);
      }
    }
    Scope.ForMethod scope = blockCompiler.scope;
    BlockContext block = methodDef.block();
    if (block != null) {
      int numDefinedAtStart = scope.nextIndex();
      PassOne.apply(scope, block);
      PassTwo.apply(scope, block, numDefinedAtStart, !blockCompiler.isProcedure());
      // We allow args to not be used, since names may make them more readable
      PassThree.apply(blockCompiler, block);
      if (ib.nextInstructionIsReachable()) {
        blockCompiler.setLineNumber(block.stop);
        if (blockCompiler.isProcedure()) {
          blockCompiler.compileReturn(null);
        } else {
          ib.emitError("Missing return statement");
        }
      }
    } else if (blockCompiler.isProcedure()) {
      throw Compiler.error(methodDef.start, "Procedure cannot return a result");
    } else {
      ExpressionContext expr = methodDef.expression();
      blockCompiler.setLineNumber(expr.start);
      PassTwo.apply(scope, expr, false);
      blockCompiler.setLineNumber(expr.start);
      Expr result = blockCompiler.compile(expr);
      blockCompiler.compileReturn(result);
    }
    ib.done();
    ib.addMethod(function, predicate, isDefault);
  }

  private static List<ParamContext> params(ParamListContext list) {
    return (list == null) ? ImmutableList.of() : list.param();
  }

  /// Process the parameters of a method declaration:
  ///
  /// - returns an array of parameter names (with nulls for unnamed parameters);
  /// - returns a Vm.MethodPredicate combining any parameter type constraints, or null if there are
  ///   none;
  /// - emits instructions to verify that any parameters declared as Integer are actually Integers
  ///   (the Vm.MethodPredicate will only require that they be Numbers);
  /// - emits instructions to destructure any parameters declared with array or structure
  ///   expressions; and
  /// - calls blockCompiler.setResults() to save expressions that will provide the final values for
  ///   all inout parameters.
  ///
  /// @param blockCompiler the BlockCompiler for the method body
  /// @param startToken the first token of the method declaration, used only for reporting errors
  /// @return a Pair of the parameter names and the MethodPredicate
  private Pair<String[], Vm.MethodPredicate> processParams(
      List<ParamContext> params,
      Vm.Function function,
      BlockCompiler blockCompiler,
      Token startToken) {
    // There is a lot going on here; we're effectively doing parts of PassOne (setting up the scope)
    // and PassThree (emitting instructions in some cases) before starting either of them.
    // It would probably be better to refactor this so that those pieces can be done at more natural
    // times.
    Vm.MethodPredicate predicate = null;
    String[] paramNames = new String[function.numArgs()];
    Expr[] results = new Expr[function.numResults()];
    // next location in paramNames
    int index = 0;
    // next location in results
    int resultIndex = 0;
    // InstructionBlock requires that all args must be created before emitting any instructions,
    // so we use this list to defer steps that should happen once all args have been created.
    List<Runnable> extracts = new ArrayList<>();
    blockCompiler.setLineNumber(startToken);
    for (ParamContext param : params) {
      LowerIdContext id = null;
      Vm.Type paramType = null;
      boolean needsExtract = false;
      boolean isInt = false;
      // Set id, paramType, needsExtract, and/or isInt depending on the form of the param.
      ExtractLhsContext extractLhs = param.extractLhs();
      if (extractLhs == null) {
        // If param is not an extractLhs, it's a typeName optionally followed by a lowerId
        paramType = getType(param.typeName());
        id = param.lowerId();
      } else if (extractLhs instanceof SimpleExtractContext) {
        // param is just a lowerId
        id = ((SimpleExtractContext) extractLhs).lowerId();
      } else if (extractLhs instanceof ArrayExtractContext) {
        paramType = vmCore.lookupType("Array");
        needsExtract = true;
      } else if (extractLhs instanceof StructExtractContext) {
        paramType = vmCore.lookupType("Struct");
        needsExtract = true;
      } else {
        assert extractLhs instanceof DiscardExtractContext;
      }
      String paramName = (id != null) ? id.getText() : null;
      ParamAnnotationContext annotation = param.paramAnnotation();
      if (isCollector(annotation)) {
        if (paramType != null) {
          throw Compiler.error(param.start, "Collector parameter cannot be typed");
        }
        // This is actually two parameters; the first (_ro) is handled here,
        // the second (_rw, inout) is handled below.
        checkInOutParameter(function, index, false, param);
        if (id != null) {
          String collectorName = Compiler.roName(paramName);
          paramNames[index] = collectorName;
          blockCompiler.scope.addArg(collectorName, id.start, false);
          paramName = Compiler.rwName(paramName);
        } else {
          // foo(_<<), i.e. an anonymous (and hence unused) collector parameter
          blockCompiler.ib.addArg(null);
        }
        index++;
      } else if (paramType != null) {
        if (paramType == vmInteger) {
          isInt = true;
          paramType = vmCore.lookupType("Number");
        }
        Vm.MethodPredicate conjunct = paramType.argType(index, true);
        predicate = (predicate == null) ? conjunct : predicate.and(conjunct);
      }
      boolean isInOut = annotation != null;
      checkInOutParameter(function, index, isInOut, param);
      Local local;
      if (id != null) {
        paramNames[index] = paramName;
        local = blockCompiler.scope.addArg(paramName, id.start, isInOut).local;
      } else if (isInOut || needsExtract || isInt) {
        local = blockCompiler.ib.addArg(blockCompiler.newAnonymousArgName());
        if (needsExtract) {
          int ri = resultIndex;
          extracts.add(
              () -> {
                Expr result =
                    blockCompiler.scope.extractCompoundParameter(extractLhs, local, isInOut);
                if (isInOut) {
                  results[ri] = result;
                }
              });
        }
      } else {
        local = blockCompiler.ib.addArg(null);
      }
      if (isInt) {
        extracts.add(
            () -> blockCompiler.ib.emitCall(local, vmCore.lookupFunction("asInt", 1), local));
      }
      if (isInOut) {
        // If needsExtract is true, we'll actually overwrite this value later (with an expression
        // that reassembles the extracted vars), but we still need to increment resultIndex to
        // reserve the spot.
        results[resultIndex++] = local;
      }
      index++;
    }
    // We already counted the args before looking up the function, so this has to match.
    assert index == function.numArgs();
    extracts.forEach(Runnable::run);
    // At this point resultIndex is the number of inout args.
    int numReturned = results.length - resultIndex;
    if (numReturned > 1) {
      // The Vm.Function API allows functions that return multiple values without using inout
      // args, but you can't define methods for them in Retrospect.
      throw Compiler.error(
          startToken, "Cannot define a method for function with %s results", numReturned);
    }
    if (numReturned == 1 && results.length > 1) {
      // Move the inout args down, since the explicit return value is expected first.
      System.arraycopy(results, 0, results, 1, results.length - 1);
      results[0] = null;
    }
    blockCompiler.setResults(results);
    return new Pair<>(paramNames, predicate);
  }

  /// Returns true if the given annotation is "<<".
  static boolean isCollector(ParamAnnotationContext annotation) {
    return annotation != null && annotation.start.getType() == TokenType.DOUBLE_LESS_THAN;
  }

  /// Checks whether the specified parameter of a function is inout, and throws an appropriate
  /// CompileError if it is not as expected.
  static void checkInOutParameter(
      Vm.Function fn, int paramIndex, boolean isInOut, ParserRuleContext ctx) {
    if (fn.argIsInout(paramIndex) != isInOut) {
      throw Compiler.error(
          ctx.start, "Parameter %s should%s be inOut", paramIndex, isInOut ? " not" : "");
    }
  }

  /// Returns the Vm.Type identified by the given name, or throws a CompileError if there is none.
  /// Reads but does not modify the types map.
  Vm.Type getType(TypeNameContext typeName) {
    if (typeName.localName != null) {
      throw new UnsupportedOperationException("finish package support!");
    }
    String name = typeName.start.getText();
    TypeDecl decl = types.get(name);
    if (decl != null) {
      if (decl.type == null) {
        throw Compiler.error(typeName.start, "Must declare '%s' before use", name);
      }
      return decl.type;
    }
    Vm.Type result = vmCore.lookupType(name);
    if (result == null) {
      throw Compiler.error(typeName.start, "Unknown type '%s'", name);
    }
    return result;
  }

  /// Returns the Vm.Singleton identified by the given name, or throws a CompileError if the name is
  /// unknown or not a singleton. Reads but does not modify the types map.
  Vm.Singleton getSingleton(TypeNameContext typeName) {
    if (typeName.localName != null) {
      throw new UnsupportedOperationException("finish package support!");
    }
    String name = typeName.start.getText();
    TypeDecl decl = types.get(name);
    if (decl == null) {
      Vm.Singleton result = vmCore.lookupSingleton(name);
      if (result != null) {
        return result;
      }
      throw Compiler.error(typeName.start, "Unknown singleton '%s'", name);
    } else if (decl instanceof LeafTypeDecl) {
      Vm.Singleton result = ((LeafTypeDecl) decl).singleton;
      if (result != null) {
        return result;
      }
    }
    throw Compiler.error(typeName.start, "'%s' is not a singleton", name);
  }

  /// Returns the Vm.Compound identified by the given name, or throws a CompileError if there is
  /// none. Reads but does not modify the types map.
  Vm.Compound getOwnCompound(TypeNameContext typeName) {
    if (typeName.localName == null) {
      String name = typeName.start.getText();
      TypeDecl decl = types.get(name);
      if (decl instanceof LeafTypeDecl) {
        Vm.Compound result = ((LeafTypeDecl) decl).compound;
        if (result != null) {
          return result;
        }
      }
    }
    throw Compiler.error(
        typeName.start, "'%s' is not a compound type declared in this module", typeName.getText());
  }

  /// Returns the Vm.Function identified by the given name and numArgs, or throws a CompileError if
  /// there is none. Reads but does not modify the functions map.
  Vm.Function getFunction(FunctionNameContext fnName, int numArgs) {
    if (fnName.module != null) {
      throw new UnsupportedOperationException("finish package support!");
    }
    String name = fnName.lowerId().getText();
    FunctionDecl decl = functions.get(name + ":" + numArgs);
    if (decl != null) {
      assert decl.function != null;
      return decl.function;
    }
    Vm.Function result = vmCore.lookupFunction(name, numArgs);
    if (result == null) {
      throw Compiler.error(fnName.start, "Unknown function '%s:%s'", name, numArgs);
    }
    return result;
  }

  /// If the given declaration begins with "`private`" or "`open`", returns the corresponding
  /// Vm.Access; otherwise returns VISIBLE.
  private static Vm.Access access(ParserRuleContext decl) {
    int token = decl.start.getType();
    if (token == TokenType.KEYWORD_PRIVATE) {
      return Vm.Access.PRIVATE;
    } else if (token == TokenType.KEYWORD_OPEN) {
      return Vm.Access.OPEN;
    } else {
      return Vm.Access.VISIBLE;
    }
  }

  /// A FunctionDecl is added to the [#functions] table for each function declaration in the program
  /// being compiled.
  private static class FunctionDecl {
    /// The parsed function declaration that this object was created from.
    final FunctionDeclContext parsed;

    /// The function name.
    final String name;

    /// The number of arguments to this function.
    final int numArgs;

    /// The indices of any inout arguments.
    final Bits inOutArgs;

    /// If non-null, the method definition that was included in the function definition.
    final MethodDefContext method;

    /// The corresponding Vm.Function object.
    final Vm.Function function;

    FunctionDecl(FunctionDeclContext parsed, Symbols symbols) {
      this.parsed = parsed;
      this.name = parsed.name.getText();
      this.method = parsed.methodDef();
      int numArgs = 0;
      Bits.Builder inOutArgs = new Bits.Builder();
      for (ParamContext param : params(parsed.paramList())) {
        ParamAnnotationContext annotation = param.paramAnnotation();
        if (annotation != null) {
          // Arguments with a collector (<<) annotation correspond to two Vm.Function arguments
          // and the second of them is inout.
          if (isCollector(annotation)) {
            numArgs++;
          }
          inOutArgs.set(numArgs);
        }
        numArgs++;
        if (param.typeName() != null && method == null) {
          throw Compiler.error(
              param.start, "Function declaration should not include parameter type");
        }
      }
      this.numArgs = numArgs;
      this.inOutArgs = inOutArgs.build();
      int keyword = parsed.kind.getType();
      boolean hasResult = (keyword == TokenType.KEYWORD_FUNCTION);
      assert hasResult || (keyword == TokenType.KEYWORD_PROCEDURE);
      this.function =
          symbols.module.newFunction(name, numArgs, this.inOutArgs, hasResult, access(parsed));
    }

    /// If this function declaration included a method declaration, creates a subtask to compile the
    /// method.
    void compileMethod(Symbols symbols) {
      if (method != null) {
        symbols.addSubTask(
            () ->
                symbols.compileMethodDecl(
                    function, params(parsed.paramList()), null, false, method));
      }
    }
  }

  /// A TypeDecl is added to the [#types] table for each type declaration in the program being
  /// compiled. "singleton" and "compound" declarations create a LeafTypeDecl, while "type"
  /// declarations create a UnionTypeDecl.
  private abstract static class TypeDecl {
    final String name;

    // The Vm.Type will be filled in by the create() method once all the type declarations have been
    // seen.
    Vm.Type type;

    TypeDecl(String name) {
      this.name = name;
    }

    /// Creates the Vm.Type object corresponding to this declaration.
    abstract void create(Symbols symbols);

    private static final Vm.Type[] NO_TYPES = new Vm.Type[0];

    /// Converts the given superclass or subclass list to an array of Vm.Type.
    static Vm.Type[] types(TypeListContext typeList, Symbols symbols) {
      if (typeList == null) {
        return NO_TYPES;
      }
      return typeList.typeName().stream().map(symbols::getType).toArray(Vm.Type[]::new);
    }
  }

  private static class LeafTypeDecl extends TypeDecl {
    final LeafTypeDeclContext parsed;

    // Set by create() if this is a singleton declaration.
    Vm.Singleton singleton;

    // Set by create() if this is a compound declaration.
    Vm.Compound compound;

    LeafTypeDecl(LeafTypeDeclContext parsed) {
      super(parsed.UPPER_ID().getText());
      this.parsed = parsed;
    }

    @Override
    void create(Symbols symbols) {
      Vm.Type[] types = types(parsed.typeList(), symbols);
      Vm.Access access = access(parsed);
      int keyword = parsed.kind.getType();
      if (keyword == TokenType.KEYWORD_SINGLETON) {
        singleton = symbols.module.newSingleton(name, access, types);
        type = singleton.asType();
      } else {
        assert keyword == TokenType.KEYWORD_COMPOUND;
        compound = symbols.module.newCompoundType(name, null, access, types);
        type = compound.asType();
      }
    }
  }

  private static class UnionTypeDecl extends TypeDecl {
    final UnionTypeDeclContext parsed;

    UnionTypeDecl(UnionTypeDeclContext parsed) {
      super(parsed.UPPER_ID().getText());
      this.parsed = parsed;
    }

    @Override
    void create(Symbols symbols) {
      Vm.Type[] types = types(parsed.typeList(), symbols);
      Vm.Access access = access(parsed);
      if (types.length == 0 || parsed.marker.getType() == TokenType.KEYWORD_IS) {
        type = symbols.module.newUnionType(name, access, types);
      } else {
        type = symbols.module.newTypeFromUnion(name, access, types);
      }
    }
  }
}
