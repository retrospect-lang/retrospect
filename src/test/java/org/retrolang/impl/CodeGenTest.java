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

package org.retrolang.impl;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.CharStreams;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.retrolang.Vm;
import org.retrolang.compiler.Compiler;
import org.retrolang.testing.TestMonitor;
import org.retrolang.testing.TestdataScanner;
import org.retrolang.testing.TestdataScanner.TestProgram;

/** Generates bytecodes for each of the .r8t files in the testdata/codegen directory. */
@RunWith(TestParameterInjector.class)
public class CodeGenTest {

  private static final Path TESTDATA = Path.of("src/test/java/org/retrolang/impl/testdata/codegen");

  /**
   * Each .r8t file is expected to have source code followed by a comment that begins "{@code /*
   * CODEGEN } fnName ...", where fnName is the name of a function defined in the source code.
   * fnName is followed an optional "{@code ESCAPE_COUNT=}n", and then by RETURNS or ERRORS (e.g.
   * {@code CODEGEN foo ESCAPE_COUNT=2 RETURNS}).
   *
   * <p>The source code will be executed twice, the first time with the {@code prep} argument set to
   * {@code True}, and the second time with it set to {@code False}. In between the two executions
   * it will force code generation for the method(s) of any function named fnName, so that the
   * second execution will use the generated code.
   *
   * <p>If ESCAPE_COUNT is not specified or is 0, the second execution is expected to complete
   * without any escapes from the generated code; otherwise the generated is expected to escape that
   * many times.
   *
   * <p>The rest of the CODEGEN comment is 3 or 4 parts separated by lines containing just "---".
   *
   * <p>The first part depends on whether the first line said RETURNS or ERRORS:
   *
   * <ul>
   *   <li>RETURNS: The second execution is expected to succeed, and this part contains a printed
   *       representation of the expected return value.
   *   <li>ERRORS: The second execution is expected to fail, and this part contains a printed
   *       representation of the expected RuntimeError.
   * </ul>
   *
   * <p>The second part is the blocks that are expected to be generated for the method(s) of the
   * named function (and any other exlined methods they call).
   *
   * <p>If the program contains any trace instructions, there should be four parts and the third
   * part contains the expected output from the traces; otherwise there should be three parts.
   *
   * <p>The final part of the comment has expected memory statistics for the second execution.
   */
  private static final Pattern COMMENT_PATTERN =
      Pattern.compile("\n/\\* CODEGEN (.*?)\\*/\\n*", Pattern.DOTALL);

  public static final class AllPrograms extends TestdataScanner {
    public AllPrograms() {
      super(TESTDATA, COMMENT_PATTERN);
    }
  }

  /**
   * A pattern to parse the first line of a CODEGEN comment (beginning immediately after the "/*
   * CODEGEN ").
   */
  private static final Pattern FIRST_LINE_PATTERN =
      Pattern.compile(
          " *(?<fn>\\w+) +(ESCAPE_COUNT *= *(?<escapes>\\d+) *)?(?<returns>RETURNS|ERRORS)\n");

  private static final int MEMORY_LIMIT = 2_000_000;

  private final VirtualMachine vm = new VirtualMachine();
  private final Vm.Value falseValue = lookupCoreSingleton("False");
  private final Vm.Value trueValue = lookupCoreSingleton("True");

  private ResourceTracker tracker;

  private ResourceTracker newTracker() {
    tracker = new ResourceTracker(vm.scope, MEMORY_LIMIT, false);
    return tracker;
  }

  private Vm.Value lookupCoreSingleton(String name) {
    return vm.core().lookupSingleton(name).asValue();
  }

  @Test
  public void codeGenTest(
      @TestParameter(valuesProvider = AllPrograms.class) TestProgram testProgram) {
    // Parse the CODEGEN comment.
    checkNotNull(testProgram.comment(), "No CODEGEN comment found");
    Matcher argsMatcher = FIRST_LINE_PATTERN.matcher(testProgram.comment());
    assertWithMessage("Bad CODEGEN comment").that(argsMatcher.lookingAt()).isTrue();
    String fnName = argsMatcher.group("fn");
    int escapeCount =
        Optional.ofNullable(argsMatcher.group("escapes")).map(Integer::parseInt).orElse(0);
    boolean expectReturn = argsMatcher.group("returns").equals("RETURNS");
    String[] parts = testProgram.comment().substring(argsMatcher.end()).split("\n---\n");
    String expectedTraces = "";
    if (parts.length == 4) {
      expectedTraces = parts[2];
    } else {
      assertThat(parts).hasLength(3);
    }
    String expectedResult = parts[0];
    String expectedBlocks = parts[1];
    String expectedResources = parts[parts.length - 1].trim();

    // Configure it to always exline the specified function.
    vm.enableDebugging();
    ModuleBuilder module = (ModuleBuilder) vm.newModule("(input)");
    vm.scope.setForceExlined(method -> isExlined(method, fnName, module));

    TState tstate = TState.resetAndGet();
    tstate.bindTo(newTracker());

    // Compile the given code into our VM with a "prep" parameter, and create a MethodMemo for
    // executing it.
    String name = testProgram.name();
    String[] argNames = new String[] {"prep"};
    InstructionBlock ib =
        ((VmInstructionBlock)
                Compiler.compile(
                    CharStreams.fromString(testProgram.code()), name, vm, module, argNames))
            .ib;
    module.build();
    MethodMemo mMemo = ib.memoForApply();

    // Execute with prep = true to initialize the MethodMemo
    try {
      Value result = run(ib, mMemo, trueValue);
      // Just log and drop the prep result (we only check the result from generated code)
      System.out.format("prep result: %s\n", result);
      tstate.dropValue(result);
    } catch (Vm.RuntimeError e) {
      // Might it be useful to error during prep?  Currently allowed but unused.
      System.out.format("prep failed:\n%s\n", String.join("\n", e.stack()));
      e.close();
    }
    // Log any traces that were executed during prep and discard them
    // (we only check the traces from generated code).
    var unused = getTraces(tracker);
    // Make sure that memory was released properly.
    assertWithMessage("Reference counting error: %s", tracker).that(tracker.allReleased()).isTrue();
    System.out.format("prep resources: %s\n\n", tracker);

    // Now force code generation for the designated method(s).
    // Our Monitor will log the code generator's DebugInfo, but also save it for later checking.
    TestMonitor monitor = new TestMonitor(false);
    vm.scope.codeGenManager.setMonitor(monitor);
    vm.scope.generateCodeForForcedMethods();

    // Execute with prep = false, this time (hopefully) using the generated code
    tstate.bindTo(newTracker());
    String traces;
    try {
      Value result = run(ib, mMemo, falseValue);
      // Executed without errors, so make sure we got what we expected
      String resultAsString = result.toString();
      tstate.dropValue(result);
      System.out.format("result: %s\n", resultAsString);
      // Save and log any traces now (they can be useful for debugging failed tests), but delay
      // checking them until we've checked the result (since if the result is wrong that's the
      // most important thing to know).
      traces = getTraces(tracker);
      assertWithMessage("Expected error, executed OK").that(expectReturn).isTrue();
      assertThat(resultAsString).isEqualTo(cleanValue(expectedResult));
    } catch (Vm.RuntimeError e) {
      // Error during execution, so confirm that was expected
      String stack = String.join("\n", e.stack());
      System.out.format("failed:\n%s\n", stack);
      // See comment above about why we grab (and log) any traces now but only check them later.
      traces = getTraces(tracker);
      assertWithMessage("Unexpected error %s", e).that(expectReturn).isFalse();
      assertWithMessage("Unexpected error %s", e)
          .that(cleanIds(stack))
          .isEqualTo(cleanLines(expectedResult));
      e.close();
    }
    System.out.println("Counters: " + monitor.counters());
    assertThat(tracker.escapeCount()).isEqualTo(escapeCount);
    assertWithMessage("Trace doesn't match")
        .that(cleanIds(cleanLines(traces)))
        .isEqualTo(cleanLines(expectedTraces));

    if (!tracker.allReleased()) {
      // TODO: reenable this assert once we generate proper reference-counting code
      // assertWithMessage("Reference counting error: %s", tracker)
      //     .that(tracker.allReleased()).isTrue();
      System.out.println("Reference counting error: " + tracker);
    }
    assertThat(cleanBlocks(monitor.blocks.toString())).isEqualTo(cleanBlocks(expectedBlocks));
    assertThat(tracker.toString()).isEqualTo(expectedResources);
  }

  /** Returns true if {@code method} is for a function with the given name in the given module. */
  private static boolean isExlined(VmMethod method, String fnName, ModuleBuilder module) {
    return method.function.name.equals(fnName) && method.impl.module() == module.module;
  }

  /**
   * Removes all whitespace at the beginning and end of lines in the given output, and removes all
   * completely blank lines.
   */
  private static String cleanLines(String output) {
    return output.replaceAll(" *\n[ \n]*", "\n").trim();
  }

  private static final Pattern AT_HEX = Pattern.compile("@[0-9a-f]{3,}");

  /**
   * Removes comments and preceding whitespace from the ends of lines in the given output. Replaces
   * 3 or more hex digits following an "@" with "x"s.
   */
  private static String cleanBlocks(String output) {
    String s = output.replaceAll(" *//.*", "").trim();
    return AT_HEX
        .matcher(s)
        .replaceAll(mr -> "@xxxxx".substring(0, Math.min(6, mr.end() - mr.start())));
  }

  /**
   * Replaces unpredictable hex strings following "RThread@" or a future symbol ("□", "⊡", or "⊠")
   * with "xxxx".
   */
  private static String cleanIds(String output) {
    return output.replaceAll("(RThread@|[□⊡⊠])[0-9a-f]+\\b", "$1xxxx");
  }

  /** Removes whitespace at the beginning and end of lines, and replaces newlines with spaces. */
  private static String cleanValue(String output) {
    return output.replaceAll(" *\n *", " ").trim();
  }

  /** Executes the given instruction block, passing {@code arg} as the argument. */
  private Value run(InstructionBlock ib, MethodMemo mMemo, Vm.Value arg) throws Vm.RuntimeError {
    try {
      return ib.applyToArgs(tracker, mMemo, arg);
    } finally {
      // If any async threads are still executing, let them complete before we check anything else.
      assertThat(ForkJoinPool.commonPool().awaitQuiescence(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** Logs the result of {@link ResourceTracker#takeTraces()} and returns it. */
  private static String getTraces(ResourceTracker tracker) {
    String traces = tracker.takeTraces();
    if (!traces.isEmpty()) {
      System.out.println("---\n" + traces);
    }
    return traces;
  }
}
