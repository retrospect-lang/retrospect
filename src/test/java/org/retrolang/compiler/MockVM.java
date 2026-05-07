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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.FormatMethod;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.retrolang.Vm;
import org.retrolang.util.Bits;
import org.retrolang.util.Pair;

/**
 * MockVM implements the Retrospect VM interface but isn't able to execute any instructions; instead
 * it simply provides a mostly-readable summary of the VM methods that were called by the compiler.
 *
 * <p>To use: create an instance of MockVM, create a ModuleBuilder with newModule(), call the
 * various new*() methods on the ModuleBuilder, and then call toString() on it. The result will
 * encode all the method calls that were made on that ModuleBuilder, with some canonicalization to
 * simplify tests (e.g. declarations are sorted).
 */
class MockVM implements Vm.VirtualMachine {

  private static final ImmutableMap<String, Singleton> SINGLETONS;

  static {
    ImmutableMap.Builder<String, Singleton> builder = ImmutableMap.builder();
    String[] names =
        new String[] {
          "Absent",
          "EnumerateAllKeys",
          "EnumerateValues",
          "EnumerateWithKeys",
          "False",
          "None",
          "True",
        };
    for (String name : names) {
      builder.put(name, new Singleton(name));
    }
    SINGLETONS = builder.buildOrThrow();
  }

  private static final ImmutableMap<String, Type> TYPES;
  private static final Type INT_TYPE;

  static {
    ImmutableMap.Builder<String, Type> builder = ImmutableMap.builder();
    String[] names =
        new String[] {
          "Array",
          "Integer",
          "Lambda",
          "Loop",
          "LoopExit",
          "Matrix",
          "Number",
          "Range",
          "String",
          "Struct",
          "Boolean",
        };
    for (String name : names) {
      builder.put(name, new Type(name));
    }
    TYPES = builder.buildOrThrow();
    INT_TYPE = TYPES.get("Integer");
  }

  /** Maps strings like "add:2" and "sum:0" to the appropriate Core function. */
  private static final ImmutableMap<String, Function> FUNCTIONS;

  static {
    ImmutableMap.Builder<String, Function> builder = ImmutableMap.builder();
    for (String binOpFn : Symbols.BIN_OP_FUNCTION_NAMES.values()) {
      addFunctions(builder, 2, 0, 1, binOpFn);
    }
    // function f()
    addFunctions(
        builder,
        0,
        0,
        1,
        "allFalse",
        "allTrue",
        "anyFalse",
        "anyTrue",
        "max",
        "min",
        "random",
        "save",
        "saveSequential",
        "saveUnordered",
        "sum");
    // procedure f(x)
    addFunctions(builder, 1, 0, 0, "verifyEV");
    // function f(x)
    addFunctions(
        builder,
        1,
        0,
        1,
        "abs",
        "asInt",
        "bitCount",
        "bitFirstOne",
        "bitFirstZero",
        "bitInvert",
        "bitLastOne",
        "bitLastZero",
        "filter",
        "emptyState",
        "loopHelper",
        "emptyStateHelper",
        "loopExit",
        "loopExitState",
        "matrix",
        "max",
        "maxAt",
        "min",
        "minAt",
        "negative",
        "not",
        "number",
        "randomInt",
        "range",
        "reverse",
        "size",
        "sizes",
        "sqrt",
        "sum",
        "u32",
        "withKeys");
    // function f(x, y)
    addFunctions(
        builder,
        2,
        0,
        1,
        "bitAnd",
        "bitAndNot",
        "bitOr",
        "bitRotate",
        "bitShift",
        "div",
        "anotherRw",
        "finalStateHelper",
        "at",
        "reversedAt",
        "iterateUnbounded",
        "join",
        "matrix",
        "new",
        "newMatrix",
        "uAdd",
        "uDivWithRemainder",
        "uMultiply",
        "uSubtract");
    // function f(x, y, z)
    addFunctions(
        builder,
        3,
        0,
        1,
        "nextState",
        "combineStates",
        "finalStateHelper",
        "combineStatesHelper",
        "replaceElement");
    // function f(x, y, z, w)
    addFunctions(builder, 4, 0, 1, "iterate", "enumerate");
    // procedure f(x=, y)
    addFunctions(builder, 2, 1, 1, "concatUpdate");
    // function f(x=, y)
    addFunctions(builder, 2, 1, 2, "startUpdate");
    // function f(x, y=)
    addFunctions(builder, 2, 2, 2, "splitState", "splitStateHelper");
    // procedure f(x=, y, z)
    addFunctions(builder, 3, 1, 1, "binaryUpdate");
    // function f(x, y=, z)
    addFunctions(builder, 3, 2, 2, "emitValue", "emitAll", "emitKey");
    // function f(x, y, z=, w)
    addFunctions(builder, 4, 4, 2, "loopHelper");

    FUNCTIONS = builder.buildOrThrow();
  }

  private static void addFunctions(
      ImmutableMap.Builder<String, Function> builder,
      int numArgs,
      int inOutArgs,
      int numResults,
      String... names) {
    for (String name : names) {
      builder.put(name + ":" + numArgs, new Function(name, numArgs, inOutArgs, numResults));
    }
  }

  static final Vm.Module CORE =
      new Vm.Module() {
        @Override
        public Singleton lookupSingleton(String name) {
          return SINGLETONS.get(name);
        }

        @Override
        public Type lookupType(String name) {
          Type result = TYPES.get(name);
          if (result == null) {
            Singleton singleton = lookupSingleton(name);
            if (singleton != null) {
              return singleton.type;
            }
          }
          return result;
        }

        @Override
        public Vm.Function lookupFunction(String name, int numArgs) {
          return FUNCTIONS.get(name + ":" + numArgs);
        }
      };

  @Override
  public Vm.Module core() {
    return CORE;
  }

  @Override
  public Vm.Compound arrayOfSize(int size) {
    return new Vm.Compound() {
      @Override
      public Vm.Type asType() {
        throw new AssertionError();
      }

      @Override
      public Vm.Function extract() {
        return new Function("unarray" + size, 1, 0, size);
      }

      @Override
      public Vm.Expr make(Vm.Expr... elements) {
        return Expr.forCompound(Arrays.toString(elements), elements);
      }

      @Override
      public Vm.Value make(Vm.ResourceTracker tracker, Vm.Value... elements) {
        throw new AssertionError();
      }

      @Override
      public Vm.Expr asLambdaExpr() {
        return new Value("lambda_toArray" + size);
      }
    };
  }

  @Override
  public Vm.Compound structWithKeys(String... keys) {
    String[] cleanKeys = Arrays.stream(keys).map(MockVM::toStructKey).toArray(String[]::new);
    String name = "struct{" + String.join(",", cleanKeys) + "}";
    return new Vm.Compound() {
      @Override
      public Vm.Type asType() {
        throw new AssertionError();
      }

      @Override
      public Vm.Function extract() {
        return new Function("un" + name, 1, 0, cleanKeys.length);
      }

      @Override
      public Vm.Expr make(Vm.Expr... elements) {
        String result =
            IntStream.range(0, elements.length)
                .mapToObj(i -> String.format("%s: %s", cleanKeys[i], elements[i]))
                .collect(Collectors.joining(", ", "{", "}"));
        return Expr.forCompound(result, elements);
      }

      @Override
      public Vm.Value make(Vm.ResourceTracker tracker, Vm.Value... elements) {
        throw new AssertionError();
      }

      @Override
      public Vm.Expr asLambdaExpr() {
        return new Value("lambda_" + name);
      }
    };
  }

  @Override
  public ModuleBuilder newModule(String name) {
    return new ModuleBuilder(name);
  }

  @Override
  public Expr asExpr(String s) {
    return new Value(quote(s));
  }

  @Override
  public Expr asExpr(int i) {
    return new Value(String.valueOf(i));
  }

  @Override
  public Expr asExpr(double d) {
    return new Value(String.valueOf(d));
  }

  @Override
  public Vm.ResourceTracker newResourceTracker(long limit, int maxTraces, boolean debug) {
    throw new AssertionError();
  }

  /** A trivial base class that defines toString() to return the given string. */
  abstract static class Named {
    final String name;

    Named(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  static class Expr extends Named implements Vm.Expr {
    Expr(String name) {
      super(name);
    }

    static Expr forCompound(String name, Vm.Expr[] elements) {
      return Arrays.stream(elements).allMatch(e -> e instanceof Vm.Value)
          ? new Value(name)
          : new Expr(name);
    }
  }

  private static String accessLabel(Vm.Access access) {
    return switch (access) {
      case PRIVATE -> "private ";
      case VISIBLE -> "";
      case OPEN -> "open ";
    };
  }

  static class ModuleBuilder extends Named implements Vm.ModuleBuilder {
    /**
     * Each call to newSingleton, newCompoundType, newUnionType, newTypeFromUnion, newFunction, or
     * InstructionBlock.addMethod adds a pair to this list; the first element of the pair is a sort
     * key, and the second is a textish description of the declaration. Sort keys are chosen so that
     * they sort in the above order (singletons, then compounds, then both types of unions, then
     * functions, then methods), and within each kind are sorted by name.
     */
    private List<Pair<String, String>> results = new ArrayList<>();

    /** Calling build() formats the results into a single string. */
    private String asString;

    final Function unCompound;
    final Function updateCompound;

    ModuleBuilder(String name) {
      super(name);
      unCompound = new Function("_get", 1, 0, 1);
      updateCompound = new Function("_update", 2, 0, 1);
    }

    /**
     * Adds a declaration to our output, with the given sort key.
     *
     * <p>Sort keys are currently
     *
     * <ul>
     *   <li>"a1" + name: singletons
     *   <li>"a2" + name: compounds
     *   <li>"a3" + name: unions
     *   <li>"b" + name: functions
     *   <li>"method" + declaration: methods
     * </ul>
     */
    private void addResult(String key, String decl) {
      synchronized (this) {
        results.add(new Pair<>(key, decl));
      }
    }

    @Override
    public Singleton newSingleton(String name, Vm.Access access, Vm.Type... supers) {
      String decl = accessLabel(access) + "singleton " + name;
      if (supers.length != 0) {
        decl = decl + " is " + join(supers);
      }
      addResult("a1" + name, decl);
      return new Singleton(name);
    }

    @Override
    public Compound newCompoundType(
        String name, String[] elementNames, Vm.Access access, Vm.Type... supers) {
      String decl = accessLabel(access) + "compound " + name;
      if (elementNames != null) {
        decl = decl + "{" + String.join(",", elementNames) + "}";
      }
      if (supers.length != 0) {
        decl = decl + " is " + join(supers);
      }
      addResult("a2" + name, decl);
      return new Compound(name, elementNames);
    }

    @Override
    public Type newUnionType(String name, Vm.Access access, Vm.Type... supers) {
      String decl = accessLabel(access) + "type " + name;
      if (supers.length != 0) {
        decl = decl + " is " + join(supers);
      }
      addResult("a3" + name, decl);
      return new Type(name);
    }

    @Override
    public Vm.Type newTypeFromUnion(String name, Vm.Access access, Vm.Type... subtypes) {
      assertThat(subtypes).asList().doesNotContain(INT_TYPE);
      String decl = accessLabel(access) + "type " + name;
      if (subtypes.length != 0) {
        decl = decl + " contains " + join(subtypes);
      }
      addResult("a3" + name, decl);
      return new Type(name);
    }

    @Override
    public Vm.Function newFunction(
        String name, int numArgs, IntPredicate argIsInOut, boolean hasResult, Vm.Access access) {
      Bits inoutBits = Bits.fromPredicate(numArgs, argIsInOut);
      String decl = accessLabel(access) + (hasResult ? "function " : "procedure ") + name;
      decl +=
          IntStream.range(0, numArgs)
              .mapToObj(i -> "a" + i + (inoutBits.test(i) ? "=" : ""))
              .collect(Collectors.joining(", ", "(", ")"));
      addResult("b" + name + ":" + numArgs, decl);
      return new Function(name, numArgs, inoutBits, inoutBits.count() + (hasResult ? 1 : 0));
    }

    @Override
    public InstructionBlock newInstructionBlock(int numArgs, int numResults, Object source) {
      return new InstructionBlock(this, numArgs, numResults);
    }

    @Override
    public Function unCompound() {
      return unCompound;
    }

    @Override
    public Vm.Function updateCompound() {
      return updateCompound;
    }

    @Override
    public synchronized void build() {
      results.sort(Comparator.comparing(p -> p.x));
      asString = results.stream().map(p -> p.y).collect(Collectors.joining("\n"));
      results = null;
    }

    @Override
    public String toString() {
      return (asString != null) ? asString : "incomplete " + super.toString();
    }
  }

  static class Function extends Named implements Vm.Function {
    final int numArgs;
    final IntPredicate argIsInOut;
    final int numResults;
    final Value asLambdaExpr;

    /**
     * Creates a Function.
     *
     * @param inOutArgs identifies which args are inOut using a bitmask, i.e. the k-th argument is
     *     inOut if the k-th bit of inOutArgs is 1.
     */
    Function(String name, int numArgs, int inOutArgs, int numResults) {
      this(name, numArgs, Bits.fromInt(inOutArgs), numResults);
    }

    Function(String name, int numArgs, Bits argIsInOut, int numResults) {
      super(name);
      this.numArgs = numArgs;
      this.argIsInOut = argIsInOut;
      this.numResults = numResults;
      this.asLambdaExpr =
          (numResults == 1 && argIsInOut.isEmpty())
              ? new Value(String.format("'%s:%s'", name, numArgs))
              : null;
    }

    @Override
    public int numArgs() {
      return numArgs;
    }

    @Override
    public boolean argIsInout(int i) {
      return argIsInOut.test(i);
    }

    @Override
    public int numResults() {
      return numResults;
    }

    @Override
    public Vm.Expr asLambdaExpr() {
      assertThat(asLambdaExpr).isNotNull();
      return asLambdaExpr;
    }
  }

  static class MethodPredicate extends Named implements Vm.MethodPredicate {
    @FormatMethod
    MethodPredicate(String fmt, Object... args) {
      super(String.format(fmt, args));
    }

    @Override
    public MethodPredicate or(Vm.MethodPredicate other) {
      return new MethodPredicate("(%s) or (%s)", this, other);
    }

    @Override
    public MethodPredicate and(Vm.MethodPredicate other) {
      return new MethodPredicate("(%s) and (%s)", this, other);
    }
  }

  static class Type extends Named implements Vm.Type {
    Type(String name) {
      super(name);
    }

    @Override
    public MethodPredicate argType(int argIndex, boolean positive) {
      assertThat(this).isNotSameInstanceAs(INT_TYPE);
      return new MethodPredicate("_%s %s %s", argIndex, positive ? "is" : "is not", this);
    }

    @Override
    public Vm.Expr testLambda() {
      return new Value("lambda_is" + name);
    }
  }

  /** The only Values we see at compile time are constant expressions. */
  static class Value extends Expr implements Vm.Value {
    Value(String name) {
      super(name);
    }

    @Override
    public void close() {}

    @Override
    public Vm.Value keep() {
      throw new AssertionError();
    }
  }

  static class Singleton extends Value implements Vm.Singleton {
    final Type type;

    Singleton(String name) {
      super(name);
      this.type = new Type(name);
    }

    @Override
    public Type asType() {
      return type;
    }

    @Override
    public Vm.Value asValue() {
      return this;
    }
  }

  static class Compound extends Named implements Vm.Compound {
    final String[] elementNames;
    final Type asType;

    Compound(String name, String[] elementNames) {
      super(name);
      this.elementNames = elementNames;
      this.asType = new Type(name);
    }

    @Override
    public Vm.Type asType() {
      return asType;
    }

    @Override
    public Vm.Function extract() {
      return new Function("un" + name, 1, 0, elementNames == null ? 1 : elementNames.length);
    }

    @Override
    public Vm.Expr make(Vm.Expr... elements) {
      String result;
      if (elementNames == null) {
        assertThat(elements).hasLength(1);
        result = name + "(" + elements[0] + ")";
      } else {
        result =
            IntStream.range(0, elements.length)
                .mapToObj(
                    i -> {
                      String element = elements[i].toString();
                      return element.equals(elementNames[i])
                          ? element
                          : elementNames[i] + ": " + element;
                    })
                .collect(Collectors.joining(", ", name + "(", ")"));
      }
      return Expr.forCompound(result, elements);
    }

    @Override
    public Vm.Value make(Vm.ResourceTracker tracker, Vm.Value... elements) {
      throw new AssertionError();
    }

    @Override
    public Vm.Expr asLambdaExpr() {
      throw new AssertionError();
    }
  }

  static class Local extends Expr implements Vm.Local {
    Local(String name) {
      super(name);
    }
  }

  static class BranchTarget extends Named implements Vm.BranchTarget {
    boolean wasUsed;

    BranchTarget(String name) {
      super(name);
    }
  }

  /**
   * InstructionBlock constructs a toString() result that just records the emit*() methods that were
   * called on it.
   *
   * <p>To better match the behavior of the real VM it does a couple of trivial optimizations:
   *
   * <ul>
   *   <li>Targets that are never branched to are dropped.
   *   <li>Instructions that are emitted when nextInstructionIsReachable() is false are dropped.
   * </ul>
   */
  static class InstructionBlock implements Vm.InstructionBlock {
    final ModuleBuilder module;
    final int numArgs;
    final int numResults;
    final List<String> args = new ArrayList<>();

    /** A list of one-line strings, each ending with "\n"; toString() just appends these. */
    final List<String> instructions = new ArrayList<>();

    /** The number of calls to newTarget(), for creating distinct labels. */
    private int numTargets;

    private boolean nextInstructionIsReachable = true;

    private boolean done;

    /**
     * Usually "\n", but set by setLineNumber() to a string that should be appended to the next line
     * we emit.
     */
    private String suffix = "\n";

    InstructionBlock(ModuleBuilder module, int numArgs, int numResults) {
      this.module = module;
      this.numArgs = numArgs;
      this.numResults = numResults;
    }

    @Override
    public Local addArg(String name) {
      assertThat(done).isFalse();
      // Should be called before any instructions are emitted.
      assertThat(instructions).isEmpty();
      args.add((name == null) ? "_" : name);
      return (name == null) ? null : new Local(name);
    }

    @Override
    public Local newLocal(String name) {
      assertThat(done).isFalse();
      return new Local(name);
    }

    @Override
    public BranchTarget newTarget() {
      assertThat(done).isFalse();
      return new BranchTarget("L" + ++numTargets);
    }

    @Override
    public boolean nextInstructionIsReachable() {
      return nextInstructionIsReachable;
    }

    /** Appends one line to our output. */
    @FormatMethod
    private void addInst(String fmt, Object... args) {
      assertThat(done).isFalse();
      if (nextInstructionIsReachable) {
        String inst = String.format(fmt, args);
        // The lines emitted by defineTarget() are indented less than everything else.
        String prefix = (inst.endsWith(":") ? "  " : "    ");
        instructions.add(prefix + inst + suffix);
        suffix = "\n";
      }
    }

    /**
     * Calls toString() on each element of objs, and joins them with ", ". Null elements are
     * displayed as "_".
     */
    private static String withCommas(Object[] objs) {
      return Arrays.stream(objs)
          .map(x -> (x == null) ? "_" : x.toString())
          .collect(Collectors.joining(", "));
    }

    @Override
    public void setLineNumber(int lineNum, int charPositionInLine) {
      suffix = String.format("  // %s:%s\n", lineNum, charPositionInLine);
    }

    @Override
    public void defineTarget(Vm.BranchTarget target) {
      assertThat(done).isFalse();
      if (((BranchTarget) target).wasUsed) {
        nextInstructionIsReachable = true;
        addInst("%s:", target);
      }
    }

    @Override
    public void emitSet(Vm.Local output, Vm.Expr input) {
      addInst("%s = %s", output, input);
    }

    @Override
    public void emitCall(Vm.Local[] outputs, Vm.Function fn, Vm.Expr... inputs) {
      assertThat(fn).isNotNull();
      assertThat(inputs).asList().doesNotContain(null);
      String assigns = (outputs.length != 0) ? withCommas(outputs) + " = " : "";
      addInst("%s%s(%s)", assigns, fn, withCommas(inputs));
    }

    @Override
    public void emitReturn(Vm.Expr... input) {
      addInst("return %s", withCommas(input));
      nextInstructionIsReachable = false;
    }

    @Override
    public void emitError(String cause, Vm.Local... include) {
      addInst("error %s %s", quote(cause), withCommas(include));
      nextInstructionIsReachable = false;
    }

    @Override
    public void emitTrace(String message, Vm.Local... include) {
      addInst("trace %s %s", quote(message), withCommas(include));
    }

    @Override
    public void emitBranch(Vm.BranchTarget target) {
      addInst("branch %s", target);
      ((BranchTarget) target).wasUsed |= nextInstructionIsReachable;
      nextInstructionIsReachable = false;
    }

    @Override
    public void emitConditionalBranch(
        Vm.BranchTarget target, Vm.Local input, boolean branchIfTrue) {
      addInst("branch %s if %s%s", target, branchIfTrue ? "" : "not ", input);
      ((BranchTarget) target).wasUsed |= nextInstructionIsReachable;
    }

    @Override
    public void emitTypeCheckBranch(
        Vm.BranchTarget target, Vm.Local input, Vm.Type type, boolean branchIfContains) {
      addInst("branch %s if %s is %s%s", target, input, branchIfContains ? "" : "not ", type);
      ((BranchTarget) target).wasUsed |= nextInstructionIsReachable;
    }

    @Override
    public void done() {
      assertThat(done).isFalse();
      assertThat(nextInstructionIsReachable).isFalse();
      done = true;
    }

    @Override
    public void addMethod(Vm.Function fn, Vm.MethodPredicate predicate, boolean isDefault) {
      assertThat(done).isTrue();
      assertThat(fn.numArgs()).isEqualTo(numArgs);
      assertThat(fn.numResults()).isEqualTo(numResults);
      String decl =
          String.format(
              "method %s(%s) %s%s{\n%s}",
              fn,
              String.join(", ", args),
              (predicate == null) ? "" : predicate + " ",
              isDefault ? "default " : "",
              this);
      // decl sorts after type & function keys (since it starts with an "m")
      module.addResult(decl, decl);
    }

    @Override
    public Vm.Value applyToArgs(Vm.ResourceTracker tracker, Vm.Value... args) {
      throw new AssertionError();
    }

    @Override
    public String toString() {
      return String.join("", instructions);
    }
  }

  private static String join(Object[] array) {
    return Arrays.stream(array).map(Object::toString).collect(Collectors.joining(", "));
  }

  private static String quote(String s) {
    if (s == null) {
      return "null";
    }
    s =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\b", "\\b")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\f", "\\f")
            .replace("\r", "\\r");
    return "\"" + s + "\"";
  }

  private static final Pattern LOWER_ID = Pattern.compile("[a-z](_*[a-zA-Z0-9])*");

  private static String toStructKey(String key) {
    return LOWER_ID.matcher(key).matches() ? key : quote(key);
  }
}
