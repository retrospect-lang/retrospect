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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.CharStreams;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.retrolang.Vm;
import org.retrolang.compiler.Compiler;
import org.retrolang.testing.TestMonitor;
import org.retrolang.testing.TestdataScanner;
import org.retrolang.testing.TestdataScanner.TestProgram;
import org.retrolang.util.Pair;

/**
 * Compiles and executes Retrospect source code from each of the .r8t files in the testdata
 * directory, based on comments in the files.
 */
@RunWith(TestParameterInjector.class)
public class VirtualMachineTest {

  private static final Path TESTDATA = Path.of("src/test/java/org/retrolang/impl/testdata");

  /**
   * Each .r8t file is expected to have source code followed by a comment that begins "{@code /* RUN
   * (...)}". The parentheses enclose argument names (if any) for the code block, with a value for
   * each, followed by RETURNS or ERRORS (e.g. {@code RUN (x=3, y="foo") RETURNS}).
   *
   * <ul>
   *   <li>RETURNS: The following lines contain a printed representation of the expected return
   *       value, and the test passes if the execution returns a matching value.
   *   <li>ERRORS: The following lines contain a printed representation of the expected
   *       RuntimeError, and the test passes if the execution fails with a matching exception.
   * </ul>
   *
   * <p>In either case those lines are terminated by a line containing just "---". If the program
   * contains any trace instructions, the expected output from the traces comes next, again
   * terminated by a line containing just "---". The last line of the comment should look like
   * "allocated=xx/yy", where xx is the number of bytes that are allocated during execution, and yy
   * is the number of objects.
   *
   * <p>RETURNS or ERRORS may optionally be followed by RC_DEBUG to enable reference count debugging
   * while running the test.
   *
   * <p>For convenience a single file may contain multiple source programs, each followed by a RUN
   * comment.
   */
  private static final Pattern COMMENT_PATTERN =
      Pattern.compile("\n/\\* RUN (.*?)\\*/\\n*", Pattern.DOTALL);

  /**
   * A pattern to parse the first line of a RUN comment (beginning immediately after the "/* RUN ").
   */
  private static final Pattern FIRST_LINE_PATTERN =
      Pattern.compile(
          """
          \\((?<args>[^\\)]*)\\) *\
          (LIMIT\\((?<limit>\\d+)\\) *)?\
          (CODEGEN\\((?<codegen>\\d+)\\) *)?\
          (?<returns>RETURNS|ERRORS) *\
          (?<debug>RC_DEBUG *)?\
          \n\
          """);

  @Test
  public void runOne(
      @TestParameter(valuesProvider = AllProgramsProvider.class) TestProgram testProgram) {
    checkNotNull(testProgram.comment(), "No RUN comment found");
    Matcher argsMatcher = FIRST_LINE_PATTERN.matcher(testProgram.comment());
    assertWithMessage("Bad RUN comment").that(argsMatcher.lookingAt()).isTrue();
    // Get the args as a list of (arg, value) pairs.  This is very simplistic (values shouldn't
    // contain "," or "="), but we don't need anything more sophisticated.
    List<Pair<String, String>> args =
        Arrays.stream(splitAndTrim(argsMatcher.group("args")))
            .map(VirtualMachineTest::parseRunArg)
            .collect(Collectors.toList());
    long limit =
        Optional.ofNullable(argsMatcher.group("limit")).map(Long::parseLong).orElse(200_000L);
    boolean expectReturn = argsMatcher.group("returns").equals("RETURNS");
    Optional<Integer> codeGen =
        Optional.ofNullable(argsMatcher.group("codegen")).map(Integer::parseInt);
    boolean rcDebug = argsMatcher.group("debug") != null;
    String expected = testProgram.comment().substring(argsMatcher.end());
    String expectedTraces = "";
    String[] parts = expected.split("\n---\n");
    expected = parts[0];
    int minParts = codeGen.isPresent() ? 3 : 2;
    if (parts.length == minParts + 1) {
      expectedTraces = parts[1];
    } else {
      assertThat(parts).hasLength(minParts);
    }
    String expectedCodeGen = codeGen.isPresent() ? parts[parts.length - 2] : null;
    String expectedResources = parts[parts.length - 1].trim();
    // Compile the program into a new module
    VirtualMachine vm = new VirtualMachine();
    vm.enableDebugging();
    Vm.ModuleBuilder module = vm.newModule("(input)");
    String[] argNames = args.stream().map(p -> p.x).toArray(String[]::new);
    Vm.InstructionBlock insts =
        Compiler.compile(
            CharStreams.fromString(testProgram.code()), testProgram.name(), vm, module, argNames);
    module.build();
    TestMonitor monitor = null;
    if (codeGen.isPresent()) {
      CodeGenManager codeGenManager = vm.scope.codeGenManager;
      codeGenManager.setThresholds(2 * codeGen.get(), codeGen.get());
      codeGenManager.enableCodeGenDebugging();
      monitor = new TestMonitor(true);
      codeGenManager.setMonitor(monitor);
    }
    // Now set up to execute the compiled code, passing any arg values that were given.
    // Use a relatively low maxTraces to make testing easier.
    ResourceTracker tracker = vm.newResourceTracker(limit, /* maxTraces= */ 6, rcDebug);
    // Currently only supports integer-valued arguments
    Vm.Value[] argValues =
        args.stream().map(p -> tracker.asValue(Integer.parseInt(p.y))).toArray(Vm.Value[]::new);
    String traces;
    try (Vm.Value result = insts.applyToArgs(tracker, argValues)) {
      waitForThreadCompletion();
      // Executed without errors, so make sure we got what we expected
      String resultAsString = result.toString();
      System.out.println("result: " + resultAsString);
      // Save and log any traces now (they can be useful for debugging failed tests), but delay
      // checking them until we've checked the result (since if the result is wrong that's the
      // most important thing to know).
      traces = getTraces(tracker);
      assertWithMessage("Expected error, executed OK").that(expectReturn).isTrue();
      assertThat(resultAsString).isEqualTo(cleanValue(expected));
    } catch (Vm.RuntimeError e) {
      waitForThreadCompletion();
      // Error during execution, so confirm that was expected
      String stack = String.join("\n", e.stack());
      System.out.format("failed:\n%s\n", stack);
      // See comment above about why we grab (and log) any traces now but only check them later.
      traces = getTraces(tracker);
      assertWithMessage("Unexpected error %s", e).that(expectReturn).isFalse();
      assertWithMessage("Unexpected error %s", e)
          .that(cleanIds(stack))
          .isEqualTo(cleanLines(expected));
      e.close();
    }
    assertWithMessage("Trace doesn't match")
        .that(cleanIds(cleanLines(traces)))
        .isEqualTo(cleanLines(expectedTraces));
    if (codeGen.isPresent()) {
      assertThat(vm.scope.codeGenDebugging().takeLog()).isEqualTo(expectedCodeGen.trim());
    }
    // Now everything should have been released
    assertWithMessage("Reference counting error: %s", tracker).that(tracker.allReleased()).isTrue();
    // Confirm that memory use matched expectations
    String layouts = vm.getDebuggingSummary();
    String actualResources = tracker + (layouts.isEmpty() ? "" : "\n" + layouts);
    if (!checkForRanges(actualResources, expectedResources)) {
      assertThat(actualResources).isEqualTo(expectedResources);
    }
  }

  /** If any async threads are still executing, let them complete before we check anything else. */
  private static void waitForThreadCompletion() {
    assertThat(ForkJoinPool.commonPool().awaitQuiescence(2, TimeUnit.SECONDS)).isTrue();
  }

  private static String getTraces(ResourceTracker tracker) {
    String traces = tracker.takeTraces();
    if (!traces.isEmpty()) {
      System.out.println("---\n" + traces);
    }
    return traces;
  }

  private static final Pattern COMMA = Pattern.compile(" *, *");

  private static String[] splitAndTrim(String s) {
    s = s.trim();
    return s.isEmpty() ? new String[0] : COMMA.split(s);
  }

  private static final Pattern EQUAL = Pattern.compile(" *= *");

  private static Pair<String, String> parseRunArg(String arg) {
    String[] parts = EQUAL.split(arg);
    assertThat(parts).hasLength(2);
    return new Pair<>(parts[0], parts[1]);
  }

  public static final class AllProgramsProvider extends TestdataScanner {
    public AllProgramsProvider() {
      super(TESTDATA, COMMENT_PATTERN);
    }
  }

  /**
   * Removes all whitespace at the beginning and end of lines in the given output, and removes all
   * completely blank lines.
   */
  private static String cleanLines(String output) {
    return output.replaceAll(" *\n[ \n]*", "\n").trim();
  }

  /** Removes whitespace at the beginning and end of lines, and replaces newlines with spaces. */
  private static String cleanValue(String output) {
    return output.replaceAll(" *\n *", " ").trim();
  }

  /**
   * Replaces unpredictable hex strings following "RThread@" or a future symbol ("□", "⊡", or "⊠")
   * with "xxxx".
   */
  private static String cleanIds(String output) {
    return output.replaceAll("(RThread@|[□⊡⊠])[0-9a-f]+\\b", "$1xxxx");
  }

  // Uses the unicode "Two Dot Leader" character as a separator (to avoid spurious matches), so
  // you'll probably need to copy & paste it.
  private static final Pattern NUMERIC_RANGE = Pattern.compile("(\\d+)‥(\\d+)");

  /**
   * If {@code expected} contains ranges (i.e. pairs of non-negative integers separated by "‥"
   * (that's a single unicode character)), verifies that {@code actual} matches the rest of the
   * string and has in-range integers in those places.
   *
   * <p>If {@code expected} contains no ranges, or has ranges but {@code actual} doesn't match,
   * returns false; otherwise returns true. The caller is expected to handle a false return by
   * asserting that the two strings are equals, which both handles the no-ranges case and produces a
   * useful failure message when there are ranges.
   */
  private static boolean checkForRanges(String actual, String expected) {
    Matcher matcher = NUMERIC_RANGE.matcher(expected);
    if (!matcher.find()) {
      // A simple string comparison will suffice.
      return false;
    }
    List<Pair<Integer, Integer>> ranges = new ArrayList<>();
    StringBuilder builder = new StringBuilder();
    int prevEnd = 0;
    do {
      // Chars before the range must match exactly.
      builder.append(Pattern.quote(expected.substring(prevEnd, matcher.start())));
      // The range is replaced with a regex that matches any non-negative int...
      builder.append("(\\d+)");
      // ... and we save the range bounds for the follow-up check.
      ranges.add(
          new Pair<>(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
      prevEnd = matcher.end();
    } while (matcher.find());
    // Chars before the final range must match exactly.
    builder.append(Pattern.quote(expected.substring(prevEnd)));
    String pattern = builder.toString();
    Matcher matcher2 = Pattern.compile(pattern).matcher(actual);
    if (!matcher2.matches()) {
      // Returning false will cause a simple string comparison, which will fail the test and print
      // the actual & expected strings; we can add this clue for debugging.
      System.out.format("Failed range match:\n  %s\nvs\n  %s\n", actual, pattern);
      return false;
    }
    // The pattern matched; now we need to confirm that each of the integers was in range.
    for (int i = 0; i < ranges.size(); i++) {
      Pair<Integer, Integer> range = ranges.get(i);
      int found = Integer.parseInt(matcher2.group(i + 1));
      if (found < range.x || found > range.y) {
        System.out.format("Value %s (%s) not in %s..%s\n", i, found, range.x, range.y);
        return false;
      }
    }
    return true;
  }
}
